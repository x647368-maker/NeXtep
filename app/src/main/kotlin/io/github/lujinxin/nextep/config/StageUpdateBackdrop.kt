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
            Color.rgb(15,12,43), Color.rgb(51,31,126), Color.rgb(27,21,76)
        ), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = RadialGradient(width * .81f, height * .55f, width * .86f, intArrayOf(
            Color.argb(198,144,100,255), Color.argb(99,119,76,225), Color.TRANSPARENT
        ), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
        wave.reset()
        wave.moveTo(0f, height * .57f)
        wave.cubicTo(width * .36f, height * .74f, width * .65f, height * .47f, width, height * .46f)
        wave.lineTo(width, height * .88f)
        wave.lineTo(0f, height * .88f)
        wave.close()
        paint.shader = LinearGradient(0f, height * .50f, width, height * .76f, intArrayOf(
            Color.rgb(224,145,238), Color.rgb(112,71,206), Color.rgb(255,126,105)
        ), null, Shader.TileMode.CLAMP)
        canvas.drawPath(wave, paint)
        wave.reset()
        wave.moveTo(0f, height * .74f)
        wave.cubicTo(width * .34f, height * .62f, width * .72f, height * .94f, width, height * .74f)
        wave.lineTo(width, height)
        wave.lineTo(0f, height)
        wave.close()
        paint.shader = LinearGradient(0f, height * .70f, width, height, intArrayOf(
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
