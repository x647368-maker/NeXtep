package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.*
import kotlin.math.ceil

/** A bounded native popup. Local translucent glass fallback; no global dim or blur. */
internal object AtomicPopupMenu {
    private var active:PopupWindow?=null

    // A local, translucent material: the actual background remains visible.
    // Optical rim shading is geometry-only, not true background refraction.
    // No capture, additional window, render loop or full-screen blur.
    internal fun dualGlassPane(density:Float):GradientDrawable=GlassPane(density)

    private class GlassPane(private val density:Float):GradientDrawable(
        GradientDrawable.Orientation.TL_BR,intArrayOf(Color.argb(135,81,103,122),
            Color.argb(170,28,43,59),Color.argb(142,61,83,104))) {
        private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
        private val inner=RectF()
        private val outer=RectF()
        private val radius=16f*density
        init { cornerRadius=radius }

        override fun onBoundsChange(bounds:Rect) {
            super.onBoundsChange(bounds)
            outer.set(bounds)
            outer.inset(.55f*density,.55f*density)
            inner.set(bounds)
            inner.inset(2.5f*density,2.5f*density)
            edge.shader=if(bounds.width()>0 && bounds.height()>0)LinearGradient(
                bounds.left.toFloat(),bounds.top.toFloat(),
                bounds.right.toFloat(),bounds.bottom.toFloat(),
                intArrayOf(Color.argb(214,244,252,255),Color.argb(32,214,234,247),
                    Color.argb(42,210,231,248),Color.argb(197,242,250,255)),
                floatArrayOf(0f,.32f,.67f,1f),Shader.TileMode.CLAMP) else null
        }

        override fun draw(canvas:Canvas) {
            super.draw(canvas)
            if(outer.isEmpty || edge.shader==null)return
            // Two soft, directionally lit bevel bands; both lie inside the
            // existing pane outline. The shader is rebuilt only on bounds change.
            edge.strokeWidth=3f*density
            edge.alpha=(alpha*.18f).toInt()
            if(!inner.isEmpty)canvas.drawRoundRect(inner,(radius-2.5f*density).coerceAtLeast(0f),
                (radius-2.5f*density).coerceAtLeast(0f),edge)
            edge.strokeWidth=1.1f*density
            edge.alpha=alpha
            canvas.drawRoundRect(outer,(radius-.55f*density).coerceAtLeast(0f),
                (radius-.55f*density).coerceAtLeast(0f),edge)
        }
    }

    // Only explicitly declared navigation rows may synchronously open a
    // child in the currently owned popup. Leaf actions keep dismiss/dispatch.
    private data class InlineNavigation(
        val context:Context,val anchor:View,
        val open:(List<Pair<String,()->Unit>>,List<Rect>,Set<String>)->Unit,
    )
    private data class InlinePage(
        val title:String,val items:List<Pair<String,()->Unit>>,val submenuLabels:Set<String>,
    )
    private var inlineNavigation:InlineNavigation?=null
    private val handler=Handler(Looper.getMainLooper())
    private val gate=StagePanelMenuGate({SystemUiPanelMonitor.state()},{SystemClock.uptimeMillis()},
        {delay,action->handler.postDelayed({action()},delay)},::dismissActive)
    // One user selection owns one main-queue action, independently of HWUI
    // animator end callbacks. Native panel/open/exit and external dismiss
    // invalidate pending work, including the turn after popup dismissal.
    private var pendingAction:Runnable?=null
    private fun cancelPendingAction() {
        pendingAction?.let { handler.removeCallbacks(it) }
        pendingAction=null
    }
    private fun scheduleAction(delay:Long, run:()->Unit):Boolean {
        check(pendingAction==null)
        lateinit var task:Runnable
        task=Runnable {
            if(pendingAction===task) {
                pendingAction=null
                run()
            }
        }
        pendingAction=task
        if(handler.postDelayed(task,delay))return true
        pendingAction=null
        return false
    }
    private fun dismissActive(){
        cancelPendingAction()
        val popup=active
        active=null
        popup?.contentView?.animate()?.cancel()
        popup?.dismiss()
    }
    fun dismissAll(){gate.cancel()}
    internal fun isShowing()=active?.isShowing==true
    fun onPanelChanged(){gate.panelChanged()}
    fun show(context:Context,anchor:View,items:List<Pair<String,()->Unit>>,avoidBounds:List<Rect> = emptyList(),
        selectedLabels:Set<String> = emptySet(),submenuLabels:Set<String> = emptySet()) {
        StageRuntimeIdentity.install(context)
        if(items.isEmpty())return
        inlineNavigation?.let { navigation ->
            // A synchronous page build may not replace another anchor's popup
            // or outlive an intervening native panel / workspace dismissal.
            if(navigation.context===context && navigation.anchor===anchor &&
                anchor.isAttachedToWindow &&
                SystemUiPanelMonitor.refresh(context)==StagePanelMenuGate.State.CLOSED)
                navigation.open(items.toList(),avoidBounds.map{Rect(it)},submenuLabels.toSet())
            return
        }
        SystemUiPanelMonitor.refresh(context)
        gate.request({anchor.isAttachedToWindow},{SystemUiPanelCollapser.tryCollapse(context)},
            {showClosed(context,anchor,items,avoidBounds.map{Rect(it)},selectedLabels.toSet(),submenuLabels.toSet())},
            {Toast.makeText(context,"请先收起系统通知栏或控制中心，再打开菜单",Toast.LENGTH_SHORT).show()})
    }
    private fun showClosed(context:Context,anchor:View,items:List<Pair<String,()->Unit>>,avoid:List<Rect>,
        selectedLabels:Set<String>,submenuLabels:Set<String>) {
        if(SystemUiPanelMonitor.refresh(context)!=StagePanelMenuGate.State.CLOSED)return
        val density=context.resources.displayMetrics.density
        fun dp(n:Int)=ceil(n*density).toInt()
        val screen=context.resources.displayMetrics
        val safe=runCatching {
            val m=context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val b=Rect(m.bounds);val i=m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            b.inset(i.left+dp(8),i.top+dp(8),i.right+dp(8),i.bottom+dp(8));b
        }.getOrElse{Rect(dp(8),dp(8),screen.widthPixels-dp(8),screen.heightPixels-dp(8))}
        val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val ink=if(dark)Color.rgb(245,248,255) else Color.rgb(26,37,54)
        val glass=GradientDrawable(GradientDrawable.Orientation.TL_BR,if(dark)
            intArrayOf(Color.argb(238,62,76,96),Color.argb(232,39,52,73)) else
            intArrayOf(Color.argb(246,250,253,255),Color.argb(240,221,232,245))).apply {
            cornerRadius=dp(20).toFloat();setStroke(dp(1),if(dark)Color.argb(85,232,245,255) else Color.argb(205,255,255,255))
        }
        val rows=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(6));setBackgroundColor(Color.TRANSPARENT)}
        val scroll=ScrollView(context).apply{isFillViewport=false;addView(rows,ViewGroup.LayoutParams(-1,-2))}
        val content=LinearLayout(context).apply{
            orientation=LinearLayout.HORIZONTAL
            clipToOutline=true
            background=glass
        }
        val grouped=StageMenuPolicy.isMain(items.map{it.first})
        val xy=IntArray(2).also(anchor::getLocationOnScreen)
        fun Rect.box()=StageMenuPlacement.Box(left,top,right,bottom)
        val anchorBox=StageMenuPlacement.Box(xy[0],xy[1],xy[0]+anchor.width,xy[1]+anchor.height)
        val blocked=avoid.map{it.box()}
        val compactWidth=safe.width()<dp(600)
        // The supplied portrait design is about 75% of the viewport wide,
        // with compact 4-row categories and a wider independent right pane.
        val fullWidth=dp(if(compactWidth)276 else 312)
        // Measure the longest real title in its selected bold font, including
        // the user's SP scaling. Keep every character plus icon and chevron.
        val categoryPaint=TextView(context).apply {
            textSize=12f
            includeFontPadding=false
            setTypeface(null,android.graphics.Typeface.BOLD)
        }.paint
        val titleWidth=ceil(StageMenuPolicy.groups.maxOf(categoryPaint::measureText)).toInt()
        val categoryInsets=dp(4)*2+dp(5)*2+dp(14)+dp(8)+dp(4)*2+dp(2)
        val preferredCategoryWidth=maxOf(dp(if(compactWidth)120 else 132),titleWidth+categoryInsets)
        val preferredDualWidth=maxOf(fullWidth,preferredCategoryWidth+dp(3)+dp(144))
        val metrics=categoryPaint.fontMetricsInt
        val categoryHeight=maxOf(dp(30),metrics.bottom-metrics.top+dp(4))*StageMenuPolicy.groups.size+dp(8)
        // Allow the naturally measured right pane to grow beyond the former
        // 152dp viewport. #1477 screenshots proved rows were cut mid-line.
        // The popup remains anchor-pinned and never changes task geometry.
        val dualHeight=maxOf(dp(if(compactWidth)272 else 280),categoryHeight)
        val dualBounds=if(grouped) StageMenuPlacement.pinnedDual(
            safe.box(),anchorBox,preferredDualWidth,dualHeight,dp(220),
            minOf(categoryHeight,safe.height().coerceAtLeast(1)),blocked,dp(2)) else null
        val categoryWidth=dualBounds?.let {
            preferredCategoryWidth.coerceAtMost((it.w-dp(3))*46/100).coerceAtLeast(1)
        } ?: preferredCategoryWidth
        if(grouped)io.github.x647368maker.atomicworkbench.logging.NeXtepLog.info("workspace_menu",
            if(dualBounds==null)"grouped viewport too narrow; accessible compact fallback"
            else "pinned_dual width=${dualBounds.w} height=${dualBounds.h} overlap=${blocked.sumOf{dualBounds.overlap(it)}}")
        val categories=LinearLayout(context).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(6),dp(6),dp(6),dp(6))
        }
        if (dualBounds!=null) {
            // Two independently rounded, translucent panes with a visible
            // glass rim. No screenshot, global blur, or task-window change.
            fun pane()=dualGlassPane(density)
            content.background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            content.clipToOutline=false
            categories.setPadding(dp(4),dp(4),dp(4),dp(4))
            rows.setPadding(dp(4),dp(4),dp(4),dp(4))
            content.addView(ScrollView(context).apply {
                background=pane();clipToOutline=true;isVerticalScrollBarEnabled=false
                addView(categories)
            },LinearLayout.LayoutParams(categoryWidth,-2).apply {
                rightMargin=dp(3);gravity=Gravity.TOP
            })
            scroll.background=pane();scroll.clipToOutline=true
            // Each glass card follows its own contents under the same bounded
            // popup viewport, instead of painting an empty matching column.
            content.addView(scroll,LinearLayout.LayoutParams(0,-2,1f).apply {gravity=Gravity.TOP})
        } else content.addView(scroll,LinearLayout.LayoutParams(-1,-1))
        val popup=PopupWindow(content,1,1,true).apply {
            isOutsideTouchable=true;setTouchModal(false)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=dp(4).toFloat()
            animationStyle=0
        }
        active=popup
        popup.setOnDismissListener{
            content.animate().cancel()
            if(active===popup) {
                active=null
                cancelPendingAction()
            }
        }
        var initialized=false
        fun fit(secondary:Boolean) {
            val maxWidth=safe.width().coerceAtLeast(1)
            val maxHeight=safe.height().coerceAtLeast(1)
            if(dualBounds!=null) {
                // Independent natural pane heights. Resize only the popup,
                // never its anchor, task cards or left/right column widths.
                val rightWidth=(dualBounds.w-categoryWidth-dp(3)).coerceAtLeast(1)
                categories.measure(View.MeasureSpec.makeMeasureSpec(categoryWidth,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                rows.measure(View.MeasureSpec.makeMeasureSpec(rightWidth,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                val h=maxOf(categories.measuredHeight,rows.measuredHeight).coerceIn(1,dualBounds.h)
                if(initialized)popup.update(dualBounds.l,dualBounds.t,dualBounds.w,h)
                else {
                    popup.width=dualBounds.w;popup.height=h
                    popup.showAtLocation(anchor,Gravity.TOP or Gravity.LEFT,dualBounds.l,dualBounds.t)
                    initialized=true
                }
                return
            }
            // Measure real labels, including system font scaling, before placing.
            rows.measure(View.MeasureSpec.makeMeasureSpec(maxWidth,View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            val width=maxOf(dp(if(secondary)220 else 208),rows.measuredWidth).coerceAtMost(maxWidth).coerceAtMost(dp(220))
            rows.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            // Preserve measured labels, but keep the accepted compact scrolling viewport.
            val height=rows.measuredHeight.coerceIn(1,maxHeight)
                .coerceAtMost(minOf(dp(300),(maxHeight*.38f).toInt()).coerceAtLeast(1))
            val pos=StageMenuPlacement.compact(safe.box(),anchorBox,
                width,height,
                // The short primary category page must remain fully readable.
                // Prefer an empty rectangle that fits it; if none exists, the
                // placement policy minimizes temporary overlap. Only longer
                // action lists may shrink to a scrolling one-row viewport.
                if(grouped && !secondary) width else dp(160),
                if(grouped && !secondary) height else dp(60),
                blocked,dp(8))
            if(initialized)popup.update(pos.l,pos.t,pos.w,pos.h) else {
                popup.width=pos.w;popup.height=pos.h
                popup.showAtLocation(anchor,Gravity.TOP or Gravity.LEFT,pos.l,pos.t);initialized=true
            }
        }
        fun row(label:String,parent:LinearLayout=rows,navigation:Boolean=false,click:()->Unit):TextView {
            val button=TextView(context).apply {
                text=label;contentDescription=label;textSize=15f;minHeight=dp(48)
                setTextColor(ink);gravity=Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(12),dp(6),dp(12),dp(6));isClickable=true;isFocusable=true
                if(dualBounds!=null) {
                    val categoryRow=parent===categories
                    textSize=12f
                    includeFontPadding=false
                    minHeight=dp(if(categoryRow)30 else 28)
                    setTextColor(Color.WHITE)
                    setShadowLayer(dp(1).toFloat(),0f,dp(1).toFloat(),Color.argb(205,0,0,0))
                    setPadding(dp(5),dp(2),dp(5),dp(2))
                    val kind=if(categoryRow)StageMenuPolicy.groups.indexOf(label).coerceAtLeast(0)
                        else StageMenuPolicy.actionGlyph(label)
                    val size=dp(14)
                    val glyph=StageMenuLineGlyph(kind,if(categoryRow) 0 else 1).apply {setBounds(0,0,size,size)}
                    val selected=!categoryRow && StageMenuPolicy.isSelected(label,selectedLabels)
                    val choice=!categoryRow && StageMenuPolicy.isLayoutChoice(label)
                    if(choice) {
                        // Keep the complete original command in the accessibility
                        // label while displaying its selection as a right hand mark.
                        text=label.removePrefix("✓ ")
                        isSelected=selected
                    }
                    val marker=when {
                        categoryRow || navigation->StageMenuLineGlyph(0,2).apply {setBounds(0,0,dp(8),size)}
                        choice->StageMenuLineGlyph(0,if(selected)3 else 4).apply {setBounds(0,0,size,size)}
                        else->null
                    }
                    setCompoundDrawables(glyph,null,marker,null)
                    compoundDrawablePadding=dp(4)
                }
                val selectedGlass=if(dualBounds!=null && (parent===categories || StageMenuPolicy.isLayoutChoice(label)))
                    android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_selected),GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.argb(214,44,166,253),Color.argb(211,18,118,216),
                                Color.argb(194,21,92,185))
                        ).apply {
                            cornerRadius=dp(10).toFloat()
                            setStroke(dp(1),Color.argb(124,186,232,255))
                        })
                        addState(intArrayOf(),android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    } else null
                background=RippleDrawable(ColorStateList.valueOf(if(dark)Color.argb(40,235,245,255) else Color.argb(25,30,70,110)),selectedGlass,
                    GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(12).toFloat()})
                setOnClickListener{click()}
            }
            parent.addView(button,LinearLayout.LayoutParams(-1,-2))
            return button
        }
        var selectedCategory:Int?=null
        val childPages=mutableListOf<InlinePage>()
        // The header back affordance delegates to the same category-switch path.
        val categoryButtons=mutableMapOf<Int,TextView>()
        lateinit var page:(Int?)->Unit
        lateinit var renderRight:()->Unit
        fun action(label:String,run:()->Unit,subpage:Boolean=false) = row(label,navigation=subpage) select@{
            if(active!==popup || !popup.isShowing || pendingAction!=null || inlineNavigation!=null)return@select
            if(!anchor.isAttachedToWindow ||
                SystemUiPanelMonitor.refresh(context)!=StagePanelMenuGate.State.CLOSED) {
                dismissActive()
                return@select
            }
            if(dualBounds!=null && subpage) {
                val navigation=InlineNavigation(context,anchor){ next,nextAvoid,nextSubmenus ->
                    if(active===popup && popup.isShowing && anchor.isAttachedToWindow &&
                        SystemUiPanelMonitor.refresh(context)==StagePanelMenuGate.State.CLOSED) {
                        // Existing intentional compact overlay is not itself
                        // a reason to dismiss an inline page. Only invalidate
                        // if the actual protected geometry has changed.
                        if(nextAvoid.map{it.box()}.toSet()!=blocked.toSet())dismissActive()
                        else {
                            childPages.add(InlinePage(label,next,nextSubmenus))
                            renderRight()
                            fit(true)
                        }
                    }
                }
                inlineNavigation=navigation
                try {
                    runCatching(run).onFailure {
                        io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("workspace_menu","Subpage unavailable",it)
                        Toast.makeText(context,it.message ?: "操作未完成，请稍后重试",Toast.LENGTH_SHORT).show()
                    }
                } finally { if(inlineNavigation===navigation)inlineNavigation=null }
                return@select
            }
            io.github.x647368maker.atomicworkbench.logging.NeXtepLog.debug("workspace_menu","selection accepted")
            content.animate().cancel()
            content.animate().alpha(0f).setDuration(70).start()
            // The fade is purely visual: a canceled animator must not swallow
            // an accepted click. A single bounded main-queue task owns it.
            if(!scheduleAction(70) {
                if(active!==popup || !popup.isShowing || !anchor.isAttachedToWindow ||
                    SystemUiPanelMonitor.refresh(context)!=StagePanelMenuGate.State.CLOSED) {
                    dismissActive()
                } else {
                    // This is the selected dismissal, not an outside cancel.
                    // Clear ownership before dismiss; cancel/new request still
                    // invalidate the next main-queue turn.
                    active=null
                    popup.dismiss()
                    if(!scheduleAction(0) {
                        if(anchor.isAttachedToWindow &&
                            SystemUiPanelMonitor.refresh(context)==StagePanelMenuGate.State.CLOSED) {
                            io.github.x647368maker.atomicworkbench.logging.NeXtepLog.debug("workspace_menu","selection dispatch")
                            runCatching(run).onFailure {
                                io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("workspace_menu","Action failed",it)
                                Toast.makeText(context,it.message ?: "操作未完成，请稍后重试",Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            io.github.x647368maker.atomicworkbench.logging.NeXtepLog.debug("workspace_menu","selection rejected after dismissal")
                        }
                    }) {
                        io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("workspace_menu","Action dispatch unavailable")
                    }
                }
            })dismissActive()
        }
        renderRight={
            scroll.animate().cancel()
            scroll.alpha=1f
            rows.removeAllViews()
            scroll.scrollTo(0,0)
            val child=childPages.lastOrNull()
            if(child!=null) {
                row("‹  ${child.title}"){
                    if(active===popup && popup.isShowing && pendingAction==null &&
                        anchor.isAttachedToWindow &&
                        SystemUiPanelMonitor.refresh(context)==StagePanelMenuGate.State.CLOSED) {
                        childPages.removeAt(childPages.lastIndex)
                        renderRight()
                        fit(true)
                    } else dismissActive()
                }
                child.items.forEach { action(it.first,it.second,it.first in child.submenuLabels) }
            } else {
                // The supplied layout panel has no duplicate title; other
                // categories have one compact descriptive right-pane header.
                selectedCategory?.takeIf{it==2 || it==3}?.let { index->
                    rows.addView(TextView(context).apply {
                        text="‹  ${StageMenuPolicy.groups[index]}"
                        textSize=11f
                        includeFontPadding=false
                        setTextColor(Color.WHITE)
                        setPadding(dp(5),dp(2),dp(5),dp(3))
                        gravity=Gravity.CENTER_VERTICAL
                        isClickable=true;isFocusable=true
                        setOnClickListener { page(0) }
                    },LinearLayout.LayoutParams(-1,-2))
                }
                items.filter{StageMenuPolicy.group(it.first)==selectedCategory}
                    .forEach { action(it.first,it.second,it.first in submenuLabels) }
            }
        }
        page=pageChange@{ category ->
            if(pendingAction!=null)return@pageChange
            if(active===popup && SystemUiPanelMonitor.refresh(context)==StagePanelMenuGate.State.CLOSED) {
                content.animate().cancel();rows.removeAllViews();scroll.scrollTo(0,0)
                if(dualBounds!=null) {
                    for ((index, button) in categoryButtons) {
                        val selected=index==category
                        button.isSelected=selected
                        button.setTypeface(null,if(selected)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        button.setTextColor(Color.WHITE)
                    }
                    selectedCategory=category
                    childPages.clear()
                    renderRight()
                } else if(grouped && category==null) {
                    StageMenuPolicy.groups.forEachIndexed {index,title->
                        if(items.any{StageMenuPolicy.group(it.first)==index})row("$title  ›"){page(index)}
                    }
                } else {
                    if(grouped && category!=null)row("‹  ${StageMenuPolicy.groups[category]}"){page(null)}
                    items.filter{!grouped || StageMenuPolicy.group(it.first)==category}.forEach{action(it.first,it.second)}
                }
                fit(category!=null)
                val animated=if(dualBounds!=null) scroll else content
                animated.animate().cancel()
                if(android.animation.ValueAnimator.areAnimatorsEnabled()){
                    animated.alpha=.35f;animated.animate().alpha(1f).setDuration(100).start()
                }
            } else dismissActive()
        }
        runCatching {
            if (dualBounds!=null) {
                StageMenuPolicy.groups.forEachIndexed { index,title ->
                    if(items.any { StageMenuPolicy.group(it.first)==index })
                        categoryButtons[index]=row(title,categories){ page(index) }
                }
            }
            page(categoryButtons.keys.firstOrNull())
        }.onFailure {
            dismissActive();io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("workspace_menu","Popup unavailable",it)
        }
    }
}
