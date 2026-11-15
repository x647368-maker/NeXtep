package io.github.x647368maker.atomicworkbench.config

/** Only labels and icon roles from the confirmed Nov 15 diagnostic pages.
 * Inclusion is scope, never a successful test. Runtime evidence lives in Session. */
internal object StageIssuePresentation {
    data class Row(val title:String,val description:String,val icon:String,val tint:Int)
    private val blue=0xFF1769EF.toInt()
    private val violet=0xFF7546E9.toInt()
    private val green=0xFF08AC72.toInt()
    private val orange=0xFFF08A25.toInt()
    private fun row(title:String,description:String,icon:String,tint:Int=blue)=Row(title,description,icon,tint)

    private val modules=mapOf(
        "all" to row("全面综合诊断","自动记录有效诊断事件，帮助定位问题","diagnostics"),
        "performance" to row("性能与流畅度","卡顿、动画、闪烁、耗电、发热","issue_bolt"),
        "layout" to row("窗口与布局体验","四小窗、窗口交换、智能隐藏、空间利用","issue_swap"),
        "content" to row("应用与内容状态","视频播放、游戏触控、后台状态、内容保留","apps"),
        "compatibility" to row("系统与兼容性","折叠屏与平板、锁屏、系统手势、Root与模块","issue_shield"),
        "services" to row("功能与服务","工作组、快捷栏、在线更新、收款码","issue_cube")
    )
    fun module(id:String):Row=modules[id]?:row(StageIssueTestCatalog.find(id).name,"按需记录真实事件","diagnostics")
    fun rows(id:String):List<Row> = when(id) {
        "all" -> listOf(
            row("窗口交换与动画","","issue_swap",violet),
            row("布局与触控","","windows"),
            row("应用状态与稳定性","","apps",green),
            row("电池与发热","","issue_battery",orange),
            row("系统与模块兼容性","","issue_cube",violet))
        "performance" -> listOf(
            row("卡顿","窗口切换、操作延迟与掉帧线索","issue_speed"),
            row("动画","窗口动画流畅性与一致性","issue_layers",violet),
            row("闪烁","画面闪烁与抖动需复现","issue_bolt"),
            row("耗电","记录整机电量变化","issue_battery",green),
            row("发热","记录温度变化","issue_temperature",orange))
        "layout" -> listOf(
            row("四小窗","小窗开启、显示比例、排列与响应","apps"),
            row("窗口交换","主窗与小窗互换的稳定性","issue_swap",violet),
            row("智能隐藏","侧边栏隐藏、恢复与触控范围","windows",green),
            row("空间利用","主窗口占比、留白与内容显示","windows",violet))
        "content" -> listOf(
            row("视频","播放、暂停、切换与横竖屏","issue_media"),
            row("游戏","游戏窗口、触控响应与稳定性","apps",violet),
            row("触控","点击、滑动、拖拽与窗口操作","gesture"),
            row("后台状态","后台状态、资源与重启线索","issue_layers",orange),
            row("内容保留","暂停返回、页面刷新、播放进度需复现","update",green))
        "compatibility" -> listOf(
            row("折叠屏","展开与折叠适配、窗口布局与比例","windows",violet),
            row("平板","横竖屏、按钮与状态栏适配","windows"),
            row("锁屏","息屏、锁屏、解锁及后台恢复","issue_lock",green),
            row("系统手势","通知栏、控制中心、返回与底部手势","gesture",orange),
            row("Root模块","Root、Zygisk、LSPosed / Vector兼容性","issue_layers",violet))
        "services" -> listOf(
            row("工作组","保存、恢复与切换","groups"),
            row("快捷栏","显示、切换与编辑","apps",violet),
            row("在线更新","检查、下载、验证与安装需实际操作","update",green),
            row("收款码","显示、保存及扫码识别需实际验证","issue_document",orange))
        else -> listOf(module(id))
    }
}
