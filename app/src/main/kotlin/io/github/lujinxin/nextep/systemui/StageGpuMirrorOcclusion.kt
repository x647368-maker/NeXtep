package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Rect
import android.view.AttachedSurfaceControl
import android.view.SurfaceControl

/**
 * PR62 private trial: two short-lived GPU color surfaces cover the stationary
 * original cards while the two exact task mirrors fly above them.
 *
 * ALL four child layers are parented, layered, cropped and shown in the same
 * SurfaceFlinger transaction. No view snapshot, framebuffer read, extra
 * WindowManager root, mutation of the original task leashes, or idle surface.
 * If the vendor refuses a color layer, fail before any real task migration.
 */
internal object StageGpuCoverPolicy {
    const val SOURCE_COVER_LAYER = 100000
    const val MAIN_COVER_LAYER = 100001
    const val MAIN_MIRROR_LAYER = 100002
    const val SOURCE_MIRROR_LAYER = 100003

    fun fits(left: Int, top: Int, right: Int, bottom: Int,
             screenWidth: Int, screenHeight: Int): Boolean =
        screenWidth > 1 && screenHeight > 1 && left >= 0 && top >= 0 &&
            right > left + 1 && bottom > top + 1 &&
            right <= screenWidth && bottom <= screenHeight

    fun correctOrder(): Boolean =
        SOURCE_COVER_LAYER < MAIN_MIRROR_LAYER &&
            MAIN_COVER_LAYER < MAIN_MIRROR_LAYER &&
            MAIN_MIRROR_LAYER < SOURCE_MIRROR_LAYER
}

internal class StageGpuMirrorOcclusion private constructor(
    private val sourceCover: SurfaceControl,
    private val mainCover: SurfaceControl,
) {
    companion object {
        private val colorLayerMethod by lazy {
            SurfaceControl.Builder::class.java.getDeclaredMethod("setColorLayer")
                .apply { isAccessible = true }
        }
        private val setColorMethod by lazy {
            SurfaceControl.Transaction::class.java.getDeclaredMethod(
                "setColor", SurfaceControl::class.java, FloatArray::class.java,
            ).apply { isAccessible = true }
        }

        fun create(): StageGpuMirrorOcclusion? {
            var first: SurfaceControl? = null
            return runCatching {
                fun layer(name: String): SurfaceControl {
                    val builder = SurfaceControl.Builder().setName(name)
                    colorLayerMethod.invoke(builder)
                    return builder.build().also {
                        check(it.isValid) { "Color layer unavailable" }
                    }
                }
                first = layer("AtomicSwapOriginalSlotCover")
                val second = layer("AtomicSwapOriginalMainCover")
                StageGpuMirrorOcclusion(checkNotNull(first), second)
            }.getOrElse {
                first?.release()
                null
            }
        }
    }

    fun append(
        attached: AttachedSurfaceControl,
        transaction: SurfaceControl.Transaction,
        source: Rect,
        main: Rect,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        require(StageGpuCoverPolicy.correctOrder())
        require(StageGpuCoverPolicy.fits(
            source.left, source.top, source.right, source.bottom, screenWidth, screenHeight
        ) && StageGpuCoverPolicy.fits(
            main.left, main.top, main.right, main.bottom, screenWidth, screenHeight
        )) { "Occlusion bounds must be physical display rects" }

        for (cover in arrayOf(sourceCover, mainCover)) {
            val reparent = attached.buildReparentTransaction(cover)
                ?: error("No compositor reparent transaction for original card cover")
            try { transaction.merge(reparent) } finally { reparent.close() }
        }
        fun configure(control: SurfaceControl, rect: Rect, layer: Int) {
            // A color layer uses RGB values; no buffer is allocated or captured.
            setColorMethod.invoke(transaction, control, floatArrayOf(.047f, .055f, .083f))
            transaction.setCrop(control, Rect(0, 0, rect.width(), rect.height()))
            transaction.setPosition(control, rect.left.toFloat(), rect.top.toFloat())
            transaction.setLayer(control, layer)
            transaction.setVisibility(control, true)
        }
        configure(sourceCover, source, StageGpuCoverPolicy.SOURCE_COVER_LAYER)
        configure(mainCover, main, StageGpuCoverPolicy.MAIN_COVER_LAYER)
    }

    fun hide(transaction: SurfaceControl.Transaction) {
        if (sourceCover.isValid) transaction.setVisibility(sourceCover, false)
        if (mainCover.isValid) transaction.setVisibility(mainCover, false)
    }

    fun release() {
        sourceCover.release()
        mainCover.release()
    }
}
