#!/usr/bin/env python3
"""Source boundaries, not device playback tests. Fail if passive paths regain side effects."""
from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1];k=root/'app/src/main/kotlin/io/github/lujinxin/nextep'

# Only exact reviewed performance/animation fragments may differ. All task moves,
# geometry formulas, crop/input writers and the rest of each file remain frozen.
import hashlib
# 0.9.26 Y700 follow-up: narrow exact reversible fragments over the frozen
# compositor. Restore the previous controller bytes before all historical
# full-blob checks; source changes outside these reviewed fragments still fail.
ux_review=json.loads((root/'scripts/reviewed-assistant4-y700-ux-0.9.26.json').read_text())['files']
assert set(ux_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageTabletCanvasPolicy.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StagePresentationPolicy.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
}
def undo_y700_ux(path,data):
 if path not in ux_review:return data
 text=data.decode('utf-8')
 for patch in ux_review[path]:
  assert text.count(patch['after'])==1,path
  text=text.replace(patch['after'],patch['before'],1)
 return text.encode('utf-8')
# Reviewed compositor-only reflow; normalize exact new controller blob
# back to its previous code before every pre-existing freeze hash check.
reflow_path='app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'
reflow_review=json.loads((root/'scripts/reviewed-sparse-side-reflow-0.9.26.json').read_text())['files']
assert set(reflow_review)=={reflow_path}
def undo_reflow(path,data):
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

def protected_bytes(path):
 data=undo_swap_rollback_fix(path,undo_y700_visual(path,undo_sparse_cards(path,undo_reflow(path,undo_y700_ux(path,(root/path).read_bytes())))))
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
rekey_patches=json.loads((root/'scripts/reviewed-rekey-0.9.18.json').read_text())['files']
assert set(rekey_patches)=={'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdatePolicy.kt'}
reviewed_game=json.loads((root/'scripts/reviewed-game-scope-0.9.18.json').read_text())['files']
reviewed_game_paths={
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemserver/FixedOrientationAspectRatioHooker.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemserver/SizeCompatDisplayInsetsHooker.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemserver/WorkspaceLandscapeRotationHooker.kt',
}
assert set(reviewed_game)==reviewed_game_paths
reviewed_n5=json.loads((root/'scripts/reviewed-n5-parallel-diagnostics-0.9.18.json').read_text())['files']
assert set(reviewed_n5)=={'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt'}
frozen=json.loads((root/'scripts/frozen-workspace-0.9.8.json').read_text())
assert set(reviewed_updater).issubset(set(frozen))
assert reviewed_game_paths.issubset(set(frozen))
for path,sha in frozen.items():
 if path in reviewed_n5:
  spec=reviewed_n5[path]
  assert sha==spec['original_sha256'],path
  data=undo_y700_visual(path,undo_reflow(path,undo_y700_ux(path,(root/path).read_bytes())))
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
  data=(root/path).read_bytes()
  # The exact Git blob SHA-1 pins the reviewed hook's entire contents.
  git_blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
  assert git_blob==spec['reviewed_blob_sha1'],path
 elif path in reviewed_updater:
  spec=reviewed_updater[path]
  assert sha==spec['original_sha256'],path
  data=(root/path).read_bytes()
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
