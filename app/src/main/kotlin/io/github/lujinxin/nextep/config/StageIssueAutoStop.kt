package io.github.x647368maker.atomicworkbench.config

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.x647368maker.atomicworkbench.monitoring.BatterySample
import io.github.x647368maker.atomicworkbench.monitoring.WorkspaceEnergyContract

/**
 * One non-wakeup, inexact alarm per opted-in session. Does not wake a sleeping
 * phone and needs no exact-alarm permission. Doze may postpone delivery.
 */
internal object StageIssueAutoStop {
    const val ACTION = "io.github.x647368maker.atomicworkbench.action.ISSUE_TIMEOUT"

    private fun intent(context: Context, token: Long) = Intent(context, StageIssueTimeoutReceiver::class.java)
        .setAction(ACTION)
        .setData(Uri.parse("stageissue://deadline/$token"))
        .putExtra("token", token)

    private fun pending(context: Context, token: Long) = PendingIntent.getBroadcast(
        context, 0, intent(context, token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, token: Long, durationMs: Long): Boolean = runCatching {
        val manager = checkNotNull(context.getSystemService(AlarmManager::class.java))
        // ELAPSED_REALTIME (without _WAKEUP): no forced wake just for diagnostics.
        manager.set(AlarmManager.ELAPSED_REALTIME,
            Math.addExact(token, StageIssueReportPolicy.selectedDuration(durationMs)),
            pending(context,token))
        true
    }.getOrDefault(false)

    fun cancel(context: Context, token: Long) {
        if (token < 0L) return
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.cancel(pending(context,token))
        }
    }
}

class StageIssueTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != StageIssueAutoStop.ACTION) return
        val token = intent.getLongExtra("token", -1L)
        val session = StageIssueSession(context)
        if (!session.active() || session.currentToken() != token || !session.isDue()) return

        val result = goAsync()
        // One on-demand snapshot, with 1.5s fallback. No permanent monitors.
        runCatching {
            WorkspaceEnergyContract.query(context.applicationContext) { snapshot ->
                try {
                    val current = StageIssueSession(context)
                    if (current.active() && current.currentToken() == token && current.isDue()) {
                        current.finish(current.storedVersion(), current.storedNote(),
                            BatterySample.read(context.applicationContext), snapshot,
                            "定时到期（系统可能延期交付）")
                    }
                } finally { result.finish() }
            }
        }.onFailure {
            try {
                val current = StageIssueSession(context)
                if (current.active() && current.currentToken()==token && current.isDue())
                    current.finish(current.storedVersion(),current.storedNote(),
                        BatterySample.read(context.applicationContext),null,
                        "定时到期（工作台状态不可用）")
            } finally { result.finish() }
        }
    }
}
