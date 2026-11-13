#!/usr/bin/env bash
set -euo pipefail
# Dedicated API37 production SurfaceFlinger mirror test. This is NOT the
# legacy screenshot/HWUI suite and MUST fail, not SKIP, on mirror unavailability.
mkdir -p /tmp/atomic-native visual-results
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
