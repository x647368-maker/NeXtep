package io.github.x647368maker.atomicworkbench.systemui

import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import android.view.View
import android.view.animation.PathInterpolator
import io.github.x647368maker.atomicworkbench.workspace.StageSparseRailReflowPolicy
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry
import java.util.IdentityHashMap

/**
 * One-shot 190ms card reflow. Compositor properties ONLY: no per-frame
 * view size/layout changes, bitmap captures, extra display reconnects or
 * persistent timers. Native input dispatch sees the normal View transforms.
 */
internal class StageSparseRailReflowAnimator(
    context: Context,
    // A geometry-only compositor animation on every supported phone, fold and
    // tablet. The optional switch remains for deterministic instrumentation.
    private val trialDevice: Boolean = true,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val active = IdentityHashMap<View, Long>()
    private var generation = 0L
    private val easing = PathInterpolator(.2f, 0f, 0f, 1f)

    fun isAnimating(view: View): Boolean = active.containsKey(view)

    private fun end(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
    }

    fun cancel() {
        generation++
        val views = active.keys.toList()
        active.clear()
        views.forEach(::end)
    }

    /**
     * All frames use global coordinates so the existing panel can change its
     * width/position without an unexpected lateral jump.
     */
    fun animate(
        oldGeometry: WorkspaceGeometry?,
        newGeometry: WorkspaceGeometry,
        oldCount: Int,
        newCount: Int,
        before: Map<View, StageSparseRailReflowPolicy.Bounds>,
        after: Map<View, StageSparseRailReflowPolicy.Bounds>,
        enabled: Boolean,
    ) {
        val allowed = enabled &&
            trialDevice &&
            StageSparseRailReflowPolicy.supported(oldGeometry, newGeometry, oldCount, newCount) &&
            ValueAnimator.areAnimatorsEnabled() &&
            power?.isPowerSaveMode != true
        if (!allowed) {
            // An interrupted count change must never leave a previous
            // compositor transformation on a live task or in its touch path.
            if (active.isNotEmpty()) cancel()
            return
        }
        // Layout has advanced to the next count, but any previous FLIP
        // may still be visible. Capture the actual rendered rectangle/alpha
        // before cancelling, otherwise fast repeated additions would snap
        // to the previous target for one frame.
        val runningBounds = IdentityHashMap<View, StageSparseRailReflowPolicy.Bounds>()
        val runningAlpha = IdentityHashMap<View, Float>()
        for ((view, nominal) in before) {
            if (!active.containsKey(view)) continue
            StageSparseRailReflowPolicy.visibleBounds(
                nominal, view.scaleX, view.scaleY,
                view.translationX, view.translationY
            )?.let { runningBounds[view] = it }
            runningAlpha[view] = view.alpha
        }
        generation++
        val epoch = generation
        // Removed cards can already have been hidden by the real workspace.
        // Do not force alpha=1 on a card omitted from the next occupied set:
        // rapid 4->3->2 would otherwise flash a just-closed real task.
        for (view in active.keys.toList()) {
            if (after.containsKey(view)) {
                end(view)
            } else {
                val retainedAlpha=view.alpha
                view.animate().cancel()
                view.scaleX=1f
                view.scaleY=1f
                view.translationX=0f
                view.translationY=0f
                view.alpha=retainedAlpha
            }
        }
        active.clear()
        for ((view, target) in after) {
            if (!view.isAttachedToWindow || !view.isShown) continue
            val start = StageSparseRailReflowPolicy.start(
                runningBounds[view] ?: before[view], target
            ) ?: continue
            val nearIdentity = kotlin.math.abs(start.offsetX) < .5f &&
                kotlin.math.abs(start.offsetY) < .5f &&
                kotlin.math.abs(start.uniformScale - 1f) < .005f &&
                start.alpha == 1f
            if (nearIdentity) continue
            view.pivotX = target.width / 2f
            view.pivotY = target.height / 2f
            view.translationX = start.offsetX
            view.translationY = start.offsetY
            view.scaleX = start.uniformScale
            view.scaleY = start.uniformScale
            view.alpha = runningAlpha[view]?.coerceIn(0f, 1f) ?: start.alpha
            active[view] = epoch
            view.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(StageSparseRailReflowPolicy.DURATION_MS)
                .setInterpolator(easing)
                .withEndAction {
                    if (active[view] == epoch) {
                        active.remove(view)
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.translationX = 0f
                        view.translationY = 0f
                        view.alpha = 1f
                    }
                }.start()
        }
    }
}
