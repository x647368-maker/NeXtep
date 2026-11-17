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
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
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
    private var railHandleTouching = false
    private var shownWindowCount = 0
    private val sparseRailMotion = StageSparseRailReflowAnimator(context)
    private var previousRailGeometry: WorkspaceGeometry? = null
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
    private var cinemaCheckQueued = false
    private var cinemaConfirmQueued = false
    private val cinemaCheck = Runnable { cinemaCheckQueued = false; evaluateCinema() }
    private val cinemaConfirm = Runnable { cinemaConfirmQueued = false; evaluateCinema(true) }
    private fun cancelCinemaChecks() {
        backdropHandler.removeCallbacks(cinemaCheck)
        backdropHandler.removeCallbacks(cinemaConfirm)
        cinemaCheckQueued = false
        cinemaConfirmQueued = false
    }
    private fun updateCinemaMonitoring() {
        if (workspaceVisible && !suspended && core.cinemaMode == StageCinemaPolicy.Mode.AUTO) {
            if (pendingResumeContent != null) return
            mediaSignals.start()
            scheduleCinemaCheck()
        } else {
            mediaSignals.stop()
            cancelCinemaChecks()
        }
    }
    private var windowPickerGeneration = 0
    private var tabletLayoutMotionGeneration = 0
    private var railLayoutMotionView: View? = null
    private var railLayoutDrawObserver: ViewTreeObserver? = null
    private var railLayoutDrawListener: ViewTreeObserver.OnPreDrawListener? = null
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
    private val oneShotSwapMotion = StageOneShotSwapMotion(context)
    private val swapCapture = StageAsyncPreviewCapture()
    private val transientTapSwap = StageTransientTapSwap(context)
    private var liveSurfaceExchange: StageLiveSurfaceExchange? = null
    // Allocated only for one physical card swap; NEVER a persistent overlay.
    private var nativeSwapHost: StageNativeSwapFullscreenHost? = null
    private var nativeSwapPreparingSlot: VirtualDisplaySlot? = null
    private var nativeSwapAttempt = 0L
    private fun clearNativeSwapHost() {
        val old = nativeSwapHost
        nativeSwapHost = null
        nativeSwapPreparingSlot?.view?.endExchangePreview()
        nativeSwapPreparingSlot = null
        old?.close()
    }
    private var activeSwapViewport: WorkspaceGeometry? = null
    private var previewCapturePending = false
    private var deferredSwapGeometryRefresh = false

    private fun keepActiveSwapForViewport(now: WorkspaceGeometry): Boolean =
        StageSwapViewportHoldPolicy.retain(
            swapAnimator.isRunning || previewCapturePending ||
                liveSurfaceExchange?.isRunning == true || transientTapSwap.isRunning, activeSwapViewport, now,
        )
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
        { workspaceVisible && !suspended && !sidebarCollapsed && !groupInputBlocked && !railHandleTouching &&
            cinemaSlot == null && !workgroups.isSwitching() && slotCoordinator?.isGroupRestoreReady() == true &&
            !isExchangePending() && !swapAnimator.isRunning && !previewCapturePending &&
            !transientTapSwap.isRunning && liveSurfaceExchange?.isRunning != true && nativeSwapHost == null &&
            !windowPicker.isShowing && !AtomicPopupMenu.isShowing() &&
            SystemUiPanelMonitor.refresh(context) == StagePanelMenuGate.State.CLOSED }, ::renderSmartRail)
    private fun renderSmartRail(hidden: Boolean) {
        if (!workspaceVisible || suspended) return
        cancelRailLayoutMotion()
        // Hide existing live hosts before expanding the main task. Keep the
        // producer profile and rail bounds unchanged while invisible.
        if (hidden) records.firstOrNull { it.title == "NeXtepSidebar" }?.view?.let {
            it.animate().cancel()
            it.alpha = 0f
        }
        onViewportChanged()
        // A failed reconfigure can close the workspace and reset the rail.
        if (!workspaceVisible || suspended || smartRail.hidden != hidden) return
        records.filter { it.title == "NeXtepSidebar" || it.title == "AtomicSidebarHandle" }.forEach { record ->
            val params = record.view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            configureWindow(record, params, geometry())
            windowManager.updateViewLayout(record.view, params)
            if (record.title == "NeXtepSidebar") {
                record.view.animate().cancel()
                record.view.alpha = if (hidden) 0f else 1f
            }
        }
        if (!hidden) scheduleCinemaCheck()
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
    // One ready texture for the visible workspace, borrowed only by an
    // admitted native swap. All cache operations run on the main lane.
    private val gpuWallpaper=StageWallpaperCoverCache<android.hardware.HardwareBuffer>()
    // Power changes invalidate only the optional texture, not the ordinary
    // wallpaper capture. A worker from before saver entry cannot republish
    // its buffer if saver is switched off again before delivery.
    private val gpuWallpaperEpoch=java.util.concurrent.atomic.AtomicInteger()
    private fun wallpaperKey(g:WorkspaceGeometry)=StageWallpaperCoverCache.Key(
        g.screenWidth,g.screenHeight,g.displayRotation)
    private fun acquireWallpaperCover(g:WorkspaceGeometry):StageGpuMirrorOcclusion? {
        val lease=gpuWallpaper.acquire(wallpaperKey(g))?:return null
        val cover=runCatching {
            StageGpuMirrorOcclusion.createTextured(lease.resource,lease::close)
        }.getOrNull()
        if(cover==null)lease.close()
        return cover
    }
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
                    gpuWallpaper.clear()
                    clearHomePreview()
                    // Retain the displayed frame while the new wallpaper starts rendering.
                    // OEM live services may change content without changing component/id.
                    backdropGeneration.incrementAndGet()
                }
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    // Observe the real service state, not broadcast extras.
                    if(context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode!=false) {
                        gpuWallpaperEpoch.incrementAndGet()
                        gpuWallpaper.clear()
                        gpuWallpaper.invalidateRecovery()
                    }
                    // No recapture, timer, or automatic GPU warmup on exit.
                    return
                }
                Intent.ACTION_USER_PRESENT -> Unit
                else -> return
            }
            if (workspaceVisible && !suspended) refreshWallpaperBackdrop()
        }
    }

    init {
        // Do not pre-capture bitmap wallpapers at SystemUI startup: the user
        // may never open workbench in this session. First show() requests it.
        runCatching {
            context.registerReceiver(
                wallpaperReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { NeXtepLog.warn("wallpaper_backdrop", "Wallpaper change listener unavailable", it) }
        // No background capture until a real workspace has been displayed.
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
        cancelRailLayoutMotion()
        sparseRailMotion.cancel()
        railHandleTouching = false
        smartRail.stop(restorePresentation = false)
        cinemaEpoch++; directionDebounce.reset()
        mediaSignals.stop(); cancelCinemaChecks()
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
        gpuWallpaper.clear()
        clearHomePreview()
        backdropGeneration.incrementAndGet()
        slotCoordinator?.setSuspended(true)
        topAppStrip?.setWorkspaceVisible(false)
        controlAreaPager?.setWorkspaceState(workspaceVisible, locked = true)
        // Keep TextureViews attached: GONE/removeView would destroy their virtual displays.
        records.forEach { record ->
            record.view.animate().cancel()
            record.view.alpha = 0f
            record.view.scaleX = 1f
            record.view.scaleY = 1f
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
        // The existing frame-commit/fallback callback starts media monitoring.
        refreshQuickBar()
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
            record.view.scaleX = 1f
            record.view.scaleY = 1f
        }
        resumeContentAfterFrame()
        smartRail.armWhenReady()
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
            runCatching { updateCinemaMonitoring() }.onFailure {
                NeXtepLog.warn("workspace_unlock", "Media monitoring resume pending", it)
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
        cancelRailLayoutMotion()
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        workspaceVisible = true
        syncCoreViewport()
        updateCinemaMonitoring(); refreshQuickBar()
        reconfigure().getOrThrow()
        // Compose the first real workbench frame with the same bounded,
        // uniform card movement as tap exchanges. No extra layers or captures.
        // Leave control handles at scale 1 so their touch geometry never shifts.
        val entryGeometry = geometry()
        val entryMotion = StageWorkbenchEntryMotionPolicy.enabled(
            android.animation.ValueAnimator.areAnimatorsEnabled(),
            context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true,
        )
        val railOnRight = logicalSidebarSide(entryGeometry) == SidebarSide.RIGHT
        records.forEach { record ->
            val view = record.view
            view.animate().cancel()
            view.visibility = View.VISIBLE
            val start = StageWorkbenchEntryMotionPolicy.start(
                record.title, entryGeometry.railAtTop, railOnRight,
            )
            view.translationX = if (entryMotion) dp(start.offsetXdp) else 0f
            view.translationY = if (entryMotion) dp(start.offsetYdp) else 0f
            view.scaleX = if (entryMotion) start.scale else 1f
            view.scaleY = if (entryMotion) start.scale else 1f
            view.alpha = if (entryMotion) start.alpha else 1f
            if (entryMotion) {
                view.animate()
                    .alpha(1f).translationX(0f).translationY(0f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(WORKSPACE_ENTER_DURATION_MS)
                    .setInterpolator(transitionInterpolator)
                    .start()
            }
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
        smartRail.armWhenReady()
        scheduleStageFocus()
        return geometry()
    }

    fun hide() {
        cancelRailLayoutMotion()
        sparseRailMotion.cancel()
        previousRailGeometry = null
        railHandleTouching = false
        smartRail.stop(restorePresentation = false)
        cinemaEpoch++; directionDebounce.reset()
        mediaSignals.stop(); cancelCinemaChecks()
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
        gpuWallpaper.clear()
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
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(WORKSPACE_EXIT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .withEndAction {
                    view.visibility = View.GONE
                    view.translationX = 0f
                    view.translationY = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
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

    private var lastN5ParallelScene: StageNativeParallelDiagnostics.Scene? = null
    private var lastN5ParallelObservedMs: Long = 0L
    private var lastN5ParallelAttemptMs: Long? = null

    /** Called while the native app is still foreground, before opening diagnostics. */
    private fun observeN5ParallelForeground() {
        if (!workspaceVisible || suspended) return
        val now = SystemClock.uptimeMillis()
        if (lastN5ParallelAttemptMs?.let { now - it < 800L } == true) return
        // Rate-limit the entire diagnostic probe, including viewport reads on compact screens.
        // Keep the successful observation timestamp separate for diagnostic age.
        lastN5ParallelAttemptMs = now
        // Skip Binder task queries on phone and native landscape tablet profiles.
        // Only the near-square expanded physical canvas needs this diagnostic.
        val g = currentGeometry()
        val shortSide = minOf(g.screenWidth, g.screenHeight)
        val dpi = context.resources.displayMetrics.densityDpi
        if (shortSide <= 1 || dpi <= 0 ||
            shortSide * 160f / dpi < 600f ||
            maxOf(g.screenWidth, g.screenHeight).toFloat() / shortSide > 1.35f) return
        val scene = runCatching { nativeParallelScene(g, dpi) }.getOrNull() ?: return
        val foreground = scene.tasks.firstOrNull { it.display == 0 } ?: return
        if (foreground.app == "com.android.systemui" ||
            foreground.app == "io.github.x647368maker.atomicworkbench") return
        // A native side-by-side layout can still report mode=1. Activity count is
        // a hint only; recording makes no change to system surfaces or tasks.
        lastN5ParallelScene = scene
        lastN5ParallelObservedMs = SystemClock.uptimeMillis()
    }

    fun onTasksChanged() {
        observeN5ParallelForeground()
        slotCoordinator?.onTasksChanged()
        if (workspaceVisible && !suspended && !sidebarCollapsed) smartRail.armWhenReady()
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
        // Select the layout scope and geometry from the same physical measurement.
        // An explicit refresh must not re-read the display after selecting its side.
        val preparedScope = if (refreshViewport) viewport.prepareLayoutScope() else null
        syncCoreViewport(preparedScope)
        return viewport.geometry(sidebarSide).let {
            if (sidebarCollapsed) io.github.x647368maker.atomicworkbench.workspace.StageCollapsedRailPolicy.geometry(it, context.resources.displayMetrics.density)
            else {
                val base = if (viewport.activeCount == 0)
                    it.copy(sidebarWidth = minOf(it.sidebarWidth, dp(if (it.railAtTop) 56 else 52).toInt()))
                else it
                if (smartRail.hidden)
                    io.github.x647368maker.atomicworkbench.workspace.StageSmartRailGeometry
                        .reclaim(base, context.resources.displayMetrics.density)
                else base
            }
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
        cancelRailLayoutMotion()
        clearHomePreview()
        initializeIfNeeded()
        check(records.size == EXPECTED_WINDOW_COUNT) { "SystemUI windows are unavailable" }
        if (cinemaSlot != null && viewport.geometry(sidebarSide).isLandscape) {
            cinemaPolicy.leave(); cinemaSlot = null; viewport.cinema = false
        }
        val geometry = geometry()
        gpuWallpaper.clearIfMismatched(wallpaperKey(geometry))
        SystemServerWorkspaceBridge.publishLayout(context, viewport.layout())
        applyWindowGeometry(geometry, refreshControls = !rotationOnly)
        if (rotationOnly && !geometry.railAtTop &&
            !io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy.wideTablet(
                geometry.screenWidth, geometry.screenHeight)) {
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
        cancelRailLayoutMotion()
        val previous = geometry()
        viewport.setSidebarSide(side)
        sidebarSide = side
        val geometry = geometry()
        applyWindowGeometry(geometry)
        topAppStrip?.setSidebarSide(side)
        sidebarView?.let { panel -> applySlotLayout(panel, geometry) }
        applySidebarBackdrop(geometry)
        animateRailLayoutSelection(previous, geometry, SIDEBAR_ENTER_OFFSET_DP, SIDEBAR_SWITCH_DURATION_MS)
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
                cinemaPolicy.mode = core.cinemaMode; updateCinemaMonitoring()
            }
            "cinema_enter" -> { check(workspaceVisible && !suspended) { "请先开启台前调度" }; showCinemaMenu(records.first { it.title == "StageMainHandle" }.view) }
            "cinema_leave" -> leaveCinema()
            "quick_enable" -> {
                core.enabled = true
                core.expanded = false
                syncCoreViewport()
                renderQuickChrome()
                if (workspaceVisible && !suspended) onViewportChanged()
            }
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
            putBoolean("quick_fixed", core.fixed); putBoolean("quick_enabled", core.enabled); putBoolean("quick_expanded", core.enabled && core.expanded)
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

    /** Read-only scene details; only called on task changes / explicit diagnostics. */
    private fun nativeParallelScene(
        g: WorkspaceGeometry = currentGeometry(),
        dpi: Int = context.resources.displayMetrics.densityDpi,
    ): StageNativeParallelDiagnostics.Scene {
        val tasks = runCatching { taskRepository.runningTasks() }.getOrDefault(emptyList())
        val activityCounts = runCatching { context.getSystemService(android.app.ActivityManager::class.java)
            ?.getRunningTasks(8).orEmpty().associate { it.taskId to it.numActivities } }.getOrDefault(emptyMap())
        return StageNativeParallelDiagnostics.Scene(
            screenWidth = g.screenWidth,
            screenHeight = g.screenHeight,
            densityDpi = dpi,
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
                    activities = activityCounts[task.taskId] ?: -1,
                )
            },
        )
    }

    /** Prefer the last foreground observation; opening Settings changes foreground task. */
    fun slotStatesDescription(): String {
        val live = nativeParallelScene()
        val remembered = lastN5ParallelScene?.takeIf { StageNativeParallelDiagnostics.nearSquareExpanded(live) }
        val selected = remembered ?: live
        val msAgo = if (remembered == null) -1 else
            (SystemClock.uptimeMillis() - lastN5ParallelObservedMs).coerceAtLeast(0L)
        return "screen=${live.screenWidth}x${live.screenHeight} dpi=${context.resources.displayMetrics.densityDpi} collapsed=$sidebarCollapsed occupied=${viewport.activeCount} shown=$shownWindowCount\n" +
            (slotCoordinator?.slotDiagnostics() ?: "unavailable") +
            "\\nparallelSceneCached=${remembered != null} ageMs=$msAgo\n" +
            "nativeSwap=${StageLiveSurfaceExchange.lastOutcome()}\\n" +
            StageNativeParallelDiagnostics.report(selected)
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
                    { chooseWindowApp(it, null, quickPin = true) }, { setQuickExpanded(false) })
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
            title = "StageMainHandle", view = StageMainHandle(context, ::showMainMenu, ::closeMainWindow, {
                // Only on a wide Y700-like tablet: quick tap toggles the
                // favorites row. Long press still opens the complete main menu.
                // Compact phones and near-square foldables keep click=menu.
                val g = geometry()
                if (!core.enabled || io.github.x647368maker.atomicworkbench.workspace.StageTabletMainGripPolicy
                        .tap(g.screenWidth, g.screenHeight) !=
                    io.github.x647368maker.atomicworkbench.workspace.StageTabletMainGripPolicy.Tap.QUICK_BAR) false
                else {
                    setQuickExpanded(!(core.enabled && core.expanded))
                    true
                }
            }),
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
        if (keepActiveSwapForViewport(geometry)) {
            // Slot state notifications can request a new rail size while two
            // real tasks are moving between displays. Do not cancel the
            // visible card morph (or its transaction session) merely to draw
            // an intermediate Occupied/Ready state. Final layout is flushed
            // once by the exchange completion callback.
            deferredSwapGeometryRefresh = true
            NeXtepLog.info("swap_morph", "Deferred same-viewport window relayout during live exchange")
            return
        }
        cancelDragVisuals()
        // Folding is a display-geometry event. Switch dock vs inline '+' in
        // place, even when no new usage query or task callback arrives.
        quickBar?.setWideActions(
            io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy
                .quickBarCapacity(geometry.screenWidth, geometry.screenHeight) > 5)
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
        requested: WorkspaceGeometry,
    ) {
        // Invisible TextureViews keep their expanded rail's physical bounds.
        // Only the main task and the independently touchable handle reclaim space.
        val geometry = if (smartRail.hidden && record.title == "NeXtepSidebar")
            viewport.geometry(sidebarSide) else requested
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
        if (record.title == "StageMainHandle") {
            (record.view as? StageMainHandle)?.setTabletQuickBarShortcut(
                io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy
                    .wideTablet(geometry.screenWidth, geometry.screenHeight) && core.enabled)
        }
        setWindowBounds(record.title, params, geometry)
        if (record.title == "NeXtepTopBar") renderQuickChrome()
        if (record.title == "AtomicSidebarHandle") (record.view as? android.widget.TextView)?.apply {
            text = if (!sidebarCollapsed) "‹›" else if (
                io.github.x647368maker.atomicworkbench.workspace.StageRailHandleGesture.horizontalRail(geometry))
                (if (geometry.railAtTop || geometry.isSidebarAtTop) "⌄ 展开小窗" else "⌃ 展开小窗")
                else if (geometry.sidebarSide == SidebarSide.LEFT) "›\n展开" else "‹\n展开"
            textSize = if (sidebarCollapsed) 13f else 14f
            contentDescription = if (sidebarCollapsed || smartRail.hidden) "展开小窗" else "收起小窗"
            (background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (smartRail.hidden) Color.TRANSPARENT else Color.argb(210, 28, 43, 53))
            if (smartRail.hidden) {
                text = if (io.github.x647368maker.atomicworkbench.workspace.StageSmartRailGeometry.horizontal(geometry)) "━" else "│"
                textSize = 18f
            }
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
                if (smartRail.hidden) {
                    val box = io.github.x647368maker.atomicworkbench.workspace.StageSmartRailGeometry
                        .handle(geometry, context.resources.displayMetrics.density)
                    params.width=box.width;params.height=box.height;params.x=box.left;params.y=box.top
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

    private fun syncCoreViewport(preparedScope: String? = null) {
        core.layoutScope = preparedScope ?: viewport.prepareLayoutScope()
        sidebarSide = viewport.selectedSidebarSide()
        val states = slotCoordinator?.slotStates().orEmpty()
        viewport.activeCount = states.count { it is SlotState.Occupied }
        viewport.adaptive = core.adaptive
        viewport.mainPriority = core.mainPriority
        viewport.mainInputRegionReady = sidebarTouchRegionReady
        viewport.quickExpanded = core.enabled && core.expanded
        cinemaSlot?.let { index ->
            if ((states.getOrNull(index) as? SlotState.Occupied)?.taskId != cinemaPolicy.task) {
                cinemaSlot = null; cinemaPolicy.leave(); directionDebounce.reset()
            }
        }
        viewport.cinema = cinemaSlot != null && !sidebarCollapsed
        viewport.cinemaPortrait = cinemaPortrait
    }

    private fun addStageWindow(engine: SlotTaskCoordinator, intent: Intent): Result<Unit> {
        if (sidebarCollapsed) setSidebarCollapsed(false)
        if (smartRail.hidden) smartRail.interact()
        // Rebind presentation before capacity checks. Existing tasks are never removed.
        sidebarView?.let { applySlotLayout(it, currentGeometry()) }
        scheduleWindowRefresh()
        return engine.addWindow(intent) { showStageMessage(it) }
    }

    private fun renderQuickChrome() {
        val shown = quickInputReady && !quickChromeSuppressed && !suspended
        quickBar?.visibility = if (StageQuickBarEnablePolicy.showBar(shown, core.enabled, core.expanded)) View.VISIBLE else View.GONE
        quickExpandButton?.visibility = if (StageQuickBarEnablePolicy.showHandle(shown, core.enabled, core.expanded)) View.VISIBLE else View.GONE
        quickBar?.parent?.requestLayout()
    }
    private fun setQuickExpanded(expanded: Boolean) {
        if (groupInputBlocked || workgroups.isSwitching() || (expanded && !core.enabled)) return
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
                val g = currentGeometry()
                val capacity = io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy
                    .quickBarCapacity(g.screenWidth, g.screenHeight)
                // On wide displays keep '+' clickable even when 10–12 icons
                // overflow horizontally; do not change the compact phone bar.
                quickBar?.setWideActions(capacity > 5)
                val available = byKey.keys - core.hiddenApps
                val keys = StageQuickPolicy.select(core.fixed, core.pins, recent.map { it.key }, available, capacity)
                quickBar?.render(keys.mapNotNull(byKey::get))
            }
        } }
    }

    private fun quickSelected(entry: io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository.AppEntry, anchor: View, long: Boolean) {
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return
        fun ready(): SlotTaskCoordinator? {
            if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return null
            val engine = slotCoordinator ?: return null
            if (!engine.isGroupRestoreReady()) {
                showStageMessage("窗口正在切换，请稍后重试")
                return null
            }
            return engine
        }
        // Use the same exact-component / first same-user package resolution as
        // the coordinator, never a last-wins package map that overwrites tasks.
        fun existingSlot(engine: SlotTaskCoordinator): Int? {
            val task = taskRepository.findTaskForComponent(entry.component,
                io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository.userHandle(entry.userId),
                allowPackageFallback = entry.component.packageName != TriggerBroadcastContract.MODULE_PACKAGE)
                ?: return null
            return StageQuickSlotPolicy.ownedSlot(task.taskId, task.displayId,
                engine.slotStates().map { state ->
                    (state as? SlotState.Occupied)?.let { StageQuickSlotPolicy.Owner(it.taskId, it.displayId) }
                })
        }
        fun reveal() {
            if (sidebarCollapsed) setSidebarCollapsed(false)
            if (smartRail.hidden) smartRail.interact()
        }
        fun open() {
            val engine = ready() ?: return
            val source = existingSlot(engine)
            val result = if (source != null) {
                reveal()
                // Existing small-window tasks follow the established phased
                // exchange path, rather than the synchronous top-app promotion.
                if (ready() !== engine) return
                engine.openFromLauncher(entry.launchIntent())
            } else engine.openInMain(entry.launchIntent())
            result.onSuccess { frequentApps.observe(entry.component.packageName, entry.userId) }
                .onFailure { showStageMessage(it.message ?: "应用未打开") }
        }
        fun small(target: Int? = null, expected: Int? = null) {
            val engine = ready() ?: return
            val source = existingSlot(engine)
            val ids = engine.slotStates().map { (it as? SlotState.Occupied)?.taskId }
            when (StageQuickSlotPolicy.action(ids, source, target, expected)) {
                StageQuickSlotPolicy.Action.STALE -> showStageMessage("小窗已变化，请重新选择")
                StageQuickSlotPolicy.Action.ALREADY_PRESENT -> {
                    reveal()
                    showStageMessage("应用已在小窗 ${source!! + 1}，保留原任务")
                }
                StageQuickSlotPolicy.Action.FULL -> showStageMessage("四个小窗已满，请明确选择要替换的小窗")
                StageQuickSlotPolicy.Action.ADD -> {
                    addStageWindow(engine,entry.launchIntent())
                        .onSuccess { frequentApps.observe(entry.component.packageName, entry.userId) }
                        .onFailure { showStageMessage(it.message ?: "小窗未打开") }
                }
                StageQuickSlotPolicy.Action.REPLACE -> {
                    reveal()
                    if (ready() !== engine) return
                    // Revealing a rail can trigger a synchronous layout callback.
                    if ((engine.slotStates().getOrNull(target!!) as? SlotState.Occupied)?.taskId != expected) {
                        showStageMessage("小窗已变化，请重新选择")
                        return
                    }
                    engine.replaceWindow(target,entry.launchIntent())
                        .onSuccess { frequentApps.observe(entry.component.packageName, entry.userId) }
                        .onFailure { showStageMessage(it.message ?: "小窗未打开") }
                }
            }
        }
        if (!long) { open(); return }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "启动 / 切换到主窗" to { open() }
        val menuStates = slotCoordinator?.slotStates().orEmpty()
        if (menuStates.any { it !is SlotState.Occupied }) actions += "添加到小窗" to { small() }
        menuStates.forEachIndexed { index, state -> if (state is SlotState.Occupied) {
            val expected = state.taskId
            actions += "替换小窗 ${index + 1}" to { small(index,expected) }
        } }
        val g = currentGeometry()
        val pinLimit = maxOf(6,io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy
            .quickBarCapacity(g.screenWidth, g.screenHeight))
        actions += (if (entry.key in core.pins) "取消固定" else "固定到快捷栏（最多${pinLimit}个）") to {
            if (entry.key !in core.pins && core.pins.size >= pinLimit)
                showStageMessage("固定应用已满，请先移除一个")
            else {
                core.hiddenApps = core.hiddenApps - entry.key
                core.pins = StageQuickPolicy.toggle(core.pins, entry.key)
                refreshQuickBar()
            }
        }
        actions += "从常用应用栏移除" to {
            core.pins = core.pins.filter { it != entry.key }
            core.hiddenApps = core.hiddenApps + entry.key
            refreshQuickBar()
        }
        if (entry.key in core.pins) {
            actions += "向前排序" to { core.pins = StageQuickPolicy.move(core.pins, entry.key, -1); refreshQuickBar() }
            actions += "向后排序" to { core.pins = StageQuickPolicy.move(core.pins, entry.key, 1); refreshQuickBar() }
        }
        AtomicPopupMenu.show(context, anchor, actions, avoidBounds = menuAvoidBounds())
    }

    private fun scheduleCinemaCheck() {
        if (!workspaceVisible || suspended || pendingResumeContent != null || cinemaCheckQueued ||
            core.cinemaMode != StageCinemaPolicy.Mode.AUTO || cinemaPolicy.isManual) return
        // Coalesce bursts without indefinitely postponing a stable playback signal.
        cinemaCheckQueued = backdropHandler.postDelayed(cinemaCheck, 180)
    }
    private fun evaluateCinema(confirm: Boolean = false) {
        if (!workspaceVisible || suspended || pendingResumeContent != null || cinemaApplying || groupInputBlocked || workgroups.isSwitching() ||
            core.cinemaMode != StageCinemaPolicy.Mode.AUTO || cinemaPolicy.isManual) return
        if (slotCoordinator?.isGroupRestoreReady() != true) return
        // Automation must not override explicit hidden/collapsed layouts or
        // repeatedly propose an unsupported landscape cinema presentation.
        if (sidebarCollapsed || smartRail.hidden || viewport.geometry(sidebarSide).isLandscape) {
            directionDebounce.reset()
            val retained = slotCoordinator?.slotStates().orEmpty()
                .filterIsInstance<SlotState.Occupied>().mapTo(mutableSetOf()) { it.taskId }
            cinemaPolicy.update(null, retained, emptySet(), android.os.SystemClock.uptimeMillis(), retained)
            return
        }
        val states = slotCoordinator?.slotStates().orEmpty()
        val occupied = states.filterIsInstance<SlotState.Occupied>()
        if (occupied.isEmpty()) {
            directionDebounce.reset()
            cinemaPolicy.update(null, emptySet(), emptySet(), android.os.SystemClock.uptimeMillis())
            if (cinemaSlot != null) applyCinemaTask(null)
            return
        }
        val observations = mediaSignals.observations()
        if (observations.isEmpty() && cinemaPolicy.task == null) {
            cinemaPolicy.update(null, occupied.mapTo(mutableSetOf()) { it.taskId }, emptySet(),
                android.os.SystemClock.uptimeMillis(), occupied.mapTo(mutableSetOf()) { it.taskId })
            return
        }
        val tasks = taskRepository.runningTasks().associateBy { it.taskId }
        val bound = StageMediaBinding.bind(tasks.values.map { StageObservedTask(it.taskId, it.component.packageName, it.userId) }, observations)
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
        if (!confirm && !cinemaConfirmQueued && trusted != null &&
            (next == null || next in playing && !cinemaPolicy.isManual)) {
            // Exactly one bounded confirmation; no anonymous callback backlog.
            cinemaConfirmQueued = backdropHandler.postDelayed(cinemaConfirm, 750)
        }
    }
    private fun enterCinema(task: Int, portrait: Boolean = core.cinemaMode == StageCinemaPolicy.Mode.PORTRAIT) {
        if (core.cinemaMode == StageCinemaPolicy.Mode.OFF) { showStageMessage("影音布局已关闭，请在设置中改为手动或自动"); return }
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching() || slotCoordinator?.isGroupRestoreReady() != true) return
        if (viewport.geometry(sidebarSide).isLandscape) {
            showStageMessage("当前横屏排列保留，影音小窗布局请在竖屏使用")
            return
        }
        if (slotCoordinator?.slotStates()?.none { (it as? SlotState.Occupied)?.taskId == task } != false) {
            showStageMessage("小窗已变化，请重新选择")
            return
        }
        cinemaEpoch++; directionDebounce.reset(); cancelCinemaChecks()
        // Explicit cinema selection may reveal the rail; automatic selection may not.
        if (smartRail.hidden) smartRail.interact()
        setSidebarCollapsed(false)
        if (!workspaceVisible || suspended || slotCoordinator?.isGroupRestoreReady() != true ||
            slotCoordinator?.slotStates()?.none { (it as? SlotState.Occupied)?.taskId == task } != false) return
        cinemaPolicy.enter(task); applyCinemaTask(task, portrait)
    }
    private fun leaveCinema(deferViewportRefresh:Boolean=false) {
        cinemaEpoch++; directionDebounce.reset(); cancelCinemaChecks()
        cinemaPolicy.leave(); applyCinemaTask(null,deferViewportRefresh=deferViewportRefresh)
        smartRail.armWhenReady()
    }
    private fun applyCinemaTask(task: Int?, portrait: Boolean = cinemaPortrait,
                                deferViewportRefresh:Boolean=false) {
        if (cinemaApplying) return
        val index = task?.let { id -> slotCoordinator?.slotStates()?.indexOfFirst { (it as? SlotState.Occupied)?.taskId == id }?.takeIf { it >= 0 } }
        if (index == cinemaSlot && (index == null || portrait == cinemaPortrait)) return
        if (viewport.geometry(sidebarSide).isLandscape && index != null) { cinemaPolicy.leave(); return }
        cinemaApplying = true
        val previous = cinemaSlot
        try {
            cinemaSlot = index; cinemaPortrait = portrait; syncCoreViewport()
            // Layout selection owns the combined final geometry. Do not
            // project the old rail layout once just to exit cinema.
            if (workspaceVisible && !suspended && !deferViewportRefresh) onViewportChanged()
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
            items += label to { leaveCinema(); core.cinemaMode = mode; cinemaPolicy.mode = mode; updateCinemaMonitoring() }
        }
        AtomicPopupMenu.show(context, anchor, items, avoidBounds = menuAvoidBounds())
    }
    private fun moveWechat() {
        check(workspaceVisible && !suspended && !groupInputBlocked && !workgroups.isSwitching()) { "请先开启台前调度" }
        val task = checkNotNull(taskRepository.foregroundTask()) { "请先在主窗打开微信小程序" }
        checkNotNull(slotCoordinator).moveForegroundWechatToSlot(task.taskId).getOrThrow()
        showStageMessage("已移动实际微信任务；共用任务的小程序会与微信一起移动")
    }

    private fun changeStageLayout(top: Boolean, side: SidebarSide = sidebarSide) {
        check(!groupInputBlocked && !workgroups.isSwitching()) { "请等待工作组切换完成" }
        if (workspaceVisible) check(slotCoordinator?.isGroupRestoreReady() == true) { "请等待窗口切换完成" }
        // Do not let smart-rail restore trigger an intermediate Shell/main
        // Surface refit before this explicit layout selection. The chosen
        // geometry is committed exactly once below; restore rail visibility
        // only after the final window bounds have been applied.
        val railWasHidden=smartRail.hidden
        // Hidden rail uses the actually projected pre-restore main task as
        // the motion origin. A visible rail in cinema mode must instead use
        // the geometry AFTER leaveCinema(), as it did before this fix.
        val hiddenGeometry=if(railWasHidden) geometry() else null
        val previous=viewport.isTopRail()
        val previousSide=sidebarSide
        val changingLayout=previous!=top || previousSide!=side
        if(railWasHidden && changingLayout)smartRail.stop(restorePresentation=false)
        else smartRail.stop()
        // Preserve the established collapsed-rail expand/side callbacks. Atomic
        // layout selection applies to the visible four-card path, not its state machine.
        if (!top && sidebarCollapsed && side != sidebarSide) {
            if (viewport.isTopRail()) changeStageLayout(false)
            onSidebarSideRequested(side)
            return
        }
        cancelRailLayoutMotion()
        cancelDragVisuals(); contentDrag.cancel(); windowPickerGeneration++; windowPicker.dismiss()
        leaveCinema(deferViewportRefresh=changingLayout)
        val previousGeometry=hiddenGeometry ?: geometry()
        if(previous==top && previousSide==side) {
            if (workspaceVisible && !suspended && !sidebarCollapsed) smartRail.armWhenReady()
            return
        }
        runCatching {
            viewport.setSidebarSide(side);sidebarSide=side;topAppStrip?.setSidebarSide(side)
            viewport.setTopRail(top)
            if (workspaceVisible && !suspended) {
                onViewportChanged()
                // Explicit selection owns the only viewport reconfiguration.
                // A prior auto-hidden rail must become visible at the NEW
                // position, never at the previous one for a transient frame.
                if(railWasHidden && changingLayout && workspaceVisible && !suspended) {
                    records.firstOrNull { it.title=="NeXtepSidebar" }?.view?.let {
                        it.animate().cancel()
                        it.alpha=1f
                    }
                    scheduleCinemaCheck()
                }
                // The same physical-axis entry is used on phones, folds and
                // tablets. The normal geometry owner still lays out real tasks.
                animateRailLayoutSelection(previousGeometry, geometry(), 10, 200L)
                if (!sidebarCollapsed) smartRail.armWhenReady()
            }
        }.onFailure {
            viewport.setSidebarSide(previousSide);sidebarSide=previousSide;topAppStrip?.setSidebarSide(previousSide)
            viewport.setTopRail(previous)
            if (workspaceVisible && !suspended) {
                onViewportChanged()
                if(railWasHidden && changingLayout && workspaceVisible && !suspended) {
                    records.firstOrNull { it.title=="NeXtepSidebar" }?.view?.let {
                        it.animate().cancel()
                        it.alpha=1f
                    }
                }
                if (!sidebarCollapsed) smartRail.armWhenReady()
            }
            throw it
        }
    }

    private fun cancelRailLayoutMotion() {
        tabletLayoutMotionGeneration++
        railLayoutDrawListener?.let { listener ->
            railLayoutDrawObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        railLayoutDrawObserver = null
        railLayoutDrawListener = null
        val panel = railLayoutMotionView ?: return
        railLayoutMotionView = null
        panel.animate().cancel()
        panel.translationX = 0f
        panel.translationY = 0f
        if (workspaceVisible && !suspended && !sidebarCollapsed && !smartRail.hidden)
            panel.alpha = 1f
    }

    private fun animateRailLayoutSelection(previous: WorkspaceGeometry, target: WorkspaceGeometry,
        distanceDp: Int, durationMs: Long) {
        cancelRailLayoutMotion()
        fun allowed(): Boolean = workspaceVisible && !suspended && !sidebarCollapsed &&
            !smartRail.hidden && !railHandleTouching && !groupInputBlocked && !workgroups.isSwitching() &&
            slotCoordinator?.isGroupRestoreReady() == true &&
            cinemaSlot == null && !isExchangePending() && !swapAnimator.isRunning &&
            !previewCapturePending && !transientTapSwap.isRunning &&
            liveSurfaceExchange?.isRunning != true && nativeSwapHost == null &&
            slotWindows.none { sparseRailMotion.isAnimating(it.view) } &&
            slotCoordinator?.slotStates()?.any { it is SlotState.Occupied } == true &&
            StageWorkbenchEntryMotionPolicy.enabled(
                android.animation.ValueAnimator.areAnimatorsEnabled(),
                context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true)
        if (!allowed()) return
        val axis = io.github.x647368maker.atomicworkbench.workspace.StageRailHandleGesture
        val entry = io.github.x647368maker.atomicworkbench.workspace.StageRailLayoutMotionPolicy.entry(
            sameCanvas = previous.screenWidth == target.screenWidth &&
                previous.screenHeight == target.screenHeight &&
                previous.displayRotation == target.displayRotation &&
                previous.navigationInsets == target.navigationInsets,
            changedLayout = previous.railAtTop != target.railAtTop ||
                previous.sidebarSide != target.sidebarSide,
            horizontal = axis.horizontalRail(target),
            positiveOutward = axis.positiveOutward(target),
            distance = dp(distanceDp),
            durationMs = durationMs,
        ) ?: return
        val panel = records.firstOrNull { it.title == "NeXtepSidebar" }?.view ?: return
        val generation = tabletLayoutMotionGeneration
        val observer = panel.viewTreeObserver
        if (!observer.isAlive || !panel.isAttachedToWindow) return
        // Choreographer's animation callback precedes measure/layout. Use a
        // single pre-draw callback instead to inspect the actual new bounds.
        // Pending callbacks own no View transform; only a started entry does.
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                else if (panel.viewTreeObserver.isAlive) panel.viewTreeObserver.removeOnPreDrawListener(this)
                if (railLayoutDrawListener !== this || generation != tabletLayoutMotionGeneration) return true
                railLayoutDrawObserver = null
                railLayoutDrawListener = null
                val actual = geometry()
                if (!allowed() || !panel.isAttachedToWindow || panel.visibility != View.VISIBLE ||
                    actual != target || panel.width != target.sidebarPhysicalWidth ||
                    panel.height != target.sidebarPhysicalHeight) return true
                panel.animate().cancel()
                railLayoutMotionView = panel
                panel.alpha = .86f
                panel.translationX = entry.dx
                panel.translationY = entry.dy
                panel.animate().alpha(1f).translationX(0f).translationY(0f)
                    .setDuration(entry.durationMs)
                    .setInterpolator(transitionInterpolator)
                    .withEndAction {
                        if (generation == tabletLayoutMotionGeneration && railLayoutMotionView === panel)
                            railLayoutMotionView = null
                    }.start()
                return true
            }
        }
        railLayoutDrawObserver = observer
        railLayoutDrawListener = listener
        observer.addOnPreDrawListener(listener)
        panel.invalidate()
    }

    private fun showMainMenu() {
        syncCoreViewport()
        if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return
        val anchor = records.firstOrNull { it.title == "StageMainHandle" }?.view ?: return
        AtomicPopupMenu.show(context, anchor, listOf(
            "顶部横排" to { changeStageLayout(true) },
            "左侧排列" to { changeStageLayout(false,SidebarSide.LEFT) },
            "右侧排列" to { changeStageLayout(false,SidebarSide.RIGHT) },
            // Quick visibility is managed in 快捷栏样式.
            (if (!core.mainPriority) "✓ 完整显示布局" else "完整显示布局") to { setMainPriority(false) },
            (if (core.mainPriority) "✓ 主窗口优先布局" else "主窗口优先布局") to { setMainPriority(true) },
            "常用应用管理" to { showQuickManagementMenu(anchor) },
            "快捷栏样式" to { showQuickStyleMenu(anchor) },
            "显示与动画" to { showDisplayOptionsMenu(anchor) },
            "主窗口与小窗交换" to { showMainSlotExchangeMenu(anchor) },
            "窗口顺序调整" to { showSlotOrderMenu(anchor) },
            "隐藏/显示侧边栏" to { toggleRailFromMenu() },
            "添加小窗" to { chooseWindowApp(anchor,null) },
            // Cinema and rail controls are managed in 显示与动画.
            "将当前微信任务移入小窗" to { runCatching { moveWechat() }.onFailure { showStageMessage(it.message ?: "微信任务未移动") } },
            "替换主应用" to { chooseWindowApp(anchor, null, main = true) },
            "添加常用应用到快捷栏" to { chooseWindowApp(anchor, null, quickPin = true) },
            "恢复被隐藏的常用应用" to {
                core.hiddenApps = emptySet()
                refreshQuickBar()
            },
            "关闭主窗（移回后台）" to { closeMainWindow() },
            "退出台前调度" to { onExitRequested() },
        ), avoidBounds = menuAvoidBounds(), selectedLabels = setOf(
            if(viewport.isTopRail()) "顶部横排" else if(sidebarSide==SidebarSide.LEFT) "左侧排列" else "右侧排列",
            if(core.mainPriority) "主窗口优先布局" else "完整显示布局",
        ), submenuLabels = setOf("常用应用管理","快捷栏样式","显示与动画","主窗口与小窗交换","窗口顺序调整"))
    }

    // Menu actions must recheck the real workspace owner after dismissal.
    private fun menuQuickOrDisplayReady():Boolean {
        if(menuWindowEngine()!=null &&
            SystemUiPanelMonitor.refresh(context)==StagePanelMenuGate.State.CLOSED)return true
        showStageMessage("窗口正在切换，请稍后重试")
        return false
    }

    private fun showQuickManagementMenu(anchor:View) {
        if(!menuQuickOrDisplayReady())return
        val items=mutableListOf<Pair<String,()->Unit>>()
        items += "管理已显示的常用应用" to {
                if(menuQuickOrDisplayReady()) {
                    if(core.enabled) {
                        setQuickExpanded(true)
                        showStageMessage("长按快捷栏应用图标，可固定、移除或调整排序")
                    } else showStageMessage("请先在快捷栏样式中启用常用应用栏")
                }
        }
        // Manage every persisted pin, even ones outside this display's
        // visible capacity. Keep add/restore in the original Other category.
        core.pins.forEachIndexed { index,key->
            val label=appCatalog.cachedLabel(key) ?: key
            items += "固定应用 ${index+1}：$label" to {
                if(menuQuickOrDisplayReady() && key in core.pins)showPinnedQuickActions(anchor,key)
                else showStageMessage("固定应用已变化，请重新选择")
            }
        }
        AtomicPopupMenu.show(context,anchor,items,avoidBounds=menuAvoidBounds(),
            submenuLabels=items.map{it.first}.filter{it.startsWith("固定应用 ")}.toSet())
    }

    private fun showPinnedQuickActions(anchor:View,key:String) {
        fun update(transform:(List<String>)->List<String>) {
            if(!menuQuickOrDisplayReady() || key !in core.pins)return
            core.pins=transform(core.pins)
            refreshQuickBar()
        }
        AtomicPopupMenu.show(context,anchor,listOf(
            "向前排序" to { update { StageQuickPolicy.move(it,key,-1) } },
            "向后排序" to { update { StageQuickPolicy.move(it,key,1) } },
            "取消固定" to { update { pins->pins.filter { it!=key } } },
            "从常用应用栏移除" to {
                if(menuQuickOrDisplayReady() && key in core.pins) {
                    core.pins=core.pins.filter { it!=key }
                    core.hiddenApps=core.hiddenApps+key
                    refreshQuickBar()
                }
            },
        ),avoidBounds=menuAvoidBounds())
    }

    private fun showQuickStyleMenu(anchor:View) {
        if(!menuQuickOrDisplayReady())return
        val items=mutableListOf<Pair<String,()->Unit>>()
        items += (if(core.enabled) "关闭常用应用栏" else "启用常用应用栏") to {
            if(menuQuickOrDisplayReady()) {
                core.enabled=!core.enabled
                setQuickExpanded(false)
            }
        }
        if(core.enabled)items += (if(core.expanded) "收起常用应用快捷栏" else "展开常用应用快捷栏") to {
            if(menuQuickOrDisplayReady() && core.enabled)setQuickExpanded(!core.expanded)
        }
        items += (if(!core.fixed) "✓ 自动推荐" else "自动推荐") to {
            if(menuQuickOrDisplayReady()){core.fixed=false;refreshQuickBar()}
        }
        items += (if(core.fixed) "✓ 固定应用" else "固定应用") to {
            if(menuQuickOrDisplayReady()){core.fixed=true;refreshQuickBar()}
        }
        AtomicPopupMenu.show(context,anchor,items,avoidBounds=menuAvoidBounds())
    }

    private fun showDisplayOptionsMenu(anchor:View) {
        if(!menuQuickOrDisplayReady())return
        AtomicPopupMenu.show(context,anchor,listOf(
            (if(core.adaptive) "关闭小窗智能自适应" else "开启小窗智能自适应") to {
                if(menuQuickOrDisplayReady()) {
                    core.adaptive=!core.adaptive
                    syncCoreViewport()
                    onViewportChanged()
                }
            },
            "影音布局与自动模式" to { if(menuQuickOrDisplayReady())showCinemaMenu(anchor) },
            (if (smartRail.enabled) "关闭智能侧栏" else "开启智能侧栏（静置收起）") to {
                if(menuQuickOrDisplayReady()) {
                    smartRail.setEnabled(!smartRail.enabled)
                    showStageMessage(if (smartRail.enabled) "智能侧栏已开启：顶部或侧栏静置后收起，点击恢复条重新展开；保留真实任务" else "已恢复原有侧栏行为")
                }
            },
            "侧栏自动收起时间（${smartRail.idleSeconds}秒）" to {
                if(menuQuickOrDisplayReady())showSmartRailDelayMenu(anchor)
            },
            (if(smartRail.pinned) "取消固定侧栏" else "固定侧栏") to {
                if(menuQuickOrDisplayReady())smartRail.setPinned(!smartRail.pinned)
            },
            "打开完整设置" to { if(menuQuickOrDisplayReady())onSettingsRequested() },
        ),avoidBounds=menuAvoidBounds(),submenuLabels=setOf(
            "影音布局与自动模式","侧栏自动收起时间（${smartRail.idleSeconds}秒）"))
    }

    // These entries reuse the existing task exchange, visual reorder and
    // rail owners. Never create a replacement task or write window geometry.
    private fun menuWindowEngine(): SlotTaskCoordinator? {
        if(!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching() ||
            isExchangePending() || swapAnimator.isRunning || previewCapturePending ||
            transientTapSwap.isRunning || liveSurfaceExchange?.isRunning==true || nativeSwapHost!=null)return null
        return slotCoordinator?.takeIf { it.isGroupRestoreReady() }
    }

    private fun menuSlotStillOwned(engine:SlotTaskCoordinator,index:Int,expected:SlotState.Occupied):Boolean {
        val current=engine.slotStates().getOrNull(index) as? SlotState.Occupied
        return current!=null && current.taskId==expected.taskId && current.displayId==expected.displayId &&
            current.component==expected.component
    }

    private fun menuOccupiedSlots(engine:SlotTaskCoordinator):List<Pair<Int,SlotState.Occupied>> =
        stageOrder.mapNotNull { index->
            (engine.slotStates().getOrNull(index) as? SlotState.Occupied)?.let { index to it }
        }

    private fun showMainSlotExchangeMenu(anchor:View) {
        val engine=menuWindowEngine() ?: run { showStageMessage("窗口正在切换，请稍后重试");return }
        val entries=menuOccupiedSlots(engine)
        if(entries.isEmpty()){showStageMessage("暂无可交换的小窗");return}
        AtomicPopupMenu.show(context,anchor,entries.mapIndexed { position,(index,state)->
            "小窗 ${position+1}：${state.component.packageName}" to {
                if(menuWindowEngine()!==engine || !menuSlotStillOwned(engine,index,state)) {
                    showStageMessage("小窗已变化，请重新选择")
                } else {
                    if(sidebarCollapsed)setSidebarCollapsed(false)
                    if(smartRail.hidden)smartRail.interact()
                    if(menuWindowEngine()===engine && menuSlotStillOwned(engine,index,state))
                        engine.onSlotClicked(index)
                    else showStageMessage("窗口正在切换，请重新选择")
                }
            }
        },avoidBounds=menuAvoidBounds())
    }

    private fun showSlotOrderMenu(anchor:View) {
        val engine=menuWindowEngine() ?: run { showStageMessage("窗口正在切换，请稍后重试");return }
        val entries=menuOccupiedSlots(engine)
        if(entries.size<2){showStageMessage("至少需要两个小窗才能调整顺序");return}
        AtomicPopupMenu.show(context,anchor,entries.mapIndexed { position,(source,state)->
            "选择小窗 ${position+1}：${state.component.packageName}" to {
                if(menuWindowEngine()!==engine || !menuSlotStillOwned(engine,source,state))
                    showStageMessage("小窗已变化，请重新选择")
                else {
                    val targets=menuOccupiedSlots(engine).mapIndexedNotNull { targetPosition,(target,other)->
                        if(source==target)null else
                            "与小窗 ${targetPosition+1} 交换：${other.component.packageName}" to {
                                if(menuWindowEngine()===engine &&
                                    menuSlotStillOwned(engine,source,state) &&
                                    menuSlotStillOwned(engine,target,other)) {
                                    // Same visual-only operation as drag reorder:
                                    // no display migration or producer replacement.
                                    stageOrder=StageVisualOrder.swap(stageOrder,source,target)
                                    applyStageStack(geometry())
                                    NeXtepLog.info("task_continuity","menu_visual_reorder tasks=${state.taskId},${other.taskId} display_migrations=0 launch_requests=0")
                                } else showStageMessage("小窗已变化，请重新选择")
                            }
                    }
                    if(targets.isEmpty())showStageMessage("暂无可调整的小窗")
                    else AtomicPopupMenu.show(context,anchor,targets,avoidBounds=menuAvoidBounds())
                }
            }
        },avoidBounds=menuAvoidBounds(),submenuLabels=entries.mapIndexed { position,(_,state)->
            "选择小窗 ${position+1}：${state.component.packageName}"
        }.toSet())
    }

    private fun toggleRailFromMenu() {
        if(menuWindowEngine()==null){showStageMessage("窗口正在切换，请稍后重试");return}
        if(SystemUiPanelMonitor.refresh(context)!=StagePanelMenuGate.State.CLOSED)return
        if(smartRail.hidden)smartRail.interact()
        else if(smartRail.enabled && !sidebarCollapsed)smartRail.hideNow()
        else setSidebarCollapsed(!sidebarCollapsed)
    }

    /** All workspace popups use the same visible task regions. A hidden rail
     * still owns real TextureViews but is not a visible menu obstacle. */
    private fun menuAvoidBounds(): List<Rect> = buildList {
        val g = geometry()
        add(Rect(g.contentLeft,g.contentTop,g.contentRight,g.contentBottom))
        if (!sidebarCollapsed && !smartRail.hidden) slotWindows.forEach { slot ->
            val box = Rect()
            if (slot.view.isWorkspaceCardVisible() && slot.view.getScreenVisibleRect(box)) add(box)
        }
    }

    private fun showSmartRailDelayMenu(anchor: View) {
        val items: List<Pair<String, () -> Unit>> = listOf(3, 5, 10, 15, 30).map { seconds ->
            (if (smartRail.idleSeconds == seconds) "✓ ${seconds}秒" else "${seconds}秒") to {
                smartRail.setIdleSeconds(seconds)
            }
        } + listOf("始终显示（固定侧栏）" to { smartRail.setPinned(true) })
        AtomicPopupMenu.show(context, anchor, items, avoidBounds = menuAvoidBounds())
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
        // A popup outlives a task callback. Never address its old positional
        // index after another task took ownership (including a recreated VD).
        fun withOwner(action: (SlotTaskCoordinator) -> Unit) {
            if (!workspaceVisible || suspended || groupInputBlocked || workgroups.isSwitching()) return
            val engine = slotCoordinator ?: return
            if (!engine.isGroupRestoreReady()) {
                showStageMessage("窗口正在切换，请稍后重试")
                return
            }
            val current = engine.slotStates().getOrNull(index) as? SlotState.Occupied
            if (current == null || current.taskId != state.taskId ||
                current.displayId != state.displayId || current.component != state.component) {
                showStageMessage("小窗已变化，请重新打开菜单")
                return
            }
            action(engine)
        }
        AtomicPopupMenu.show(context, anchor, listOf(
            "横屏影音布局（保留原任务）" to { withOwner { enterCinema(state.taskId, portrait = false) } },
            "竖屏影音布局（保留原任务）" to { withOwner { enterCinema(state.taskId, portrait = true) } },
            "在小窗中操作" to { withOwner { slotWindows.getOrNull(index)?.view?.setDirectInteraction(true) } },
            "切换为主窗口" to { withOwner { it.onSlotClicked(index) } },
            "替换应用" to { withOwner { chooseWindowApp(anchor, index, state.taskId) } },
            "关闭小窗（移回后台）" to { withOwner { engine ->
                engine.closeWindow(index).onFailure {
                    android.widget.Toast.makeText(context, it.message ?: "关闭失败", android.widget.Toast.LENGTH_LONG).show()
                }
            } },
        ), avoidBounds = menuAvoidBounds())
    }

    private fun chooseWindowApp(anchor: View, target: Int?, expectedTask: Int? = null,
        main: Boolean = false, quickPin: Boolean = false) {
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
        windowPicker.show(anchor, if (quickPin) "添加到常用应用快捷栏" else if (main) "替换主应用"
            else if (target == null) "添加小窗" else "替换应用", rows, fillContent = true)
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
                    if (workgroups.isSwitching() || groupInputBlocked || !workspaceVisible || suspended) return
                    if (quickPin) {
                        val g = currentGeometry()
                        val cap = maxOf(6,io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy
                            .quickBarCapacity(g.screenWidth, g.screenHeight))
                        if (entry.key !in core.pins && core.pins.size >= cap) {
                            showStageMessage("快捷栏固定应用已满，请长按移除一个后再添加")
                            return
                        }
                        core.hiddenApps = core.hiddenApps - entry.key
                        if (entry.key !in core.pins) core.pins = core.pins + entry.key
                        refreshQuickBar()
                        showStageMessage("已添加到常用应用栏：${entry.label}")
                        return
                    }
                    val engine = slotCoordinator ?: return
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
                // Keep a working retry action in the picker instead of a
                // full-height dead-end sheet. The catalog itself owns logging
                // and the retry again runs only on its background executor.
                rows.removeAllViews()
                // Show only a safe failure *class* for screenshot-based diagnosis.
                // Never display an exception message, package identifiers or stack traces.
                val failureType = when (it) {
                    is SecurityException -> "系统限制"
                    is IllegalStateException -> "未找到可用应用"
                    else -> it.javaClass.simpleName.take(32).ifBlank { "未知异常" }
                }
                rows.addView(windowPicker.label("应用列表读取失败（$failureType），可重试"))
                rows.addView(windowPicker.label("重新读取应用列表").apply {
                    contentDescription = "重新读取应用列表"
                    background = windowPicker.surface(active = true)
                    isClickable = true
                    setOnClickListener {
                        if (!workspaceVisible || suspended || !anchor.isAttachedToWindow) return@setOnClickListener
                        windowPicker.dismiss()
                        chooseWindowApp(anchor, target, expectedTask, main, quickPin)
                    }
                }, android.widget.LinearLayout.LayoutParams(-1, dp(52).toInt()))
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
        fun canChangeRail(): Boolean = workspaceVisible && !suspended &&
            !groupInputBlocked && !workgroups.isSwitching() &&
            slotCoordinator?.isGroupRestoreReady() == true &&
            !isExchangePending() && !swapAnimator.isRunning &&
            !previewCapturePending && !transientTapSwap.isRunning &&
            liveSurfaceExchange?.isRunning != true && nativeSwapHost == null &&
            SystemUiPanelMonitor.refresh(context) == StagePanelMenuGate.State.CLOSED
        setOnClickListener {
            if (railHandleTouching || !canChangeRail()) return@setOnClickListener
            if (smartRail.hidden) smartRail.interact()
            else if (smartRail.enabled && !sidebarCollapsed) smartRail.hideNow()
            else setSidebarCollapsed(!sidebarCollapsed)
        }
        var downX = 0f
        var downY = 0f
        var initialGeometry: WorkspaceGeometry? = null
        var initialHidden = false
        var initialCollapsed = false
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    initialGeometry = geometry()
                    initialHidden = smartRail.hidden; initialCollapsed = sidebarCollapsed
                    railHandleTouching = true
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    railHandleTouching = false
                    val started = initialGeometry
                    initialGeometry = null
                    val g = geometry()
                    if (started == null || started != g || initialHidden != smartRail.hidden ||
                        initialCollapsed != sidebarCollapsed) {
                        smartRail.armWhenReady()
                        return@setOnTouchListener true
                    }
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                    val decision = io.github.x647368maker.atomicworkbench.workspace.StageRailHandleGesture
                        .decision(g,event.rawX-downX,event.rawY-downY,slop,maxOf(dp(24),slop*2f))
                    when (decision) {
                        io.github.x647368maker.atomicworkbench.workspace.StageRailHandleGesture.Decision.TAP -> view.performClick()
                        io.github.x647368maker.atomicworkbench.workspace.StageRailHandleGesture.Decision.INWARD -> {
                            if (canChangeRail()) {
                                if (smartRail.hidden) smartRail.interact()
                                else if (sidebarCollapsed) setSidebarCollapsed(false)
                            }
                        }
                        io.github.x647368maker.atomicworkbench.workspace.StageRailHandleGesture.Decision.OUTWARD -> {
                            if (!smartRail.hidden && !sidebarCollapsed && canChangeRail()) setSidebarCollapsed(true)
                        }
                        else -> Unit
                    }
                    smartRail.armWhenReady()
                    true
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN, android.view.MotionEvent.ACTION_CANCEL -> {
                    initialGeometry = null
                    railHandleTouching = false
                    smartRail.armWhenReady()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_POINTER_UP -> true
                else -> false
            }
        }
    }

    private fun setSidebarCollapsed(value: Boolean) {
        if (!workspaceVisible || suspended || groupInputBlocked || sidebarCollapsed == value) return
        cancelRailLayoutMotion()
        // Collapsing an auto-hidden rail used to restore the old wide main
        // Surface and immediately refit it again to the collapsed width.
        // Keep it hidden and perform only the final collapsed reconfiguration.
        if(smartRail.hidden && value)smartRail.stop(restorePresentation=false)
        else smartRail.stop()
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
            val offset = if (value) io.github.x647368maker.atomicworkbench.workspace.StageRailHandleGesture
                .collapseOffset(base) else 0f to 0f
            record.view.animate().alpha(if (value) 0f else 1f)
                .translationX(offset.first)
                .translationY(offset.second)
                .setDuration(220).setInterpolator(transitionInterpolator).start()
        }
        if (!value) {
            smartRail.armWhenReady()
            scheduleCinemaCheck()
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
        transientTapSwap.cancel()
        swapAnimator.cancel()
        oneShotSwapMotion.cancel()
        nativeSwapAttempt++
        liveSurfaceExchange?.stop()
        liveSurfaceExchange = null
        clearNativeSwapHost()
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

    /**
     * Experimental private-only real two-card swap. Unlike PR50 the existing
     * ContentPanel already occupies (0,0,screenWidth,screenHeight) and is
     * touch-transparent. No second WindowManager view or ViewRoot is added.
     * The coordinator's genuine phased task migration starts at once.
     */
    private fun tryConcurrentLiveSwap(
        slotIndex: Int,
        moveAsync: ((Boolean) -> Unit) -> Unit,
        canRelease: () -> Boolean,
        onFinished: () -> Unit,
        onVisualUnavailable: ((String) -> Boolean)? = null,
    ): Boolean {
        val traceId=StageExchangeTelemetry.currentNativeTapId()
        val visible=workspaceVisible && !suspended && !sidebarCollapsed && !groupInputBlocked
        val animators=android.animation.ValueAnimator.areAnimatorsEnabled()
        val powerSave=context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        if (!StageConcurrentNativeTrialPolicy.allowed(visible,animators,powerSave)) {
            val reason=StageConcurrentNativeTrialPolicy.gateReason(visible,animators,powerSave)
            StageExchangeTelemetry.nativeEvent("live_gpu_skip:$reason",traceId)
            StageExchangeTelemetry.nativeEvent("fallback:gate_$reason",traceId)
            return false
        }
        val g=geometry()
        val task=mainTaskPresenter.currentTaskId() ?: run {
            StageExchangeTelemetry.nativeEvent("live_gpu_skip:main_task_missing",traceId)
            return false
        }
        val slot=slotWindows.getOrNull(slotIndex) ?: run {
            StageExchangeTelemetry.nativeEvent("live_gpu_skip:slot_view_missing",traceId)
            return false
        }
        val state=slotCoordinator?.slotStates()?.getOrNull(slotIndex) as? SlotState.Occupied
            ?: run {
                StageExchangeTelemetry.nativeEvent("live_gpu_skip:slot_not_occupied",traceId)
                return false
            }
        val host=records.firstOrNull { it.title=="NeXtepContentPanel" }?.view as? FrameLayout
            ?: run {
                StageExchangeTelemetry.nativeEvent("live_gpu_skip:content_panel_missing",traceId)
                return false
            }
        val point=IntArray(2)
        host.rootView.getLocationOnScreen(point)
        if (!StageConcurrentNativeTrialPolicy.fullScreenHost(
                point[0],point[1],host.rootView.width,host.rootView.height,
                g.screenWidth,g.screenHeight)) {
            StageExchangeTelemetry.nativeEvent("live_gpu_skip:host_bounds",traceId)
            StageExchangeTelemetry.nativeEvent("fallback:host_bounds",traceId)
            NeXtepLog.warn("native_swap", "PR55 fallback: host bounds " +
                "origin=(${point[0]},${point[1]}) view=${host.rootView.width}x${host.rootView.height} " +
                "display=${g.screenWidth}x${g.screenHeight}")
            return false
        }
        val source=Rect()
        if (!slot.view.getScreenVisibleRect(source)) {
            StageExchangeTelemetry.nativeEvent("live_gpu_skip:slot_bounds_missing",traceId)
            StageExchangeTelemetry.nativeEvent("fallback:slot_bounds_missing",traceId)
            return false
        }
        val main=Rect(g.contentLeft,g.contentTop,g.contentRight,g.contentBottom)
        val before=android.os.SystemClock.uptimeMillis()
        var coverWasMissing=false
        val native=StageLiveSurfaceExchange.create(
            host=host,sourceSlotView=slot.view,
            sourceTaskId=state.taskId,sourceDisplayId=state.displayId,
            mainTaskId=task,
            sourceBounds=source,mainBounds=main,
            // Only OTHER LIVE APP windows can obstruct the GPU card. PR66
            // mistakenly treated empty/ready placeholder slot Views as
            // obstacles and disabled the animation for otherwise safe taps.
            otherSlotBounds=slotWindows.mapIndexedNotNull { index, other ->
                if (index==slotIndex ||
                    slotCoordinator?.slotStates()?.getOrNull(index) !is SlotState.Occupied
                ) null else Rect().takeIf {
                    other.view.getScreenVisibleRect(it)
                }
            },
            fullScreenWidth=g.screenWidth,fullScreenHeight=g.screenHeight,
            backdrop=null,
            wallpaperCoverFactory={acquireWallpaperCover(g).also { coverWasMissing=it==null }},
        ) ?: run {
            val reason=StageLiveSurfaceExchange.lastOutcome()
            StageExchangeTelemetry.nativeEvent("live_gpu_skip:mirror_$reason",traceId)
            StageExchangeTelemetry.nativeEvent("fallback:mirror:$reason",traceId)
            NeXtepLog.warn("native_swap", "PR59 mirror rejected on current tap reason=$reason")
            if (StageConcurrentNativeTrialPolicy.transientMirrorOutcome(reason)) {
                // This task's owned leash or card bounds can become ready next tap.
                // Keep the current fallback but do not lock out the entire session.
                StageExchangeTelemetry.nativeEvent("mirror_transient_retry_next_tap")
            } else {
                StageConcurrentNativeTrialPolicy.disableForSession("OEM mirror unavailable: "+reason)
            }
            return false
        }
        val setupMs=android.os.SystemClock.uptimeMillis()-before
        StageExchangeTelemetry.nativeEvent("mirror_prepared_${setupMs}ms")
        if (!StageConcurrentNativeTrialPolicy.acceptPrepareTime(setupMs)) {
            native.stop()
            StageExchangeTelemetry.nativeEvent(
                "fallback:prepare_${setupMs}ms " + StageConcurrentNativeTrialPolicy.gateReason(
                    true,true,false))
            NeXtepLog.warn("native_swap","Mirror preparation exceeded frame budget: $setupMs ms; native allowed next tap=" +
                StageConcurrentNativeTrialPolicy.allowed(true,true,false))
            return false
        }
        // PR59: the previous task may draw many times during the GPU flight;
        // do not count old frames as new post-migration virtual-display output.
        var outputAtConfirmedMove: VirtualDisplaySlot.OutputFrameMark?=null
        fun outputHasFreshFrame():Boolean {
            val mark=outputAtConfirmedMove ?: return false
            val current=slot.outputFrameMark() ?: return false
            return StageConcurrentNativeTrialPolicy.freshFrameOnConfirmedOutput(
                StageConcurrentNativeTrialPolicy.sameOutput(
                    mark.displayId,current.displayId,mark.texture,current.texture,mark.surface,current.surface),
                mark.sequence,current.sequence)
        }
        var lastTargetWait=""
        val incoming=state.taskId
        activeSwapViewport=g
        deferredSwapGeometryRefresh=false
        slot.view.beginExchangePreview()
        val started=native.start(
            durationMs=StageConcurrentNativeTrialPolicy.DURATION_MS.toInt(),
            moveAsync={ complete -> moveAsync { ok ->
                if (ok) {
                    // Use the earlier verified ownership/layout checkpoint
                    // only for exactly the same live output as the final ACK.
                    val atAck=slot.outputFrameMark()
                    val earlier=slotCoordinator?.confirmedOutputMark(slotIndex,task)
                    outputAtConfirmedMove=earlier?.takeIf {
                        atAck!=null && StageConcurrentNativeTrialPolicy.sameOutput(
                            it.displayId,atAck.displayId,it.texture,atAck.texture,it.surface,atAck.surface)
                    } ?: atAck
                    StageExchangeTelemetry.nativeEvent(
                        if (outputAtConfirmedMove!=null && outputAtConfirmedMove===earlier)
                            "slot_buffer_baseline=verified_layout" else "slot_buffer_baseline=final_ack",traceId)
                }
                complete(ok)
            } },
            move={ true },
            liveReady={
                val now=slotCoordinator?.slotStates()?.getOrNull(slotIndex)
                val reason=when {
                    now !is SlotState.Occupied || now.taskId!=task -> "slot_owner"
                    !slot.view.textureView.isAvailable -> "slot_texture"
                    !outputHasFreshFrame() -> "slot_post_ack_buffer"
                    mainTaskPresenter.currentTaskId()!=incoming -> "main_owner"
                    !TaskSurfaceCompat.isPresentationSettled(incoming,g) -> "main_geometry"
                    else -> "ready"
                }
                if (reason != lastTargetWait) {
                    lastTargetWait=reason
                    StageExchangeTelemetry.nativeEvent("target_wait=$reason",traceId)
                }
                reason=="ready"
            },
            canRelease=canRelease,
            immediateMove=true,
            onFinished={
                slot.view.endExchangePreview()
                StageExchangeTelemetry.nativeEvent(
                    "native_finished:"+StageLiveSurfaceExchange.lastOutcome(),traceId)
                StageExchangeTelemetry.endNativeTap(
                    "gpu_" + StageLiveSurfaceExchange.lastOutcome().take(48),traceId)
                activeSwapViewport=null
                liveSurfaceExchange=null
                val flush=deferredSwapGeometryRefresh
                deferredSwapGeometryRefresh=false
                if(flush && workspaceVisible && !suspended) scheduleWindowRefresh()
                onFinished()
                if(workspaceVisible && !suspended) scheduleStageFocus()
                if(coverWasMissing && !flush) recoverMissingWallpaperAfterNativeFlight()
            },
            onVisualUnavailable={ reason ->
                // The native engine has already hidden/released its uncommitted
                // mirrors without finishing the coordinator. Transfer this same
                // tap to the next backend; never run two visual owners together.
                liveSurfaceExchange=null
                slot.view.endExchangePreview()
                activeSwapViewport=g
                previewCapturePending=false
                val accepted=runCatching { onVisualUnavailable?.invoke(reason) ?: false }
                    .getOrDefault(false)
                if(!accepted) StageExchangeTelemetry.nativeEvent(
                    "live_gpu_visual_fallback_rejected:$reason",traceId)
                accepted
            }
        )
        if (!started) {
            slot.view.endExchangePreview()
            activeSwapViewport=null
            StageExchangeTelemetry.nativeEvent("fallback:mirror_start_rejected")
            StageConcurrentNativeTrialPolicy.disableForSession("Mirror startup rejected")
            return false
        }
        liveSurfaceExchange=native
        oneShotSwapMotion.cancel()
        StageExchangeTelemetry.nativeEvent("native_armed:prepare_${setupMs}ms")
        NeXtepLog.info("native_swap","PR55 dual card GPU flight armed; prepMs=$setupMs")
        return true
    }

    /** PR79 private trial: short-lived independent task pixels, never a
     * migrating live mirror. The original coordinator and output proof are reused. */
    private fun tryTransientTapSwap(
        slotIndex:Int, moveAsync:((Boolean)->Unit)->Unit,
        canRelease:()->Boolean, onFinished:()->Unit,
        onVisualUnavailable:((String)->Boolean)?=null,
    ):Boolean {
        val traceId=StageExchangeTelemetry.currentNativeTapId()
        fun reject(reason:String):Boolean {
            StageExchangeTelemetry.nativeEvent("snapshot_skip:$reason",traceId)
            return false
        }
        val powerSave=context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        val rejection=when {
            !workspaceVisible -> "workspace_hidden"
            suspended -> "workspace_suspended"
            sidebarCollapsed -> "sidebar_collapsed"
            groupInputBlocked -> "group_input_blocked"
            !android.animation.ValueAnimator.areAnimatorsEnabled() -> "system_animators_disabled"
            powerSave -> "power_saver"
            !WorkspaceWallpaperBackdrop.canCapture(context) -> "screen_not_interactive"
            swapAnimator.isRunning -> "workspace_preview_busy"
            transientTapSwap.isRunning -> "renderthread_flight_busy"
            liveSurfaceExchange?.isRunning==true -> "live_surface_flight_busy"
            else -> null
        }
        if(rejection!=null)return reject(rejection)
        val g=geometry()
        val task=mainTaskPresenter.currentTaskId() ?: return reject("main_task_missing")
        val slot=slotWindows.getOrNull(slotIndex) ?: return reject("slot_view_missing")
        val state=slotCoordinator?.slotStates()?.getOrNull(slotIndex) as? SlotState.Occupied
            ?: return reject("slot_not_occupied")
        val host=records.firstOrNull { it.title=="NeXtepContentPanel" }?.view as? FrameLayout
            ?: return reject("content_panel_missing")
        val origin=IntArray(2)
        host.rootView.getLocationOnScreen(origin)
        if(!StageConcurrentNativeTrialPolicy.fullScreenHost(origin[0],origin[1],
                host.rootView.width,host.rootView.height,g.screenWidth,g.screenHeight)) return reject("content_panel_not_fullscreen")
        val source=Rect()
        if(!slot.view.getScreenVisibleRect(source))return reject("slot_bounds_missing")
        val main=Rect(g.contentLeft,g.contentTop,g.contentRight,g.contentBottom)
        val slotCanvas=slot.sourceBounds()
        val slotCrop=Rect(0,0,slotCanvas.width,slotCanvas.height)
        val mainCrop=Rect(0,0,g.screenWidth,g.screenHeight)
        val incoming=TaskSurfaceCompat.mirrorSourceStamp(state.taskId,state.displayId)
            ?: return reject("incoming_mirror_stamp_missing")
        val outgoing=TaskSurfaceCompat.mirrorSourceStamp(task,0)
            ?: return reject("outgoing_mirror_stamp_missing")
        val atStart=slot.outputFrameMark() ?: return reject("slot_output_mark_missing")
        if(!TaskSurfaceCompat.isPresentationSettled(task,g))return reject("main_presentation_unsettled")
        fun same(a:VirtualDisplaySlot.OutputFrameMark,b:VirtualDisplaySlot.OutputFrameMark?):Boolean =
            b!=null && StageConcurrentNativeTrialPolicy.sameOutput(a.displayId,b.displayId,
                a.texture,b.texture,a.surface,b.surface)
        var outputAtConfirmedMove:VirtualDisplaySlot.OutputFrameMark?=null
        var reason=""
        var slotLatchRequested=false
        fun requestSlotLatch(){
            if(slotLatchRequested)return
            slotLatchRequested=true
            slot.view.textureView.postInvalidateOnAnimation()
            StageExchangeTelemetry.nativeEvent("snapshot_slot_latch_requested",traceId)
        }
        fun verifiedMainEndpoint():Boolean =
            canRelease() &&
                (slotCoordinator?.slotStates()?.getOrNull(slotIndex) as? SlotState.Occupied)?.taskId==task &&
                mainTaskPresenter.currentTaskId()==state.taskId &&
                TaskSurfaceCompat.isPresentationSettled(state.taskId,g)
        activeSwapViewport=g
        previewCapturePending=true
        deferredSwapGeometryRefresh=false
        homePreviewGeneration.incrementAndGet()
        val started=transientTapSwap.start(
            host,source,main,slotWindows.mapIndexedNotNull { index,other ->
                if(index==slotIndex || slotCoordinator?.slotStates()?.getOrNull(index) !is SlotState.Occupied)
                    null else Rect().takeIf { other.view.getScreenVisibleRect(it) }
            },incoming,outgoing,slotCrop,mainCrop,
            WorkspaceWallpaperBackdrop.crop(wallpaperBitmap,0,0,frostStrength),
            valid={
                workspaceVisible && !suspended && !groupInputBlocked &&
                    mainTaskPresenter.currentTaskId()==task &&
                    slotCoordinator?.slotStates()?.getOrNull(slotIndex)==state &&
                    StageSwapViewportHoldPolicy.retain(true,g,geometry()) &&
                    WorkspaceWallpaperBackdrop.canCapture(context) &&
                    same(atStart,slot.outputFrameMark()) &&
                    incoming.sameSource(TaskSurfaceCompat.mirrorSourceStamp(state.taskId,state.displayId)) &&
                    outgoing.sameSource(TaskSurfaceCompat.mirrorSourceStamp(task,0))
            },
            moveAsync={ done ->
                moveAsync { ok ->
                    if(ok){
                        // Capture the monotonic producer-frame counter at ACK even
                        // when the output Surface is temporarily unavailable. Keep
                        // the pre-move producer identity as a strict fallback.
                        val ack=slot.outputFrameMark()
                        val ackSequence=slot.contentFrameSequence
                        val earlier=slotCoordinator?.confirmedOutputMark(slotIndex,task)
                        outputAtConfirmedMove=earlier?.takeIf { same(it,ack) } ?: ack
                            ?: atStart.copy(sequence=ackSequence)
                        // Request one traversal as soon as ownership is confirmed.
                        // This is only a wake-up hint, never proof of a fresh buffer.
                        val ownerStillMatches=
                            (slotCoordinator?.slotStates()?.getOrNull(slotIndex) as? SlotState.Occupied)?.taskId==task &&
                                mainTaskPresenter.currentTaskId()==state.taskId
                        if(ownerStillMatches)requestSlotLatch()
                    }
                    done(ok)
                }
            },
            liveReady={
                val mark=outputAtConfirmedMove
                val now=slot.outputFrameMark()
                val occupied=slotCoordinator?.slotStates()?.getOrNull(slotIndex) as? SlotState.Occupied
                val wait=when{
                    !canRelease()->"exchange_pending"
                    occupied?.taskId!=task->"slot_owner"
                    mark==null || now==null || !same(mark,now) || now.sequence<=mark.sequence->"slot_buffer"
                    mainTaskPresenter.currentTaskId()!=state.taskId->"main_owner"
                    !TaskSurfaceCompat.isPresentationSettled(state.taskId,g)->"main_geometry"
                    else->"ready"
                }
                // If ACK did not request it, retain the original one-shot fallback.
                if(wait=="slot_buffer")requestSlotLatch()
                if(wait!=reason){reason=wait;StageExchangeTelemetry.nativeEvent("snapshot_wait="+wait,traceId)}
                wait=="ready"
            },
            trace={StageExchangeTelemetry.nativeEvent(it,traceId)},
            onVisualStart={slot.view.beginExchangePreview()},
            onVisualEnd={slot.view.endExchangePreview()},
            mainReady={verifiedMainEndpoint()},
            // Main panel is transparent over the desktop wallpaper, not
            // frosted like the rail. Reuse only a matching cached frame;
            // no per-exchange wallpaper capture/blur node or stale rotation.
            mainBackdrop=matchingWallpaperFrame(g)?.let {
                WorkspaceWallpaperBackdrop.crop(it,0,0,0,wallpaperRotation,g.displayRotation)
            },
            onVisualUnavailable={ reason ->
                previewCapturePending=false
                activeSwapViewport=g
                val accepted=runCatching { onVisualUnavailable?.invoke(reason) ?: false }
                    .getOrDefault(false)
                if(!accepted) StageExchangeTelemetry.nativeEvent(
                    "snapshot_workspace_preview_rejected:$reason",traceId)
                accepted
            },
            onFinished={
                previewCapturePending=false
                activeSwapViewport=null
                val flush=deferredSwapGeometryRefresh
                deferredSwapGeometryRefresh=false
                StageExchangeTelemetry.endNativeTap("snapshot_trial_finished",traceId)
                if(flush && workspaceVisible && !suspended)scheduleWindowRefresh()
                onFinished()
                if(workspaceVisible && !suspended)scheduleStageFocus()
            }
        )
        if(!started){
            previewCapturePending=false;activeSwapViewport=null
            return reject("attempt_start_rejected")
        }
        return true
    }

    private fun animateSwap(
        sourceIndex: Int, targetIndex: Int?, drag: SlotTaskDrag?,
        move: () -> Boolean, onFinished: () -> Unit,
        asyncMove: (((Boolean) -> Unit) -> Unit)? = null,
        canRelease: () -> Boolean = { true },
        attemptNative: Boolean = true,
    ) {
        // A user swap takes precedence over optional wallpaper preparation.
        // Invalidate queued work/recovery, but retain any already ready texture.
        gpuWallpaperEpoch.incrementAndGet()
        // The snapshot/mirror must sample the settled physical rail bounds.
        // Invalidate a queued or running layout-entry cue before capture.
        cancelRailLayoutMotion()
        var tapFallbackFinish: (() -> Unit)? = null
        var workspaceTapTraceId: Long? = null
        // P0 (3731.mp4 + PR51 real-device feedback): do not capture a
        // TextureView bitmap or await a screenshot before the real task move.
        // On the phased tap path the coordinator already owns an asynchronous,
        // task-identity-preserving exchange session and its rollback.
        // Launch it immediately. The old preview animation first waited for
        // off-thread capture, then delayed the Binder move until animation end.
        // This route draws no extra frame and schedules no background polling.
        if (StageSwapTapLatencyPolicy.useImmediatePhasedPath(
                isTap = drag == null && targetIndex == null,
                asynchronousTaskMove = asyncMove != null,
                occupiedSlot = slotCoordinator?.slotStates()?.getOrNull(sourceIndex) is SlotState.Occupied
            )) {
            swapCapture.cancel()
            activeSwapViewport = null
            previewCapturePending = false
            StageExchangeTelemetry.beginNativeTap()
            val tapTraceId=StageExchangeTelemetry.currentNativeTapId()
            workspaceTapTraceId=tapTraceId
            var fallbackFinished=false
            tapFallbackFinish = {
                if (!fallbackFinished) {
                    fallbackFinished=true
                    StageExchangeTelemetry.endNativeTap("workspace_preview_fallback",tapTraceId)
                    onFinished()
                }
            }
            NeXtepLog.info("swap_latency", "Phased tap swap: select the best available visual backend")
            val phasedMove = checkNotNull(asyncMove)
            // v88 animation cascade:
            // 1) Real task-owned SurfaceControl mirrors (live GPU pixels).
            // 2) Bounded RenderThread flight of short-lived observed task frames.
            // 3) The existing WorkspaceSwapAnimator path below, which captures
            //    only transient previews and hands back to the real task buffers.
            // The real task coordinator remains the sole owner of migration,
            // identity checks and rollback. A rejected visual backend must not
            // turn a successful task swap into an abrupt no-animation fallback.
            val workspacePreviewFallback: (String) -> Boolean = { reason ->
                StageExchangeTelemetry.nativeEvent(
                    "tap_workspace_preview_fallback:$reason",tapTraceId)
                runCatching {
                    previewCapturePending=false
                    activeSwapViewport=geometry()
                    animateSwapPrepared(sourceIndex,targetIndex,drag,move,
                        tapFallbackFinish ?: onFinished,null,asyncMove,canRelease,tapTraceId)
                    true
                }.onFailure {
                    if(tapTraceId!=null) StageExchangeTelemetry.nativeEvent("workspace_preview_exception",tapTraceId)
                }.getOrDefault(false)
            }
            val renderThreadFallback: (String) -> Boolean = { reason ->
                StageExchangeTelemetry.nativeEvent(
                    "tap_renderthread_fallback:$reason",tapTraceId)
                val accepted=tryTransientTapSwap(sourceIndex,phasedMove,canRelease,
                    onFinished,workspacePreviewFallback)
                if(!accepted) {
                    previewCapturePending=false
                    activeSwapViewport=geometry()
                    workspacePreviewFallback(reason)
                } else true
            }
            if (StagePrivateDualTaskFlightGate.enabled() &&
                tryConcurrentLiveSwap(sourceIndex,phasedMove,canRelease,onFinished,
                    onVisualUnavailable=renderThreadFallback)) return
            if (StageTransientTapPolicy.snapshotFallbackEnabled() &&
                tryTransientTapSwap(sourceIndex,phasedMove,canRelease,
                    onFinished,workspacePreviewFallback)) return

            StageExchangeTelemetry.nativeEvent("tap_animation_fallback:workspace_preview",tapTraceId)
            NeXtepLog.warn("swap_latency",
                "Live GPU and RenderThread preflight unavailable; falling through to bounded workspace preview")
        }
        val task=if(targetIndex==null)mainTaskPresenter.currentTaskId() else null
        val effectiveOnFinished=tapFallbackFinish ?: onFinished
        // Native two-layer flight is attempted BEFORE any bitmap capture.
        // It mirrors the real existing SurfaceFlinger task + rail surfaces.
        // No screenshot loop, no root/task reparent, no app restart.
        // P0: PR50 delayed real task migration until 94% of a 280-360ms
        // native animation and could then await another 600ms for live buffers.
        // Its extra full-screen WM surface also costs an attach/render pass.
        // Do NOT attempt it in user builds until a separate device-specific
        // opt-in passes FPS, input-latency and power checks. Restore the
        // already existing non-native exchange path without touching tasks.
        if (attemptNative && StageNativeSwapPerformanceGate.enabledByDefault() &&
            tryLiveSurfaceExchange(sourceIndex,targetIndex,drag,task,move,
                onFinished,asyncMove,canRelease) {
                // The vendor denied a live surface or the temporary full-screen
                // compositor root could not attach. Run the original protected
                // path exactly ONCE; do not recursively retry native mirroring.
                animateSwap(sourceIndex,targetIndex,drag,move,onFinished,
                    asyncMove,canRelease,attemptNative=false)
            }) return
        if(task==null || !workspaceVisible || suspended || !WorkspaceWallpaperBackdrop.canCapture(context)) {
            animateSwapPrepared(sourceIndex,targetIndex,drag,move,effectiveOnFinished,null,asyncMove,canRelease,workspaceTapTraceId);return
        }
        // Mark the whole PREVIEW-PREPARATION interval, not only the
        // ValueAnimator. Without this guard, a rail count refresh during the
        // async ColorOS task capture can cancel the entire exchange before the
        // first drawable frame, producing the observed instant content swap.
        swapCapture.cancel()
        val capturedGeometry=geometry()
        val states=slotCoordinator?.slotStates().orEmpty().toList()
        activeSwapViewport = capturedGeometry
        previewCapturePending = true
        swapCapture.request(
            load={
                // A ColorOS system image can expose a live main task without a
                // public Shell layer-capture path. Try the owned task leash
                // first, then the actually displayed main viewport (never an
                // uncropped whole screen). Both run off the UI thread and
                // platform secure-layer exclusions remain in effect.
                TaskSurfaceCompat.capturePreview(task,
                    Rect(0,0,capturedGeometry.screenWidth,capturedGeometry.screenHeight))
                    ?: TaskSurfaceCompat.captureDisplayPreview(
                        Rect(capturedGeometry.contentLeft,capturedGeometry.contentTop,
                            capturedGeometry.contentRight,capturedGeometry.contentBottom))
            },
            valid={workspaceVisible && !suspended &&
                StageSwapViewportHoldPolicy.retain(true,capturedGeometry,geometry()) &&
                mainTaskPresenter.currentTaskId()==task && slotCoordinator?.slotStates().orEmpty()==states},
            accept={
                previewCapturePending = false
                animateSwapPrepared(sourceIndex,targetIndex,drag,move,effectiveOnFinished,it,asyncMove,canRelease,workspaceTapTraceId)
            },
            onCancel={
                previewCapturePending = false
                activeSwapViewport = null
                val flush = deferredSwapGeometryRefresh
                deferredSwapGeometryRefresh = false
                if (flush) scheduleWindowRefresh()
                effectiveOnFinished()
            },
        )
    }

    /**
     * Real native dual-card exchange for ColorOS variants that expose live
     * compositor mirroring. A failed capability check has zero side effects;
     * the original phased/bitmap route remains available for older devices.
     *
     * The real task move is still driven by SlotTaskCoordinator. We do not
     * mutate input coordinates, the original task leashes or producer displays.
     */
    private fun tryLiveSurfaceExchange(
        sourceIndex: Int, targetIndex: Int?, drag: SlotTaskDrag?, task: Int?,
        move: () -> Boolean, onFinished: () -> Unit,
        asyncMove: (((Boolean) -> Unit) -> Unit)?,
        canRelease: () -> Boolean,
        onUnavailable: () -> Unit,
    ): Boolean {
        if (drag != null || targetIndex != null || asyncMove == null || task == null ||
            !workspaceVisible || suspended || sidebarCollapsed || groupInputBlocked ||
            !android.animation.ValueAnimator.areAnimatorsEnabled() ||
            context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true ||
            !WorkspaceWallpaperBackdrop.canCapture(context) ||
            liveSurfaceExchange?.isRunning == true || nativeSwapHost != null) return false
        val g = geometry()
        val slot = slotWindows.getOrNull(sourceIndex) ?: return false
        val state = slotCoordinator?.slotStates()?.getOrNull(sourceIndex) as? SlotState.Occupied
            ?: return false
        val source = Rect()
        if (!slot.view.getScreenVisibleRect(source)) return false
        val main = Rect(g.contentLeft,g.contentTop,g.contentRight,g.contentBottom)
        val type=SystemUiWindowTypeResolver.resolve() ?: return false

        // PR49 accidentally used NeXtepContentPanel as host: that window is
        // ONLY contentWidth x contentHeight, while its native gate requires a
        // full physical display. Every phone then failed host_not_full_display
        // before the SurfaceFlinger flight could even start.
        // Instead mount a ONE-SHOT, non-touchable screen-wide root, with zero
        // idle frame callbacks, and remove it immediately on finish/failure.
        val overlay=StageNativeSwapFullscreenHost.open(context,windowManager,type,
            g.screenWidth,g.screenHeight) ?: return false
        val token=++nativeSwapAttempt
        nativeSwapHost=overlay
        val originalSlotFrame=slot.contentFrameSequence
        val incomingId=state.taskId
        nativeSwapPreparingSlot=slot
        slot.view.beginExchangePreview()
        activeSwapViewport=g
        deferredSwapGeometryRefresh=false
        previewCapturePending=true
        swapCapture.cancel()

        var attempted=false
        val deadline = Runnable {
            if (attempted || token!=nativeSwapAttempt || nativeSwapHost !== overlay)
                return@Runnable
            attempted=true
            val stale=!workspaceVisible || suspended || groupInputBlocked ||
                mainTaskPresenter.currentTaskId()!=task ||
                (slotCoordinator?.slotStates()?.getOrNull(sourceIndex) as? SlotState.Occupied)?.taskId!=incomingId ||
                !StageSwapViewportHoldPolicy.retain(true,g,geometry())
            if (stale) {
                NeXtepLog.warn("native_swap","Temporary full-screen host became stale before mirror attach")
            }
            val native=if(stale) null else StageLiveSurfaceExchange.create(
                host=overlay.view,sourceSlotView=slot.view,
                sourceTaskId=state.taskId,sourceDisplayId=state.displayId,
                mainTaskId=task,
                sourceBounds=source,mainBounds=main,
                otherSlotBounds=slotWindows.mapIndexedNotNull { index, other ->
                    if(index==sourceIndex ||
                        slotCoordinator?.slotStates()?.getOrNull(index) !is SlotState.Occupied
                    ) null else Rect().takeIf {
                        other.view.getScreenVisibleRect(it)
                    }
                },
                fullScreenWidth=g.screenWidth,fullScreenHeight=g.screenHeight,
                backdrop=WorkspaceWallpaperBackdrop.crop(wallpaperBitmap,0,0,frostStrength),
            )
            fun restore() {
                if (nativeSwapHost === overlay) clearNativeSwapHost()
                else slot.view.endExchangePreview()
                activeSwapViewport=null
                previewCapturePending=false
                val flush=deferredSwapGeometryRefresh
                deferredSwapGeometryRefresh=false
                if (flush && workspaceVisible && !suspended) scheduleWindowRefresh()
            }
            if (native==null) {
                restore()
                if (!stale) onUnavailable()
                else onFinished()
                return@Runnable
            }
            val started=native.start(
                durationMs=SystemUiRuntime.swapAnimationDurationMs(),
                moveAsync={done -> asyncMove { ok -> done(ok) }},
                move=move,
                liveReady={
                    val now=slotCoordinator?.slotStates()?.getOrNull(sourceIndex)
                    now is SlotState.Occupied &&
                        now.taskId==task && slot.view.textureView.isAvailable &&
                        slot.contentFrameSequence>originalSlotFrame &&
                        mainTaskPresenter.currentTaskId()==incomingId &&
                        TaskSurfaceCompat.isPresentationSettled(incomingId,g)
                },
                canRelease=canRelease,
                onFinished={
                    restore()
                    liveSurfaceExchange=null
                    onFinished()
                    if (workspaceVisible && !suspended) scheduleStageFocus()
                },
            )
            if (!started) {
                restore()
                onUnavailable()
                return@Runnable
            }
            liveSurfaceExchange=native
            previewCapturePending=false
            NeXtepLog.info("native_swap",
                "Full-screen ephemeral SurfaceFlinger host ACTIVE task=$task slot=$sourceIndex from=$source to=$main")
        }
        // The ViewRoot surface does not exist until the first traversal.
        // Do not create/parent mirrors immediately after addView(): on ColorOS
        // this produces another silent null and yet another instant swap.
        overlay.afterFirstCommittedFrame { deadline.run() }
        return true
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
        tapTraceId: Long? = null,
    ) {
        // Only the initiating tap owns these bounded diagnostic events.
        // A later asynchronous fallback must not annotate a successor tap.
        fun tracePreview(event: String) {
            if(tapTraceId!=null) StageExchangeTelemetry.nativeEvent(event,tapTraceId)
        }
        fun withoutPreview(reason: String) {
            tracePreview("workspace_no_visual:$reason")
            activeSwapViewport = null
            previewCapturePending = false
            if (deferredSwapGeometryRefresh) {
                deferredSwapGeometryRefresh = false
                scheduleWindowRefresh()
            }
            if(capturedMainPreview?.isRecycled==false)capturedMainPreview.recycle()
            if (asyncMove != null) asyncMove { onFinished(); scheduleStageFocus() }
            else try { move() } finally { onFinished(); scheduleStageFocus() }
        }
        if (!workspaceVisible || suspended || !WorkspaceWallpaperBackdrop.canCapture(context)) {
            withoutPreview(when {
                !workspaceVisible -> "workspace_hidden"
                suspended -> "workspace_suspended"
                else -> "capture_unavailable"
            })
            return
        }
        // An in-flight occupied/ready callback can temporarily recalculate
        // the rail thickness before it is drawn. Use the geometry captured at
        // the user's tap, not an intermediate layout, for the card flight.
        val geometry = activeSwapViewport?.takeIf {
            StageSwapViewportHoldPolicy.retain(true,it,geometry())
        } ?: geometry()
        val previewHost = contentHighlightView?.parent as? FrameLayout
        val sourceSlot = slotWindows.getOrNull(sourceIndex)
        val sourceRect = Rect()
        if (previewHost == null || sourceSlot == null || !sourceSlot.view.getScreenVisibleRect(sourceRect)) {
            withoutPreview(when {
                previewHost==null -> "host_missing"
                sourceSlot==null -> "source_slot_missing"
                else -> "source_bounds_missing"
            })
            return
        }
        val states = slotCoordinator?.slotStates().orEmpty()
        val sourceState = states.getOrNull(sourceIndex)
        val parkingMain = drag == null && targetIndex == null && sourceState is SlotState.Ready
        if (!parkingMain && sourceState !is SlotState.Occupied) {
            withoutPreview("source_not_occupied")
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
                withoutPreview("target_bounds_missing")
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
        if (sourcePreview == null) {
            // No legitimate source image: never invent a static task image.
            // Keep normal task migration instead of rendering stale pixels.
            if (targetPreview !== sourcePreview && targetPreview?.isRecycled == false)
                targetPreview.recycle()
            NeXtepLog.warn("swap_morph", "Source TextureView preview missing; genuine task migration retained")
            withoutPreview("source_preview_missing")
            return
        }
        if (needsTargetPreview && targetPreview == null) {
            // Crucial ColorOS fallback: a missing main-task capture must NOT
            // disable the visible small -> large card expansion. Render the
            // real occupied TextureView as a one-way moving card, then hand
            // ownership back to both real tasks. Never retain the bitmap.
            NeXtepLog.info("swap_morph",
                "Main preview unavailable; continue source-only uniform scale with live task handoff")
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
        // Click-to-exchange is a cross-device motion. The rectangle geometry
        // is acquired from current actual workspace bounds in every display
        // shape: phones, folded/unfolded foldables and tablets. No model gate.
        // When capture/geometry is missing the proven fixed-frame fallback
        // remains available, without touching the real task lifecycle.
        // User-reduced animation, power saver and HOME transitions are
        // authoritative. All supported form factors use the same geometry gate.
        // Scale on the actual slot TAP path, regardless of whether the move
        // is phased/async or a guarded legacy synchronous transaction. The old
        // asyncMove != null gate silently selected a frozen 1ms preview in
        // other valid tap paths, even though user requested live card motion.
        val animateTap = drag == null && !parkingMain &&
            android.animation.ValueAnimator.areAnimatorsEnabled() &&
            context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode != true &&
            StageCrossDeviceSwapMotionPolicy.canAnimateTap(
                StageCrossDeviceSwapMotionPolicy.Bounds(start.left, start.top, start.right, start.bottom),
                StageCrossDeviceSwapMotionPolicy.Bounds(to.left, to.top, to.right, to.bottom),
                sourcePreview != null,
            )
        tracePreview(when {
            drag!=null -> "workspace_preview_selected:drag"
            !animateTap -> "workspace_preview_selected:fixed"
            targetPreview==null -> "workspace_preview_selected:source_only"
            else -> "workspace_preview_selected:dual"
        })
        // Reuse the same certified or scored snapshot corridor during the
        // final fallback. Keep planning one-shot, with a separate bounded
        // reserve for the ordinary paired corridor if certification fails.
        // Missing bounds, source-only, drag, HOME or a budget miss retain
        // the existing safe fallback; no extra capture, worker or idle work.
        val planningStart=System.nanoTime()
        val sharedDeadline=planningStart+2_000_000L
        val pairedDeadline=planningStart+4_000_000L
        val protectedBounds=mutableListOf<RectF>()
        var protectedGeometryComplete=true
        val sharedMotion=if(animateTap && targetIndex==null && targetPreview!=null &&
            previewHost.width==geometry.screenWidth && previewHost.height==geometry.screenHeight) {
            val rootOrigin=IntArray(2)
            previewHost.getLocationOnScreen(rootOrigin)
            if(rootOrigin[0]!=0 || rootOrigin[1]!=0) null else {
                slotWindows.forEachIndexed { index, other ->
                    if(index!=sourceIndex && states.getOrNull(index) is SlotState.Occupied) {
                        val bounds=Rect()
                        if(other.view.getScreenVisibleRect(bounds)) protectedBounds.add(RectF(bounds))
                        else protectedGeometryComplete=false
                    }
                }
                fun box(bounds:RectF)=StageSnapshotMotion.Box(bounds.left,bounds.top,bounds.right,bounds.bottom)
                if(!protectedGeometryComplete || System.nanoTime()>=pairedDeadline)null
                else {
                    val a=box(from);val z=box(to)
                    val screen=StageSnapshotMotion.Box(0f,0f,geometry.screenWidth.toFloat(),geometry.screenHeight.toFloat())
                    val excluded=protectedBounds.map(::box)
                    runCatching {
                        val certified=if(System.nanoTime()<sharedDeadline)
                            StageSnapshotMotion.sharedFlights(a,z,
                                sourcePreview.width,sourcePreview.height,targetPreview.width,targetPreview.height,
                                screen,excluded){System.nanoTime()<sharedDeadline}
                            else null
                        certified ?: StageSnapshotMotion.pairedFlights(a,z,
                            sourcePreview.width,sourcePreview.height,targetPreview.width,targetPreview.height,
                            screen,excluded){System.nanoTime()<pairedDeadline}
                    }.getOrNull()
                }
            }
        } else null
        if(sharedMotion?.usesSharedClock==true)tracePreview("workspace_preview_shared_certified_route")
        else if(sharedMotion!=null)tracePreview("workspace_preview_shared_snapshot_corridor")
        val previewSlots = listOfNotNull(sourceSlot, targetSlot)
        val y700NativeHandoff =
            io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy
                .wideLandscapeTablet(geometry.screenWidth, geometry.screenHeight)
        val stableTabletFrame = StageTabletSwapLiveGate()
        var sourceFrame = sourceSlot.contentFrameSequence
        var targetFrame = targetSlot?.contentFrameSequence ?: 0L
        previewSlots.forEach { it.view.beginExchangePreview() }
        activeSwapViewport = geometry
        deferredSwapGeometryRefresh = false
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
                // Move both temporary preview frames on EVERY supported
                // screen shape, while preserving each image's actual aspect
                // ratio. For invalid bounds, fall back to fixed safe exchange.
                // Task ownership, Surface handoff and input geometry remain
                // managed by the existing real display coordinator.
                freezeGeometry = drag == null && !animateTap,
                uniformTapMorph = animateTap,
                sharedMotion = sharedMotion,
                protectedBounds = if(protectedGeometryComplete) protectedBounds else emptyList(),
                // The fallback must know the real endpoint role even when
                // no paired route can be planned (slot-slot and parking too).
                sourceIsPreview = !parkingMain,
                targetIsPreview = parkingMain || targetIndex != null,
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
                    val framesReady = sourceReady && targetReady
                    if (!y700NativeHandoff) framesReady
                    else if (!stableTabletFrame.ready(android.os.SystemClock.uptimeMillis(), framesReady)) false
                    else {
                        // After at least one new real buffer and a brief stable
                        // dwell, verify task ownership ONCE the gate opens.
                        // A stale frame from the previous virtual display can
                        // otherwise be mistaken for the destination task.
                        // One Binder snapshot for the entire handoff, not a
                        // new getRunningTasks call per card (or per frame).
                        val actualTasks = taskRepository.runningTasks()
                        val actualOwners = actualTasks.map {
                            StageTabletSwapOwnershipPolicy.Owner(it.taskId, it.displayId)
                        }
                        fun slotOwnerCorrect(index: Int): Boolean =
                            when (val state = currentStates.getOrNull(index)) {
                                is SlotState.Occupied ->
                                    StageTabletSwapOwnershipPolicy.settled(
                                        state.taskId, state.displayId, actualOwners)
                                is SlotState.Ready ->
                                    StageTabletSwapOwnershipPolicy.settled(
                                        null, state.displayId, actualOwners)
                                else -> false
                            }
                        val sourceOwner = slotOwnerCorrect(sourceIndex)
                        val targetOwner = if (targetIndex != null) slotOwnerCorrect(targetIndex)
                            else if (parkingMain) true
                            else incomingTaskId != null &&
                                StageTabletSwapOwnershipPolicy.settled(
                                    incomingTaskId, android.view.Display.DEFAULT_DISPLAY,
                                    actualOwners)
                        if (!sourceOwner || !targetOwner) {
                            // Ownership changed mid-handoff; require a fresh
                            // stable dwell, never release on an old ready frame.
                            stableTabletFrame.ready(android.os.SystemClock.uptimeMillis(), false)
                        }
                        sourceOwner && targetOwner
                    }
                },
                onFinished = {
                    previewSlots.forEach { it.view.endExchangePreview() }
                    activeSwapViewport = null
                    val flushLayout = deferredSwapGeometryRefresh
                    deferredSwapGeometryRefresh = false
                    onFinished()
                    if (flushLayout && workspaceVisible && !suspended) {
                        // One final frame after the exchange, not any polling
                        // or per-frame requestLayout. Keep the real task IDs.
                        backdropHandler.post {
                            if (workspaceVisible && !suspended && !swapAnimator.isRunning) {
                                val finalGeometry = geometry()
                                applyStageStack(finalGeometry)
                                applyWindowGeometry(finalGeometry, refreshControls = false)
                            }
                        }
                    }
                    scheduleStageFocus()
                    if (parkingMain) backdropHandler.postDelayed({
                        if (!suspended && workspaceVisible) captureHomePreview(currentGeometry())
                    }, HOME_PREVIEW_SETTLE_DELAY_MS)
                },
            )
        } catch (error: Throwable) {
            swapAnimator.cancel()
            activeSwapViewport = null
            deferredSwapGeometryRefresh = false
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
                // A late result while hidden or locked must not refresh the viewport.
                if (suspended || !workspaceVisible) {
                    snapshot?.recycle()
                    return@post
                }
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

    /**
     * Untransformed layout rectangles only. StageSparseRailReflowAnimator
     * samples the still-running compositor properties exactly once. Passing
     * rendered bounds here as well would apply the old scale/offset twice.
     */
    private fun nominalRailFrames(
        geometry: WorkspaceGeometry,
    ): Map<View, io.github.x647368maker.atomicworkbench.workspace.StageSparseRailReflowPolicy.Bounds> =
        slotWindows.mapNotNull { slot ->
            val view = slot.view
            if (!view.isWorkspaceCardVisible()) return@mapNotNull null
            val box = view.layoutParams as? FrameLayout.LayoutParams ?: return@mapNotNull null
            if (box.width <= 1 || box.height <= 1) return@mapNotNull null
            view to io.github.x647368maker.atomicworkbench.workspace.StageSparseRailReflowPolicy.Bounds(
                (geometry.sidebarLeft + box.marginStart).toFloat(),
                (geometry.sidebarTop + box.topMargin).toFloat(),
                box.width.toFloat(), box.height.toFloat(),
            )
        }.toMap()

    private fun applyStageStack(geometry: WorkspaceGeometry) {
        if (slotWindows.isEmpty()) return
        if (keepActiveSwapForViewport(geometry)) {
            deferredSwapGeometryRefresh = true
            return
        }
        val oldCount = shownWindowCount
        val oldRailGeometry = previousRailGeometry
        val oldCardFrames = oldRailGeometry?.let { nominalRailFrames(it) }.orEmpty()
        // If the physical screen or layout mode changed, never preserve a stale
        // scale/translation into the next touchable workspace.
        if (oldRailGeometry != null &&
            !io.github.x647368maker.atomicworkbench.workspace.StageSparseRailReflowPolicy
                .supported(oldRailGeometry, geometry, 2, 3)) sparseRailMotion.cancel()
        syncCoreViewport()
        val selectedProfile = viewport.layout().let { selected ->
            // The no-task compact rail is ONLY a parent/add affordance. Hidden
            // producer hosts remain 1x1 until an occupied state chooses real bounds.
            if (viewport.activeCount > 0 && !sidebarCollapsed) selected.copy(
                geometry = if (smartRail.hidden) viewport.geometry(sidebarSide) else geometry
            ) else selected
        }
        val right = logicalSidebarSide(geometry) == SidebarSide.RIGHT
        val states = slotCoordinator?.slotStates().orEmpty()
        val occupied = List(WORKSPACE_SLOT_COUNT) { states.getOrNull(it) is SlotState.Occupied }
        val profile = selectedProfile
        val bounds = io.github.x647368maker.atomicworkbench.workspace.StageLiveCardLayout.bounds(
            profile, slotWindows.map { it.geometry() }, occupied, stageOrder,
            allowPhoneSparse = io.github.x647368maker.atomicworkbench.workspace.StageSparseRailReflowPolicy
                .trialModel(android.os.Build.MODEL))
        slotWindows.forEachIndexed { index, slot ->
            slot.setLayoutProfile(profile)
            val box = if (occupied[index]) bounds[index] else io.github.x647368maker.atomicworkbench.workspace.WorkspaceLayoutProfile.Slot(1, 1, 0, 0)
            if (cinemaSlot == null) {
                val x = if (!occupied[index]) 0 else if (geometry.railAtTop) box.left else (profile.geometry.sidebarWidth - box.width) / 2
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
            val compactAddTop = io.github.x647368maker.atomicworkbench.workspace.StageTabletRailVisualPolicy
                .addTop(profile.geometry, bounds, occupied, dp(44).toInt(), dp(8).toInt())
            layoutParams = FrameLayout.LayoutParams(dp(44).toInt(), dp(44).toInt(),
                if (compactAddTop != null) Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else if (geometry.railAtTop) Gravity.END or Gravity.BOTTOM
                else if (shownWindowCount == 0) Gravity.CENTER
                else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                if (compactAddTop != null) topMargin = compactAddTop
                else bottomMargin = if (geometry.railAtTop) dp(4).toInt() else dp(12).toInt()
                marginEnd = if (geometry.railAtTop) dp(10).toInt() else 0
            }
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
                cameraDistance = dp(900)
                rotationY = pose.tilt
                // The running FLIP also owns its centre pivot. Changing it
                // during a harmless refresh would move the rendered card
                // and invalidate the animator's next interruption sample.
                if (!sparseRailMotion.isAnimating(this)) {
                    pivotX = if (geometry.railAtTop) bounds[index].width / 2f else if (right) 0f else bounds[index].width.toFloat()
                    pivotY = bounds[index].height / 2f
                    scaleX = pose.scale
                    scaleY = pose.scale
                    translationX = pose.x
                    translationY = pose.y
                    alpha = pose.alpha
                }
                elevation = pose.z
                // The live app fills the card. Its outline owns all four rounded corners.
                setBackgroundColor(Color.TRANSPARENT)
                invalidateOutline()
                clipToOutline = true
            }
        }
        sidebarView?.requestLayout()
        previousRailGeometry = profile.geometry
        if (countChanged && workspaceVisible && !suspended) onViewportChanged()
        if (countChanged) {
            // 190ms, once per add/remove only. Never animate the main app,
            // fixed menu, bottom '+' action, virtual display or touch bridge.
            sparseRailMotion.animate(
                oldRailGeometry, profile.geometry, oldCount, shownWindowCount,
                oldCardFrames, nominalRailFrames(profile.geometry),
                enabled = workspaceVisible && !suspended && !sidebarCollapsed &&
                    !smartRail.hidden && !groupInputBlocked && !workgroups.isSwitching() &&
                    cinemaSlot == null && !swapAnimator.isRunning &&
                    slotCoordinator?.isGroupRestoreReady() == true,
            )
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
                visibility = View.GONE
            }
            // Focus is the centered real task. Avoid a debug-looking cyan frame.
            contentHighlightView?.foreground = null
        }.onFailure { NeXtepLog.warn("stage_focus", "Foreground decoration unavailable", it) }
    }

    private fun refreshWallpaperBackdrop(attempt: Int = 0) {
        // Ignore delayed callbacks after hide/lock; no invisible bitmap capture.
        if (!workspaceVisible || suspended) return
        val geometry = geometry()
        // Y700 wide panel: let the same native wallpaper surface show through
        // both translucent panels. Duplicating screenshots over the live
        // wallpaper creates visible seams after physical rotation.
        if (io.github.x647368maker.atomicworkbench.workspace.StageTabletWallpaperPolicy
                .useLiveSystemWallpaper(geometry)) {
            gpuWallpaper.clear()
            backdropGeneration.incrementAndGet()
            applyWallpaperBackdrop(geometry)
            return
        }
        // Use the last frame immediately; surface capture runs off the UI thread.
        applyWallpaperBackdrop(geometry)
        val previous = wallpaperBitmap
        val generation = backdropGeneration.incrementAndGet()
        // Reuse the existing capture worker, only on the settled capture.
        // No conversion on the first two refreshes, no timer or tap-time upload.
        val textureEpoch=gpuWallpaperEpoch.get()
        val prepareTexture=attempt>=BACKDROP_SETTLE_REFRESHES &&
            StagePrivateDualTaskFlightGate.enabled() &&
            StageConcurrentNativeTrialPolicy.allowed(workspaceVisible && !suspended,
                android.animation.ValueAnimator.areAnimatorsEnabled(),
                context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode!=false) &&
            !isExchangePending()
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
            ) {
                // A mismatched old screenshot cannot tile the Y700's new
                // physical orientation. Let its live system wallpaper show
                // through until a correctly oriented capture is available.
                if (io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy
                        .wideTablet(geometry.screenWidth, geometry.screenHeight)) null
                else WorkspaceWallpaperBackdrop.fit(previous, geometry.screenWidth, geometry.screenHeight)
            } else null
            // Publish the ordinary wallpaper independently of optional GPU
            // preparation. A slow render callback must not delay the backdrop.
            backdropHandler.post {
                if (generation != backdropGeneration.get() || !WorkspaceWallpaperBackdrop.canCapture(context)) {
                    bitmap?.recycle()
                    fitted?.recycle()
                    return@post
                }
                if (bitmap != null) {
                    gpuWallpaper.clear()
                    wallpaperBitmap = bitmap
                    wallpaperRotation = geometry.displayRotation
                } else if (fitted != null) {
                    gpuWallpaper.clear()
                    wallpaperBitmap = fitted
                    wallpaperRotation = geometry.displayRotation
                }
                // A capture may finish after a window closes/collapses. Crop for the
                // current layout, not the geometry that started this asynchronous job.
                applyWallpaperBackdrop(this@NeXtepWindowController.geometry())
                if(prepareTexture && bitmap!=null) {
                    // The published bitmap now belongs to the drawable cache.
                    // The capture rejection path can no longer recycle it
                    // concurrently with texture preparation.
                    queueWallpaperTexture(geometry,generation,textureEpoch,bitmap)
                }
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

    private fun queueWallpaperTexture(
        capturedGeometry:WorkspaceGeometry,generation:Int,textureEpoch:Int,frame:Bitmap,
        attempt:Int=0,
    ) {
        val key=wallpaperKey(capturedGeometry)
        if(!workspaceVisible || suspended || isExchangePending() ||
            generation!=backdropGeneration.get() ||
            textureEpoch!=gpuWallpaperEpoch.get() ||
            wallpaperBitmap!==frame || frame.isRecycled ||
            frame.width!=key.width || frame.height!=key.height ||
            wallpaperKey(geometry())!=key ||
            !StagePrivateDualTaskFlightGate.enabled() ||
            !StageConcurrentNativeTrialPolicy.allowed(true,
                android.animation.ValueAnimator.areAnimatorsEnabled(),
                context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode!=false))return
        // One optional job on the existing capture worker, with at most
        // one recovery after a real callback timeout. No new executor,
        // periodic retry, frame wait, or upload triggered by a tap.
        backdropWorker.execute {
            if(generation!=backdropGeneration.get() ||
                textureEpoch!=gpuWallpaperEpoch.get() || frame.isRecycled ||
                !WorkspaceWallpaperBackdrop.canCapture(context) ||
                context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode!=false)return@execute
            var preparation="not_reported"
            val textured=runCatching {
                StageGpuWallpaperBuffer.prepare(frame) { outcome ->
                    preparation=outcome
                    NeXtepLog.info("wallpaper_backdrop","GPU cover preparation attempt="+attempt+" "+outcome)
                }
            }.getOrNull()
            if(textured==null) {
                if(StageConcurrentNativeTrialPolicy.retryWallpaperPreparation(attempt,preparation)) {
                    // Re-enter the main-lane admission check after the
                    // failed renderer has been closed. No second try on
                    // stale geometry, a new swap, lock, or saver entry.
                    backdropHandler.post {
                        queueWallpaperTexture(capturedGeometry,generation,textureEpoch,frame,attempt+1)
                    }
                }
                return@execute
            }
            if(!backdropHandler.post {
                val current=runCatching {
                    StageConcurrentNativeTrialPolicy.wallpaperTexturePublishable(
                        generation==backdropGeneration.get(),
                        textureEpoch==gpuWallpaperEpoch.get(),
                        wallpaperBitmap===frame && !frame.isRecycled,
                        workspaceVisible && !suspended,
                        wallpaperKey(geometry())==key,
                        StagePrivateDualTaskFlightGate.enabled() &&
                            WorkspaceWallpaperBackdrop.canCapture(context) &&
                            StageConcurrentNativeTrialPolicy.allowed(true,
                                android.animation.ValueAnimator.areAnimatorsEnabled(),
                                context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode!=false))
                }.getOrDefault(false)
                if(current)gpuWallpaper.replace(key,textured) else textured.close()
            })textured.close()
        }
    }

    // Restore only a missing optional GPU cover after a user-triggered native
    // attempt retires. Reuse the current exact wallpaper frame, not a screen
    // capture. A busy, hidden, rotated, locked or saver workspace does nothing.
    // If a new exchange invalidates this preparation, there is no retry loop.
    private fun recoverMissingWallpaperAfterNativeFlight() {
        if(!workspaceVisible || suspended || isExchangePending() ||
            activeSwapViewport!=null || previewCapturePending || liveSurfaceExchange!=null ||
            !StagePrivateDualTaskFlightGate.enabled() ||
            !WorkspaceWallpaperBackdrop.canCapture(context) ||
            !StageConcurrentNativeTrialPolicy.allowed(true,
                android.animation.ValueAnimator.areAnimatorsEnabled(),
                context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode!=false))return
        val g=geometry()
        val key=wallpaperKey(g)
        val frame=matchingWallpaperFrame(g)?:return
        if(frame.width!=key.width || frame.height!=key.height)return
        val generation=backdropGeneration.get()
        if(!gpuWallpaper.claimMissingRecovery(key,generation))return
        // attempt=1 disables the existing one-time renderer retry for this
        // optional recovery. Normal capture publication retains its policy.
        queueWallpaperTexture(g,generation,gpuWallpaperEpoch.get(),frame,attempt=1)
    }

    private fun matchingWallpaperFrame(geometry: WorkspaceGeometry): android.graphics.Bitmap? =
        wallpaperBitmap?.takeIf {
            !it.isRecycled &&
                io.github.x647368maker.atomicworkbench.workspace.StageTabletWallpaperPolicy
                    .frameMatches(geometry, it.width, it.height, wallpaperRotation)
        }

    private fun applyWallpaperBackdrop(geometry: WorkspaceGeometry, refreshContrast: Boolean = true) {
        // Wide ported tablets use physical panel coordinates; the phone/fold
        // projection still uses its original portrait drawing axes.
        val panel = io.github.x647368maker.atomicworkbench.workspace.StageTabletWallpaperPolicy.panel(geometry)
        val useNative = io.github.x647368maker.atomicworkbench.workspace.StageTabletWallpaperPolicy
            .useLiveSystemWallpaper(geometry)
        val frame = if (useNative) null else matchingWallpaperFrame(geometry)
        (records.firstOrNull { it.title == "NeXtepTopBar" }?.view as? WorkspacePanelHost)?.setPanelBackground(
            if (useNative) android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            else WorkspaceWallpaperBackdrop.crop(frame, panel.controlLeft, panel.controlTop,
                frostStrength, wallpaperRotation, panel.displayRotation),
        )
        if (refreshContrast) {
            topAppStrip?.setMediaWallpaperLight(WorkspaceWallpaperBackdrop.isLight(
                frame, panel.controlLeft, panel.controlTop, panel.controlWidth, panel.controlHeight,
            ))
        }
        applySidebarBackdrop(geometry)
    }

    private fun applySidebarBackdrop(geometry: WorkspaceGeometry) {
        val panel = io.github.x647368maker.atomicworkbench.workspace.StageTabletWallpaperPolicy.panel(geometry)
        // No duplicated wallpaper pixels in the tablet rail: the existing
        // FLAG_SHOW_WALLPAPER surface is shared underneath all transparent panels.
        val background = if (io.github.x647368maker.atomicworkbench.workspace.StageTabletWallpaperPolicy
                .useLiveSystemWallpaper(geometry)) android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            else WorkspaceWallpaperBackdrop.crop(matchingWallpaperFrame(geometry), panel.sidebarLeft, panel.sidebarTop,
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
