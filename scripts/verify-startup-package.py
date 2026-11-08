#!/usr/bin/env python3
"""Inspect final compiled resources and manifest, not just editable source XML."""
import argparse,re,subprocess,zipfile,json
p=argparse.ArgumentParser();p.add_argument('--apk',required=True);p.add_argument('--aapt2',required=True);args=p.parse_args()
def dump(*command):return subprocess.run([args.aapt2,'dump',*command],check=True,capture_output=True,text=True).stdout
resources=dump('resources',args.apk)
blocks={}
for m in re.finditer(r'^    resource (0x[0-9a-f]+) ([^\n]+)\n(.*?)(?=^    resource |^  type |\Z)',resources,re.M|re.S):
 blocks[m[2]]=(m[1],m[3])
icon_id=blocks['drawable/ic_atomic_workbench'][0]
for name in ['style/Theme.NeXtep','style/Theme.StageLauncherEntry']:
 body=blocks[name][1]
 assert body.count('@drawable/ic_atomic_workbench') >= (2 if name == 'style/Theme.NeXtep' else 1),(name,body)
 assert '@mipmap/ic_splash_round' not in body,(name,body)
 assert body.count('@color/stage_background') >= (2 if name == 'style/Theme.NeXtep' else 1),(name,body)
colors=blocks['color/stage_background'][1].lower()
assert '#fff0f1f3' in colors and '#ff111214' in colors,colors
for name in ['ic_launcher','ic_launcher_round','ic_splash_round','ic_launcher_spring','ic_launcher_summer','ic_launcher_autumn','ic_launcher_winter','ic_launcher_night']:
 body=blocks['mipmap/'+name][1]
 assert '@drawable/ic_atomic_workbench' in body and '(file)' not in body,(name,body)
manifest=dump('xmltree',args.apk,'--file','AndroidManifest.xml')
lines=manifest.splitlines()
def elements(kind):
 for i,line in enumerate(lines):
  m=re.match(r'^(\s*)E: '+re.escape(kind)+r'\b',line)
  if not m:continue
  indent=len(m[1]);body=[]
  for next_line in lines[i+1:]:
   element=re.match(r'^(\s*)E:',next_line)
   if element:break
   body.append(next_line)
  yield '\n'.join(body)
app=next(elements('application'))
assert f':icon(0x01010002)=@{icon_id}' in app,app
assert f':roundIcon(0x0101052c)=@{icon_id}' in app,app
assert '@'+blocks['style/Theme.NeXtep'][0] in app,app
aliases=list(elements('activity-alias'));assert len(aliases)==6
for alias in aliases:
 assert f':icon(0x01010002)=@{icon_id}' in alias,alias
 assert 'io.github.x647368maker.atomicworkbench.icons.LauncherEntryActivity' in alias,alias
entry=next(x for x in elements('activity') if '.icons.LauncherEntryActivity' in x)
assert '@'+blocks['style/Theme.StageLauncherEntry'][0] in entry,entry
assert ':noHistory(0x0101022d)=true' in entry,entry
with zipfile.ZipFile(args.apk) as z:
 assert not any('mipmap-' in n and '/ic_launcher' in n and n.endswith('.webp') for n in z.namelist())
print(json.dumps({'compiled_day_and_night_splash':'stage identity','backgrounds':'match settings colors','launcher_aliases':len(aliases),'legacy_icon_aliases':8,'no_density_upstream_launcher_bitmaps':True,'physical_cold_warm_and_coloros_snapshot_verified':False},indent=2))
