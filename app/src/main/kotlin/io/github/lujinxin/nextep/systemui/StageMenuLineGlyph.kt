package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small static vector marks for the original four glass categories.
 * Rendered only as part of the open popup; no extra surface or capture. */
internal class StageMenuLineGlyph(private val group:Int,private val style:Int):Drawable() {
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE;strokeWidth=1.7f;this.style=Paint.Style.STROKE
        strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    private val path=Path()
    override fun draw(canvas:Canvas) {
        val save=canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/24f,bounds.height()/24f)
        fun line(vararg points:Float) {
            path.reset();path.moveTo(points[0],points[1])
            var i=2
            while(i<points.size){path.lineTo(points[i],points[i+1]);i+=2}
            canvas.drawPath(path,pen)
        }
        fun rect(l:Float,t:Float,r:Float,b:Float)=canvas.drawRoundRect(l,t,r,b,2f,2f,pen)
        if(style==2)line(9f,5f,15f,12f,9f,19f)
        else when(group) {
            0->{
                if(style==0){rect(3f,3f,10f,10f);rect(14f,3f,21f,10f);rect(3f,14f,10f,21f);rect(14f,14f,21f,21f)}
                else {rect(3f,4f,21f,20f);line(9f,4f,9f,20f);line(3f,9f,9f,9f)}
            }
            1->{line(3f,8f,12f,3f,21f,8f,12f,13f,3f,8f);line(3f,12f,12f,17f,21f,12f);line(3f,16f,12f,21f,21f,16f)}
            2->{line(3f,6f,21f,6f);line(3f,12f,21f,12f);line(3f,18f,21f,18f);rect(7f,3f,10f,9f);rect(14f,9f,17f,15f);rect(8f,15f,11f,21f)}
            else->{line(12f,3f,15f,9f,21f,12f,15f,15f,12f,21f,9f,15f,3f,12f,9f,9f,12f,3f)}
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){pen.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter:ColorFilter?){pen.colorFilter=colorFilter;invalidateSelf()}
    @Deprecated("Deprecated in Java")
    override fun getOpacity()=PixelFormat.TRANSLUCENT
}
