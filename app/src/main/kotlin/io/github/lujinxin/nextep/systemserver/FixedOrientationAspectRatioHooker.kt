package io.github.x647368maker.atomicworkbench.systemserver

import android.content.Context
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.SystemServerWorkspaceBridge
import io.github.libxposed.api.XposedInterface

class FixedOrientationAspectRatioHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val context = findContext(chain.thisObject) ?: return chain.proceed()
        val physicalAspect = SystemServerWorkspaceBridge.activePhysicalAspectRatio(context)
            ?: return chain.proceed()
        val compactSlot = runCatching {
            if (SystemServerWorkspaceBridge.observedActiveGeometry()?.railAtTop != true) return@runCatching false
            val activity = readField(chain.thisObject, "mActivityRecord") ?: return@runCatching false
            val display = call(activity, "getDisplayContent") ?: return@runCatching false
            val info = call(display, "getDisplayInfo") ?: return@runCatching false
            (readField(info, "name") as? String).orEmpty().startsWith("NeXtep-slot-")
        }.getOrDefault(false)
        val aspectRatio = if (compactSlot) 1120f / 720f else physicalAspect
        NeXtepLog.info("fixed_orientation_aspect", "Using recorded usable aspectRatio=$aspectRatio")
        return aspectRatio
    }

    private fun call(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true }?.invoke(target)

    private fun findContext(target: Any?): Context? = runCatching {
        val activityRecord = readField(target, "mActivityRecord") ?: return@runCatching null
        val service = readField(activityRecord, "mAtmService") ?: return@runCatching null
        readField(service, "mContext") as? Context
    }.onFailure { error ->
        NeXtepLog.warn("fixed_orientation_aspect", "Context lookup failed open", error)
    }.getOrNull()

    private fun readField(target: Any?, name: String): Any? {
        var type: Class<*>? = target?.javaClass
        while (type != null && type != Any::class.java) {
            runCatching {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }
            type = type.superclass
        }
        return null
    }
}
