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
    private val roots=WeakHashMap<View,ViewTreeObserver.OnGlobalLayoutListener>()
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
        roots[view]=listener
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) {changed()}
            override fun onViewDetachedFromWindow(v:View) {
                roots.remove(v)?.let {if(v.viewTreeObserver.isAlive)v.viewTreeObserver.removeOnGlobalLayoutListener(it)}
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
    fun state(requireNativeRoot:Boolean=systemUi && !inventoryConfirmed):StagePanelMenuGate.State {
        val entries=roots.keys.toList()
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
    fun changed() { AtomicPopupMenu.onPanelChanged() }
}
