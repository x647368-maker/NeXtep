"""Source integration guard, not a runtime/visual test."""
from pathlib import Path
import sys
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path('.')
s=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt').read_text()
a=s.index('val workspacePreviewFallback:');b=s.index('val renderThreadFallback:',a)
assert 'prepareWorkspaceSwap(' in s[a:b], 'fallback bypasses main capture and forces source-only'
a=s.index('private fun prepareWorkspaceSwap(');b=s.index('private fun tryLiveSurfaceExchange(',a)
f=s[a:b]
assert 'swapCapture.request(' in f and 'capturePreview(task,' in f and 'captureDisplayPreview(' in f
assert 'mainTaskPresenter.currentTaskId()==task' in f and 'slotCoordinator?.slotStates().orEmpty()==states' in f
assert 'animateSwapPrepared(sourceIndex,targetIndex,drag,move,effectiveOnFinished,it,' in f
assert 'onCancel=' in f and 'effectiveOnFinished()' in f
assert 'tryConcurrentLiveSwap' not in f and 'tryTransientTapSwap' not in f
s=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageLiveSurfaceExchange.kt').read_text()
assert 'ackClaimed.compareAndSet(false,true)) StageAckDispatch.dispatch(' in s
assert 'Looper.myLooper()==ui.looper' in s
assert 'rejectUnconfirmedVisibleMove' in s and 'sourceIdentityReadyNow()' in s
print('PASS fallback capture and GPU ACK integration guards (not visual acceptance)')
