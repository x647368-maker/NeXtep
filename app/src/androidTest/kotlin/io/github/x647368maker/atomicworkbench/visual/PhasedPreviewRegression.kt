package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.RectF
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.systemui.WorkspaceSwapAnimator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise the production async animation contract on a real attached HWUI host. */
internal class PhasedPreviewRegression(private val instrumentation: Instrumentation) {
    fun run(activity: Activity, host: FrameLayout): String {
        val requested = CountDownLatch(1)
        val finished = CountDownLatch(1)
        lateinit var animator: WorkspaceSwapAnimator
        lateinit var complete: (Boolean) -> Unit
        lateinit var source: Bitmap
        lateinit var target: Bitmap
        var releaseAllowed = false
        var completionCount = 0
        instrumentation.runOnMainSync {
            animator = WorkspaceSwapAnimator(activity)
            source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
            target = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
            animator.animate(source, target, RectF(0f, 0f, 100f, 200f),
                RectF(0f, 0f, 100f, 200f), RectF(100f, 100f, 400f, 700f),
                host, null, null, 200, false, null, null,
                move = { error("Async exchange used the synchronous move") },
                isLiveReady = { true },
                moveAsync = { done -> complete = done; requested.countDown() },
                canRelease = { releaseAllowed },
                freezeGeometry = true, // Exercise the new 0.9.16 no-morph rendering path.
                onFinished = { completionCount++; finished.countDown() })
        }
        check(requested.await(6, TimeUnit.SECONDS)) { "Async exchange did not start" }
        try {
            instrumentation.runOnMainSync {
                check(animator.isRunning && completionCount == 0)
                animator.cancel()
                check(animator.isRunning && !source.isRecycled && !target.isRecycled) {
                    "Pending exchange lost its preview protection"
                }
                releaseAllowed = true
                complete(false)
            }
            // The async completion callback is allowed to finish on a posted frame.
            // Require eventual release instead of crashing the instrumentation target
            // with a same-main-loop synchronous assertion before that frame can run.
            check(finished.await(3, TimeUnit.SECONDS)) {
                "Rollback preview did not settle within 3 seconds"
            }
            instrumentation.runOnMainSync {
                check(!animator.isRunning && completionCount == 1) {
                    "Failed rollback preview release: running=${animator.isRunning} " +
                        "completionCount=$completionCount releaseAllowed=$releaseAllowed " +
                        "sourceRecycled=${source.isRecycled} targetRecycled=${target.isRecycled}"
                }
                check(source.isRecycled && target.isRecycled)
                animator.cancel()
                check(completionCount == 1)
            }
        } finally {
            instrumentation.runOnMainSync { releaseAllowed = true; animator.cancel() }
        }
        return "PASS production async preview retains protection during pending/cancel and releases exactly once after rollback completion"
    }
}
