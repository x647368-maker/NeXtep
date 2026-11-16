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
        else if(style==3 || style==4) {
            canvas.drawCircle(12f,12f,8.5f,pen)
            if(style==3)line(7.5f,12f,10.5f,15f,16.5f,8.5f)
        } else when(group) {
            10->{rect(2f,3f,22f,21f);line(2f,9f,22f,9f);for(x in listOf(6f,10f,14f,18f))line(x,5f,x,7f)}
            11,12->{rect(2f,3f,22f,21f);val x=if(group==11)8f else 16f;line(x,3f,x,21f)
                val edge=if(group==11)2f else 22f;for(y in listOf(7.5f,12f,16.5f))line(edge,y,x,y)}
            13->{rect(2f,4f,22f,20f);rect(7f,7f,20f,17f)}
            14->{rect(2f,4f,22f,20f);line(10f,4f,10f,20f);line(2f,12f,10f,12f)}
            15->{line(3f,9f,3f,3f,9f,3f);line(15f,3f,21f,3f,21f,9f);line(21f,15f,21f,21f,15f,21f);line(9f,21f,3f,21f,3f,15f)}
            16->{rect(3f,3f,21f,21f);line(12f,7f,12f,17f);line(7f,12f,17f,12f)}
            17->{line(3f,8f,20f,8f,16f,4f);line(21f,16f,4f,16f,8f,20f)}
            18->{line(12f,2f,12f,11f);canvas.drawArc(3f,4f,21f,22f,-55f,290f,false,pen)}
            19->{line(2f,12f,5f,8f,12f,5f,19f,8f,22f,12f,19f,16f,12f,19f,5f,16f,2f,12f);canvas.drawCircle(12f,12f,3f,pen)}
            20->{line(7f,3f,17f,3f,15f,10f,19f,15f,5f,15f,9f,10f,7f,3f);line(12f,15f,12f,22f)}
            21->{canvas.drawCircle(12f,12f,9f,pen);line(12f,6f,12f,12f,16f,15f)}
            22->{rect(3f,3f,21f,21f);line(9f,3f,9f,21f);line(12f,8f,16f,12f,12f,16f)}
            23->{rect(3f,3f,10f,10f);rect(14f,3f,21f,10f);rect(3f,14f,10f,21f);rect(14f,14f,21f,21f)}
            24->{rect(3f,5f,21f,19f);line(10f,9f,15f,12f,10f,15f,10f,9f)}
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
