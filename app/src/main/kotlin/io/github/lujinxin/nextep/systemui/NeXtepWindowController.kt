package io.github.x647368maker.atomicworkbench.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.R
import io.github.x647368maker.atomicworkbench.config.AppLanguage
import io.github.x647368maker.atomicworkbench.display.SlotState
import io.github.x647368maker.atomicworkbench.display.SlotTaskCoordinator
import io.github.x647368maker.atomicworkbench.display.SlotTaskDrag
import io.github.x647368maker.atomicworkbench.display.VirtualDisplaySlot
import io.github.x647368maker.atomicworkbench.display.WORKSPACE_SLOT_COUNT
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.safety.FeatureGate
import io.github.x647368maker.atomicworkbench.task.MainTaskPresentationCoordinator
import io.github.x647368maker.atomicworkbench.task.TaskRepository
import io.github.x647368maker.atomicworkbench.trigger.TriggerBroadcastContract
import io.github.x647368maker.atomicworkbench.workspace.SidebarSide
import io.github.x647368maker.atomicworkbench.workspace.SystemServerWorkspaceBridge
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceTransitionSpec

class NeXtepWindowController(
    private val context: Context,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val onSidebarSideRequested: (SidebarSide) -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onExitRequested: () -> Unit,
    private val onViewportChanged: () -> Unit,
) {
    private data class WindowRecord(val view: View, val title: String)

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val taskRepository = TaskRepository(context)
    private val viewport = WorkspaceViewport(context, onViewportChanged)
    private val records = mutableListOf<WindowRecord>()
    @Volatile private var rotationPanelHosts: List<WorkspacePanelHost> = emptyList()
    private var initialized = false
    private var slotCoordinator: SlotTaskCoordinator? = null
    private var sidebarCollapsed = false
    private val workgroups = AtomicWorkgroups(context, taskRepository, { slotCoordinator },
        { workspaceVisible && !suspended }, { setSidebarCollapsed(false) }, { scheduleStageFocus() },
        { stageOrder }, { order ->
            stageOrder = order
            stageMainId = null
            applyStageStack(geometry())
        }, { ready -> startWorkgroupCover(ready) }, { success -> finishWorkgroupCover(success) })
    private var topAppStrip: TopAppStripView? = null
    private var controlAreaPager: ControlAreaPager? = null
    private var sidebarView: FrameLayout? = null
    private var contentHighlightView: View? = null
    private var slotWindows: List<VirtualDisplaySlot> = emptyList()
    private var slotBackgroundSwipeEnabled = true
    private var frostStrength = 50
    private val swapAnimator = WorkspaceSwapAnimator(context)
    private val internalDrag = WorkspaceInternalDragController(
        context, ::geometry, { slotWindows.map { it.view } },
        ::highlightInternalDragTarget, ::dropInternalPayload,
        { topAppStrip?.finishInternalAppDrag() },
    )
    private val backdropGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val backdropWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-Backdrop")
    }
    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stageOrder = List(WORKSPACE_SLOT_COUNT) { it }
    private var stageMainId: Int? = null
    private var stageFocusBadge: android.widget.TextView? = null
    private val stageFocusUpdate = Runnable { updateStageFocus() }
    private var groupInputBlocked = false
    private var groupInitialOwners: List<Int?> = emptyList()
    private var groupInitialFrames: List<Long> = emptyList()
    private val groupCover = WorkgroupTransitionCover(context,
        { contentHighlightView?.parent as? FrameLayout }, { blocked -> setGroupInputBlocked(blocked) })
    private var wallpaperBitmap: android.graphics.Bitmap? = null
    private var wallpaperRotation = 0
    private var homePreview: Bitmap? = null
    private var homePreviewBounds = Rect()
    private val homePreviewGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val homePreviewWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-HomePreview").apply { isDaemon = true }
    }
    private var homePreviewCapturePending = false
    private var resumeContentGeneration = 0
    private var pendingResumeContent: Runnable? = null
    private var resumeFrameObserver: ViewTreeObserver? = null
    private var resumeFrameCallback: Runnable? = null
    private var rotationDrawObserver: ViewTreeObserver? = null
    private var rotationDrawCallback: ViewTreeObserver.OnPreDrawListener? = null
    private var sidebarSide = SystemServerWorkspaceBridge.sidebarSide(context)
    private var suspended = false
    private var workspaceVisible = false
    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_WALLPAPER_CHANGED -> {
                    clearHomePreview()
                    // Retain the displayed frame while the new wallpaper starts rendering.
                    // OEM live services may change content without changing component/id.
                    backdropGeneration.incrementAndGet()
                }
                Intent.ACTION_USER_PRESENT -> Unit
                else -> return
            }
            if (!suspended) refreshWallpaperBackdrop()
        }
    }

    init {
        // Warm the cache before the first workspace entry, including after unlocking.
        // Window creation is deliberately lazy; wallpaper observation must not be.
        runCatching {
            context.registerReceiver(
                wallpaperReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { NeXtepLog.warn("wallpaper_backdrop", "Wallpaper change listener unavailable", it) }
        backdropHandler.post { refreshWallpaperBackdrop() }
        mainTaskPresenter.onHomePresented = {
            backdropHandler.post {
                if (!suspended && workspaceVisible) refreshWallpaperBackdrop()
            }
            backdropHandler.postDelayed({
                if (!suspended && workspaceVisible) captureHomePreview(geometry())
            }, WorkspaceTransitionSpec.DURATION_MS + HOME_PREVIEW_SETTLE_DELAY_MS)
        }
    }

    fun suspendForLock() {
        workgroups.cancel()
        backdropHandler.removeCallbacks(stageFocusUpdate)
        stageMainId = null
        sidebarCollapsed = false
        cancelPendingRotationDraw()
        cancelPendingResumeContent()
        suspended = true
        cancelDragVisuals()
        clearHomePreview()
        backdropGeneration.incrementAndGet()
        slotCoordinator?.setSuspended(true)
        topAppStrip?.setWorkspaceVisible(false)
        controlAreaPager?.setWorkspaceState(workspaceVisible, locked = true)
        // Keep TextureViews attached: GONE/removeView would destroy their virtual displays.
        records.forEach { record ->
            record.view.animate().cancel()
            record.view.alpha = 0f
            val params = record.view.layoutParams as WindowManager.LayoutParams
            params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(record.view, params)
        }
    }

    fun resumeAfterLock(): WorkspaceGeometry {
        check(records.size == EXPECTED_WINDOW_COUNT) { "Retained SystemUI windows are unavailable" }
        cancelPendingResumeContent()
        workspaceVisible = true
        suspended = false
        // Wake reuses attached windows and the recorded layout. Re-running show()
        // starts a fresh alpha=0 entry animation and repeats all display setup.
        val geometry = geometry()
        applyWindowGeometry(geometry, refreshControls = false)
        sidebarView?.let { applySlotLayout(it, geometry) }
        records.forEach { record ->
            record.view.animate().cancel()
            record.view.visibility = View.VISIBLE
            record.view.alpha = 1f
            record.view.translationX = 0f
            record.view.translationY = 0f
        }
        resumeContentAfterFrame()
        return geometry
    }

    fun updateSuspendedOutputs(screenAwake: Boolean) {
        if (workspaceVisible && suspended) slotCoordinator?.updateSuspendedOutputs(screenAwake)
    }

    private fun resumeContentAfterFrame() {
        val token = resumeContentGeneration
        val startedAt = SystemClock.uptimeMillis()
        val resumeContent = Runnable {
            if (token != resumeContentGeneration || suspended || !workspaceVisible) return@Runnable
            cancelPendingResumeContent()
            val geometry = currentGeometry()
            // Commit the retained panel contents before reconnecting virtual
            // displays or querying media sessions through synchronous Binder calls.
            runCatching { topAppStrip?.setWorkspaceVisible(true) }.onFailure {
                NeXtepLog.warn("workspace_unlock", "Top controls resume pending", it)
            }
            runCatching {
                controlAreaPager?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft)
                controlAreaPager?.setWorkspaceState(workspaceVisible, locked = false)
                controlAreaPager?.refreshHost()
            }.onFailure {
                NeXtepLog.warn("workspace_unlock", "Video host resume pending", it)
            }
            runCatching { slotCoordinator?.setSuspended(false) }.onFailure {
                NeXtepLog.warn("workspace_unlock", "Slot outputs resume pending", it)
            }
        }
        pendingResumeContent = resumeContent
        val host = records.firstOrNull { it.title == "NeXtepTopBar" }?.view
        val observer = host?.viewTreeObserver
        if (host?.isAttachedToWindow == true && host.isHardwareAccelerated && observer?.isAlive == true) {
            val onFrame = Runnable {
                backdropHandler.post {
                    if (token != resumeContentGeneration) return@post
                    NeXtepLog.info("workspace_unlock", "Retained panel frame submitted in ${SystemClock.uptimeMillis() - startedAt}ms")
                    resumeContent.run()
                }
            }
            resumeFrameObserver = observer
            resumeFrameCallback = onFrame
            observer.registerFrameCommitCallback(onFrame)
            host.invalidate()
            // A dropped callback must not strand the retained slot outputs.
            backdropHandler.postDelayed(resumeContent, RESUME_FRAME_FALLBACK_DELAY_MS)
        } else {
            backdropHandler.post(resumeContent)
        }
    }

    private fun cancelPendingResumeContent() {
        resumeContentGeneration += 1
        pendingResumeContent?.let(backdropHandler::removeCallbacks)
        pendingResumeContent = null
        val observer = resumeFrameObserver
        resumeFrameCallback?.let { callback ->
            if (observer?.isAlive == true) observer.unregisterFrameCommitCallback(callback)
        }
        resumeFrameObserver = null
        resumeFrameCallback = null
    }

    fun show(): WorkspaceGeometry {
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        workspaceVisible = true
        reconfigure().getOrThrow()
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.translationX = 0f
            view.alpha = 0f
            view.translationY = when (record.title) {
                "NeXtepTopBar" -> -dp(ENTER_OFFSET_DP)
                else -> dp(ENTER_OFFSET_DP)
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(WORKSPACE_ENTER_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        refreshWallpaperBackdrop()
        topAppStrip?.setWorkspaceVisible(true)
        controlAreaPager?.setWorkspaceState(visible = true, locked = false)
        topAppStrip?.refresh()
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotCoordinator?.activate()
        }
        scheduleStageFocus()
        return geometry()
    }

    fun hide() {
        workgroups.cancel()
        backdropHandler.removeCallbacks(stageFocusUpdate)
        stageMainId = null
        sidebarCollapsed = false
        cancelPendingRotationDraw()
        cancelPendingResumeContent()
        workspaceVisible = false
        cancelDragVisuals()
        clearHomePreview()
        suspended = false
        backdropGeneration.incrementAndGet()
        slotCoordinator?.deactivate()
        topAppStrip?.setWorkspaceVisible(false)
        controlAreaPager?.setWorkspaceState(visible = false, locked = false)
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
            windowManager.updateViewLayout(view, params)
            view.animate()
                .alpha(0f)
                .translationY(
                    if (record.title == "NeXtepTopBar") -dp(EXIT_OFFSET_DP)
                    else dp(EXIT_OFFSET_DP),
                )
                .setDuration(WORKSPACE_EXIT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .withEndAction {
                    view.visibility = View.GONE
                    view.translationX = 0f
                    view.translationY = 0f
                }
                .start()
        }
    }

    fun launchInSlot(intent: Intent): Result<Unit> {
        if (!FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            return Result.failure(IllegalStateException("VirtualDisplay slot feature disabled"))
        }
        return slotCoordinator?.launchInSlot(intent)
            ?: Result.failure(IllegalStateException("VirtualDisplay slot unavailable"))
    }

    fun onTasksChanged() {
        slotCoordinator?.onTasksChanged()
        scheduleStageFocus()
    }

    internal fun energySnapshot() = android.os.Bundle().apply {
        putInt("slot_count", slotWindows.count { it.displayId() != null })
        putLong("slot_frames", slotWindows.sumOf { it.contentFrameSequence })
    }

    fun openFromLauncher(intent: Intent): Result<Unit> = slotCoordinator
        ?.openFromLauncher(intent)
        ?: Result.failure(IllegalStateException("Slot coordinator unavailable"))

    fun currentGeometry(refreshViewport: Boolean = false): WorkspaceGeometry =
        viewport.geometry(sidebarSide, refreshViewport)

    fun projectDisplayRotation(rotation: Int) {
        viewport.projectRotation(rotation)
    }

    fun stagePanelRotation(rotation: Int) {
        rotationPanelHosts.forEach { it.stageRotation(rotation) }
    }

    fun whenRotationFrameReady(onReady: (View) -> Unit) {
        cancelPendingRotationDraw()
        val host = records.firstOrNull { it.title == "NeXtepTopBar" }?.view as? WorkspacePanelHost ?: return
        val observer = host.viewTreeObserver
        val callback = ViewTreeObserver.OnPreDrawListener {
            if (host.isProjectionReady()) {
                cancelPendingRotationDraw()
                onReady(host)
            }
            true
        }
        rotationDrawObserver = observer
        rotationDrawCallback = callback
        observer.addOnPreDrawListener(callback)
        host.requestLayout()
        host.invalidate()
    }

    private fun cancelPendingRotationDraw() {
        rotationDrawCallback?.let { callback ->
            rotationDrawObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(callback)
        }
        rotationDrawCallback = null
        rotationDrawObserver = null
    }

    fun setNavigationButtonsEnabled(enabled: Boolean) {
        viewport.setNavigationButtonsEnabled(enabled)
    }

    fun setSlotBackgroundSwipeEnabled(enabled: Boolean) {
        if (slotBackgroundSwipeEnabled == enabled) return
        slotBackgroundSwipeEnabled = enabled
        slotWindows.forEach { it.view.setBackgroundSwipeEnabled(enabled) }
    }

    fun reconfigure(rotationOnly: Boolean = false): Result<WorkspaceGeometry> = runCatching {
        clearHomePreview()
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        val geometry = geometry()
        applyWindowGeometry(geometry, refreshControls = !rotationOnly)
        if (rotationOnly) {
            // Only project the existing windows into the new display coordinates.
            // Their logical content, slot buffers and wallpaper frame stay intact.
            backdropGeneration.incrementAndGet()
            controlAreaPager?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft, refreshHost = false)
        } else {
            sidebarView?.let { applySlotLayout(it, geometry) }
            refreshWallpaperBackdrop()
        }
        NeXtepLog.info("workspace_configuration", "Reconfigured geometry=$geometry")
        geometry
    }

    fun setSidebarSide(side: SidebarSide): Result<WorkspaceGeometry> = runCatching {
        setSidebarCollapsed(false)
        clearHomePreview()
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        sidebarSide = side
        val geometry = geometry()
        applyWindowGeometry(geometry)
        topAppStrip?.setSidebarSide(side)
        sidebarView?.let { panel -> applySlotLayout(panel, geometry) }
        applySidebarBackdrop(geometry)
        records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.let { sidebar ->
            sidebar.animate().cancel()
            sidebar.translationY = 0f
            sidebar.alpha = 0f
            sidebar.translationX = if (side == SidebarSide.LEFT) {
                -dp(SIDEBAR_ENTER_OFFSET_DP)
            } else {
                dp(SIDEBAR_ENTER_OFFSET_DP)
            }
            sidebar.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(SIDEBAR_SWITCH_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        }
        NeXtepLog.info("sidebar_side", "Applied side=$side geometry=$geometry")
        geometry
    }

    fun slotStatesDescription(): String = slotCoordinator
        ?.slotStates()
        ?.mapIndexed { index, state -> "${index + 1}:$state" }
        ?.joinToString(" | ")
        ?: "unavailable"

    internal fun visibleSlotTaskIds(): Set<Int> = if (!workspaceVisible || suspended) emptySet() else {
        slotCoordinator?.slotStates().orEmpty().filterIsInstance<SlotState.Occupied>()
            .mapTo(mutableSetOf()) { it.taskId }
    }

    private fun initializeIfNeeded() {
        if (initialized) return
        initialized = true
        val windowType = SystemUiWindowTypeResolver.resolve() ?: run {
            initialized = false
            return
        }
        val geometry = geometry()

        addWindow(
            title = "NeXtepTopBar",
            view = TopAppStripView(
                context = context,
                initialSidebarSide = sidebarSide,
                onAppClicked = { intent ->
                    slotCoordinator?.openInMain(
                        intent,
                        autoMinimizeCurrent = SystemUiRuntime.shouldAutoMinimizeMainOnTopAppSwitch(),
                    )?.onFailure { error ->
                        NeXtepLog.warn("top_apps", "Unable to open ${intent.component} in main", error)
                    }
                },
                onMediaClicked = { session ->
                    slotCoordinator?.openMediaInMain(session)?.onFailure { error ->
                        NeXtepLog.warn("media_control", "Unable to open media app in main", error)
                        android.widget.Toast.makeText(context, AppLanguage.string(context, R.string.media_open_failed), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onSidebarSideRequested = onSidebarSideRequested,
                onSettingsRequested = onSettingsRequested,
                onExitRequested = onExitRequested,
                onFrostStrengthChanged = { strength ->
                    if (frostStrength != strength) {
                        frostStrength = strength
                        refreshWallpaperBackdrop()
                    }
                },
                onAppDragStarting = { source, intent ->
                    workspaceVisible && !suspended && internalDrag.start(
                        source, WorkspaceInternalDragController.Payload.App(intent),
                    )
                },
                onAppDragTouch = internalDrag::touch,
            ).also {
                topAppStrip = it
                it.setWorkgroupActions(workgroups::save, workgroups::saveNew, workgroups::choose,
                    { setSidebarCollapsed(!sidebarCollapsed) })
            }.let { strip ->
                ControlAreaPager(context, strip).also { controlAreaPager = it }
            },
            width = geometry.screenWidth,
            height = geometry.topHeight,
            gravity = Gravity.TOP,
            touchable = true,
            windowType = windowType,
        )
        addWindow(
            title = "NeXtepSidebar",
            view = sidebarPanel(geometry),
            width = geometry.sidebarWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.START
            } else {
                Gravity.END
            },
            y = geometry.topHeight,
            touchable = FeatureGate.TASK_SWAP.defaultEnabled,
            windowType = windowType,
        )
        addWindow(
            title = "NeXtepContentPanel",
            view = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(View(context).also { contentHighlightView = it })
                addView(android.widget.TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(9).toInt(), 0, dp(9).toInt(), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.argb(200, 25, 33, 44))
                        cornerRadius = dp(10)
                    }
                    visibility = View.GONE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.also { stageFocusBadge = it })
            },
            width = geometry.contentWidth,
            height = geometry.contentHeight,
            gravity = Gravity.TOP or if (sidebarSide == SidebarSide.LEFT) {
                Gravity.END
            } else {
                Gravity.START
            },
            y = geometry.topHeight,
            windowType = windowType,
        )
        addWindow(
            title = "AtomicSidebarHandle", view = sidebarHandle(),
            width = dp(20).toInt(), height = dp(80).toInt(), gravity = Gravity.TOP or Gravity.LEFT,
            touchable = true, windowType = windowType,
        )
        if (records.size != EXPECTED_WINDOW_COUNT) {
            records.forEach { record ->
                try {
                    windowManager.removeView(record.view)
                } catch (error: Throwable) {
                    NeXtepLog.warn("systemui_window", "Cleanup failed for ${record.title}", error)
                }
            }
            records.clear()
            slotCoordinator = null
            topAppStrip = null
            controlAreaPager = null
            sidebarView = null
            contentHighlightView = null
            slotWindows = emptyList()
            initialized = false
        }
    }

    private fun addWindow(
        title: String,
        view: View,
        width: Int,
        height: Int,
        gravity: Int,
        y: Int = 0,
        touchable: Boolean = false,
        windowType: Int,
    ) {
        val interactionFlags = if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                interactionFlags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.y = y
            this.title = title
            // A change in display axes is not a physical panel move. WM's default
            // move animation otherwise interpolates old/new x/y across the screen.
            runCatching {
                val privateFlags = WindowManager.LayoutParams::class.java.getField("privateFlags")
                privateFlags.setInt(this, privateFlags.getInt(this) or 0x40) // PRIVATE_FLAG_NO_MOVE_ANIMATION
            }.onFailure { NeXtepLog.warn("systemui_window", "Unable to disable panel move animation", it) }
        }
        val hostedView = if (title == "NeXtepContentPanel" || title == "AtomicSidebarHandle") view else WorkspacePanelHost(context, view)
        val record = WindowRecord(hostedView, title)
        configureWindow(record, params, geometry())
        hostedView.visibility = View.GONE
        try {
            windowManager.addView(hostedView, params)
            records += record
            rotationPanelHosts = records.mapNotNull { it.view as? WorkspacePanelHost }
            NeXtepLog.info("systemui_window", "Added $title")
        } catch (error: Throwable) {
            NeXtepLog.error("systemui_window", "Could not add $title", error)
        }
    }

    private fun applyWindowGeometry(geometry: WorkspaceGeometry, refreshControls: Boolean = true) {
        cancelDragVisuals()
        records.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            configureWindow(record, params, geometry)
            windowManager.updateViewLayout(record.view, params)
        }
        if (refreshControls) {
            controlAreaPager?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft)
            controlAreaPager?.refreshHost()
        }
    }

    private fun configureWindow(
        record: WindowRecord,
        params: WindowManager.LayoutParams,
        geometry: WorkspaceGeometry,
    ) {
        val touchable = !suspended && ((record.title == "NeXtepContentPanel" && groupInputBlocked) ||
            record.title == "NeXtepTopBar" || record.title == "AtomicSidebarHandle" ||
            (record.title == "NeXtepSidebar" && !sidebarCollapsed && FeatureGate.TASK_SWAP.defaultEnabled))
        params.flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // A foreground app normally hides live wallpaper, so screenshotWallpaper()
        // has no surface to read on entry. Keep that surface available behind the
        // workspace until exit/lock, without sending control gestures to it.
        params.flags = if (record.title == "NeXtepTopBar" && workspaceVisible && !suspended) {
            params.flags or WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        } else params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
        params.setWallpaperTouchEventsEnabled(false)
        // WorkspaceGeometry explicitly reserves navigation space in display coordinates.
        // FLAG_LAYOUT_IN_SCREEN alone does not disable inset fitting: a bottom navigation
        // inset can make WindowManager shift the entire full-height sidebar upward.
        // Apply this on both creation and reconfiguration, for either sidebar and rotation.
        params.setFitInsetsTypes(0)
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.gravity = Gravity.TOP or Gravity.LEFT
        setWindowBounds(record.title, params, geometry)
        if (record.title == "NeXtepContentPanel") {
            contentHighlightView?.layoutParams = FrameLayout.LayoutParams(
                geometry.contentWidth, geometry.contentHeight, Gravity.TOP or Gravity.LEFT,
            ).apply {
                leftMargin = geometry.contentLeft
                topMargin = geometry.contentTop
            }
            stageFocusBadge?.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(28).toInt(), Gravity.TOP or Gravity.LEFT,
            ).apply {
                leftMargin = geometry.contentLeft + dp(8).toInt()
                topMargin = geometry.contentTop + dp(8).toInt()
            }
            stageFocusBadge?.maxWidth = (geometry.contentWidth - dp(16).toInt()).coerceAtLeast(1)
        }
        // WM chooses these bounds during its rotation layout, before the
        // configuration callback can reach SystemUI's main looper. Without them
        // a portrait-sized window can be placed in landscape for an entire frame.
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("paramsForRotation")
            val rotations = Array(4) { rotation ->
                WindowManager.LayoutParams().apply {
                    copyFrom(params)
                    field.set(this, null)
                    setWindowBounds(record.title, this, geometry.rotatedTo(rotation))
                }
            }
            field.set(params, rotations)
            (record.view as? WorkspacePanelHost)?.setRotationFrames(rotations.mapIndexed { rotation, bounds ->
                WorkspacePanelProjection.Frame(bounds.width, bounds.height, geometry.rotatedTo(rotation).panelRotationDegrees)
            })
        }.onFailure { NeXtepLog.warn("systemui_window", "Rotation-specific panel bounds unavailable", it) }
        (record.view as? WorkspacePanelHost)?.setProjection(
            geometry.panelRotationDegrees, params.width, params.height,
        )
    }

    private fun setWindowBounds(title: String, params: WindowManager.LayoutParams, geometry: WorkspaceGeometry) {
        when (title) {
            "AtomicSidebarHandle" -> {
                if (!geometry.isLandscape) {
                    params.width = dp(20).toInt()
                    params.height = dp(80).toInt()
                    params.x = if (geometry.sidebarSide == SidebarSide.LEFT)
                        geometry.sidebarLeft + geometry.sidebarPhysicalWidth - params.width else geometry.sidebarLeft
                    params.y = geometry.sidebarTop + (geometry.sidebarPhysicalHeight - params.height) / 2
                } else {
                    params.width = dp(80).toInt()
                    params.height = dp(20).toInt()
                    params.x = geometry.sidebarLeft + (geometry.sidebarPhysicalWidth - params.width) / 2
                    params.y = if (geometry.isSidebarAtTop)
                        geometry.sidebarTop + geometry.sidebarPhysicalHeight - params.height else geometry.sidebarTop
                }
            }
            "NeXtepTopBar" -> {
                params.width = geometry.controlWidth
                params.height = geometry.controlHeight
                params.x = geometry.controlLeft
                params.y = geometry.controlTop
            }
            "NeXtepSidebar" -> {
                params.width = geometry.sidebarPhysicalWidth
                params.height = geometry.sidebarPhysicalHeight
                params.x = geometry.sidebarLeft
                params.y = geometry.sidebarTop
            }
            "NeXtepContentPanel" -> {
                // The transparent, non-touchable root observes full-display insets.
                // Its drag highlight child and visible tasks use the safe bounds.
                params.width = geometry.screenWidth
                params.height = geometry.screenHeight
                params.x = 0
                params.y = 0
            }
        }
    }

    private fun setGroupInputBlocked(blocked: Boolean) {
        if (groupInputBlocked == blocked) return
        groupInputBlocked = blocked
        records.firstOrNull { it.title == "NeXtepContentPanel" }?.let { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@let
            configureWindow(record, params, geometry())
            runCatching { windowManager.updateViewLayout(record.view, params) }.onFailure {
                NeXtepLog.warn("workgroup_cover", "Unable to update transition input protection", it)
            }
        }
    }

    private fun startWorkgroupCover(onReady: () -> Unit) {
        cancelDragVisuals()
        val geometry = geometry()
        groupInitialOwners = slotCoordinator?.slotStates().orEmpty().map { (it as? SlotState.Occupied)?.taskId }
        groupInitialFrames = slotWindows.map { it.contentFrameSequence }
        val frames = mutableListOf<WorkgroupTransitionCover.Frame>()
        try {
            val main = taskRepository.foregroundTask()
            val bitmap = if (WorkspaceWallpaperBackdrop.canCapture(context) && main != null) runCatching {
                TaskSurfaceCompat.capturePreview(main.taskId, Rect(geometry.availableLeft, geometry.availableTop,
                    geometry.availableRight, geometry.availableBottom))
            }.getOrNull() else null
            frames.add(WorkgroupTransitionCover.Frame(bitmap, floatArrayOf(
                geometry.contentLeft.toFloat(), geometry.contentTop.toFloat(),
                geometry.contentRight.toFloat(), geometry.contentTop.toFloat(),
                geometry.contentRight.toFloat(), geometry.contentBottom.toFloat(),
                geometry.contentLeft.toFloat(), geometry.contentBottom.toFloat())))
            slotWindows.sortedBy { it.view.z }.forEach { slot ->
                val corners = slot.view.screenQuad() ?: return@forEach
                // Display-size captures suffice for a stationary cover; avoid allocating
                // four full-resolution phone bitmaps inside the SystemUI heap.
                val captured = runCatching { slot.view.capturePreview() }.getOrNull()
                frames.add(WorkgroupTransitionCover.Frame(captured, corners, slot.view.alpha))
            }
            groupCover.start(geometry, frames, onReady)
        } catch (failure: Throwable) {
            frames.mapNotNull { it.bitmap }.distinct().forEach { if (!it.isRecycled) it.recycle() }
            throw failure
        }
    }

    private fun finishWorkgroupCover(success: Boolean) {
        groupCover.finish(success) {
            val states = slotCoordinator?.slotStates().orEmpty()
            states.size == WORKSPACE_SLOT_COUNT && states.indices.all { index ->
                when (val state = states[index]) {
                    is SlotState.Ready -> true
                    is SlotState.Occupied -> {
                        val slot = slotWindows[index]
                        slot.view.textureView.isAvailable &&
                            (state.taskId == groupInitialOwners.getOrNull(index) ||
                                slot.contentFrameSequence > (groupInitialFrames.getOrNull(index) ?: -1L))
                    }
                    else -> false
                }
            }
        }
    }

    private fun sidebarHandle(): View = android.widget.TextView(context).apply {
        text = "⋮"
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(190, 35, 45, 60))
        contentDescription = "滑动收起或展开侧栏"
        isClickable = true
        setOnClickListener { setSidebarCollapsed(!sidebarCollapsed) }
        var downX = 0f
        var downY = 0f
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
                android.view.MotionEvent.ACTION_UP -> {
                    val g = geometry()
                    val delta = if (g.isLandscape) event.rawY - downY else event.rawX - downX
                    val outward = if (g.isLandscape) !g.isSidebarAtTop else g.sidebarSide == SidebarSide.RIGHT
                    if (kotlin.math.abs(delta) > dp(24)) setSidebarCollapsed((delta > 0) == outward)
                    else view.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun setSidebarCollapsed(value: Boolean) {
        if (!workspaceVisible || suspended || groupInputBlocked || sidebarCollapsed == value) return
        cancelDragVisuals()
        sidebarCollapsed = value
        records.firstOrNull { it.title == "NeXtepSidebar" }?.let { record ->
            val params = record.view.layoutParams as WindowManager.LayoutParams
            configureWindow(record, params, geometry())
            windowManager.updateViewLayout(record.view, params)
            // Retain TextureViews and tasks; a GONE sidebar would destroy their displays.
            record.view.animate().cancel()
            record.view.animate().alpha(if (value) 0f else 1f).setDuration(180).start()
        }
    }

    private fun sidebarPanel(geometry: WorkspaceGeometry): View = object : FrameLayout(context) {
        private val dividerPaint = android.graphics.Paint().apply {
            color = Color.argb(90, 255, 255, 255)
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val thickness = resources.displayMetrics.density
            val edge = if (logicalSidebarSide(this@NeXtepWindowController.geometry()) == SidebarSide.LEFT) width - thickness else 0f
            canvas.drawRect(edge, 0f, edge + thickness, height.toFloat(), dividerPaint)
        }
    }.apply {
        sidebarView = this
        setBackgroundColor(Color.TRANSPARENT)
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotWindows = List(WORKSPACE_SLOT_COUNT) { index -> VirtualDisplaySlot(context, index) }
            slotWindows.forEach { slot ->
                slot.view.setBackgroundSwipeEnabled(slotBackgroundSwipeEnabled)
                addView(slot.view)
            }
            applySlotLayout(this, geometry)
            slotCoordinator = SlotTaskCoordinator(
                context = context,
                slots = slotWindows,
                mainTaskPresenter = mainTaskPresenter,
                geometryProvider = ::geometry,
                onSwapAnimation = ::animateDragSwap,
                onMainSlotAnimation = ::animateMainSlotSwap,
                onInternalSlotDrag = { source, drag ->
                    internalDrag.start(source, WorkspaceInternalDragController.Payload.Slot(drag))
                },
                onInternalDragTouch = internalDrag::touch,
            )
        }
    }

    private fun cancelDragVisuals() {
        internalDrag.cancel()
        slotWindows.forEach { it.view.cancelBackgroundSwipe() }
        slotCoordinator?.onSlotDragEnded()
        slotCoordinator?.cancelPendingExchange()
        swapAnimator.cancel()
    }

    private fun highlightInternalDragTarget(target: WorkspaceInternalDragController.Target?) {
        slotWindows.forEachIndexed { index, slot ->
            slot.view.setDropHighlighted(target == WorkspaceInternalDragController.Target.Slot(index))
        }
        topAppStrip?.setDismissTargetHighlighted(target == WorkspaceInternalDragController.Target.Background)
        contentHighlightView?.background =
            if (target == WorkspaceInternalDragController.Target.Main) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.argb(25, 62, 190, 198))
                    setStroke(dp(2).toInt(), Color.rgb(80, 216, 224))
                    cornerRadius = dp(8)
                }
            } else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    }

    private fun dropInternalPayload(
        payload: WorkspaceInternalDragController.Payload,
        target: WorkspaceInternalDragController.Target,
    ): Boolean {
        val coordinator = slotCoordinator ?: return false
        return when (target) {
            is WorkspaceInternalDragController.Target.Slot -> when (payload) {
                is WorkspaceInternalDragController.Payload.Slot -> coordinator.onSlotDropped(target.index, payload.drag)
                is WorkspaceInternalDragController.Payload.App -> coordinator.onIntentDropped(target.index, payload.intent)
            }
            WorkspaceInternalDragController.Target.Main ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dropSlotIntoMain(payload.drag)
            WorkspaceInternalDragController.Target.Background ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dismissDraggedSlot(payload.drag)
        }
    }

    private fun animateMainSlotSwap(slotIndex: Int, move: () -> Boolean, onFinished: () -> Unit) {
        animateSwap(slotIndex, null, null, move, onFinished)
    }

    private fun animateDragSwap(
        drag: SlotTaskDrag,
        targetIndex: Int?,
        move: () -> Boolean,
        onFinished: () -> Unit,
    ) {
        animateSwap(drag.slotIndex, targetIndex, drag, move, onFinished)
    }

    private fun animateSwap(
        sourceIndex: Int,
        targetIndex: Int?,
        drag: SlotTaskDrag?,
        move: () -> Boolean,
        onFinished: () -> Unit,
    ) {
        fun withoutPreview() {
            try { move() } finally { onFinished(); scheduleStageFocus() }
        }
        if (!workspaceVisible || suspended || !WorkspaceWallpaperBackdrop.canCapture(context)) {
            withoutPreview()
            return
        }
        val geometry = geometry()
        val previewHost = contentHighlightView?.parent as? FrameLayout
        val sourceSlot = slotWindows.getOrNull(sourceIndex)
        val sourceRect = Rect()
        if (previewHost == null || sourceSlot == null || !sourceSlot.view.getScreenVisibleRect(sourceRect)) {
            withoutPreview()
            return
        }
        val states = slotCoordinator?.slotStates().orEmpty()
        val sourceState = states.getOrNull(sourceIndex)
        val parkingMain = drag == null && targetIndex == null && sourceState is SlotState.Ready
        if (!parkingMain && sourceState !is SlotState.Occupied) {
            withoutPreview()
            return
        }
        val mainRect = Rect(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom)
        if (homePreviewBounds != mainRect) clearHomePreview()
        // Use only a settled HOME frame. Never wait for a display capture on the
        // UI thread or accept an in-flight capture containing this swap overlay.
        homePreviewGeneration.incrementAndGet()
        val targetSlot = targetIndex?.let { slotWindows.getOrNull(it) }
        val targetRect = Rect(mainRect)
        if (targetIndex != null) {
            if (targetSlot == null || !targetSlot.view.getScreenVisibleRect(targetRect)) {
                withoutPreview()
                return
            }
        }
        val backdrop = WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, 0, 0, frostStrength)
        val mainTaskId = if (targetIndex == null) mainTaskPresenter.currentTaskId() else null
        val mainPreview = mainTaskId?.let {
            TaskSurfaceCompat.capturePreview(it, Rect(geometry.availableLeft, geometry.availableTop,
                geometry.availableRight, geometry.availableBottom))
        }
        fun slotPreview(slot: VirtualDisplaySlot): android.graphics.Bitmap? {
            val source = slot.sourceBounds()
            return slot.view.capturePreview(source.width, source.height)
        }
        val sourcePreview = if (parkingMain) mainPreview else slotPreview(sourceSlot)
        val targetOccupied = targetIndex != null && states.getOrNull(targetIndex) is SlotState.Occupied
        val targetPreview = when {
            parkingMain -> null
            targetSlot != null && targetOccupied -> slotPreview(targetSlot)
            targetIndex == null -> mainPreview
            else -> null
        }
        val needsTargetPreview = !parkingMain && (targetOccupied || mainTaskId != null)
        if (sourcePreview == null || (needsTargetPreview && targetPreview == null)) {
            sourcePreview?.recycle()
            if (targetPreview !== sourcePreview) targetPreview?.recycle()
            withoutPreview()
            return
        }
        val from = RectF(if (parkingMain) mainRect else sourceRect)
        val to = RectF(if (parkingMain) sourceRect else targetRect)
        val start = if (drag != null) {
            val point = drag.dropPoint ?: android.graphics.PointF(to.centerX(), to.centerY())
            val halfWidth = sourceRect.width() * 0.45f
            val halfHeight = sourceRect.height() * 0.45f
            RectF(point.x - halfWidth, point.y - halfHeight, point.x + halfWidth, point.y + halfHeight)
        } else RectF(from)
        val incomingTaskId = (sourceState as? SlotState.Occupied)?.taskId
        val returningHomePackage = if (parkingMain) TriggerBroadcastContract.resolveHomePackage(context) else null
        val stationaryBackground = if (parkingMain) {
            homePreview?.copy(Bitmap.Config.ARGB_8888, false)
        } else null
        val previewSlots = listOfNotNull(sourceSlot, targetSlot)
        var sourceFrame = sourceSlot.contentFrameSequence
        var targetFrame = targetSlot?.contentFrameSequence ?: 0L
        previewSlots.forEach { it.view.beginExchangePreview() }
        try {
            swapAnimator.animate(
                sourcePreview, targetPreview, start, from, to,
                host = previewHost,
                backdrop = backdrop,
                stationaryBackground = stationaryBackground,
                durationMs = SystemUiRuntime.swapAnimationDurationMs(),
                returningHome = parkingMain,
                sourceQuad = if (parkingMain) null else sourceSlot.view.screenQuad(),
                targetQuad = if (parkingMain) sourceSlot.view.screenQuad() else targetSlot?.view?.screenQuad(),
                move = {
                    // Ignore frames produced while the old tasks were still in
                    // place. Handoff needs a buffer after the logical exchange.
                    sourceFrame = sourceSlot.contentFrameSequence
                    targetFrame = targetSlot?.contentFrameSequence ?: 0L
                    move()
                },
                isLiveReady = {
                    val currentStates = slotCoordinator?.slotStates().orEmpty()
                    fun slotReady(index: Int, slot: VirtualDisplaySlot, frame: Long): Boolean =
                        when (currentStates.getOrNull(index)) {
                            is SlotState.Ready -> true
                            is SlotState.Occupied -> slot.view.textureView.isAvailable && slot.contentFrameSequence > frame
                            else -> false
                        }
                    val sourceReady = slotReady(sourceIndex, sourceSlot, sourceFrame)
                    val targetReady = if (targetIndex != null && targetSlot != null) {
                        slotReady(targetIndex, targetSlot, targetFrame)
                    } else if (parkingMain) {
                        returningHomePackage != null &&
                            taskRepository.foregroundTask()?.component?.packageName == returningHomePackage
                    } else mainTaskPresenter.currentTaskId() == incomingTaskId
                    sourceReady && targetReady
                },
                onFinished = {
                    previewSlots.forEach { it.view.endExchangePreview() }
                    onFinished()
                    scheduleStageFocus()
                    if (parkingMain) backdropHandler.postDelayed({
                        if (!suspended && workspaceVisible) captureHomePreview(currentGeometry())
                    }, HOME_PREVIEW_SETTLE_DELAY_MS)
                },
            )
        } catch (error: Throwable) {
            swapAnimator.cancel()
            previewSlots.forEach { it.view.endExchangePreview() }
            if (!sourcePreview.isRecycled) sourcePreview.recycle()
            if (targetPreview != null && !targetPreview.isRecycled) targetPreview.recycle()
            if (stationaryBackground != null && !stationaryBackground.isRecycled) stationaryBackground.recycle()
            throw error
        }
    }

    private fun captureHomePreview(geometry: WorkspaceGeometry) {
        if (homePreviewCapturePending || suspended || !workspaceVisible || swapAnimator.isRunning) return
        val bounds = Rect(geometry.contentLeft, geometry.contentTop, geometry.contentRight, geometry.contentBottom)
        val token = homePreviewGeneration.incrementAndGet()
        homePreviewCapturePending = true
        homePreviewWorker.execute {
            val result = runCatching {
                if (token != homePreviewGeneration.get() || !WorkspaceWallpaperBackdrop.canCapture(context)) {
                    return@runCatching null
                }
                val foreground = taskRepository.foregroundTask() ?: return@runCatching null
                if (foreground.component.packageName != TriggerBroadcastContract.resolveHomePackage(context)) {
                    return@runCatching null
                }
                // The synchronous platform listener may wait seconds while the
                // display wakes. Its buffer wait must never block SystemUI drawing.
                val snapshot = TaskSurfaceCompat.captureDisplayPreview(bounds) ?: return@runCatching null
                val stillHome = runCatching { taskRepository.foregroundTask()?.taskId == foreground.taskId }
                    .getOrDefault(false)
                if (snapshot.width != bounds.width() || snapshot.height != bounds.height() || !stillHome) {
                    snapshot.recycle()
                    null
                } else snapshot
            }
            backdropHandler.post {
                homePreviewCapturePending = false
                val snapshot = result.getOrNull()
                val current = currentGeometry()
                val currentBounds = Rect(current.contentLeft, current.contentTop, current.contentRight, current.contentBottom)
                if (token != homePreviewGeneration.get() || suspended || !workspaceVisible ||
                    swapAnimator.isRunning || bounds != currentBounds || !WorkspaceWallpaperBackdrop.canCapture(context)
                ) {
                    snapshot?.recycle()
                    // A lock/configuration change can supersede a slow capture.
                    // Warm the new lifecycle once that old request has drained.
                    if (!suspended && workspaceVisible && !swapAnimator.isRunning) {
                        backdropHandler.postDelayed({
                            if (!suspended && workspaceVisible) captureHomePreview(currentGeometry())
                        }, HOME_PREVIEW_SETTLE_DELAY_MS)
                    }
                    return@post
                }
                result.exceptionOrNull()?.let {
                    NeXtepLog.warn("workspace_swap", "HOME preview unavailable", it)
                }
                clearHomePreview()
                homePreview = snapshot
                if (snapshot != null) homePreviewBounds.set(bounds)
            }
        }
    }

    private fun clearHomePreview() {
        homePreviewGeneration.incrementAndGet()
        homePreview?.recycle()
        homePreview = null
        homePreviewBounds.setEmpty()
    }

    private fun applySlotLayout(panel: FrameLayout, geometry: WorkspaceGeometry) {
        panel.invalidate()
        val profile = viewport.layout()
        val bounds = profile.slots
        slotWindows.forEachIndexed { index, slot ->
            slot.setLayoutProfile(profile)
            slot.view.setBackgroundSwipeSide(logicalSidebarSide(geometry))
            slot.view.layoutParams = FrameLayout.LayoutParams(
                bounds[index].width,
                bounds[index].height,
                Gravity.TOP,
            ).apply {
                marginStart = if (logicalSidebarSide(geometry) == SidebarSide.RIGHT) profile.gapPixels else 0
                marginEnd = if (logicalSidebarSide(geometry) == SidebarSide.LEFT) profile.gapPixels else 0
                topMargin = bounds[index].top
            }
        }
        applyStageStack(geometry)
    }

    private fun applyStageStack(geometry: WorkspaceGeometry) {
        if (slotWindows.isEmpty()) return
        val bounds = viewport.layout().slots
        val right = logicalSidebarSide(geometry) == SidebarSide.RIGHT
        val poses = StageStackPolicy.poses(bounds.map { it.height }, bounds.map { it.top },
            context.resources.displayMetrics.density, right, stageOrder)
        slotWindows.forEachIndexed { index, slot ->
            val pose = poses[index]
            slot.view.apply {
                pivotX = if (right) 0f else bounds[index].width.toFloat()
                pivotY = bounds[index].height / 2f
                cameraDistance = dp(900)
                rotationY = pose.tilt
                scaleX = pose.scale
                scaleY = pose.scale
                translationX = pose.x
                translationY = pose.y
                elevation = pose.z
                alpha = pose.alpha
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(20, 25, 34))
                    cornerRadius = dp(8)
                    setStroke(dp(1).toInt().coerceAtLeast(1), Color.argb(100, 190, 210, 230))
                }
                clipToOutline = true
            }
        }
    }

    private fun scheduleStageFocus() {
        if (!workspaceVisible || suspended) return
        backdropHandler.removeCallbacks(stageFocusUpdate)
        backdropHandler.postDelayed(stageFocusUpdate, 48)
    }

    private fun updateStageFocus() {
        if (!workspaceVisible || suspended) return
        runCatching {
            val main = taskRepository.foregroundTask()
            if (main == null) {
                stageFocusBadge?.visibility = View.GONE
                contentHighlightView?.foreground = null
                return@runCatching
            }
            if (main.taskId != stageMainId) {
                val oldMain = stageMainId
                val returnedSlot = slotCoordinator?.slotStates()?.indexOfFirst {
                    it is SlotState.Occupied && it.taskId == oldMain
                } ?: -1
                if (returnedSlot >= 0) {
                    stageOrder = StageStackPolicy.touch(stageOrder, returnedSlot)
                    applyStageStack(geometry())
                }
                stageMainId = main.taskId
            }
            val label = runCatching {
                val info = context.packageManager.getApplicationInfo(main.component.packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(main.component.packageName)
            stageFocusBadge?.apply {
                text = "${workgroups.statusLabel()} · $label"
                visibility = View.VISIBLE
            }
            contentHighlightView?.foreground = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1).toInt().coerceAtLeast(1), Color.argb(190, 95, 216, 228))
            }
        }.onFailure { NeXtepLog.warn("stage_focus", "Foreground decoration unavailable", it) }
    }

    private fun refreshWallpaperBackdrop(attempt: Int = 0) {
        val geometry = geometry()
        // Use the last frame immediately; surface capture runs off the UI thread.
        applyWallpaperBackdrop(geometry)
        val previous = wallpaperBitmap
        val generation = backdropGeneration.incrementAndGet()
        backdropWorker.execute {
            if (generation != backdropGeneration.get()) return@execute
            val identity = WorkspaceWallpaperBackdrop.identity(context)
            val captured = WorkspaceWallpaperBackdrop.capture(
                context, geometry.screenWidth, geometry.screenHeight,
            )
            val currentIdentity = WorkspaceWallpaperBackdrop.identity(context)
            // Do not associate a frame taken during a wallpaper change with the
            // new wallpaper. The next retry will capture the settled surface.
            val bitmap = captured?.takeUnless {
                identity != null && currentIdentity != null && identity != currentIdentity
            }
            if (bitmap !== captured) captured?.recycle()
            val fitted = if (bitmap == null && previous != null &&
                (previous.width != geometry.screenWidth || previous.height != geometry.screenHeight)
            ) WorkspaceWallpaperBackdrop.fit(previous, geometry.screenWidth, geometry.screenHeight)
            else null
            backdropHandler.post {
                if (generation != backdropGeneration.get() || !WorkspaceWallpaperBackdrop.canCapture(context)) {
                    bitmap?.recycle()
                    fitted?.recycle()
                    return@post
                }
                if (bitmap != null) {
                    wallpaperBitmap = bitmap
                    wallpaperRotation = geometry.displayRotation
                } else if (fitted != null) {
                    wallpaperBitmap = fitted
                    wallpaperRotation = geometry.displayRotation
                }
                // A failed capture or identity query must not erase a valid frame.
                applyWallpaperBackdrop(geometry)
                // A waking live engine can briefly expose its initial frame even
                // when capture succeeds. Refresh again after it has started drawing.
                if (attempt < BACKDROP_SETTLE_REFRESHES ||
                    (bitmap == null && attempt < BACKDROP_MAX_RETRIES)
                ) {
                    backdropHandler.postDelayed({
                        if (generation == backdropGeneration.get() && !suspended &&
                            workspaceVisible
                        ) refreshWallpaperBackdrop(attempt + 1)
                    }, minOf(1_000L, 200L * (attempt + 1)))
                }
            }
        }
    }

    private fun applyWallpaperBackdrop(geometry: WorkspaceGeometry, refreshContrast: Boolean = true) {
        val panel = geometry.rotatedTo(0)
        (records.firstOrNull { it.title == "NeXtepTopBar" }?.view as? WorkspacePanelHost)?.setPanelBackground(
            WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, panel.controlLeft, panel.controlTop,
                frostStrength, wallpaperRotation, panel.displayRotation),
        )
        if (refreshContrast) {
            topAppStrip?.setMediaWallpaperLight(WorkspaceWallpaperBackdrop.isLight(
                wallpaperBitmap, panel.controlLeft, panel.controlTop, panel.controlWidth, panel.controlHeight,
            ))
        }
        applySidebarBackdrop(geometry)
    }

    private fun applySidebarBackdrop(geometry: WorkspaceGeometry) {
        val panel = geometry.rotatedTo(0)
        (records.firstOrNull { it.title == "NeXtepSidebar" }?.view as? WorkspacePanelHost)?.setPanelBackground(
            WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, panel.sidebarLeft, panel.sidebarTop,
                frostStrength, wallpaperRotation, panel.displayRotation),
        )
    }

    private fun logicalSidebarSide(geometry: WorkspaceGeometry): SidebarSide =
        geometry.panelSidebarSide

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density

    private fun geometry(): WorkspaceGeometry = currentGeometry()


    private val transitionInterpolator = WorkspaceTransitionSpec.interpolator

    private companion object {
        const val EXPECTED_WINDOW_COUNT = 4
        const val BACKDROP_SETTLE_REFRESHES = 2
        const val BACKDROP_MAX_RETRIES = 8
        const val HOME_PREVIEW_SETTLE_DELAY_MS = 100L
        const val RESUME_FRAME_FALLBACK_DELAY_MS = 250L
        const val WORKSPACE_ENTER_DURATION_MS = WorkspaceTransitionSpec.DURATION_MS
        const val WORKSPACE_EXIT_DURATION_MS = WorkspaceTransitionSpec.DURATION_MS
        const val SIDEBAR_SWITCH_DURATION_MS = 220L
        const val ENTER_OFFSET_DP = 12
        const val EXIT_OFFSET_DP = 8
        const val SIDEBAR_ENTER_OFFSET_DP = 28
    }
}
