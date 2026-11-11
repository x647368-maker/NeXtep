package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.trigger.TriggerBroadcastContract
import java.util.concurrent.Executors

/** One shared asynchronous catalogue, warmed before the user opens the chooser. */
internal class StageAppCatalog(context: Context) {
    private val app = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var cached: List<LauncherAppRepository.AppEntry>? = null
    private var loading = false
    private var generation = 0
    private val waiting = mutableListOf<(Result<List<LauncherAppRepository.AppEntry>>) -> Unit>()
    init {
        handler.postDelayed({ load() }, 1500)
        runCatching { app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                generation++; cached = null
                if (!loading) load()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED) }
        runCatching { app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                generation++; cached = null; if (!loading) load()
            }
        }, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED) }
    }
    fun request(callback: (Result<List<LauncherAppRepository.AppEntry>>) -> Unit) {
        cached?.let { callback(Result.success(it)); return }
        waiting += callback; load()
    }
    private fun load() {
        if (loading) return
        loading = true
        val version = generation
        executor.execute {
            val result = runCatching {
                val repository = LauncherAppRepository(app)
                val entries = StageAppCatalogLoadPolicy.load(
                    allProfiles = { repository.load() },
                    currentUser = { repository.load(includeProfiles = false) },
                    onFullFailure = { error ->
                        // Clone/profile service failures must not block regular
                        // app selection on phones or the Y700 custom ROM.
                        NeXtepLog.error("app_catalog", "All-profile query failed; retrying current user", error)
                    },
                )
                val selectable = entries.filter {
                    it.component.packageName != TriggerBroadcastContract.MODULE_PACKAGE
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                check(selectable.isNotEmpty()) { "No selectable launcher activities visible in the SystemUI host" }
                selectable
            }
            result.onFailure { NeXtepLog.error("app_catalog", "Could not load picker catalog", it) }
            handler.post {
                loading = false
                if (version != generation) { load(); return@post }
                result.onSuccess { cached = it }
                val callbacks = waiting.toList(); waiting.clear()
                callbacks.forEach { it(result) }
            }
        }
    }
}
