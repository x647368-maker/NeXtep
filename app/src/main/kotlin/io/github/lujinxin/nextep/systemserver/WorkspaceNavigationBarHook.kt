package io.github.x647368maker.atomicworkbench.systemserver

import android.content.Context
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.SystemServerWorkspaceBridge
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceNavigationInsets

/** Keep the reserved physical navigation bar visible when a game requests immersive mode. */
internal object WorkspaceNavigationBarHook {
    fun install(module: XposedModule, loader: ClassLoader) {
        val policyClass = loader.loadClass("com.android.server.wm.InsetsPolicy")
        val windowClass = loader.loadClass("com.android.server.wm.WindowState")
        val method = policyClass.getDeclaredMethod("updateSystemBars", windowClass,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val displayField = policyClass.getDeclaredField("mDisplayContent").apply { isAccessible = true }
        val displayClass = loader.loadClass("com.android.server.wm.DisplayContent")
        val getDisplayId = generateSequence(displayClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod("getDisplayId") }.getOrNull() }
            .first().apply { isAccessible = true }
        val serviceField = generateSequence(displayClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField("mWmService") }.getOrNull() }
            .first().apply { isAccessible = true }
        val contextField = loader.loadClass("com.android.server.wm.WindowManagerService")
            .getDeclaredField("mContext").apply { isAccessible = true }
        fun recordedButtons(display: Any?): Boolean = runCatching {
            if (display == null || getDisplayId.invoke(display) != 0) return@runCatching false
            val service = serviceField.get(display) ?: return@runCatching false
            val context = contextField.get(service) as? Context ?: return@runCatching false
            SystemServerWorkspaceBridge.startGeometryObservation(context)
            val geometry = SystemServerWorkspaceBridge.observedActiveGeometry()
                ?: return@runCatching false
            geometry.navigationInsets != WorkspaceNavigationInsets()
        }.getOrDefault(false)
        // ROM-compiled callers can inline the original policy update. Deoptimize
        // this one caller so the forced visibility mask reaches our hook.
        val displayPolicyClass = loader.loadClass("com.android.server.wm.DisplayPolicy")
        displayPolicyClass.declaredMethods
            .filter { it.name == "updateSystemBarsLw" }.forEach { module.deoptimize(it) }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val keepNavigation = recordedButtons(chain.thisObject?.let(displayField::get))
                if (!keepNavigation) return chain.proceed()
                val args = chain.args.toTypedArray()
                val navigation = WindowInsets.Type.navigationBars()
                args[1] = (args[1] as Int) or navigation
                args[2] = (args[2] as Int) and navigation.inv()
                return chain.proceed(args)
            }
        })
        val policyDisplay = displayPolicyClass.getDeclaredField("mDisplayContent").apply { isAccessible = true }
        val attach = displayPolicyClass.getDeclaredMethod("shouldAttachNavBarToAppDuringTransition")
            .apply { isAccessible = true }
        module.hook(attach).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // The physical bar belongs outside every scaled task/slot. Let
                // native policy restore its normal parenting when workspace exits.
                if (recordedButtons(chain.thisObject?.let(policyDisplay::get))) return false
                return chain.proceed()
            }
        })
        displayPolicyClass.declaredMethods.filter { it.name == "updateSystemBarAttributes" }
            .forEach { module.deoptimize(it) }
        val light = displayPolicyClass.getDeclaredMethod("updateLightNavigationBarLw",
            Int::class.javaPrimitiveType, windowClass).apply { isAccessible = true }
        module.hook(light).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val appearance = chain.proceed() as Int
                // The reserved bar shows the workspace wallpaper, not the white
                // background of the logical fullscreen app that requested dark keys.
                return if (recordedButtons(chain.thisObject?.let(policyDisplay::get))) {
                    appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS.inv()
                } else appearance
            }
        })
        NeXtepLog.info("workspace_navigation", "Installed navigation visibility for recorded button layouts")
    }
}
