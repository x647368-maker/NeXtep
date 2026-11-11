package io.github.x647368maker.atomicworkbench.systemui

/**
 * Read-only diagnostic for native ColorOS parallel / split-window scenes.
 *
 * This reports what SystemUI and ActivityTaskManager actually observe instead of
 * guessing from the model name. It never moves or rescales the native tasks.
 */
internal object StageNativeParallelDiagnostics {
    data class Task(
        val id: Int,
        val app: String,
        val display: Int,
        val mode: Int,
        val vendorWindowed: Boolean,
        val bounds: String,
        val activities: Int = -1,
    )

    data class Scene(
        val screenWidth: Int,
        val screenHeight: Int,
        val densityDpi: Int,
        val railAtTop: Boolean,
        val controlHeight: Int,
        val sidebarWidth: Int,
        val sidebarHeight: Int,
        val mainLeft: Int,
        val mainTop: Int,
        val mainWidth: Int,
        val mainHeight: Int,
        val fittedMainId: Int?,
        val tasks: List<Task>,
    )

    fun nearSquareExpanded(scene: Scene): Boolean {
        if (scene.screenWidth <= 1 || scene.screenHeight <= 1 || scene.densityDpi <= 0) return false
        val shortSide = minOf(scene.screenWidth, scene.screenHeight)
        val longSide = maxOf(scene.screenWidth, scene.screenHeight)
        return shortSide * 160f / scene.densityDpi >= 600f &&
            longSide.toFloat() / shortSide <= 1.35f
    }

    fun classification(scene: Scene): String {
        val active = scene.tasks.firstOrNull { it.display == 0 } ?: return "NO_PHYSICAL_TASK"
        return when {
            active.vendorWindowed -> "OPLUS_NATIVE_WINDOWED"
            active.mode != 1 -> "ANDROID_WINDOWED_MODE_${active.mode}"
            scene.fittedMainId != active.id -> "FULLSCREEN_NOT_FITTED"
            else -> "FULLSCREEN_FITTED_OEM_PARALLEL_UNKNOWN"
        }
    }

    fun report(scene: Scene): String = buildString {
        append("native_parallel_probe_v1: foldableNearSquare=").append(nearSquareExpanded(scene))
        append(" classification=").append(classification(scene))
        append("\nphysical=").append(scene.screenWidth).append('x').append(scene.screenHeight)
        append(" dpi=").append(scene.densityDpi)
        append(" rail=").append(if (scene.railAtTop) "TOP" else "SIDE")
        append(" topHeight=").append(scene.controlHeight)
        append(" side=").append(scene.sidebarWidth).append('x').append(scene.sidebarHeight)
        append("\nmainBounds=").append(scene.mainLeft).append(',').append(scene.mainTop)
        append(' ').append(scene.mainWidth).append('x').append(scene.mainHeight)
        append(" fittedTask=").append(scene.fittedMainId ?: -1)
        scene.tasks.take(5).forEach { task ->
            append("\ntask#").append(task.id)
            append(" display=").append(task.display)
            append(" mode=").append(task.mode)
            append(" oplusWindowed=").append(task.vendorWindowed)
            append(" bounds=").append(task.bounds)
            append(" activities=").append(task.activities)
            append(" app=").append(task.app)
        }
        append("\nNOTE: ColorOS native parallel panes may still report fullscreen; this probe does not assume two panes were detected.")
    }
}
