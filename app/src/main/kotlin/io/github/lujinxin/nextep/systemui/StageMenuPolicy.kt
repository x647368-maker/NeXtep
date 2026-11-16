package io.github.x647368maker.atomicworkbench.systemui

internal object StageMenuPolicy {
    fun isMain(labels:List<String>)=labels.contains("顶部横排") && labels.contains("添加小窗") && labels.contains("退出台前调度")
    fun group(label:String):Int {
        val s=label.removePrefix("✓ ")
        return when {
            s in listOf("顶部横排","左侧排列","右侧排列","完整显示布局","主窗口优先布局")->0
            s in listOf("切换全屏（保留工作区）","添加常用应用到快捷栏","恢复被隐藏的常用应用","退出台前调度")->3
            s in listOf("主窗口与小窗交换","窗口顺序调整","隐藏/显示侧边栏","添加小窗","将当前微信任务移入小窗","替换主应用","关闭主窗（移回后台）")->1
            s in listOf("常用应用管理","快捷栏样式","显示与动画")->2
            s.contains("侧栏") || s.contains("常用应用") || s=="影音布局与自动模式"->2
            else->3
        }
    }
    val groups=listOf("窗口布局","窗口管理","快捷栏与显示","其他功能")
    private val layoutChoices=setOf("顶部横排","左侧排列","右侧排列","主窗口优先布局","完整显示布局")
    fun isLayoutChoice(label:String)=label.removePrefix("✓ ") in layoutChoices
    fun isSelected(label:String,selected:Set<String>) =
        isLayoutChoice(label) && (label.startsWith("✓ ") || label.removePrefix("✓ ") in selected)
    // Distinguish actions without changing their text, callback or group.
    // Numbers 0..3 remain the category glyph identifiers.
    fun actionGlyph(label:String):Int {
        val s=label.removePrefix("✓ ")
        return when {
            s=="顶部横排"->10
            s=="左侧排列"->11
            s=="右侧排列"->12
            s=="主窗口优先布局"->13
            s=="完整显示布局"->14
            s=="切换全屏（保留工作区）"->15
            s=="添加小窗" || s=="添加常用应用到快捷栏"->16
            s=="替换主应用" || s=="将当前微信任务移入小窗"->17
            s=="关闭主窗（移回后台）" || s=="退出台前调度"->18
            s=="恢复被隐藏的常用应用"->19
            s.contains("固定侧栏")->20
            s.contains("自动收起时间")->21
            s.contains("智能侧栏")->22
            s.contains("常用应用")->23
            s=="影音布局与自动模式"->24
            s=="主窗口与小窗交换"->17
            s=="窗口顺序调整"->25
            s=="隐藏/显示侧边栏"->26
            else->group(s)
        }
    }
}

internal object StageMenuPlacement {
    data class Box(val l:Int,val t:Int,val r:Int,val b:Int) {
        val w get()=r-l;val h get()=b-t
        fun overlap(o:Box):Long=(minOf(r,o.r)-maxOf(l,o.l)).coerceAtLeast(0).toLong()*(minOf(b,o.b)-maxOf(t,o.t)).coerceAtLeast(0)
    }
    // Narrow portrait uses the same persistent two-pane design whenever
    // both columns and four 48dp primary rows actually fit. Never displace
    // a real app window to manufacture space; retain accessible fallback.
    fun dualGlass(safe:Box,anchor:Box,w:Int,h:Int,minW:Int,minH:Int,avoid:List<Box>,gap:Int):Box? {
        if(minW<=0 || minH<=0 || w<minW || h<minH || safe.w<minW || safe.h<minH)return null
        return compact(safe,anchor,w,h,minW,minH,avoid,gap).takeIf {
            it.w>=minW && it.h>=minH && avoid.none { a->it.overlap(a)>0L }
        }
    }
    fun place(safe:Box,anchor:Box,w:Int,h:Int,avoid:List<Box>,gap:Int):Box {
        val homeX=anchor.l;val homeY=anchor.b+gap
        val xs=mutableListOf(homeX,anchor.r-w,safe.l,safe.r-w)
        val ys=mutableListOf(homeY,anchor.t-h-gap,safe.t,safe.b-h)
        for(a in avoid){xs.add(a.r+gap);xs.add(a.l-w-gap);ys.add(a.b+gap);ys.add(a.t-h-gap)}
        fun box(x:Int,y:Int):Box {val l=x.coerceIn(safe.l,maxOf(safe.l,safe.r-w));val t=y.coerceIn(safe.t,maxOf(safe.t,safe.b-h));return Box(l,t,l+w,t+h)}
        return xs.flatMap{x->ys.map{y->box(x,y)}}.distinct().minWithOrNull(
            compareBy<Box>{p->avoid.sumOf{p.overlap(it)}}.thenBy{p->kotlin.math.abs(p.l-homeX).toLong()+kotlin.math.abs(p.t-homeY)}
        ) ?: box(homeX,homeY)
    }

    /** Select a scrollable viewport in a true empty rectangle before considering
     * overlap. This is a menu-open-only calculation; covered tasks stay intact. */
    fun compact(safe:Box,anchor:Box,preferredW:Int,preferredH:Int,
                minimumW:Int,minimumH:Int,avoid:List<Box>,gap:Int):Box {
        val w=preferredW.coerceIn(1,safe.w.coerceAtLeast(1))
        val h=preferredH.coerceIn(1,safe.h.coerceAtLeast(1))
        val minW=minimumW.coerceIn(1,w)
        val minH=minimumH.coerceIn(1,h)
        val homeX=anchor.l
        val homeY=anchor.b+gap
        fun distance(b:Box)=kotlin.math.abs(b.l-homeX).toLong()+kotlin.math.abs(b.t-homeY)
        fun search(clearance:Int):Box? {
            val blocked=avoid.mapNotNull { a->
                Box(maxOf(safe.l,a.l-clearance),maxOf(safe.t,a.t-clearance),
                    minOf(safe.r,a.r+clearance),minOf(safe.b,a.b+clearance))
                    .takeIf { it.w>0 && it.h>0 }
            }
            val xs=(listOf(safe.l,safe.r)+blocked.flatMap{listOf(it.l,it.r)}).distinct().sorted()
            var best:Box?=null
            fun offer(l:Int,r:Int,top:Int,bottom:Int){
                if(r-l<minW || bottom-top<minH)return
                val cw=minOf(w,r-l);val ch=minOf(h,bottom-top)
                val x=homeX.coerceIn(l,r-cw);val y=homeY.coerceIn(top,bottom-ch)
                val candidate=Box(x,y,x+cw,y+ch)
                val prior=best
                val area=cw.toLong()*ch
                val priorArea=prior?.let{it.w.toLong()*it.h}?:0L
                if(prior==null || area>priorArea ||
                    (area==priorArea && (cw>prior.w || (cw==prior.w && distance(candidate)<distance(prior)))))
                    best=candidate
            }
            // For each bounded horizontal strip, merge vertical obstacle intervals.
            // The free intervals are complete for rectangles whose edges touch
            // bounds/obstacles; no per-pixel scan, bitmap or background timer.
            for(i in 0 until xs.lastIndex)for(j in i+1..xs.lastIndex){
                val l=xs[i];val r=xs[j]
                if(r-l<minW)continue
                var top=safe.t
                for(a in blocked.filter{it.l<r && it.r>l}.sortedBy{it.t}){
                    if(a.t>top)offer(l,r,top,a.t)
                    top=maxOf(top,a.b)
                }
                if(top<safe.b)offer(l,r,top,safe.b)
            }
            return best
        }
        // Keep the existing comfortable size if possible. If there is only a
        // smaller true gap, use a scrolling viewport instead of covering a card.
        val clear=search(gap.coerceAtLeast(0))
        val tight=search(0)
        if(clear!=null && (clear.w==w && clear.h==h || tight==null ||
                clear.w.toLong()*clear.h>=tight.w.toLong()*tight.h))return clear
        if(tight!=null)return tight
        // A fully occupied viewport may have no space for even one accessible
        // row. Keep actions reachable, minimize the footprint, and favor
        // preserving small cards (relative protected-area loss).
        val xs=mutableListOf(homeX,anchor.r-w,safe.l,safe.r-w)
        val ys=mutableListOf(homeY,anchor.t-minH-gap,safe.t,safe.b-minH)
        for(a in avoid){xs.add(a.r+gap);xs.add(a.l-w-gap);ys.add(a.b+gap);ys.add(a.t-minH-gap)}
        fun box(x:Int,y:Int):Box {
            val l=x.coerceIn(safe.l,maxOf(safe.l,safe.r-w))
            val t=y.coerceIn(safe.t,maxOf(safe.t,safe.b-minH))
            return Box(l,t,l+w,t+minH)
        }
        return xs.flatMap{x->ys.map{y->box(x,y)}}.distinct().minWithOrNull(
            compareBy<Box>{b->avoid.sumOf{a->b.overlap(a).toDouble()/(a.w.toLong()*a.h).coerceAtLeast(1L)}}
                .thenBy{b->avoid.sumOf{b.overlap(it)}}.thenBy(::distance)
        )?:box(homeX,homeY)
    }
}
