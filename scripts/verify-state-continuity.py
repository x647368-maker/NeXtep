#!/usr/bin/env python3
"""Source boundaries, not device playback tests. Fail if passive paths regain side effects."""
from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1];k=root/'app/src/main/kotlin/io/github/lujinxin/nextep'

# Only exact reviewed performance/animation fragments may differ. All task moves,
# geometry formulas, crop/input writers and the rest of each file remain frozen.
import hashlib
# The tablet branch is independently reviewed as exact reverse patches.
# Normalize only these known fragments before applying the existing frozen
# v0.9.17 -> v0.9.8 source chain. An unrelated edit still fails its hash.
tablet_review=json.loads((root/'scripts/reviewed-assistant4-native-landscape.json').read_text())['files']
assert set(tablet_review)=={
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/WorkspaceGeometry.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/workspace/StageLiveCardLayout.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/TopAppStripView.kt',
 'app/src/main/kotlin/io/github/lujinxin/nextep/systemui/ControlAreaPager.kt',
}
reviewed_chain=[json.loads((root/'scripts/reviewed-fast-tap-0.9.17.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-fixed-swap-0.9.16.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-session-hardening.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-session-integration.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-output-retention.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-exchange-0.9.14.json').read_text())['files']]+[json.loads((root/'scripts/reviewed-injection-0.9.13.json').read_text())['files']]+[json.loads((root/f'scripts/reviewed-refinements-{v}.json').read_text())['files'] for v in ['0.9.12','0.9.11']]
def protected_bytes(path):
 data=(root/path).read_bytes()
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
for path,sha in json.loads((root/'scripts/frozen-workspace-0.9.8.json').read_text()).items():
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
