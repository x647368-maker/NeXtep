package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import io.github.x647368maker.atomicworkbench.display.WORKSPACE_SLOT_COUNT

/** One recorded layout shared by panels, main-task fitting and all preview displays. */
data class WorkspaceLayoutProfile(
    val geometry: WorkspaceGeometry,
    val densityDpi: Int,
    val gapPixels: Int,
) {
    data class Slot(val width: Int, val height: Int, val top: Int, val left: Int = 0)
    data class Source(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    data class PreviewTransform(val scaleX: Float, val scaleY: Float, val x: Float, val y: Float)

    init {
        require(densityDpi > 0 && gapPixels > 0)
        require(geometry.sidebarWidth > gapPixels)
    }

    private val leftGeometry = geometry.copy(sidebarSide = SidebarSide.LEFT)
    private val rightGeometry = geometry.copy(sidebarSide = SidebarSide.RIGHT)
    val canvas = if (geometry.railAtTop) SlotGeometry(720, 1120, 320)
        else SlotGeometry.forRecordedDisplay(geometry.availableWidth, geometry.availableHeight, densityDpi)
    // Apps lay out directly at the recorded usable resolution. Fit that complete
    // buffer into a slot; do not crop navigation pixels off an already laid-out app.
    val source = Source(0, 0, canvas.width, canvas.height)
    val slots: List<Slot> = run {
        if (geometry.railAtTop) {
            val density = densityDpi / 160f
            val gutter = (8 * density).toInt()
            val header = 0
            val footer = (48 * density).toInt()
            val maxWidth = ((geometry.sidebarPhysicalWidth - gutter * 5) / 4).coerceAtLeast(1)
            val bodyHeight = minOf((maxWidth.toDouble() * canvas.height / canvas.width).toInt(),
                geometry.sidebarPhysicalHeight - header - footer - gutter * 2).coerceAtLeast(1)
            val width = minOf(maxWidth, (bodyHeight.toDouble() * canvas.width / canvas.height).toInt()).coerceAtLeast(1)
            return@run List(4) { i -> Slot(width, bodyHeight + header, gutter, gutter + i * (width + gutter)) }

        }
        val maxWidth = geometry.sidebarWidth - gapPixels
        val usable = geometry.sidebarLogicalHeight
        val idealHeight = (maxWidth.toDouble() * canvas.height / canvas.width).toInt().coerceAtLeast(1)
        val height = minOf(idealHeight, (usable - gapPixels * (WORKSPACE_SLOT_COUNT - 1)) / WORKSPACE_SLOT_COUNT).coerceAtLeast(1)
        val width = minOf(maxWidth, (height.toDouble() * canvas.width / canvas.height).toInt()).coerceAtLeast(1)
        val stackHeight = height * WORKSPACE_SLOT_COUNT + gapPixels * (WORKSPACE_SLOT_COUNT - 1)
        val padding = ((usable - stackHeight) / 2).coerceAtLeast(0)
        List(WORKSPACE_SLOT_COUNT) { index -> Slot(width, height, padding + index * (height + gapPixels)) }
    }

    fun geometry(side: SidebarSide): WorkspaceGeometry = if (side == SidebarSide.LEFT) leftGeometry else rightGeometry

    fun rotatedTo(rotation: Int): WorkspaceLayoutProfile =
        if (rotation == geometry.displayRotation) this else copy(geometry = geometry.rotatedTo(rotation))

    /** Undo TextureView's default stretching and fit the complete recorded canvas.
     * Slot bounds have the same aspect ratio apart from integer-pixel rounding. */
    fun previewTransform(width: Int, height: Int): PreviewTransform {
        require(width > 0 && height > 0)
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        return PreviewTransform(canvas.width * scale / width, canvas.height * scale / height,
            (width - source.width * scale) / 2f - source.left * scale,
            (height - source.height * scale) / 2f - source.top * scale)
    }

    fun encode(): String = listOf("12", geometry.screenWidth, geometry.screenHeight,
        geometry.topHeight, geometry.sidebarWidth, densityDpi, gapPixels,
        geometry.navigationInsets.left, geometry.navigationInsets.top,
        geometry.navigationInsets.right, geometry.navigationInsets.bottom,
        geometry.contentWidth, geometry.contentHeight, geometry.contentScale,
        canvas.width, canvas.height,
        source.left, source.top, source.right, source.bottom,
        *slots.flatMap { listOf(it.width, it.height, it.top) }.toTypedArray(),
        geometry.railAtTop, geometry.displayRotation).joinToString(",")

    companion object {
        fun create(width: Int, height: Int, densityDpi: Int, insets: WorkspaceNavigationInsets,
            displayRotation: Int = 0) =
            WorkspaceLayoutProfile(WorkspaceGeometry.forDisplay(width, height, density = densityDpi / 160f,
                navigationInsets = insets, displayRotation = displayRotation),
                densityDpi, (densityDpi / 160f).toInt().coerceAtLeast(1))

        fun decode(encoded: String?): WorkspaceLayoutProfile? = runCatching {
            val p = encoded?.split(',') ?: return@runCatching null
            if (p.firstOrNull() != "12" || p.size != 22 + WORKSPACE_SLOT_COUNT * 3) return@runCatching null
            val profile = WorkspaceLayoutProfile(WorkspaceGeometry(p[1].toInt(), p[2].toInt(),
                p[3].toInt(), p[4].toInt(), SidebarSide.RIGHT,
                WorkspaceNavigationInsets(p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt()),
                displayRotation = p.last().toInt(), railAtTop = p[p.size - 2].toBooleanStrict()), p[5].toInt(), p[6].toInt())
            // This independent application never consumes NeXtep's three-slot records.
            if (profile.encode() != encoded) return@runCatching null
            profile
        }.getOrNull()

    }
}
