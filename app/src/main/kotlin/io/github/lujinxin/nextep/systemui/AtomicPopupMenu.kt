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
    private val handler=Handler(Looper.getMainLooper())
    private val gate=StagePanelMenuGate({SystemUiPanelMonitor.state()},{SystemClock.uptimeMillis()},
        {delay,action->handler.postDelayed({action()},delay)},::dismissActive)
    private fun dismissActive(){active?.contentView?.animate()?.cancel();active?.dismiss();active=null}
    fun dismissAll(){gate.cancel()}
    internal fun isShowing()=active?.isShowing==true
    fun onPanelChanged(){gate.panelChanged()}
    fun show(context:Context,anchor:View,items:List<Pair<String,()->Unit>>,avoidBounds:List<Rect> = emptyList()) {
        StageRuntimeIdentity.install(context)
        if(items.isEmpty())return
        SystemUiPanelMonitor.refresh(context)
        gate.request({anchor.isAttachedToWindow},{SystemUiPanelCollapser.tryCollapse(context)},
            {showClosed(context,anchor,items,avoidBounds.map{Rect(it)})},
            {Toast.makeText(context,"请先收起系统通知栏或控制中心，再打开菜单",Toast.LENGTH_SHORT).show()})
    }
    private fun showClosed(context:Context,anchor:View,items:List<Pair<String,()->Unit>>,avoid:List<Rect>) {
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
            intArrayOf(Color.argb(216,62,76,96),Color.argb(206,39,52,73)) else
            intArrayOf(Color.argb(229,250,253,255),Color.argb(215,221,232,245))).apply {
            cornerRadius=dp(20).toFloat();setStroke(dp(1),if(dark)Color.argb(85,232,245,255) else Color.argb(205,255,255,255))
        }
        val rows=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(6));setBackgroundColor(Color.TRANSPARENT)}
        val content=ScrollView(context).apply{isFillViewport=false;clipToOutline=true;background=glass;addView(rows,android.view.ViewGroup.LayoutParams(-1,-2))}
        val popup=PopupWindow(content,1,1,true).apply {
            isOutsideTouchable=true;setTouchModal(false)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=dp(4).toFloat()
            animationStyle=0
        }
        active=popup
        popup.setOnDismissListener{content.animate().cancel();if(active===popup)active=null}
        val grouped=StageMenuPolicy.isMain(items.map{it.first})
        var initialized=false
        fun fit(count:Int,secondary:Boolean) {
            val width=minOf(dp(if(secondary)220 else 208),safe.width().coerceAtLeast(1))
            val height=minOf(dp(48)*count+dp(12),(safe.height()*.38f).toInt(),dp(300)).coerceAtLeast(1)
            val xy=IntArray(2);anchor.getLocationOnScreen(xy)
            fun Rect.box()=StageMenuPlacement.Box(left,top,right,bottom)
            val pos=StageMenuPlacement.place(safe.box(),StageMenuPlacement.Box(xy[0],xy[1],xy[0]+anchor.width,xy[1]+anchor.height),width,height,avoid.map{it.box()},dp(8))
            if(initialized)popup.update(pos.l,pos.t,width,height) else {
                popup.width=width;popup.height=height
                popup.showAtLocation(anchor,Gravity.TOP or Gravity.LEFT,pos.l,pos.t);initialized=true
            }
        }
        fun row(label:String,click:()->Unit) {
            rows.addView(TextView(context).apply {
                text=label;contentDescription=label;textSize=15f;minHeight=dp(48)
                setTextColor(ink);gravity=Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(12),dp(6),dp(12),dp(6));isClickable=true;isFocusable=true
                background=RippleDrawable(ColorStateList.valueOf(if(dark)Color.argb(40,235,245,255) else Color.argb(25,30,70,110)),null,
                    GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(12).toFloat()})
                setOnClickListener{click()}
            },LinearLayout.LayoutParams(-1,-2))
        }
        fun action(label:String,run:()->Unit) = row(label) {
            content.animate().cancel()
            content.animate().alpha(0f).setDuration(70).withEndAction {
                if(active!==popup || !popup.isShowing)return@withEndAction
                popup.dismiss()
                anchor.post {
                    if(SystemUiPanelMonitor.refresh(context)!=StagePanelMenuGate.State.CLOSED)return@post
                    runCatching(run).onFailure {
                        io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("workspace_menu","Action failed",it)
                        Toast.makeText(context,it.message ?: "操作未完成，请稍后重试",Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        lateinit var page:(Int?)->Unit
        page={ category ->
            if(active===popup && SystemUiPanelMonitor.refresh(context)==StagePanelMenuGate.State.CLOSED) {
                content.animate().cancel();rows.removeAllViews();content.scrollTo(0,0)
                if(grouped && category==null) {
                    StageMenuPolicy.groups.forEachIndexed {index,title->
                        if(items.any{StageMenuPolicy.group(it.first)==index})row("$title  ›"){page(index)}
                    }
                } else {
                    if(grouped && category!=null)row("‹  ${StageMenuPolicy.groups[category]}"){page(null)}
                    items.filter{!grouped || StageMenuPolicy.group(it.first)==category}.forEach{action(it.first,it.second)}
                }
                fit(rows.childCount,category!=null)
                content.alpha=.35f;content.animate().alpha(1f).setDuration(100).start()
            } else dismissActive()
        }
        runCatching{page(null)}.onFailure {
            dismissActive();io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("workspace_menu","Popup unavailable",it)
        }
    }
}
