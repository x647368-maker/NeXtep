package io.github.x647368maker.atomicworkbench.systemserver

import android.content.Context
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.SystemServerWorkspaceBridge

/** Keep workspace animation backgrounds below the stable system windows. */
internal object WorkspaceDisplayHierarchyHook {
    fun install(module: XposedModule, loader: ClassLoader, nativeLayers: NativeFloatingWindowLayerHooker) {
        val method = loader.loadClass("com.android.server.wm.Transition").declaredMethods.single {
            it.name == "calculateTransitionRoots" && it.parameterCount == 3
        }.apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    val targets = chain.args[1] as? List<*> ?: return@runCatching
                    val nativeTransition = targets.any { it != null && isNativeWindowingChange(it) }
                    val movedTask = targets.firstNotNullOfOrNull { change ->
                        change ?: return@firstNotNullOfOrNull null
                        val container = field(change, "mContainer") ?: return@firstNotNullOfOrNull null
                        val display = call(container, "getDisplayContent") ?: return@firstNotNullOfOrNull null
                        val from = field(change, "mDisplayId") as? Int ?: return@firstNotNullOfOrNull null
                        val to = call(display, "getDisplayId") as? Int ?: return@firstNotNullOfOrNull null
                        val service = field(container, "mAtmService") ?: return@firstNotNullOfOrNull null
                        val root = field(service, "mRootWindowContainer") ?: return@firstNotNullOfOrNull null
                        if (nativeTransition) {
                            // Include same-display notification launches and native closes,
                            // not just tasks pulled out of a NeXtep virtual display.
                            return@firstNotNullOfOrNull if (to == 0 && isNativeWindowingChange(change)) {
                                service to root
                            } else null
                        }
                        if (from == to || (from != 0 && to != 0)) return@firstNotNullOfOrNull null
                        val slot = call(root, "getDisplayContent", if (from == 0) to else from)
                            ?: return@firstNotNullOfOrNull null
                        val info = call(slot, "getDisplayInfo") ?: return@firstNotNullOfOrNull null
                        if ((field(info, "name") as? String)?.startsWith("NeXtep-slot-") != true) {
                            return@firstNotNullOfOrNull null
                        }
                        service to root
                    } ?: return@runCatching
                    val context = field(movedTask.first, "mContext") as? Context ?: return@runCatching
                    if (!SystemServerWorkspaceBridge.isWorkspaceActive(context)) return@runCatching
                    val physical = call(movedTask.second, "getDefaultDisplay") ?: return@runCatching
                    // A real display rotation/resize must retain its native hierarchy.
                    if (targets.any { change ->
                        change != null && field(change, "mContainer") === physical &&
                            (field(change, "mRotation") != call(physical, "getRotation") ||
                                field(change, "mAbsoluteBounds") != call(physical, "getBounds"))
                    }) return@runCatching
                    val info = chain.args[0] ?: return@runCatching
                    val index = call(info, "findRootIndex", 0) as? Int ?: return@runCatching
                    if (index < 0) return@runCatching
                    val leash = call(call(info, "getRoot", index) ?: return@runCatching, "getLeash")
                        as? SurfaceControl ?: return@runCatching
                    if (!leash.isValid || (!nativeTransition &&
                            !leash.toString().contains("Transition Root: Display 0"))) return@runCatching
                    val taskArea = call(physical, "getDefaultTaskDisplayArea") ?: return@runCatching
                    // The native animation root stays inside the task area so a
                    // later Shell setLayer cannot move its background above PHONE
                    // windows. Do not create a cycle if the area itself is animated.
                    if (nativeTransition && targets.any {
                            it != null && field(it, "mContainer") === taskArea
                        }) return@runCatching
                    val parent = if (nativeTransition) taskArea else
                        call(taskArea, "getParent") ?: return@runCatching
                    val parentLeash = call(parent, "getSurfaceControl") as? SurfaceControl ?: return@runCatching
                    if (!parentLeash.isValid) return@runCatching
                    val transaction = chain.args[2] as? SurfaceControl.Transaction ?: return@runCatching
                    // A notification launch can use a Task-rooted animation instead
                    // of "Display 0". Its fullscreen wallpaper/starting background
                    // must stay below our PHONE windows from the first transaction.
                    // Lift only native floating tasks above those windows, preserving
                    // Shell's task matrices, root offsets and animation lifecycle.
                    if (nativeTransition) {
                        nativeLayers.adjustDisplayLayers(physical, transaction, workspaceConfirmedActive = true)
                    }
                    transaction.reparent(leash, parentLeash).setLayer(leash, 0)
                    NeXtepLog.info("workspace_display_hierarchy", "Kept animation background below workspace windows native=$nativeTransition")
                }.onFailure { NeXtepLog.warn("workspace_display_hierarchy", "Exchange root fitting unavailable", it) }
                return result
            }
        })
        NeXtepLog.info("workspace_display_hierarchy", "Installed scoped workspace animation hierarchy")
    }

    private fun isNativeWindowingChange(change: Any): Boolean {
        val originalMode = field(change, "mWindowingMode")
        if (originalMode == 5 || originalMode == 2 || field(change, "mIsAlwaysOnTop") == true) return true
        val container = field(change, "mContainer") ?: return false
        val mode = call(container, "getWindowingMode")
        if (mode == 5 || mode == 2) return true
        val config = call(container, "getConfiguration") ?: return false
        val extra = field(config, "mOplusExtraConfiguration") ?: return false
        return call(extra, "getScenario") in setOf(1, 2)
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String, vararg args: Any): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == args.size }
            ?.apply { isAccessible = true }?.invoke(target, *args)
}
