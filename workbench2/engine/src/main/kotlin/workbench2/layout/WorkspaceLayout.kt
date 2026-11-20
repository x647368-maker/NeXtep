package workbench2.layout

import android.view.WindowInsets
import android.view.WindowMetrics
import workbench2.motion.Box
import workbench2.unified.LaunchRequest
import workbench2.unified.StartPlan
import java.util.Collections
import kotlin.math.roundToInt

/**
 * Destination geometry only. No virtual display, task resolution, task identity,
 * view or native session is manufactured here. Run on layout changes, not frames.
 * Selected rails remain in panel axes; all rectangles and UI hit tests share
 * the same physical destination coordinate space.
 */
object WorkspaceLayout {
    enum class Rail { TOP, LEFT, RIGHT }
    data class Insets(val left: Int = 0, val top: Int = 0,
                      val right: Int = 0, val bottom: Int = 0)
    data class Viewport(val width: Int, val height: Int, val dpi: Int,
                        val insets: Insets, val controlHeight: Int,
                        val sideWidth: Int, val mainInset: Int = 0,
                        val rotation: Int? = null) {
        init {
            require(width in 2..32768 && height in 2..32768 && dpi in 1..2000)
            require(listOf(insets.left,insets.top,insets.right,insets.bottom).all { it in 0..32768 })
            require(usableWidth > 1 && usableHeight > 1)
            require(controlHeight in 1 until usableHeight && sideWidth in 1 until usableWidth)
            require(mainInset in 0..minOf(usableWidth,usableHeight)/2)
            require(rotation == null || rotation in 0..3)
        }
        val usableWidth get() = width-insets.left-insets.right
        val usableHeight get() = height-insets.top-insets.bottom
        val density get() = dpi/160f
    }
    data class Card(val slot: Int, val box: Box)
    data class TopActions(val collapse: Box, val main: Box, val add: Box)
    data class Recovery(val main: Box, val handle: Box, val horizontal: Boolean)
    enum class HitKind { CARD, COLLAPSE, MAIN_ACTION, ADD }
    data class Hit(val kind: HitKind, val slot: Int? = null)

    class Layout internal constructor(val main: Box, val rail: Box,
        cards: List<Card>, val topActions: TopActions?, val displayEpoch: Long,
        val control: Box, val panelRotationDegrees: Int,
        val viewport: Viewport, val addAction: Box?, val recovery: Recovery,
        val collapseAction: Box) {
        val cards: List<Card> = Collections.unmodifiableList(ArrayList(cards))
        /** UI destinations only; not native task input dispatch or input proof. */
        fun hitAt(x: Double,y: Double): Hit? {
            fun hit(b: Box) = x>=b.x && x<b.x+b.w && y>=b.y && y<b.y+b.h
            if(hit(collapseAction)) return Hit(HitKind.COLLAPSE)
            topActions?.let { a ->
                if(hit(a.main)) return Hit(HitKind.MAIN_ACTION)
            }
            addAction?.let { if(hit(it)) return Hit(HitKind.ADD) }
            return cards.firstOrNull { hit(it.box) }?.let { Hit(HitKind.CARD,it.slot) }
        }
        /** Map by persisted slot identity, never by task ID, and never fill a missing slot. */
        fun startPlan(mainRequest: LaunchRequest, bySlot: Map<Int,LaunchRequest>): StartPlan {
            require(bySlot.keys == cards.map { it.slot }.toSet())
            val requests = listOf(mainRequest) + cards.map { bySlot.getValue(it.slot) }
            return StartPlan(Collections.unmodifiableList(requests),
                Collections.unmodifiableList(listOf(main)+cards.map { it.box }),displayEpoch)
        }
    }
    private fun rect(x: Int,y: Int,w: Int,h: Int): Box {
        require(w > 0 && h > 0) { "Insufficient layout space" }
        return Box(x.toDouble(),y.toDouble(),w.toDouble(),h.toDouble())
    }
    private fun fits(inner: Box, outer: Box) =
        outer.contains(inner.x,inner.y) && outer.contains(inner.x+inner.w,inner.y+inner.h)
    private fun intersects(a: Box,b: Box) =
        a.x < b.x+b.w && a.x+a.w > b.x && a.y < b.y+b.h && a.y+a.h > b.y
    private fun fitted(region: Box, width: Int, height: Int, roundRight: Boolean = false): Box {
        val scale=minOf(region.w/width,region.h/height)
        val w=(width*scale).roundToInt(); val h=(height*scale).roundToInt()
        require(w>0 && h>0)
        return rect(region.x.toInt()+(region.w.toInt()-w+(if(roundRight) 1 else 0))/2,
            region.y.toInt()+(region.h.toInt()-h)/2,w,h)
    }

    // Frozen physical display predicates, independent of ROM model strings.
    private fun wideTablet(width: Int,height: Int): Boolean {
        val short=minOf(width,height); val long=maxOf(width,height)
        return short>=1300 && long.toFloat()/short>=1.45f && long.toLong()<2L*short
    }
    private fun wideLandscape(v: Viewport) = v.width>v.height && wideTablet(v.width,v.height)
    private fun nearSquare(v: Viewport) = minOf(v.width,v.height)>=1800 &&
        maxOf(v.width,v.height).toFloat()/minOf(v.width,v.height)<=1.25f
    private fun rotatedSide(v: Viewport,rail: Rail) =
        rail!=Rail.TOP && v.width>v.height && !wideLandscape(v)
    // Frozen SidebarButtonLayout: LEFT is physical top when toolbar is right.
    private fun railAtPhysicalTop(v: Viewport,rail: Rail) =
        (rail==Rail.LEFT) != (v.rotation==1)
    private fun requireSupported(v: Viewport,rail: Rail) {
        require(!rotatedSide(v,rail) || v.rotation!=null) {
            "Rotated side rail requires an explicit display rotation"
        }
    }
    private fun region(v: Viewport,rail: Rail,depth: Int): Box {
        if(rotatedSide(v,rail)) {
            return rect(v.insets.left+v.mainInset+(if(v.rotation==1)v.controlHeight else 0),
                v.insets.top+v.mainInset+(if(railAtPhysicalTop(v,rail))depth else 0),
                v.usableWidth-v.controlHeight-2*v.mainInset,
                v.usableHeight-depth-2*v.mainInset)
        }
        val side=if(rail==Rail.TOP) 0 else depth
        return rect(v.insets.left+v.mainInset+(if(rail==Rail.LEFT) side else 0),
            v.insets.top+v.controlHeight+v.mainInset+(if(rail==Rail.TOP) depth else 0),
            v.usableWidth-side-2*v.mainInset,
            v.usableHeight-v.controlHeight-(if(rail==Rail.TOP) depth else 0)-2*v.mainInset)
    }
    private fun control(v: Viewport,rail: Rail): Box = if(rotatedSide(v,rail))
        rect(v.insets.left+(if(v.rotation==1)0 else v.usableWidth-v.controlHeight),
            v.insets.top,v.controlHeight,v.usableHeight)
        else rect(v.insets.left,v.insets.top,v.usableWidth,v.controlHeight)
    private fun project(rail: Box,degrees: Int,left: Int,top: Int,width: Int,height: Int): Box =
        when(degrees) {
            90 -> rect(rail.x.toInt()+rail.w.toInt()-top-height,rail.y.toInt()+left,height,width)
            -90 -> rect(rail.x.toInt()+top,rail.y.toInt()+rail.h.toInt()-left-width,height,width)
            else -> rect(rail.x.toInt()+left,rail.y.toInt()+top,width,height)
        }
    private fun mainBox(v: Viewport,rail: Rail,depth: Int) =
        fitted(region(v,rail,depth),v.width,v.height,rotatedSide(v,rail)&&v.rotation!=1)

    private fun presentation(v: Viewport,rail: Rail,priority: Boolean): Viewport {
        if(!priority) return v
        val band=(56*v.density).roundToInt().coerceAtLeast(1)
        val height=minOf(v.controlHeight,band)
        // Frozen wide landscape tablet retains its side breathing margin.
        val inset=if(wideLandscape(v)&&rail!=Rail.TOP)
            (12*v.density).roundToInt().coerceAtLeast(1).coerceAtMost(
                (minOf(v.usableWidth-v.sideWidth,v.usableHeight-height)/4).coerceAtLeast(1))
            else 0
        // Footer overlap remains disabled until independently verified input regions.
        return v.copy(controlHeight=height,mainInset=inset)
    }

    /**
     * Public Android insets boundary for an already verified full-screen window.
     * The caller must capture dimensions, rotation, density and epoch together.
     * Stable system bars/cutout constrain only destination geometry.
     */
    fun fromWindowMetrics(metrics: WindowMetrics,width: Int,height: Int,dpi: Int,
                          rotation: Int,rail: Rail,count: Int,priority: Boolean): Viewport {
        val bounds=metrics.bounds
        require(bounds.left==0 && bounds.top==0 && bounds.width()==width && bounds.height()==height) {
            "Full-display coordinate space required"
        }
        return fromWindowInsets(width,height,dpi,rotation,metrics.windowInsets,rail,count,priority)
    }

    fun fromWindowInsets(width: Int,height: Int,dpi: Int,rotation: Int,insets: WindowInsets,
                         rail: Rail,count: Int,priority: Boolean): Viewport {
        require(width in 2..32768 && height in 2..32768 && dpi in 1..2000 && rotation in 0..3 && count in 0..4)
        val d=dpi/160f
        val i=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val tablet=wideTablet(width,height)
        // Old Y700 safety rule: even a ported ROM with a bad reported status inset.
        val top=if(tablet)maxOf(i.top,(48*d).toInt()) else i.top
        val safe=Insets(i.left,top,i.right,i.bottom)
        val uw=width-safe.left-safe.right; val uh=height-safe.top-safe.bottom
        require(uw>1 && uh>1)
        val landscape=width>height
        val expanded=minOf(width,height)*160f/dpi>=600f
        val toolbar=((if(priority)56 else if(expanded)112 else 96)*d).roundToInt()
        val short=if(landscape)uh else uw
        val fraction=if(landscape) { if(tablet).33f else .25f }
            else if(rail!=Rail.TOP && count>0) {
                if(tablet) when(count){1->.16f;2->.18f;3->.20f;else->.22f}
                else when(count){1->.215f;2->.235f;else->.25f}
            } else .25f
        val side=(short*fraction).roundToInt().coerceAtLeast(2)
        val normalInset=if(priority)0 else (12*d).roundToInt()
        return presentation(Viewport(width,height,dpi,safe,toolbar,side,normalInset,rotation),rail,priority)
    }

    /** Geometry only, never detaches tasks. Retains the historical fold handle reserve. */
    fun focusedMain(viewport: Viewport,rail: Rail,mainPriority: Boolean = false): Box {
        requireSupported(viewport,rail)
        val v=presentation(viewport,rail,mainPriority)
        val depth=if(rail!=Rail.TOP && nearSquare(v))
            minOf(v.sideWidth,(44*v.density).roundToInt().coerceAtLeast(2)) else 1
        return mainBox(v,rail,depth)
    }

    /** Sparse slots retain identity and an optional persisted visual permutation. */
    fun calculate(viewport: Viewport,rail: Rail,activeSlots: List<Int>,
                  mainPriority: Boolean,displayEpoch: Long,
                  visualOrder: List<Int> = listOf(0,1,2,3)): Layout {
        require(displayEpoch>=0 && activeSlots.size<=4 &&
            activeSlots.all { it in 0..3 } && activeSlots.distinct().size==activeSlots.size)
        require(visualOrder.sorted()==listOf(0,1,2,3))
        requireSupported(viewport,rail)
        val v=presentation(viewport,rail,mainPriority)
        val slots=visualOrder.filter { it in activeSlots }
        val count=slots.size
        val d=v.density; val x=v.insets.left; val y=v.insets.top+v.controlHeight
        val w=v.usableWidth; val h=v.usableHeight-v.controlHeight
        fun dp(n: Int)=(n*d).toInt()
        val cards=mutableListOf<Card>()
        val railBox: Box
        val actions: TopActions?
        val addAction: Box?
        val depth: Int
        val rotated=rotatedSide(v,rail)
        val degrees=if(!rotated)0 else if(v.rotation==1)-90 else 90
        if(rail==Rail.TOP) {
            val gutter=dp(8)
            val cap=when(count){1->.38f;2->.34f;3->.30f;else->1f}
            val cardWidth=if(count==0) 0 else minOf((w-(count+1)*8*d)/count,w*cap).toInt()
            // Former 720:1120 retained only as card-design ratio, not task resolution.
            val desired=if(count==0) (56*d).roundToInt()
                else (cardWidth*1120f/720f+(if(mainPriority)56 else 64)*d).roundToInt()
            val minMain=((if(v.width>v.height)160 else 220)*d).roundToInt()
            val limit=minOf(w-1,h-minMain)
            val shapeCap=when {
                nearSquare(v)->(v.usableHeight*.24f).roundToInt()
                wideLandscape(v)->(v.usableHeight*.32f).roundToInt()
                else->limit
            }
            require(limit>=2 && shapeCap>=2)
            depth=if(count==0) desired else minOf(desired,limit,shapeCap)
            railBox=rect(x,y,w,depth)
            if(count>0) {
                val body=minOf((cardWidth*1120.0/720).toInt(),depth-dp(48)-gutter)
                val width=minOf(cardWidth,(body*720.0/1120).toInt())
                for((i,slot) in slots.withIndex())
                    cards.add(Card(slot,rect(x+gutter+i*(width+gutter),y+gutter,width,body)))
            }
            actions=TopActions(rect(x+dp(12),y+depth-dp(44),dp(44),dp(32)),
                rect(x+(w-dp(64))/2,y+depth-dp(40),dp(64),dp(24)),
                rect(x+w-dp(54),y+depth-dp(48),dp(44),dp(44)))
            addAction=if(count<4)actions.add else null
            val controls=listOf(actions.collapse,actions.main,actions.add)
            require(controls.all { fits(it,railBox) && cards.none { c->intersects(it,c.box) } })
            require(controls.indices.all { i->(i+1 until controls.size).none { intersects(controls[i],controls[it]) } })
        } else {
            depth=v.sideWidth
            railBox=if(rotated) rect(x+(if(v.rotation==1)v.controlHeight else 0),
                v.insets.top+(if(railAtPhysicalTop(v,rail))0 else v.usableHeight-depth),
                w-v.controlHeight,depth)
                else rect(if(rail==Rail.LEFT)x else x+w-depth,y,depth,h)
            actions=null
            val logicalHeight=if(rotated)railBox.w.toInt() else h
            val button=dp(44)
            val footer=if(count in 1..3)dp(56) else 0
            val compactTablet=v.height>v.width && wideTablet(v.width,v.height)
            val gap=if(compactTablet)(8*d).roundToInt().coerceAtLeast(1) else d.toInt().coerceAtLeast(1)
            var lastBottom=0
            if(count>0) {
                val maxWidth=depth-gap
                val available=logicalHeight-footer-(if(compactTablet)2*gap else 0)
                val height=minOf((maxWidth.toDouble()*v.usableHeight/w).toInt(),
                    (available-gap*(count-1))/count)
                val width=minOf(maxWidth,(height.toDouble()*w/v.usableHeight).toInt())
                val pad=if(compactTablet)gap else (available-height*count-gap*(count-1))/2
                for((i,slot) in slots.withIndex()) {
                    val top=pad+i*(height+gap)
                    cards.add(Card(slot,project(railBox,degrees,(depth-width)/2,top,width,height)))
                    lastBottom=top+height
                }
            }
            addAction=if(count==4)null else {
                val top=when {
                    count==0 -> (logicalHeight-button)/2
                    compactTablet -> minOf(lastBottom+gap,logicalHeight-button-gap)
                    else -> logicalHeight-dp(12)-button
                }
                project(railBox,degrees,(depth-button)/2,top,button,button)
            }
        }
        val main=mainBox(v,rail,depth)
        val controls=control(v,rail)
        val safe=rect(v.insets.left,v.insets.top,v.usableWidth,v.usableHeight)
        require(listOf(main,railBox,controls).all { fits(it,safe) })
        require(!intersects(main,railBox) && !intersects(main,controls) && !intersects(railBox,controls))
        require(cards.all { fits(it.box,railBox) && !intersects(it.box,main) })
        require(cards.indices.all { i->(i+1 until cards.size).none { intersects(cards[i].box,cards[it].box) } })
        require(addAction==null || (fits(addAction,railBox) &&
            cards.none { intersects(it.box,addAction) }))
        // Historical smart-rail 44dp reserve and 96dp long, white handle.
        // This is a proposed geometry only: callers must commit the real main
        // presentation and input before displaying the recovery control.
        val horizontal=rail==Rail.TOP || rotated
        val reserve=minOf(if(horizontal)railBox.h.toInt() else railBox.w.toInt(),
            (44*d).roundToInt().coerceAtLeast(1))
        val long=minOf(if(horizontal)railBox.w.toInt() else railBox.h.toInt(),
            (96*d).roundToInt().coerceAtLeast(1))
        val rw=if(horizontal)long else reserve
        val rh=if(horizontal)reserve else long
        val rx=if(horizontal)railBox.x.toInt()+(railBox.w.toInt()-rw)/2
            else if(rail==Rail.LEFT)railBox.x.toInt() else (railBox.x+railBox.w).toInt()-rw
        val ry=if(!horizontal)railBox.y.toInt()+(railBox.h.toInt()-rh)/2
            else if(rail==Rail.TOP || railAtPhysicalTop(v,rail))railBox.y.toInt() else (railBox.y+railBox.h).toInt()-rh
        val recovery=Recovery(mainBox(v,rail,reserve),rect(rx,ry,rw,rh),horizontal)
        require(fits(recovery.handle,safe) && !intersects(recovery.handle,recovery.main) &&
            !intersects(recovery.handle,controls))
        // Frozen side handle: 48dp at the toolbar end; top row retains its own anchor.
        val collapseAction=actions?.collapse ?: run {
            val size=minOf(dp(48).coerceAtLeast(1),controls.w.toInt(),controls.h.toInt())
            val pad=dp(8)
            if(rotated) rect(controls.x.toInt()+(controls.w.toInt()-size)/2,
                maxOf(controls.y.toInt(),(controls.y+controls.h).toInt()-pad-size),size,size)
            else rect(maxOf(controls.x.toInt(),(controls.x+controls.w).toInt()-pad-size),
                controls.y.toInt()+(controls.h.toInt()-size)/2,size,size)
        }
        require(fits(collapseAction,if(actions==null)controls else railBox))
        return Layout(main,railBox,cards,actions,displayEpoch,controls,degrees,v,addAction,recovery,collapseAction)
    }
}
