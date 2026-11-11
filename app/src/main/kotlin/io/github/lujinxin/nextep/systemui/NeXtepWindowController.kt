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
    private var shownWindowCount = 0
    private var sidebarAddButton: android.widget.TextView? = null
    private val windowPicker = AtomicPanel(context)
    private val appCatalog = StageAppCatalog(context)
    private val frequentApps = StageFrequentApps(context)
    private val core = StageQuickStore(context)
    private val cinemaPolicy = StageCinemaPolicy().apply { mode = core.cinemaMode }
    private val mediaSignals = StageMediaSignals(context) { scheduleCinemaCheck() }
    private var cinemaSlot: Int? = null
    private var quickBar: StageQuickBarView? = null
    private var quickExpandButton: android.widget.TextView? = null
    private var quickChromeSuppressed = false
    private var quickInputReady = false
    private var cinemaPortrait = false
    private val directionDebounce = StageDirectionDebounce()
    private var cinemaEpoch = 0
    private var quickGeneration = 0
    private var cinemaApplying = false
    private val cinemaCheck = Runnable { evaluateCinema() }
    private var windowPickerGeneration = 0
    private var windowRefreshQueued = false
    private fun scheduleWindowRefresh() {
        if (windowRefreshQueued) return
        windowRefreshQueued = true
        backdropHandler.post {
            windowRefreshQueued = false
            if (workspaceVisible && !suspended) {
                applyStageStack(geometry())
                scheduleCinemaCheck()
                scheduleStageFocus()
            }
        }
    }
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
    private val swapCapture = StageAsyncPreviewCapture()
    private val internalDrag = WorkspaceInternalDragController(
        context, ::geometry, { slotWindows.map { it.view } },
        ::highlightInternalDragTarget, ::dropInternalPayload,
        { topAppStrip?.finishInternalAppDrag() },
    )
    private val contentDrag = StageContentDragBridge(context,
        { x, y ->
            slotWindows.withIndex().sortedByDescending { it.value.view.z }.firstOrNull {
                it.value.view.isWorkspaceCardVisible() && it.value.view.containsScreenPoint(x, y)
            }?.let { entry -> (slotCoordinator?.slotStates()?.getOrNull(entry.index) as? SlotState.Occupied)?.let {
                entry.index to it.taskId
            } }
        }, { index, task ->
            val engine = slotCoordinator
            if (workspaceVisible && !suspended && !groupInputBlocked && !workgroups.isSwitching() &&
                engine?.isGroupRestoreReady() == true && (engine.slotStates().getOrNull(index) as? SlotState.Occupied)?.taskId == task) {
                engine.onSlotClicked(index); true
            } else false
        })
    private val backdropGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val backdropWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NeXtep-Backdrop")
    }
    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val smartRail = StageSmartRail(context, backdropHandler,
        { workspaceVisible && !suspended && !sidebarCollapsed && !groupInputBlocked &&
            cinemaSlot == null && !workgroups.isSwitching() && slotCoordinator?.isGroupRestoreReady() == true &&
            !geometry().railAtTop && !windowPicker.isShowing && !AtomicPopupMenu.isShowing() &&
            SystemUiPanelMonitor.refresh(context) == StagePanelMenuGate.State.CLOSED }, ::renderSmartRail)
    private fun renderSmartRail(hidden: Boolean) {
        // Display buffers and the frozen main/slot geometry remain untouched.
        records.filter { it.title == "NeXtepSidebar" || it.title == "AtomicSidebarHandle" }.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            configureWindow(record, params, geometry())
            windowManager.updateViewLayout(record.view, params)
            if (record.title == "NeXtepSidebar") {
                record.view.animate().cancel()
                record.view.alpha = if (hidden) 0f else 1f
            }
        }
    }
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
        smartRail.stop()
        cinemaEpoch++; directionDebounce.reset()
        mediaSignals.stop(); backdropHandler.removeCallbacks(cinemaCheck)
        contentDrag.cancel()
        windowPickerGeneration++
        windowPicker.dismiss()
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
        mediaSignals.start(); refreshQuickBar()
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
        syncCoreViewport()
        mediaSignals.start(); refreshQuickBar()
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
        // Activation can restore some retained tasks, or leave zero occupied
        // windows. Commit the actual final rail/main geometry before returning.
        applyStageStack(geometry())
        applyWindowGeometry(geometry(), refreshControls = false)
        scheduleStageFocus()
        return geometry()
    }

    fun hide() {
        smartRail.stop()
        cinemaEpoch++; directionDebounce.reset()
        mediaSignals.stop(); backdropHandler.removeCallbacks(cinemaCheck)
        cinemaPolicy.reset(); cinemaSlot = null; viewport.cinema = false
        slotWindows.forEach { it.setCinema(false) }
        contentDrag.cancel()
        windowPickerGeneration++
        windowPicker.dismiss()
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
        scheduleCinemaCheck()
        scheduleStageFocus()
    }

    internal fun energySnapshot() = android.os.Bundle().apply {
        putInt("slot_count", slotWindows.count { it.displayId() != null })
        putLong("slot_frames", slotWindows.sumOf { it.contentFrameSequence })
    }

    fun openFromLauncher(intent: Intent): Result<Unit> = slotCoordinator
        ?.openFromLauncher(intent)
        ?: Result.failure(IllegalStateException("Slot coordinator unavailable"))

    fun currentGeometry(refreshViewport: Boolean = false): WorkspaceGeometry {
        syncCoreViewport()
        return viewport.geometry(sidebarSide, refreshViewport).let {
            if (sidebarCollapsed) io.github.x647368maker.atomicworkbench.workspace.StageCollapsedRailPolicy.geometry(it, context.resources.displayMetrics.density)
            else if (viewport.activeCount == 0) it.copy(sidebarWidth = minOf(it.sidebarWidth, dp(if (it.railAtTop) 56 else 52).toInt())) else it
        }
    }

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
        if (cinemaSlot != null && viewport.geometry(sidebarSide).isLandscape) {
            cinemaPolicy.leave(); cinemaSlot = null; viewport.cinema = false
        }
        val geometry = geometry()
        SystemServerWorkspaceBridge.publishLayout(context, viewport.layout())
        applyWindowGeometry(geometry, refreshControls = !rotationOnly)
        if (rotationOnly && !geometry.railAtTop) {
            // Only project the existing windows into the new display coordinates.
            // Their logical content, slot buffers and wallpaper frame stay intact.
            backdropGeneration.incrementAndGet()
            controlAreaPager?.setLandscape(geometry.isLandscape, geometry.isControlOnLeft, refreshHost = false)
        } else {
            sidebarView?.let { applySlotLayout(it, geometry) }
            slotCoordinator?.onTasksChanged()
            val cached = wallpaperBitmap
            if (StageBackdropReusePolicy.canReuse(cached != null && !cached.isRecycled,
                    cached?.width ?: 0, cached?.height ?: 0, wallpaperRotation,
                    geometry.screenWidth, geometry.screenHeight, geometry.displayRotation)) {
                applyWallpaperBackdrop(geometry)
            } else refreshWallpaperBackdrop()
        }
        NeXtepLog.info("workspace_configuration", "Reconfigured geometry=$geometry")
        geometry
    }

    fun setSidebarSide(side: SidebarSide): Result<WorkspaceGeometry> = runCatching {
        setSidebarCollapsed(false)
        clearHomePreview()
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        viewport.setSidebarSide(side)
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

    fun stageSettingsCommand(intent: Intent): android.os.Bundle = runCatching {
        syncCoreViewport()
        val command = intent.getStringExtra(io.github.x647368maker.atomicworkbench.trigger.StageControlContract.COMMAND)
        when (command) {
            "query" -> Unit
            "main_priority", "main_complete" -> {
                core.mainPriority = command == "main_priority"; syncCoreViewport()
                if (workspaceVisible && !suspended) onViewportChanged()
            }
            "cinema_auto", "cinema_manual", "cinema_landscape", "cinema_portrait", "cinema_off" -> {
                leaveCinema(); core.cinemaMode = when(command) { "cinema_auto" -> StageCinemaPolicy.Mode.AUTO; "cinema_manual" -> StageCinemaPolicy.Mode.MANUAL; "cinema_landscape" -> StageCinemaPolicy.Mode.LANDSCAPE; "cinema_portrait" -> StageCinemaPolicy.Mode.PORTRAIT; else -> StageCinemaPolicy.Mode.OFF }
                cinemaPolicy.mode = core.cinemaMode; scheduleCinemaCheck()
            }
            "cinema_enter" -> { check(workspaceVisible && !suspended) { "请先开启台前调度" }; showCinemaMenu(records.first { it.title == "StageMainHandle" }.view) }
            "cinema_leave" -> leaveCinema()
            "quick_expand", "quick_collapse", "quick_off" -> { if (command == "quick_off") core.enabled = false; setQuickExpanded(command == "quick_expand") }
            "quick_auto", "quick_fixed" -> { core.fixed = command == "quick_fixed"; refreshQuickBar() }
            "adaptive_on", "adaptive_off" -> { core.adaptive = command == "adaptive_on"; syncCoreViewport(); if(workspaceVisible && !suspended) onViewportChanged() }
            "wechat_move" -> moveWechat()
            "layout_top", "layout_side" -> changeStageLayout(command == "layout_top")
            "close_main" -> closeMainWindow()
            "fullscreen" -> onExitRequested()
            "add_window", "replace_window", "close_window" -> {
                check(workspaceVisible && !suspended && !workgroups.isSwitching() && !groupInputBlocked) { "请先开启台前调度" }
                val engine = checkNotNull(slotCoordinator)
                check(engine.isGroupRestoreReady()) { "窗口正在切换" }
                val expectedTask = intent.getIntExtra("expected_task", -1)
                if (expectedTask >= 0) check((engine.slotStates().getOrNull(intent.getIntExtra("slot", -1)) as? SlotState.Occupied)?.taskId == expectedTask) { "小窗已发生变化，请刷新列表" }
                if (command == "close_window") engine.closeWindow(intent.getIntExtra("slot", -1)).getOrThrow()
                else {
                    val launch = checkNotNull(intent.getParcelableExtra(TriggerBroadcastContract.EXTRA_LAUNCH_INTENT, Intent::class.java))
                    if (command == "add_window") addStageWindow(engine, launch).getOrThrow()
                    else engine.replaceWindow(intent.getIntExtra("slot", -1), launch).getOrThrow()
                }
                scheduleWindowRefresh()
            }
            "open" -> {
                check(workspaceVisible && !suspended && !workgroups.isSwitching()) { "请先开启台前调度，等待任务就绪" }
                val launch = checkNotNull(intent.getParcelableExtra(TriggerBroadcastContract.EXTRA_LAUNCH_INTENT, Intent::class.java))
                checkNotNull(slotCoordinator).openInMain(launch).getOrThrow()
            }
            "save", "new" -> check(workgroups.saveForSettings(command == "new")) { "未保存：请先打开台前调度并等待任务就绪" }
            "restore" -> workgroups.restoreForSettings(checkNotNull(intent.getStringExtra("group_id")))
            "rename", "delete" -> workgroups.editForSettings(checkNotNull(intent.getStringExtra("group_id")),
                if (command == "rename") checkNotNull(intent.getStringExtra("group_name")) else null)
            "collapse", "expand" -> {
                check(workspaceVisible && !suspended && !workgroups.isSwitching() && !groupInputBlocked) { "请先打开台前调度，等待工作组切换完成" }
                setSidebarCollapsed(command == "collapse")
            }
            "left", "right" -> {
                val side = if (command == "left") SidebarSide.LEFT else SidebarSide.RIGHT
                // Route through the workspace owner so native geometry and input
                // mapping change together. Persist side even while inactive.
                if (workspaceVisible && !suspended) onSidebarSideRequested(side)
                else { viewport.setSidebarSide(side); sidebarSide = side; SystemServerWorkspaceBridge.publish(context, false, geometry()) }
            }
            else -> error("未知设置操作")
        }
        android.os.Bundle().apply {
            putBoolean("ok", true)
            putBoolean("layout_top", viewport.isTopRail())
            putString("cinema_mode", core.cinemaMode.name)
            putBoolean("quick_fixed", core.fixed); putBoolean("quick_expanded", core.enabled && core.expanded)
            putBoolean("adaptive", core.adaptive); putBoolean("main_priority", core.mainPriority)
            putString("groups", workgroups.settingsSnapshot())
            putString("slot_diagnostics", slotStatesDescription())
            putString("windows", org.json.JSONArray().also { array ->
                slotCoordinator?.slotStates()?.forEachIndexed { index, state ->
                    if (state is SlotState.Occupied) array.put(org.json.JSONObject().put("slot", index)
                        .put("task", state.taskId).put("package", state.component.packageName))
                }
            }.toString())
            putString("message", if (command == "restore") "已请求切换，最终结果以窗口与提示为准" else "已处理")
        }
    }.getOrElse { android.os.Bundle().apply {
        putBoolean("ok", false); putString("message", it.message ?: "操作失败")
    } }

    /** Read-only scope diagnostic for native two-pane scenes; never moves a task. */
    fun slotStatesDescription(): String {
        val g = currentGeometry()
        val tasks = runCatching { taskRepository.runningTasks() }.getOrDefault(emptyList())
        val scene = StageNativeParallelDiagnostics.Scene(
            screenWidth = g.screenWidth,
            screenHeight = g.screenHeight,
            densityDpi = context.resources.displayMetrics.densityDpi,
            railAtTop = g.railAtTop,
            controlHeight = g.controlHeight,
            sidebarWidth = g.sidebarPhysicalWidth,
            sidebarHeight = g.sidebarPhysicalHeight,
            mainLeft = g.contentLeft,
            mainTop = g.contentTop,
            mainWidth = g.contentWidth,
            mainHeight = g.contentHeight,
            fittedMainId = mainTaskPresenter.currentTaskId(),
            tasks = tasks.take(6).map { task ->
                StageNativeParallelDiagnostics.Task(
                    id = task.taskId,
                    app = task.component.packageName,
                    display = task.displayId,
                    mode = task.windowingMode,
                    vendorWindowed = task.vendorWindowed,
                    bounds = task.bounds.flattenToString(),
                )
            },
        )
        return "screen=${g.screenWidth}x${g.screenHeight} dpi=${context.resources.displayMetrics.densityDpi} collapsed=$sidebarCollapsed occupied=${viewport.activeCount} shown=$shownWindowCount\\n" +
            (slotCoordinator?.slotDiagnostics() ?: "unavailable") + "\\n" +
            StageNativeParallelDiagnostics.report(scene)
    }

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
            // Reuse the existing status-safe wallpaper host. Only the quick-bar
            // button or explicitly expanded bar owns a narrow touch region.
            view = StageQuickBarHost(context, { ready ->
                quickInputReady = ready
                backdropHandler.post {
                    renderQuickChrome()
                    records.firstOrNull { it.title == "NeXtepTopBar" }?.takeIf { it.view.isAttachedToWindow }?.let {
                        val params = it.view.layoutParams as? WindowManager.LayoutParams
                        if (params != null) { configureWindow(it, params, geometry()); windowManager.updateViewLayout(it.view, params) }
                    }
                }
            }) {
                backdropHandler.post { setQuickExpanded(false); showStageMessage("快捷栏触摸区域不可用，已安全收起") }
            }.apply {
                quickBar = StageQuickBarView(context, ::quickSelected,
                    { chooseWindowApp(it, null, main = true) }, { setQuickExpanded(false) })
                addView(quickBar, FrameLayout.LayoutParams(-1, dp(52).toInt(), Gravity.BOTTOM).apply {
                    leftMargin = dp(64).toInt(); rightMargin = dp(64).toInt(); bottomMargin = dp(4).toInt()
                })
                quickExpandButton = android.widget.TextView(context).apply {
                    text = "▦"; textSize = 24f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE); contentDescription = "展开常用应用快捷栏"
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(175, 35, 38, 44)); cornerRadius = dp(18) }
                    setOnClickListener { setQuickExpanded(true) }
                }.also { addView(it, FrameLayout.LayoutParams(dp(48).toInt(), dp(48).toInt(), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(64).toInt(); bottomMargin = dp(4).toInt()
                }) }
                renderQuickChrome()
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
        addWindow(
            title = "StageMainHandle", view = StageMainHandle(context, ::showMainMenu, ::closeMainWindow),
            width = dp(48).toInt(), height = dp(48).toInt(), gravity = Gravity.TOP or Gravity.LEFT,
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
        val hostedView = if (title == "NeXtepContentPanel" || title == "AtomicSidebarHandle" || title == "StageMainHandle") view else WorkspacePanelHost(context, view)
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
            record.title == "AtomicSidebarHandle" || record.title == "StageMainHandle" ||
            (record.title == "NeXtepTopBar" && quickInputReady && !quickChromeSuppressed && !groupInputBlocked) ||
            (record.title == "NeXtepSidebar" && !sidebarCollapsed && !smartRail.hidden && FeatureGate.TASK_SWAP.defaultEnabled))
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
        if (record.title == "NeXtepTopBar") renderQuickChrome()
        if (record.title == "AtomicSidebarHandle") (record.view as? android.widget.TextView)?.apply {
            text = if (!sidebarCollapsed) "‹›" else if (geometry.railAtTop || geometry.isLandscape)
                (if (geometry.railAtTop || geometry.isSidebarAtTop) "⌄ 展开小窗" else "⌃ 展开小窗")
                else if (geometry.sidebarSide == SidebarSide.LEFT) "›\n展开" else "‹\n展开"
            textSize = if (sidebarCollapsed) 13f else 14f
            contentDescription = if (sidebarCollapsed || smartRail.hidden) "展开小窗" else "收起小窗"
            if (smartRail.hidden) { text = "│"; textSize = 18f }
        }
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
                topMargin = if (geometry.isLandscape) geometry.contentTop + dp(8).toInt() else
                    (geometry.contentTop - dp(32).toInt()).coerceAtLeast(geometry.controlTop + geometry.controlHeight)
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

    private fun setWindowBounds(title: String, params: WindowManager.LayoutParams, requested: WorkspaceGeometry) {
        val geometry = requested
        when (title) {
            "StageMainHandle" -> {
                val box=io.github.x647368maker.atomicworkbench.workspace.StageMainWindowMetrics.mainHandle(geometry,context.resources.displayMetrics.density)
                params.width=box.width;params.height=box.height;params.x=box.left;params.y=box.top
            }
            "AtomicSidebarHandle" -> {
                params.width=dp(48).toInt();params.height=dp(48).toInt()
                params.x=geometry.availableRight-dp(56).toInt();params.y=geometry.availableTop+dp(4).toInt()
                if (smartRail.hidden && geometry.isNativeLandscapeRail) {
                    // On native tablets the rail lives at the top or bottom.
                    // Its tiny reopen handle must not jump to a phone's left/right edge.
                    val box=io.github.x647368maker.atomicworkbench.workspace.NativeLandscapeHandlePlacement
                        .hidden(geometry,context.resources.displayMetrics.density)
                    params.width=box.width;params.height=box.height
                    params.x=box.left;params.y=box.top
                } else if (smartRail.hidden && !geometry.railAtTop) {
                    // Inset from the back-gesture edge; no gesture exclusion or global listener.
                    params.width=dp(24).toInt();params.height=dp(48).toInt()
                    params.x=if (geometry.sidebarSide==SidebarSide.LEFT) geometry.availableLeft+dp(32).toInt()
                        else geometry.availableRight-dp(56).toInt()
                    params.y=(geometry.availableTop+geometry.availableBottom-params.height)/2
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
        windowPickerGeneration++; windowPicker.dismiss()
        val geometry = geometry()
        groupInitialOwners = slotCoordinator?.slotStates().orEmpty().map { (it as? SlotState.Occupied)?.taskId }
        groupInitialFrames = slotWindows.map { it.contentFrameSequence }
        val frames = mutableListOf<WorkgroupTransitionCover.Frame>()
        try {
            val main = taskRepository.foregroundTask()
            val bitmap = if (WorkspaceWallpaperBackdrop.canCapture(context) && main != null) runCatching {
                TaskSurfaceCompat.capturePreview(main.taskId, Rect(0, 0, geometry.screenWidth, geometry.screenHeight))
            }.getOrNull() else null
            frames.add(WorkgroupTransitionCover.Frame(bitmap, floatArrayOf(
                geometry.contentLeft.toFloat(), geometry.contentTop.toFloat(),
                geometry.contentRight.toFloat(), geometry.contentTop.toFloat(),
                geometry.contentRight.toFloat(), geometry.contentBottom.toFloat(),
                geometry.contentLeft.toFloat(), geometry.contentBottom.toFloat())))
            slotWindows.sortedBy { it.view.z }.forEach { slot ->
                if (!slot.view.isWorkspaceCardVisible()) return@forEach
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

    private fun showStageMessage(value: String) = android.widget.Toast.makeText(context, value, android.widget.Toast.LENGTH_LONG).show()

    private fun syncCoreViewport() {
        core.layoutScope = viewport.prepareLayoutScope()
        sidebarSide = viewport.selectedSidebarSide()
        viewport.activeCount = slotCoordinator?.slotStates()?.count { it is SlotState.Occupied } ?: 0
        viewport.adaptive = core.adaptive
        viewport.mainPriority = core.mainPriority
        viewport.mainInputRegionReady = sidebarTouchRegionReady
        viewport.quickExpanded = core.enabled && core.expanded
        cinemaSlot?.let { index ->
            if ((slotCoordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied)?.taskId != cinemaPolicy.task) {
                cinemaSlot = null; cinemaPolicy.leave(); directionDebounce.reset()
            }
        }
        viewport.cinema = cinemaSlot != null && !sidebarCollapsed
        viewport.cinemaPortrait = cinemaPortrait
    }

    private fun addStageWindow(engine: SlotTaskCoordinator, intent: Intent): Result<Unit> {
        if (sidebarCollapsed) setSidebarCollapsed(false)
        // Rebind presentation before capacity checks. Existing tasks are never removed.
        sidebarView?.let { applySlotLayout(it, currentGeometry()) }
        scheduleWindowRefresh()
        return engine.addWindow(intent) { showStageMessage(it) }
    }

    private fun renderQuickChrome() {
        val shown = quickInputReady && !quickChromeSuppressed && !suspended
        quickBar?.visibility = if (shown && core.enabled && core.expanded) View.VISIBLE else View.GONE
        quickExpandButton?.visibility = if (shown && !(core.enabled && core.expanded)) View.VISIBLE else View.GONE
        quickBar?.parent?.requestLayout()
    }
    private fun setQuickExpanded(expanded: Boolean) {
        if (groupInputBlocked || workgroups.isSwitching()) return
        if (expanded) core.enabled = true
        core.expanded = expanded; syncCoreViewport()
        renderQuickChrome()
        if (!expanded) quickGeneration += 1
        else if (quickBar?.hasEntries != true) refreshQuickBar()
        if (workspaceVisible && !suspended) onViewportChanged()
    }

    private fun refreshQuickBar() {
        if (!core.enabled || !core.expanded || !workspaceVisible || suspended) return
        val generation = ++quickGeneration
        appCatalog.request { result -> result.onSuccess { entries ->
            frequentApps.request(entries) recentRow@ { recent ->
                if (generation != quickGeneration || !workspaceVisible || suspended || !core.expanded) return@recentRow
                val byKey = entries.associateBy { it.key }
                val keys = StageQuickPolicy.select(core.fixed, core.pins, recent.map { it.key }, byKey.keys)
                quickBar?.render(keys.mapNotNull(byKey::get))
            }
        } }
    }

    private fun quickSelected(entry: io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository.AppEntry, anchor: View, long: Boolean) {
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return
        fun open(slot: Int? = null) {
            val engine = slotCoordinator ?: return
            if (!engine.isGroupRestoreReady()) { showStageMessage("窗口正在切换"); return }
            val existing = StageQuickPolicy.existing("${entry.userId}:${entry.component.packageName}",
                taskRepository.runningTasks().associate { "${it.userId}:${it.component.packageName}" to it.taskId })
            val result = if (slot == null) engine.openInMain(entry.launchIntent(), preferredTaskId = existing) else engine.replaceWindow(slot, entry.launchIntent())
            result.onFailure { showStageMessage(it.message ?: "应用未打开") }
            frequentApps.observe(entry.component.packageName, entry.userId)
        }
        if (!long) { open(); return }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "启动 / 切换到主窗" to { open() }
        actions += "添加到小窗" to {
            slotCoordinator?.let { addStageWindow(it,entry.launchIntent()).onFailure { error -> showStageMessage(error.message ?: "小窗未打开") } }
        }
        slotCoordinator?.slotStates()?.forEachIndexed { index, state -> if (state is SlotState.Occupied) {
            val expected = state.taskId
            actions += "替换小窗 ${index + 1}" to {
                if ((slotCoordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied)?.taskId == expected) open(index)
            }
        } }
        actions += (if (entry.key in core.pins) "取消固定" else "固定到快捷栏（最多六个）") to {
            if (entry.key !in core.pins && core.pins.size >= 6) showStageMessage("已固定六个，请先取消一个")
            else { core.pins = StageQuickPolicy.toggle(core.pins, entry.key); refreshQuickBar() }
        }
        if (entry.key in core.pins) {
            actions += "向前排序" to { core.pins = StageQuickPolicy.move(core.pins, entry.key, -1); refreshQuickBar() }
            actions += "向后排序" to { core.pins = StageQuickPolicy.move(core.pins, entry.key, 1); refreshQuickBar() }
        }
        AtomicPopupMenu.show(context, anchor, actions)
    }

    private fun scheduleCinemaCheck() {
        if (!workspaceVisible || suspended) return
        backdropHandler.removeCallbacks(cinemaCheck)
        backdropHandler.postDelayed(cinemaCheck, 180)
    }
    private fun evaluateCinema(confirm: Boolean = false) {
        if (!workspaceVisible || suspended || cinemaApplying || groupInputBlocked || workgroups.isSwitching()) return
        if (slotCoordinator?.isGroupRestoreReady() != true) return
        val states = slotCoordinator?.slotStates().orEmpty()
        val occupied = states.filterIsInstance<SlotState.Occupied>()
        val tasks = taskRepository.runningTasks().associateBy { it.taskId }
        val bound = StageMediaBinding.bind(tasks.values.map { StageObservedTask(it.taskId, it.component.packageName, it.userId) }, mediaSignals.observations())
        val live = occupied.mapTo(mutableSetOf()) { it.taskId }
        val playing = occupied.filter { bound[it.taskId]?.state == StagePlayback.PLAYING }.mapTo(mutableSetOf()) { it.taskId }
        // Missing or ambiguous session data is UNKNOWN, not a command or evidence that playback stopped.
        val present = live.filterTo(mutableSetOf()) { bound[it]?.state != StagePlayback.STOPPED }
        val trusted = occupied.firstOrNull { state ->
            val task = tasks[state.taskId]
            task != null && task.displayId == state.displayId && state.taskId in playing &&
                StageVideoSignalPolicy.direction(task.requestedOrientation, task.configurationOrientation) != null
        }?.taskId
        cinemaPolicy.mode = core.cinemaMode
        val before = cinemaPolicy.task
        val now = android.os.SystemClock.uptimeMillis()
        val next = cinemaPolicy.update(trusted, live, playing, now, present)
        val target = next?.let(tasks::get)
        val direction = target?.let { StageVideoSignalPolicy.direction(it.requestedOrientation, it.configurationOrientation) }
        val chosen = if (next != null && !cinemaPolicy.isManual) directionDebounce.update(next, direction, next in playing, now) else null
        if (next != before) applyCinemaTask(next, direction == StageVideoDirection.PORTRAIT)
        else if (chosen != null && !cinemaPolicy.isManual && (chosen == StageVideoDirection.PORTRAIT) != cinemaPortrait)
            applyCinemaTask(next, chosen == StageVideoDirection.PORTRAIT)
        if (!confirm && trusted != null && (next == null || next in playing && !cinemaPolicy.isManual)) {
            val epoch = cinemaEpoch
            backdropHandler.postDelayed({ if(epoch == cinemaEpoch) evaluateCinema(true) }, 750)
        }
    }
    private fun enterCinema(task: Int, portrait: Boolean = core.cinemaMode == StageCinemaPolicy.Mode.PORTRAIT) {
        if (core.cinemaMode == StageCinemaPolicy.Mode.OFF) { showStageMessage("影音布局已关闭，请在设置中改为手动或自动"); return }
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching() || slotCoordinator?.isGroupRestoreReady() != true) return
        cinemaEpoch++; directionDebounce.reset()
        cinemaPolicy.enter(task); setSidebarCollapsed(false); applyCinemaTask(task, portrait)
    }
    private fun leaveCinema() {
        cinemaEpoch++; directionDebounce.reset()
        cinemaPolicy.leave(); applyCinemaTask(null)
    }
    private fun applyCinemaTask(task: Int?, portrait: Boolean = cinemaPortrait) {
        if (cinemaApplying) return
        val index = task?.let { id -> slotCoordinator?.slotStates()?.indexOfFirst { (it as? SlotState.Occupied)?.taskId == id }?.takeIf { it >= 0 } }
        if (index == cinemaSlot && (index == null || portrait == cinemaPortrait)) return
        if (viewport.geometry(sidebarSide).isLandscape && index != null) { cinemaPolicy.leave(); return }
        cinemaApplying = true
        val previous = cinemaSlot
        try {
            cinemaSlot = index; cinemaPortrait = portrait; syncCoreViewport()
            if (workspaceVisible && !suspended) onViewportChanged()
        } catch (error: Throwable) {
            cinemaSlot = null; viewport.cinema = false; cinemaPolicy.leave()
            slotWindows.forEach { it.setCinema(false) }
            runCatching { if (workspaceVisible && !suspended) onViewportChanged() }
            showStageMessage("影音布局不可用，已恢复普通布局")
        } finally { cinemaApplying = false }
        if (previous != index) scheduleWindowRefresh()
    }
    private fun showCinemaMenu(anchor: View) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (cinemaSlot != null) items += "退出影音布局，恢复原排列" to { leaveCinema() }
        slotCoordinator?.slotStates()?.forEachIndexed { index, state -> if (state is SlotState.Occupied) {
            items += "小窗 ${index + 1}：${state.component.packageName}" to { enterCinema(state.taskId) }
        } }
        for ((label, mode) in listOf("自动（可靠方向信号）" to StageCinemaPolicy.Mode.AUTO, "手动横屏影音" to StageCinemaPolicy.Mode.LANDSCAPE, "手动竖屏影音" to StageCinemaPolicy.Mode.PORTRAIT, "关闭影音布局" to StageCinemaPolicy.Mode.OFF)) {
            items += label to { leaveCinema(); core.cinemaMode = mode; cinemaPolicy.mode = mode; scheduleCinemaCheck() }
        }
        AtomicPopupMenu.show(context, anchor, items)
    }
    private fun moveWechat() {
        check(workspaceVisible && !suspended && !groupInputBlocked && !workgroups.isSwitching()) { "请先开启台前调度" }
        val task = checkNotNull(taskRepository.foregroundTask()) { "请先在主窗打开微信小程序" }
        checkNotNull(slotCoordinator).moveForegroundWechatToSlot(task.taskId).getOrThrow()
        showStageMessage("已移动实际微信任务；共用任务的小程序会与微信一起移动")
    }

    private fun changeStageLayout(top: Boolean, side: SidebarSide = sidebarSide) {
        smartRail.stop()
        check(!groupInputBlocked && !workgroups.isSwitching()) { "请等待工作组切换完成" }
        if (workspaceVisible) check(slotCoordinator?.isGroupRestoreReady() == true) { "请等待窗口切换完成" }
        // Preserve the established collapsed-rail expand/side callbacks. Atomic
        // layout selection applies to the visible four-card path, not its state machine.
        if (!top && sidebarCollapsed && side != sidebarSide) {
            if (viewport.isTopRail()) changeStageLayout(false)
            onSidebarSideRequested(side)
            return
        }
        cancelDragVisuals(); contentDrag.cancel(); windowPickerGeneration++; windowPicker.dismiss()
        leaveCinema()
        val previous = viewport.isTopRail()
        val previousSide=sidebarSide
        if(previous==top && previousSide==side)return
        runCatching {
            viewport.setSidebarSide(side);sidebarSide=side;topAppStrip?.setSidebarSide(side)
            viewport.setTopRail(top)
            if (workspaceVisible && !suspended) onViewportChanged()
        }.onFailure {
            viewport.setSidebarSide(previousSide);sidebarSide=previousSide;topAppStrip?.setSidebarSide(previousSide)
            viewport.setTopRail(previous)
            if (workspaceVisible && !suspended) onViewportChanged()
            throw it
        }
    }

    private fun showMainMenu() {
        syncCoreViewport()
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return
        val anchor = records.firstOrNull { it.title == "StageMainHandle" }?.view ?: return
        val current = geometry()
        val nativeTablet = io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy
            .wideLandscapeTablet(current.screenWidth, current.screenHeight)
        AtomicPopupMenu.show(context, anchor, listOf(
            "切换全屏（保留工作区）" to { onExitRequested() },
            "顶部横排" to { changeStageLayout(true) },
            (if (nativeTablet) "小窗排列在上方" else "左侧排列") to { changeStageLayout(false,SidebarSide.LEFT) },
            (if (nativeTablet) "小窗排列在下方" else "右侧排列") to { changeStageLayout(false,SidebarSide.RIGHT) },
            (if (core.enabled && core.expanded) "收起常用应用快捷栏" else "展开常用应用快捷栏") to { setQuickExpanded(!(core.enabled && core.expanded)) },
            (if (!core.mainPriority) "✓ 完整显示布局" else "完整显示布局") to { setMainPriority(false) },
            (if (core.mainPriority) "✓ 主窗口优先布局" else "主窗口优先布局") to { setMainPriority(true) },
            "添加小窗" to { chooseWindowApp(anchor,null) },
            "影音布局与自动模式" to { showCinemaMenu(anchor) },
            (if (smartRail.enabled) "关闭智能侧栏" else "开启智能侧栏（静置收起）") to {
                smartRail.setEnabled(!smartRail.enabled)
                showStageMessage(if (smartRail.enabled) "智能侧栏已开启：侧栏静置后收起，保留任务和主窗比例；顶部布局不受影响" else "已恢复原有侧栏行为")
            },
            (if (smartRail.pinned) "取消固定侧栏" else "固定侧栏") to { smartRail.setPinned(!smartRail.pinned) },
            "将当前微信任务移入小窗" to { runCatching { moveWechat() }.onFailure { showStageMessage(it.message ?: "微信任务未移动") } },
            "替换主应用" to { chooseWindowApp(anchor, null, main = true) },
            "关闭主窗（移回后台）" to { closeMainWindow() },
            "退出台前调度" to { onExitRequested() },
        ), avoidBounds = buildList {
            val g=geometry();add(Rect(g.contentLeft,g.contentTop,g.contentRight,g.contentBottom))
            slotWindows.forEach { slot -> val box=Rect();if(slot.view.getScreenVisibleRect(box))add(box) }
        })
    }

    private fun setMainPriority(priority: Boolean) {
        val before=currentGeometry()
        core.mainPriority=priority;syncCoreViewport();onViewportChanged()
        val after=currentGeometry()
        if(before.contentWidth==after.contentWidth && before.contentHeight==after.contentHeight)
            showStageMessage("当前屏幕空间已达完整显示上限，保持原比例")
    }

    private fun closeMainWindow() {
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return
        runCatching {
            val engine = checkNotNull(slotCoordinator)
            check(engine.isGroupRestoreReady()) { "窗口正在切换" }
            val task = checkNotNull(taskRepository.foregroundTask())
            check(task.component.packageName != TriggerBroadcastContract.resolveHomePackage(context) &&
                task.component.packageName != TriggerBroadcastContract.SYSTEM_UI_PACKAGE && task.windowingMode == 1 && !task.vendorWindowed) { "当前主窗不能关闭" }
            io.github.x647368maker.atomicworkbench.framework.WindowContainerTransactionCompat.parkInDefaultDisplay(
                checkNotNull(task.token), Rect(), task.densityDpi, task.windowingMode).getOrThrow()
            mainTaskPresenter.restoreForMove(task.taskId).getOrThrow()
            engine.onTasksChanged(); scheduleStageFocus()
        }.onFailure { showStageMessage(it.message ?: "主窗未关闭") }
    }

    private fun showWindowMenu(index: Int, anchor: View) {
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return
        val state = slotCoordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied ?: return
        AtomicPopupMenu.show(context, anchor, listOf(
            "横屏影音布局（保留原任务）" to { enterCinema(state.taskId, portrait = false) },
            "竖屏影音布局（保留原任务）" to { enterCinema(state.taskId, portrait = true) },
            "在小窗中操作" to { slotWindows[index].view.setDirectInteraction(true) },
            "切换为主窗口" to { slotCoordinator?.onSlotClicked(index) },
            "替换应用" to { chooseWindowApp(anchor, index, state.taskId) },
            "关闭小窗（移回后台）" to {
                val current = slotCoordinator?.slotStates()?.getOrNull(index) as? SlotState.Occupied
                if (current?.taskId == state.taskId) slotCoordinator?.closeWindow(index)?.onFailure {
                    android.widget.Toast.makeText(context, it.message ?: "关闭失败", android.widget.Toast.LENGTH_LONG).show()
                }
            },
        ))
    }

    private fun chooseWindowApp(anchor: View, target: Int?, expectedTask: Int? = null, main: Boolean = false) {
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return
        val generation = ++windowPickerGeneration
        val rows = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
        rows.addView(android.widget.ProgressBar(context), android.widget.LinearLayout.LayoutParams(-1, dp(48).toInt()))
        rows.addView(windowPicker.label("正在读取应用…"))
        windowPicker.onVisibilityChanged = { shown ->
            quickChromeSuppressed = shown; renderQuickChrome()
            records.filter { it.title == "AtomicSidebarHandle" || it.title == "StageMainHandle" }.forEach {
                it.view.visibility = if (!shown && workspaceVisible && !suspended) View.VISIBLE else View.GONE
            }
        }
        windowPicker.show(anchor, if (main) "替换主应用" else if (target == null) "添加小窗" else "替换应用", rows, fillContent = true)
        appCatalog.request { result ->
            if (generation != windowPickerGeneration || !windowPicker.isShowing || !workspaceVisible || suspended) return@request
            result.onSuccess { entries ->
                rows.removeAllViews()
                val query = android.widget.EditText(context).apply {
                    hint = "搜索应用"; textSize = 15f; setSingleLine(true)
                    setTextColor(windowPicker.textColor); setHintTextColor(Color.GRAY)
                    setPadding(dp(12).toInt(), 0, dp(12).toInt(), 0)
                }
                rows.addView(query, android.widget.LinearLayout.LayoutParams(-1, dp(48).toInt()))
                val grid = android.widget.GridView(context).apply {
                    numColumns = 4; stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
                    verticalSpacing = dp(6).toInt(); horizontalSpacing = dp(4).toInt()
                    clipToPadding = false; setPadding(0, dp(8).toInt(), 0, dp(16).toInt())
                }
                rows.addView(grid, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
                var filtered = entries
                val adapter = object : android.widget.BaseAdapter() {
                    override fun getCount() = filtered.size
                    override fun getItem(position: Int) = filtered[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convert: View?, parent: android.view.ViewGroup?): View {
                        val cell = (convert as? android.widget.LinearLayout) ?: android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER
                            setPadding(dp(4).toInt(), dp(8).toInt(), dp(4).toInt(), dp(4).toInt())
                            addView(android.widget.ImageView(context).apply {
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            }, android.widget.LinearLayout.LayoutParams(dp(44).toInt(), dp(44).toInt()))
                            addView(windowPicker.label("", 12f).apply {
                                gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                                setPadding(0, dp(6).toInt(), 0, 0)
                            }, android.widget.LinearLayout.LayoutParams(-1, dp(38).toInt()))
                        }
                        val entry = getItem(position)
                        (cell.getChildAt(0) as android.widget.ImageView).setImageDrawable(entry.icon)
                        (cell.getChildAt(1) as android.widget.TextView).text = entry.label
                        cell.contentDescription = entry.label
                        return cell
                    }
                }
                grid.adapter = adapter
                fun chooseEntry(entry: io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository.AppEntry) {
                    windowPicker.dismiss()
                    val engine = slotCoordinator ?: return
                    if (workgroups.isSwitching() || groupInputBlocked || !workspaceVisible || suspended) return
                    if (main) {
                        engine.openInMain(entry.launchIntent()).onFailure { showStageMessage(it.message ?: "主应用未打开") }
                    } else {
                        val current = target?.let { engine.slotStates().getOrNull(it) as? SlotState.Occupied }
                        if (target != null && current?.taskId != expectedTask) return
                        val result = if (target == null) addStageWindow(engine,entry.launchIntent()) else engine.replaceWindow(target,entry.launchIntent())
                        result.onFailure { showStageMessage(it.message ?: "应用未打开") }
                    }
                }
                grid.setOnItemClickListener { _, _, position, _ ->
                    filtered.getOrNull(position)?.let(::chooseEntry)
                }
                grid.setOnItemLongClickListener { _, cell, position, _ ->
                    filtered.getOrNull(position)?.let { entry -> quickSelected(entry, cell, true) }; true
                }
                val frequentTitle = windowPicker.label("最近常用", 13f).apply {
                    setPadding(dp(12).toInt(), dp(2).toInt(), dp(12).toInt(), 0)
                }
                val frequentRow = android.widget.LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    addView(android.widget.ProgressBar(context), android.widget.LinearLayout.LayoutParams(dp(22).toInt(), dp(22).toInt()))
                }
                // Fixed quick row before the recycled, alphabetic full catalogue.
                rows.addView(frequentTitle, 1, android.widget.LinearLayout.LayoutParams(-1, dp(28).toInt()))
                rows.addView(frequentRow, 2, android.widget.LinearLayout.LayoutParams(-1, dp(88).toInt()))
                var hasFrequent = false
                frequentApps.request(entries) quickRow@ { apps ->
                    if (generation != windowPickerGeneration || !windowPicker.isShowing) return@quickRow
                    frequentRow.removeAllViews()
                    apps.take(5).forEach { entry ->
                        val cell = android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER
                            contentDescription = entry.label; isClickable = true
                            setOnClickListener { chooseEntry(entry) }
                        }
                        cell.addView(android.widget.ImageView(context).apply {
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(entry.icon.constantState?.newDrawable(context.resources)?.mutate() ?: entry.icon)
                        }, android.widget.LinearLayout.LayoutParams(dp(38).toInt(), dp(38).toInt()))
                        cell.addView(windowPicker.label(entry.label, 11f).apply {
                            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(dp(2).toInt(), dp(5).toInt(), dp(2).toInt(), 0)
                        }, android.widget.LinearLayout.LayoutParams(-1, dp(34).toInt()))
                        frequentRow.addView(cell, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
                    }
                    hasFrequent = apps.isNotEmpty()
                    val show = hasFrequent && query.text.isNullOrBlank()
                    frequentTitle.visibility = if (show) View.VISIBLE else View.GONE
                    frequentRow.visibility = if (show) View.VISIBLE else View.GONE
                }
                query.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val filter = s?.toString().orEmpty().trim()
                        filtered = entries.filter { it.label.contains(filter, true) || it.component.packageName.contains(filter, true) }
                        adapter.notifyDataSetChanged()
                        val show = hasFrequent && filter.isEmpty()
                        frequentTitle.visibility = if (show) View.VISIBLE else View.GONE
                        frequentRow.visibility = if (show) View.VISIBLE else View.GONE
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }.onFailure {
                rows.removeAllViews(); rows.addView(windowPicker.label("应用列表读取失败，请关闭后重试"))
            }
        }
    }

    private fun sidebarHandle(): View = android.widget.TextView(context).apply {
        text = "‹›"
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(210, 28, 43, 53)); cornerRadius = dp(12)
        }
        contentDescription = "滑动收起或展开侧栏"
        isClickable = true
        setOnClickListener {
            if (smartRail.hidden) {
                if (SystemUiPanelMonitor.refresh(context)==StagePanelMenuGate.State.CLOSED) smartRail.interact()
            } else if (smartRail.enabled && !sidebarCollapsed && !geometry().railAtTop) smartRail.hideNow()
            else setSidebarCollapsed(!sidebarCollapsed)
        }
        var downX = 0f
        var downY = 0f
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
                android.view.MotionEvent.ACTION_UP -> {
                    val g = geometry()
                    val delta = if (g.railAtTop || g.isLandscape) event.rawY - downY else event.rawX - downX
                    val outward = if (g.railAtTop) false else if (g.isLandscape) !g.isSidebarAtTop else g.sidebarSide == SidebarSide.RIGHT
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
        smartRail.stop()
        cancelDragVisuals()
        sidebarCollapsed = value
        onViewportChanged()
        topAppStrip?.setStageStatus(workgroups.statusLabel(), "", value)
        records.firstOrNull { it.title == "NeXtepSidebar" }?.let { record ->
            val params = record.view.layoutParams as WindowManager.LayoutParams
            configureWindow(record, params, geometry())
            windowManager.updateViewLayout(record.view, params)
            // Retain TextureViews and tasks; a GONE sidebar would destroy their displays.
            record.view.animate().cancel()
            val base = viewport.geometry(sidebarSide)
            val distance = if (base.railAtTop || base.isLandscape) base.sidebarPhysicalHeight.toFloat() else base.sidebarPhysicalWidth.toFloat()
            val outward = if (base.railAtTop) false else if (base.isLandscape) !base.isSidebarAtTop else base.sidebarSide == SidebarSide.RIGHT
            val offset = if (value) distance * (if (outward) 1 else -1) else 0f
            record.view.animate().alpha(if (value) 0f else 1f)
                .translationX(if (base.railAtTop || base.isLandscape) 0f else offset)
                .translationY(if (base.railAtTop || base.isLandscape) offset else 0f)
                .setDuration(220).setInterpolator(transitionInterpolator).start()
        }
    }

    private var sidebarTouchRegionReady = false
    private fun sidebarPanel(geometry: WorkspaceGeometry): View = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> smartRail.touch(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> smartRail.touch(false)
            }
            return super.dispatchTouchEvent(event)
        }
        private var insetsListener: Any? = null
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            runCatching {
                val cls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
                val listener = java.lang.reflect.Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { proxy, method, arguments ->
                    when (method.name) {
                        "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                        "equals" -> return@newProxyInstance proxy === arguments?.firstOrNull()
                        "toString" -> return@newProxyInstance "StageSidebarTouchRegion"
                    }
                    if (method.name == "onComputeInternalInsets") runCatching {
                        val info = arguments?.firstOrNull() ?: return@newProxyInstance null
                        info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(info, 3)
                        val region = info.javaClass.getField("touchableRegion").get(info) as android.graphics.Region
                        region.setEmpty()
                        slotWindows.forEach { slot ->
                            if (slot.view.isWorkspaceCardVisible()) StageWindowTouchRegion.add(region,slot.view)
                        }
                        sidebarAddButton?.let { StageWindowTouchRegion.add(region,it) }
                        if (!sidebarTouchRegionReady) {
                            sidebarTouchRegionReady = true
                            backdropHandler.post { if (workspaceVisible && !suspended) onViewportChanged() }
                        }
                    }.onFailure {
                        if (sidebarTouchRegionReady) {
                            sidebarTouchRegionReady = false
                            backdropHandler.post { if (workspaceVisible && !suspended) onViewportChanged() }
                        }
                        NeXtepLog.warn("stage_sidebar", "Narrow input region failed; retaining original main fit", it)
                    }; null
                }
                viewTreeObserver.javaClass.getMethod("addOnComputeInternalInsetsListener", cls).invoke(viewTreeObserver, listener)
                insetsListener = listener
                sidebarTouchRegionReady = false
                backdropHandler.post { if (workspaceVisible && !suspended) onViewportChanged() }
                scheduleWindowRefresh()
            }.onFailure { NeXtepLog.warn("stage_sidebar", "Narrow input region unavailable; keep safe main margin", it) }
        }
        override fun onDetachedFromWindow() {
            insetsListener?.let { listener -> runCatching {
                val cls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
                if (viewTreeObserver.isAlive) viewTreeObserver.javaClass.getMethod("removeOnComputeInternalInsetsListener", cls).invoke(viewTreeObserver, listener)
            } }
            insetsListener = null; sidebarTouchRegionReady = false
            super.onDetachedFromWindow()
        }
    }.apply {
        sidebarView = this
        setOnDragListener(contentDrag)
        setBackgroundColor(Color.TRANSPARENT)
        if (FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled) {
            slotWindows = List(WORKSPACE_SLOT_COUNT) { index -> VirtualDisplaySlot(context, index) }
            slotWindows.forEachIndexed { index, slot ->
                slot.view.setWindowMenuAction(::showWindowMenu)
                slot.view.setBackgroundSwipeEnabled(slotBackgroundSwipeEnabled)
                addView(slot.view)
            }
            sidebarAddButton = android.widget.TextView(context).apply {
                text = "+"; textSize = 26f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); contentDescription = "添加小窗，最多四个"
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(210, 30, 30, 30)); cornerRadius = dp(22) }
                elevation = dp(30)
                setOnClickListener { chooseWindowApp(this, null) }
            }.also { addView(it, FrameLayout.LayoutParams(dp(44).toInt(), dp(44).toInt(), Gravity.CENTER)) }
            applySlotLayout(this, geometry)
            slotCoordinator = SlotTaskCoordinator(
                context = context,
                slots = slotWindows,
                mainTaskPresenter = mainTaskPresenter,
                geometryProvider = ::geometry,
                onSwapAnimation = ::animateDragSwap,
                onMainSlotAnimation = ::animateMainSlotSwap,
                onPhasedMainSlotAnimation = { index, move, canRelease, finished ->
                    animateSwap(index, null, null, { true }, finished, move, canRelease)
                },
                onInternalSlotDrag = { source, drag ->
                    internalDrag.start(source, WorkspaceInternalDragController.Payload.Slot(drag))
                },
                onInternalDragTouch = internalDrag::touch,
                onSlotStatesChanged = ::scheduleWindowRefresh,
            )
        }
    }

    private fun cancelDragVisuals() {
        internalDrag.cancel()
        slotWindows.forEach { it.view.cancelBackgroundSwipe() }
        slotCoordinator?.onSlotDragEnded()
        slotCoordinator?.cancelPendingExchange()
        swapCapture.cancel()
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
                is WorkspaceInternalDragController.Payload.Slot -> {
                    val drag = payload.drag
                    val source = coordinator.slotStates().getOrNull(drag.slotIndex) as? SlotState.Occupied
                    val destination = coordinator.slotStates().getOrNull(target.index) as? SlotState.Occupied
                    if (source?.taskId == drag.taskId && source.displayId == drag.displayId && destination != null &&
                        workspaceVisible && !suspended && !groupInputBlocked && !workgroups.isSwitching() && coordinator.isGroupRestoreReady()) {
                        stageOrder = StageVisualOrder.swap(stageOrder, drag.slotIndex, target.index)
                        applyStageStack(geometry())
                        NeXtepLog.info("task_continuity", "visual_reorder tasks=${source.taskId},${destination.taskId} display_migrations=0 launch_requests=0")
                        true
                    } else coordinator.onSlotDropped(target.index, drag)
                }
                is WorkspaceInternalDragController.Payload.App -> coordinator.onIntentDropped(target.index, payload.intent)
            }
            WorkspaceInternalDragController.Target.Main ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dropSlotIntoMain(payload.drag)
            WorkspaceInternalDragController.Target.Background ->
                payload is WorkspaceInternalDragController.Payload.Slot && coordinator.dismissDraggedSlot(payload.drag)
        }
    }

    fun isExchangePending(): Boolean = slotCoordinator?.isExchangePending() == true

    fun deferExchangeExit(action: () -> Unit): Boolean =
        slotCoordinator?.deferUntilExchangeSettled(action) == true

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
        sourceIndex: Int, targetIndex: Int?, drag: SlotTaskDrag?,
        move: () -> Boolean, onFinished: () -> Unit,
        asyncMove: (((Boolean) -> Unit) -> Unit)? = null,
        canRelease: () -> Boolean = { true },
    ) {
        val task=if(targetIndex==null)mainTaskPresenter.currentTaskId() else null
        if(task==null || !workspaceVisible || suspended || !WorkspaceWallpaperBackdrop.canCapture(context)) {
            animateSwapPrepared(sourceIndex,targetIndex,drag,move,onFinished,null,asyncMove,canRelease);return
        }
        val capturedGeometry=geometry()
        val states=slotCoordinator?.slotStates().orEmpty().toList()
        swapCapture.request(
            load={TaskSurfaceCompat.capturePreview(task,Rect(0,0,capturedGeometry.screenWidth,capturedGeometry.screenHeight))},
            valid={workspaceVisible && !suspended && geometry()==capturedGeometry && mainTaskPresenter.currentTaskId()==task && slotCoordinator?.slotStates().orEmpty()==states},
            accept={animateSwapPrepared(sourceIndex,targetIndex,drag,move,onFinished,it,asyncMove,canRelease)},
            onCancel=onFinished,
        )
    }

    private fun animateSwapPrepared(
        sourceIndex: Int,
        targetIndex: Int?,
        drag: SlotTaskDrag?,
        move: () -> Boolean,
        onFinished: () -> Unit,
        capturedMainPreview: Bitmap?,
        asyncMove: (((Boolean) -> Unit) -> Unit)? = null,
        canRelease: () -> Boolean = { true },
    ) {
        fun withoutPreview() {
            if(capturedMainPreview?.isRecycled==false)capturedMainPreview.recycle()
            if (asyncMove != null) asyncMove { onFinished(); scheduleStageFocus() }
            else try { move() } finally { onFinished(); scheduleStageFocus() }
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
        val mainPreview = capturedMainPreview
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
                moveAsync = asyncMove?.let { startMove ->
                    { done: (Boolean) -> Unit ->
                        startMove { success ->
                            sourceFrame = sourceSlot.contentFrameSequence
                            targetFrame = targetSlot?.contentFrameSequence ?: 0L
                            done(success)
                        }
                    }
                },
                canRelease = canRelease,
                // Tap swaps should change task ownership, never animate the
                // physical outline or distort the screenshot as it crosses sizes.
                freezeGeometry = drag == null,
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
                    } else incomingTaskId!=null && mainTaskPresenter.currentTaskId()==incomingTaskId &&
                        TaskSurfaceCompat.isPresentationSettled(incomingTaskId,geometry)
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
        // applyStageStack owns the single state/profile/bounds snapshot.
        applyStageStack(geometry)
    }

    private fun applyStageStack(geometry: WorkspaceGeometry) {
        if (slotWindows.isEmpty()) return
        syncCoreViewport()
        val selectedProfile = viewport.layout().let { selected ->
            // The no-task compact rail is ONLY a parent/add affordance. Hidden
            // producer hosts remain 1x1 until an occupied state chooses real bounds.
            if (viewport.activeCount > 0 && !sidebarCollapsed) selected.copy(geometry = geometry) else selected
        }
        val right = logicalSidebarSide(geometry) == SidebarSide.RIGHT
        val states = slotCoordinator?.slotStates().orEmpty()
        val occupied = List(WORKSPACE_SLOT_COUNT) { states.getOrNull(it) is SlotState.Occupied }
        val profile = selectedProfile
        val bounds = io.github.x647368maker.atomicworkbench.workspace.StageLiveCardLayout.bounds(
            profile, slotWindows.map { it.geometry() }, occupied, stageOrder)
        slotWindows.forEachIndexed { index, slot ->
            slot.setLayoutProfile(profile)
            val box = if (occupied[index]) bounds[index] else io.github.x647368maker.atomicworkbench.workspace.WorkspaceLayoutProfile.Slot(1, 1, 0, 0)
            if (cinemaSlot == null) {
                val x = if (!occupied[index]) 0 else if (geometry.railAtTop || geometry.isNativeLandscapeRail) box.left else (profile.geometry.sidebarWidth - box.width) / 2
                val old = slot.view.layoutParams as? FrameLayout.LayoutParams
                if (old == null || old.width != box.width || old.height != box.height || old.marginStart != x || old.topMargin != box.top) {
                    slot.view.layoutParams = FrameLayout.LayoutParams(box.width, box.height, Gravity.TOP).apply { marginStart = x; topMargin = box.top }
                }
            }
        }
        // Bounds already include compact per-producer spacing. Do not apply a second
        // fit/scale to the requested canvas or reserve invisible four-slot capacity.
        val poses = stageOrder.filter { occupied[it] }.associateWith {
            StageStackPolicy.Pose(1f, 0f, 0f, 0f, dp(if (geometry.railAtTop) 4 else 2), 1f)
        }
        val countChanged = shownWindowCount != poses.size
        shownWindowCount = poses.size
        sidebarAddButton?.apply {
            visibility = if (shownWindowCount < WORKSPACE_SLOT_COUNT) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(44).toInt(), dp(44).toInt(),
                if (geometry.railAtTop || geometry.isNativeLandscapeRail) Gravity.END or Gravity.BOTTOM else if (shownWindowCount == 0) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = if (geometry.railAtTop || geometry.isNativeLandscapeRail) dp(4).toInt() else dp(12).toInt(); marginEnd = if (geometry.railAtTop || geometry.isNativeLandscapeRail) dp(10).toInt() else 0 }
        }
        slotWindows.forEachIndexed { index, slot ->
            val media = cinemaSlot
            if (media != null && !sidebarCollapsed) {
                if (!slot.setCinema(index == media)) backdropHandler.post { leaveCinema(); showStageMessage("应用横屏显示不可用，已恢复普通布局") }
                slot.view.setWorkspaceCardVisible(index == media && occupied[index])
                if (index == media && occupied[index]) {
                    val w = (profile.geometry.sidebarPhysicalWidth - dp(if (cinemaPortrait) 8 else 16)).toInt().coerceAtLeast(1)
                    val h = minOf((w * (if (cinemaPortrait) 16f / 9 else 9f / 16)).toInt(),
                        (profile.geometry.sidebarPhysicalHeight - dp(64)).toInt()).coerceAtLeast(1)
                    val old = slot.view.layoutParams as? FrameLayout.LayoutParams
                    if (old == null || old.width != w || old.height != h || old.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL) ||
                        old.topMargin != (if (cinemaPortrait) ((profile.geometry.sidebarPhysicalHeight - h - dp(56)) / 2).toInt().coerceAtLeast(0) else dp(8).toInt()))
                        slot.view.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = if (cinemaPortrait) ((profile.geometry.sidebarPhysicalHeight - h - dp(56)) / 2).toInt().coerceAtLeast(0) else dp(8).toInt() }
                    slot.view.apply { rotationY = 0f; scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f; alpha = 1f; elevation = dp(8) }
                    slot.view.setDirectInteraction(true)
                }
                return@forEachIndexed
            }
            slot.setCinema(false)
            val pose = poses[index]
            slot.view.setWorkspaceCardVisible(pose != null)
            if (pose == null) {
                slot.view.apply { translationX=0f;translationY=0f;scaleX=1f;scaleY=1f;rotationY=0f;elevation=0f }
                return@forEachIndexed
            }
            slot.view.apply {
                pivotX = if (geometry.railAtTop) bounds[index].width / 2f else if (right) 0f else bounds[index].width.toFloat()
                pivotY = bounds[index].height / 2f
                cameraDistance = dp(900)
                rotationY = pose.tilt
                scaleX = pose.scale
                scaleY = pose.scale
                translationX = pose.x
                translationY = pose.y
                elevation = pose.z
                alpha = pose.alpha
                // The live app fills the card. Its outline owns all four rounded corners.
                setBackgroundColor(Color.TRANSPARENT)
                invalidateOutline()
                clipToOutline = true
            }
        }
        sidebarView?.requestLayout()
        if (countChanged && workspaceVisible && !suspended) onViewportChanged()
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
                main.userId?.let { frequentApps.observe(main.component.packageName, it) }
                val oldMain = stageMainId
                val returnedSlot = slotCoordinator?.slotStates()?.indexOfFirst {
                    it is SlotState.Occupied && it.taskId == oldMain
                } ?: -1
                if (returnedSlot >= 0) {
                    // User visual order is stable; focus changes do not reorder tasks.
                    applyStageStack(geometry())
                }
                stageMainId = main.taskId
                smartRail.interact()
            }
            val label = runCatching {
                val info = context.packageManager.getApplicationInfo(main.component.packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(main.component.packageName)
            topAppStrip?.setStageStatus(workgroups.statusLabel(), label, sidebarCollapsed)
            stageFocusBadge?.apply {
                text = label
                val g = geometry()
                visibility = View.GONE
            }
            // Focus is the centered real task. Avoid a debug-looking cyan frame.
            contentHighlightView?.foreground = null
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
                // A capture may finish after a window closes/collapses. Crop for the
                // current layout, not the geometry that started this asynchronous job.
                applyWallpaperBackdrop(this@NeXtepWindowController.geometry())
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
        val background = WorkspaceWallpaperBackdrop.crop(wallpaperBitmap, panel.sidebarLeft, panel.sidebarTop,
            frostStrength, wallpaperRotation, panel.displayRotation)
        val visibleBackground = if (geometry.mainTopOverlap > 0 && geometry.railAtTop)
            MainFooterBackdrop(background, Rect(geometry.contentLeft - geometry.sidebarLeft,
                geometry.contentTop - geometry.sidebarTop, geometry.contentRight - geometry.sidebarLeft,
                geometry.sidebarPhysicalHeight)) else background
        (records.firstOrNull { it.title == "NeXtepSidebar" }?.view as? WorkspacePanelHost)?.setPanelBackground(visibleBackground)
    }

    private fun logicalSidebarSide(geometry: WorkspaceGeometry): SidebarSide =
        geometry.panelSidebarSide

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density

    private fun geometry(): WorkspaceGeometry = currentGeometry()


    private val transitionInterpolator = WorkspaceTransitionSpec.interpolator

    private companion object {
        const val EXPECTED_WINDOW_COUNT = 5
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
