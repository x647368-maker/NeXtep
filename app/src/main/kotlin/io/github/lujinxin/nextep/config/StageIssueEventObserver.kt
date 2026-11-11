package io.github.x647368maker.atomicworkbench.config

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Only while a user-armed test is active AND this app process survives.
 * OS screen broadcasts and Activity lifecycle transitions only.
 * No polling, service, wake lock, screenshot or running-app inspection.
 */
internal object StageIssueEventObserver {
    private var app: Application? = null
    private var receiver: BroadcastReceiver? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private var token = -1L
    private val activityTracker = StageIssueForegroundTracker()

    fun enable(context: Context) {
        val application = context.applicationContext as? Application ?: return
        val session = StageIssueSession(application)
        if (!session.active() || session.isDue()) return
        val current = session.currentToken()
        if (app === application && token == current) return
        disable()
        app = application
        token = current
        activityTracker.reset()
        // The registration happens during onResume, after onStart. Seed the
        // visible page; older already-started activities may stop afterward.
        // Ignore their unobserved onStop rather than invent a background event.
        if (context is Activity) activityTracker.seed(context)
        session.recordAutoEvent(current,
            if (activityTracker.isVisible()) "测试App前台页面可见" else "测试App前后台状态未知",
            StageIssueSceneSnapshot.screenLabel(application))

        lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (activityTracker.start(activity)) record("测试App前台页面可见")
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                if (activityTracker.stop(activity)) record("测试App退到后台（无可见页面）")
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(lifecycle)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                val change = when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> "系统通知：退出交互（通常息屏，仍以物理屏状态为准）"
                    Intent.ACTION_SCREEN_ON -> "系统通知：进入交互（不代表已解锁）"
                    Intent.ACTION_USER_PRESENT -> "系统通知：用户解锁"
                    else -> return
                }
                record(change)
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(application, listener, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED)
            true
        }.getOrDefault(false)
        if (registered) receiver = listener
        else record("屏幕变化通知不可用（只保留开始/结束状态）")
    }

    private fun record(label: String) {
        val owner = app ?: return
        StageIssueSession(owner).recordAutoEvent(token, label, StageIssueSceneSnapshot.screenLabel(owner))
    }

    fun disable() {
        val owner = app
        val registered = receiver
        val callbacks = lifecycle
        app = null
        receiver = null
        lifecycle = null
        token = -1L
        activityTracker.reset()
        if (owner != null && registered != null)
            runCatching { owner.unregisterReceiver(registered) }
        if (owner != null && callbacks != null)
            runCatching { owner.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}
