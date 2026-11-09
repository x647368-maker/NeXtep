package io.github.x647368maker.atomicworkbench.framework

import android.graphics.Rect
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object WindowContainerTransactionCompat {
    @Volatile private var defaultTaskDisplayArea: Any? = null
    private val taskDisplayAreas = ConcurrentHashMap<Int, Any>()

    fun observeDisplayArea(info: Any) {
        val type = info.javaClass
        if (type.getField("featureId").getInt(info) == 1) {
            val displayId = type.getField("displayId").getInt(info)
            val token = type.getField("token").get(info) ?: return
            taskDisplayAreas[displayId] = token
            if (displayId == 0) defaultTaskDisplayArea = token
        }
    }

    fun forgetDisplayArea(info: Any) {
        val type = info.javaClass
        if (type.getField("featureId").getInt(info) != 1) return
        val id = type.getField("displayId").getInt(info)
        val token = type.getField("token").get(info) ?: return
        taskDisplayAreas.remove(id, token)
        if (id == 0 && defaultTaskDisplayArea == token) defaultTaskDisplayArea = null
    }

    /** Reparent below existing default-display tasks, without briefly foregrounding each app. */
    fun parkInDefaultDisplay(token: Any, bounds: Rect, densityDpi: Int, mode: Int): Result<Unit> = runCatching {
        val parent = checkNotNull(defaultTaskDisplayArea) { "Default task display area unavailable" }
        val transaction = Class.forName("android.window.WindowContainerTransaction")
            .getDeclaredConstructor().newInstance()
        invoke(transaction, "setWindowingMode", token, mode)
        invoke(transaction, "setBounds", token, Rect(bounds))
        invoke(transaction, "setDensityDpi", token, densityDpi)
        invoke(transaction, "reparent", token, parent, false)
        applyTransaction(transaction)
    }

    fun applyTaskBounds(token: Any, bounds: Rect): Result<Unit> = runCatching {
        val transactionClass = Class.forName("android.window.WindowContainerTransaction")
        val transaction = transactionClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()

        invoke(transaction, "setBounds", token, Rect(bounds))
        applyTransaction(transaction)
        NeXtepLog.info(
            "window_container_transaction",
            "Applied fullscreen bounds=$bounds",
        )
    }.onFailure { error ->
        NeXtepLog.error(
            "window_container_transaction",
            "Fullscreen task bounds transaction failed",
            error,
        )
    }

    fun applyTaskLayout(
        token: Any,
        bounds: Rect,
        densityDpi: Int,
        windowingMode: Int,
    ): Result<Unit> = runCatching {
        val transactionClass = Class.forName("android.window.WindowContainerTransaction")
        val transaction = transactionClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()

        invoke(transaction, "setWindowingMode", token, windowingMode)
        invoke(transaction, "setBounds", token, Rect(bounds))
        invoke(transaction, "setDensityDpi", token, densityDpi)

        applyTransaction(transaction)
        NeXtepLog.info(
            "window_container_transaction",
            "Applied bounds=$bounds densityDpi=$densityDpi windowingMode=$windowingMode",
        )
    }.onFailure { error ->
        NeXtepLog.error("window_container_transaction", "Task layout transaction failed", error)
    }

    private fun applyTransaction(transaction: Any) {
        val organizerClass = Class.forName("android.window.WindowOrganizer")
        val organizer = organizerClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()
        io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.phase("wct_submit") {
            invoke(organizer, "applyTransaction", transaction)
        }
    }

    private fun invoke(target: Any, name: String, vararg arguments: Any): Any? {
        val method = findCompatibleMethod(target.javaClass, name, arguments)
            ?: error("No compatible ${target.javaClass.name}#$name candidate")
        return method.invoke(target, *arguments)
    }

    private fun findCompatibleMethod(
        startType: Class<*>,
        name: String,
        arguments: Array<out Any>,
    ): Method? {
        var type: Class<*>? = startType
        while (type != null) {
            val method = type.declaredMethods.firstOrNull { candidate ->
                candidate.name == name &&
                    candidate.parameterTypes.size == arguments.size &&
                    candidate.parameterTypes.indices.all { index ->
                        wraps(candidate.parameterTypes[index])
                            .isAssignableFrom(arguments[index].javaClass)
                    }
            }
            if (method != null) return method.apply { isAccessible = true }
            type = type.superclass
        }
        return null
    }

    private fun wraps(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> type
    }
}
