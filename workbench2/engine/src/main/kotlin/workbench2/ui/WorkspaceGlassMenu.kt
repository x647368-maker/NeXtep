package workbench2.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import workbench2.layout.WorkspaceLayout
import workbench2.motion.Box

/**
 * Native, locally translucent menu using the frozen first-party glass material,
 * category order, glyphs and placement. Host supplies only implemented actions;
 * selection requests never alter task ownership or forge a core receipt.
 * No system window, screenshots, blur pass, polling or delayed action.
 */
class WorkspaceGlassMenu(private val context: Context) {
    data class Entry(val id: String,val label: String,val enabled: Boolean=true,
                     val children: List<Entry> = emptyList())
    data class Selection(val id: String,val revision: Long,val displayEpoch: Long)
    private var active: PopupWindow?=null
    internal val contentView: View? get()=active?.contentView
    val isShowing get()=active?.isShowing==true
    private fun owner()=check(Looper.myLooper()===Looper.getMainLooper())
    fun dismiss() { owner();val old=active;active=null;old?.dismiss() }

    // Preserve explicit caller-provided commands, never synthesize unavailable leaves.
    private fun snapshot(items: List<Entry>): List<Entry>? {
        val ids=HashSet<String>()
        fun copy(input: List<Entry>,depth: Int): List<Entry> {
            require(depth<=5)
            return input.map { e ->
                require(e.id.isNotBlank()&&e.id.length<=128&&e.label.isNotBlank()&&e.label.length<=128)
                require(ids.add(e.id)&&ids.size<=256)
                e.copy(children=if(e.children.isEmpty())emptyList() else copy(e.children,depth+1))
            }
        }
        return try { copy(items,0).takeIf { it.isNotEmpty() } } catch(_:IllegalArgumentException){null}
    }
    private fun pxBox(b: Box)=StageMenuPlacement.Box(b.x.toInt(),b.y.toInt(),
        (b.x+b.w).toInt(),(b.y+b.h).toInt())

    /**
     * Anchor and layout must belong to the same verified full-display parent.
     * guard must recheck current presentation/input/notification state.
     * Unknown system-panel state is represented by false, never auto-collapsed.
     */
    fun show(anchor: View,layout: WorkspaceLayout.Layout,revision: Long,
             entries: List<Entry>,selectedLabels: Set<String>,guard: ()->Boolean,
             onSelect: (Selection)->Unit): Boolean {
        owner();dismiss()
        val items=snapshot(entries)?:return false
        if(revision<0 || !anchor.isAttachedToWindow || !guard())return false
        val selected=selectedLabels.toSet()
        val v=layout.viewport
        val d=v.density
        fun dp(n: Int)=ceil(n*d).toInt()
        val origin=IntArray(2);anchor.getLocationOnScreen(origin)
        val safe=StageMenuPlacement.Box(v.insets.left+dp(8),v.insets.top+dp(8),
            v.width-v.insets.right-dp(8),v.height-v.insets.bottom-dp(8))
        if(safe.w<dp(160)||safe.h<dp(48)||anchor.width<=0||anchor.height<=0)return false
        val anchorBox=StageMenuPlacement.Box(origin[0],origin[1],origin[0]+anchor.width,origin[1]+anchor.height)
        val avoid=(listOf(layout.main)+layout.cards.map {it.box}).map(::pxBox)
        val compact=safe.w<dp(600)
        val labelProbe=TextView(context).apply {textSize=12f;includeFontPadding=false}
        val categoryWidth=maxOf(dp(if(compact)120 else 132),
            ceil(StageMenuPolicy.groups.maxOf{labelProbe.paint.measureText(it)}).toInt()+
                dp(4)*2+dp(5)*2+dp(14)+dp(8)+dp(4)*2+dp(2))
        val font=labelProbe.paint.fontMetricsInt
        val categoryHeight=maxOf(dp(30),font.bottom-font.top+dp(4))*StageMenuPolicy.groups.size+dp(8)
        val wantedWidth=maxOf(dp(if(compact)276 else 312),categoryWidth+dp(3)+dp(144))
        val wantedHeight=maxOf(dp(if(compact)272 else 280),categoryHeight)
        val dual=StageMenuPlacement.pinnedDual(safe,anchorBox,wantedWidth,wantedHeight,
            dp(220),minOf(categoryHeight,safe.h),avoid,dp(2))
        val bounds=dual?:StageMenuPlacement.compact(safe,anchorBox,dp(220),dp(272),
            dp(160),dp(48),avoid,dp(8))
        val categories=LinearLayout(context).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(4));background=GlassPane(d)
        }
        val rows=LinearLayout(context).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(4));background=GlassPane(d)
        }
        val catScroll=ScrollView(context).apply{addView(categories)}
        val rowScroll=ScrollView(context).apply{addView(rows)}
        val content=LinearLayout(context).apply{
            orientation=if(dual!=null)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity=Gravity.TOP
            if(dual!=null){
                val cw=categoryWidth.coerceAtMost((bounds.w-dp(3))*46/100).coerceAtLeast(1)
                addView(catScroll,LinearLayout.LayoutParams(cw,-1).apply{rightMargin=dp(3)})
                addView(rowScroll,LinearLayout.LayoutParams((bounds.w-cw-dp(3)).coerceAtLeast(1),-1))
            }else addView(rowScroll,LinearLayout.LayoutParams(-1,-1))
        }
        val popup=PopupWindow(content,bounds.w,bounds.h,true).apply{
            isOutsideTouchable=true;isClippingEnabled=true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation=dp(4).toFloat()
        }
        fun usable()=active===popup&&popup.isShowing&&anchor.isAttachedToWindow&&guard()
        fun row(parent: LinearLayout,label: String,group: Int,category: Boolean=false,
                choice: Boolean=false,chosen: Boolean=false,navigation: Boolean=false,
                enabled: Boolean=true,click: ()->Unit): TextView {
            val btn=TextView(context).apply{
                text=label.removePrefix("✓ ");contentDescription=label
                textSize=12f;includeFontPadding=false;minHeight=dp(if(category)30 else 28)
                setTextColor(Color.WHITE);setShadowLayer(dp(1).toFloat(),0f,dp(1).toFloat(),Color.argb(205,0,0,0))
                gravity=Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(5),dp(2),dp(5),dp(2));isClickable=true;isFocusable=true
                isEnabled=enabled;alpha=if(enabled)1f else .45f
                isSelected=chosen
                val size=dp(14)
                val icon=StageMenuLineGlyph(group,if(category)0 else 1).apply{setBounds(0,0,size,size)}
                val mark=when{
                    category||navigation->StageMenuLineGlyph(0,2).apply{setBounds(0,0,dp(8),size)}
                    choice->StageMenuLineGlyph(0,if(chosen)3 else 4).apply{setBounds(0,0,size,size)}
                    else->null
                }
                setCompoundDrawables(icon,null,mark,null);compoundDrawablePadding=dp(4)
                val selection=if(category||choice)StateListDrawable().apply{
                    addState(intArrayOf(android.R.attr.state_selected),GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(214,44,166,253),
                            Color.argb(211,18,118,216),Color.argb(194,21,92,185))).apply{
                        cornerRadius=dp(10).toFloat();setStroke(dp(1),Color.argb(124,186,232,255))
                    })
                    addState(intArrayOf(),ColorDrawable(Color.TRANSPARENT))
                }else null
                background=RippleDrawable(ColorStateList.valueOf(Color.argb(40,235,245,255)),
                    selection,GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(12).toFloat()})
                setOnClickListener{
                    if(active!==popup || this.parent!==parent)return@setOnClickListener
                    if(!usable()){dismiss();return@setOnClickListener}
                    if(isEnabled)click()
                }
            }
            parent.addView(btn,LinearLayout.LayoutParams(-1,-2));return btn
        }
        val groups=StageMenuPolicy.groups.indices.filter{g->items.any{StageMenuPolicy.group(it.label)==g}}
        var category=groups.first()
        val trail=ArrayList<Entry>()
        val categoryButtons=HashMap<Int,TextView>()
        lateinit var render: ()->Unit
        fun selectGroup(group: Int){category=group;trail.clear();render()}
        fun select(entry: Entry){
            if(entry.children.isNotEmpty()){trail.add(entry);render();return}
            if(!usable())return
            dismiss()
            // Dismissal may invalidate the owner synchronously. Recheck it before dispatch.
            if(anchor.isAttachedToWindow&&guard())onSelect(Selection(entry.id,revision,layout.displayEpoch))
        }
        render={
            for((g,b) in categoryButtons){b.isSelected=g==category;b.setTypeface(null,if(g==category)Typeface.BOLD else Typeface.NORMAL)}
            rows.removeAllViews();rowScroll.scrollTo(0,0)
            if(dual==null && trail.isEmpty()){
                // Accessible compact fallback retains all categories.
                groups.forEach{g->row(rows,StageMenuPolicy.groups[g],g,category=true,chosen=g==category){selectGroup(g)}}
            }
            val page=trail.lastOrNull()
            if(page!=null)row(rows,"‹  "+page.label,category,navigation=true){trail.removeAt(trail.lastIndex);render()}
            else if(category==2||category==3)row(rows,"‹  "+StageMenuPolicy.groups[category],category,navigation=true){selectGroup(groups.first())}
            (page?.children?:items.filter{StageMenuPolicy.group(it.label)==category}).forEach{entry->
                row(rows,entry.label,StageMenuPolicy.actionGlyph(entry.label),
                    choice=StageMenuPolicy.isLayoutChoice(entry.label),
                    chosen=StageMenuPolicy.isSelected(entry.label,selected),
                    navigation=entry.children.isNotEmpty(),enabled=entry.enabled){select(entry)}
            }
        }
        if(dual!=null)groups.forEach{g->
            categoryButtons[g]=row(categories,StageMenuPolicy.groups[g],g,category=true){selectGroup(g)}
        }
        render()
        popup.setOnDismissListener{if(active===popup)active=null}
        if(!guard()||!anchor.isAttachedToWindow)return false
        active=popup
        return try{popup.showAtLocation(anchor,Gravity.TOP or Gravity.LEFT,bounds.l,bounds.t);popup.isShowing}
        catch(_:RuntimeException){dismiss();false}
    }

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


}
