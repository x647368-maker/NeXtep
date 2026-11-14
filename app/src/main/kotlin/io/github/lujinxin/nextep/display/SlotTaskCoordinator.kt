package io.github.x647368maker.atomicworkbench.display

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import io.github.x647368maker.atomicworkbench.R
import io.github.x647368maker.atomicworkbench.config.AppLanguage
import io.github.x647368maker.atomicworkbench.framework.ActivityTaskManagerCompat
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.framework.UserTargetedActivityLauncher
import io.github.x647368maker.atomicworkbench.framework.WindowContainerTransactionCompat
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
import io.github.x647368maker.atomicworkbench.task.MainTaskPresentationCoordinator
import io.github.x647368maker.atomicworkbench.task.TaskRepository
import io.github.x647368maker.atomicworkbench.trigger.TriggerBroadcastContract
import io.github.x647368maker.atomicworkbench.workspace.RecentTaskSelection
import io.github.x647368maker.atomicworkbench.workspace.RecentTaskSelectionContract
import io.github.x647368maker.atomicworkbench.workspace.SelectedRecentTask
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

class SlotTaskCoordinator(
    context: Context,
    private val slots: List<VirtualDisplaySlot>,
    private val mainTaskPresenter: MainTaskPresentationCoordinator,
    private val geometryProvider: () -> WorkspaceGeometry,
    private val onDragActiveChanged: (Boolean) -> Unit = {},
    private val onSwapAnimation: (SlotTaskDrag, Int?, () -> Boolean, () -> Unit) -> Unit =
        { _, _, move, finished -> try { move() } finally { finished() } },
    private val onMainSlotAnimation: (Int, () -> Boolean, () -> Unit) -> Unit =
        { _, move, finished -> try { move() } finally { finished() } },
    private val onPhasedMainSlotAnimation: ((Int, ((Boolean) -> Unit) -> Unit, () -> Boolean, () -> Unit) -> Unit)? = null,
    private val onInternalSlotDrag: (TaskSwitcherView, SlotTaskDrag) -> Boolean = { _, _ -> false },
    private val onInternalDragTouch: (android.view.View, android.view.MotionEvent) -> Boolean = { _, _ -> false },
    private val onSlotStatesChanged: () -> Unit = {},
) : VirtualDisplaySlot.Listener, TaskSwitcherView.Listener {
    private data class SlotRecord(
        var state: SlotState = SlotState.Empty,
        var retainedTaskId: Int? = null,
        var exitTask: TaskRepository.RetainedTask? = null,
        var pendingComponent: ComponentName? = null,
        var pendingAttempts: Int = 0,
        var originalLayout: TaskLayout? = null,
        var mismatchSince: Long = 0L,
        var migrationRecoveryComponent: ComponentName? = null,
        var migrationRecoveryDeadline: Long = 0L,
        var migrationRecoveryAttempts: Int = 0,
    )

    private data class TaskLayout(
        val bounds: Rect,
        val densityDpi: Int,
        val windowingMode: Int,
        val resizeMode: Int?,
        val vendorWindowed: Boolean = false,
    )

    private val applicationContext = context.applicationContext ?: context
    private val packageManager = applicationContext.packageManager
    private val taskRepository = TaskRepository(applicationContext)
    private val settingsComponent = ComponentName(
        TriggerBroadcastContract.MODULE_PACKAGE,
        "${TriggerBroadcastContract.MODULE_PACKAGE}.config.MainActivity",
    )
    private val handler = Handler(Looper.getMainLooper())
    private val slotRecords = MutableList(slots.size) { SlotRecord() }
    private val exchangeHandler = Handler(Looper.getMainLooper())
    private val exchangeBinderLane = StageExchangeBinderLane { action -> exchangeHandler.post(action) }
    private var exchangeSession: ExchangeSession<TaskLayout>? = null
    private data class ConfirmedOutput(val generation: Int, val slot: Int, val task: Int,
                                       val mark: VirtualDisplaySlot.OutputFrameMark)
    private var confirmedOutput: ConfirmedOutput? = null
    internal fun confirmedOutputMark(slot: Int, task: Int): VirtualDisplaySlot.OutputFrameMark? =
        confirmedOutput?.takeIf {
            it.generation==transitionGeneration && it.slot==slot && it.task==task &&
                exchangeSession?.phase==ExchangeSession.Phase.COMMITTED
        }?.mark
    private val exchangeEndActions = mutableListOf<() -> Unit>()

    fun isExchangePending(): Boolean = exchangeSession?.settled == false

    fun deferUntilExchangeSettled(action: () -> Unit): Boolean {
        ensureMainThread()
        val session = exchangeSession ?: return false
        if (session.settled) return false
        exchangeEndActions.add(action)
        session.cancel()
        return true
    }

    private var active = false
    private var suspended = false
    private var transitionInProgress = false
    private var recentSelectionPending = false
    private var recentSelectionGeneration = 0
    private var activeDrag: SlotTaskDrag? = null
    private var visualTransitionPending = false
    private var transitionGeneration = 0
    private val refreshPolicy = WorkspaceRefreshPolicy()
    private var reconciliationAt = 0L
    private var lastFullReconciliationAt = 0L
    private var pendingSelection: (() -> Unit)? = null
    private var mainSelectionGeneration = 0
    private var promotedRecoveryGeneration = 0
    private var promotedRecoveryComponent: ComponentName? = null
    private var promotedRecoveryTaskId: Int? = null
    private var promotedRecoveryUserId: Int? = null
    private var promotedRecoveryDeadline = 0L
    private var promotedRecoveryAttempts = 0

    private val reconciliation = object : Runnable {
        override fun run() {
            reconciliationAt = 0L
            if (!active || suspended) return
            if (!transitionInProgress) {
                val now = SystemClock.uptimeMillis()
                if (now - lastFullReconciliationAt >= FULL_RECONCILE_INTERVAL_MS) {
                    lastFullReconciliationAt = now
                    reconcile("periodic")
                } else {
                    reconcileForeground("periodic")
                }
            }
            scheduleReconciliation(refreshPolicy.delayMs(SystemClock.uptimeMillis(), hasPendingRecovery()))
        }
    }

    /** Called from the main thread after an existing Shell task callback. */
    fun onTasksChanged() {
        ensureMainThread()
        if (!active || suspended) return
        refreshPolicy.onTaskChanged(SystemClock.uptimeMillis())
        scheduleReconciliation(TASK_CHANGE_COALESCE_MS)
    }

    private fun hasPendingRecovery(): Boolean = promotedRecoveryComponent != null || slotRecords.any {
        it.pendingComponent != null || it.migrationRecoveryComponent != null || it.mismatchSince != 0L
    } || slots.any { it.isSuspended() }

    private fun scheduleReconciliation(delayMs: Long) {
        val target = SystemClock.uptimeMillis() + delayMs
        if (reconciliationAt != 0L && reconciliationAt <= target) return
        handler.removeCallbacks(reconciliation)
        reconciliationAt = target
        handler.postAtTime(reconciliation, target)
    }

    init {
        require(slots.size == WORKSPACE_SLOT_COUNT) {
            "P6 requires exactly $WORKSPACE_SLOT_COUNT slots"
        }
        slots.forEach { slot ->
            slot.setListener(this)
            slot.view.setListener(this)
        }
    }

    fun activate() {
        ensureMainThread()
        if (active) return
        validateExitedTasks()
        active = true
        lastFullReconciliationAt = 0L
        refreshPolicy.onTaskChanged(SystemClock.uptimeMillis())
        slots.forEach(VirtualDisplaySlot::activate)
        handler.removeCallbacks(reconciliation)
        reconciliationAt = 0L
        scheduleReconciliation(WorkspaceRefreshPolicy.FAST_INTERVAL_MS)
        NeXtepLog.info("slot_coordinator", "Activated retained=${retainedTaskDescription()}")
    }

    fun deactivate() {
        ensureMainThread()
        if (!active) return
        if (deferUntilExchangeSettled { deactivate() }) {
            suspended = true
            return
        }
        // Never destroy a display while a task failed to leave it.
        try {
            parkTasksBeforeExit()
        } catch (error: Throwable) {
            slots.forEachIndexed { index, slot ->
                if (slotRecords[index].exitTask != null) {
                    slot.displayId()?.let { onDisplayReady(index, it) }
                }
            }
            throw error
        }
        active = false
        io.github.x647368maker.atomicworkbench.systemui.StageTabletColdLaunchBridge.reset()
        cancelPendingAdd("工作区已退出")
        cancelPendingExchange()
        onSlotDragEnded()
        recentSelectionGeneration += 1
        recentSelectionPending = false
        suspended = false
        transitionInProgress = false
        pendingSelection = null
        mainSelectionGeneration += 1
        clearPromotedTaskRecovery()
        handler.removeCallbacksAndMessages(null)
        reconciliationAt = 0L
        slots.forEach { slot ->
            slot.view.setBusy(false)
            slot.deactivate()
        }
        slotRecords.forEach { record ->
            record.pendingComponent = null
            record.pendingAttempts = 0
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
        }
        NeXtepLog.info("slot_coordinator", "Deactivated retained=${retainedTaskDescription()}")
    }

    private fun parkTasksBeforeExit() {
        val tasks = taskRepository.runningTasks()
        val saved = mutableSetOf<Int>()
        slots.forEachIndexed { index, slot ->
            val record = slotRecords[index]
            record.exitTask = null
            val task = tasks.firstOrNull { it.displayId == slot.displayId() }
            record.retainedTaskId = null
            if (task == null || !saved.add(task.taskId)) return@forEachIndexed
            runCatching {
                val layout = record.originalLayout ?: defaultMainLayout(task.resizeMode)
                WindowContainerTransactionCompat.parkInDefaultDisplay(
                    checkNotNull(task.token), layout.defaultDisplayBounds(), layout.densityDpi, layout.windowingMode,
                ).getOrThrow()
                check(taskRepository.findTask(task.taskId)?.displayId == Display.DEFAULT_DISPLAY) {
                    "Task ${task.taskId} did not return to the default display"
                }
                layout.resizeMode?.let { ActivityTaskManagerCompat.setTaskResizeable(task.taskId, it).getOrThrow() }
                val recent = taskRepository.recentTasks(checkNotNull(task.userId)).getOrThrow()
                    .firstOrNull { it.taskId == task.taskId }
                record.exitTask = recent
                record.retainedTaskId = recent?.taskId
                NeXtepLog.info("slot_retention", "Parked slot=$index task=${recent?.taskId}")
            }.onFailure {
                NeXtepLog.warn("slot_retention", "Could not retain slot=$index task=${task.taskId}", it)
            }
            // Some apps open secondary tasks on the same display. Preserve those in recents
            // too, while only the top task owns this slot's restore reservation.
            tasks.filter { it.displayId == slot.displayId() && it.taskId != task.taskId }
                .forEach { secondary ->
                    val layout = defaultMainLayout(secondary.resizeMode)
                    WindowContainerTransactionCompat.parkInDefaultDisplay(
                        checkNotNull(secondary.token), layout.defaultDisplayBounds(), layout.densityDpi, layout.windowingMode,
                    ).getOrThrow()
                }
        }
        val displays = slots.mapNotNull { it.displayId() }.toSet()
        check(taskRepository.runningTasks().none { it.displayId in displays }) {
            "Slot tasks are still attached; refusing to destroy their displays"
        }
    }

    private fun validateExitedTasks() {
        val queries = slotRecords.mapNotNull { it.exitTask?.userId }.distinct()
            .associateWith { taskRepository.recentTasks(it).getOrNull() }
        val foreground = taskRepository.foregroundTask()?.taskId
        slotRecords.forEach { record ->
            val saved = record.exitTask ?: return@forEach
            val current = queries[saved.userId]?.firstOrNull { it.taskId == saved.taskId }
            val live = taskRepository.findTask(saved.taskId)
            if (current != saved || foreground == saved.taskId ||
                (live != null && (live.displayId != Display.DEFAULT_DISPLAY || live.vendorWindowed))) {
                record.exitTask = null
                record.retainedTaskId = null
                record.originalLayout = null
                NeXtepLog.info("slot_retention", "Discarded changed/removed task=${saved.taskId}")
            }
        }
    }

    fun setSuspended(value: Boolean) {
        ensureMainThread()
        if (!active || suspended == value) return
        if (!value && exchangeSession?.phase == ExchangeSession.Phase.RECOVERY_REQUIRED) {
            exchangeSession?.cancel()
        }
        if (value) {
            suspended = true
            io.github.x647368maker.atomicworkbench.systemui.StageTabletColdLaunchBridge.reset()
            cancelPendingExchange()
            onSlotDragEnded()
            recentSelectionGeneration += 1
            recentSelectionPending = false
            handler.removeCallbacksAndMessages(null)
            reconciliationAt = 0L
            transitionInProgress = exchangeSession?.settled == false
            pendingSelection = null
            mainSelectionGeneration += 1
            clearPromotedTaskRecovery()
            slots.forEach {
                it.view.setBusy(true)
                it.setSuspended(true)
            }
        } else {
            slots.forEach { slot ->
                runCatching { slot.setSuspended(false) }.onFailure {
                    // One delayed/invalid preview Surface must not keep HOME and
                    // all SystemUI panels hidden. Retry that slot in reconciliation.
                    NeXtepLog.warn("virtual_display", "Output resume pending display=${slot.displayId()}", it)
                }
            }
            suspended = false
            slots.forEach { it.view.setBusy(false) }
            handler.removeCallbacks(reconciliation)
            reconciliationAt = 0L
            refreshPolicy.onTaskChanged(SystemClock.uptimeMillis())
            scheduleReconciliation(0L)
        }
    }

    fun updateSuspendedOutputs(screenAwake: Boolean) {
        ensureMainThread()
        if (!active || !suspended) return
        slots.forEachIndexed { index, slot ->
            runCatching { slot.updateSuspendedOutput(screenAwake) }.onFailure {
                NeXtepLog.warn("workspace_unlock", "Suspended output pending slot=$index awake=$screenAwake", it)
            }
        }
    }

    fun launchInSlot(intent: Intent, slotIndex: Int = 0): Result<Unit> {
        ensureMainThread()
        return assignIntent(slotIndex, intent)
    }

    /** Park existing side tasks before a named group restore. Never kill them. */
    fun prepareGroupRestore(): Result<Unit> {
        ensureMainThread()
        if (!active || suspended || activeDrag != null) return Result.failure(IllegalStateException("工作区忙碌"))
        return runTransition("prepare workgroup restore") {
            parkTasksBeforeExit()
            slotRecords.indices.forEach { index ->
                clearSlotAssignment(index)
                slotRecords[index].exitTask = null
                setState(index, slots[index].displayId()?.let(SlotState::Ready) ?: SlotState.Empty, "group restore ready")
            }
        }
    }

    /** Reuse the same live tasks directly. No global parking or HOME detour is needed
     * when the requested group is just a permutation of the current five positions. */
    internal fun reorderGroupIfPossible(target: List<io.github.x647368maker.atomicworkbench.systemui.StageTaskIdentity?>): Result<Boolean> {
        ensureMainThread()
        if (!isGroupRestoreReady() || activeDrag != null) return Result.failure(IllegalStateException("工作区忙碌"))
        val tasks = taskRepository.runningTasks()
        val byId = tasks.associateBy { it.taskId }
        val main = tasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
        if (main == null || !isExchangeableMainTask(main)) return Result.success(false)
        fun identity(task: TaskRepository.TaskSnapshot?) = task?.let {
            it.userId?.let { user -> io.github.x647368maker.atomicworkbench.systemui.StageTaskIdentity(it.taskId, it.component.packageName, user) }
        }
        val current = mutableListOf(identity(main))
        for (record in slotRecords) {
            when (val state = record.state) {
                is SlotState.Occupied -> {
                    val task = byId[state.taskId]
                    if (task == null || task.displayId != state.displayId) return Result.success(false)
                    current.add(identity(task) ?: return Result.success(false))
                }
                is SlotState.Ready -> {
                    if (tasks.any { it.displayId == state.displayId }) return Result.success(false)
                    current.add(null)
                }
                else -> return Result.success(false)
            }
        }
        val moves = io.github.x647368maker.atomicworkbench.systemui.StageGroupSwitchPolicy.planReorder(current, target)
            ?: return Result.success(false)
        return runTransition("reorder live workgroup tasks") {
            moves.forEach { move ->
                if (move.to == 0) {
                    val source = move.from - 1
                    swapWithMain(source, slotRecords[source].state as SlotState.Occupied)
                } else swapSlots(move.from - 1, move.to - 1)
            }
        }.map { true }
    }

    fun restoreInSlot(intent: Intent, index: Int, preferredTaskId: Int): Result<Unit> {
        ensureMainThread()
        return assignIntent(index, intent, preferredTaskId)
    }

    /** Close only the workspace window: park its real task in the background. */
    fun moveForegroundWechatToSlot(expectedTask: Int): Result<Unit> = runCatching {
        ensureMainThread()
        val task = checkNotNull(taskRepository.foregroundTask()) { "请先在主窗打开微信小程序" }
        check(io.github.x647368maker.atomicworkbench.systemui.StageTaskMigrationPolicy.eligible(
            task.component.packageName, task.userId, task.userId, task.taskId, expectedTask,
            task.displayId == Display.DEFAULT_DISPLAY && isExchangeableMainTask(task), isGroupRestoreReady())) {
            "微信任务已变化，未移动任何窗口"
        }
        val index = slotRecords.indexOfFirst { it.state is SlotState.Ready }
        check(index >= 0) { "请先空出一个小窗" }
        val ready = slotRecords[index].state as SlotState.Ready
        runTransition("move exact WeChat task=${task.taskId}") {
            parkMainTaskInSlot(index, ready, task, leaveOverview = false)
        }.getOrThrow()
    }

    fun closeWindow(index: Int): Result<Unit> = runCatching {
        ensureMainThread()
        check(isGroupRestoreReady()) { "工作区正在切换" }
        val state = slotRecords.getOrNull(index)?.state as? SlotState.Occupied ?: error("窗口已关闭")
        val drag = SlotTaskDrag(index, state.taskId, state.displayId)
        check(onSlotDragStarting(drag)) { "窗口忙碌或系统任务状态已变化" }
        try {
            dismissDraggedSlotResult(drag).getOrThrow()
        } finally {
            onSlotDragEnded()
        }
        // Clear recovery metadata too; a dismissed task must not reopen itself.
        clearSlotAssignment(index)
        slotRecords[index].exitTask = null
    }

    fun replaceWindow(index: Int, intent: Intent): Result<Unit> {
        ensureMainThread()
        if (!isGroupRestoreReady() || index !in slotRecords.indices) return Result.failure(IllegalStateException("工作区忙碌"))
        val normalized = normalizeLaunchIntent(intent) ?: return Result.failure(IllegalArgumentException("应用不可用"))
        val previous = slotRecords[index].state as? SlotState.Occupied
        if (previous != null) {
            val current = taskRepository.findTask(previous.taskId)
            val target = findLaunchTask(checkNotNull(normalized.component), normalized.targetUser())
            if (target?.taskId == previous.taskId) return Result.success(Unit)
            // Validate the new launcher before parking the old task. Do not kill either app.
            val closed = closeWindow(index)
            if (closed.isFailure) return closed
            val assigned = assignIntent(index, normalized)
            if (assigned.isFailure && current?.userId != null) {
                val rollback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(previous.component).putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER,
                        android.os.UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, current.userId) as android.os.UserHandle)
                // Best effort: restore the previous real task if launch was rejected synchronously.
                assignIntent(index, rollback, previous.taskId)
            }
            return assigned
        }
        return assignIntent(index, normalized)
    }

    fun isGroupRestoreReady(): Boolean = active && !suspended && !transitionInProgress &&
        !recentSelectionPending && slotRecords.none { it.pendingComponent != null }

    fun openInMain(sourceIntent: Intent, autoMinimizeCurrent: Boolean = false, preferredTaskId: Int? = null): Result<Unit> {
        ensureMainThread()
        if (!active || suspended) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        if (transitionInProgress || recentSelectionPending) {
            val selected = Intent(sourceIntent)
            pendingSelection = { openInMain(selected, autoMinimizeCurrent, preferredTaskId) }
            return Result.success(Unit)
        }
        val normalized = normalizeLaunchIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("App does not resolve to a launcher Activity"))
        val component = checkNotNull(normalized.component)
        val existing = preferredTaskId?.let(taskRepository::findTask)?.takeIf {
            it.component.packageName == component.packageName &&
                it.userId == normalized.targetUser()?.let(io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat::userIdentifier)
        } ?: findLaunchTask(component, normalized.targetUser())

        return runTransition("open main component=$component") {
            val currentMain = taskRepository.foregroundTask()
            // Reserve only a genuinely empty slot before promoting the target app.
            // A full sidebar must not turn this click into an exchange with an occupied slot.
            val minimizeSlot = if (autoMinimizeCurrent &&
                currentMain != null && currentMain.taskId != existing?.taskId &&
                isExchangeableMainTask(currentMain)
            ) {
                val tasks = taskRepository.runningTasks()
                slotRecords.indexOfFirst { record ->
                    val ready = record.state as? SlotState.Ready ?: return@indexOfFirst false
                    record.pendingComponent == null && record.retainedTaskId == null &&
                        !shouldRecoverMigratedTask(record) &&
                        tasks.none { it.displayId == ready.displayId }
                }
            } else -1

            fun minimizePreviousMain() {
                if (minimizeSlot < 0 || currentMain == null) return
                val ready = slotRecords[minimizeSlot].state as? SlotState.Ready ?: return
                val previous = taskRepository.findTask(currentMain.taskId)
                    ?.takeIf(::isExchangeableMainTask) ?: return
                runCatching {
                    parkMainTaskInSlot(minimizeSlot, ready, previous, leaveOverview = false)
                }.onFailure { error ->
                    // Parking performs its own rollback; keep the requested app switch usable.
                    NeXtepLog.warn("top_apps", "Unable to auto-minimize task=${currentMain.taskId}", error)
                }
            }

            if (existing != null) {
                val sourceSlot = slotRecords.indexOfFirst { record ->
                    (record.state as? SlotState.Occupied)?.taskId == existing.taskId
                }
                val sourceLayout = sourceSlot.takeIf { it >= 0 }
                    ?.let { slotRecords[it].originalLayout }
                    ?: defaultMainLayout(existing.resizeMode)
                if (existing.displayId != Display.DEFAULT_DISPLAY) {
                    if (existing.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) ActivityTaskManagerCompat.setTaskResizeable(
                        existing.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
                    moveTaskToDisplayAndWait(
                        existing.taskId,
                        Display.DEFAULT_DISPLAY,
                    )
                    restoreTaskLayout(existing, sourceLayout, restoreResizeMode = false)
                }
                if (sourceSlot >= 0) {
                    slotRecords[sourceSlot].retainedTaskId = null
                    slotRecords[sourceSlot].pendingComponent = null
                    slotRecords[sourceSlot].pendingAttempts = 0
                    slotRecords[sourceSlot].originalLayout = null
                    slotRecords[sourceSlot].migrationRecoveryComponent = null
                    slotRecords[sourceSlot].migrationRecoveryDeadline = 0L
                    slotRecords[sourceSlot].migrationRecoveryAttempts = 0
                    slotRecords[sourceSlot].mismatchSince = 0L
                    val displayId = slots[sourceSlot].displayId()
                    setState(
                        sourceSlot,
                        displayId?.let(SlotState::Ready) ?: SlotState.Empty,
                        "top app promoted from slot",
                    )
                }
                // For a *retained* Y700 task already on display 0, prepare
                // the actual task leash before Shell brings it to the front.
                // Leave vendor dual-window, freeform, slot tasks, phones and
                // foldables on the original task-preserving route.
                val g=geometryProvider()
                val earlyFit = sourceSlot < 0 &&
                    existing.displayId == Display.DEFAULT_DISPLAY &&
                    existing.windowingMode == fullscreenWindowingMode() &&
                    !existing.vendorWindowed && currentMain?.taskId != existing.taskId &&
                    io.github.x647368maker.atomicworkbench.workspace.StageTabletColdLaunchPolicy.arm(
                        g.screenWidth,g.screenHeight,component.packageName,existing.userId ?: -1) &&
                    TaskSurfaceCompat.preparePromotion(existing.taskId,g).isSuccess
                try {
                    if (currentMain?.taskId != existing.taskId) taskRepository.bringTaskToFront(existing.taskId).getOrThrow()
                    minimizePreviousMain()
                    mainTaskPresenter.presentTask(existing.taskId, geometryProvider()).getOrThrow()
                } finally {
                    if (earlyFit) TaskSurfaceCompat.cancelPreparedPromotion(existing.taskId)
                }
            } else {
                check(TaskContinuityPolicy.canLaunch(TaskContinuityPolicy.Origin.USER_LAUNCH, existingTask = false))
                NeXtepLog.info("task_continuity", "explicit_user_launch package=${component.packageName} user=${normalized.targetUser()} reason=no_existing_task")
                // Y700 native first-frame handoff. The Shell onTaskAppeared
                // callback consumes this one-shot only if exact app/user,
                // actual fullscreen windowing and physical display match.
                val earlyLaunch=io.github.x647368maker.atomicworkbench.systemui.StageTabletColdLaunchBridge.arm(
                    component,normalized.targetUser(),geometryProvider())
                UserTargetedActivityLauncher.start(applicationContext, normalized)
                    .onFailure {
                        io.github.x647368maker.atomicworkbench.systemui.StageTabletColdLaunchBridge.cancel(earlyLaunch)
                    }.getOrThrow()
                minimizePreviousMain()
                val generation = mainSelectionGeneration
                handler.postDelayed({
                    presentLaunchedMain(component, attempt = 1, generation = generation,
                        targetUser = normalized.targetUser())
                }, MAIN_LAUNCH_POLL_MS)
            }
        }
    }

    fun openMediaInMain(session: android.media.session.MediaController): Result<Unit> {
        ensureMainThread()
        if (!active || suspended) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        if (transitionInProgress || recentSelectionPending) {
            pendingSelection = { openMediaInMain(session) }
            return Result.success(Unit)
        }
        val targetUser = session.sessionActivity?.creatorUserHandle ?: android.os.Process.myUserHandle()
        val userId = io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat.userIdentifier(targetUser)
        val sourceSlot = slotRecords.indexOfFirst { record ->
            val occupied = record.state as? SlotState.Occupied ?: return@indexOfFirst false
            occupied.component.packageName == session.packageName &&
                taskRepository.findTask(occupied.taskId)?.userId == userId
        }
        if (sourceSlot >= 0) {
            val occupied = slotRecords[sourceSlot].state as SlotState.Occupied
            return runTransition("media swap slot=$sourceSlot task=${occupied.taskId}", mainSlotIndex = sourceSlot) {
                swapWithMain(sourceSlot, occupied)
            }
        }
        val launcherIntent = packageManager.getLaunchIntentForPackage(session.packageName)
            ?.putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER, targetUser)
        if (launcherIntent != null) return openInMain(launcherIntent)

        // Some media sessions have a player Activity but no launcher entry.
        val pendingIntent = session.sessionActivity
            ?: return Result.failure(IllegalStateException("Media app has no launch target"))
        if (!pendingIntent.isActivity) {
            return Result.failure(IllegalStateException("Media session launch target is not an Activity"))
        }
        return runTransition("media session main package=${session.packageName}") {
            val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(Display.DEFAULT_DISPLAY) }
            pendingIntent.send(applicationContext, 0, null, null, null, null, options.toBundle())
            handler.postDelayed({ reconcileForeground("media session launched") }, MAIN_LAUNCH_POLL_MS)
        }
    }

    fun openExternalInMain(sourceIntent: Intent): Result<Unit> {
        ensureMainThread()
        if (!active || suspended) {
            return Result.failure(IllegalStateException("Workspace transition is busy"))
        }
        if (transitionInProgress || recentSelectionPending) {
            val selected = Intent(sourceIntent)
            pendingSelection = { openExternalInMain(selected) }
            return Result.success(Unit)
        }
        val normalized = normalizeExternalIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("External content has no Activity"))
        val component = checkNotNull(normalized.component)
        return runTransition("open external component=$component") {
            UserTargetedActivityLauncher.start(applicationContext, normalized).getOrThrow()
            val generation = mainSelectionGeneration
            handler.postDelayed({
                presentLaunchedMain(component, attempt = 1, generation = generation,
                    targetUser = normalized.targetUser())
            }, MAIN_LAUNCH_POLL_MS)
        }
    }

    fun openFromLauncher(sourceIntent: Intent): Result<Unit> {
        ensureMainThread()
        if (!active) return Result.failure(IllegalStateException("Workspace is inactive"))
        val normalized = normalizeLaunchIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("App does not resolve to a launcher Activity"))
        val component = checkNotNull(normalized.component)
        val existing = findLaunchTask(component, normalized.targetUser())
        val sourceSlot = existing?.let { task ->
            slotRecords.indexOfFirst { record ->
                (record.state as? SlotState.Occupied)?.taskId == task.taskId
            }
        } ?: -1
        if (sourceSlot >= 0) {
            onSlotClicked(sourceSlot)
            NeXtepLog.info(
                "launcher_redirect",
                "Converted repeated launch into slot exchange slot=$sourceSlot taskId=${existing?.taskId}",
            )
            return Result.success(Unit)
        }
        return openInMain(normalized)
    }

    private fun presentLaunchedMain(component: ComponentName, attempt: Int, generation: Int,
        targetUser: android.os.UserHandle? = null) {
        if (!active || suspended || generation != mainSelectionGeneration) return
        val launched = findLaunchTask(component, targetUser)
            ?.takeIf { it.displayId == Display.DEFAULT_DISPLAY }
        if (launched == null) {
            if (attempt < MAX_MAIN_LAUNCH_POLLS) {
                handler.postDelayed(
                    { presentLaunchedMain(component, attempt + 1, generation, targetUser) },
                    MAIN_LAUNCH_POLL_MS,
                )
            } else {
                NeXtepLog.warn(
                    "top_apps",
                    "Main task did not appear component=$component attempts=$attempt",
                )
            }
            return
        }
        if (taskRepository.foregroundTask()?.taskId != launched.taskId) return
        mainTaskPresenter.presentTask(launched.taskId, geometryProvider())
            .onFailure { error ->
                NeXtepLog.error(
                    "top_apps",
                    "Main presentation failed taskId=${launched.taskId}",
                    error,
                )
            }
    }

    private var pendingAddIntent: Intent? = null
    private var pendingAddFailure: ((String) -> Unit)? = null
    private var addGeneration = 0

    private fun availability(): List<SlotAvailabilityPolicy.Fact> {
        val tasks = taskRepository.runningTasks()
        return slotRecords.mapIndexed { i, record ->
            val state=record.state
            val display=slots[i].displayId()
            val task=(state as? SlotState.Occupied)?.taskId
            SlotAvailabilityPolicy.Fact(when(state) {
                SlotState.Empty -> SlotAvailabilityPolicy.State.EMPTY
                is SlotState.Ready -> SlotAvailabilityPolicy.State.READY
                is SlotState.Occupied -> SlotAvailabilityPolicy.State.OCCUPIED
                is SlotState.Failed -> SlotAvailabilityPolicy.State.FAILED
            }, display, task, tasks.firstOrNull { it.taskId==task }?.displayId,
                tasks.filter { it.displayId==display }.map { it.taskId },
                slots[i].view.textureView.isAvailable && slots[i].view.textureView.surfaceTexture != null,
                slots[i].view.hasVisibleContent(), when(state) {is SlotState.Ready -> state.displayId;is SlotState.Occupied -> state.displayId;else -> null})
        }
    }

    fun addWindow(intent: Intent, onFailure: (String) -> Unit = {}): Result<Unit> = runCatching {
        ensureMainThread()
        check(isGroupRestoreReady()) { "窗口正在切换，请稍后重试" }
        check(pendingAddIntent == null) { "正在初始化小窗，请勿重复点击" }
        reconcile("user add snapshot")
        when(val decision=SlotAvailabilityPolicy.decide(availability())) {
            is SlotAvailabilityPolicy.Decision.Available -> replaceWindow(decision.index,intent).getOrThrow()
            SlotAvailabilityPolicy.Decision.Full -> error("最多四个小窗")
            SlotAvailabilityPolicy.Decision.Recover -> {
                pendingAddIntent=Intent(intent);pendingAddFailure=onFailure
                val generation=++addGeneration
                // Failed/Empty records with an existing producer are reconciled, never destroyed.
                slotRecords.forEachIndexed { i, record ->
                    if ((record.state is SlotState.Empty || record.state is SlotState.Failed) && slots[i].displayId()==null)
                        slots[i].retryInitialization()
                }
                onSlotStatesChanged()
                handler.post { completePendingAdd() }
                handler.postDelayed({
                    if(generation==addGeneration && pendingAddIntent!=null) {
                        runCatching {
                        reconcile("add deadline")
                        completePendingAdd()
                        if(pendingAddIntent!=null) {
                            val callback=pendingAddFailure
                            pendingAddIntent=null;pendingAddFailure=null;addGeneration++
                            val reason="小窗初始化或画面绑定未完成："+slotDiagnostics()
                            NeXtepLog.warn("slot_add",reason)
                            callback?.invoke(reason)
                        }
                        }.onFailure { error ->
                            NeXtepLog.warn("slot_add", "Add deadline recovery failed", error)
                            cancelPendingAdd("小窗恢复失败："+(error.message ?: "系统任务服务暂不可用"))
                        }
                    }
                },2500L)
            }
        }
        Unit
    }
    private fun completePendingAdd() {
        runCatching {
        val intent=pendingAddIntent ?: return
        if(!active || suspended) { cancelPendingAdd("工作区已退出或挂起");return }
        if(!isGroupRestoreReady()) return
        val decision=SlotAvailabilityPolicy.decide(availability())
        if(decision == SlotAvailabilityPolicy.Decision.Full) { cancelPendingAdd("最多四个小窗");return }
        if(decision !is SlotAvailabilityPolicy.Decision.Available) return
        val callback=pendingAddFailure
        pendingAddIntent=null;pendingAddFailure=null;addGeneration++
        replaceWindow(decision.index,intent).onFailure { callback?.invoke(it.message ?: "应用未打开") }
        }.onFailure { error ->
            NeXtepLog.warn("slot_add", "Surface-ready recovery failed", error)
            cancelPendingAdd("小窗恢复失败："+(error.message ?: "系统任务服务暂不可用"))
        }
    }
    private fun cancelPendingAdd(reason: String) {
        val callback=pendingAddFailure
        pendingAddIntent=null;pendingAddFailure=null;addGeneration++
        callback?.invoke(reason)
    }
    fun slotDiagnostics(): String = slotRecords.mapIndexed { i, record ->
        val slot=slots[i];val view=slot.view
        val task=(record.state as? SlotState.Occupied)?.taskId ?: record.retainedTaskId
        val state=when(record.state) {SlotState.Empty->"Empty";is SlotState.Ready->"Ready";is SlotState.Occupied->"Occupied";is SlotState.Failed->"Failed"}
        val failure=(record.state as? SlotState.Failed)?.message ?: slot.initializationFailure().orEmpty()
        "槽${i+1} $state task=$task display=${slot.displayId()} texture=${view.textureView.isAvailable} visible=${view.hasVisibleContent()} bounds=${view.width}x${view.height}@${view.x.toInt()},${view.y.toInt()} failure=$failure"
    }.joinToString("\n")

    fun slotStates(): List<SlotState> = slotRecords.map { it.state }

    override fun onDisplayReady(slotIndex: Int, displayId: Int) {
        ensureMainThread()
        if (!active) return
        if (deferUntilExchangeSettled { onDisplayReady(slotIndex, displayId) }) return
        validateExitedTasks()
        setState(slotIndex, SlotState.Ready(displayId), "display ready")
        handler.post { completePendingAdd() }
        val retainedTaskId = slotRecords[slotIndex].retainedTaskId
        if (retainedTaskId != null) {
            val exited = slotRecords[slotIndex].exitTask
            if (exited != null && taskRepository.findTask(retainedTaskId) == null) {
                taskRepository.restoreRecentTask(retainedTaskId, displayId).onFailure {
                    NeXtepLog.warn("slot_retention", "Recent task restore failed task=$retainedTaskId", it)
                }
            }
            slotRecords[slotIndex].exitTask = null
            val retained = taskRepository.findTask(retainedTaskId)
            if (retained == null) {
                slotRecords[slotIndex].retainedTaskId = null
                slotRecords[slotIndex].originalLayout = null
            } else {
                val wasPresented = mainTaskPresenter.currentTaskId() == retainedTaskId
                runCatching {
                    mainTaskPresenter.restoreForMove(retainedTaskId).getOrThrow()
                    if (retained.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) ActivityTaskManagerCompat.setTaskResizeable(
                        retainedTaskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
                    moveTaskToDisplayAndWait(retainedTaskId, displayId)
                    ensureSlotLayout(slotIndex, retained)
                }
                    .onSuccess {
                        setState(
                            slotIndex,
                            SlotState.Occupied(displayId, retained.taskId, retained.component),
                            "retained task rebound",
                        )
                    }
                    .onFailure { error ->
                        if (wasPresented) {
                            mainTaskPresenter.presentTask(retainedTaskId, geometryProvider())
                                .onFailure(error::addSuppressed)
                        }
                        slotRecords[slotIndex].retainedTaskId = null
                        slotRecords[slotIndex].originalLayout = null
                        setState(
                            slotIndex,
                            SlotState.Failed("restore failed"),
                            "retained task rebind failed: ${error.message}",
                        )
                    }
            }
        }
        handler.postDelayed({ reconcile("display ready") }, SETTLE_DELAY_MS)
    }

    override fun onDisplayReleased(slotIndex: Int, displayId: Int?, reason: String) {
        ensureMainThread()
        exchangeSession?.takeUnless { it.settled }?.let {
            it.cancel()
            return
        }
        setState(slotIndex, SlotState.Empty, "display released id=$displayId reason=$reason")
    }

    override fun onDisplayFailed(slotIndex: Int, message: String) {
        ensureMainThread()
        if (deferUntilExchangeSettled { onDisplayFailed(slotIndex, message) }) return
        setState(slotIndex, SlotState.Failed(message), "display failed")
    }

    override fun onSlotClicked(slotIndex: Int) {
        if (!active || suspended || slotIndex !in slots.indices) return
        if (transitionInProgress || recentSelectionPending) {
            val selectedTaskId = (slotRecords[slotIndex].state as? SlotState.Occupied)?.taskId
            pendingSelection = {
                // Keep the tapped app if an in-flight move changes its slot.
                val selectedSlot = if (selectedTaskId == null) slotIndex else
                    slotRecords.indexOfFirst { (it.state as? SlotState.Occupied)?.taskId == selectedTaskId }
                if (selectedSlot >= 0) onSlotClicked(selectedSlot)
            }
            NeXtepLog.info("slot_coordinator", "Queued slot tap slot=$slotIndex")
            return
        }
        if (slotRecords[slotIndex].state is SlotState.Failed) {
            // A failed layout is not necessarily an empty display. Reconcile its
            // actual owner before accepting another task into the visible '+' slot.
            reconcile("retry failed slot tap")
            if (slotRecords[slotIndex].state is SlotState.Failed) {
                showMessage(AppLanguage.string(applicationContext, R.string.window_layout_pending))
                return
            }
        }
        when (val state = slotRecords[slotIndex].state) {
            is SlotState.Occupied -> runTransition(
                "swap slot=$slotIndex taskId=${state.taskId}",
                mainSlotIndex = slotIndex,
            ) {
                swapWithMain(slotIndex, state)
            }
            is SlotState.Ready -> {
                if (slotRecords[slotIndex].pendingComponent != null) return
                val mainTask = taskRepository.foregroundTask()
                if (mainTask != null && mainTask.component.packageName == TriggerBroadcastContract.resolveHomePackage(applicationContext)) {
                    requestRecentTask(slotIndex, state, mainTask.taskId)
                    return
                }
                if (!isExchangeableMainTask(mainTask)) {
                    NeXtepLog.info(
                        "slot_coordinator",
                        "Ignored empty slot tap slot=$slotIndex; main task is unavailable or HOME",
                    )
                    return
                }
                val parkedTask = checkNotNull(mainTask)
                runTransition(
                    "park main taskId=${parkedTask.taskId} slot=$slotIndex",
                    mainSlotIndex = slotIndex,
                ) {
                    parkMainTaskInSlot(slotIndex, state, parkedTask)
                }
            }
            else -> Unit
        }
    }

    private fun requestRecentTask(slotIndex: Int, ready: SlotState.Ready, homeTaskId: Int) {
        pendingSelection = null
        recentSelectionPending = true
        val generation = ++recentSelectionGeneration
        slots.forEach { it.view.setBusy(true) }
        RecentTaskSelectionContract.query(applicationContext) { selection ->
            if (generation != recentSelectionGeneration) return@query
            recentSelectionPending = false
            dispatchPendingSelection()
            if (!active || suspended) return@query
            slots.forEach { it.view.setBusy(false) }
            // A more recent selection supersedes this asynchronous overview query.
            if (pendingSelection != null) return@query
            // Reject a delayed result after another app took the foreground or
            // this slot/display was repurposed by another workspace operation.
            if (slotRecords[slotIndex].state != ready || slots[slotIndex].displayId() != ready.displayId ||
                taskRepository.foregroundTask()?.taskId != homeTaskId || transitionInProgress
            ) return@query
            when (selection) {
                RecentTaskSelection.NotVisible -> Unit // HOME itself stays outside slots.
                is RecentTaskSelection.Unavailable -> showMessage(selection.message)
                is RecentTaskSelection.Selected -> runTransition(
                    "center recent task=${selection.task.taskId} slot=$slotIndex",
                ) { assignRecentTask(slotIndex, ready, selection.task) }.onFailure {
                    showMessage(AppLanguage.string(applicationContext, R.string.recent_add_failed))
                }
            }
        }
    }

    private fun assignRecentTask(slotIndex: Int, ready: SlotState.Ready, selected: SelectedRecentTask) {
        val retained = taskRepository.recentTasks(selected.userId).getOrThrow()
            .firstOrNull { it.taskId == selected.taskId && it.baseComponent.packageName == selected.component.packageName }
            ?: error("Selected recent task changed or was removed")
        val live = taskRepository.findTask(retained.taskId)
        if (live != null) {
            check(live.userId == selected.userId) { "Selected task user changed" }
            // ColorOS includes tasks on our virtual displays in overview. Move
            // that exact task between slots instead of rejecting it or launching
            // a second instance. The destination is still the queried empty slot.
            val sourceSlot = slotRecords.indexOfFirst { record ->
                (record.state as? SlotState.Occupied)?.taskId == live.taskId
            }
            if (sourceSlot >= 0) {
                check(sourceSlot != slotIndex && slots[sourceSlot].displayId() == live.displayId) {
                    "Selected slot task changed"
                }
                swapSlots(sourceSlot, slotIndex)
                return
            }
            check(isExchangeableMainTask(live)) {
                "Selected task is no longer on the default display"
            }
            parkMainTaskInSlot(slotIndex, ready, live, leaveOverview = false)
            return
        }
        // Restore the exact retained task, including its user and back stack; do not
        // launch by package (that could open a different card or a fresh Activity).
        val record = slotRecords[slotIndex]
        var restored: TaskRepository.TaskSnapshot? = null
        var original = defaultMainLayout(null)
        try {
            taskRepository.restoreRecentTask(retained.taskId, ready.displayId).getOrThrow()
            val task = checkNotNull(taskRepository.findTask(retained.taskId)) { "Restored task is unavailable" }
            restored = task
            check(task.userId == selected.userId && task.component.packageName == selected.component.packageName)
            original = defaultMainLayout(task.resizeMode)
            ActivityTaskManagerCompat.setTaskResizeable(task.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
            if (task.displayId != ready.displayId) moveTaskToDisplayAndWait(task.taskId, ready.displayId)
            record.originalLayout = original
            ensureSlotLayout(slotIndex, task, original)
            record.retainedTaskId = task.taskId
            setState(slotIndex, SlotState.Occupied(ready.displayId, task.taskId, task.component), "center recent restored")
        } catch (error: Throwable) {
            record.originalLayout = null
            record.retainedTaskId = null
            restored?.let { task ->
                runCatching {
                    WindowContainerTransactionCompat.parkInDefaultDisplay(
                        checkNotNull(task.token), original.defaultDisplayBounds(), original.densityDpi, original.windowingMode,
                    ).getOrThrow()
                    original.resizeMode?.let { mode ->
                        ActivityTaskManagerCompat.setTaskResizeable(task.taskId, mode).getOrThrow()
                    }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onIntentDropped(slotIndex: Int, intent: Intent): Boolean {
        if (!validActiveSlot(slotIndex)) return false
        return assignIntent(slotIndex, intent).isSuccess
    }

    override fun onSlotDragStarting(drag: SlotTaskDrag): Boolean {
        if (!active || suspended || transitionInProgress || recentSelectionPending || activeDrag != null ||
            !matchesDrag(drag)
        ) return false
        activeDrag = drag
        onDragActiveChanged(true)
        return true
    }

    override fun onSlotDragEnded() {
        activeDrag = null
        onDragActiveChanged(false)
    }

    override fun onSlotDragStarted(source: TaskSwitcherView, drag: SlotTaskDrag) = onInternalSlotDrag(source, drag)
    override fun onSlotDragTouch(source: android.view.View, event: android.view.MotionEvent) = onInternalDragTouch(source, event)

    override fun onSlotBackgroundSwiped(drag: SlotTaskDrag) = dismissDraggedSlot(drag)

    /** Keep the swipe gesture's Boolean contract; menu calls keep the cause. */
    fun dismissDraggedSlot(drag: SlotTaskDrag): Boolean =
        dismissDraggedSlotResult(drag).isSuccess

    /** Relinquish ONLY the virtual slot; preserve the real task in Recents.
     * A WCT reparent is asynchronous on ColorOS and must not be judged by an
     * immediate getRunningTasks() snapshot.
     */
    private fun dismissDraggedSlotResult(drag: SlotTaskDrag): Result<Unit> {
        if (activeDrag != drag || !matchesDrag(drag) || !active || suspended || recentSelectionPending) {
            return Result.failure(IllegalStateException("小窗任务发生变化或正在切换，请重试"))
        }
        return runTransition("background slot=${drag.slotIndex} task=${drag.taskId}") {
            val task = checkNotNull(taskRepository.findTask(drag.taskId))
            val record = slotRecords[drag.slotIndex]
            val layout = record.originalLayout ?: defaultMainLayout(task.resizeMode)
            val foreground = taskRepository.foregroundTask()
                ?.takeIf { it.taskId != task.taskId && it.displayId == Display.DEFAULT_DISPLAY }
            var usedBinderFallback = false
            try {
                val park = task.token?.let {
                    WindowContainerTransactionCompat.parkInDefaultDisplay(
                        it, layout.defaultDisplayBounds(), layout.densityDpi, layout.windowingMode
                    )
                } ?: Result.failure(IllegalStateException("没有可用的系统任务令牌"))
                if (park.isFailure) {
                    NeXtepLog.warn("slot_close", "WCT parking unavailable task=${task.taskId}; trying native move",park.exceptionOrNull())
                }
                // Native Shell WCT is async. Avoid the old immediate-check race.
                val settled = if (park.isSuccess) waitForSlotDismissDisplay(task.taskId) else false
                if (SlotDismissDecisionPolicy.nativeFallbackRequired(
                        settled, taskRepository.findTask(task.taskId)?.displayId)) {
                    // On some ColorOS/Vector builds the default TaskDisplayArea
                    // hook is unavailable. A real task move is the safe fallback:
                    // NEVER removeTask(), kill the app, or fake a preview frame.
                    usedBinderFallback = true
                    moveTaskToDisplayAndWait(task.taskId, Display.DEFAULT_DISPLAY)
                    check(SlotDismissDecisionPolicy.mayPublishVacantSlot(
                        taskRepository.findTask(task.taskId)?.displayId)) {
                        "系统未能将应用移回后台（task=${task.taskId}）"
                    }
                }
                check(SlotDismissDecisionPolicy.mayPublishVacantSlot(
                    taskRepository.findTask(task.taskId)?.displayId)) {
                    "关闭等待结束但应用仍留在小窗（task=${task.taskId}）"
                }
                // Binder fallback may temporarily raise the moved task; place
                // the original real main task back on top without relaunching.
                if (SlotDismissDecisionPolicy.shouldRestoreMain(
                        usedBinderFallback, foreground?.taskId, task.taskId,
                        foreground?.let { taskRepository.findTask(it.taskId)?.displayId })) {
                    taskRepository.bringTaskToFront(checkNotNull(foreground).taskId).getOrThrow()
                    mainTaskPresenter.reconcileForeground(geometryProvider())
                        .onFailure { NeXtepLog.warn("slot_close","Main surface reconciliation deferred",it) }
                }
                // Failure to restore a private resize flag is NOT a reason to
                // undo an already completed real task move.
                layout.resizeMode?.let { mode ->
                    ActivityTaskManagerCompat.setTaskResizeable(task.taskId, mode)
                        .onFailure { NeXtepLog.warn("slot_close","Original resize mode restore deferred task=${task.taskId}",it) }
                }
                record.exitTask = null
                updateDraggedSlot(drag.slotIndex, SlotState.Ready(drag.displayId), null)
                NeXtepLog.info("slot_close",
                    "Dismissed real task=${task.taskId} slot=${drag.slotIndex} binderFallback=$usedBinderFallback")
                showMessage(AppLanguage.string(applicationContext, R.string.window_backgrounded))
            } catch (error: Throwable) {
                // If Shell actually completed the move, never bounce a real
                // task back to a mini-window just because a post-check failed.
                if (SlotDismissDecisionPolicy.mayPublishVacantSlot(
                        taskRepository.findTask(task.taskId)?.displayId)) {
                    if (SlotDismissDecisionPolicy.shouldRestoreMain(
                            usedBinderFallback, foreground?.taskId, task.taskId,
                            foreground?.let { taskRepository.findTask(it.taskId)?.displayId })) {
                        runCatching { taskRepository.bringTaskToFront(checkNotNull(foreground).taskId).getOrThrow() }
                            .onFailure { NeXtepLog.warn("slot_close", "Main z-order recovery pending", it) }
                    }
                    NeXtepLog.warn("slot_close",
                        "Parking committed despite a nonfatal follow-up error task=${task.taskId}",error)
                    record.exitTask = null
                    updateDraggedSlot(drag.slotIndex, SlotState.Ready(drag.displayId), null)
                    return@runTransition
                }
                runCatching {
                    moveTaskToDisplayAndWait(task.taskId, drag.displayId)
                    ensureSlotLayout(drag.slotIndex, task, layout)
                }.onFailure(error::addSuppressed)
                throw error
            }
        }
    }

    private fun waitForSlotDismissDisplay(taskId: Int): Boolean {
        val deadline = SystemClock.uptimeMillis() + DISPLAY_MOVE_TIMEOUT_MS
        var displayId = taskRepository.findTask(taskId)?.displayId
        while (displayId != Display.DEFAULT_DISPLAY &&
            SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(DISPLAY_MOVE_POLL_MS)
            displayId = taskRepository.findTask(taskId)?.displayId
        }
        return displayId == Display.DEFAULT_DISPLAY
    }

    override fun onSlotDropped(slotIndex: Int, drag: SlotTaskDrag): Boolean {
        if (activeDrag != drag || !validActiveSlot(slotIndex) || !matchesDrag(drag)) return false
        if (slotIndex == drag.slotIndex) return true
        val target = slotRecords[slotIndex].state
        if (target !is SlotState.Ready && target !is SlotState.Occupied) return false
        return runTransition("drag slot=${drag.slotIndex} to slot=$slotIndex", drag to slotIndex) {
            check(matchesDrag(drag) && slotRecords[slotIndex].state == target) { "Drag target changed" }
            swapSlots(drag.slotIndex, slotIndex)
        }.isSuccess
    }

    fun dropSlotIntoMain(drag: SlotTaskDrag): Boolean {
        if (activeDrag != drag || !matchesDrag(drag) || !active || suspended || recentSelectionPending) return false
        val mainTaskId = taskRepository.foregroundTask()?.taskId
        return runTransition("drag slot=${drag.slotIndex} to main", drag to null) {
            check(matchesDrag(drag) && taskRepository.foregroundTask()?.taskId == mainTaskId) { "Main task changed during drag" }
            swapWithMain(drag.slotIndex, slotRecords[drag.slotIndex].state as SlotState.Occupied)
        }.isSuccess
    }

    private fun matchesDrag(drag: SlotTaskDrag): Boolean {
        val state = slotRecords.getOrNull(drag.slotIndex)?.state as? SlotState.Occupied ?: return false
        return state.taskId == drag.taskId && state.displayId == drag.displayId &&
            slots[drag.slotIndex].displayId() == drag.displayId &&
            taskRepository.findTask(drag.taskId)?.displayId == drag.displayId
    }

    fun cancelPendingExchange() {
        exchangeSession?.takeUnless { it.settled }?.let { it.cancel(); return }
        if (!visualTransitionPending) return
        transitionGeneration += 1
        visualTransitionPending = false
        transitionInProgress = false
        pendingSelection = null
        slots.forEach { it.view.setBusy(suspended || recentSelectionPending) }
    }

    private fun swapSlots(sourceIndex: Int, targetIndex: Int) {
        val sourceState = slotRecords[sourceIndex].state as SlotState.Occupied
        val targetState = slotRecords[targetIndex].state
        val sourceTask = checkNotNull(taskRepository.findTask(sourceState.taskId))
        val targetOccupied = targetState as? SlotState.Occupied
        val targetTask = targetOccupied?.let { checkNotNull(taskRepository.findTask(it.taskId)) }
        val targetDisplay = checkNotNull(slots[targetIndex].displayId())
        check(sourceTask.displayId == sourceState.displayId)
        check(targetTask == null || targetTask.displayId == targetDisplay)
        val sourceRecord = slotRecords[sourceIndex]
        val targetRecord = slotRecords[targetIndex]
        val sourceLayout = sourceRecord.originalLayout ?: defaultMainLayout(sourceTask.resizeMode)
        val targetLayout = targetTask?.let { targetRecord.originalLayout ?: defaultMainLayout(it.resizeMode) }
        try {
            // Both tasks stay on virtual displays; no temporary foreground app is
            // exposed on display 0. Preview frames cover the intermediate reparent.
            if (targetTask != null) moveTaskToDisplayAndWait(targetTask.taskId, sourceState.displayId)
            moveTaskToDisplayAndWait(sourceTask.taskId, targetDisplay)
            targetRecord.originalLayout = sourceLayout
            ensureSlotLayout(targetIndex, sourceTask, sourceLayout)
            if (targetTask != null) {
                sourceRecord.originalLayout = targetLayout
                ensureSlotLayout(sourceIndex, targetTask, targetLayout)
            }
            updateDraggedSlot(targetIndex, SlotState.Occupied(targetDisplay, sourceTask.taskId, sourceTask.component), sourceLayout)
            updateDraggedSlot(sourceIndex, if (targetTask != null) {
                SlotState.Occupied(sourceState.displayId, targetTask.taskId, targetTask.component)
            } else SlotState.Ready(sourceState.displayId), targetLayout)
        } catch (error: Throwable) {
            runCatching {
                moveTaskToDisplayAndWait(sourceTask.taskId, sourceState.displayId)
                sourceRecord.originalLayout = sourceLayout
                ensureSlotLayout(sourceIndex, sourceTask, sourceLayout)
            }.onFailure(error::addSuppressed)
            if (targetTask != null) runCatching {
                moveTaskToDisplayAndWait(targetTask.taskId, targetDisplay)
                targetRecord.originalLayout = targetLayout
                ensureSlotLayout(targetIndex, targetTask, targetLayout)
            }.onFailure(error::addSuppressed)
            updateDraggedSlot(sourceIndex, sourceState, sourceLayout)
            updateDraggedSlot(targetIndex, targetState, targetLayout)
            throw error
        }
    }

    private fun updateDraggedSlot(index: Int, state: SlotState, layout: TaskLayout?) {
        val record = slotRecords[index]
        record.retainedTaskId = (state as? SlotState.Occupied)?.taskId
        record.originalLayout = if (state is SlotState.Occupied) layout else null
        record.pendingComponent = null
        record.pendingAttempts = 0
        record.migrationRecoveryComponent = null
        record.migrationRecoveryDeadline = 0L
        record.migrationRecoveryAttempts = 0
        record.mismatchSince = 0L
        setState(index, state, "drag exchange")
    }

    private fun assignIntent(slotIndex: Int, sourceIntent: Intent, preferredTaskId: Int? = null): Result<Unit> {
        if (!validActiveSlot(slotIndex)) {
            return Result.failure(IllegalStateException("Slot $slotIndex is unavailable"))
        }
        val normalized = normalizeLaunchIntent(sourceIntent)
            ?: return Result.failure(IllegalArgumentException("Drop does not resolve to an App"))
        val component = checkNotNull(normalized.component)
        if (slotRecords[slotIndex].pendingComponent != null) {
            return Result.failure(IllegalStateException("Slot $slotIndex launch is pending"))
        }
        val targetDisplayId = slots[slotIndex].displayId()
            ?: return Result.failure(IllegalStateException("Slot $slotIndex display is pending"))
        val existing = preferredTaskId?.let(taskRepository::findTask)?.takeIf {
            it.component.packageName == component.packageName &&
                it.userId == normalized.targetUser()?.let(io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat::userIdentifier)
        } ?: findLaunchTask(component, normalized.targetUser())
        val occupied = slotRecords[slotIndex].state as? SlotState.Occupied

        // The ported Y700 ROM may own a WeChat task as a native dual-pane
        // window even while reporting display 0/fullscreen. Never coerce
        // that real task into a synthetic slot or rewrite resize metadata.
        val physical=geometryProvider()
        if (existing != null &&
            io.github.x647368maker.atomicworkbench.workspace.StageTabletWeChatPolicy
                .nativeWindowMustRemainUntouched(physical.screenWidth,physical.screenHeight,
                    existing.component.packageName,existing.windowingMode,existing.vendorWindowed)) {
            NeXtepLog.warn("y700_wechat",
                "Preserving native WeChat task id=${existing.taskId} display=${existing.displayId} mode=${existing.windowingMode} vendor=${existing.vendorWindowed}")
            return Result.failure(IllegalStateException(
                "Y700 微信处于系统双窗模式：已保留原微信任务，不强制拉伸或迁移；请先在主窗使用"))
        }

        if (existing?.displayId == targetDisplayId) {
            slotRecords[slotIndex].retainedTaskId = existing.taskId
            val layoutResult = runCatching { ensureSlotLayout(slotIndex, existing) }
            if (layoutResult.isFailure) {
                return Result.failure(checkNotNull(layoutResult.exceptionOrNull()))
            }
            setState(
                slotIndex,
                SlotState.Occupied(targetDisplayId, existing.taskId, existing.component),
                "drop matched target task",
            )
            return Result.success(Unit)
        }

        if (occupied != null) {
            val foreground = taskRepository.foregroundTask()
            if (existing != null && foreground?.taskId == existing.taskId) {
                return runTransition("drop main into occupied slot=$slotIndex", mainSlotIndex = slotIndex) {
                    swapWithMain(slotIndex, occupied)
                }
            }
            slots[slotIndex].view.showState(occupied)
            return Result.failure(IllegalStateException("Slot $slotIndex is occupied"))
        }

        if (existing != null) {
            return runTransition("move dropped taskId=${existing.taskId} slot=$slotIndex") {
                val wasPresented = mainTaskPresenter.currentTaskId() == existing.taskId
                val sourceSlot = slotRecords.indexOfFirst { record ->
                    (record.state as? SlotState.Occupied)?.taskId == existing.taskId
                }
                var restoredTask = existing
                var originalLayout = sourceSlot.takeIf { it >= 0 }
                    ?.let { slotRecords[it].originalLayout }
                    ?: defaultMainLayout(existing.resizeMode)
                var moved = false
                try {
                    mainTaskPresenter.restoreForMove(existing.taskId).getOrThrow()
                    restoredTask = taskRepository.findTask(existing.taskId) ?: existing
                    originalLayout = if (sourceSlot >= 0) {
                        slotRecords[sourceSlot].originalLayout
                    } else {
                        restoredTask.toLayout()
                    } ?: defaultMainLayout(restoredTask.resizeMode)
                    ActivityTaskManagerCompat.setTaskResizeable(
                        existing.taskId,
                        TASK_RESIZE_MODE_RESIZEABLE,
                    ).getOrThrow()
                    moveTaskToDisplayAndWait(existing.taskId, targetDisplayId)
                    moved = true
                    slotRecords[slotIndex].originalLayout = originalLayout
                    ensureSlotLayout(slotIndex, restoredTask, originalLayout)
                } catch (error: Throwable) {
                    slotRecords[slotIndex].originalLayout = null
                    if (moved) {
                        runCatching {
                            moveTaskToDisplayAndWait(
                                existing.taskId,
                                existing.displayId,
                            )
                            if (sourceSlot >= 0) {
                                ensureSlotLayout(sourceSlot, restoredTask, originalLayout)
                            } else {
                                restoreTaskLayout(restoredTask, originalLayout)
                            }
                        }.onFailure(error::addSuppressed)
                    }
                    if (wasPresented) {
                        mainTaskPresenter.presentTask(existing.taskId, geometryProvider())
                            .onFailure(error::addSuppressed)
                    }
                    throw error
                }
                if (sourceSlot >= 0 && sourceSlot != slotIndex) {
                    clearSlotAssignment(sourceSlot)
                    val sourceDisplayId = slots[sourceSlot].displayId()
                    setState(
                        sourceSlot,
                        sourceDisplayId?.let { SlotState.Ready(it) } ?: SlotState.Empty,
                        "task moved to another slot",
                    )
                }
                slotRecords[slotIndex].retainedTaskId = existing.taskId
                setState(
                    slotIndex,
                    SlotState.Occupied(targetDisplayId, existing.taskId, existing.component),
                    "existing task assigned",
                )
            }
        }

        slotRecords[slotIndex].pendingComponent = component
        slotRecords[slotIndex].pendingAttempts = 0
        slots[slotIndex].view.showWaiting()
        return runTransition("launch component=$component slot=$slotIndex") {
            try {
                slots[slotIndex].launch(normalized).getOrThrow()
                handler.postDelayed({ reconcile("launch requested") }, SETTLE_DELAY_MS)
            } catch (error: Throwable) {
                slotRecords[slotIndex].pendingComponent = null
                setState(
                    slotIndex,
                    SlotState.Failed("launch failed"),
                    error.message ?: "launch failed",
                )
                throw error
            }
        }
    }

    private fun parkMainTaskInSlot(
        slotIndex: Int,
        ready: SlotState.Ready,
        mainTask: TaskRepository.TaskSnapshot,
        leaveOverview: Boolean = true,
    ) {
        val targetDisplayId = slots[slotIndex].displayId()
            ?: error("Slot $slotIndex display is unavailable")
        check(targetDisplayId == ready.displayId) {
            "Slot $slotIndex display changed ${ready.displayId}->$targetDisplayId"
        }
        val record = slotRecords[slotIndex]
        val wasPresented = mainTaskPresenter.currentTaskId() == mainTask.taskId
        var restoredMainTask = mainTask
        var mainOriginalLayout = mainTask.toLayout()
        var preparedForMove = false
        var movedToSlot = false
        try {
            preparedForMove = true
            restoredMainTask = taskRepository.findTask(mainTask.taskId) ?: mainTask
            mainOriginalLayout = restoredMainTask.toLayout()
            if (restoredMainTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                ActivityTaskManagerCompat.setTaskResizeable(
                    mainTask.taskId,
                    TASK_RESIZE_MODE_RESIZEABLE,
                ).getOrThrow()
            }
            record.migrationRecoveryComponent = mainTask.component
            record.migrationRecoveryDeadline =
                SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
            record.migrationRecoveryAttempts = 0
            moveTaskToDisplayAndWait(mainTask.taskId, targetDisplayId)
            movedToSlot = true
            // Never expose the identity-sized task on Display 0 between restore and move.
            // Its logical layout is unchanged by the presentation transform.
            mainTaskPresenter.restoreForMove(mainTask.taskId).getOrThrow()
            record.originalLayout = mainOriginalLayout
            ensureSlotLayout(slotIndex, restoredMainTask, mainOriginalLayout)
            if (leaveOverview) showHomeOnDefaultDisplay()

            slotRecords.forEachIndexed { index, otherRecord ->
                val occupiedTaskId = (otherRecord.state as? SlotState.Occupied)?.taskId
                if (index != slotIndex &&
                    (otherRecord.retainedTaskId == mainTask.taskId ||
                        occupiedTaskId == mainTask.taskId)
                ) {
                    otherRecord.retainedTaskId = null
                    otherRecord.pendingComponent = null
                    otherRecord.pendingAttempts = 0
                    otherRecord.originalLayout = null
                    val displayId = slots[index].displayId()
                    setState(
                        index,
                        displayId?.let(SlotState::Ready) ?: SlotState.Empty,
                        "duplicate parked task ownership cleared",
                    )
                }
            }
            record.retainedTaskId = mainTask.taskId
            setState(
                slotIndex,
                SlotState.Occupied(targetDisplayId, mainTask.taskId, mainTask.component),
                "main task parked in empty slot",
            )
        } catch (error: Throwable) {
            record.retainedTaskId = null
            record.originalLayout = null
            record.migrationRecoveryComponent = null
            record.migrationRecoveryDeadline = 0L
            record.migrationRecoveryAttempts = 0
            val rollbackErrors = io.github.x647368maker.atomicworkbench.systemui.StageTaskMigrationPolicy.rollback({
                if (preparedForMove) {
                    // Query actual ownership even when a Binder call moved then threw.
                    if (movedToSlot || taskRepository.findTask(mainTask.taskId)?.displayId == targetDisplayId) {
                        moveTaskToDisplayAndWait(mainTask.taskId, Display.DEFAULT_DISPLAY)
                    }
                    restoreTaskLayout(restoredMainTask, mainOriginalLayout)
                }
            }, {
                if (wasPresented) mainTaskPresenter.presentTask(mainTask.taskId, geometryProvider()).getOrThrow()
            })
            if (rollbackErrors.isEmpty()) {
                setState(slotIndex, ready, "empty slot parking rolled back")
            } else {
                setState(
                    slotIndex,
                    SlotState.Failed("rollback failed"),
                    "empty slot parking rollback incomplete",
                )
                rollbackErrors.forEach(error::addSuppressed)
            }
            throw error
        }
    }

    private fun showHomeOnDefaultDisplay() {
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
            ?: error("No resolved HOME package")
        // Moving the foreground task off display 0 normally resumes HOME already.
        // Starting it again can create a ColorOS whole-display change transition.
        if (taskRepository.foregroundTask()?.component?.packageName == homePackage) return
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(homePackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        applicationContext.startActivity(homeIntent)
    }

    private fun isExchangeableMainTask(task: TaskRepository.TaskSnapshot?): Boolean {
        val mainTask = task ?: return false
        val homePackage = TriggerBroadcastContract.resolveHomePackage(applicationContext)
            ?: return false
        val packageName = mainTask.component.packageName
        return mainTask.displayId == Display.DEFAULT_DISPLAY &&
            !mainTask.vendorWindowed && mainTask.windowingMode == fullscreenWindowingMode() &&
            packageName != homePackage &&
            packageName != TriggerBroadcastContract.SYSTEM_UI_PACKAGE
    }

    private fun <T> timed(name:String,block:()->T):T = io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.phase(name,block)

    private fun swapWithMain(slotIndex: Int, occupied: SlotState.Occupied) {
        val slotTask = timed("source_lookup") { taskRepository.findTask(occupied.taskId) }
            ?: error("Slot task ${occupied.taskId} exited")
        val mainTask = timed("main_lookup") { taskRepository.foregroundTask() }
        val record = slotRecords[slotIndex]
        val slotOriginalLayout = record.originalLayout
            ?: defaultMainLayout(slotTask.resizeMode)
        val mainIsExchangeable = mainTask != null &&
            isExchangeableMainTask(mainTask) &&
            mainTask.taskId != slotTask.taskId

        var slotMovedToMain = false
        var mainMovedToSlot = false
        var restoredMainTask: TaskRepository.TaskSnapshot? = null
        var mainOriginalLayout: TaskLayout? = null
        try {
            promotedRecoveryTaskId = slotTask.taskId
            promotedRecoveryUserId = slotTask.userId
            promotedRecoveryComponent = slotTask.component
            promotedRecoveryDeadline =
                SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
            promotedRecoveryAttempts = 0
            if (mainIsExchangeable) {
                restoredMainTask = timed("outgoing_lookup") { taskRepository.findTask(mainTask.taskId) } ?: mainTask
                mainOriginalLayout = restoredMainTask.toLayout()
                if (restoredMainTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                    ActivityTaskManagerCompat.setTaskResizeable(
                        mainTask.taskId,
                        TASK_RESIZE_MODE_RESIZEABLE,
                    ).getOrThrow()
                }
            }

            if (slotTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                ActivityTaskManagerCompat.setTaskResizeable(
                    slotTask.taskId,
                    TASK_RESIZE_MODE_RESIZEABLE,
                ).getOrThrow()
            }
            timed("promote_display") { moveTaskToDisplayAndWait(slotTask.taskId, Display.DEFAULT_DISPLAY) }
            slotMovedToMain = true
            timed("incoming_layout") { restoreTaskLayout(slotTask, slotOriginalLayout, restoreResizeMode = false) }
            timed("bring_front") { taskRepository.bringTaskToFront(slotTask.taskId).getOrThrow() }

            if (mainIsExchangeable) {
                record.migrationRecoveryComponent = mainTask.component
                record.migrationRecoveryDeadline =
                    SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
                record.migrationRecoveryAttempts = 0
                timed("demote_display") { moveTaskToDisplayAndWait(mainTask.taskId, occupied.displayId) }
                mainMovedToSlot = true
                timed("outgoing_surface_restore") { mainTaskPresenter.restoreForMove(mainTask.taskId).getOrThrow() }
                record.originalLayout = mainOriginalLayout
                timed("slot_layout_and_fit") { ensureSlotLayout(slotIndex, checkNotNull(restoredMainTask), mainOriginalLayout) }
                record.retainedTaskId = mainTask.taskId
                timed("publish_state") { setState(
                    slotIndex,
                    SlotState.Occupied(
                        occupied.displayId,
                        mainTask.taskId,
                        mainTask.component,
                    ),
                    "main and slot exchanged",
                ) }
            } else {
                // Promotion intentionally emptied the slot. An old migration
                // recovery would otherwise relaunch this live task back into it,
                // leaving the visible '+' blocked by pendingComponent.
                clearSlotAssignment(slotIndex)
                timed("publish_state") { setState(slotIndex, SlotState.Ready(occupied.displayId), "slot promoted to main") }
            }
            val presentation = timed("incoming_presentation") { mainTaskPresenter.presentTask(slotTask.taskId, geometryProvider()) }
            if (presentation.isFailure && taskRepository.findTask(slotTask.taskId) != null) {
                presentation.getOrThrow()
            }
            timed("schedule_recovery") { schedulePromotedTaskRecovery() }
        } catch (error: Throwable) {
            timed("rollback") {
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
                timed("publish_state") { setState(slotIndex, occupied, "swap rolled back") }
            } else {
                slotRecords[slotIndex].retainedTaskId = null
                slotRecords[slotIndex].originalLayout = null
                timed("publish_state") { setState(slotIndex, SlotState.Failed("rollback failed"), "swap rollback incomplete") }
                rollbackErrors.forEach(error::addSuppressed)
            }
            throw error
            }
        }
    }

    private fun runPhasedMainTransition(slotIndex: Int, expectedMainTaskId: Int): Result<Unit> {
        if (!active || suspended || transitionInProgress || recentSelectionPending) {
            return Result.failure(IllegalStateException("Another transition is settling"))
        }
        val occupied = slotRecords[slotIndex].state as? SlotState.Occupied
            ?: return Result.failure(IllegalStateException("Slot changed"))
        val token = ++transitionGeneration
        StageExchangeTelemetry.beginPhased("slot=$slotIndex")
        confirmedOutput=null
        transitionInProgress = true
        visualTransitionPending = true
        pendingSelection = null
        clearPromotedTaskRecovery()
        slots.forEach { it.view.setBusy(true) }
        var moveRequested = false
        var animationFinished = false
        fun finish() {
            if (token != transitionGeneration) return
            if (exchangeSession?.settled == false) return
            if (!animationFinished) return
            StageExchangeTelemetry.endPhased(exchangeSession?.phase == ExchangeSession.Phase.COMMITTED)
            confirmedOutput=null
            exchangeSession = null
            visualTransitionPending = false
            transitionInProgress = false
            slots.forEach { it.view.setBusy(suspended || recentSelectionPending) }
            val actions = exchangeEndActions.toList()
            exchangeEndActions.clear()
            actions.forEach { exchangeHandler.post(it) }
            if (active && !suspended) {
                handler.post { reconcile("phased exchange settled") }
                dispatchPendingSelection()
            }
        }
        return runCatching {
            checkNotNull(onPhasedMainSlotAnimation).invoke(slotIndex, { complete ->
                if (moveRequested || token != transitionGeneration || !active || suspended) {
                    complete(false)
                } else {
                    moveRequested = true
                    try {
                        check(slotRecords[slotIndex].state == occupied)
                        startPhasedSwap(slotIndex, occupied, token, expectedMainTaskId) { success ->
                            try {
                                complete(success)
                            } catch (error: Throwable) {
                                NeXtepLog.error("slot_coordinator", "Exchange preview completion failed", error)
                            } finally {
                                finish()
                            }
                        }
                    } catch (error: Throwable) {
                        NeXtepLog.error("slot_coordinator", "Exchange preparation failed", error)
                        complete(false)
                    }
                }
            }, { exchangeSession?.settled != false }, {
                animationFinished = true
                finish()
            })
        }.onFailure {
            animationFinished = true
            exchangeSession?.cancel()
            finish()
        }
    }

    private fun startPhasedSwap(slotIndex: Int, occupied: SlotState.Occupied, token: Int, expectedMainTaskId: Int?, complete: (Boolean) -> Unit) {
        StageExchangeTelemetry.phasedEvent("snapshot_start")
        val initialTasks = taskRepository.runningTasks()
        val slotTask = initialTasks.firstOrNull { it.taskId == occupied.taskId } ?: error("Slot task exited")
        val mainTask = initialTasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } ?: error("Main task exited")
        check(mainTask.taskId == expectedMainTaskId) { "Main task changed during preview" }
        check(isExchangeableMainTask(mainTask) && mainTask.taskId != slotTask.taskId)
        checkNotNull(slotTask.token)
        checkNotNull(mainTask.token)
        val record = slotRecords[slotIndex]
        val slotLayout = record.originalLayout ?: defaultMainLayout(slotTask.resizeMode)
        val mainLayout = mainTask.toLayout()
        val exchangeGeometry = geometryProvider()
        StageExchangeTelemetry.phasedEvent("snapshot_ready")

        var mutationStarted = false
        fun current(expected: TaskRepository.TaskSnapshot, tasks: List<TaskRepository.TaskSnapshot>): TaskRepository.TaskSnapshot {
            val task = tasks.firstOrNull { it.taskId == expected.taskId } ?: error("Exchange task disappeared")
            check(task.userId == expected.userId && task.token == expected.token) { "Exchange identity changed" }
            return task
        }

        val session = ExchangeSession(
            token, SystemClock.uptimeMillis() + 4_000L,
            ExchangeSession.Origin(mainTask.taskId, mainTask.userId, mainTask.token, mainTask.displayId, mainLayout),
            ExchangeSession.Origin(slotTask.taskId, slotTask.userId, slotTask.token, occupied.displayId, slotLayout),
            SystemClock::uptimeMillis,
            { action -> exchangeHandler.post(action) },
            object : ExchangeSession.Driver {
                // PR64: the normal tapped exchange must never sleep through
                // multiple Choreographer frames on the SystemUI main Looper.
                // The old synchronous methods below remain for rollback
                // safety and non-tap/legacy callers.
                override val nonBlocking: Boolean get() = true
                override fun promoteAsync(done: (Throwable?) -> Unit) {
                    val preliminary = runCatching {
                        StageExchangeTelemetry.phasedEvent("promote_start")
                        check(token == transitionGeneration && active && !suspended)
                        check(geometryProvider() == exchangeGeometry)
                        val before = taskRepository.runningTasks()
                        check(current(slotTask, before).displayId == occupied.displayId)
                        check(before.firstOrNull { it.displayId == occupied.displayId }?.taskId == slotTask.taskId)
                        check(current(mainTask, before).displayId == Display.DEFAULT_DISPLAY)
                        check(before.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.taskId == mainTask.taskId)
                        check(current(mainTask, before).toLayout() == mainLayout)
                        mutationStarted = true
                        if (mainTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE)
                            ActivityTaskManagerCompat.setTaskResizeable(mainTask.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
                        if (slotTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE)
                            ActivityTaskManagerCompat.setTaskResizeable(slotTask.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
                    }.exceptionOrNull()
                    if (preliminary != null) { done(preliminary); return }
                    moveTaskToDisplayNonBlocking(slotTask, Display.DEFAULT_DISPLAY) { result ->
                        val observed=result.getOrElse { done(it); return@moveTaskToDisplayNonBlocking }
                        val stale = runCatching {
                            check(token == transitionGeneration && active && !suspended)
                        }.exceptionOrNull()
                        if (stale != null) { done(stale); return@moveTaskToDisplayNonBlocking }
                        restoreTaskLayoutNonBlocking(slotTask, slotLayout, restoreResizeMode = false,
                            observed=observed) { layoutError ->
                            if (layoutError != null) { done(layoutError); return@restoreTaskLayoutNonBlocking }
                            done(runCatching {
                                check(token == transitionGeneration && active && !suspended)
                                taskRepository.bringTaskToFront(slotTask.taskId).getOrThrow()
                                StageExchangeTelemetry.phasedEvent("promote_complete")
                            }.exceptionOrNull())
                        }
                    }
                }

                override fun demoteAndVerifyAsync(done: (Throwable?) -> Unit) {
                    val preliminary = runCatching {
                        StageExchangeTelemetry.phasedEvent("demote_start")
                        check(token == transitionGeneration && active && !suspended)
                        check(geometryProvider() == exchangeGeometry)
                        check(slots[slotIndex].displayId() == occupied.displayId)
                        val promoted = taskRepository.runningTasks()
                        check(current(slotTask, promoted).displayId == Display.DEFAULT_DISPLAY)
                        check(current(mainTask, promoted).displayId == Display.DEFAULT_DISPLAY)
                        check(promoted.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.taskId == slotTask.taskId)
                    }.exceptionOrNull()
                    if (preliminary != null) { done(preliminary); return }
                    var observedFront:TaskRepository.TaskSnapshot?=null
                    moveTaskToDisplayNonBlocking(mainTask, occupied.displayId,
                        observedDemotionSlot=slotIndex,
                        onObservedDestinationFront={observedFront=it}) { result ->
                        val observed=result.getOrElse { done(it); return@moveTaskToDisplayNonBlocking }
                        val preliminary = runCatching {
                            check(token == transitionGeneration && active && !suspended)
                            mainTaskPresenter.restoreForMove(mainTask.taskId).getOrThrow()
                        }.exceptionOrNull()
                        if (preliminary != null) { done(preliminary); return@moveTaskToDisplayNonBlocking }
                        ensureSlotLayoutNonBlocking(slotIndex, mainTask, mainLayout, observed=observed) { layoutError ->
                            if (layoutError != null) { done(layoutError); return@ensureSlotLayoutNonBlocking }
                            done(runCatching {
                                check(token == transitionGeneration && active && !suspended)
                                // Sample immediately after the exact verified foreground task's
                                // existing target layout fit, BEFORE main presentation work.
                                // A WCT/layout mismatch cannot opt into this checkpoint.
                                val earlyCanvas=slots[slotIndex].geometry()
                                val earlyMark=if(observedFront===observed && earlyCanvas!=null &&
                                    observed.resizeMode==TASK_RESIZE_MODE_RESIZEABLE &&
                                    !observed.vendorWindowed && observed.windowingMode==fullscreenWindowingMode() &&
                                    observed.bounds==Rect(0,0,earlyCanvas.width,earlyCanvas.height) &&
                                    observed.densityDpi==earlyCanvas.densityDpi &&
                                    observed.displayId==occupied.displayId) {
                                    slots[slotIndex].outputFrameMark()?.takeIf{it.displayId==occupied.displayId}
                                } else null
                                mainTaskPresenter.presentTask(slotTask.taskId, geometryProvider()).getOrThrow()
                                val committed = taskRepository.runningTasks()
                                check(current(slotTask, committed).displayId == Display.DEFAULT_DISPLAY)
                                val parked=current(mainTask, committed)
                                check(parked.displayId == occupied.displayId)
                                // Earlier evidence is optional, requiring the actual foreground
                                // slot task and final canvas, not merely a display move ACK.
                                val canvas=slots[slotIndex].geometry()
                                confirmedOutput=if (canvas != null &&
                                    committed.firstOrNull { it.displayId==occupied.displayId }?.taskId==mainTask.taskId &&
                                    !parked.vendorWindowed && parked.windowingMode==fullscreenWindowingMode() &&
                                    parked.bounds==Rect(0,0,canvas.width,canvas.height) &&
                                    parked.densityDpi==canvas.densityDpi) {
                                    slots[slotIndex].outputFrameMark()?.takeIf {
                                        it.displayId==occupied.displayId
                                    }?.let { finalMark ->
                                        val earlier=earlyMark?.takeIf {
                                            StageEarlySlotOutputPolicy.allowEarlier(
                                                it.displayId,finalMark.displayId,it.texture,finalMark.texture,
                                                it.surface,finalMark.surface,it.sequence,finalMark.sequence,
                                                earlyCanvas==canvas)
                                        }
                                        if(earlier!=null)
                                            StageExchangeTelemetry.phasedEvent("slot_early_verified_output_mark")
                                        ConfirmedOutput(token,slotIndex,mainTask.taskId,earlier?:finalMark)
                                    }
                                } else null
                                StageExchangeTelemetry.phasedEvent(if (confirmedOutput!=null)
                                    "slot_verified_output_mark" else "slot_output_mark_deferred")
                                record.originalLayout = mainLayout
                                record.retainedTaskId = mainTask.taskId
                                record.migrationRecoveryComponent = null
                                record.migrationRecoveryDeadline = 0L
                                record.migrationRecoveryAttempts = 0
                                setState(slotIndex, SlotState.Occupied(occupied.displayId, mainTask.taskId, mainTask.component),
                                    "phased exchange committed")
                                promotedRecoveryTaskId = slotTask.taskId
                                promotedRecoveryUserId = slotTask.userId
                                promotedRecoveryComponent = slotTask.component
                                promotedRecoveryDeadline = SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
                                promotedRecoveryAttempts = 0
                                schedulePromotedTaskRecovery()
                                StageExchangeTelemetry.phasedEvent("demote_complete")
                            }.exceptionOrNull())
                        }
                    }
                }

                override fun promote() {
                    StageExchangeTelemetry.phasedEvent("promote_start")
                    check(token == transitionGeneration && active && !suspended)
                    check(geometryProvider() == exchangeGeometry)
                    val before = taskRepository.runningTasks()
                    check(current(slotTask, before).displayId == occupied.displayId)
                    check(before.firstOrNull { it.displayId == occupied.displayId }?.taskId == slotTask.taskId)
                    check(current(mainTask, before).displayId == Display.DEFAULT_DISPLAY)
                    check(before.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.taskId == mainTask.taskId)
                    check(current(mainTask, before).toLayout() == mainLayout)
                    mutationStarted = true
                    if (mainTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                        ActivityTaskManagerCompat.setTaskResizeable(mainTask.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
                    }
                    if (slotTask.resizeMode != TASK_RESIZE_MODE_RESIZEABLE) {
                        ActivityTaskManagerCompat.setTaskResizeable(slotTask.taskId, TASK_RESIZE_MODE_RESIZEABLE).getOrThrow()
                    }
                    moveTaskToDisplayAndWait(slotTask.taskId, Display.DEFAULT_DISPLAY)
                    restoreTaskLayout(slotTask, slotLayout, restoreResizeMode = false)
                    taskRepository.bringTaskToFront(slotTask.taskId).getOrThrow()
                    StageExchangeTelemetry.phasedEvent("promote_complete")
                }

                override fun demoteAndVerify() {
                    StageExchangeTelemetry.phasedEvent("demote_start")
                    check(token == transitionGeneration && active && !suspended)
                    check(geometryProvider() == exchangeGeometry)
                    check(slots[slotIndex].displayId() == occupied.displayId)
                    val promoted = taskRepository.runningTasks()
                    check(current(slotTask, promoted).displayId == Display.DEFAULT_DISPLAY)
                    check(current(mainTask, promoted).displayId == Display.DEFAULT_DISPLAY)
                    check(promoted.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.taskId == slotTask.taskId)
                    moveTaskToDisplayAndWait(mainTask.taskId, occupied.displayId)
                    mainTaskPresenter.restoreForMove(mainTask.taskId).getOrThrow()
                    ensureSlotLayout(slotIndex, mainTask, mainLayout)
                    mainTaskPresenter.presentTask(slotTask.taskId, geometryProvider()).getOrThrow()
                    val committed = taskRepository.runningTasks()
                    check(current(slotTask, committed).displayId == Display.DEFAULT_DISPLAY)
                    check(current(mainTask, committed).displayId == occupied.displayId)
                    record.originalLayout = mainLayout
                    record.retainedTaskId = mainTask.taskId
                    record.migrationRecoveryComponent = null
                    record.migrationRecoveryDeadline = 0L
                    record.migrationRecoveryAttempts = 0
                    setState(slotIndex, SlotState.Occupied(occupied.displayId, mainTask.taskId, mainTask.component),
                        "phased exchange committed")
                    promotedRecoveryTaskId = slotTask.taskId
                    promotedRecoveryUserId = slotTask.userId
                    promotedRecoveryComponent = slotTask.component
                    promotedRecoveryDeadline = SystemClock.uptimeMillis() + MIGRATION_RECOVERY_WINDOW_MS
                    promotedRecoveryAttempts = 0
                    schedulePromotedTaskRecovery()
                    StageExchangeTelemetry.phasedEvent("demote_complete")
                }

                override fun restoreAndVerify() {
                    if (!mutationStarted) {
                        // Cancellation before our first Binder mutation is not proof that
                        // another actor has left both tasks at the captured origins.
                        val untouched = taskRepository.runningTasks()
                        check(slots[slotIndex].displayId() == occupied.displayId &&
                            current(slotTask, untouched).displayId == occupied.displayId &&
                            current(mainTask, untouched).displayId == Display.DEFAULT_DISPLAY &&
                            untouched.firstOrNull { it.displayId == occupied.displayId }?.taskId == slotTask.taskId &&
                            untouched.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.taskId == mainTask.taskId
                        ) { "Exchange origins changed before promotion; keep display reserved" }
                        return
                    }
                    fun remaining(expected: TaskRepository.TaskSnapshot): TaskRepository.TaskSnapshot? {
                        val live = taskRepository.findTask(expected.taskId)
                        if (live != null) {
                            check(live.userId == expected.userId && live.token == expected.token)
                            return live
                        }
                        val recent = taskRepository.recentTasks(checkNotNull(expected.userId)).getOrThrow()
                        check(recent.none { it.taskId == expected.taskId }) { "Task ownership cannot yet be verified" }
                        return null
                    }
                    val slotNow = remaining(slotTask)
                    val mainNow = remaining(mainTask)
                    check(slots[slotIndex].displayId() == occupied.displayId)
                    clearPromotedTaskRecovery()
                    mainTaskPresenter.restoreForMove(slotTask.taskId).getOrThrow()
                    if (slotNow != null) {
                        check(slotNow.displayId == 0 || slotNow.displayId == occupied.displayId)
                        if (slotNow.displayId != occupied.displayId) {
                            moveTaskToDisplayAndWait(slotTask.taskId, occupied.displayId)
                        }
                        ensureSlotLayout(slotIndex, slotTask, slotLayout)
                    }
                    if (mainNow != null) {
                        check(mainNow.displayId == 0 || mainNow.displayId == occupied.displayId)
                        if (mainNow.displayId != Display.DEFAULT_DISPLAY) {
                            moveTaskToDisplayAndWait(mainTask.taskId, Display.DEFAULT_DISPLAY)
                        }
                        restoreTaskLayout(mainTask, mainLayout)
                        if (!suspended) mainTaskPresenter.presentTask(mainTask.taskId, geometryProvider()).getOrThrow()
                    }
                    val restored = taskRepository.runningTasks()
                    if (slotNow != null) check(current(slotTask, restored).displayId == occupied.displayId)
                    if (mainNow != null) check(current(mainTask, restored).displayId == Display.DEFAULT_DISPLAY)
                    record.retainedTaskId = slotNow?.taskId
                    record.originalLayout = if (slotNow != null) slotLayout else null
                    record.migrationRecoveryComponent = null
                    record.migrationRecoveryDeadline = 0L
                    record.migrationRecoveryAttempts = 0
                    setState(slotIndex, if (slotNow != null) occupied else SlotState.Ready(occupied.displayId),
                        "phased exchange restored")

                }
            },
            complete,
        )
        exchangeSession = session
        session.start()
    }

    private fun runTransition(
        label: String,
        animation: Pair<SlotTaskDrag, Int?>? = null,
        mainSlotIndex: Int? = null,
        operation: () -> Unit,
    ): Result<Unit> {
        if (mainSlotIndex != null && slotRecords[mainSlotIndex].state is SlotState.Occupied &&
            onPhasedMainSlotAnimation != null) {
            val main = taskRepository.foregroundTask()
            if (isExchangeableMainTask(main)) {
                return runPhasedMainTransition(mainSlotIndex, checkNotNull(main).taskId)
            }
        }
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
                io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.measure("exchange_move") { operation() }
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
        if (exchangeSession?.settled == false) return
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
                if (slotRecords[index].state !is SlotState.Failed) setState(index, SlotState.Empty, "$reason: no display")
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
                    record.migrationRecoveryComponent = null
                    record.migrationRecoveryDeadline = 0L; record.migrationRecoveryAttempts = 0
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
                // ColorOS 16 phone-ROM port can redirect WeChat's NEW slot
                // launch to its own native display-0 dual-pane window. Reuse
                // that exact task; never retry by package (which might open a
                // second WeChat task) or force a new resize/migration.
                record.pendingComponent != null &&
                    tasks.any { task ->
                        io.github.x647368maker.atomicworkbench.workspace.StageTabletWeChatPolicy
                            .nativeMainRedirect(geometryProvider().screenWidth,
                                geometryProvider().screenHeight,
                                record.pendingComponent?.packageName,
                                task.component.packageName,task.displayId)
                    } -> {
                    record.pendingComponent=null
                    record.pendingAttempts=0
                    record.retainedTaskId=null
                    record.originalLayout=null
                    setState(index, SlotState.Ready(displayId),
                        "$reason: Y700 WeChat kept native main task")
                    NeXtepLog.info("y700_wechat",
                        "ColorOS redirected WeChat slot request to native main; preserved existing task")
                    mainTaskPresenter.reconcileForeground(geometryProvider(),tasks)
                        .onFailure { error ->
                            NeXtepLog.warn("y700_wechat", "Native main presentation unavailable",error)
                        }
                    mainReconciled=true
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
        if (suspended || transitionInProgress) return
        val promotedTask = promotedRecoveryTaskId?.let(taskRepository::findTask)?.takeIf {
            it.userId == promotedRecoveryUserId && it.component.packageName == component.packageName && it.displayId == Display.DEFAULT_DISPLAY
        }
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
        // A missing/reclaimed app is not permission to replay its launcher or media session.
        NeXtepLog.warn("task_continuity", "Promoted task unavailable id=$promotedRecoveryTaskId; no launch or media command issued")
        clearPromotedTaskRecovery()
    }

    private fun clearPromotedTaskRecovery() {
        promotedRecoveryGeneration += 1
        promotedRecoveryComponent = null
        promotedRecoveryTaskId = null; promotedRecoveryUserId = null
        promotedRecoveryDeadline = 0L
        promotedRecoveryAttempts = 0
    }

    private fun recoverMigratedTask(slotIndex: Int, record: SlotRecord) {
        record.migrationRecoveryAttempts += 1
        // Bounded observation only: never launch a replacement Activity during an exchange.
        if (record.migrationRecoveryAttempts < MAX_MIGRATION_RECOVERY_ATTEMPTS &&
            SystemClock.uptimeMillis() <= record.migrationRecoveryDeadline) return
        val id = record.retainedTaskId
        record.migrationRecoveryComponent = null; record.migrationRecoveryDeadline = 0L
        record.migrationRecoveryAttempts = 0
        if (id == null || taskRepository.findTask(id) == null) {
            clearSlotAssignment(slotIndex)
            val display = slots[slotIndex].displayId()
            setState(slotIndex, display?.let(SlotState::Ready) ?: SlotState.Empty, "task exited; explicit user launch required")
            NeXtepLog.warn("task_continuity", "Task unavailable id=$id; preserved user playback decision by not relaunching")
        }
    }

    private fun reconcileForeground(reason: String) {
        if (isExchangePending()) return
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
        targetUser ?: android.os.Process.myUserHandle(),
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
            if (restoreResizeMode) layout.resizeMode?.takeIf { it != current.resizeMode }?.let {
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

    /**
     * PR64: task state observation yields to the SystemUI frame Looper instead
     * of blocking for 30ms at every poll. Used only by the owned two-task
     * ExchangeSession, which retains generation checks and verified rollback.
     * Other moves retain the existing synchronous semantics.
     */
    /** On the active two-task exchange only, observe the layout without a main-thread sleep. */
    private fun waitForTaskLayoutNonBlocking(
        taskId: Int, bounds: Rect, densityDpi: Int, windowingMode: Int,
        done: (Throwable?) -> Unit,
    ) {
        val deadline = SystemClock.uptimeMillis() + TASK_LAYOUT_TIMEOUT_MS
        fun observe() {
            val result = runCatching {
                val snapshot = taskRepository.findTask(taskId)
                val matches = snapshot != null &&
                    snapshot.bounds == bounds && snapshot.densityDpi == densityDpi &&
                    snapshot.windowingMode == windowingMode
                if (matches) {
                    StageExchangeTelemetry.phasedEvent("layout_observed")
                    done(null)
                } else if (SystemClock.uptimeMillis() >= deadline) {
                    done(IllegalStateException(
                        "Async layout not settled task=$taskId bounds=$bounds density=$densityDpi " +
                            "mode=$windowingMode actual=${snapshot?.bounds}/${snapshot?.densityDpi}/${snapshot?.windowingMode}",
                    ))
                } else {
                    exchangeHandler.postDelayed({ observe() }, TASK_LAYOUT_POLL_MS)
                }
            }
            result.exceptionOrNull()?.let(done)
        }
        observe()
    }

    /** Maintains the exact layout/resize checks of restoreTaskLayout, but yields on confirmation. */
    private fun restoreTaskLayoutNonBlocking(
        task: TaskRepository.TaskSnapshot,
        layout: TaskLayout,
        restoreResizeMode: Boolean = true,
        observed: TaskRepository.TaskSnapshot? = null,
        done: (Throwable?) -> Unit,
    ) {
        val started = runCatching {
            val token = task.token ?: error("Task ${task.taskId} has no restore token")
            val current = observed ?: taskRepository.findTask(task.taskId)
            val mainFullscreen = current?.displayId == Display.DEFAULT_DISPLAY &&
                layout.windowingMode == fullscreenWindowingMode()
            val expectedBounds = if (mainFullscreen) geometryProvider().let {
                Rect(0, 0, it.screenWidth, it.screenHeight)
            } else layout.bounds
            fun finish() {
                done(runCatching {
                    if (restoreResizeMode) layout.resizeMode?.let { resizeMode ->
                        ActivityTaskManagerCompat.setTaskResizeable(task.taskId, resizeMode).getOrThrow()
                    }
                    StageExchangeTelemetry.phasedEvent("restore_layout_complete")
                }.exceptionOrNull())
            }
            if (mainFullscreen && current?.bounds == expectedBounds &&
                current.densityDpi == layout.densityDpi &&
                current.windowingMode == layout.windowingMode
            ) {
                finish()
            } else {
                WindowContainerTransactionCompat.applyTaskLayout(
                    token = token,
                    bounds = if (mainFullscreen) Rect() else layout.bounds,
                    densityDpi = layout.densityDpi,
                    windowingMode = layout.windowingMode,
                ).getOrThrow()
                StageExchangeTelemetry.phasedEvent("restore_layout_submitted")
                waitForTaskLayoutNonBlocking(task.taskId, expectedBounds,
                    layout.densityDpi, layout.windowingMode) { error ->
                    if (error != null) done(error) else finish()
                }
            }
        }
        started.exceptionOrNull()?.let(done)
    }

    /** Keeps the old slot geometry, WCT and input/crop application, with async observation. */
    private fun ensureSlotLayoutNonBlocking(
        slotIndex: Int, task: TaskRepository.TaskSnapshot,
        originalLayout: TaskLayout? = null,
        observed: TaskRepository.TaskSnapshot? = null,
        done: (Throwable?) -> Unit,
    ) {
        val started = runCatching {
            val slotGeometry = slots[slotIndex].geometry()
                ?: error("Slot $slotIndex geometry is unavailable")
            val fullscreenMode = fullscreenWindowingMode()
            val targetBounds = Rect(0, 0, slotGeometry.width, slotGeometry.height)
            val record = slotRecords[slotIndex]
            if (record.originalLayout == null) {
                record.originalLayout = originalLayout ?: if (task.displayId == Display.DEFAULT_DISPLAY)
                    task.toLayout() else defaultMainLayout(task.resizeMode)
            }
            val current = observed ?: if (task.displayId != slots[slotIndex].displayId())
                taskRepository.findTask(task.taskId) ?: error("Task ${task.taskId} exited")
            else task
            check(current.taskId==task.taskId && current.token==task.token &&
                current.userId==task.userId && current.displayId==slots[slotIndex].displayId())
            val resizeMismatch = current.resizeMode != TASK_RESIZE_MODE_RESIZEABLE
            val layoutMismatch = current.bounds != targetBounds ||
                current.densityDpi != slotGeometry.densityDpi ||
                current.windowingMode != fullscreenMode
            fun finish() {
                done(runCatching {
                    slots[slotIndex].displayId()?.let { displayId ->
                        val source = slots[slotIndex].sourceBounds()
                        TaskSurfaceCompat.fitSlotSurface(task.taskId, displayId,
                            Rect(source.left, source.top, source.right, source.bottom)).getOrThrow()
                    }
                    StageExchangeTelemetry.phasedEvent("slot_layout_complete")
                }.exceptionOrNull())
            }
            if (resizeMismatch) {
                ActivityTaskManagerCompat.setTaskResizeable(
                    task.taskId, TASK_RESIZE_MODE_RESIZEABLE,
                ).getOrThrow()
            }
            if (layoutMismatch) {
                val token = current.token ?: error("Task ${task.taskId} has no slot layout token")
                WindowContainerTransactionCompat.applyTaskLayout(
                    token = token, bounds = Rect(),
                    densityDpi = slotGeometry.densityDpi,
                    windowingMode = fullscreenMode,
                ).getOrThrow()
                StageExchangeTelemetry.phasedEvent("slot_layout_submitted")
                waitForTaskLayoutNonBlocking(task.taskId, targetBounds,
                    slotGeometry.densityDpi, fullscreenMode) { error ->
                    if (error != null) done(error) else finish()
                }
            } else finish()
        }
        started.exceptionOrNull()?.let(done)
    }

    private fun moveTaskToDisplayNonBlocking(
        expected: TaskRepository.TaskSnapshot, displayId: Int,
        observedDemotionSlot: Int? = null,
        onObservedDestinationFront: ((TaskRepository.TaskSnapshot)->Unit)? = null,
        done: (Result<TaskRepository.TaskSnapshot>) -> Unit,
    ) {
        val taskId=expected.taskId
        fun checkIdentity(task: TaskRepository.TaskSnapshot) {
            check(task.taskId==taskId && task.token==expected.token && task.userId==expected.userId) {
                "Exchange task identity changed"
            }
        }
        val started = runCatching {
            val before = taskRepository.findTask(taskId) ?: error("Exchange task disappeared")
            checkIdentity(before)
            if (!TaskContinuityPolicy.shouldMove(before.displayId, displayId)) {
                done(Result.success(before))
                return
            }
            StageExchangeTelemetry.phasedEvent("display_${displayId}_begin")
            TaskSurfaceCompat.markDisplayExchange(taskId, displayId)
            if (displayId == Display.DEFAULT_DISPLAY) {
                timed("promotion_prepare") {
                    TaskSurfaceCompat.preparePromotion(taskId, geometryProvider())
                }.onFailure { NeXtepLog.warn("task_surface", "Early promotion fitting unavailable", it) }
            } else {
                TaskSurfaceCompat.cancelPreparedPromotion(taskId)
                val target=observedDemotionSlot?.let { slots.getOrNull(it) }
                val canvas=target?.geometry()
                if (target!=null && canvas!=null && target.displayId()==displayId) {
                    TaskSurfaceCompat.prepareObservedDemotionFit(
                        taskId,displayId,expected.token,Rect(0,0,canvas.width,canvas.height),
                        canvas.densityDpi)
                }
            }
            // Keep the existing reservation and all UI-owned state on this
            // Looper. Only the blocking system IPC moves to the single-flight
            // worker. Its actual response re-enters the old observation path.
            fun afterBinder() {
                StageExchangeTelemetry.phasedEvent("display_${displayId}_binder_done")
                val deadline = SystemClock.uptimeMillis() + DISPLAY_MOVE_TIMEOUT_MS
                fun observe() {
                val result = runCatching {
                    // findTask already reads the full running task list. Keep
                    // the same single observation but reuse its foreground proof
                    // for the optional early buffer checkpoint, not another query.
                    val running=taskRepository.runningTasks()
                    val current=running.firstOrNull{it.taskId==taskId}
                    if (current != null) checkIdentity(current)
                    if (current != null && current.displayId == displayId) {
                        if(onObservedDestinationFront!=null &&
                            running.firstOrNull{it.displayId==displayId}?.taskId==taskId)
                            onObservedDestinationFront.invoke(current)
                        StageExchangeTelemetry.phasedEvent("display_${displayId}_observed")
                        NeXtepLog.info("task_continuity",
                            "async_move_observed task=$taskId display=$displayId")
                        done(Result.success(current))
                    } else if (SystemClock.uptimeMillis() >= deadline) {
                        done(Result.failure(IllegalStateException(
                            "Async task move did not settle task=$taskId expected=$displayId actual=${current?.displayId}"
                        )))
                    } else {
                        // No sleep: let the compositor draw other frames.
                        exchangeHandler.postDelayed({ observe() }, DISPLAY_MOVE_POLL_MS)
                    }
                }
                result.exceptionOrNull()?.let { done(Result.failure(it)) }
            }
                observe()
            }
            val accepted=exchangeBinderLane.submit(
                call={
                    ActivityTaskManagerCompat.moveTaskToDisplay(taskId, displayId).getOrThrow()
                },
                complete={ result ->
                    // Never synthesize success or release the reservation while
                    // an already submitted system mutation is still running.
                    val error=result.exceptionOrNull()
                    if(error!=null){
                        TaskSurfaceCompat.cancelDisplayExchange(taskId)
                        done(Result.failure(error))
                    } else runCatching{afterBinder()}.onFailure{done(Result.failure(it))}
                },
            )
            if(!accepted){
                TaskSurfaceCompat.cancelDisplayExchange(taskId)
                error("Serialized exchange Binder lane unexpectedly busy")
            }
            StageExchangeTelemetry.phasedEvent("display_${displayId}_binder_worker")
        }
        started.exceptionOrNull()?.let { done(Result.failure(it)) }
    }

    private fun moveTaskToDisplayAndWait(taskId: Int, displayId: Int) {
        // One task snapshot suffices for both the move decision and diagnostics.
        val before = taskRepository.findTask(taskId)
        if (!TaskContinuityPolicy.shouldMove(before?.displayId, displayId)) return
        StageExchangeTelemetry.phasedEvent("display_${displayId}_begin")
        NeXtepLog.info("task_continuity", "move_begin task=$taskId user=${before?.userId} display=${before?.displayId}->$displayId activity=${before?.component?.className} bounds=${before?.bounds} dpi=${before?.densityDpi} launch_requested=false lifecycle_callbacks=not_observable")
        TaskSurfaceCompat.markDisplayExchange(taskId, displayId)
        if (displayId == Display.DEFAULT_DISPLAY) {
            timed("promotion_prepare") { TaskSurfaceCompat.preparePromotion(taskId, geometryProvider()) }.onFailure {
                NeXtepLog.warn("task_surface", "Early promotion fitting unavailable taskId=$taskId", it)
            }
        } else {
            TaskSurfaceCompat.cancelPreparedPromotion(taskId)
        }
        try {
            io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.measure("display_binder") {
                ActivityTaskManagerCompat.moveTaskToDisplay(taskId, displayId).getOrThrow()
            }
        } catch (error: Throwable) {
            TaskSurfaceCompat.cancelDisplayExchange(taskId)
            throw error
        }
        StageExchangeTelemetry.phasedEvent("display_${displayId}_binder_done")
        val deadline = SystemClock.uptimeMillis() + DISPLAY_MOVE_TIMEOUT_MS
        var observedTask = taskRepository.findTask(taskId)
        io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.measure("display_observation") {
        while (observedTask?.displayId != displayId && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(DISPLAY_MOVE_POLL_MS)
            observedTask = taskRepository.findTask(taskId)
        }
        }
        // Reuse the last verified observation for diagnostics and final check.
        val after = observedTask
        val observedDisplayId = observedTask?.displayId
        StageExchangeTelemetry.phasedEvent("display_${displayId}_observed")
        NeXtepLog.info("task_continuity", "move_end task=$taskId found=${after != null} display=${after?.displayId} activity=${after?.component?.className} bounds=${after?.bounds} dpi=${after?.densityDpi} launch_requested=false lifecycle_callbacks=not_observable")
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
    ) = io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.measure("layout_observation") {
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
        if (previous != state && state is SlotState.Ready && pendingAddIntent != null) handler.post { completePendingAdd() }
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
