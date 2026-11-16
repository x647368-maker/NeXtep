package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Rect
import android.hardware.HardwareBuffer
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
    // Borrowed, immutable, already rendered. The caller owns its lifetime.
    private val wallpaper: HardwareBuffer? = null,
    private val onReleased:(()->Unit)?=null,
) {
    private val released=java.util.concurrent.atomic.AtomicBoolean(false)
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

        fun create(): StageGpuMirrorOcclusion? = createInternal(null)

        // Explicit experimental entry; production retains create() until the
        // real pixel/lifetime gate and wallpaper cache integration are proven.
        fun createTextured(buffer:HardwareBuffer,onReleased:(()->Unit)?=null):StageGpuMirrorOcclusion? {
            if(!StageGpuWallpaperBuffer.usable(buffer))return null
            return createInternal(buffer,onReleased)
        }

        private fun createInternal(wallpaper:HardwareBuffer?,onReleased:(()->Unit)?=null):StageGpuMirrorOcclusion? {
            var first: SurfaceControl? = null
            return runCatching {
                fun layer(name: String): SurfaceControl {
                    val builder = SurfaceControl.Builder().setName(name)
                    if(wallpaper==null) colorLayerMethod.invoke(builder)
                    else builder.setBufferSize(wallpaper.width,wallpaper.height)
                    val control=builder.build()
                    if (!control.isValid) {
                        runCatching { control.release() }
                        error("Color layer unavailable")
                    }
                    return control
                }
                first = layer("AtomicSwapOriginalSlotCover")
                val second = layer("AtomicSwapOriginalMainCover")
                StageGpuMirrorOcclusion(checkNotNull(first), second,wallpaper,onReleased)
            }.getOrElse {
                runCatching { first?.release() }
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

        val texture=wallpaper
        // No stretch/rotation or stale cached dimensions; both covers use
        // the original screen coordinates and the very same ready buffer.
        require(texture==null || (StageGpuWallpaperBuffer.usable(texture) &&
            texture.width==screenWidth && texture.height==screenHeight)) {
            "Wallpaper texture must match the physical viewport"
        }
        for (cover in arrayOf(sourceCover, mainCover)) {
            val reparent = attached.buildReparentTransaction(cover)
                ?: error("No compositor reparent transaction for original card cover")
            try { transaction.merge(reparent) } finally { reparent.close() }
        }
        fun configure(control: SurfaceControl, rect: Rect, layer: Int) {
            if(texture==null) {
                // Preserve the established opaque color-layer path.
                setColorMethod.invoke(transaction, control, floatArrayOf(.047f, .055f, .083f))
                transaction.setCrop(control, Rect(0, 0, rect.width(), rect.height()))
                transaction.setPosition(control, rect.left.toFloat(), rect.top.toFloat())
            } else {
                // One shared opaque wallpaper texture, two disjoint crops.
                // Submit with the same atomic parent/layer transaction.
                transaction.setBuffer(control,texture)
                transaction.setCrop(control,Rect(rect))
                transaction.setPosition(control,0f,0f)
            }
            transaction.setLayer(control, layer)
            transaction.setVisibility(control, true)
        }
        configure(sourceCover, source, StageGpuCoverPolicy.SOURCE_COVER_LAYER)
        configure(mainCover, main, StageGpuCoverPolicy.MAIN_COVER_LAYER)
    }

    fun setRevealAlpha(transaction: SurfaceControl.Transaction, alpha: Float) {
        transaction.setAlpha(sourceCover, alpha)
        transaction.setAlpha(mainCover, alpha)
    }

    fun hide(transaction: SurfaceControl.Transaction) {
        if (sourceCover.isValid) transaction.setVisibility(sourceCover, false)
        if (mainCover.isValid) transaction.setVisibility(mainCover, false)
    }

    fun release() {
        // Both local handles must be retired before returning a cache lease.
        // SurfaceFlinger retains its own buffer reference for submitted frames.
        if(!released.compareAndSet(false,true))return
        try {
            sourceCover.release()
        } finally {
            try { mainCover.release() } finally { onReleased?.invoke() }
        }
    }
}
