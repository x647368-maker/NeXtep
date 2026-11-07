package io.github.x647368maker.atomicworkbench.systemserver

import android.content.Context
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.SystemServerWorkspaceBridge

/** A parked side task must not replace the foreground app's input-method target. */
internal object WorkspaceImeTargetHook {
    private data class Move(val window: Any, val destination: Any)
    private val parkedWindow = ThreadLocal<Move?>()
    private val removeInputFocus by lazy {
        SurfaceControl.Transaction::class.java.getDeclaredMethod("removeCurrentInputFocus",
            Int::class.javaPrimitiveType).apply { isAccessible = true }
    }
    fun install(module: XposedModule, loader: ClassLoader) {
        val windowClass = loader.loadClass("com.android.server.wm.WindowState")
        val displayClass = loader.loadClass("com.android.server.wm.DisplayContent")
        val inputTargetClass = loader.loadClass("com.android.server.wm.InputTarget")
        module.hook(windowClass.getDeclaredMethod("onDisplayChanged", displayClass))
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val window = chain.thisObject ?: return chain.proceed()
                    val service = field(window, "mWmService") ?: return chain.proceed()
                    val context = field(service, "mContext") as? Context ?: return chain.proceed()
                    val destination = chain.getArg(0) ?: return chain.proceed()
                    val source = field(window, "mDisplayContent") ?: return chain.proceed()
                    val info = call(source, "getDisplayInfo") ?: return chain.proceed()
                    if ((field(info, "name") as? String)?.startsWith("NeXtep-slot-") != true ||
                        call(destination, "getDisplayId") != 0) return chain.proceed()
                    if (SystemServerWorkspaceBridge.isWorkspaceActive(context)) {
                        val transfersIme = field(source, "mImeInputTarget") === window
                        // The input channel is reused across displays. If the old
                        // display loses focus in the same traversal that display 0
                        // gains it, the old loss can arrive last and deactivate IMM.
                        // Release that focus before native reparent, without touching
                        // window geometry, delaying the move or requesting IME show.
                        if (field(source, "mCurrentFocus") === window && isFullscreen(window)) {
                            runCatching {
                                val sourceId = call(source, "getDisplayId") as? Int ?: return@runCatching
                                SurfaceControl.Transaction().use { transaction ->
                                    removeInputFocus.invoke(transaction, sourceId)
                                    transaction.apply()
                                }
                                NeXtepLog.info("workspace_ime_target", "Released slot input focus before main return")
                            }.onFailure { NeXtepLog.warn("workspace_ime_target", "Slot input focus release unavailable", it) }
                        }
                        if (!transfersIme || !isFullscreen(window)) return chain.proceed()
                        // Native onDisplayChanged transfers IME before changing the
                        // window's display. getImeControlTarget then consults the old
                        // slot's IME host; retaining that host can detach the main IME
                        // from its scaled app. Preserve native cleanup, but resolve
                        // the destination control target after display ownership moves.
                        val previous = parkedWindow.get()
                        parkedWindow.set(Move(window, destination))
                        val result = try {
                            chain.proceed()
                        } finally {
                            if (previous == null) parkedWindow.remove() else parkedWindow.set(previous)
                        }
                        runCatching {
                            if (field(window, "mDisplayContent") === destination) {
                                displayClass.getDeclaredMethod("updateImeInputAndControlTarget", inputTargetClass)
                                    .apply { isAccessible = true }.invoke(destination, window)
                            }
                        }.onFailure { NeXtepLog.warn("workspace_ime_target", "Destination IME handoff unavailable", it) }
                        return result
                    }
                    // Exit publishes the inactive gate before parking side tasks.
                    val focused = field(destination, "mCurrentFocus")
                    if (focused == null || focused === window) return chain.proceed()
                    val previous = parkedWindow.get()
                    parkedWindow.set(Move(window, destination))
                    return try {
                        chain.proceed()
                    } finally {
                        if (previous == null) parkedWindow.remove() else parkedWindow.set(previous)
                    }
                }
            })
        module.hook(displayClass.getDeclaredMethod("updateImeInputAndControlTarget", inputTargetClass))
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val move = parkedWindow.get()
                    if (move != null && chain.thisObject === move.destination &&
                        chain.getArg(0) === move.window) {
                        // WindowState transfers its old display's IME target before updating
                        // its own display. That is valid for foreground moves, but a side
                        // display's focused window is only being parked behind the main app.
                        // Keep native cleanup on the old display and all subsequent focus
                        // updates; suppress only this stale target transfer to display 0.
                        return null
                    }
                    return chain.proceed()
                }
            })
        NeXtepLog.info("workspace_ime_target", "Installed foreground IME preservation during slot parking")
    }

    private fun isFullscreen(window: Any): Boolean {
        val task = call(window, "getTask") ?: return false
        if (call(task, "getWindowingMode") != 1) return false
        val configuration = call(task, "getConfiguration") ?: return false
        val extra = field(configuration, "mOplusExtraConfiguration")
        return extra == null || call(extra, "getScenario") !in setOf(1, 2)
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true }?.invoke(target)
}
