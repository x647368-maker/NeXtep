#!/usr/bin/env python3
"""Prevent user-armed animation from becoming an always-on battery/CPU cost."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
files=root/'app/src/main/kotlin/io/github/lujinxin/nextep'
policy=(files/'workspace/StageSparseRailReflowPolicy.kt').read_text()
animator=(files/'systemui/StageSparseRailReflowAnimator.kt').read_text()
host=(files/'systemui/NeXtepWindowController.kt').read_text()
assert 'const val DURATION_MS = 190L' in policy
assert 'oldCount != newCount' in policy
assert 'view.scaleX = start.uniformScale' in animator and 'view.scaleY = start.uniformScale' in animator
assert 'isPowerSaveMode' in animator and 'areAnimatorsEnabled' in animator
# Universal motion, but legacy device-limited *sizing* remains untouched.
# A removed guard must not also remove the power, compositor or frame safety rules.
assert 'private val trialDevice: Boolean = true' in animator
assert 'trialDevice &&' in animator
assert 'before.railAtTop == now.railAtTop' in policy
assert 'before.screenWidth == now.screenWidth' in policy
assert 'before.screenHeight == now.screenHeight' in policy
assert 'before.displayRotation == now.displayRotation' in policy
assert 'before.navigationInsets == now.navigationInsets' in policy
assert 'abs(sx - sy) > .045f' in policy
assert 'if (scale !in .55f..1.6f) return null' in policy
assert '!StageDisplayPolicy.wideTablet(' not in policy
assert '!StageDisplayPolicy.unfoldedNearSquare(' not in policy
assert 'withEndAction' in animator and 'active.remove(view)' in animator
assert 'sparseRailMotion.cancel()' in host
assert 'sparseRailMotion.isAnimating(this)' in host
assert 'if (countChanged)' in host and 'sparseRailMotion.animate(' in host
assert 'allowPhoneSparse = io.github.x647368maker.atomicworkbench.workspace.StageSparseRailReflowPolicy' in host
assert '.trialModel(android.os.Build.MODEL)' in host
live=(files/'workspace/StageLiveCardLayout.kt').read_text()
assert 'allowPhoneSparse: Boolean = false' in live
assert 'if (allowPhoneSparse) StageSparseSideCardPolicy.fit' in live
for bad in ['setRepeating(', 'setExact(', 'WakeLock', 'Thread.sleep(', 'HandlerThread',
            'SurfaceControl.Transaction', 'capturePreview(', 'setSurface(', '.resize(',
            'updateViewLayout(', 'postDelayed(', 'ValueAnimator.of', 'addUpdateListener(']:
    assert bad not in animator, bad
assert 'requestLayout()' not in animator
assert 'onSurfaceTextureSizeChanged' not in animator
print('PASS: geometry-gated 190ms one-shot live-card reflow across supported shapes; legacy sizing frozen; power saver, system animation settings and no background schedule enforced')
