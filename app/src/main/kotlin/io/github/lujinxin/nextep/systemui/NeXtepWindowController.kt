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
        val command = intent.getStringExtra(io.github.x647368maker.atomicworkbench.trigger.StageControlContract.COMMAND)
        when (command) {
            "query" -> Unit
            "cinema_auto", "cinema_manual", "cinema_off" -> {
                leaveCinema(); core.cinemaMode = when(command) { "cinema_auto" -> StageCinemaPolicy.Mode.AUTO; "cinema_manual" -> StageCinemaPolicy.Mode.MANUAL; else -> StageCinemaPolicy.Mode.OFF }
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
                    val index = if (command == "add_window") engine.slotStates().indexOfFirst { it is SlotState.Ready } else intent.getIntExtra("slot", -1)
                    check(index >= 0) { "最多四个小窗" }
                    engine.replaceWindow(index, launch).getOrThrow()
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
                else { sidebarSide = side; SystemServerWorkspaceBridge.publish(context, false, geometry()) }
            }
            else -> error("未知设置操作")
        }
        android.os.Bundle().apply {
            putBoolean("ok", true)
            putBoolean("layout_top", viewport.isTopRail())
            putString("cinema_mode", core.cinemaMode.name)
            putBoolean("quick_fixed", core.fixed); putBoolean("quick_expanded", core.enabled && core.expanded)
            putBoolean("adaptive", core.adaptive)
            putString("groups", workgroups.settingsSnapshot())
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
            // Retain only a transparent status-safe wallpaper host. No controls,
            // application strip, branding or touch interception above the stage.
            view = FrameLayout(context).apply {
                quickBar = StageQuickBarView(context, ::quickSelected,
                    { chooseWindowApp(it, null, main = true) }, { setQuickExpanded(false) })
                addView(quickBar, FrameLayout.LayoutParams(-1, dp(52).toInt(), Gravity.BOTTOM).apply {
                    leftMargin = dp(8).toInt(); rightMargin = dp(8).toInt(); bottomMargin = dp(4).toInt()
                })
                quickBar?.visibility = if (core.enabled && core.expanded) View.VISIBLE else View.GONE
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
            width = dp(64).toInt(), height = dp(24).toInt(), gravity = Gravity.TOP or Gravity.LEFT,
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
            (record.title == "NeXtepTopBar" && core.enabled && core.expanded && !geometry.isLandscape) ||
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
        if (record.title == "NeXtepTopBar") quickBar?.visibility = if (core.enabled && core.expanded && !geometry.isLandscape) View.VISIBLE else View.GONE
        if (record.title == "AtomicSidebarHandle") (record.view as? android.widget.TextView)?.apply {
            text = if (!sidebarCollapsed) "‹›" else if (geometry.railAtTop || geometry.isLandscape)
                (if (geometry.railAtTop || geometry.isSidebarAtTop) "⌄ 展开小窗" else "⌃ 展开小窗")
                else if (geometry.sidebarSide == SidebarSide.LEFT) "›\n展开" else "‹\n展开"
            textSize = if (sidebarCollapsed) 13f else 14f
            contentDescription = if (sidebarCollapsed) "展开小窗" else "收起小窗"
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
        val geometry = if ((title == "NeXtepSidebar" && (sidebarCollapsed || shownWindowCount > 0)) ||
            (title == "AtomicSidebarHandle" && !sidebarCollapsed && shownWindowCount > 0))
            viewport.geometry(sidebarSide).rotatedTo(requested.displayRotation) else requested
        when (title) {
            "StageMainHandle" -> {
                params.width = minOf(dp(64).toInt(), geometry.contentWidth)
                params.height = dp(24).toInt()
                params.x = geometry.contentLeft + (geometry.contentWidth - params.width) / 2
                params.y = if (geometry.railAtTop && !sidebarCollapsed) {
                    val actions …49061 tokens truncated…ot exchanged",
                )
            } else {
                // Promotion intentionally emptied the slot. An old migration
                // recovery would otherwise relaunch this live task back into it,
                // leaving the visible '+' blocked by pendingComponent.
                clearSlotAssignment(slotIndex)
                setState(slotIndex, SlotState.Ready(occupied.displayId), "slot promoted to main")
            }
            val presentation = mainTaskPresenter.presentTask(slotTask.taskId, geometryProvider())
            if (presentation.isFailure && taskRepository.findTask(slotTask.taskId) != null) {
                presentation.getOrThrow()
            }
            schedulePromotedTaskRecovery()
        } catch (error: Throwable) {
            clearPromotedTaskRecovery()
            val rollbackErrors = mutableListOf<Throwable>()
            if (slotMovedToMain) {
                mainTaskPresenter.restoreForMove(slotTask.taskId)
                    .onFailure(rollbackErrors::add)
                runCatching {
                    moveTaskToDisplayAndWait(slotTask.taskId, occupied.displayId)
                    record.originalLayout = slotOriginalLayout
                    ensureSlotLayout(slotIndex, slotTask, slotOriginalLayout)
                }.onFailure(rollbackErrors::add)
            }
            val rollbackMainTask = restoredMainTask
            if (mainMovedToSlot && mainTask != null && rollbackMainTask != null) {
                runCatching {
                    moveTaskToDisplayAndWait(
                        mainTask.taskId,
                        Display.DEFAULT_DISPLAY,
                    )
                    restoreTaskLayout(
                        rollbackMainTask,
                        checkNotNull(mainOriginalLayout),
                    )
                }.onFailure(rollbackErrors::add)
            }
            if (mainTask != null && rollbackMainTask != null) {
                mainTaskPresenter.presentTask(mainTask.taskId, geometryProvider())
                    .onFailure(rollbackErrors::add)
            }
            record.retainedTaskId = occupied.taskId
            record.originalLayout = slotOriginalLayout
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
            if (rollbackErrors.isEmpty()) {
                setState(slotIndex, occupied, "swap rolled back")
            } else {
                slotRecords[slotIndex].retainedTaskId = null
                slotRecords[slotIndex].originalLayout = null
                setState(slotIndex, SlotState.Failed("rollback failed"), "swap rollback incomplete")
                rollbackErrors.forEach(error::addSuppressed)
            }
            throw error
        }
    }

    private fun runTransition(
        label: String,
        animation: Pair<SlotTaskDrag, Int?>? = null,
        mainSlotIndex: Int? = null,
        operation: () -> Unit,
    ): Result<Unit> {
        if (transitionInProgress || recentSelectionPending) {
            NeXtepLog.warn("slot_coordinator", "Ignored concurrent transition $label")
            return Result.failure(IllegalStateException("Another slot transition is settling"))
        }
        val animated = animation != null || mainSlotIndex != null
        val expectedSlotState = mainSlotIndex?.let { slotRecords[it].state }
        val expectedMainTaskId = if (mainSlotIndex != null) taskRepository.foregroundTask()?.taskId else null
        val token = ++transitionGeneration
        var movePerformed = false
        var moveResult: Result<Unit>? = null
        transitionInProgress = true
        refreshPolicy.onTaskChanged(SystemClock.uptimeMillis())
        pendingSelection = null
        mainSelectionGeneration += 1
        clearPromotedTaskRecovery()
        slots.forEach { it.view.setBusy(true) }
        NeXtepLog.info("slot_coordinator", "Transition started $label")
        fun performMove(): Result<Unit> {
            if (token != transitionGeneration) {
                return Result.failure(IllegalStateException("Workspace transition was cancelled"))
            }
            if (movePerformed) {
                return moveResult ?: Result.failure(IllegalStateException("Workspace move is already running"))
            }
            movePerformed = true
            val result = runCatching {
                check(active && !suspended) { "Workspace is no longer interactive" }
                if (mainSlotIndex != null) {
                    check(slotRecords[mainSlotIndex].state == expectedSlotState &&
                        taskRepository.foregroundTask()?.taskId == expectedMainTaskId
                    ) { "Main task or slot changed during the exchange preview" }
                }
                operation()
            }.onFailure { error ->
                NeXtepLog.error("slot_coordinator", "Transition failed $label", error)
                if (animated) showMessage(AppLanguage.string(applicationContext, R.string.window_swap_incomplete))
            }
            moveResult = result
            NeXtepLog.info("slot_coordinator", "Transition move completed $label")
            return result
        }
        fun finishTransition() {
            if (token != transitionGeneration || !transitionInProgress) return
            visualTransitionPending = false
            transitionInProgress = false
            slots.forEach { it.view.setBusy(suspended || recentSelectionPending) }
            // Taps keep their task identity while the preview is visible. Start
            // the last selection only after the previous overlay has gone away.
            dispatchPendingSelection()
            if (movePerformed) {
                handler.postDelayed({
                    if (active && token == transitionGeneration) {
                        reconcile("settled $label")
                        NeXtepLog.info("slot_coordinator", "Transition settled $label")
                    }
                }, SETTLE_DELAY_MS)
            }
        }
        if (!animated) {
            val result = performMove()
            finishTransition()
            return result
        }
        visualTransitionPending = true
        val move = {
            if (visualTransitionPending && token == transitionGeneration) performMove().isSuccess else false
        }
        return runCatching {
            if (animation != null) {
                onSwapAnimation(animation.first, animation.second, move, ::finishTransition)
            } else {
                onMainSlotAnimation(checkNotNull(mainSlotIndex), move, ::finishTransition)
            }
        }.fold(
            onSuccess = { moveResult ?: Result.success(Unit) },
            onFailure = { error ->
                NeXtepLog.error("slot_coordinator", "Unable to start exchange preview $label", error)
                finishTransition()
                Result.failure(error)
            },
        )
    }

    private fun dispatchPendingSelection() {
        handler.post {
            if (!active || suspended || transitionInProgress || recentSelectionPending) return@post
            val selected = pendingSelection ?: return@post
            pendingSelection = null
            selected()
        }
    }

    private fun reconcile(reason: String) {
        ensureMainThread()
        if (!active || suspended || transitionInProgress) return
        val tasks = taskRepository.runningTasks()
        val assignedTaskIds = mutableSetOf<Int>()
        var mainReconciled = false

        slots.forEachIndexed { index, slot ->
            if (slot.isSuspended()) {
                runCatching { slot.setSuspended(false) }.onFailure {
                    NeXtepLog.warn("virtual_display", "Output reattachment pending slot=$index", it)
                }
            }
            val displayId = slot.displayId() ?: run {
                setState(index, SlotState.Empty, "$reason: no display")
                return@forEachIndexed
            }
            val displayTasks = tasks.filter { it.displayId == displayId }
            val displayTask = displayTasks.firstOrNull()
            if (displayTasks.size > 1) {
                NeXtepLog.warn(
                    "slot_state",
                    "slot=$index displayId=$displayId stackedTasks=${displayTasks.map { it.taskId }}",
                )
            }
            val retainedTask = slotRecords[index].retainedTaskId
                ?.let { taskId -> tasks.firstOrNull { it.taskId == taskId } }
            val record = slotRecords[index]
            val recoveringSameComponent = record.migrationRecoveryComponent
                ?.packageName == displayTask?.component?.packageName &&
                SystemClock.uptimeMillis() <= record.migrationRecoveryDeadline

            when {
                displayTask != null && assignedTaskIds.add(displayTask.taskId) -> {
                    slotRecords[index].mismatchSince = 0L
                    if (record.retainedTaskId != null &&
                        record.retainedTaskId != displayTask.taskId &&
                        !recoveringSameComponent
                    ) {
                        record.originalLayout = null
                    }
                    try {
                        ensureSlotLayout(index, displayTask)
                    } catch (error: Throwable) {
                        setState(
                            index,
                            SlotState.Failed("layout rejected"),
                            "$reason: ${error.message}",
                        )
                        return@forEachIndexed
                    }
                    record.retainedTaskId = displayTask.taskId
                    record.pendingComponent = null
                    record.pendingAttempts = 0
                    setState(
                        index,
                        SlotState.Occupied(displayId, displayTask.taskId, displayTask.component),
                        "$reason: adopted display task",
                    )
                }
                displayTask != null -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    setState(index, SlotState.Failed("duplicate task"), "$reason: duplicate record")
                }
                retainedTask != null && !assignedTaskIds.add(retainedTask.taskId) -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    setState(index, SlotState.Failed("duplicate task"), "$reason: retained duplicate")
                }
                retainedTask != null -> {
                    val foregroundTaskId = tasks.firstOrNull {
                        it.displayId == Display.DEFAULT_DISPLAY
                    }?.taskId
                    if (retainedTask.displayId == Display.DEFAULT_DISPLAY &&
                        retainedTask.taskId == foregroundTaskId
                    ) {
                        val originalLayout = slotRecords[index].originalLayout
                            ?: defaultMainLayout(retainedTask.resizeMode)
                        runCatching {
                            restoreExternalFullscreenLayout(retainedTask, originalLayout)
                            clearSlotAssignment(index)
                            setState(
                                index,
                                SlotState.Ready(displayId),
                                "$reason: task externally promoted to main",
                            )
                            mainTaskPresenter.reconcileForeground(geometryProvider()).getOrThrow()
                            mainReconciled = true
                        }.onFailure { error ->
                            setState(
                                index,
                                SlotState.Failed("main promotion failed"),
                                "$reason: ${error.message}",
                            )
                        }
                        return@forEachIndexed
                    }
                    val now = SystemClock.uptimeMillis()
                    if (record.mismatchSince == 0L) record.mismatchSince = now
                    if (now - record.mismatchSince >= SLOT_MISMATCH_GRACE_MS) {
                        assignedTaskIds -= retainedTask.taskId
                        clearSlotAssignment(index)
                        setState(
                            index,
                            SlotState.Ready(displayId),
                            "$reason: stale slot ownership cleared",
                        )
                    }
                }
                shouldRecoverMigratedTask(record) -> {
                    recoverMigratedTask(index, record)
                }
                slotRecords[index].pendingComponent != null &&
                    slotRecords[index].pendingAttempts < MAX_PENDING_POLLS -> {
                    slotRecords[index].pendingAttempts += 1
                    slot.view.showWaiting()
                }
                else -> {
                    slotRecords[index].retainedTaskId = null
                    slotRecords[index].originalLayout = null
                    val launchTimedOut = slotRecords[index].pendingComponent != null
                    slotRecords[index].pendingComponent = null
                    slotRecords[index].pendingAttempts = 0
                    setState(
                        index,
                        if (launchTimedOut) SlotState.Failed("launch timed out")
                        else SlotState.Ready(displayId),
                        "$reason: empty",
                    )
                }
            }
        }
        // The steady pass already owns a complete snapshot. Only the branch
        // above that changes a main task's native layout needs a fresh query.
        if (!mainReconciled) mainTaskPresenter.reconcileForeground(geometryProvider(), tasks)
            .onFailure { error ->
                NeXtepLog.warn(
                    "main_task_presenter",
                    "Foreground reconciliation failed reason=$reason",
                    error,
                )
            }
    }

    private fun shouldRecoverMigratedTask(record: SlotRecord): Boolean =
        record.migrationRecoveryComponent != null &&
            record.migrationRecoveryAttempts < MAX_MIGRATION_RECOVERY_ATTEMPTS &&
            SystemClock.uptimeMillis() <= record.migrationRecoveryDeadline

    private fun schedulePromotedTaskRecovery() {
        postPromotedRecovery(PROMOTED_RECOVERY_DELAY_MS)
    }

    private fun postPromotedRecovery(delayMillis: Long) {
        val generation = promotedRecoveryGeneration
        handler.postDelayed({
            if (generation == promotedRecoveryGeneration) recoverPromotedTaskIfNeeded()
        }, delayMillis)
    }

    private fun recoverPromotedTaskIfNeeded() {
        val component = promotedRecoveryComponent ?: return
        if (!active || SystemClock.uptimeMillis() > promotedRecoveryDeadline) {
            clearPromotedTaskRecovery()
            return
        }
        val promotedTask = findLaunchTask(component)
            ?.takeIf { task -> task.displayId == Display.DEFAULT_DISPLAY }
        if (promotedTask != null) {
            if (taskRepository.foregroundTask()?.taskId != promotedTask.taskId) {
                clearPromotedTaskRecovery()
                return
            }
            mainTaskPresenter.presentTask(promotedTask.taskId, geometryProvider())
                .onSuccess {
                    NeXtepLog.info(
                        "slot_coordinator",
                        "Promoted task settled taskId=${promotedTask.taskId} component=$component",
                    )
                    clearPromotedTaskRecovery()
                }
                .onFailure { error ->
                    NeXtepLog.warn(
                        "slot_coordinator",
                        "Promoted task presentation still settling taskId=${promotedTask.taskId}",
                        error,
                    )
                    postPromotedRecovery(PROMOTED_RECOVERY_POLL_MS)
                }
            return
        }
        if (promotedRecoveryAttempts >= MAX_MIGRATION_RECOVERY_ATTEMPTS) {
            NeXtepLog.warn(
                "slot_coordinator",
                "Promoted task recovery exhausted component=$component",
            )
            clearPromotedTaskRecovery()
            return
        }
        val intent = normalizeLaunchIntent(Intent().setComponent(component))
        if (intent == null) {
            NeXtepLog.warn(
                "slot_coordinator",
                "Promoted task recovery has no launcher Activity component=$component",
            )
            clearPromotedTaskRecovery()
            return
        }
        promotedRecoveryAttempts += 1
        runCatching { applicationContext.startActivity(intent) }
            .onSuccess {
                NeXtepLog.warn(
                    "slot_coordinator",
                    "Relaunched promoted task after process death component=$component",
                )
                postPromotedRecovery(PROMOTED_RECOVERY_POLL_MS)
            }
            .onFailure { error ->
                NeXtepLog.error(
                    "slot_coordinator",
                    "Promoted task recovery launch failed component=$component",
                    error,
                )
                clearPromotedTaskRecovery()
            }
    }

    private fun clearPromotedTaskRecovery() {
        promotedRecoveryGeneration += 1
        promotedRecoveryComponent = null
        promotedRecoveryDeadline = 0L
        promotedRecoveryAttempts = 0
    }

    private fun recoverMigratedTask(slotIndex: Int, record: SlotRecord) {
        val component = checkNotNull(record.migrationRecoveryComponent)
        val intent = normalizeLaunchIntent(Intent().setComponent(component))
        if (intent == null) {
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            setState(
                slotIndex,
                SlotState.Failed("migration recovery unavailable"),
                "No launcher Activity for $component",
            )
            return
        }
        record.migrationRecoveryAttempts += 1
        record.retainedTaskId = null
        record.pendingComponent = component
        record.pendingAttempts = 0
        slots[slotIndex].view.showWaiting()
        slots[slotIndex].launch(intent)
            .onSuccess {
                NeXtepLog.warn(
                    "slot_coordinator",
                    "Relaunched migrated task after process death slot=$slotIndex " +
                        "component=$component attempt=${record.migrationRecoveryAttempts}",
                )
            }
            .onFailure { error ->
                record.pendingComponent = null
                NeXtepLog.error(
                    "slot_coordinator",
                    "Migration recovery launch failed slot=$slotIndex component=$component",
                    error,
                )
            }
    }

    private fun reconcileForeground(reason: String) {
        val tasks = taskRepository.runningTasks()
        val foreground = tasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } ?: return
        val promotedSlot = slotRecords.indexOfFirst { record ->
            record.retainedTaskId == foreground.taskId &&
                foreground.displayId == Display.DEFAULT_DISPLAY
        }
        if (promotedSlot >= 0) {
            val record = slotRecords[promotedSlot]
            val originalLayout = record.originalLayout
                ?: defaultMainLayout(foreground.resizeMode)
            runCatching {
                restoreExternalFullscreenLayout(foreground, originalLayout)
                clearSlotAssignment(promotedSlot)
                val displayId = slots[promotedSlot].displayId()
                setState(
                    promotedSlot,
                    displayId?.let(SlotState::Ready) ?: SlotState.Empty,
                    "$reason: foreground task left slot",
                )
            }.onFailure { error ->
                NeXtepLog.warn(
                    "slot_state",
                    "Unable to adopt externally promoted taskId=${foreground.taskId}",
                    error,
                )
                return
            }
        }
        // Reuse this pass's snapshot. A fullscreen promotion restores layout, so that path
        // needs a fresh query before the presenter reads windowing mode/bounds.
        val presentationTasks = if (promotedSlot >= 0) taskRepository.runningTasks() else tasks
        mainTaskPresenter.reconcileForeground(geometryProvider(), presentationTasks)
            .onFailure { error ->
                NeXtepLog.warn(
                    "main_task_presenter",
                    "Fast foreground reconciliation failed reason=$reason",
                    error,
                )
            }
    }

    private fun findLaunchTask(
        component: ComponentName,
        targetUser: android.os.UserHandle? = null,
    ): TaskRepository.TaskSnapshot? = taskRepository.findTaskForComponent(
        component,
        targetUser,
        // The icon entry is a separate, no-display task; never promote it as settings.
        allowPackageFallback = component != settingsComponent,
    )

    private fun normalizeLaunchIntent(source: Intent): Intent? {
        val requestedComponent = source.component ?: packageManager.resolveActivity(source, 0)
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
            ?: return null
        // Seasonal launcher aliases lead through a no-display entry activity. Workspace
        // task reuse and presentation must target the real settings activity instead.
        val component = if (requestedComponent.packageName == TriggerBroadcastContract.MODULE_PACKAGE) {
            settingsComponent
        } else requestedComponent
        val info = runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull()
            ?: return null
        if (!info.exported || !info.enabled) return null
        return Intent(source)
            .setComponent(component)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun restoreExternalFullscreenLayout(task: TaskRepository.TaskSnapshot, layout: TaskLayout) {
        // A notification or native window gesture may promote a slot task into
        // freeform/PiP. Shell already chose its bounds, density and windowing mode.
        // Relinquish the slot without restoring the former fullscreen layout.
        if (task.vendorWindowed || task.windowingMode != fullscreenWindowingMode()) return
        restoreTaskLayout(task, layout, restoreResizeMode = false)
    }

    private fun normalizeExternalIntent(source: Intent): Intent? {
        val component = source.component ?: packageManager.resolveActivity(source, 0)
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
            ?: return null
        val info = runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull()
            ?: return null
        if (!info.exported || !info.enabled) return null
        return Intent(source)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun Intent.targetUser() = getParcelableExtra(
        TriggerBroadcastContract.EXTRA_TARGET_USER,
        android.os.UserHandle::class.java,
    )

    private fun ensureSlotLayout(
        slotIndex: Int,
        task: TaskRepository.TaskSnapshot,
        originalLayout: TaskLayout? = null,
    ) {
        val slotGeometry = slots[slotIndex].geometry()
            ?: error("Slot $slotIndex geometry is unavailable")
        val fullscreenMode = fullscreenWindowingMode()
        val targetBounds = Rect(0, 0, slotGeometry.width, slotGeometry.height)
        val record = slotRecords[slotIndex]
        if (record.originalLayout == null) {
            record.originalLayout = originalLayout ?: if (task.displayId == Display.DEFAULT_DISPLAY) {
                task.toLayout()
            } else {
                defaultMainLayout(task.resizeMode)
            }
        }
        // Callers may hold the snapshot from before reparenting. Compare against
        // the destination task, otherwise every exchange forces another relayout.
        val current = if (task.displayId != slots[slotIndex].displayId()) {
            taskRepository.findTask(task.taskId) ?: error("Task ${task.taskId} exited")
        } else task
        val resizeModeMismatch = current.resizeMode != TASK_RESIZE_MODE_RESIZEABLE
        val layoutMismatch = current.bounds != targetBounds ||
            current.densityDpi != slotGeometry.densityDpi ||
            current.windowingMode != fullscreenMode
        if (resizeModeMismatch) {
            ActivityTaskManagerCompat.setTaskResizeable(
                task.taskId,
                TASK_RESIZE_MODE_RESIZEABLE,
            ).getOrThrow()
        }
        if (layoutMismatch) {
            val token = current.token ?: error("Task ${task.taskId} has no slot layout token")
            WindowContainerTransactionCompat.applyTaskLayout(
                token = token,
                // Fullscreen inherits this virtual display's canvas. An explicit
                // slot-sized override survives promotion and forces a second resize.
                bounds = Rect(),
                densityDpi = slotGeometry.densityDpi,
                windowingMode = fullscreenMode,
            ).getOrThrow()
            waitForTaskLayout(
                taskId = task.taskId,
                bounds = targetBounds,
                densityDpi = slotGeometry.densityDpi,
                windowingMode = fullscreenMode,
            )
            NeXtepLog.info(
                "slot_layout",
                "Applied slot=$slotIndex taskId=${task.taskId} bounds=$targetBounds " +
                    "density=${slotGeometry.densityDpi}",
            )
        }
        slots[slotIndex].displayId()?.let { displayId ->
            val source = slots[slotIndex].sourceBounds()
            TaskSurfaceCompat.fitSlotSurface(task.taskId, displayId,
                Rect(source.left, source.top, source.right, source.bottom)).getOrThrow()
        }
    }

    private fun restoreTaskLayout(
        task: TaskRepository.TaskSnapshot,
        layout: TaskLayout,
        restoreResizeMode: Boolean = true,
    ) {
        val token = task.token ?: error("Task ${task.taskId} has no restore token")
        val current = taskRepository.findTask(task.taskId)
        val mainFullscreen = current?.displayId == Display.DEFAULT_DISPLAY &&
            layout.windowingMode == fullscreenWindowingMode()
        val expectedBounds = if (mainFullscreen) geometryProvider().let {
            Rect(0, 0, it.screenWidth, it.screenHeight)
        } else layout.bounds
        if (mainFullscreen &&
            current?.bounds == expectedBounds && current.densityDpi == layout.densityDpi &&
            current.windowingMode == layout.windowingMode) {
            if (restoreResizeMode) layout.resizeMode?.let {
                ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow()
            }
            return
        }
        // Fullscreen tasks inherit display bounds. Clear the slot override once;
        // restoring a saved resolved Rect and clearing it again adds another layout.
        WindowContainerTransactionCompat.applyTaskLayout(
            token = token,
            bounds = if (mainFullscreen) Rect() else layout.bounds,
            densityDpi = layout.densityDpi,
            windowingMode = layout.windowingMode,
        ).getOrThrow()
        waitForTaskLayout(
            taskId = task.taskId,
            bounds = expectedBounds,
            densityDpi = layout.densityDpi,
            windowingMode = layout.windowingMode,
        )
        if (restoreResizeMode) {
            layout.resizeMode?.let { resizeMode ->
                ActivityTaskManagerCompat.setTaskResizeable(task.taskId, resizeMode).getOrThrow()
            }
        }
        NeXtepLog.info(
            "slot_layout",
            "Restored taskId=${task.taskId} bounds=${layout.bounds} density=${layout.densityDpi} " +
                "restoreResizeMode=$restoreResizeMode",
        )
    }

    private fun TaskRepository.TaskSnapshot.toLayout() = TaskLayout(
        bounds = Rect(bounds),
        densityDpi = densityDpi,
        windowingMode = windowingMode,
        resizeMode = resizeMode,
        vendorWindowed = vendorWindowed,
    )

    private fun TaskLayout.defaultDisplayBounds(): Rect {
        // Match foreground restoration in the same reparent transaction: fullscreen
        // inherits the destination's current rotation/size, not a saved landscape Rect.
        // ColorOS floating tasks can report fullscreen; retain their native bounds.
        return if (!vendorWindowed && windowingMode == fullscreenWindowingMode()) Rect() else Rect(bounds)
    }

    private fun defaultMainLayout(resizeMode: Int?): TaskLayout {
        val geometry = geometryProvider()
        return TaskLayout(
            bounds = Rect(0, 0, geometry.screenWidth, geometry.screenHeight),
            densityDpi = applicationContext.resources.displayMetrics.densityDpi,
            windowingMode = fullscreenWindowingMode(),
            resizeMode = resizeMode,
        )
    }

    private fun fullscreenWindowingMode(): Int = ActivityTaskManagerCompat
        .resolveWindowingMode("WINDOWING_MODE_FULLSCREEN")
        ?: WINDOWING_MODE_FULLSCREEN_FALLBACK

    private fun moveTaskToDisplayAndWait(taskId: Int, displayId: Int) {
        TaskSurfaceCompat.markDisplayExchange(taskId, displayId)
        if (displayId == Display.DEFAULT_DISPLAY) {
            TaskSurfaceCompat.preparePromotion(taskId, geometryProvider()).onFailure {
                NeXtepLog.warn("task_surface", "Early promotion fitting unavailable taskId=$taskId", it)
            }
        } else {
            TaskSurfaceCompat.cancelPreparedPromotion(taskId)
        }
        try {
            ActivityTaskManagerCompat.moveTaskToDisplay(taskId, displayId).getOrThrow()
        } catch (error: Throwable) {
            TaskSurfaceCompat.cancelDisplayExchange(taskId)
            throw error
        }
        val deadline = SystemClock.uptimeMillis() + DISPLAY_MOVE_TIMEOUT_MS
        var observedDisplayId = taskRepository.findTask(taskId)?.displayId
        while (observedDisplayId != displayId && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(DISPLAY_MOVE_POLL_MS)
            observedDisplayId = taskRepository.findTask(taskId)?.displayId
        }
        check(observedDisplayId == displayId) {
            "Task $taskId display move did not settle: expected=$displayId " +
                "actual=$observedDisplayId"
        }
    }

    private fun waitForTaskLayout(
        taskId: Int,
        bounds: Rect,
        densityDpi: Int,
        windowingMode: Int,
    ) {
        val deadline = SystemClock.uptimeMillis() + TASK_LAYOUT_TIMEOUT_MS
        var snapshot = taskRepository.findTask(taskId)
        while (snapshot != null &&
            (snapshot.bounds != bounds ||
                snapshot.densityDpi != densityDpi ||
                snapshot.windowingMode != windowingMode) &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(TASK_LAYOUT_POLL_MS)
            snapshot = taskRepository.findTask(taskId)
        }
        check(snapshot != null &&
            snapshot.bounds == bounds &&
            snapshot.densityDpi == densityDpi &&
            snapshot.windowingMode == windowingMode
        ) {
            "Task $taskId layout did not settle: expected=$bounds/$densityDpi/$windowingMode " +
                "actual=${snapshot?.bounds}/${snapshot?.densityDpi}/${snapshot?.windowingMode}"
        }
    }

    private fun clearSlotAssignment(index: Int) {
        val record = slotRecords[index]
        record.retainedTaskId = null
        record.originalLayout = null
        record.pendingComponent = null
        record.pendingAttempts = 0
        record.migrationRecoveryComponent = null
        record.migrationRecoveryDeadline = 0L
        record.migrationRecoveryAttempts = 0
        record.mismatchSince = 0L
    }

    private fun setState(index: Int, state: SlotState, reason: String) {
        val previous = slotRecords[index].state
        slotRecords[index].state = state
        if (state !is SlotState.Occupied) slotRecords[index].mismatchSince = 0L
        slots[index].view.showState(state)
        if (previous != state) onSlotStatesChanged()
        if (previous != state) {
            NeXtepLog.info(
                "slot_state",
                "slot=$index previous=$previous current=$state reason=$reason",
            )
        }
    }

    private fun validActiveSlot(index: Int): Boolean =
        active && !suspended && index in slots.indices && !transitionInProgress

    private fun retainedTaskDescription(): String =
        slotRecords.map { it.retainedTaskId }.toString()

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Slot state changes must run on the main thread"
        }
    }

    private companion object {
        const val SETTLE_DELAY_MS = 700L
        const val MAIN_LAUNCH_POLL_MS = 200L
        const val MAX_MAIN_LAUNCH_POLLS = 3
        const val FULL_RECONCILE_INTERVAL_MS = 1_000L
        const val TASK_CHANGE_COALESCE_MS = 80L
        const val MAX_PENDING_POLLS = 4
        const val TASK_RESIZE_MODE_RESIZEABLE = 2
        const val WINDOWING_MODE_FULLSCREEN_FALLBACK = 1
        const val DISPLAY_MOVE_TIMEOUT_MS = 700L
        const val DISPLAY_MOVE_POLL_MS = 30L
        const val TASK_LAYOUT_TIMEOUT_MS = 900L
        const val TASK_LAYOUT_POLL_MS = 30L
        const val SLOT_MISMATCH_GRACE_MS = 2_400L
        const val MIGRATION_RECOVERY_WINDOW_MS = 5_000L
        const val MAX_MIGRATION_RECOVERY_ATTEMPTS = 1
        const val PROMOTED_RECOVERY_DELAY_MS = 900L
        const val PROMOTED_RECOVERY_POLL_MS = 350L
    }
}
