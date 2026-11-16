#!/usr/bin/env bash
set -euo pipefail
# Test-only emulator signing; never use or export the project's signing identity.
label="$1"
apk_root="$2"
out="$(realpath -m "$3")"
mkdir -p "$out"
pkg=io.github.x647368maker.atomicworkbench
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
keytool -genkeypair -keystore "$tmp/test.p12" -storetype PKCS12 -storepass android -keypass android -alias visual -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Disposable Emulator Performance Test'
signer="$ANDROID_HOME/build-tools/36.0.0/apksigner"
target="$(find "$apk_root/debug" -name '*.apk' -print -quit)"
tests="$(find "$apk_root/androidTest/debug" -name '*.apk' -print -quit)"
test -s "$target" && test -s "$tests"
sha256sum "$target" "$tests" > "$out/input-apks.sha256"
adb uninstall "$pkg.test" >/dev/null 2>&1 || true
adb uninstall "$pkg" >/dev/null 2>&1 || true
for pair in "$target:target" "$tests:tests"; do
  path="${pair%:*}"; name="${pair##*:}"
  "$signer" sign --ks "$tmp/test.p12" --ks-pass pass:android --out "$tmp/$name.apk" "$path"
  adb install -t "$tmp/$name.apk"
done
adb shell wm size 1272x2772
adb shell wm density 512
adb shell settings put global hidden_api_policy 1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 1
adb shell input keyevent 82
# Workload test is deliberately screen-on. No stay-awake/wake permission changes.
{
  echo "candidate=$label"
  adb shell getprop ro.build.fingerprint
  adb shell getprop ro.build.version.sdk
  adb shell wm size
  adb shell wm density
  adb shell settings get global animator_duration_scale
  adb shell cat /proc/cpuinfo
  uname -a
  cat /proc/loadavg
} > "$out/runner-device.txt"
adb shell dumpsys power > "$out/power-before.txt"
adb shell dumpsys SurfaceFlinger --list > "$out/surfaces-before.txt"
adb logcat -c
set +e
timeout --signal=TERM --kill-after=20s 720s adb shell am instrument -w "$pkg.test/io.github.x647368maker.atomicworkbench.visual.PerformanceEvidenceInstrumentation" 2>&1 | tee "$out/instrumentation.txt"
status=$?
set -e
# Always preserve real partial results, even with timeout or failed assertion.
if adb exec-out run-as "$pkg" tar -C files/performance-evidence -cf - . > "$tmp/raw.tar"; then
  tar -xf "$tmp/raw.tar" -C "$out"
else
  echo "Runtime did not create a readable evidence directory" > "$out/missing-runtime-data.txt"
fi
adb shell dumpsys power > "$out/power-after.txt" || true
adb shell dumpsys SurfaceFlinger --list > "$out/surfaces-after.txt" || true
adb shell dumpsys meminfo "$pkg" > "$out/memory-after.txt" || true
adb logcat -d > "$out/logcat.txt" || true
cat /proc/loadavg > "$out/runner-load-after.txt"
printf '%s\n' "$status" > "$out/instrumentation-exit.txt"
test "$status" = 0
grep -F 'PERFORMANCE_EVIDENCE_PASS:' "$out/instrumentation.txt"
grep -F 'INSTRUMENTATION_CODE: -1' "$out/instrumentation.txt"
