package io.github.x647368maker.atomicworkbench.safety

import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import java.io.File

object EmergencyDisable {
    private val disableFile = File("/data/adb/nextep/disable")

    fun isRequested(): Boolean = try {
        disableFile.exists().also { disabled ->
            if (disabled) NeXtepLog.warn("safety", "Emergency disable file is present")
        }
    } catch (error: SecurityException) {
        NeXtepLog.warn("safety", "Emergency disable file could not be inspected", error)
        false
    }
}
