package io.github.x647368maker.atomicworkbench.config

/** Shared native home menu contract: no search state, no duplicate destructive row. */
internal object StageHomeLayout {
    const val ROW_DP = 54
    const val HEADER_DP = 72
    const val GROUP_GAP_DP = 10
    data class Item(val name: String, val page: String, val icon: String)
    data class Section(val title: String, val items: List<Item>)
    val sections = listOf(
        Section("主要功能", listOf(Item("影音与常用应用","core","apps"), Item("窗口与侧栏","windows","windows"),
            Item("工作组","groups","groups"), Item("手势","gestures","gesture"))),
        Section("管理", listOf(Item("控制中心","tile","tile"), Item("建议与反馈","feedback","help"),
            Item("关于台前调度","about","info"))),
        Section("高级", listOf(Item("运行与诊断","diagnostics","diagnostics"), Item("使用与退出","help","help")))
    )
}
