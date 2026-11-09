package io.github.x647368maker.atomicworkbench.systemui

internal object StageMenuPolicy {
    fun isMain(labels:List<String>)=labels.contains("顶部横排") && labels.contains("添加小窗") && labels.contains("退出台前调度")
    fun group(label:String):Int {
        val s=label.removePrefix("✓ ")
        return when {
            s in listOf("顶部横排","左侧排列","右侧排列","完整显示布局","主窗口优先布局")->0
            s in listOf("添加小窗","将当前微信任务移入小窗","替换主应用","关闭主窗（移回后台）")->1
            s.contains("侧栏") || s.contains("常用应用快捷栏") || s=="影音布局与自动模式"->2
            else->3
        }
    }
    val groups=listOf("窗口布局","窗口管理","快捷栏与显示","其他功能")
}

internal object StageMenuPlacement {
    data class Box(val l:Int,val t:Int,val r:Int,val b:Int) {
        val w get()=r-l;val h get()=b-t
        fun overlap(o:Box):Long=(minOf(r,o.r)-maxOf(l,o.l)).coerceAtLeast(0).toLong()*(minOf(b,o.b)-maxOf(t,o.t)).coerceAtLeast(0)
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
}
