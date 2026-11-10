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
        val activity = readField(chain.thisObject, "mActivityRecord")
        val snapshot = StageOrientationHookScope.snapshot(activity)
        val aspectRatio = StageOrientationHookScope.aspectOverride(
            snapshot,
            topRail = SystemServerWorkspaceBridge.observedActiveGeometry()?.railAtTop == true,
            physicalAspect = physicalAspect,
        ) ?: return chain.proceed()
        NeXtepLog.info("fixed_orientation_aspect", "Scoped aspectRatio=$aspectRatio target=${snapshot.target}")
        return aspectRatio
    }

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
            val current = type
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }
            type = current.superclass
        }
        return null
    }
}
