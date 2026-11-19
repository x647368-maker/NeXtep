package workbench2.layout

import workbench2.motion.Box
import workbench2.unified.LaunchRequest
import workbench2.unified.StartPlan
import java.util.Collections
import kotlin.math.roundToInt

/**
 * Destination geometry only. No virtual display, task resolution, task identity,
 * view or native session is manufactured here. Run on a layout change, not a frame.
 * Supports horizontal top across device shapes and physically upright side rails.
 * Rotated phone/foldable landscape-side rails require the legacy projection;
 * never silently remap them.
 */
object WorkspaceLayout {
    enum class Rail { TOP, LEFT, RIGHT }
    data class Insets(val left: Int = 0, val top: Int = 0,
                      val right: Int = 0, val bottom: Int = 0)
    data class Viewport(val width: Int, val height: Int, val dpi: Int,
                        val insets: Insets, val controlHeight: Int,
                        val sideWidth: Int, val mainInset: Int = 0) {
        init {
            require(width in 2..32768 && height in 2..32768 && dpi in 1..2000)
            require(listOf(insets.left,insets.top,insets.right,insets.bottom).all { it in 0..32768 })
            require(usableWidth > 1 && usableHeight > 1)
            require(controlHeight in 1 until usableHeight && sideWidth in 1 until usableWidth)
            require(mainInset in 0..minOf(usableWidth,usableHeight)/2)
        }
        val usableWidth get() = width-insets.left-insets.right
        val usableHeight get() = height-insets.top-insets.bottom
        val density get() = dpi/160f
    }
    data class Card(val slot: Int, val box: Box)
    data class TopActions(val collapse: Box, val main: Box, val add: Box)

    class Layout internal constructor(val main: Box, val rail: Box,
        cards: List<Card>, val topActions: TopActions?, val displayEpoch: Long) {
        val cards: List<Card> = Collections.unmodifiableList(ArrayList(cards))
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
    private fun fitted(region: Box, width: Int, height: Int): Box {
        val scale=minOf(region.w/width,region.h/height)
        val w=(width*scale).roundToInt(); val h=(height*scale).roundToInt()
        require(w>0 && h>0)
        return rect(region.x.toInt()+(region.w.toInt()-w)/2,
            region.y.toInt()+(region.h.toInt()-h)/2,w,h)
    }
    private fun region(v: Viewport, rail: Rail, depth: Int): Box {
        val side=if(rail==Rail.TOP) 0 else depth
        return rect(v.insets.left+v.mainInset+if(rail==Rail.LEFT) side else 0,
            v.insets.top+v.controlHeight+v.mainInset+if(rail==Rail.TOP) depth else 0,
            v.usableWidth-side-2*v.mainInset,
            v.usableHeight-v.controlHeight-(if(rail==Rail.TOP) depth else 0)-2*v.mainInset)
    }

    // Same physical display predicates as frozen StageDisplayPolicy. No ROM/model guess.
    private fun wideLandscape(v: Viewport) = v.width>v.height && v.height>=1300 &&
        v.width.toFloat()/v.height>=1.45f && v.width.toLong()<2L*v.height
    private fun nearSquare(v: Viewport) = minOf(v.width,v.height)>=1800 &&
        maxOf(v.width,v.height).toFloat()/minOf(v.width,v.height)<=1.25f
    private fun requireSupported(v: Viewport,rail: Rail) {
        require(rail==Rail.TOP || v.height>=v.width || wideLandscape(v)) {
            "Rotated landscape side rail needs verified legacy projection"
        }
    }
    /** Hidden rail refit is geometry only; does not detach/close any existing tasks. */
    fun focusedMain(v: Viewport, rail: Rail): Box {
        requireSupported(v,rail)
        return fitted(region(v,rail,1),v.width,v.height)
    }

    /**
     * Input slot indices are the active historical slots (0..3). The visual row
     * compacts, but original indices survive. Resolved dimensions are supplied by
     * the caller from the actual display/controller; no brand or ROM guess.
     */
    fun calculate(v: Viewport, rail: Rail, activeSlots: List<Int>,
                  mainPriority: Boolean, displayEpoch: Long): Layout {
        require(displayEpoch>=0 && activeSlots.size<=4 &&
            activeSlots.all { it in 0..3 } && activeSlots.distinct().size==activeSlots.size)
        requireSupported(v,rail)
        val slots=activeSlots.sorted()
        val count=slots.size
        val d=v.density; val x=v.insets.left; val y=v.insets.top+v.controlHeight
        val w=v.usableWidth; val h=v.usableHeight-v.controlHeight
        fun dp(n: Int)=(n*d).toInt()
        val cards=mutableListOf<Card>()
        val railBox: Box
        val actions: TopActions?
        val depth: Int
        if(rail==Rail.TOP) {
            val gutter=dp(8)
            val cap=when(count){1->.38f;2->.34f;3->.30f;else->1f}
            val cardWidth=if(count==0) 0 else minOf((w-(count+1)*8*d)/count,w*cap).toInt()
            // The former 720:1120 canvas is retained ONLY as a card-design ratio 9:14.
            val desired=if(count==0) (56*d).roundToInt()
                else (cardWidth*1120f/720f+(if(mainPriority)56 else 64)*d).roundToInt()
            val minMain=((if(v.width>v.height)160 else 220)*d).roundToInt()
            val limit=minOf(w-1,h-minMain)
            // Exact historical TOP-only caps: unfolded foldable 24%, wide landscape tablet 32%.
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
            val controls=listOf(actions.collapse,actions.main,actions.add)
            require(controls.all { fits(it,railBox) && cards.none { c->intersects(it,c.box) } })
            require(controls.indices.all { i->(i+1 until controls.size).none { intersects(controls[i],controls[it]) } })
        } else {
            depth=v.sideWidth
            railBox=rect(if(rail==Rail.LEFT)x else x+w-depth,y,depth,h)
            actions=null
            if(count>0) {
                val gap=d.toInt().coerceAtLeast(1)
                val maxWidth=depth-gap
                // Preserve legacy upright side card aspect using usable viewport,
                // not a fabricated task buffer. Actual content uses Pose.fit.
                val height=minOf((maxWidth.toDouble()*v.usableHeight/w).toInt(),
                    (h-gap*(count-1))/count)
                val width=minOf(maxWidth,(height.toDouble()*w/v.usableHeight).toInt())
                val pad=(h-height*count-gap*(count-1))/2
                for((i,slot) in slots.withIndex())
                    cards.add(Card(slot,rect(railBox.x.toInt(),y+pad+i*(height+gap),width,height)))
            }
        }
        val main=fitted(region(v,rail,depth),v.width,v.height)
        require(cards.all { fits(it.box,railBox) && !intersects(it.box,main) })
        require(cards.indices.all { i->(i+1 until cards.size).none { intersects(cards[i].box,cards[it].box) } })
        return Layout(main,railBox,cards,actions,displayEpoch)
    }
}
