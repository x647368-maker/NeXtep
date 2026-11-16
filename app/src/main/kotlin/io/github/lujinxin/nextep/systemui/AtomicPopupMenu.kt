package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
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
        val fullWidth=dp(if(compactWidth)304 else 340)
        // Measure the longest real title in its selected bold font, including
        // the user's SP scaling. Keep every character plus icon and chevron.
        val categoryPaint=TextView(context).apply {
            textSize=13f
            setTypeface(null,android.graphics.Typeface.BOLD)
        }.paint
        val titleWidth=ceil(StageMenuPolicy.groups.maxOf(categoryPaint::measureText)).toInt()
        val categoryInsets=dp(4)*2+dp(5)*2+dp(14)+dp(8)+dp(4)*2+dp(2)
        val categoryWidth=maxOf(dp(if(compactWidth)132 else 140),titleWidth+categoryInsets)
        val minDualWidth=maxOf(dp(if(compactWidth)282 else 304),categoryWidth+dp(3)+dp(147))
        val metrics=categoryPaint.fontMetricsInt
        val categoryHeight=maxOf(dp(48),metrics.bottom-metrics.top+dp(8))*StageMenuPolicy.groups.size+dp(12)
        val dualHeight=minOf(dp(252),(safe.height()*.38f).toInt())
        val dualBounds=if(grouped) StageMenuPlacement.dualGlass(
            safe.box(),anchorBox,fullWidth,dualHeight,minDualWidth,
            categoryHeight,blocked,dp(8)) else null
        val categories=LinearLayout(context).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(6),dp(6),dp(6),dp(6))
        }
        if (dualBounds!=null) {
            // Two independently rounded, translucent panes with a visible
            // glass rim. No screenshot, global blur, or task-window change.
            fun pane()=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(154,104,125,143),Color.argb(167,34,49,67),
                    Color.argb(147,77,98,117))).apply {
                cornerRadius=dp(16).toFloat()
                setStroke(dp(1),Color.argb(145,235,248,255))
            }
            content.background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            content.clipToOutline=false
            categories.setPadding(dp(4),dp(6),dp(4),dp(6))
            rows.setPadding(dp(5),dp(6),dp(5),dp(6))
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
            // Measure real labels, including system font scaling, before placing.
            rows.measure(View.MeasureSpec.makeMeasureSpec(maxWidth,View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            val width=maxOf(dp(if(secondary)220 else 208),rows.measuredWidth).coerceAtMost(maxWidth).coerceAtMost(dp(220))
            rows.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            // Preserve measured labels, but keep the accepted compact scrolling viewport.
            val height=rows.measuredHeight.coerceIn(1,maxHeight)
                .coerceAtMost(minOf(dp(300),(maxHeight*.38f).toInt()).coerceAtLeast(1))
            if(dualBounds!=null && initialized)return
            val pos=dualBounds ?: StageMenuPlacement.compact(safe.box(),anchorBox,
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
                    textSize=13f
                    setTextColor(Color.WHITE)
                    setShadowLayer(dp(1).toFloat(),0f,dp(1).toFloat(),Color.argb(205,0,0,0))
                    setPadding(dp(5),dp(4),dp(5),dp(4))
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
                val selectedGlass=if(dualBounds!=null && parent===categories)
                    android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_selected),GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(Color.argb(224,37,151,239),Color.argb(205,17,99,201))
                        ).apply {
                            cornerRadius=dp(12).toFloat()
                            setStroke(dp(1),Color.argb(172,159,222,255))
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
                        // Geometry may change while an old menu is open. Never
                        // reuse its placement if current protected bounds moved.
                        if(nextAvoid.any{dualBounds.overlap(it.box())>0L})dismissActive()
                        else {
                            childPages.add(InlinePage(label,next,nextSubmenus))
                            renderRight()
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
                    } else dismissActive()
                }
                child.items.forEach { action(it.first,it.second,it.first in child.submenuLabels) }
            } else {
                items.filter{StageMenuPolicy.group(it.first)==selectedCategory}
                    .forEach { action(it.first,it.second,it.first in submenuLabels) }
            }
        }
        val categoryButtons=mutableMapOf<Int,TextView>()
        lateinit var page:(Int?)->Unit
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
