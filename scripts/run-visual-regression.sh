#!/usr/bin/env bash
set -euo pipefail
# Disposable emulator-only key. Neither this key nor these APKs are deliverables.
mkdir -p /tmp/atomic-visual
keytool -genkeypair -keystore /tmp/atomic-visual/test.p12 -storetype PKCS12 -storepass android -keypass android -alias visual -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Emulator Test Only'
signer="$ANDROID_HOME/build-tools/36.0.0/apksigner"
for pair in 'app/build/outputs/apk/debug/app-debug.apk:target' 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk:tests'; do
  original="${pair%:*}"; name="${pair##*:}"
  "$signer" sign --ks /tmp/atomic-visual/test.p12 --ks-pass pass:android --out "/tmp/atomic-visual/$name.apk" "$original"
  adb install -r -t "/tmp/atomic-visual/$name.apk"
done
adb shell wm size 1264x2780
adb shell wm density 512
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82
adb shell am instrument -w io.github.x647368maker.atomicworkbench.test/io.github.x647368maker.atomicworkbench.visual.LivePreviewInstrumentation | tee visual-instrumentation.txt
grep -F 'HWUI_RENDER_PASS' visual-instrumentation.txt
mkdir -p visual-results
for name in 095-parent-only.png 096-live-clip.png 096-top-four-live.png 096-side-four-live.png render-results.txt; do
  adb exec-out run-as io.github.x647368maker.atomicworkbench cat "files/visual-regression/$name" > "visual-results/$name"
done
