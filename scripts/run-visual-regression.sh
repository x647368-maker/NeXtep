#!/usr/bin/env bash
set -euo pipefail
# Disposable emulator-only key. Neither this key nor these APKs are deliverables.
mkdir -p /tmp/atomic-visual
keytool -genkeypair -keystore /tmp/atomic-visual/test.p12 -storetype PKCS12 -storepass android -keypass android -alias visual -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Emulator Test Only'
signer="$ANDROID_HOME/build-tools/36.0.0/apksigner"
target_apk=$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)
test_apk=$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -print -quit)
test -s "$target_apk" && test -s "$test_apk"
for pair in "$target_apk:target" "$test_apk:tests"; do
  original="${pair%:*}"; name="${pair##*:}"
  "$signer" sign --ks /tmp/atomic-visual/test.p12 --ks-pass pass:android --out "/tmp/atomic-visual/$name.apk" "$original"
  adb install -r -t "/tmp/atomic-visual/$name.apk"
done
adb shell wm size 1272x2772
adb shell wm density 512
adb shell settings put global hidden_api_policy 1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
# Exercise real app compositor animations, while system window/transition animations stay disabled.
adb shell settings put global animator_duration_scale 1
adb shell input keyevent 82
adb logcat -c
set +e
timeout --signal=TERM --kill-after=20s 720s adb shell am instrument -w io.github.x647368maker.atomicworkbench.test/io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation | tee visual-instrumentation.txt
set -e
adb logcat -d -s NeXtep > virtual-display-log.txt
mkdir -p visual-results
# Capture every diagnostic even on assertion failure; no empty placeholder files.
adb exec-out run-as io.github.x647368maker.atomicworkbench tar -C files/visual-regression -cf - . > visual-results.tar
if [ -s visual-results.tar ]; then tar -xf visual-results.tar -C visual-results; fi
adb shell dumpsys window > visual-results/final-window.txt
adb shell dumpsys statusbar > visual-results/final-statusbar.txt
adb logcat -d > visual-results/logcat.txt

grep -F 'HWUI_RENDER_PASS' visual-instrumentation.txt
