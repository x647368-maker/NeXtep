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
            Item("工作组","groups","groups"),Item("启动手势","gestures","gesture"),
            Item("快捷开关","tile","tile"))),
        Section("故障诊断",listOf(Item("一键综合诊断","issue_all","diagnostics"),
            Item("专项测试与报告","issue_specialties","diagnostics"))),
        Section("支持与服务",listOf(Item("软件更新","update","update"),
            Item("建议与反馈","feedback","feedback"),Item("打赏作者","donate","heart"),
            Item("关于与使用帮助","about","about")))
    )
    // Actual available bounds, not manufacturer/model names. Portrait tablets
    // retain single-column navigation; wide inner foldables and landscape
    // tablets have room for a 208dp navigation rail plus a readable detail pane.
    fun useTwoPane(width: Int, height: Int, density: Float): Boolean =
        width > 0 && height > 0 && density > 0 &&
            width / density >= 600f && width.toDouble() / height >= .82

    fun navigationWidth(width: Int, density: Float): Int {
        require(width > 0 && density > 0)
        return (width * .32f).toInt().coerceIn((208 * density).toInt(), (300 * density).toInt())
    }

    fun contentWidth(availablePixels:Int,density:Float):Int {
        require(availablePixels>0 && density>0)
        return minOf(availablePixels,(MAX_COLUMN_DP*density).toInt().coerceAtLeast(1))
    }
}
