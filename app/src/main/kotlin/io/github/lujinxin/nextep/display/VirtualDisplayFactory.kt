package io.github.x647368maker.atomicworkbench.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.view.Surface
import io.github.x647368maker.atomicworkbench.framework.VirtualDisplayFlagsCompat
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog

class VirtualDisplayFactory(context: Context) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    fun create(index: Int, geometry: SlotGeometry, surface: Surface): Result<VirtualDisplay> {
        // Per-invocation only; successful candidate, no policy changes.
        var selectedFlags: VirtualDisplayFlagsCompat.Resolved? = null
        return runCatching {
            val manager = checkNotNull(displayManager) { "DisplayManager unavailable" }
            var lastFailure: Throwable? = null
            VirtualDisplayFlagsCompat.resolveCandidates().forEach { flags ->
                try {
                    // Preview displays need no 120/144 Hz mode simply because
                    // the physical display is fast. The main display stays native.
                    val config = VirtualDisplayConfig.Builder("NeXtep-slot-$index",
                        geometry.width, geometry.height, geometry.densityDpi)
                        .setSurface(surface)
                        .setFlags(flags.value)
                        .setRequestedRefreshRate(60f)
                        .build()
                    val display = manager.createVirtualDisplay(config)
                    if (display != null) {
                        selectedFlags = flags
                        return@runCatching display
                    }
                    lastFailure = IllegalStateException("DisplayManager returned null flags=${flags.names}")
                } catch (error: Throwable) {
                    lastFailure = error
                    NeXtepLog.warn(
                        "virtual_display",
                        "Candidate rejected slot=$index flags=${flags.names}",
                        error,
                    )
                }
            }
            throw lastFailure ?: IllegalStateException("No VirtualDisplay flag candidate")
        }.onSuccess { display ->
            NeXtepLog.info(
                "virtual_display",
                "Created slot=$index displayId=${display.display.displayId} geometry=$geometry " +
                    "selectedFlags=${selectedFlags?.names} selectedValue=${selectedFlags?.value}",
            )
        }.onFailure { error ->
            NeXtepLog.error("virtual_display", "Creation failed slot=$index geometry=$geometry", error)
        }
    }
}
