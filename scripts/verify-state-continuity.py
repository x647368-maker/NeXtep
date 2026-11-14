#!/usr/bin/env python3
"""Source boundaries, not device playback tests. Fail if passive paths regain side effects."""
from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1];k=root/'app/src/main/kotlin/io/github/lujinxin/nextep'

# Only exact reviewed performance/animation fragments may differ. All task moves,
# geometry formulas, crop/input writers and the rest of each file remain frozen.
import hashlib
# PR70: exact approved diagnostic-only differences, reversed before every
# prior immutable source contract. Unknown edits still fail the SHA checks.
pr70_review=json.loads((root/'scripts/reviewed-pr70-unified-fault-report-0.9.46.json').read_text())['files']
def undo_pr70(path,data):
 if path not in pr70_review:return data
 spec=pr70_review[path]
 assert hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()==spec['reviewed_blob_sha1'],path
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr70_review:
 undo_pr70(reviewed_path,(root/reviewed_path).read_bytes())

# PR68: read-only performance/mirror trace. Reverse exact SHA-reviewed
# differences before checking PR67 and every earlier frozen source.
pr68_review=json.loads((root/'scripts/reviewed-pr68-swap-trace-0.9.45.json').read_text())['files']
def undo_pr68(path,data):
 if path not in pr68_review:return data
 spec=pr68_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr68_review:
 undo_pr68(reviewed_path,undo_pr70(reviewed_path,(root/reviewed_path).read_bytes()))

# Guard the non-invasive instrumented entry points and privacy properties.
_pr68=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/logging/StageSwapTraceBuffer.kt').read_text()
assert 'maxRecords: Int = 5' in _pr68 and 'maxEvents: Int = 28' in _pr68
assert 'fun begin(operation: String)' in _pr68
assert 'fun finish(result: String, expectedId: Long? = null)' in _pr68
assert not any(word in _pr68 for word in ('Bitmap', 'captureLayers', 'FileOutputStream', 'HttpURLConnection'))
_pr68_native=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageLiveSurfaceExchange.kt').read_text()
assert 'main_transform:' not in _pr68_native or 'describeOwnedMainPresentation(' in _pr68_native
assert 'real_move_ack@' in _pr68_native and 'handoff_' in _pr68_native

# PR67: restore reviewed ColorOS rail-collision regression repair before
# applying every earlier independent task/geometry/source freeze boundary.
pr67_review=json.loads((root/'scripts/reviewed-pr67-restore-animation-0.9.44.json').read_text())['files']
def undo_pr67(path,data):
 if path not in pr67_review:return data
 spec=pr67_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr67_review:
 undo_pr67(reviewed_path,undo_pr68(reviewed_path,undo_pr70(reviewed_path,(root/reviewed_path).read_bytes())))

_pr67_geometry=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageLiveSurfaceFlightGeometry.kt').read_text()
_pr67_controller=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt').read_text()
assert 'fun intersectionArea(' in _pr67_geometry
assert 'val baseline=otherSlots.map' in _pr67_geometry
assert 'railLeftRight = small.cx < main.left' in _pr67_geometry
assert _pr67_controller.count('!is SlotState.Occupied') >= 2
assert _pr67_controller.count('otherSlotBounds=slotWindows.mapIndexedNotNull') == 2

# PR66: all rail/collision code is reversible before earlier frozen sources.
pr66_review=json.loads((root/'scripts/reviewed-pr66-rail-corridor-0.9.43.json').read_text())['files']
def undo_pr66(path,data):
 if path not in pr66_review:return data
 spec=pr66_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr66_review:
 undo_pr66(reviewed_path,undo_pr67(reviewed_path,undo_pr68(reviewed_path,undo_pr70(reviewed_path,(root/reviewed_path).read_bytes()))))

# Both live cards must be rejected if either may cover another occupied slot.
_pr66_geom=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageLiveSurfaceFlightGeometry.kt').read_text()
_pr66_live=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageLiveSurfaceExchange.kt').read_text()
_pr66_controller=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt').read_text()
assert 'fun corridorAvoidsOtherSlots(' in _pr66_geom
assert 'return markUnavailable("rail_corridor_blocked")' in _pr66_live
assert _pr66_controller.count('otherSlotBounds=slotWindows.mapIndexedNotNull') == 2

# PR65: restore only the reviewed asynchronous layout-wait patch
# before PR64 and every older immutable task/geometry audit.
pr65_review=json.loads((root/'scripts/reviewed-pr65-nonblocking-layout-0.9.42.json').read_text())['files']
def undo_pr65(path,data):
 if path not in pr65_review:return data
 spec=pr65_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr65_review:
 undo_pr65(reviewed_path,undo_pr66(reviewed_path,undo_pr67(reviewed_path,undo_pr68(reviewed_path,undo_pr70(reviewed_path,(root/reviewed_path).read_bytes())))))


# Normal two-task exchange must yield while observing OEM task display status.
# These are source contracts, not evidence of real-device smoothness.
_pr64_coordinator=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/display/SlotTaskCoordinator.kt').read_text()
_pr64_async=_pr64_coordinator.split('private fun moveTaskToDisplayNonBlocking(',1)[1].split('private fun moveTaskToDisplayAndWait(',1)[0]
assert 'SystemClock.sleep(' not in _pr64_async
assert 'exchangeHandler.postDelayed(' in _pr64_async
assert 'moveTaskToDisplayNonBlocking(slotTask.taskId, Display.DEFAULT_DISPLAY)' in _pr64_coordinator
assert 'moveTaskToDisplayNonBlocking(mainTask.taskId, occupied.displayId)' in _pr64_coordinator
_pr64_session=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/display/ExchangeSession.kt').read_text()
assert 'driver.promoteAsync' in _pr64_session and 'driver.demoteAndVerifyAsync' in _pr64_session

_pr65_source=(root/'app/src/main/kotlin/io/github/lujinxin/nextep/display/SlotTaskCoordinator.kt').read_text()
_pr65_layout=_pr65_source.split('private fun waitForTaskLayoutNonBlocking(',1)[1].split('private fun moveTaskToDisplayNonBlocking(',1)[0]
assert 'SystemClock.sleep(' not in _pr65_layout
assert 'exchangeHandler.postDelayed(' in _pr65_layout
assert 'restoreTaskLayoutNonBlocking(slotTask' in _pr65_source
assert 'ensureSlotLayoutNonBlocking(slotIndex' in _pr65_source

# PR64: reverse ONLY SHA-reviewed phased async observation/handoff changes
# before checking any older protected task, geometry or compositor source.
pr64_review=json.loads((root/'scripts/reviewed-pr64-nonblocking-handoff-0.9.41.json').read_text())['files']
def undo_pr64(path,data):
 if path not in pr64_review:return data
 spec=pr64_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr64_review:
 undo_pr64(reviewed_path,undo_pr65(reviewed_path,undo_pr66(reviewed_path,undo_pr67(reviewed_path,undo_pr68(reviewed_path,undo_pr70(reviewed_path,(root/reviewed_path).read_bytes()))))))

# PR63: restore exact aspect-lock/frame-pacing changes before PR62/older frozen audits.
pr63_review=json.loads((root/'scripts/reviewed-pr63-frame-pacing-aspect-lock-0.9.40.json').read_text())['files']
def undo_pr63(path,data):
 if path not in pr63_review:return data
 spec=pr63_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr63_review:
 undo_pr63(reviewed_path,undo_pr64(reviewed_path,undo_pr65(reviewed_path,undo_pr66(reviewed_path,undo_pr67(reviewed_path,undo_pr68(reviewed_path,undo_pr70(reviewed_path,(root/reviewed_path).read_bytes())))))))

# PR62: restore the exact reviewed PRIVATE owned-task GPU flight changes
# before older source-freeze boundaries. All modified source blobs are checked
# against their parent PR61 SHA-1, not exempted from protection.
pr62_review=json.loads((root/'scripts/reviewed-pr62-dual-task-gpu-atomic-0.9.39.json').read_text())['files']
def undo_pr62(path,data):
 if path not in pr62_review:return data
 spec=pr62_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr62_review:
 undo_pr62(reviewed_path,undo_pr63(reviewed_path,undo_pr64(reviewed_path,undo_pr65(reviewed_path,undo_pr66(reviewed_path,undo_pr67(reviewed_path,undo_pr68(reviewed_path,undo_pr70(reviewed_path,(root/reviewed_path).read_bytes()))))))))

# PR61: invert reviewed phased timing and privileged-snapshot reuse first.
pr61_review=json.loads((root/'scripts/reviewed-pr61-phased-async-latency-0.9.38.json').read_text())['files']
def undo_pr61(path,data):
 if path not in pr61_review:return data
 spec=pr61_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:100])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior
for reviewed_path in pr61_review:
 undo_pr61(reviewed_path,undo_pr62(reviewed_path,undo_pr63(reviewed_path,undo_pr64(reviewed_path,undo_pr65(reviewed_path,undo_pr66(reviewed_path,undo_pr67(reviewed_path,undo_pr68(reviewed_path,undo_pr70(reviewed_path,(root/reviewed_path).read_bytes())))))))))

# PR60 P0: reverse only the reviewed single real swap gate/auxiliary visual removal.
pr60_review=json.loads((root/'scripts/reviewed-pr60-single-real-swap-0.9.37.json').read_text())['files']
def undo_pr60(path,data):
 if path not in pr60_review:return data
 spec=pr60_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:90])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior

# PR59 cold mirror gate: reverse reviewed policy/controller edits before PR58.
cold_mirror_review=json.loads((root/'scripts/reviewed-pr59-cold-mirror-gate-recovery-0.9.35.json').read_text())['files']
def undo_pr59(path,data):
 if path not in cold_mirror_review:return data
 spec=cold_mirror_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:90])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior

# PR58 native handoff timing: reverse the reviewed visual-motion edits first.
native_handoff_review=json.loads((root/'scripts/reviewed-pr58-late-native-handoff-0.9.35.json').read_text())['files']
def undo_pr58(path,data):
 if path not in native_handoff_review:return data
 spec=native_handoff_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:90])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior

# PR57: undo only private route tracing before the PR55 and legacy frozen audits.
swap_path_review=json.loads((root/'scripts/reviewed-pr57-swap-path-0.9.35.json').read_text())['files']
def undo_pr57(path,data):
 if path not in swap_path_review:return data
 spec=swap_path_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:90])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior

# PR55: reverse the scoped animation change before the PR54 frozen review.
vivo_lead_review=json.loads((root/'scripts/reviewed-pr55-vivo-visible-flight-0.9.34.json').read_text())['files']
def undo_pr55(path,data):
 if path not in vivo_lead_review:return data
 spec=vivo_lead_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:90])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior

# PR54: pre-existing full-screen host + concurrent, budgeted two-live-mirror flight.
# Restore exact preceding blobs before PR53/PR52/N3 frozen-source checks.
gpu_concurrent_review=json.loads((root/'scripts/reviewed-pr54-concurrent-live-gpu-0.9.34.json').read_text())['files']
def undo_gpu_concurrent(path,data):
 if path not in gpu_concurrent_review:return data
 spec=gpu_concurrent_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:70])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior

# One-shot tap motion: full preceding blob restored before N3 and older frozen checks.
swap_visual_review=json.loads((root/'scripts/reviewed-assistant3-one-shot-swap-motion-0.9.34.json').read_text())['files']
def undo_one_shot_swap(path,data):
 if path not in swap_visual_review:return data
 spec=swap_visual_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:70])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior

# Unmerged N3 power candidate on PR52: exact reversible fragments and old blob hashes.
n3_port_review=json.loads((root/'scripts/reviewed-assistant3-n3-port-0.9.34.json').read_text())['files']
def undo_n3_port(path,data):
 if path not in n3_port_review:return data
 spec=n3_port_review[path]
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:75])
  source=source.replace(patch['after'],patch['before'],1)
 prior=source.encode('utf-8')
 assert hashlib.sha1(b'blob '+str(len(prior)).encode()+bytes([0])+prior).hexdigest()==spec['previous_blob_sha1'],path
 return prior

# P0 direct phased click exchange: reversible exact source byte guard.
# The new fast path is private and no task/VD/input primitives are changed.
immediate_tap_review=json.loads((root/'scripts/reviewed-pr51-immediate-tap-0.9.34.json').read_text())['files']
assert set(immediate_tap_review)=={'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'}
def undo_immediate_tap(path,data):
 if path not in immediate_tap_review:return data
 spec=immediate_tap_review[path]
 gitblob=lambda b:hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 body=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert body.count(patch['after'])==1,(path,patch['after'][:70])
  body=body.replace(patch['after'],patch['before'],1)
 old=body.encode('utf-8')
 assert gitblob(old)==spec['previous_blob_sha1'],path
 return old

# 3731.mp4 P0 performance rollback: reject any unreviewed native path.
# Reverse this single UI-path gate before every older frozen source check.
pr50_jank_review=json.loads((root/'scripts/reviewed-pr50-jank-guard-0.9.34.json').read_text())['files']
assert set(pr50_jank_review)=={'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'}
def undo_pr50_jank(path,data):
 if path not in pr50_jank_review:return data
 spec=pr50_jank_review[path]
 gitblob=lambda b:hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 body=data.decode('utf-8')
 for fragment in reversed(spec['patches']):
  assert body.count(fragment['after'])==1,(path,fragment['after'][:64])
  body=body.replace(fragment['after'],fragment['before'],1)
 old=body.encode('utf-8')
 assert gitblob(old)==spec['previous_blob_sha1'],path
 return old

# Assistant3 full display overlay, exact complete Git blob and reversible
# edits BEFORE the PR49 and every older frozen task/display/gesture check.
full_host_review=json.loads((root/'scripts/reviewed-assistant3-fullscreen-native-swap-0.9.34.json').read_text())['files']
assert set(full_host_review)=={'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'}
def undo_assistant3_full_host(path,data):
 if path not in full_host_review:return data
 spec=full_host_review[path]
 blob=lambda b:hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert blob(data)==spec['reviewed_blob_sha1'],path
 body=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert body.count(patch['after'])==1,(path,patch['after'][:70])
  body=body.replace(patch['after'],patch['before'],1)
 old=body.encode('utf-8')
 assert blob(old)==spec['previous_blob_sha1'],path
 return old

# Native dual-SurfaceFlinger mirror flight: exact Git blobs + reversible
# source patches BEFORE the previous PR48 and PR47 safety/freeze chain.
# This is presentation-only and deliberately does not review any task moves.
native_live_review=json.loads((root/'scripts/reviewed-total4-native-live-exchange-3665.json').read_text())['files']
assert set(native_live_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/framework/TaskSurfaceCompat.kt',
}
def undo_native_live_exchange(path,data):
 if path not in native_live_review:return data
 spec=native_live_review[path]
 gitblob=lambda b:hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 body=data.decode('utf-8')
 expected=6 if path.endswith('NeXtepWindowController.kt') else 1
 assert len(spec['patches'])==expected,path
 for change in reversed(spec['patches']):
  assert body.count(change['after'])==1,(path,change['after'][:90])
  body=body.replace(change['after'],change['before'],1)
 before=body.encode('utf-8')
 assert gitblob(before)==spec['previous_blob_sha1'],path
 return before

# 3717.mp4 real-device cancellation fix. Enforce exact Git blobs and
# reversible presentation-only fragments before the previous PR47 reviews.
# This does not permit task-token, display-migration, input or geometry changes.
swap_hold_review=json.loads((root/'scripts/reviewed-total4-swap-animation-hold-3717.json').read_text())['files']
assert set(swap_hold_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/display/TaskSwitcherView.kt',
}
def undo_swap_hold(path,data):
 if path not in swap_hold_review:return data
 spec=swap_hold_review[path]
 gitblob=lambda b:hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 body=data.decode('utf-8')
 expected=9 if path.endswith('NeXtepWindowController.kt') else 1
 assert len(spec['patches'])==expected,path
 for patch in reversed(spec['patches']):
  assert body.count(patch['after'])==1,(path,patch['after'][:90])
  body=body.replace(patch['after'],patch['before'],1)
 prior=body.encode('utf-8')
 assert gitblob(prior)==spec['previous_blob_sha1'],path
 return prior

# Actual 3712.mp4 tap morph: only two reviewed presentation files are
# permitted to change. Reverse their exact patches BEFORE all older freeze
# validations, so task tokens, display moves and hitboxes stay unchanged.
real_morph_review=json.loads((root/'scripts/reviewed-total4-real-swap-morph-3712.json').read_text())['files']
assert set(real_morph_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/WorkspaceSwapAnimator.kt',
}
def undo_total4_real_swap(path,data):
 if path not in real_morph_review:return data
 spec=real_morph_review[path]
 gitblob=lambda b:hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 text=data.decode('utf-8')
 assert len(spec['patches'])==(3 if path.endswith('NeXtepWindowController.kt') else 1),path
 for patch in reversed(spec['patches']):
  assert text.count(patch['after'])==1,(path,patch['after'][:90])
  text=text.replace(patch['after'],patch['before'],1)
 previous=text.encode('utf-8')
 assert gitblob(previous)==spec['previous_blob_sha1'],path
 return previous

# Total window 4: exact full-blob contract for four bounded entry-only
# presentation fragments. Reverse them IN MEMORY before every older review.
# Never weaken the pre-existing task, touch or compositor freeze chain.
total4_entry_review=json.loads((root/'scripts/reviewed-total4-entry-motion-0.9.34.json').read_text())['files']
assert set(total4_entry_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'
}
def undo_total4_entry(path,data):
 if path not in total4_entry_review:return data
 spec=total4_entry_review[path]
 gitblob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 source=data.decode('utf-8')
 assert len(spec['patches'])==4,path
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,(path,patch['after'][:80])
  source=source.replace(patch['after'],patch['before'],1)
 before=source.encode('utf-8')
 assert gitblob(before)==spec['previous_blob_sha1'],path
 return before

# Total4 GPU-backed exchange preview: exact short-lived ownership transfer,
# reversed before the prior frozen task Surface chain. No new capture polling.
gpu_preview_review=json.loads((root/'scripts/reviewed-total4-gpu-preview-0.9.34.json').read_text())['files']
assert set(gpu_preview_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/framework/TaskSurfaceCompat.kt'
}
def undo_total4_gpu(path,data):
 if path not in gpu_preview_review:return data
 spec=gpu_preview_review[path]
 gitblob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 body=data.decode('utf-8')
 assert len(spec['patches'])==2,path
 for patch in reversed(spec['patches']):
  assert body.count(patch['after'])==1,(path,patch['after'][:75])
  body=body.replace(patch['after'],patch['before'],1)
 prior=body.encode('utf-8')
 assert gitblob(prior)==spec['previous_blob_sha1'],path
 return prior

# Total4: exact reversible GPU preview cleanup before older swap animator
# reviews. The callback waits for the removal frame, NOT a task move delay.
gpu_release_review=json.loads((root/'scripts/reviewed-total4-gpu-release-0.9.34.json').read_text())['files']
assert set(gpu_release_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/WorkspaceSwapAnimator.kt'
}
def undo_total4_gpu_release(path,data):
 if path not in gpu_release_review:return data
 spec=gpu_release_review[path]
 gitblob=lambda b:hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 text=data.decode('utf-8')
 assert len(spec['patches'])==3,path
 for patch in reversed(spec['patches']):
  assert text.count(patch['after'])==1,(path,patch['after'][:75])
  text=text.replace(patch['after'],patch['before'],1)
 previous=text.encode('utf-8')
 assert gitblob(previous)==spec['previous_blob_sha1'],path
 return previous

# Scoped ColorOS update-center redesign; exact Git blob verifier then undo
# only this private UI patch before all previous reviewed frozen checks.
update_hero_review=json.loads((root/'scripts/reviewed-update-coloros-hero-device-0.9.34.json').read_text())['files']
assert set(update_hero_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageSettingsScreen.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdateSection.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageUpdateDeviceLabel.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageUpdateBackdrop.kt',
}
def undo_update_hero(path,data):
 if path not in update_hero_review:return data
 spec=update_hero_review[path]
 gitblob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 previous=spec['previous_content'].encode('utf-8')
 assert gitblob(previous)==spec['previous_blob_sha1'],path
 return previous

# ABC layout preset UI: exact blob and reversible fragment review. No new
# runtime task, geometry, virtual-display or rendering changes are authorized.
abc_ui_review=json.loads((root/'scripts/reviewed-abc-presets-0.9.34.json').read_text())['files']
assert set(abc_ui_review)=={'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageSettingsScreen.kt'}
def undo_abc_ui(path,data):
 if path not in abc_ui_review:return data
 spec=abc_ui_review[path]
 gitblob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 content=data.decode('utf-8')
 for change in reversed(spec['patches']):
  assert content.count(change['after'])==1,(path,change['after'][:80])
  content=content.replace(change['after'],change['before'],1)
 previous=content.encode('utf-8')
 assert gitblob(previous)==spec['previous_blob_sha1'],path
 return previous

# All-device parallel tap motion reviewed against exact PR39 Git blobs.
parallel_review=json.loads((root/'scripts/reviewed-all-device-parallel-swap-0.9.34.json').read_text())['files']
assert set(parallel_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/WorkspaceSwapAnimator.kt',
}
def undo_parallel_motion(path,data):
 if path not in parallel_review:return data
 spec=parallel_review[path]
 blob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert blob(data)==spec['reviewed_blob_sha1'],path
 source=data.decode('utf-8')
 for p in reversed(spec['patches']):
  assert source.count(p['after'])==1,(path,p['after'][:80])
  source=source.replace(p['after'],p['before'],1)
 prior=source.encode('utf-8')
 assert blob(prior)==spec['previous_blob_sha1'],path
 return prior

# Quickbar master switch: exact reviewed complete Git blob and reversible patches.
quickbar_review=json.loads((root/'scripts/reviewed-cooluser-quickbar-0.9.34.json').read_text())['files']
assert set(quickbar_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageSettingsScreen.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
}
def undo_cooluser_quickbar(path,data):
 if path not in quickbar_review:return data
 spec=quickbar_review[path]
 blob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert blob(data)==spec['reviewed_blob_sha1'],path
 source=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert source.count(patch['after'])==1,path
  source=source.replace(patch['after'],patch['before'],1)
 previous=source.encode('utf-8')
 assert blob(previous)==spec['previous_blob_sha1'],path
 return previous

# Assistant4 0.9.30: exact fragment reversals precede original frozen checks.
handoff_contract=json.loads((root/'scripts/reviewed-assistant4-y700-handoff-0.9.30.json').read_text())
handoff_review=handoff_contract['files']
handoff_blobs=handoff_contract['git_blobs']
assert set(handoff_blobs)==set(handoff_review)
assert set(handoff_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/SystemUiHook.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageTabletColdLaunchBridge.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageTabletColdLaunchPolicy.kt',
}
def undo_y700_handoff(path,data):
 if path not in handoff_review:return data
 def gitblob(blob):
  return hashlib.sha1(b'blob '+str(len(blob)).encode()+b'\0'+blob).hexdigest()
 assert gitblob(data)==handoff_blobs[path]['current'],path
 source=data.decode('utf-8')
 for fragment in handoff_review[path]:
  assert source.count(fragment['after'])==1,path
  source=source.replace(fragment['after'],fragment['before'],1)
 previous=source.encode('utf-8')
 assert gitblob(previous)==handoff_blobs[path]['previous'],path
 return previous
for source_path in handoff_review:
 undo_y700_handoff(source_path,undo_cooluser_quickbar(source_path,undo_parallel_motion(source_path,undo_abc_ui(source_path,undo_update_hero(source_path,undo_total4_gpu_release(source_path,undo_total4_gpu(source_path,undo_total4_entry(source_path,undo_total4_real_swap(source_path,undo_swap_hold(source_path,undo_native_live_exchange(source_path,undo_assistant3_full_host(source_path,undo_pr50_jank(source_path,undo_immediate_tap(source_path,undo_n3_port(source_path,undo_one_shot_swap(source_path,undo_gpu_concurrent(source_path,undo_pr55(source_path,undo_pr57(source_path,undo_pr58(source_path,undo_pr59(source_path,undo_pr60(source_path,undo_pr61(source_path,undo_pr62(source_path,undo_pr63(source_path,undo_pr64(source_path,undo_pr65(source_path,undo_pr66(source_path,undo_pr67(source_path,undo_pr68(source_path,undo_pr70(source_path,(root/source_path).read_bytes())))))))))))))))))))))))))))))))
# Scoped Y700 native first-frame callback path. Reverse exact source fragments
# and validate both full Git blob hashes before the v0.9.8 frozen chain.
cold_review=json.loads((root/'scripts/reviewed-y700-cold-launch-0.9.27.json').read_text())['files']
assert set(cold_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/SystemUiHook.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/display/SlotTaskCoordinator.kt',
}
def undo_y700_cold(path,data):
 if path not in cold_review:return data
 spec=cold_review[path]
 blob=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert blob==spec['reviewed_blob_sha1'],path
 text=data.decode('utf-8')
 for patch in spec['patches']:
  assert text.count(patch['after'])==1,path
  text=text.replace(patch['after'],patch['before'],1)
 data=text.encode('utf-8')
 previous=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert previous==spec['previous_blob_sha1'],path
 return data
# PR25 reviewed tablet-only source fragments, strictly reversed before
# all existing full-blob checks. Never replace or relax the mainline freezes.
ux_review=json.loads((root/'scripts/reviewed-assistant4-y700-ux-0.9.26.json').read_text())['files']
assert set(ux_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageTabletCanvasPolicy.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StagePresentationPolicy.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageMainHandle.kt',
}
def undo_y700_ux(path,data):
 if path not in ux_review:return data
 text=data.decode('utf-8')
 for patch in ux_review[path]:
  assert text.count(patch['after'])==1,path
  text=text.replace(patch['after'],patch['before'],1)
 return text.encode('utf-8')
# The 0.9.29 picker retry is a limited UI-only change in a frozen file.
# Verify its complete new blob, reverse the one exact fragment, and verify
# the original full blob BEFORE any existing historical freeze normalization.
picker_path='app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'
picker_review=json.loads((root/'scripts/reviewed-app-catalog-picker-0.9.29.json').read_text())['files']
assert set(picker_review)=={picker_path}
def undo_picker_retry(path,data):
 if path!=picker_path:return data
 spec=picker_review[path]
 blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
 assert blob==spec['reviewed_blob_sha1'],path
 source=data.decode('utf-8')
 for patch in spec['patches']:
  assert source.count(patch['after'])==1,path
  source=source.replace(patch['after'],patch['before'],1)
 data=source.encode('utf-8')
 previous=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
 assert previous==spec['previous_blob_sha1'],path
 return data
# Reviewed compositor-only reflow; normalize exact new controller blob
# back to its previous code before every pre-existing freeze hash check.
reflow_path='app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'
reflow_review=json.loads((root/'scripts/reviewed-sparse-side-reflow-0.9.26.json').read_text())['files']
assert set(reflow_review)=={reflow_path}
def undo_reflow(path,data):
 data=undo_y700_ux(path,undo_y700_cold(path,undo_picker_retry(path,data)))
 if path!=reflow_path:return data
 spec=reflow_review[path]
 current=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert current==spec['reviewed_blob_sha1'],path
 old=spec['previous_content'].encode('utf-8')
 earlier=hashlib.sha1(b'blob '+str(len(old)).encode()+bytes([0])+old).hexdigest()
 assert earlier==spec['previous_blob_sha1'],path
 return old
# One-count to three-count side-card bounds change is independently
# pinned to the exact full before/after Git blob. Restore the last audited
# source IN MEMORY before the existing v0.9.25 -> v0.9.8 freeze chain.
sparse_path='app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageLiveCardLayout.kt'
sparse_review=json.loads((root/'scripts/reviewed-sparse-side-cards-0.9.26.json').read_text())['files']
assert set(sparse_review)=={sparse_path}
def undo_sparse_cards(path,data):
 if path!=sparse_path:return data
 spec=sparse_review[path]
 actual=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert actual==spec['reviewed_blob_sha1'],path
 previous=spec['previous_content'].encode('utf-8')
 old=hashlib.sha1(b'blob '+str(len(previous)).encode()+bytes([0])+previous).hexdigest()
 assert old==spec['previous_blob_sha1'],path
 return previous
# Final Y700 visual sources are strict pinned complete Git blobs.
# Undo them in memory BEFORE the original v0.9.8 freeze chain.
y700_visual_review=json.loads((root/'scripts/reviewed-y700-live-wallpaper-compact-0.9.25.json').read_text())['files']
assert set(y700_visual_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageLiveCardLayout.kt',
}
def undo_y700_visual(path,data):
 if path not in y700_visual_review:return data
 spec=y700_visual_review[path]
 blob=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert blob==spec['reviewed_blob_sha1'],path
 previous=spec['previous_content'].encode('utf-8')
 oldblob=hashlib.sha1(b'blob '+str(len(previous)).encode()+bytes([0])+previous).hexdigest()
 assert oldblob==spec['previous_blob_sha1'],path
 return previous
# Backup4 Y700 post-feedback changes are reversible by exact reviewed
# before/after fragments. Normalize the latest isolated branch back to the
# unified N5/Y700 base *before* validating frozen SHA-1/SHA-256 identities.
# All original checks remain active; unreviewed source mutations still fail.
screen_review=json.loads((root/'scripts/reviewed-assistant4-y700-screen-fit.json').read_text())['files']
assert set(screen_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/display/VirtualDisplaySlot.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageLiveCardLayout.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageAdaptiveLayout.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
}
# PR9/PR13 frozen presentation changes: verify exact new Git blobs, restore
# the PR14 baseline in memory, then run the original reversible/frozen checks.
unified_review=json.loads((root/'scripts/reviewed-unified-n5-y700-0.9.18.json').read_text())['files']
assert set(unified_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageCorePolicies.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageFrequentAppPolicy.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageQuickBarView.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageQuickStore.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageSmartRail.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/WorkspaceViewport.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/WorkspaceGeometry.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageDisplayPolicy.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageTopLayoutMetrics.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageLiveCardLayout.kt',
}
# The tablet branch is independently reviewed as exact reverse patches.
# Normalize only these known fragments before applying the existing frozen
# v0.9.17 -> v0.9.8 source chain. An unrelated edit still fails its hash.
tablet_review=json.loads((root/'scripts/reviewed-assistant4-native-landscape.json').read_text())['files']
assert set(tablet_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/WorkspaceGeometry.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageDisplayPolicy.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageLiveCardLayout.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
}
reviewed_chain=[json.loads((root/'scripts/reviewed-integration-0.9.18.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-fast-tap-0.9.17.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-fixed-swap-0.9.16.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-session-hardening.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-session-integration.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-output-retention.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-exchange-0.9.14.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-injection-0.9.13.json').read_text())['files']]+[json.loads((root/f'scripts/reviewed-refinements-{v}.json').read_text())['files'] for v in ['0.9.12','0.9.11']]
# The exact rollback fix is an isolated one-line Kotlin receiver correction.
# The full updated and pre-change Git blobs are pinned before older v0.9.8
# frozen source verification. This is NOT a relaxation of the freeze hashes.
rollback_review=json.loads((root/'scripts/reviewed-swap-rollback-release.json').read_text())['files']
rollback_path='app/src/main/kotlin/io/github/lujinxin/nextep/systemui/WorkspaceSwapAnimator.kt'
assert set(rollback_review)=={rollback_path}
def undo_swap_rollback_fix(path,data):
 if path!=rollback_path:return data
 spec=rollback_review[path]
 blob=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert blob==spec['reviewed_blob_sha1'],path
 text=data.decode('utf-8')
 for patch in spec['patches']:
  assert text.count(patch['after'])==1,path
  text=text.replace(patch['after'],patch['before'],1)
 previous=text.encode('utf-8')
 old=hashlib.sha1(b'blob '+str(len(previous)).encode()+bytes([0])+previous).hexdigest()
 assert old==spec['previous_blob_sha1'],path
 return previous

# App-list resilience is a scoped 0.9.29 exception for two catalog sources.
# Pin the complete new blobs, recover their complete old source bytes, then
# leave every original 0.9.8 freeze hash and reviewer chain untouched.
catalog_review=json.loads((root/'scripts/reviewed-app-catalog-files-0.9.29.json').read_text())['files']
assert set(catalog_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/apps/LauncherAppRepository.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageAppCatalog.kt',
}
def undo_catalog_files(path,data):
 if path not in catalog_review:return data
 spec=catalog_review[path]
 current=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert current==spec['reviewed_blob_sha1'],path
 original=spec['previous_content'].encode('utf-8')
 previous=hashlib.sha1(b'blob '+str(len(original)).encode()+bytes([0])+original).hexdigest()
 assert previous==spec['previous_blob_sha1'],path
 return original

# 0.9.31 home-only UI: exact full-blob audit, reverse before all pre-existing
# 0.9.8 protected byte hashes. Unknown settings or engine edits still fail.
home_ui_review=json.loads((root/'scripts/reviewed-opt-in-donation-home-0.9.31.json').read_text())['files']
assert set(home_ui_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageHomeLayout.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageSettingsScreen.kt',
}
def undo_home_ui(path,data):
 if path not in home_ui_review:return data
 spec=home_ui_review[path]
 blob=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert blob==spec['reviewed_blob_sha1'],path
 previous=spec['previous_content'].encode('utf-8')
 oldblob=hashlib.sha1(b'blob '+str(len(previous)).encode()+bytes([0])+previous).hexdigest()
 assert oldblob==spec['previous_blob_sha1'],path
 return previous

# v0.9.32 exact UI/navigation review, before all historical freeze checks.
# This is no blanket exemption: every byte of these three files is pinned.
update_ui_files=json.loads((root/'scripts/reviewed-update-ui-0.9.32.json').read_text())['files']
assert set(update_ui_files)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdateSection.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdateCoordinator.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/MainActivity.kt',
}
def undo_update_ui(path,data):
 if path not in update_ui_files:return data
 spec=update_ui_files[path]
 actual=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert actual==spec['reviewed_blob_sha1'],path
 old=spec['previous_content'].encode('utf-8')
 original=hashlib.sha1(b'blob '+str(len(old)).encode()+bytes([0])+old).hexdigest()
 assert original==spec['previous_blob_sha1'],path
 return old

# N5 foldable-only delayed main-Surface reassertion: validate the complete
# reviewed WorkspaceController Git blob, then reverse ONLY the exact five
# approved fragments before all original v0.9.8 freeze checks.
n5_rail_refit_path='app/src/main/kotlin/io/github/lujinxin/nextep/workspace/WorkspaceController.kt'
n5_rail_refit_review=json.loads((root/'scripts/reviewed-assistant6-n5-rail-refit.json').read_text())['files']
assert set(n5_rail_refit_review)=={n5_rail_refit_path}
def undo_n5_rail_refit(path,data):
 if path!=n5_rail_refit_path:return data
 spec=n5_rail_refit_review[path]
 blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
 assert blob==spec['reviewed_blob_sha1'],path
 source=data.decode('utf-8')
 assert len(spec['patches'])==5,path
 for patch in spec['patches']:
  assert source.count(patch['after'])==1,path
  source=source.replace(patch['after'],patch['before'],1)
 data=source.encode('utf-8')
 blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
 assert blob==spec['previous_blob_sha1'],path
 return data

# v0.9.34 UI-only unification: every changed protected file must match the
# reviewed complete Git blob, then reverse to exact prior complete blob before
# all existing 0.9.8/Y700/N5/update-install contract checks.
unified_update_files=json.loads((root/'scripts/reviewed-unified-device-updates-0.9.34.json').read_text())['files']
assert set(unified_update_files)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdateSection.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageSettingsScreen.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdateCoordinator.kt',
}
def undo_unified_update(path,data):
 if path not in unified_update_files:return data
 spec=unified_update_files[path]
 gitblob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 before=spec['previous_content'].encode('utf-8')
 assert gitblob(before)==spec['previous_blob_sha1'],path
 return before

# Private v0.9.34 presentation-only review. Exact complete Git blobs are verified
# and reversed before every previous frozen contract, never bypassing a guard.
private_ux_files=json.loads((root/'scripts/reviewed-private-ux-0.9.34.json').read_text())['files']
assert set(private_ux_files)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageHomeLayout.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/StageSettingsScreen.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdateSection.kt',
}
def undo_private_ux(path,data):
 if path not in private_ux_files:return data
 spec=private_ux_files[path]
 gitblob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
 assert gitblob(data)==spec['reviewed_blob_sha1'],path
 original=spec['previous_content'].encode('utf-8')
 assert gitblob(original)==spec['previous_blob_sha1'],path
 return original

# Narrow, reversible overlay-decoration change over the existing frozen swap.
cooluser_swap_review=json.loads((root/'scripts/reviewed-cooluser-swap-accent-0.9.34.json').read_text())['files']
assert set(cooluser_swap_review)=={'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/WorkspaceSwapAnimator.kt'}
def undo_cooluser_swap(path,data):
 if path not in cooluser_swap_review:return data
 spec=cooluser_swap_review[path]
 blob=lambda b: hashlib.sha1(b'blob '+str(len(b)).encode()+bytes([0])+b).hexdigest()
 assert blob(data)==spec['reviewed_blob_sha1'],path
 body=data.decode('utf-8')
 for patch in reversed(spec['patches']):
  assert body.count(patch['after'])==1,path
  body=body.replace(patch['after'],patch['before'],1)
 before=body.encode('utf-8')
 assert blob(before)==spec['previous_blob_sha1'],path
 return before

def protected_bytes(path):
 data=undo_catalog_files(path,undo_home_ui(path,undo_update_ui(path,undo_n5_rail_refit(path,undo_y700_handoff(path,undo_unified_update(path,undo_private_ux(path,undo_cooluser_quickbar(path,undo_cooluser_swap(path,undo_parallel_motion(path,undo_abc_ui(path,undo_update_hero(path,undo_total4_gpu_release(path,undo_total4_gpu(path,undo_total4_entry(path,undo_total4_real_swap(path,undo_swap_hold(path,undo_native_live_exchange(path,undo_assistant3_full_host(path,undo_pr50_jank(path,undo_immediate_tap(path,undo_n3_port(path,undo_one_shot_swap(path,undo_gpu_concurrent(path,undo_pr55(path,undo_pr57(path,undo_pr58(path,undo_pr59(path,undo_pr60(path,undo_pr61(path,undo_pr62(path,undo_pr63(path,undo_pr64(path,undo_pr65(path,undo_pr66(path,undo_pr67(path,undo_pr68(path,undo_pr70(path,(root/path).read_bytes()))))))))))))))))))))))))))))))))))))))
 data=undo_swap_rollback_fix(path,undo_y700_visual(path,undo_sparse_cards(path,undo_reflow(path,data))))
 if path in screen_review:
  text=data.decode()
  for patch in screen_review[path]:
   assert text.count(patch['after'])==1,path
   text=text.replace(patch['after'],patch['before'],1)
  data=text.encode()
 if path in unified_review:
  spec=unified_review[path]
  blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
  assert blob==spec['merged_blob_sha1'],path
  prior=spec['previous_content'].encode('utf-8')
  oldblob=hashlib.sha1(b'blob '+str(len(prior)).encode()+b'\0'+prior).hexdigest()
  assert oldblob==spec['previous_blob_sha1'],path
  data=prior
 if path in tablet_review:
  text=data.decode()
  for patch in tablet_review[path]:
   assert text.count(patch['after'])==1,path
   text=text.replace(patch['after'],patch['before'],1)
  data=text.encode()
 for reviewed in reviewed_chain:
  if path not in reviewed:continue
  spec=reviewed[path]
  assert hashlib.sha256(data).hexdigest()==spec['updated_sha256'],path
  text=data.decode()
  for patch in spec['patches']:
   assert text.count(patch['after'])==1,path
   text=text.replace(patch['after'],patch['before'],1)
  data=text.encode();assert hashlib.sha256(data).hexdigest()==spec['baseline_sha256'],path
 return data
s=(k/'display/SlotTaskCoordinator.kt').read_text()
def function(text,name):
 start=text.index('fun '+name+'('); brace=text.index('{',start);depth=1;end=brace+1
 while depth:
  if text[end]=='{':depth+=1
  if text[end]=='}':depth-=1
  end+=1
 return text[start:end]
for name in ['swapWithMain','swapSlots','recoverMigratedTask','recoverPromotedTaskIfNeeded','moveTaskToDisplayAndWait']:
 body=function(s,name)
 assert not re.search(r'(startActivity\s*\(|UserTargetedActivityLauncher\.start|\.launch\s*\(|\.play\s*\(|\.pause\s*\(|\.stop\s*\()',body),name
for file in ['systemui/StageMediaSignals.kt','systemui/StagePlaybackObservation.kt','systemui/NeXtepWindowController.kt','display/SlotTaskCoordinator.kt','display/VirtualDisplaySlot.kt']:
 text=(k/file).read_text()
 assert not re.search(r'transportControls|dispatchMediaButtonEvent|requestAudioFocus|abandonAudioFocus|KEYCODE_MEDIA|\.reload\s*\(',text),file
v=(k/'display/VirtualDisplaySlot.kt').read_text()
assert '.resize(' not in v
assert 'releaseDisplay' not in function(v,'onSurfaceTextureSizeChanged')
assert 'TaskContinuityPolicy.canvas' in v
assert 'reconnectRetainedOutput' in v
assert 'if (active && virtualDisplay != null)' in function(v,'onSurfaceTextureDestroyed')
assert 'shouldMove' in function(s,'moveTaskToDisplayAndWait')
assert 'TaskContinuityPolicy.canTransport' in function((k/'systemui/MediaControlView.kt').read_text(),'togglePlayback')
settings=(k/'config/StageSettingsScreen.kt').read_text()
assert 'Item("选择主应用"' not in settings
assert 'engine.openInMain' in (k/'systemui/NeXtepWindowController.kt').read_text()
host=(k/'systemui/NeXtepWindowController.kt').read_text()
assert 'contentDescription = "展开常用应用快捷栏"' in host and 'setOnClickListener { setQuickExpanded(true) }' in host
assert 'FrameLayout.LayoutParams(dp(48).toInt(), dp(48).toInt()' in host
print(json.dumps({'passive_paths_send_no_media_commands':True,'exchanges_do_not_launch_apps':True,'layout_does_not_resize_or_recreate_displays':True,'exact_display_move_is_idempotent':True,'home_entry_removed_controls_retained':True,'quick_bar_direct_48dp_entry':True,'device_playback_verified':False}))

swap_source=(k/'systemui/WorkspaceSwapAnimator.kt').read_text()
assert 'if (!moved || cancelRequested) { this@WorkspaceSwapAnimator.cancel(); return }' in swap_source
assert swap_source.count('this@WorkspaceSwapAnimator.cancel()')==1
print('Pending rollback explicitly releases the owning WorkspaceSwapAnimator preview after async failure; frozen original blob validated')
assert 'FullscreenBoundsPolicy()' in (k/'task/MainTaskPresentationCoordinator.kt').read_text()
assert 'StageVisualOrder.swap' in host
assert 'getSharedPreferences("atomic_updates_v1"' in (k/'config/UpdateCoordinator.kt').read_text()
installer=(k/'config/UpdateInstaller.kt').read_text()
assert 'UpdatePolicy.digest(file)==m.sha256' in installer and 'UpdatePolicy.requireArchive' in installer
assert 'FileProvider.getUriForFile' in installer and 'canRequestPackageInstalls' in installer
assert 'gh release create' not in (root/'.github/workflows/atomic-android.yml').read_text()
print('Update install identity/hash boundaries, manual-only publication, and state-preserving visual reorder checked')

# v0.9.3 changes presentation and feedback only. Preserve all task/VD/media engines.
assert '144 * density' not in (k/'workspace/StageAdaptiveLayout.kt').read_text()
viewport=(k/'systemui/WorkspaceViewport.kt').read_text()
assert 'atomic_viewport_profiles_v5' in viewport and '"rail_top"' in viewport
assert '.clear()' not in viewport
assert 'StageTopLayoutMetrics.depth(g, profile.densityDpi / 160f, activeCount)' in viewport
assert '"feedback" -> feedback(column)' in settings and 'Item("建议与反馈"' in (k/'config/StageHomeLayout.kt').read_text()
assert 'CoolapkFeedback.template' in settings and 'setPrimaryClip' in settings
assert 'CoolapkFeedback.open' in settings and 'setPackage(packageName)' in settings
for credit in ['ZMW小武 × GPT', 'OpenAI GPT', 'lujinxin（Jensen Lu）', 'Apache License 2.0', 'https://github.com/lujinxin/NeXtep', 'https://github.com/x647368-maker/NeXtep']:
 assert credit in settings,credit
feedback=(k/'config/CoolapkFeedback.kt').read_text()
assert 'https://www.coolapk.com/u/43881308?from=qr' in feedback
assert not re.search(r'Build\.|Settings\.Secure|HttpURLConnection|https?://.*(?:api|upload)',feedback)
print('Readable top cards, geometry-only cache migration, blank feedback template and preserved attribution checked')

# Home search and duplicate direct exit are removed, not merely hidden.
home=(k/'config/StageHomeLayout.kt').read_text()
assert '搜索设置项' not in settings and 'TextWatcher' not in settings and 'private var query =' not in settings
assert 'StageHomeLayout.sections' in settings and '发现台前调度新版本' in settings
assert 'setPositiveButton("退出")' in settings and 'performSetStage(false)' in settings
assert 'MainFooterBackdrop' in host and 'mainInputRegionReady = sidebarTouchRegionReady' in host
assert 'StagePresentationPolicy.apply' in viewport and 'StageMainWindowMetrics.enlarge' in (k/'workspace/StagePresentationPolicy.kt').read_text()
assert 'clipOutRect(main)' in (k/'systemui/MainFooterBackdrop.kt').read_text()
assert 'WorkspaceGeometryCodec.encode(it)' in (k/'workspace/SystemServerWorkspaceBridge.kt').read_text()
print('Compact native home, confirmed exit, main/background/input geometry and safe input-gated fallback checked')

import hashlib
preserved=json.loads((root/'scripts/preserved-top-cards-0.9.3.json').read_text())
preserved.update(json.loads((root/'scripts/visual-repair-0.9.6.json').read_text()))
preserved.update(json.loads((root/'scripts/slot-recovery-0.9.7.json').read_text()))
preserved.update(json.loads((root/'scripts/transform-repair-0.9.8.json').read_text()))
preserved.update(json.loads((root/'scripts/shade-native-0.9.9.json').read_text()))
for path,sha in preserved.items():
 if path=='profile_card_section_sha256':
  text=(k/'workspace/WorkspaceLayoutProfile.kt').read_text();data=text[text.index('    val canvas ='):text.index('    fun geometry(')].encode()
 else:data=protected_bytes(path)
 assert hashlib.sha256(data).hexdigest()==sha,path
print('Task engine and top sizing remain frozen; explicitly reviewed 0.9.6 live clipping fixes match their manifest')

# v0.9.5: state-local geometry preferences and native compact information architecture.
assert 'StageDisplayPolicy.scope' in viewport and 'layout_state_v1:' in viewport
assert 'selectLayoutScope(snapshot)' in viewport and 'layout_scopes_migrated_v1' in viewport
assert 'prepareLayoutScope()' in host and 'viewport.selectedSidebarSide()' in host
assert 'StageDisplayPolicy.layoutKey' in (k/'systemui/StageQuickStore.kt').read_text()
assert 'postDelayed(notifyChanged, if (foldChange) 120L else 0L)' in viewport
assert 'StageHomeLayout.contentWidth' in settings and 'versionName' in settings
assert '基于 NeXtep 二次开发' in settings and 'StageHomeLayout.SECTION_TOP_DP' in settings
for title in ['工作台','系统','支持','窗口布局','影音与快捷栏','启动手势','快捷开关','运行诊断']:
 assert title in home,title
assert 'MainFooterBackdrop' in host and 'transportControls' not in host
print('Display-local layout/strategy migration, existing display listener fold settling, capped native settings columns and required home order checked')

# v0.9.8 source/destination separation is shared by each existing fitting owner.
assert '0, 0, geometry.screenWidth, geometry.screenHeight' in (k/'framework/TaskSurfaceCompat.kt').read_text()
assert 'Rect(0, 0, geometry.screenWidth, geometry.screenHeight)' in (k/'systemserver/WorkspaceTaskSurfaceHook.kt').read_text()
assert 'WorkspaceGeometryCodec.encode(it)' in (k/'workspace/SystemServerWorkspaceBridge.kt').read_text()
assert 'it.mainInset' not in host # geometry is consumed through shared metrics, not independent knobs
assert 'StageWindowTouchRegion.add' in (k/'systemui/StageQuickBarView.kt').read_text()
print('Full-source crop, shared margin snapshot and window-local chrome input boundaries checked')

# All stable runtime files except the approved shade/menu/version UI files remain exact.
reviewed_updater=json.loads((root/'scripts/reviewed-update-feed-0.9.16.json').read_text())['files']
stale_feed_path='app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdateChecker.kt'
stale_feed_review=json.loads((root/'scripts/reviewed-stale-feed-failover-0.9.26.json').read_text())['files']
assert set(stale_feed_review)=={stale_feed_path}
rekey_patches=json.loads((root/'scripts/reviewed-rekey-0.9.18.json').read_text())['files']
assert set(rekey_patches)=={'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdatePolicy.kt'}
reviewed_game=json.loads((root/'scripts/reviewed-game-scope-0.9.18.json').read_text())['files']
reviewed_game_paths={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemserver/FixedOrientationAspectRatioHooker.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemserver/SizeCompatDisplayInsetsHooker.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemserver/WorkspaceLandscapeRotationHooker.kt',
}
assert set(reviewed_game)==reviewed_game_paths
# P0 0.9.30: a validated public feed covers the installed version, but API returns 403.
# Reverse only this reviewed patch before every existing update-feed SHA check.
p0_api403_path='app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdateChecker.kt'
p0_api403_review=json.loads((root/'scripts/reviewed-api403-index-fallback-0.9.30.json').read_text())['files']
assert set(p0_api403_review)=={p0_api403_path}
def undo_api403_fallback(path,data):
 if path!=p0_api403_path:return data
 spec=p0_api403_review[path]
 blob=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert blob==spec['reviewed_blob_sha1'],path
 text=data.decode('utf-8')
 for patch in spec['patches']:
  assert text.count(patch['after'])==1,path
  text=text.replace(patch['after'],patch['before'],1)
 data=text.encode('utf-8')
 previous=hashlib.sha1(b'blob '+str(len(data)).encode()+bytes([0])+data).hexdigest()
 assert previous==spec['previous_blob_sha1'],path
 return data
reviewed_n5=json.loads((root/'scripts/reviewed-n5-parallel-diagnostics-0.9.18.json').read_text())['files']
assert set(reviewed_n5)=={'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'}
frozen=json.loads((root/'scripts/frozen-workspace-0.9.8.json').read_text())
assert set(reviewed_updater).issubset(set(frozen))
assert reviewed_game_paths.issubset(set(frozen))
for path,sha in frozen.items():
 if path in reviewed_n5:
  spec=reviewed_n5[path]
  assert sha==spec['original_sha256'],path
  data=undo_y700_visual(path,undo_reflow(path,undo_y700_handoff(path,undo_cooluser_quickbar(path,undo_cooluser_swap(path,undo_parallel_motion(path,undo_abc_ui(path,undo_update_hero(path,undo_total4_gpu_release(path,undo_total4_gpu(path,undo_total4_entry(path,undo_total4_real_swap(path,undo_swap_hold(path,undo_native_live_exchange(path,undo_assistant3_full_host(path,undo_pr50_jank(path,undo_immediate_tap(path,undo_n3_port(path,undo_one_shot_swap(path,undo_gpu_concurrent(path,undo_pr55(path,undo_pr57(path,undo_pr58(path,undo_pr59(path,undo_pr60(path,undo_pr61(path,undo_pr62(path,undo_pr63(path,undo_pr64(path,undo_pr65(path,undo_pr66(path,undo_pr67(path,undo_pr68(path,undo_pr70(path,(root/path).read_bytes()))))))))))))))))))))))))))))))))))
  # The frozen controller is also touched by the scoped Y700 wallpaper fix.
  # Reverse exactly the separately reviewed changes before checking the
  # original N5 diagnostics blob. Unknown changes are still rejected.
  if path in screen_review:
   text=data.decode()
   for patch in screen_review[path]:
    assert text.count(patch['after'])==1,path
    text=text.replace(patch['after'],patch['before'],1)
   data=text.encode()
  # Only this exact reviewed read-only instrumentation may change the frozen controller.
  git_blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
  assert git_blob==spec['reviewed_blob_sha1'],path
  text=data.decode('utf-8')
  assert 'StageNativeParallelDiagnostics.report(selected)' in text,path
  assert 'fun slotStatesDescription()' in text,path
 elif path in unified_review:
  assert hashlib.sha256(protected_bytes(path)).hexdigest()==sha,path
 elif path in reviewed_game:
  spec=reviewed_game[path]
  assert sha==spec['original_sha256'],path
  data=undo_y700_handoff(path,undo_cooluser_quickbar(path,undo_cooluser_swap(path,undo_parallel_motion(path,undo_abc_ui(path,undo_update_hero(path,undo_total4_gpu_release(path,undo_total4_gpu(path,undo_total4_entry(path,undo_total4_real_swap(path,undo_swap_hold(path,undo_native_live_exchange(path,undo_assistant3_full_host(path,undo_pr50_jank(path,undo_immediate_tap(path,undo_n3_port(path,undo_one_shot_swap(path,undo_gpu_concurrent(path,undo_pr55(path,undo_pr57(path,undo_pr58(path,undo_pr59(path,undo_pr60(path,undo_pr61(path,undo_pr62(path,undo_pr63(path,undo_pr64(path,undo_pr65(path,undo_pr66(path,undo_pr67(path,undo_pr68(path,undo_pr70(path,(root/path).read_bytes()))))))))))))))))))))))))))))))))
  # The exact Git blob SHA-1 pins the reviewed hook's entire contents.
  git_blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
  assert git_blob==spec['reviewed_blob_sha1'],path
 elif path in reviewed_updater:
  spec=reviewed_updater[path]
  assert sha==spec['original_sha256'],path
  data=undo_api403_fallback(path,undo_update_ui(path,undo_unified_update(path,undo_private_ux(path,undo_cooluser_quickbar(path,undo_cooluser_swap(path,undo_parallel_motion(path,undo_abc_ui(path,undo_update_hero(path,undo_total4_gpu_release(path,undo_total4_gpu(path,undo_total4_entry(path,undo_total4_real_swap(path,undo_swap_hold(path,undo_native_live_exchange(path,undo_assistant3_full_host(path,undo_pr50_jank(path,undo_immediate_tap(path,undo_n3_port(path,undo_one_shot_swap(path,undo_gpu_concurrent(path,undo_pr55(path,undo_pr57(path,undo_pr58(path,undo_pr59(path,undo_pr60(path,undo_pr61(path,undo_pr62(path,undo_pr63(path,undo_pr64(path,undo_pr65(path,undo_pr66(path,undo_pr67(path,undo_pr68(path,undo_pr70(path,(root/path).read_bytes())))))))))))))))))))))))))))))))))))
  if path in stale_feed_review:
   review=stale_feed_review[path]
   blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
   assert blob==review['reviewed_blob_sha1'],path
   text=data.decode('utf-8')
   for patch in review['patches']:
    assert text.count(patch['after'])==1,path
    text=text.replace(patch['after'],patch['before'],1)
   data=text.encode('utf-8')
   oldblob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
   assert oldblob==review['previous_blob_sha1'],path
  if path in rekey_patches:
   change=rekey_patches[path]
   assert hashlib.sha256(data).hexdigest()==change['updated_sha256'],path
   source=data.decode('utf-8')
   for p in change['patches']:
    assert source.count(p['after'])==1,path
    source=source.replace(p['after'],p['before'],1)
   data=source.encode('utf-8')
   assert hashlib.sha256(data).hexdigest()==change['baseline_sha256'],path
  assert hashlib.sha256(data).hexdigest()==spec['updated_sha256'],path
 else:
  assert hashlib.sha256(protected_bytes(path)).hexdigest()==sha,path
shade=(k/'systemui/SystemUiRootTransformController.kt').read_text()
shade_input=(k/'systemui/SystemUiShadeInputController.kt').read_text()
assert 'contentScale' not in shade and 'view.scaleX =' not in shade
assert 'Transform? = null' in shade
assert 'setTouchableInsets' not in shade_input and 'params.flags =' not in shade_input
menu=(k/'systemui/AtomicPopupMenu.kt').read_text()
assert 'StagePanelMenuGate' in menu and 'setTouchModal(false)' in menu
assert 'setBackgroundBlurRadius' not in menu and 'FLAG_BLUR_BEHIND' not in menu and 'RenderEffect' not in menu
assert 'TRANSPARENT' in menu and '*.38f' in menu
print('0.9.8 frozen hashes enforced after exact reviewed 0.9.12 and 0.9.11 fragment normalization; geometry/task/input writers remain original; native shade and bounded menu checked')
