package io.github.x647368maker.atomicworkbench.config

/** Guided tests describe evidence we can collect without continuous system tracing. */
internal object StageIssueTestCatalog {
    data class Case(val id: String, val name: String, val instructions: String, val boundary: String)

    val cases = listOf(
        Case("windows", "四小窗与交换",
            "1. 开始后打开四小窗；2. 切换主窗与小窗；3. 如果窗口消失或没响应，返回标记并写明窗口位置。",
            "记录小窗数量和操作前后状态；不能自动判断哪个像素遮挡或点击失败。"),
        Case("foldable", "折叠屏与平板布局",
            "1. 选择顶部/左/右布局；2. 折叠、展开或旋转屏幕；3. 异常时标记并另存截图。",
            "记录物理显示分辨率和方向；不能自动理解图片或判断原生平行视窗重叠。"),
        Case("gaming", "游戏与触控",
            "1. 在游戏中打开小窗；2. 测试游戏滑动与小窗点击；3. 卡顿/误触后标记并描述操作。",
            "记录手动时刻和系统状态；不能读取其他游戏的帧率或自动确定误触原因。"),
        Case("unlock", "息屏、锁屏与解锁",
            "1. 开始测试后锁屏；2. 解锁后查看四窗是否恢复；3. 返回页面标记并注明黑屏/延迟。",
            "记录开始/结束以及标记时刻；不能直接证明深度休眠和每次解锁耗时。"),
        Case("power", "电池与发热",
            "1. 记下开始电量/温度；2. 正常使用后返回；3. 结束测试并对比关闭工作台后的相同时长记录。",
            "记录整机电量/温度快照；不能归因模块独占耗电或自动计算整段平均功率。"),
        Case("crash", "卡顿、闪退与运行中断",
            "1. 开始测试并复现卡顿/闪退；2. 重开 App 后结束；3. 说明复现操作并附系统崩溃截图。",
            "可发现部分进程重启迹象、有限运行计数；不能抓取完整崩溃堆栈和精确帧率。"),
        Case("status", "通知栏、手势与快捷栏",
            "1. 测试下拉/上滑通知栏；2. 使用返回、小白条和快捷栏；3. 发生卡住时标记并附截图。",
            "记录用户反馈和前后状态；不能自动判定手势是否被系统拦截。"),
        Case("other", "其他问题",
            "1. 开始测试；2. 复现问题后回来标记；3. 在结束前填写复现步骤和预期结果。",
            "只记录手动快照和时间，不会自动寻找任何未知故障根因。")
    )

    fun find(id: String?): Case = cases.firstOrNull { it.id == id } ?: cases.last()
    fun guide(id: String?): String = find(id).let {
        "操作步骤：\n${it.instructions}\n\n能记录：\n${it.boundary}"
    }
}
