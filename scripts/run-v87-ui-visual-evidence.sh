#!/usr/bin/env bash
set -euo pipefail
mkdir -p /tmp/atomic-ui-visual visual-results
keytool -genkeypair -keystore /tmp/atomic-ui-visual/test.p12 -storetype PKCS12 -storepass android -keypass android -alias ui-visual -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Disposable UI Visual Evidence'
signer="$ANDROID_HOME/build-tools/36.0.0/apksigner"
target_apk=$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)
test_apk=$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -print -quit)
test -s "$target_apk" && test -s "$test_apk"
for pair in "$target_apk:target" "$test_apk:tests"; do
  original="${pair%:*}"; name="${pair##*:}"
  "$signer" sign --ks /tmp/atomic-ui-visual/test.p12 --ks-pass pass:android --out "/tmp/atomic-ui-visual/$name.apk" "$original"
  adb install -t "/tmp/atomic-ui-visual/$name.apk"
done
adb shell wm size 1272x2772
adb shell wm density 512
adb shell input keyevent 82
adb logcat -c
adb logcat -v threadtime -s StageDiagnosticTest:I '*:S' | tee visual-results/phase-live.txt &
phase_logger=$!
trap 'kill "$phase_logger" 2>/dev/null || true' EXIT
set +e
timeout --signal=TERM --kill-after=20s 260s adb shell am instrument -w -e uiVisualOnly true io.github.x647368maker.atomicworkbench.test/io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation | tee ui-visual-instrumentation.txt
status=${PIPESTATUS[0]}
set -e
echo "UI visual instrumentation process status=$status"
kill "$phase_logger" 2>/dev/null || true
adb exec-out run-as io.github.x647368maker.atomicworkbench tar -C files/visual-regression -cf - . > /tmp/atomic-ui-visual/results.tar || true
if [ -s /tmp/atomic-ui-visual/results.tar ]; then tar -xf /tmp/atomic-ui-visual/results.tar -C visual-results || true; fi
adb logcat -d > visual-results/logcat.txt
test "$status" = 0
grep -Fx 'UI_VISUAL_EVIDENCE_PASS' ui-visual-instrumentation.txt
test -s visual-results/ui-visual-evidence-results.txt
