package io.github.x647368maker.atomicworkbench.config

/** Native settings information architecture, shared by rendering and navigation tests. */
internal object StageHomeLayout {
    const val ROW_DP=56
    const val HEADER_DP=64
    const val GROUP_GAP_DP=8
    const val SECTION_TOP_DP=10
    const val MAX_COLUMN_DP=680
    data class Item(val name:String,val page:String,val icon:String)
    data class Section(val title:String,val items:List<Item>)
    val sections=listOf(
        Section("工作台",listOf(Item("窗口布局","windows","windows"),Item("影音与快捷栏","core","apps"),
            Item("工作组","groups","groups"),Item("启动手势","gestures","gesture"))),
        Section("系统",listOf(Item("快捷开关","tile","tile"),Item("运行诊断","diagnostics","diagnostics"),
            Item("在线更新","update","info"),Item("更新日志","changelog","info"),Item("使用与退出","help","help"))),
        Section("支持",listOf(Item("建议与反馈","feedback","feedback"),Item("打赏作者","donate","heart"),
            Item("关于","about","info")))
    )
    fun contentWidth(availablePixels:Int,density:Float):Int {
        require(availablePixels>0 && density>0)
        return minOf(availablePixels,(MAX_COLUMN_DP*density).toInt().coerceAtLeast(1))
    }
}
