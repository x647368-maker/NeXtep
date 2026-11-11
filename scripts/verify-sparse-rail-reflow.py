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
assert 'withEndAction' in animator and 'active.remove(view)' in animator
assert 'sparseRailMotion.cancel()' in host
assert 'sparseRailMotion.isAnimating(this)' in host
assert 'if (countChanged)' in host and 'sparseRailMotion.animate(' in host
for bad in ['setRepeating(', 'setExact(', 'WakeLock', 'Thread.sleep(', 'HandlerThread',
            'SurfaceControl.Transaction', 'capturePreview(', 'setSurface(', '.resize(',
            'updateViewLayout(', 'postDelayed(', 'ValueAnimator.of', 'addUpdateListener(']:
    assert bad not in animator, bad
assert 'requestLayout()' not in animator
assert 'onSurfaceTextureSizeChanged' not in animator
print('PASS: 190ms one-shot compositor side-card reflow on count change; power saver and system animation settings honored; no background schedule')
