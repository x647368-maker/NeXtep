package io.github.x647368maker.atomicworkbench.systemui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.hypot

/**
 * One-shot, ~160ms GPU ViewPropertyAnimator feedback. It starts in parallel
 * with the real phased task migration; the task never waits for the visual.
 * Reuses the two attached views; no bitmap, mirror, window, virtual display,
 * per-frame code, persistent timer, or always-on backdrop.
 *
 * This cue does NOT claim to be the full vivo cross-window surface flight.
 */
internal class StageOneShotSwapMotion(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val easing = PathInterpolator(.18f, 0f, .12f, 1f)
    private var generation = 0
    private var card: View? = null
    private var mainHint: View? = null
    private var originalBackground: Drawable? = null
    private var pulseBackground: Drawable? = null

    fun cancel() {
        generation++
        card?.let { v ->
            v.animate().cancel()
            v.scaleX=1f; v.scaleY=1f
            v.translationX=0f; v.translationY=0f
        }
        card = null
        mainHint?.let { v ->
            v.animate().cancel()
            if (v.background === pulseBackground) v.background = originalBackground
            v.alpha=1f
        }
        mainHint=null; pulseBackground=null; originalBackground=null
    }

    fun start(source: View, main: View?): Boolean {
        cancel()
        if (!StageOneShotSwapMotionPolicy.allowed(
                ValueAnimator.areAnimatorsEnabled(),
                power?.isPowerSaveMode == true,
                source.isAttachedToWindow && source.isShown && source.isHardwareAccelerated,
                source.width, source.height
            )) return false
        val token=generation
        card=source
        source.pivotX=source.width/2f; source.pivotY=source.height/2f
        // Small toward-target translation remains inside the existing sidebar
        // window; the real surfaces change owners independently.
        val xy=IntArray(2);source.getLocationOnScreen(xy)
        val target=IntArray(2);main?.getLocationOnScreen(target)
        val vx=if(main==null)0f else target[0]+main.width/2f-(xy[0]+source.width/2f)
        val vy=if(main==null)0f else target[1]+main.height/2f-(xy[1]+source.height/2f)
        val norm=hypot(vx,vy).coerceAtLeast(1f)
        val dx=vx/norm * 9f*density
        val dy=vy/norm * 9f*density
        source.animate().translationX(dx).translationY(dy)
            .scaleX(StageOneShotSwapMotionPolicy.PEEK_SCALE)
            .scaleY(StageOneShotSwapMotionPolicy.PEEK_SCALE)
            .setDuration(StageOneShotSwapMotionPolicy.OUTBOUND_MS)
            .setInterpolator(easing)
            .withEndAction {
                if (token==generation) source.animate()
                    .translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(StageOneShotSwapMotionPolicy.RETURN_MS)
                    .setInterpolator(easing)
                    .withEndAction {
                        if (token==generation && card===source) {
                            card=null
                            source.translationX=0f;source.translationY=0f
                            source.scaleX=1f;source.scaleY=1f
                        }
                    }.start()
            }.start()
        // The actual main Task Surface is behind a pre-existing non-touchable
        // overlay. Pulse ONLY its thin edge; never replace that real task.
        if (main?.isAttachedToWindow==true && main.isShown) {
            mainHint=main
            originalBackground=main.background
            pulseBackground=GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((2f*density).toInt().coerceAtLeast(1),Color.argb(118,206,190,255))
                cornerRadius=24f*density
            }
            main.background=pulseBackground
            main.alpha=1f
            main.animate().alpha(0f).setDuration(StageOneShotSwapMotionPolicy.TOTAL_MS)
                .setInterpolator(easing).withEndAction {
                    if(token==generation && mainHint===main) {
                        if(main.background===pulseBackground) main.background=originalBackground
                        main.alpha=1f
                        mainHint=null;pulseBackground=null;originalBackground=null
                    }
                }.start()
        }
        return true
    }
}
