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
event = (app / "StageIssueEventObserver.kt").read_text()
assert "Intent.ACTION_SCREEN_OFF" in event and "Intent.ACTION_SCREEN_ON" in event
assert "Intent.ACTION_USER_PRESENT" in event
assert "Application.ActivityLifecycleCallbacks" in event
assert "RECEIVER_NOT_EXPORTED" in event
assert "unregisterReceiver" in event and "unregisterActivityLifecycleCallbacks" in event
assert "while (true)" not in event
assert ".postDelayed(" not in event
assert "WakeLock" not in event
# Keep the three original user/session-triggered queries, now with an explicit
# opt-in trace flag at start/end. The ordinary marker must remain lightweight.
assert activity.count("WorkspaceEnergyContract.query(") == 3
assert activity.count("WorkspaceEnergyContract.query(this, includeExchangeDiagnostics = true)") == 2
assert activity.count("WorkspaceEnergyContract.query(this)") == 1
assert alarm.count("WorkspaceEnergyContract.query(") == 1
assert "WorkspaceEnergyContract.query(context.applicationContext, includeExchangeDiagnostics = true)" in alarm
contract = (root / "app/src/main/kotlin/io/github/lujinxin/nextep/monitoring/WorkspaceEnergyContract.kt").read_text()
assert 'intent.getBooleanExtra("include_exchange_diagnostics", false)' in contract
assert 'query(context, false, onResult)' in contract
assert contract.count("StageExchangeTelemetry.describe()") == 1
assert 'TriggerBroadcastContract.CONTROL_PERMISSION' in contract
assert 'snapshot?.processId, snapshot?.exchangeDiagnostics' in session
assert 'StageIssueReportPolicy.exchangeSection(' in session
assert 'data.getString("exchange_diagnostics")' in contract
assert 'result.putString("exchange_diagnostics",' in contract
print("PASS: opt-in diagnostics use one non-wakeup alarm, a UI callback, no background poller or exported receiver")
