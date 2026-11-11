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
    private var visibleActivities = 0

    fun enable(context: Context) {
        val application = context.applicationContext as? Application ?: return
        val session = StageIssueSession(application)
        if (!session.active() || session.isDue()) return
        val current = session.currentToken()
        if (app === application && token == current) return
        disable()
        app = application
        token = current
        visibleActivities = if (context is Activity) 1 else 0
        session.recordAutoEvent(current,
            if (visibleActivities > 0) "测试App前台页面可见" else "测试App前后台状态未知",
            StageIssueSceneSnapshot.screenLabel(application))

        lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                val old = visibleActivities
                visibleActivities++
                if (old == 0) record("测试App前台页面可见")
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
                if (visibleActivities == 0) record("测试App退到后台（无可见页面）")
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
                    Intent.ACTION_SCREEN_OFF -> "系统通知：屏幕关闭"
                    Intent.ACTION_SCREEN_ON -> "系统通知：屏幕打开（不代表已解锁）"
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
        visibleActivities = 0
        if (owner != null && registered != null)
            runCatching { owner.unregisterReceiver(registered) }
        if (owner != null && callbacks != null)
            runCatching { owner.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}
