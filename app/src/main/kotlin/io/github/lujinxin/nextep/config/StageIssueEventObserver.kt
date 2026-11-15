package io.github.x647368maker.atomicworkbench.config

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Only while a user-armed test is active AND this app process survives.
 * OS screen broadcasts, Activity lifecycle and actual configuration callbacks only.
 * No polling, service, wake lock, screenshot or running-app inspection.
 */
internal object StageIssueEventObserver {
    private var app: Application? = null
    private var receiver: BroadcastReceiver? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private var configurationCallbacks: ComponentCallbacks? = null
    private var lastConfiguration: String? = null
    private var token = -1L
    private val activityTracker = StageIssueForegroundTracker()

    fun enable(context: Context) {
        val application = context.applicationContext as? Application ?: return
        val session = StageIssueSession(application)
        if (!session.active() || session.isDue()) {
            if (app === application) disable()
            return
        }
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
                if (token == current && activityTracker.start(activity)) record(current, "测试App前台页面可见")
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                if (token == current && activityTracker.stop(activity)) record(current, "测试App退到后台（无可见页面）")
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(lifecycle)

        // This is App configuration, not physical hinge state. Do not infer fold
        // posture or successful layout from a callback. Ignore unrelated changes.
        lastConfiguration = configurationLabel(application.resources.configuration)
        val configListener = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                if (token != current) return
                val next = configurationLabel(newConfig)
                if (next == lastConfiguration) return
                lastConfiguration = next
                record(current, "设备配置变化：$next")
            }
            override fun onLowMemory() { /* no background sampling */ }
        }
        if (runCatching { application.registerComponentCallbacks(configListener) }.isSuccess) {
            configurationCallbacks = configListener
        } else record(current, "设备配置通知不可用")

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
                record(current, change)
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(application, listener, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED)
            true
        }.getOrDefault(false)
        if (registered) receiver = listener
        else record(current, "屏幕变化通知不可用（只保留开始/结束状态）")
    }

    private fun configurationLabel(c: Configuration): String =
        "${c.screenWidthDp}×${c.screenHeightDp}dp，方向=${c.orientation}，dpi=${c.densityDpi}"

    private fun record(expectedToken: Long, label: String) {
        val owner = app ?: return
        if (token != expectedToken) return
        val session = StageIssueSession(owner)
        // An inexact alarm can be delivered late after sleep. Stop observing on
        // the first subsequent event instead of reading screen state past deadline.
        if (!session.active() || session.currentToken() != expectedToken || session.isDue()) {
            disable()
            return
        }
        session.recordAutoEvent(expectedToken, label, StageIssueSceneSnapshot.screenLabel(owner))
    }

    fun disable() {
        val owner = app
        val registered = receiver
        val callbacks = lifecycle
        val config = configurationCallbacks
        app = null
        receiver = null
        lifecycle = null
        configurationCallbacks = null
        lastConfiguration = null
        token = -1L
        activityTracker.reset()
        if (owner != null && registered != null)
            runCatching { owner.unregisterReceiver(registered) }
        if (owner != null && callbacks != null)
            runCatching { owner.unregisterActivityLifecycleCallbacks(callbacks) }
        if (owner != null && config != null)
            runCatching { owner.unregisterComponentCallbacks(config) }
    }
}
