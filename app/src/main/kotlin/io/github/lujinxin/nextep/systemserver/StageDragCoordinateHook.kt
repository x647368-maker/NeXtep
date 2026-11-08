package io.github.x647368maker.atomicworkbench.systemserver

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.x647368maker.atomicworkbench.workspace.SystemServerWorkspaceBridge
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog

/** WMS drag coordinates use window frames, not our task-surface fitting matrix.
 * Scope to DragState calls on standard fullscreen app windows; leave permission
 * checks, ClipData and normal touch dispatch entirely to Android. */
internal object StageDragCoordinateHook {
    fun install(module: XposedModule, loader: ClassLoader) {
        val window = loader.loadClass("com.android.server.wm.WindowState")
        for (axis in listOf("X", "Y")) {
            val method = window.getDeclaredMethod("translateToWindow$axis", Float::class.javaPrimitiveType).apply { isAccessible = true }
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val args = chain.args.toTypedArray()
                    runCatching {
                        val g = SystemServerWorkspaceBridge.observedActiveGeometry() ?: return@runCatching
                        if (Thread.currentThread().stackTrace.none { it.className == "com.android.server.wm.DragState" }) return@runCatching
                        val target = chain.thisObject ?: return@runCatching
                        if (call(target, "getDisplayId") != 0) return@runCatching
                        val task = call(target, "getTask") ?: return@runCatching
                        if (call(task, "getWindowingMode") != 1 || call(task, "getActivityType") != 1) return@runCatching
                        val config = call(task, "getConfiguration")
                        val extra = config?.let { field(it, "mOplusExtraConfiguration") }
                        if (extra != null && call(extra, "getScenario") in setOf(1, 2)) return@runCatching
                        val value = chain.getArg(0) as Float
                        val translation = if (axis == "X") g.contentTranslationX else g.contentTranslationY
                        args[0] = (value - translation) / g.contentScale
                    }
                    return chain.proceed(args)
                }
            })
        }
        NeXtepLog.info("stage_drag", "Installed scoped native drag coordinate fitting")
    }
    private fun call(target: Any, name: String): Any? = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }.firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.apply { isAccessible = true }?.invoke(target)
    private fun field(target: Any, name: String): Any? = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.firstOrNull()?.apply { isAccessible = true }?.get(target)
}
