package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Observe existing native windows only; no new SystemUI hooks, object graph scans or content inspection. */
internal object SystemUiPanelMonitor {
    private var inventoryConfirmed=false
    private var systemUi=false
    private data class Observers(val layout:ViewTreeObserver.OnGlobalLayoutListener,
        val draw:ViewTreeObserver.OnPreDrawListener, val focus:ViewTreeObserver.OnWindowFocusChangeListener)
    private val roots=WeakHashMap<View,Observers>()
    private var lastState:StagePanelMenuGate.State?=null
    fun isPanel(view:View):Boolean {
        val title=(view.layoutParams as? WindowManager.LayoutParams)?.title?.toString().orEmpty()
        return title.contains("NotificationShade",true) || title.contains("ControlCenter",true) ||
            view.javaClass.name.contains("NotificationShadeWindowView")
    }
    fun observe(view:View) {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){view.post{observe(view)};return}
        if(!isPanel(view) || roots.containsKey(view))return
        val ref=WeakReference(view)
        val listener=ViewTreeObserver.OnGlobalLayoutListener {if(ref.get()!=null)changed()}
        val draw=ViewTreeObserver.OnPreDrawListener {if(ref.get()!=null)changed();true}
        val focus=ViewTreeObserver.OnWindowFocusChangeListener {if(ref.get()!=null)changed()}
        roots[view]=Observers(listener,draw,focus)
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.viewTreeObserver.addOnPreDrawListener(draw)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) {changed()}
            override fun onViewDetachedFromWindow(v:View) {
                roots.remove(v)?.let {if(v.viewTreeObserver.isAlive){
                    v.viewTreeObserver.removeOnGlobalLayoutListener(it.layout)
                    v.viewTreeObserver.removeOnPreDrawListener(it.draw)
                    v.viewTreeObserver.removeOnWindowFocusChangeListener(it.focus)
                }}
                v.removeOnAttachStateChangeListener(this);changed()
            }
        })
        changed()
    }
    fun refresh(context:Context):StagePanelMenuGate.State {
        systemUi=context.packageName=="com.android.systemui"
        if(systemUi)inventoryConfirmed=false
        if(systemUi)runCatching {
            val c=Class.forName("android.view.WindowManagerGlobal")
            val global=c.getMethod("getInstance").invoke(null)
            val list=runCatching {c.getMethod("getRootViews").invoke(global) as? Collection<*>}.getOrNull()
                ?: c.getDeclaredField("mViews").apply{isAccessible=true}.get(global) as? Collection<*>
                ?: error("No root inventory")
            list.filterIsInstance<View>().forEach(::observe);inventoryConfirmed=true
        }
        return state()
    }
    fun state(requireNativeRoot:Boolean=systemUi):StagePanelMenuGate.State {
        if(requireNativeRoot && !inventoryConfirmed)return StagePanelMenuGate.State.UNKNOWN
        val entries=roots.keys.toList()
        // A readable inventory with no recognized OEM panel is still unknown.
        // Never treat successful reflection alone as proof the shade is closed.
        if(entries.isEmpty())return if(requireNativeRoot)StagePanelMenuGate.State.UNKNOWN else StagePanelMenuGate.State.CLOSED
        var unknown=false
        for(view in entries) {
            if(!view.isAttachedToWindow || !view.isShown || view.windowVisibility!=View.VISIBLE)continue
            val p=view.layoutParams as? WindowManager.LayoutParams
            if(p==null){unknown=true;continue}
            // Native expanded panels are focusable. A visible fullscreen nonfocusable OEM
            // panel is ambiguous: do not overlay a menu until native state is visibly closed.
            if(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE==0)return StagePanelMenuGate.State.OPEN
            val bar=(64*view.resources.displayMetrics.density).toInt()
            if(view.height>bar)unknown=true
        }
        return if(unknown)StagePanelMenuGate.State.UNKNOWN else StagePanelMenuGate.State.CLOSED
    }
    internal fun diagnostic():String=roots.keys.toList().joinToString(";") {
        val p=it.layoutParams as? WindowManager.LayoutParams
        "attached=${it.isAttachedToWindow},shown=${it.isShown},visibility=${it.visibility},window=${it.windowVisibility},flags=${p?.flags}"
    }
    fun changed() {
        val next=state()
        if(next!=lastState){lastState=next;io.github.x647368maker.atomicworkbench.logging.NeXtepLog.info("shade_state","$next ${diagnostic()}")}
        AtomicPopupMenu.onPanelChanged()
    }
}
