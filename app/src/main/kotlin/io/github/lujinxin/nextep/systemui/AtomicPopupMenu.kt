package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import kotlin.math.ceil

/** Native PopupWindow with bounded translucent fallback. No screenshots, dim layer or task transforms. */
internal object AtomicPopupMenu {
    private var active: PopupWindow?=null
    private val handler=Handler(Looper.getMainLooper())
    private val gate=StagePanelMenuGate({SystemUiPanelMonitor.state()},{SystemClock.uptimeMillis()},
        {delay,action->handler.postDelayed({action()},delay)},::dismissActive)
    private fun dismissActive(){active?.dismiss();active=null}
    fun dismissAll(){gate.cancel()}
    internal fun isShowing():Boolean=active?.isShowing==true
    fun onPanelChanged(){gate.panelChanged()}
    fun show(context:Context,anchor:View,items:List<Pair<String,()->Unit>>) {
        StageRuntimeIdentity.install(context)
        if(items.isEmpty())return
        SystemUiPanelMonitor.refresh(context)
        gate.request({anchor.isAttachedToWindow}, {SystemUiPanelCollapser.tryCollapse(context)},
            {showClosed(context,anchor,items)},
            {Toast.makeText(context,"请先收起系统通知栏或控制中心，再打开菜单",Toast.LENGTH_SHORT).show()})
    }
    private fun showClosed(context:Context,anchor:View,items:List<Pair<String,()->Unit>>) {
        if(SystemUiPanelMonitor.refresh(context)!=StagePanelMenuGate.State.CLOSED)return
        val density=context.resources.displayMetrics.density
        fun dp(n:Int)=ceil(n*density).toInt()
        val screen=context.resources.displayMetrics
        val safe=runCatching {
            val m=context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val b=Rect(m.bounds);val i=m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            b.inset(i.left,i.top,i.right,i.bottom);b
        }.getOrElse{Rect(0,0,screen.widthPixels,screen.heightPixels)}
        val width=minOf(dp(236),(safe.width()-dp(16)).coerceAtLeast(1))
        val row=dp(48);val padding=dp(6)
        val height=minOf(row*items.size+padding*2,minOf(dp(300),(safe.height()*.40f).toInt())).coerceAtLeast(1)
        // PopupWindow has no public Window object for scoped background blur. Keep the
        // allowed gradient fallback instead of blurring the full display behind it.
        val glass=GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(220,35,40,50),Color.argb(210,24,29,38))).apply {
            cornerRadius=dp(20).toFloat();setStroke(dp(1),Color.argb(42,225,235,255))
        }
        val rows=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;setPadding(padding,padding,padding,padding)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val content=ScrollView(context).apply {
            isFillViewport=true;clipToOutline=true;this.background=glass
            addView(rows,ViewGroup.LayoutParams(-1,-2))
        }
        val popup=PopupWindow(content,width,height,true).apply {
            isOutsideTouchable=true;setTouchModal(false)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=dp(4).toFloat()
        }
        items.forEach { (label,action)->
            rows.addView(TextView(context).apply {
                text=label;contentDescription=label;textSize=15f;minHeight=row
                setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(14),dp(6),dp(14),dp(6));isClickable=true;isFocusable=true
                background=RippleDrawable(ColorStateList.valueOf(Color.argb(35,230,240,255)),null,
                    GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(10).toFloat()})
                setOnClickListener {
                    popup.dismiss()
                    anchor.post {
                        // A new native panel can appear between click and posted callback.
                        if(SystemUiPanelMonitor.refresh(context)!=StagePanelMenuGate.State.CLOSED)return@post
                        runCatching{action()}.onFailure {
                            io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("workspace_menu","Action failed",it)
                            Toast.makeText(context,it.message ?: "操作未完成，请稍后重试",Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },LinearLayout.LayoutParams(-1,-2))
        }
        val at=IntArray(2);anchor.getLocationOnScreen(at)
        val x=(at[0]+anchor.width/2-width/2).coerceIn(safe.left,(safe.right-width).coerceAtLeast(safe.left))
        val y=(at[1]+anchor.height).coerceIn(safe.top,(safe.bottom-height).coerceAtLeast(safe.top))
        active=popup
        popup.setOnDismissListener {if(active===popup)active=null}
        runCatching {popup.showAtLocation(anchor,Gravity.TOP or Gravity.LEFT,x,y)}.onFailure {
            dismissActive()
            io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("workspace_menu","Popup unavailable",it)
        }
    }
}
