package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.app.usage.UsageStatsManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository
import io.github.x647368maker.atomicworkbench.task.TaskRepository
import java.util.concurrent.Executors

/** Usage/recents reads run off the UI thread. All records remain on this device. */
internal class StageFrequentApps(context: Context) {
    private val app = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences = app.createDeviceProtectedStorageContext().getSharedPreferences("stage_frequent_apps_v1", Context.MODE_PRIVATE)
    private var cached = emptyList<String>()
    private var loading = false
    private val waiting = mutableListOf<(List<String>) -> Unit>()
    private var cachedAt = 0L
    private var lastObservation = ""
    private var lastObservationAt = 0L
    fun observe(packageName: String, userId: Int) {
        val key = "$userId:$packageName"; val now = System.currentTimeMillis()
        // Task/listener bursts and in-app Activity changes do not inflate frequency.
        if (key == lastObservation && now - lastObservationAt < 60_000) return
        lastObservation = key; lastObservationAt = now
        val count = (preferences.getInt("count:$key", 0) + 1).coerceAtMost(10000)
        preferences.edit().putInt("count:$key", count).putLong("last:$key", now).apply()
        cachedAt = 0
    }
    fun request(entries: List<LauncherAppRepository.AppEntry>, callback: (List<LauncherAppRepository.AppEntry>) -> Unit) {
        val byKey = entries.associateBy { it.key }
        if (cached.isNotEmpty() || cachedAt > 0) callback(cached.mapNotNull(byKey::get))
        if (SystemClock.uptimeMillis() - cachedAt < 30_000 && cachedAt > 0) return
        waiting += { keys -> callback(keys.mapNotNull(byKey::get)) }
        if (loading) return
        loading = true
        worker.execute {
            val now = System.currentTimeMillis()
            val records = mutableListOf<StageFrequentAppPolicy.Usage>()
            val byPackage = entries.groupBy { it.userId to it.component.packageName }
            entries.forEach { entry ->
                val key = "${entry.userId}:${entry.component.packageName}"
                val last = preferences.getLong("last:$key", 0)
                if (last > 0) records += StageFrequentAppPolicy.Usage(entry.key, last, selections = preferences.getInt("count:$key", 0))
            }
            val repository = TaskRepository(app)
            val launcher = LauncherAppRepository(app)
            entries.map { it.userId }.distinct().forEach { user ->
                repository.recentTasks(user).getOrNull().orEmpty().forEachIndexed { index, task ->
                    val options = byPackage[task.userId to task.baseComponent.packageName].orEmpty()
                    val entry = options.firstOrNull()
                    if (entry != null) {
                        val elapsed = SystemClock.elapsedRealtime()
                        val wallTime = if (task.lastActiveTime in 1..elapsed) now - (elapsed - task.lastActiveTime)
                            else now - index * 60_000L
                        records += StageFrequentAppPolicy.Usage(entry.key, wallTime)
                    }
                }
                // Some ROMs deny usage access to SystemUI; real system recents remain the fallback.
                runCatching {
                    val userContext = launcher.contextForUser(user) ?: return@runCatching
                    val manager = userContext.getSystemService(UsageStatsManager::class.java) ?: return@runCatching
                    manager.queryAndAggregateUsageStats(now - 7L * 24 * 60 * 60 * 1000, now).forEach { (pkg, stats) ->
                        val entry = byPackage[user to pkg]?.firstOrNull() ?: return@forEach
                        if (stats.lastTimeUsed > 0) records += StageFrequentAppPolicy.Usage(entry.key, stats.lastTimeUsed, stats.totalTimeInForeground)
                    }
                }
            }
            val keys = StageFrequentAppPolicy.select(entries.distinctBy { it.userId to it.component.packageName }.map { it.key }, records, now)
            handler.post {
                cached = keys; cachedAt = SystemClock.uptimeMillis()
                loading = false
                val callbacks = waiting.toList(); waiting.clear()
                callbacks.forEach { it(keys) }
            }
        }
    }
}
