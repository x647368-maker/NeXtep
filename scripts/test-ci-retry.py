#!/usr/bin/env python3
import os,pathlib,shutil,subprocess,tempfile
script=pathlib.Path(__file__).with_name('ci-android-build.sh')
scenarios=[('network_then_ok',0,2),('network_always',9,3),('compiler',9,1),('tests',9,1),('not_found',9,1)]
for scenario,expected,attempts in scenarios:
 with tempfile.TemporaryDirectory() as folder:
  r=pathlib.Path(folder);(r/'scripts').mkdir();shutil.copyfile(script,r/'scripts/ci-android-build.sh')
  (r/'gradlew').write_text('''#!/usr/bin/env bash
n=0; if [ -f count ]; then read -r n < count; fi
n=$((n+1)); echo "$n" > count; echo "$*" > "args-$n"
case "$SCENARIO" in
 network_then_ok) if [ "$n" -gt 1 ]; then exit 0; fi; echo 'Could not GET https://example.invalid/dependency HTTP 504';;
 network_always) echo 'Could not GET https://example.invalid/dependency status code 504';;
 compiler) echo 'e: file:///source/App.kt:3: Unresolved reference'; echo 'Could not GET https://example.invalid HTTP 504';;
 tests) echo 'There were failing tests';;
 not_found) echo 'Could not GET https://example.invalid/dependency status code 404';;
esac
exit 9
''')
  env=dict(os.environ,SCENARIO=scenario,ATOMIC_RETRY_DELAY_SECONDS='0')
  p=subprocess.run(['bash',str(r/'scripts/ci-android-build.sh'),':app:assembleDebug'],env=env,capture_output=True,text=True)
  assert p.returncode==expected,(scenario,p.stdout,p.stderr)
  assert int((r/'count').read_text())==attempts,(scenario,p.stdout)
  assert '--refresh-dependencies' not in (r/'args-1').read_text()
  for retry in range(2,attempts+1):
   assert '--refresh-dependencies' in (r/f'args-{retry}').read_text()
   assert '--no-configuration-cache' in (r/f'args-{retry}').read_text()
print('5 retry scenarios passed: transient-only retries; compiler/tests/404 fail immediately; maximum 3 attempts')
