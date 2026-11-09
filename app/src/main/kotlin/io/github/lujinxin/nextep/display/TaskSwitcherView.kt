package io.github.x647368maker.atomicworkbench.display

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.SidebarSide

class TaskSwitcherView(
    context: Context,
    private val slotIndex: Int,
) : FrameLayout(context) {
    interface Listener {
        fun onSlotClicked(slotIndex: Int)
        fun onIntentDropped(slotIndex: Int, intent: Intent): Boolean
        fun onSlotDragStarting(drag: SlotTaskDrag): Boolean
        fun onSlotDragStarted(source: TaskSwitcherView, drag: SlotTaskDrag): Boolean
        fun onSlotDragTouch(source: View, event: MotionEvent): Boolean
        fun onSlotBackgroundSwiped(drag: SlotTaskDrag): Boolean
        fun onSlotDragEnded()
        fun onSlotDropped(slotIndex: Int, drag: SlotTaskDrag): Boolean
    }

    private val liveBounds = RectF()
    private val liveClip = Path()
    private val liveOutline = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            val b = if (liveBounds.isEmpty) RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()) else liveBounds
            outline.setRoundRect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(), liveRadius(b))
        }
    }
    private fun liveRadius(b: RectF): Float = io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.previewRadius(
        b.width(), b.height(), resources.displayMetrics.density)

    internal fun setLivePreviewBounds(left: Float, top: Float, right: Float, bottom: Float) {
        liveBounds.set(left, top, right, bottom)
        invalidateOutline()
        textureView.invalidateOutline()
        // Keep the sole menu on the app, never floating in a letterbox/empty slot.
        (windowMenu.layoutParams as? LayoutParams)?.let { old ->
            val x = left.toInt() + ((right - left).toInt() - old.width).coerceAtLeast(0) / 2
            val y = top.toInt() + dp(2)
            if (old.leftMargin != x || old.topMargin != y) {
                old.gravity = Gravity.TOP or Gravity.LEFT; old.leftMargin = x; old.topMargin = y
                windowMenu.layoutParams = old
            }
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!workspaceCardVisible) {
            // TextureView creates its SurfaceTexture lazily during draw. Skipping
            // this call leaves Empty -> Ready permanently blocked on cold start.
            // Call the producer directly with an empty clip: no pixels/placeholders,
            // no detachment or alpha-zero RenderNode culling.
            val saved = canvas.save()
            canvas.clipRect(0, 0, 0, 0)
            textureView.draw(canvas)
            canvas.restoreToCount(saved)
            return
        }
        val b = if (liveBounds.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else liveBounds
        liveClip.reset(); liveClip.addRoundRect(b, liveRadius(b), liveRadius(b), Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(liveClip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
    }

    var textureView: TextureView = createTextureView()
        private set

    private val statusView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        background = statusBackground()
    }
    private var menuAction: ((Int, View) -> Unit)? = null
    private var workspaceCardVisible = true
    private val windowMenu = TextView(context).apply {
        text = "⋯"; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.rgb(115, 115, 120))
        setShadowLayer(dp(1).toFloat(), 0f, 0f, Color.argb(120, 255, 255, 255))
        contentDescription = "小窗菜单：替换或关闭"
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.GONE
        setOnClickListener { if (!busy && !waiting && currentState is SlotState.Occupied) menuAction?.invoke(slotIndex, this) }
    }
    fun setWindowMenuAction(action: (Int, View) -> Unit) { menuAction = action }
    fun isWorkspaceCardVisible(): Boolean = workspaceCardVisible
    fun setWorkspaceCardVisible(value: Boolean) {
        workspaceCardVisible = value
        if (!value) { animate().cancel(); alpha = 1f; windowMenu.visibility = View.GONE }
        else windowMenu.visibility = if (currentState is SlotState.Occupied) View.VISIBLE else View.GONE
        invalidate()
    }
    internal fun hasVisibleContent(): Boolean {
        val rect = Rect()
        return workspaceCardVisible && isShown && alpha > 0f && textureView.alpha > .05f && width > 1 && height > 1 &&
            getGlobalVisibleRect(rect) && rect.width() > 1 && rect.height() > 1
    }
    private var directInteraction = false
    private var inputGesture = false
    private var lastInput: MotionEvent? = null
    private var inputAction: ((MotionEvent) -> Result<Unit>)? = null
    fun setInputAction(action: (MotionEvent) -> Result<Unit>) { inputAction = action }
    fun setDirectInteraction(value: Boolean) {
        if (directInteraction == value) return
        cancelInputGesture()
        directInteraction = value
        windowMenu.setTextColor(if (value) Color.rgb(110, 220, 170) else Color.rgb(115, 115, 120))
        if (value) android.widget.Toast.makeText(context, "小窗内操作：触摸或滑动应用；通过顶部三点菜单切回主窗", android.widget.Toast.LENGTH_LONG).show()
    }
    private fun cancelInputGesture() {
        if (inputGesture) lastInput?.let { old ->
            val cancel = MotionEvent.obtain(old); cancel.action = MotionEvent.ACTION_CANCEL
            inputAction?.invoke(cancel); cancel.recycle()
        }
        inputGesture = false; lastInput?.recycle(); lastInput = null
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!workspaceCardVisible) return false
        if (directInteraction && !busy && !waiting && currentState is SlotState.Occupied) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) inputGesture = !(
                windowMenu.visibility == View.VISIBLE && event.x >= windowMenu.left && event.x < windowMenu.right &&
                    event.y >= windowMenu.top && event.y < windowMenu.bottom)
            if (inputGesture) {
                lastInput?.recycle(); lastInput = MotionEvent.obtain(event)
                val result = inputAction?.invoke(event) ?: Result.failure(IllegalStateException("触摸通道未连接"))
                if (result.isFailure) {
                    cancelInputGesture(); directInteraction = false; windowMenu.setTextColor(Color.rgb(115, 115, 120))
                    android.widget.Toast.makeText(context, "系统未开放小窗触摸，请通过顶部三点菜单切回主窗操作", android.widget.Toast.LENGTH_LONG).show()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    inputGesture = false; lastInput?.recycle(); lastInput = null
                }
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private var listener: Listener? = null
    private var busy = false
    private var currentState: SlotState = SlotState.Empty
    private var waiting = false
    private var stateRendered = false
    private var ownsDrag = false
    private var exchangePreviewActive = false
    private val backgroundSwipe = SlotBackgroundSwipeGesture(
        view = this,
        currentTask = {
            (currentState as? SlotState.Occupied)?.takeIf { !busy && !waiting && !ownsDrag }
                ?.let { SlotTaskDrag(slotIndex, it.taskId, it.displayId) }
        },
        onStarted = { listener?.onSlotDragStarting(it) == true },
        onDismiss = { listener?.onSlotBackgroundSwiped(it) == true },
        onEnded = { listener?.onSlotDragEnded() },
    )

    init {
        clipToOutline = true
        outlineProvider = liveOutline
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
        addView(textureView, previewParams())
        addView(
            statusView,
            LayoutParams(dp(30), dp(30), Gravity.CENTER),
        )
        addView(windowMenu, LayoutParams(dp(36), dp(18), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(2) })
        showState(SlotState.Empty)
        setOnDragListener { _, event -> handleDrag(event) }
        setOnClickListener {
            // The coordinator serializes exchanges and retains the latest selection.
            // Filtering busy taps here prevents that queue from ever receiving them.
            if (currentState is SlotState.Ready || currentState is SlotState.Occupied ||
                currentState is SlotState.Failed) {
                listener?.onSlotClicked(slotIndex)
            }
        }
        setOnLongClickListener {
            val occupied = currentState as? SlotState.Occupied
                ?: return@setOnLongClickListener false
            if (busy || waiting) return@setOnLongClickListener false
            val drag = SlotTaskDrag(slotIndex, occupied.taskId, occupied.displayId)
            if (listener?.onSlotDragStarting(drag) != true) return@setOnLongClickListener false
            ownsDrag = true
            val started = listener?.onSlotDragStarted(this, drag) == true
            if (!started) finishDrag()
            else {
                textureView.alpha = 0.55f
            }
            started
        }
        setOnTouchListener { view, event ->
            if (listener?.onSlotDragTouch(view, event) == true) {
                backgroundSwipe.cancel()
                true
            } else backgroundSwipe.touch(event)
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun finishInternalDrag() = finishDrag()
    fun setBackgroundSwipeEnabled(enabled: Boolean) = backgroundSwipe.setEnabled(enabled)
    fun setBackgroundSwipeSide(side: SidebarSide) = backgroundSwipe.setSide(side)
    fun cancelBackgroundSwipe() = backgroundSwipe.cancel()
    fun acceptsAppDrop() = !busy && currentState is SlotState.Ready
    fun setDropHighlighted(highlighted: Boolean) {
        foreground = if (highlighted) GradientDrawable().apply {
            setColor(Color.argb(35, 62, 190, 198))
            setStroke(dp(2), Color.rgb(80, 216, 224))
        } else null
    }

    fun showState(state: SlotState) {
        if (stateRendered && state == currentState && !waiting) return
        stateRendered = true
        if (state != currentState || waiting) backgroundSwipe.cancel()
        waiting = false
        val previousState = currentState
        if (state != currentState) { cancelInputGesture(); directInteraction = false; windowMenu.setTextColor(Color.rgb(115, 115, 120)) }
        currentState = state
        if (state is SlotState.Occupied) contentDescription = "${state.component.packageName}，点击切换主窗，顶部三点打开窗口菜单"
        windowMenu.visibility = if (workspaceCardVisible && state is SlotState.Occupied) View.VISIBLE else View.GONE
        when (state) {
            SlotState.Empty -> showReady(animated = !exchangePreviewActive && previousState is SlotState.Occupied)
            is SlotState.Ready -> showReady(animated = !exchangePreviewActive && previousState is SlotState.Occupied)
            is SlotState.Occupied -> {
                showOccupied(
                    animated = !exchangePreviewActive &&
                        (previousState !is SlotState.Occupied || previousState.taskId != state.taskId),
                )
            }
            is SlotState.Failed -> showReady(animated = !exchangePreviewActive && previousState is SlotState.Occupied)
        }
    }

    internal fun beginExchangePreview() {
        exchangePreviewActive = true
        textureView.animate().cancel()
    }

    internal fun endExchangePreview() {
        if (!exchangePreviewActive) return
        exchangePreviewActive = false
        if (waiting) return
        // The overlay already performed the entrance. Reveal the live buffer at
        // its final size, including when a drag was cancelled before the move.
        stateRendered = false
        showState(currentState)
    }

    fun showWaiting() {
        backgroundSwipe.cancel()
        waiting = true
        textureView.animate().cancel()
        textureView.animate()
            .alpha(0f)
            .scaleX(CONTENT_EXIT_SCALE)
            .scaleY(CONTENT_EXIT_SCALE)
            .setDuration(TRANSITION_OUT_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .start()
        statusView.animate().cancel()
        setStatusVisible(currentState !is SlotState.Occupied)
    }

    fun setBusy(isBusy: Boolean) {
        busy = isBusy
        if (isBusy) { cancelInputGesture(); backgroundSwipe.cancel() }
        if (!isBusy) {
            showState(currentState)
        }
    }

    fun replaceTextureView(listener: TextureView.SurfaceTextureListener): TextureView {
        backgroundSwipe.cancel()
        waiting = true
        val previous = textureView
        previous.animate().cancel()
        previous.surfaceTextureListener = null
        removeView(previous)

        return createTextureView().also { replacement ->
            replacement.surfaceTextureListener = listener
            textureView = replacement
            addView(
                replacement,
                0,
                previewParams(),
            )
        }
    }

    private var menuTouch = false
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) menuTouch = windowMenu.visibility == View.VISIBLE &&
            event.x >= windowMenu.left && event.x < windowMenu.right &&
            event.y >= windowMenu.top && event.y < windowMenu.bottom
        return !menuTouch
    }

    fun capturePreview(width: Int = textureView.width, height: Int = textureView.height): Bitmap? = runCatching {
        io.github.x647368maker.atomicworkbench.monitoring.WorkspaceEnergyDiagnostics.previewCaptures.incrementAndGet()
        // Exchanges request the producer's recorded size. The no-argument
        // TextureView bitmap is only rail-sized and blurs when promoted to main.
        val bitmap = textureView.getBitmap(width, height) ?: return@runCatching null
        // TextureView returns its logical portrait buffer. Follow the rail's
        // actual transform so reverse-landscape drag/swap previews face correctly.
        val panelRotation = (parent as? View)?.rotation ?: 0f
        if (panelRotation != 0f) {
            try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                    android.graphics.Matrix().apply { postRotate(panelRotation) }, true)
            } finally { bitmap.recycle() }
        } else bitmap
    }.getOrNull()

    /** getGlobalVisibleRect is relative to the ViewRoot, not display 0. The
     * sidebar is a separate window whose physical offset must be included. */
    fun getScreenVisibleRect(bounds: Rect): Boolean {
        if (!getGlobalVisibleRect(bounds)) return false
        val windowOrigin = IntArray(2)
        rootView.getLocationOnScreen(windowOrigin)
        bounds.offset(windowOrigin[0], windowOrigin[1])
        return true
    }

    /** Invert the same composed view matrices used for drawing. Front-to-back
     * ordering is supplied by the drag host, so overlapped rear cards cannot steal drops. */
    private fun localToScreenMatrix(): android.graphics.Matrix? {
        val transform = android.graphics.Matrix()
        var current: View = this
        val root = rootView
        while (current !== root) {
            transform.postConcat(current.matrix)
            val parent = current.parent as? View ?: return null
            transform.postTranslate((current.left - parent.scrollX).toFloat(),
                (current.top - parent.scrollY).toFloat())
            current = parent
        }
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        transform.postTranslate(origin[0].toFloat(), origin[1].toFloat())
        return transform
    }

    fun screenQuad(): FloatArray? {
        val transform = localToScreenMatrix() ?: return null
        val points = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        transform.mapPoints(points)
        if (points.any { !it.isFinite() }) return null
        return io.github.x647368maker.atomicworkbench.systemui.StageExchangeMotion.orientQuad(
            points, (parent as? View)?.rotation ?: 0f)
    }

    fun containsScreenPoint(x: Float, y: Float): Boolean {
        if (!workspaceCardVisible) return false
        val visible = Rect()
        if (!getScreenVisibleRect(visible) || !visible.contains(x.toInt(), y.toInt())) return false
        val transform = localToScreenMatrix() ?: return false
        val inverse = android.graphics.Matrix()
        if (!transform.invert(inverse)) return false
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        return point[0] >= 0 && point[0] < width && point[1] >= 0 && point[1] < height
    }

    override fun onDetachedFromWindow() {
        cancelInputGesture()
        backgroundSwipe.cancel()
        finishDrag()
        super.onDetachedFromWindow()
    }

    private fun finishDrag() {
        if (!ownsDrag) return
        ownsDrag = false
        listener?.onSlotDragEnded()
        if (!busy && currentState is SlotState.Occupied) textureView.alpha = 1f
    }

    private fun handleDrag(event: DragEvent): Boolean {
        return when (event.action) {
        DragEvent.ACTION_DRAG_STARTED -> {
            val localDrag = event.localState as? SlotTaskDrag
            val accepted = !busy && (localDrag != null &&
                currentState.inDragTargetStates() || currentState !is SlotState.Occupied &&
                AppDragContract.accepts(event.clipDescription))
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex started accepted=$accepted busy=$busy state=$currentState " +
                    "mime=${event.clipDescription}",
            )
            accepted
        }
        DragEvent.ACTION_DRAG_ENTERED -> {
            if (!busy) {
                if (currentState is SlotState.Occupied) {
                    foreground = GradientDrawable().apply {
                        setColor(Color.argb(35, 62, 190, 198))
                        setStroke(dp(2), Color.rgb(80, 216, 224))
                    }
                } else showReady(highlighted = true)
            }
            true
        }
        DragEvent.ACTION_DRAG_EXITED -> {
            showState(currentState)
            foreground = null
            true
        }
        DragEvent.ACTION_DROP -> {
            val localDrag = event.localState as? SlotTaskDrag
            if (localDrag != null && width > 0 && height > 0) {
                val bounds = Rect()
                getScreenVisibleRect(bounds)
                localDrag.dropPoint = if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) {
                    android.graphics.PointF(bounds.right - event.y * bounds.width() / height,
                        bounds.top + event.x * bounds.height() / width)
                } else android.graphics.PointF(bounds.left + event.x, bounds.top + event.y)
            }
            val intent = AppDragContract.readLaunchIntent(event.clipData)
            val handled = !busy && if (localDrag != null) {
                listener?.onSlotDropped(slotIndex, localDrag) == true
            } else intent != null && listener?.onIntentDropped(slotIndex, intent) == true
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex drop handled=$handled component=${intent?.component}",
            )
            handled
        }
        DragEvent.ACTION_DRAG_ENDED -> {
            foreground = null
            if (!busy) showState(currentState)
            if ((event.localState as? SlotTaskDrag)?.slotIndex == slotIndex) finishDrag()
            true
        }
        else -> true
        }
    }

    private fun SlotState.inDragTargetStates(): Boolean = this is SlotState.Ready || this is SlotState.Occupied

    private fun showReady(highlighted: Boolean = false, animated: Boolean = false) {
        textureView.animate().cancel()
        if (animated) {
            textureView.animate()
                .alpha(0f)
                .scaleX(CONTENT_EXIT_SCALE)
                .scaleY(CONTENT_EXIT_SCALE)
                .setDuration(TRANSITION_OUT_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 0f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
        statusView.text = "+"
        statusView.textSize = 20f
        statusView.setPadding(0, 0, 0, dp(2))
        statusView.layoutParams = LayoutParams(dp(30), dp(30), Gravity.CENTER)
        statusView.background = statusBackground(
            if (highlighted) Color.argb(210, 28, 112, 121) else Color.argb(158, 22, 40, 48),
        )
        statusView.animate().cancel()
        // Empty slots remain actionable when a buffer or animation is interrupted.
        setStatusVisible(true)
    }

    private fun showOccupied(animated: Boolean) {
        statusView.animate().cancel()
        setStatusVisible(false)

        textureView.animate().cancel()
        if (animated) {
            textureView.alpha = 0f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
            textureView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRANSITION_IN_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 1f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
    }

    private fun setStatusVisible(visible: Boolean) {
        statusView.animate().cancel()
        statusView.visibility = if (visible) VISIBLE else GONE
        statusView.alpha = 1f
        statusView.scaleX = 1f
        statusView.scaleY = 1f
    }

    private fun statusBackground(color: Int = Color.argb(158, 22, 40, 48)) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun previewParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    private fun createTextureView(): TextureView = TextureView(context).apply {
        // Opaque TextureViews may bypass alpha/corner composition on vendor renderers.
        isOpaque = false
        clipToOutline = true
        outlineProvider = liveOutline
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val transitionInterpolator = DecelerateInterpolator(1.6f)

    private companion object {
        const val TRANSITION_OUT_DURATION_MS = 120L
        const val TRANSITION_IN_DURATION_MS = 220L
        const val CONTENT_ENTER_SCALE = 0.96f
        const val CONTENT_EXIT_SCALE = 0.98f
    }

}
