#!/usr/bin/env python3
"""Verify a manual opt-in, one-shot, non-wakeup issue reporter never becomes a poller."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
app = root / "app/src/main/kotlin/io/github/lujinxin/nextep/config"
alarm = (app / "StageIssueAutoStop.kt").read_text()
activity = (app / "StageIssueDiagnosticActivity.kt").read_text()
session = (app / "StageIssueSession.kt").read_text()
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
assert "manager.set(AlarmManager.ELAPSED_REALTIME," in alarm
assert "ELAPSED_REALTIME_WAKEUP" not in alarm
assert "RTC_WAKEUP" not in alarm
assert ".setRepeating(" not in alarm
assert ".setExact(" not in alarm
assert "android.permission.SCHEDULE_EXACT_ALARM" not in manifest
assert 'android:name=".config.StageIssueTimeoutReceiver"' in manifest
assert 'android:exported="false"' in manifest.split('android:name=".config.StageIssueTimeoutReceiver"')[1].split("/>")[0]
assert "registerForActivityResult(ActivityResultContracts.CreateDocument" in activity
assert "uiHandler.postDelayed(uiDeadline," in activity
assert "HandlerThread" not in (activity + session + alarm)
assert "while (true)" not in (activity + session + alarm)
assert "ScheduledExecutorService" not in (activity + session + alarm)
assert "WakeLock" not in (activity + session + alarm)
assert ".registerReceiver(" not in (activity + session + alarm)
assert "query(this)" in activity and "query(context.applicationContext)" in alarm
print("PASS: opt-in diagnostics use one non-wakeup alarm, a UI callback, no background poller or exported receiver")
