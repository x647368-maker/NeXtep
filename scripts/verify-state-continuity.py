#!/usr/bin/env python3
"""Source boundaries, not device playback tests. Fail if passive paths regain side effects."""
from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1];k=root/'app/src/main/kotlin/io/github/lujinxin/nextep'
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
assert 'shouldMove' in function(s,'moveTaskToDisplayAndWait')
assert 'TaskContinuityPolicy.canTransport' in function((k/'systemui/MediaControlView.kt').read_text(),'togglePlayback')
settings=(k/'config/StageSettingsScreen.kt').read_text()
assert 'Item("选择主应用"' not in settings
assert 'engine.openInMain' in (k/'systemui/NeXtepWindowController.kt').read_text()
host=(k/'systemui/NeXtepWindowController.kt').read_text()
assert 'contentDescription = "展开常用应用快捷栏"' in host and 'setOnClickListener { setQuickExpanded(true) }' in host
assert 'FrameLayout.LayoutParams(dp(48).toInt(), dp(48).toInt()' in host
print(json.dumps({'passive_paths_send_no_media_commands':True,'exchanges_do_not_launch_apps':True,'layout_does_not_resize_or_recreate_displays':True,'exact_display_move_is_idempotent':True,'home_entry_removed_controls_retained':True,'quick_bar_direct_48dp_entry':True,'device_playback_verified':False}))
