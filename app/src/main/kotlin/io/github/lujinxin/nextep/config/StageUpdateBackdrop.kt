package io.github.x647368maker.atomicworkbench.config

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable

/** Static HWUI-backed gradients: no live blur, image decode, timers, or redraw loop. */
internal class StageUpdateBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Path()
    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        paint.shader = LinearGradient(0f, 0f, width * .5f, height, intArrayOf(
            Color.rgb(15,12,37), Color.rgb(53,38,123), Color.rgb(33,24,79)
        ), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = RadialGradient(width * .75f, height * .68f, width * .82f, intArrayOf(
            Color.argb(195,131,89,247), Color.argb(84,112,73,217), Color.TRANSPARENT
        ), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
        wave.reset()
        wave.moveTo(0f, height * .67f)
        wave.cubicTo(width * .35f, height * .85f, width * .67f, height * .52f, width, height * .55f)
        wave.lineTo(width, height * .95f)
        wave.lineTo(0f, height * .95f)
        wave.close()
        paint.shader = LinearGradient(0f, height * .60f, width, height * .85f, intArrayOf(
            Color.rgb(235,150,236), Color.rgb(109,73,205), Color.rgb(251,123,105)
        ), null, Shader.TileMode.CLAMP)
        canvas.drawPath(wave, paint)
        wave.reset()
        wave.moveTo(0f, height * .83f)
        wave.cubicTo(width * .34f, height * .76f, width * .75f, height * .96f, width, height * .76f)
        wave.lineTo(width, height)
        wave.lineTo(0f, height)
        wave.close()
        paint.shader = LinearGradient(0f, height * .80f, width, height, intArrayOf(
            Color.rgb(251,151,94), Color.rgb(110,70,196), Color.rgb(27,22,68)
        ), null, Shader.TileMode.CLAMP)
        canvas.drawPath(wave, paint)
        paint.shader = null
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
