#!/usr/bin/env bash
set -euo pipefail
# Dedicated API37 production SurfaceFlinger mirror test. This is NOT the
# legacy screenshot/HWUI suite and MUST fail, not SKIP, on mirror unavailability.
mkdir -p /tmp/atomic-native visual-results

collect_flight_evidence_on_failure() {
  local result=$?
  if (( result != 0 )); then
    adb logcat -d > native-flight-logcat.txt || true
    local archive=/tmp/atomic-native/failed-flight-results.tar
    if adb exec-out run-as io.github.x647368maker.atomicworkbench \
        tar -C files/visual-regression -cf - . > "$archive" && [[ -s "$archive" ]]; then
      tar -xf "$archive" -C visual-results || true
    fi
    find visual-results -type f \( -name 'native-flight-runtime.txt' -o -name 'native-flight-trace.txt' \) \
      -print -exec tail -60 {} \; || true
  fi
  return "$result"
}
trap collect_flight_evidence_on_failure EXIT

# Android37 google_apis_ps16k starts GMS/background package initialization
# after sys.boot_completed. In #1523 the lowmemorykiller killed the **test
# app process** (SIGKILL/oom_score_adj=700) immediately after the first
# real-mirror pass while multiple system apps were also killed. Treat that as
# an emulator-environment failure rather than an app crash or a green test.
# Do not lower the physical pixel gate or disable any test assertion.
wait_for_guest_memory() {
  local phase="$1" attempt available stable=0
  for attempt in $(seq 1 24); do
    available=$(adb shell cat /proc/meminfo 2>/dev/null | awk '$1=="MemAvailable:" {print $2}' | tr -d '\r')
    if [[ "$available" =~ ^[0-9]+$ ]] && (( available >= 950000 )); then
      stable=$((stable+1))
    else
      stable=0
    fi
    echo "API37 guest memory phase=$phase sample=$attempt available_kb=${available:-unknown} stable=$stable"
    if ((stable>=3)); then return 0; fi
    sleep 4
  done
  echo "::error::API37 guest did not recover >=950000KB available memory for three consecutive checks; compositor evidence is inconclusive"
  adb shell cat /proc/meminfo | head -18 || true
  adb shell dumpsys activity oom | tail -60 || true
  return 75
}
# #1532 screenshot failed all-black with only the gesture handle.
# The device log showed lockscreenVisibility:true and a keyguard sleep token
# during full-resolution pixel capture. This is a guest-screen condition,
# not a valid failure attribution to the wallpaper GPU layer. Keep the
# emulator awake/unlocked at each *fixture boundary*; never weaken pixels.
ensure_guest_awake() {
  local phase="$1" power
  adb shell svc power stayon true
  adb shell settings put system screen_off_timeout 2147483647
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  adb shell input keyevent KEYCODE_MENU
  power="$(adb shell dumpsys power)"
  if ! grep -Eq 'mWakefulness(Raw)?=Awake' <<< "$power"; then
    echo "::error::API37 guest is not awake at $phase; do not classify black compositor pixels as a wallpaper regression"
    printf '%s\n' "$power" | grep -E 'Wakefulness|Display Power' | head -12 || true
    return 76
  fi
  echo "API37 guest awake and keyguard dismissal requested before $phase"
}

keytool -genkeypair -keystore /tmp/atomic-native/test.p12 \
  -storetype PKCS12 -storepass android -keypass android -alias visual \
  -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Native SF Mirror Test'
signer="$ANDROID_HOME/build-tools/36.0.0/apksigner"
target_apk=$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)
test_apk=$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -print -quit)
test -s "$target_apk" && test -s "$test_apk"
for pair in "$target_apk:target" "$test_apk:tests"; do
  original="${pair%:*}"; name="${pair##*:}"
  "$signer" sign --ks /tmp/atomic-native/test.p12 --ks-pass pass:android \
    --out "/tmp/atomic-native/$name.apk" "$original"
  adb install -r -t "/tmp/atomic-native/$name.apk"
done
adb shell wm size 1272x2772
adb shell wm density 512
adb shell settings put global hidden_api_policy 1
adb shell settings put global animator_duration_scale 1
# Let background package setup settle before expensive full-screen GPU
# screenshots. This applies only to the isolated API37 CI emulator, not the
# user's SystemUI process or any production background task.
sleep 25
wait_for_guest_memory before_mirror
ensure_guest_awake before_mirror
adb logcat -c
# The dedicated test has a hard 180-second wall clock limit. Never label a
# hung or skipped system compositor as a PASS.
set +e
timeout --signal=TERM --kill-after=10s 180s adb shell am instrument \
  -e nativeMirrorOnly true -w \
  io.github.x647368maker.atomicworkbench.test/io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation \
  | tee native-mirror-instrumentation.txt
status=${PIPESTATUS[0]}
set -e
adb logcat -d > native-mirror-logcat.txt || true
adb exec-out run-as io.github.x647368maker.atomicworkbench \
  tar -C files/visual-regression -cf - . > native-mirror-results.tar || true
if [ -s native-mirror-results.tar ]; then
  tar -xf native-mirror-results.tar -C visual-results || true
fi
if [ "$status" -ne 0 ]; then
  echo "::error::API37 mirror instrumentation failed (exit=$status)"
  exit "$status"
fi
grep -F 'NATIVE_MIRROR_PASS' native-mirror-instrumentation.txt
grep -F 'PASS API37 SurfaceFlinger live dual-root mirror' native-mirror-instrumentation.txt
test -s visual-results/native-api37-dual-live-surface-mirrors.png
echo 'PASS dual real GPU mirror composition and live producer update'
# #1523's second instrumentation process was killed by guest LMKD within
# seconds of this PASS. Require another stable memory window before launch.
sleep 15
wait_for_guest_memory before_flight
ensure_guest_awake before_flight

# Exercise the complete production dual-card flight engine, not only the
# mirror primitive. These use synthetic non-task roots so migration must be
# rejected; they prove real compositor pixels, terminal commits, interruption,
# rapid successor isolation and handle/mask cleanup, NOT OEM task handoff.
adb shell settings put global animator_duration_scale 3
set +e
timeout --signal=TERM --kill-after=10s 180s adb shell am instrument -e nativeFlightOnly true -e requireDefaultAnimation true -w \
  io.github.x647368maker.atomicworkbench.test/io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation \
  | tee native-flight-instrumentation.txt
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
  echo "::error::API37 live dual-card flight instrumentation failed (exit=$status)"
  exit "$status"
fi
grep -Fx 'PRODUCTION_ANIMATION_GATE_PASS' native-flight-instrumentation.txt
grep -F 'NATIVE_FLIGHT_PASS' native-flight-instrumentation.txt
ensure_guest_awake before_paired

set +e
timeout --signal=TERM --kill-after=10s 180s adb shell am instrument -e pairedFlightOnly true -w \
  io.github.x647368maker.atomicworkbench.test/io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation \
  | tee paired-flight-instrumentation.txt
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
  echo "::error::API37 paired live flight instrumentation failed (exit=$status)"
  exit "$status"
fi
grep -F 'PAIRED_FLIGHT_PASS' paired-flight-instrumentation.txt
ensure_guest_awake before_adaptive

# Mixed-aspect covered regression is independent of the old contain fixture.
set +e
timeout --signal=TERM --kill-after=10s 180s adb shell am instrument -e pairedFlightOnly true -e adaptiveCoveredFlight true -w \
  io.github.x647368maker.atomicworkbench.test/io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation \
  | tee adaptive-flight-instrumentation.txt
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
  echo "::error::API37 adaptive covered flight instrumentation failed (exit=$status)"
  exit "$status"
fi
grep -F 'ADAPTIVE_COVERED_FLIGHT_PASS' adaptive-flight-instrumentation.txt

adb logcat -d > native-flight-logcat.txt
adb exec-out run-as io.github.x647368maker.atomicworkbench \
  tar -C files/visual-regression -cf - . > /tmp/atomic-native/all-flight-results.tar
mkdir -p visual-results
tar -xf /tmp/atomic-native/all-flight-results.tar -C visual-results
test -s visual-results/native-flight-moving-mid.png
test -s visual-results/native-flight-clean-mid.png
test -s visual-results/paired-flight/native-flight-first-moving-mid.png
test -s visual-results/paired-flight/native-flight-clean-host-detach.png
test -s visual-results/adaptive-flight/native-flight-first-moving-mid.png
test -s visual-results/adaptive-flight/native-flight-moving-stale.png
test -s visual-results/adaptive-flight/native-flight-clean-host-detach.png
grep -F 'paired_covered_adaptive bound=' visual-results/adaptive-flight/native-flight-trace.txt
# Export bounded aggregated timing rows into CI logs as well as the raw artifacts.
# This is evidence availability, not a synthetic performance threshold.
python3 - <<'PY'
import re
from pathlib import Path
for folder in (Path("visual-results"), Path("visual-results/paired-flight"), Path("visual-results/adaptive-flight")):
    trace = (folder / "native-flight-trace.txt").read_text()
    totals = re.findall(r"gpu_submit frames=(\d+) max_us=(\d+) over_8333us=(\d+) over_16667us=(\d+)", trace)
    split = re.findall(r"gpu_parts n=(\d+) prep_us=(\d+) apply_us=(\d+) prep8=(\d+) apply8=(\d+)", trace)
    skips = re.findall(r"gpu_unchanged_skipped=(\d+)", trace)
    assert totals and len(totals) == len(split) == len(skips), f"Missing matching submission timing rows in {folder}"
    for total, parts, skipped in zip(totals, split, skips):
        n, peak, over120, over60 = map(int, total)
        count, prep, apply, prep_over, apply_over = map(int, parts)
        assert n > 0 and count == n and 0 <= over60 <= over120 <= n, (total, parts)
        assert 0 <= prep <= peak and 0 <= apply <= peak, (total, parts)
        assert 0 <= prep_over <= over120 and 0 <= apply_over <= over120, (total, parts)
        print(f"GPU submit {folder}: frames={n} max_us={peak} prepare_max_us={prep} apply_max_us={apply} over_8333us={over120} prepare_over8333={prep_over} apply_over8333={apply_over} unchanged_skipped={int(skipped)}")
PY
echo 'PASS API37 production live GPU flight, paired flight, mid-flight cancellation, stale-owner terminal cleanup and host-detach cleanup'
