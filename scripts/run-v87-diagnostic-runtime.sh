#!/usr/bin/env bash
set -euo pipefail
# Fresh disposable CI emulator only. No original production signing secrets.
mkdir -p /tmp/atomic-diagnostic diagnostic-results
keytool -genkeypair -keystore /tmp/atomic-diagnostic/test.p12 -storetype PKCS12 -storepass android -keypass android -alias diagnostic -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Disposable Diagnostics Test'
signer="$ANDROID_HOME/build-tools/36.0.0/apksigner"
target_apk=$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)
test_apk=$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -print -quit)
test -s "$target_apk" && test -s "$test_apk"
for pair in "$target_apk:target" "$test_apk:tests"; do
  original="${pair%:*}"; name="${pair##*:}"
  "$signer" sign --ks /tmp/atomic-diagnostic/test.p12 --ks-pass pass:android --out "/tmp/atomic-diagnostic/$name.apk" "$original"
  adb install -t "/tmp/atomic-diagnostic/$name.apk"
done
adb shell wm size 1272x2772
adb shell wm density 512
adb shell input keyevent 82
adb logcat -c
# Stream immediate checkpoint evidence even if instrumentation never returns.
adb logcat -v threadtime -s StageDiagnosticTest:I '*:S' | tee diagnostic-results/phase-live.txt &
phase_logger=$!
trap 'kill "$phase_logger" 2>/dev/null || true' EXIT
set +e
timeout --signal=TERM --kill-after=20s 660s adb shell am instrument -w -e diagnosticOnly true io.github.x647368maker.atomicworkbench.test/io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation | tee diagnostic-instrumentation.txt
status=${PIPESTATUS[0]}
set -e
echo "Diagnostic instrumentation process status=$status"
kill "$phase_logger" 2>/dev/null || true
adb exec-out run-as io.github.x647368maker.atomicworkbench tar -C files/visual-regression -cf - . > /tmp/atomic-diagnostic/results.tar || true
if [ -s /tmp/atomic-diagnostic/results.tar ]; then tar -xf /tmp/atomic-diagnostic/results.tar -C diagnostic-results || true; fi
adb logcat -d > diagnostic-results/logcat.txt
test "$status" = 0
grep -Fx 'DIAGNOSTIC_RUNTIME_PASS' diagnostic-instrumentation.txt
test -s diagnostic-results/diagnostic-ui-results.txt
