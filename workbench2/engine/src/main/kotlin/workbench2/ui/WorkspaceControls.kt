package workbench2.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import workbench2.layout.WorkspaceLayout
import workbench2.motion.Box

/**
 * Native control chrome, not a task renderer or a full-display touch overlay.
 * Mount within the verified full-display coordinate parent. The owner publishes
 * a new committed layout and input-allowed flag; this view emits UI requests
 * only, never borrows tasks or marks a native transaction complete.
 */
class WorkspaceControls(context: Context) : ViewGroup(context) {
    enum class Action { ADD, COLLAPSE, MAIN_ACTION, RESTORE }
    data class Request(val action: Action,val revision: Long,val displayEpoch: Long)
    private data class Frame(val layout: WorkspaceLayout.Layout,val revision: Long,
                             val hidden: Boolean,val allowed: Boolean)
    private var frame: Frame?=null
    private var latest=-1L
    private var lastDisplayEpoch=-1L
    private var measuredRevision=-1L
    private var applied=-1L
    private var gesture=-1L
    private val origin=IntArray(2)
    private var listener: ((Request)->Unit)?=null

    private fun owner()=check(Looper.myLooper()===Looper.getMainLooper())
    private fun dp(n: Int)=n*resources.displayMetrics.density
    private fun shape(color: Int,radius: Int)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius)
    }
    private fun button(label: String,description: String,size: Float,action: Action,
                       color: Int,radius: Int)=TextView(context).apply {
        text=label;contentDescription=description;textSize=size;gravity=Gravity.CENTER
        setTextColor(Color.WHITE);includeFontPadding=false
        background=shape(color,radius)
        isClickable=true;isFocusable=true
        setOnClickListener { emit(action,this) }
        addView(this,LayoutParams(0,0))
        visibility=INVISIBLE;isEnabled=false
    }
    private val add=button("+","添加小窗，最多四个",26f,Action.ADD,Color.argb(210,30,30,30),22)
    private val collapse=button("‹›","收起小窗",14f,Action.COLLAPSE,Color.argb(210,28,43,53),12)
    private val main=button("•••","主窗口操作",14f,Action.MAIN_ACTION,Color.TRANSPARENT,12)
    private val restore=button("│","展开小窗",18f,Action.RESTORE,Color.TRANSPARENT,12)
    init { isClickable=false;isFocusable=false;clipChildren=true }

    fun setRequestListener(value: ((Request)->Unit)?) { owner();listener=value }

    /**
     * revision orders all UI publications, including same-size rotation or
     * switching input gates within one display epoch. Hidden must describe
     * the committed presentation, not a requested transition.
     */
    fun bind(layout: WorkspaceLayout.Layout,revision: Long,hidden: Boolean,inputAllowed: Boolean): Boolean {
        owner()
        if(revision<=latest || layout.displayEpoch<lastDisplayEpoch)return false
        invalidateControls()
        latest=revision;lastDisplayEpoch=layout.displayEpoch;frame=Frame(layout,revision,hidden,inputAllowed)
        restore.text=if(layout.recovery.horizontal)"━" else "│"
        requestLayout()
        return true
    }
    fun clear(revision: Long): Boolean {
        owner()
        if(revision<=latest)return false
        invalidateControls();latest=revision;frame=null;requestLayout();return true
    }
    private fun invalidateControls() {
        applied=-1L;measuredRevision=-1L;gesture=-1L
        for(i in 0 until childCount) {
            getChildAt(i).apply {
                cancelPendingInputEvents();isPressed=false;isEnabled=false;visibility=INVISIBLE
            }
        }
    }
    private fun box(f: Frame,index: Int): Box? {
        if(f.hidden)return if(index==3)f.layout.recovery.handle else null
        val l=f.layout
        return when(index) {
            0 -> l.addAction
            1 -> l.topActions?.collapse
            2 -> l.topActions?.main
            else -> null
        }
    }
    private fun matches(f: Frame,w: Int,h: Int): Boolean {
        val v=f.layout.viewport
        if(w!=v.width || h!=v.height)return false
        if(isAttachedToWindow) {
            if(v.rotation!=null && display?.rotation!=v.rotation)return false
            getLocationOnScreen(origin)
            if(origin[0]!=0 || origin[1]!=0)return false
        }
        return true
    }
    private fun ready(): Frame? = frame?.takeIf {
        applied==it.revision && it.allowed && isLaidOut && matches(it,width,height)
    }
    private fun emit(action: Action,view: View) {
        owner();val f=ready()?:return
        val index=indexOfChild(view)
        if(view.visibility!=VISIBLE || !view.isEnabled || box(f,index)==null)return
        listener?.invoke(Request(action,f.revision,f.layout.displayEpoch))
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec);val h=MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w,h)
        val f=frame?.takeIf { MeasureSpec.getMode(widthMeasureSpec)==MeasureSpec.EXACTLY &&
            MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.EXACTLY &&
            it.layout.viewport.width==w && it.layout.viewport.height==h }
        measuredRevision=f?.revision?:-1L
        for(i in 0 until childCount) {
            val b=f?.let { box(it,i) }
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(b?.w?.toInt()?:0,MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(b?.h?.toInt()?:0,MeasureSpec.EXACTLY))
        }
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        val f=frame?.takeIf { measuredRevision==it.revision && matches(it,r-l,b-t) }
        applied=f?.revision?:-1L
        for(i in 0 until childCount) {
            val child=getChildAt(i)
            val bounds=f?.let { box(it,i) }
            val visible=bounds!=null
            child.visibility=if(visible)VISIBLE else INVISIBLE
            child.isEnabled=visible && f?.allowed==true
            if(bounds==null)child.layout(0,0,0,0)
            else child.layout(bounds.x.toInt(),bounds.y.toInt(),
                (bounds.x+bounds.w).toInt(),(bounds.y+bounds.h).toInt())
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val f=ready()
        if(event.actionMasked==MotionEvent.ACTION_DOWN)gesture=f?.revision?:-1L
        if(f==null || gesture!=f.revision) {
            if(event.actionMasked!=MotionEvent.ACTION_DOWN) {
                val cancel=MotionEvent.obtain(event)
                try { cancel.action=MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel) }
                finally { cancel.recycle() }
            }
            gesture=-1L;return false
        }
        val handled=super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)gesture=-1L
        return handled
    }
    override fun onDetachedFromWindow() {
        invalidateControls()
        frame=null // Require fresh committed publication after host/window detach.
        super.onDetachedFromWindow()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestLayout()
    }
}
