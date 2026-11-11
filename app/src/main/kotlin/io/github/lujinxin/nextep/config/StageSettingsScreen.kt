package io.github.x647368maker.atomicworkbench.config

import android.app.StatusBarManager
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.x647368maker.atomicworkbench.R
import io.github.x647368maker.atomicworkbench.trigger.*
import org.json.JSONArray
import org.json.JSONObject

/** Ordinary native settings: grouped rows and separate pages, no glass or overlays. */
internal class StageSettingsScreen(private val activity: AppCompatActivity, private val repository: SettingsRepository) {
    val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val backgroundColor = activity.resources.getColor(R.color.stage_background, activity.theme)
    private val cardColor = Color.parseColor(if (dark) "#202124" else "#FFFFFF")
    private val ink = Color.parseColor(if (dark) "#F1F2F4" else "#121315")
    private val secondary = Color.parseColor(if (dark) "#A0A2A6" else "#818387")
    private val dividerColor = Color.parseColor(if (dark) "#303136" else "#F2F2F4")
    private val blue = Color.rgb(29, 125, 239)
    private val handler = Handler(Looper.getMainLooper())
    private var cinemaMode = "AUTO"
    private var quickFixed = false
    private var quickExpanded = false
    private var adaptive = true
    private var mainPriority = true
    private var active = false
    private var connected = false
    private var runtimeIdentity = "尚未核实注入代码"
    private var busy = false
    private var groups = JSONArray()
    private var windows = JSONArray()
    private var activeId: String? = null
    private var status = "正在连接系统组件"
    private val versionName by lazy { runCatching { UpdateCoordinator.installed(activity).versionName }.getOrNull().orEmpty() }
    private var body: LinearLayout? = null
    private var stateText: TextView? = null
    private var master: Switch? = null
    private var settingSwitch = false
    var page = "home"
        private set
    val view = FrameLayout(activity).apply { setBackgroundColor(backgroundColor); isFocusableInTouchMode = true }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float = 16f, color: Int = ink) = TextView(activity).apply {
        text = s; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun surface() = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(if (page == "home") 20 else 24).toFloat() }
    private fun toast(s: String) { if (!activity.isDestroyed) Toast.makeText(activity, s, Toast.LENGTH_LONG).show() }
    private fun hint(s: String) = label(s, 12f, secondary).apply {
        setLineSpacing(dp(3).toFloat(), 1f); setPadding(dp(16), dp(12), dp(16), dp(4))
    }
    private fun group(parent: LinearLayout): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; background = surface(); clipToOutline = true
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (page == "home") StageHomeLayout.GROUP_GAP_DP else 18) })
    }
    private fun row(parent: LinearLayout, title: String, detail: String? = null, icon: String? = null,
        color: Int = blue, end: View? = null, action: (() -> Unit)? = null): View {
        if (parent.childCount > 0) parent.addView(View(activity).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)).apply { marginStart = dp(if (icon != null) 56 else 18); marginEnd = dp(18) })
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (page == "home") StageHomeLayout.ROW_DP else 62); setPadding(dp(18), dp(if (page == "home") 10 else 12), dp(18), dp(if (page == "home") 10 else 12))
            if (icon != null) addView(if (icon == "stage") ImageView(activity).apply {
                setImageResource(R.drawable.ic_stage_manager_apple); scaleType = ImageView.ScaleType.FIT_CENTER
            } else StageSettingsIcon(activity, icon, color), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
            val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            words.addView(label(title, 16f, if (icon == "exit") Color.rgb(220, 65, 60) else ink))
            if (!detail.isNullOrBlank()) words.addView(label(detail, 12f, secondary).apply {
                setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(5), 0, 0)
            })
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            if (end != null) addView(end, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            else if (action != null) addView(label("›", 26f, Color.rgb(181, 183, 187)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(18), dp(32)))
            if (action != null) {
                isClickable = true; isFocusable = true
                val a = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                foreground = a.getDrawable(0); a.recycle()
                setOnClickListener { action() }
            }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun toggle(checked: Boolean, action: (Boolean) -> Unit) = Switch(activity).apply {
        isChecked = checked; showText = false; minWidth = dp(48)
        thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(blue, if (dark) Color.rgb(75, 77, 81) else Color.rgb(204, 206, 210)))
        setOnCheckedChangeListener { _, value -> if (!settingSwitch) action(value) }
    }
    fun back(): Boolean { if (page == "home") return false; open(if (page == "changelog") "about" else "home"); return true }
    fun open(target: String) {
        page = target; body = null; stateText = null; master = null
        view.removeAllViews()
        view.requestFocus()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(if (target == "home") 12 else 8), dp(16), dp(if (target == "home") 16 else 28))
        }
        val columnHost = FrameLayout(activity).apply {
            addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                val available=r-l
                if (available>0) {
                    val targetWidth=StageHomeLayout.contentWidth(available, activity.resources.displayMetrics.density)
                    if (column.layoutParams.width!=targetWidth) column.layoutParams=(column.layoutParams as FrameLayout.LayoutParams).apply { width=targetWidth }
                }
            }
        }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false; isFillViewport = true
            addView(columnHost, ViewGroup.LayoutParams(-1, -2))
        }
        view.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        if (target == "home") {
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0); minimumHeight = dp(StageHomeLayout.HEADER_DP)
            }
            val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            words.addView(label("台前调度", 24f).apply { typeface = Typeface.DEFAULT_BOLD })
            stateText = label(homeState(), 13f, secondary).apply { setPadding(0, dp(6), 0, 0) }
            words.addView(stateText)
            header.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            master = toggle(active) { setStage(it) }.apply {
                minimumHeight = dp(48); contentDescription = "台前调度开关"
            }
            header.addView(master, LinearLayout.LayoutParams(dp(52), dp(48)))
            column.addView(header)
            body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(body); renderHome()
        } else {
            val titles = mapOf("core" to "影音与快捷栏", "windows" to "窗口布局", "groups" to "工作组", "gestures" to "启动手势", "tile" to "快捷开关", "help" to "使用与退出", "diagnostics" to "运行诊断", "about" to "关于", "feedback" to "建议与反馈", "changelog" to "更新日志")
            val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(label("‹", 34f).apply {
                gravity = Gravity.CENTER; contentDescription = "返回"; setOnClickListener { open(if (target == "changelog") "about" else "home") }
            }, LinearLayout.LayoutParams(dp(40), dp(52)))
            header.addView(label(titles[target] ?: "设置", 24f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(header)
            when (target) {
                "core" -> corePage(column)
                "windows" -> windows(column)
                "groups" -> groupPage(column)
                "gestures" -> gestures(column)
                "tile" -> tile(column)
                "help" -> help(column)
                "diagnostics" -> diagnostics(column)
                "about" -> about(column)
                "feedback" -> feedback(column)
                "changelog" -> column.addView(StageChangelogPage.create(activity), LinearLayout.LayoutParams(-1, -2))
            }
        }
    }
    private fun homeState(): String = "${if (connected) { if (active) "已开启" else "已关闭" } else "待连接"} · $versionName"
    private fun renderHome() {
        val root = body ?: return; root.removeAllViews()
        if (UpdateCoordinator.available(activity) != null)
            row(group(root), "发现台前调度新版本", icon = "info") { open("about") }
        row(group(root), "故障测试与报告", "手动开启 · 标记问题 · 自行导出，平时不监控", "diagnostics") {
            activity.startActivity(Intent(activity, StageIssueDiagnosticActivity::class.java))
        }
        for (section in StageHomeLayout.sections) {
            root.addView(label(section.title, 12f, secondary).apply {
                setPadding(dp(16), dp(StageHomeLayout.SECTION_TOP_DP), dp(16), 0)
            })
            val card = group(root)
            for (item in section.items) row(card, item.name, icon = item.icon, color = blue) {
                open(item.page)
                if (item.page in listOf("core", "windows", "groups")) refresh()
            }
        }
        root.addView(label("ZMW小武 × GPT\n基于 NeXtep 二次开发", 11f, secondary).apply {
            gravity=Gravity.CENTER; setLineSpacing(dp(3).toFloat(),1f); setPadding(0,dp(14),0,dp(6))
        })
    }
    private fun windows(root: LinearLayout) {
        val manage = group(root)
        row(manage, "添加小窗", "${windows.length()}/4 · 按需添加，不显示空框", "apps") { chooseApp(window = true) }
        for (i in 0 until windows.length()) {
            val item = windows.getJSONObject(i); val slot = item.getInt("slot"); val task = item.getInt("task")
            val label = runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(item.getString("package"), 0)).toString() }.getOrDefault(item.getString("package"))
            row(manage, label, "替换或关闭这个小窗", "windows") {
                MaterialAlertDialogBuilder(activity).setTitle(label).setItems(arrayOf("替换应用", "关闭小窗")) { _, which ->
                    if (which == 0) chooseApp(window = true, slot = slot, expectedTask = task)
                    else command("close_window", slot = slot, expectedTask = task)
                }.setNegativeButton("取消", null).show()
            }
        }
        val layout = group(root)
        row(layout, "主窗口显示策略", if (mainPriority) "主窗口优先（推荐）" else "完整显示 · 原 macOS 风格", "windows") {
            MaterialAlertDialogBuilder(activity).setTitle("主窗口显示策略").setItems(arrayOf("主窗口优先：安全空间最大化，保持完整画面", "完整显示：保留原主窗与卡片布局")) { _, which -> command(if(which == 0) "main_priority" else "main_complete") }.setNegativeButton("取消", null).show()
        }
        row(layout, "自动适配", "按实际屏幕状态与小窗数量计算；每个屏幕状态分别保存排列和显示策略", "windows", end = toggle(adaptive) { command(if (it) "adaptive_on" else "adaptive_off") })
        row(layout, "顶部横排", "小窗横向排列在主窗上方", "windows") { command("layout_top") }
        row(layout, "侧边纵排", "小窗纵向排列在主窗旁", "windows") { command("layout_side") }
        val side = group(root)
        row(side, "侧栏放左边", icon = "windows") { command("left") }
        row(side, "侧栏放右边", icon = "windows") { command("right") }
        val visibility = group(root)
        row(visibility, "收起侧栏", "扩大当前主应用", "windows") { command("collapse") }
        row(visibility, "展开侧栏", "显示已有侧窗", "windows") { command("expand") }
        root.addView(hint("主窗“⋯”可切换全屏、切换布局、替换和关闭主窗。\n内外屏与横竖屏分别保存排列、侧栏方向和显示策略。主窗优先使用原始画面可安全显示的最大尺寸；任务比例不合适时仍有留白。顶部小窗保持原尺寸；活动显示器保留原分辨率和 DPI；点击小窗切换为主窗，在小窗菜单选择“在小窗中操作”后可尝试直接触摸和滑动。应用与系统支持情况可能不同。\n通过加号添加小窗，通过小窗“⋯”替换或关闭。边缘把手只收起或展开侧栏，不会退出台前调度。"))
    }
    private fun corePage(root: LinearLayout) {
        val video = group(root)
        row(video, "影音布局", when(cinemaMode) { "MANUAL", "LANDSCAPE" -> "手动横屏影音"; "PORTRAIT" -> "手动竖屏影音"; "OFF" -> "已关闭"; else -> "自动：匹配可靠任务方向和独立媒体身份" }, "windows") {
            MaterialAlertDialogBuilder(activity).setTitle("影音布局").setItems(arrayOf("自动", "横屏影音", "竖屏影音", "关闭")) { _, which ->
                command(listOf("cinema_auto", "cinema_landscape", "cinema_portrait", "cinema_off")[which])
            }.setNegativeButton("取消", null).show()
        }
        row(video, "选择影音小窗", "先添加视频小窗，再选择横屏或竖屏布局", "windows") { command("cinema_enter") }
        row(video, "退出影音布局", "恢复之前的顶部 / 侧边排列", "windows") { command("cinema_leave") }
        val quick = group(root)
        row(quick, "常用应用快捷栏", if (quickExpanded) "已展开" else "已收起", "apps") { command(if (quickExpanded) "quick_collapse" else "quick_expand") }
        row(quick, "快捷栏内容", if (quickFixed) "固定应用（最多六个）" else "最近常用五个", "apps") {
            MaterialAlertDialogBuilder(activity).setTitle("快捷栏内容").setItems(arrayOf("自动：最近常用五个", "固定：最多六个")) { _, which ->
                command(if (which == 0) "quick_auto" else "quick_fixed")
            }.setNegativeButton("取消", null).show()
        }
        row(quick, "关闭快捷栏", "保留固定应用和排序", "apps") { command("quick_off") }
        root.addView(hint("收起后点击工作区顶部 ▦ 按钮即可展开，触摸区域为 48dp。主窗 ⋯ 菜单也可展开。长按快捷栏或工作区应用列表的图标，可固定、调整顺序、添加到小窗或选择要替换的小窗。点击图标优先复用真实任务。"))
        row(group(root), "按小窗数量智能布局", "关闭后回退到 0.8.1 的固定容量布局", "windows", end = toggle(adaptive) { command(if (it) "adaptive_on" else "adaptive_off") })
        row(group(root), "移动当前微信任务到小窗", "在工作区主窗打开小程序后使用", "windows") { command("wechat_move") }
        root.addView(hint("微信兼容按系统实际 Task ID 移动，不调用微信私有接口。共用任务会整体移动；系统提供独立任务时保留独立身份。无法保证小程序与微信分别运行。失败会尝试恢复原任务。\n\n自动影音要求同用户、唯一任务和唯一媒体会话，以及一致的任务方向信号。信号不足、暂停或缓冲时保持布局。可手动指定横屏或竖屏。为避免额外重建，布局仅改变显示几何，不强改播放器分辨率或 DPI，可能保留留白。应用自身黑边无法保证消除。"))
    }
    private fun gestures(root: LinearLayout) {
        val settings = repository.topBarSettings(); val card = group(root)
        row(card, "状态栏滑动", "从状态栏右侧滑动开关", "gesture", end = toggle(settings.statusBarGestureEnabled, repository::setStatusBarGestureEnabled))
        row(card, "四指手势", "捏合进入，张开退出", "gesture", end = toggle(settings.fourFingerGestureEnabled, repository::setFourFingerGestureEnabled))
        root.addView(hint("关闭手势后，仍可通过控制中心开关或本应用退出。"))
    }
    private fun tile(root: LinearLayout) {
        row(group(root), "添加快捷开关", "开启、退出台前调度", "tile") { addTile() }
        root.addView(hint("下拉控制中心，在编辑开关中添加“台前调度”。点亮为开启，再次点击即可退出。"))
    }
    private fun help(root: LinearLayout) {
        val card = group(root)
        row(card, "退出台前调度", "恢复普通应用显示", "exit") { setStage(false) }
        row(card, "添加控制中心开关", icon = "tile") { addTile() }
        row(group(root), "跨窗口内容拖放", "兼容应用 · 待真机验证", "apps") {
            MaterialAlertDialogBuilder(activity).setTitle("跨窗口内容拖放")
                .setMessage("长按应用支持的文字、图片或文件，拖到目标小窗悬停片刻，待其切换到主窗后，移入主窗再松手。\n\n源应用需要支持 Android 全局拖放；目标应用需要支持接收内容。此适配尚未经过真机验证，不保证所有 App 可用。")
                .setPositiveButton("知道了", null).show()
        }
        root.addView(hint("主窗把手：点击打开菜单，向左滑动关闭主窗。小窗沿侧栏外侧方向滑动关闭；顶部横排时向左滑动。\n\n退出：关闭控制中心“台前调度”开关，或点击上方退出。\n\n返回键、Home 键以及收起侧栏都不等于退出。\n\n退出会恢复普通任务显示，已保存工作组记录继续保留。"))
    }
    private var slotDiagnostics = "尚未取得槽位状态"
    private fun diagnostics(root: LinearLayout) {
        val card = group(root)
        row(card, "系统组件", runtimeIdentity + "\n工作区通信：" + if(queryPending) "查询中" else if(connected) "已连接" else "尚未连接（与注入状态分别判断）", "diagnostics") { refresh() }
        row(card, "退出当前工作区", "可用于退出仍在运行的旧版界面", "exit") { setStage(false) }
        row(card, "刷新连接状态", icon = "diagnostics") { refresh() }
        root.addView(hint(slotDiagnostics))
        root.addView(hint("安装包：$versionName\n\n模块需要支持现代 libxposed API 101/102 的框架。仅显示 LSP 名称不足以确认实际 API。作用域中的 SystemUI 与 system（系统进程）承担不同职责，Launcher 是独立适配项。\n\n更新 APK 不会立即替换已注入代码，安装后正常重启一次。无需为了使用台前调度关闭全部其他模块。无响应时原因待查，可提供本页截图及框架错误日志；不要分享包含私人内容的完整日志。"))
    }
    private fun about(root: LinearLayout) {
        val hero = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(34), 0, dp(18)) }
        hero.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_atomic_workbench) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        hero.addView(label("台前调度 · Atomic Workbench", 23f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(16), 0, dp(8)) })
        hero.addView(label(versionName, 13f, secondary)); root.addView(hero)
        val credits = group(root)
        row(credits, "ZMW小武 × GPT", "二次开发", "apps")
        row(credits, "需求策划、交互设计与真机测试", "ZMW小武")
        row(credits, "AI 辅助代码开发", "OpenAI GPT")
        row(credits, "原项目", "NeXtep") { UpdateSection.openBrowser(activity, "https://github.com/lujinxin/NeXtep") }
        row(credits, "原作者", "lujinxin（Jensen Lu）")
        row(credits, "二改仓库", "x647368-maker/NeXtep") { UpdateSection.openBrowser(activity, "https://github.com/x647368-maker/NeXtep") }
        row(credits, "开源许可证", "Apache License 2.0 · 保留原版权与第三方声明")
        val card = group(root)
        row(card, "窗口", "一个主窗口，按需显示 0–4 个小窗", "windows")
        row(card, "来源与许可", "基于 NeXtep · Apache-2.0", "info") {
            MaterialAlertDialogBuilder(activity).setTitle("来源与许可")
                .setMessage("原作者：lujinxin / JensenLu。保留 NeXtep 的 Apache-2.0 许可证与第三方声明。Android 任务引擎来自原作，本分支增加四侧窗、工作组和新的交互。\n\n交互参考 macOS Stage Manager，非 Apple 官方应用。")
                .setPositiveButton("知道了", null).show()
        }
        row(group(root), "更新日志", "离线查看各版本重大变化与验收状态", "info") { open("changelog") }
        row(group(root), "建议与反馈 · 酷安", "ZMW专用 · UID 43881308", "help") { open("feedback") }
        root.addView(UpdateSection.create(activity))
        root.addView(hint("真实任务迁移、点击交换、侧栏收起和工作组切换。新版本的实际安装、注入与交互效果仍需真机验收。"))
    }
    private fun feedback(root: LinearLayout) {
        val intro = group(root)
        row(intro, "台前调度 · 建议与反馈")
        intro.addView(label("使用过程中遇到问题，或者希望增加新功能？欢迎前往我的酷安主页交流反馈。感谢大家一起完善台前调度！", 15f).apply {
            setLineSpacing(dp(5).toFloat(), 1f); setPadding(dp(18), dp(8), dp(18), dp(18))
        })
        val info = group(root)
        row(info, "二次开发", "ZMW小武 × GPT")
        row(info, "酷安账号", "${CoolapkFeedback.ACCOUNT} · UID ${CoolapkFeedback.UID}")
        val actions = group(root)
        row(actions, "前往我的酷安主页", "优先通过酷安打开", "help") {
            val destination = CoolapkFeedback.open { url, packageName ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    if (packageName != null) setPackage(packageName)
                })
                true
            }
            if (destination == CoolapkFeedback.Destination.UNAVAILABLE)
                toast("无法打开酷安或浏览器，请在酷安搜索 UID 43881308（ZMW专用）")
            else if (destination == CoolapkFeedback.Destination.WEB)
                toast("已使用网页打开；若页面不可用，请在酷安搜索 UID 43881308")
        }
        row(actions, "复制反馈模板", "仅复制空白模板，不采集或上传信息", "info") {
            runCatching {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("台前调度反馈", CoolapkFeedback.template))
            }.onSuccess { toast("反馈模板已复制") }.onFailure { toast("复制失败，请稍后重试") }
        }
        root.addView(hint("请自行填写反馈内容。截图和日志请先隐藏个人隐私；不会自动读取或上传设备信息。"))
    }
    private fun groupPage(root: LinearLayout) {
        val actions = group(root)
        row(actions, "保存当前工作组", icon = "groups") { command("save") }
        row(actions, "另存为新工作组", icon = "groups") { command("new") }
        val saved = group(root)
        if (groups.length() == 0) row(saved, "暂无工作组", "先安排好窗口，再保存")
        for (i in 0 until groups.length()) {
            val item = groups.getJSONObject(i); val id = item.getString("id"); val name = item.getString("name")
            row(saved, name, if (id == activeId) "当前工作组" else "点击切换或管理", "groups", Color.rgb(142, 88, 224)) {
                MaterialAlertDialogBuilder(activity).setTitle(name).setItems(arrayOf("切换", "重命名", "删除")) { _, which ->
                    when (which) {
                        0 -> command("restore", id)
                        1 -> rename(id, name)
                        2 -> MaterialAlertDialogBuilder(activity).setTitle("删除工作组？").setMessage("只删除保存记录，不关闭应用。")
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> command("delete", id) }.show()
                    }
                }.setNegativeButton("取消", null).show()
            }
        }
        root.addView(hint("保存真实主应用与四侧窗。应用任务结束后，恢复时可能需要重新启动。"))
    }
    private fun rename(id: String, name: String) {
        val input = EditText(activity).apply { setText(name); isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(32)) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("重命名").setView(input).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) input.error = "请输入名称" else { dialog.dismiss(); command("rename", id, value) }
        } }; dialog.show()
    }
    private fun chooseApp(window: Boolean = false, slot: Int? = null, expectedTask: Int? = null) {
        if (!connected || !active) { toast("请先开启台前调度"); return }
        Thread({
            val loaded = runCatching { io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository(activity.applicationContext).load().filter { it.component.packageName != activity.packageName }.sortedBy { it.label } }
            activity.runOnUiThread {
                if (!activity.isDestroyed) loaded.onSuccess { entries ->
                    MaterialAlertDialogBuilder(activity).setTitle(if (!window) "选择主应用" else if (slot == null) "添加小窗" else "替换应用")
                        .setItems(entries.map { it.label }.toTypedArray()) { _, i ->
                            command(if (!window) "open" else if (slot == null) "add_window" else "replace_window",
                                launch = entries[i].launchIntent(), slot = slot, expectedTask = expectedTask)
                        }
                        .setNegativeButton("取消", null).show()
                }.onFailure { toast("无法读取应用列表") }
            }
        }, "Stage-Settings-Apps").start()
    }
    private fun addTile() {
        runCatching {
            activity.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(activity, NeXtepTileService::class.java), "台前调度", Icon.createWithResource(activity, R.drawable.ic_nextep_tile), activity.mainExecutor) { result ->
                toast(if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) "快捷开关已添加" else "请在控制中心编辑开关，添加台前调度")
            }
        }.onFailure { toast("请在控制中心编辑开关，添加台前调度") }
    }
    private fun syncSwitch() { settingSwitch = true; master?.isChecked = active; stateText?.text = homeState(); settingSwitch = false }
    private fun setStage(value: Boolean) {
        if (busy) { syncSwitch(); return }
        if (!value) {
            syncSwitch()
            MaterialAlertDialogBuilder(activity).setTitle("退出台前调度？")
                .setMessage("将退出当前工作区，恢复普通显示。设置和工作组会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出") { _, _ -> performSetStage(false) }.show()
            return
        }
        performSetStage(true)
    }
    private fun performSetStage(value: Boolean) {
        if (busy) { syncSwitch(); return }
        if (value && !connected) {
            syncSwitch(); open("diagnostics")
            toast("本版系统组件尚未连接，先退出旧工作区并正常重启一次"); return
        }
        busy = true
        TriggerCoordinator.requestSet(activity, value, TriggerSource.SYSTEM_RECOVERY) { result ->
            busy = false
            if (!activity.isDestroyed) {
                when (result) {
                    is TriggerCoordinator.RequestResult.Ready -> {
                        active = result.active; syncSwitch()
                        toast(if (!value && !result.active) "已退出台前调度" else if (value && result.active) "已开启台前调度" else "系统状态未同步")
                    }
                    is TriggerCoordinator.RequestResult.NotReady -> { syncSwitch(); toast("系统组件未就绪，请查看运行与诊断") }
                }; refresh()
            }
        }
    }
    fun refresh() { queryRuntimeIdentity(); command("query") }
    private var identityGeneration=0
    private fun queryRuntimeIdentity() {
        val generation=++identityGeneration
        val expected=io.github.x647368maker.atomicworkbench.systemui.StageRuntimeIdentity.BUILD
        val actions=listOf(io.github.x647368maker.atomicworkbench.systemui.StageRuntimeIdentity.AUDIT_ACTION,
            "io.github.x647368maker.atomicworkbench.action.RUNTIME_IDENTITY_27",
            "io.github.x647368maker.atomicworkbench.action.RUNTIME_IDENTITY_26")
        var received=false
        fun render(){if(page=="diagnostics" && !activity.isDestroyed)open("diagnostics")}
        handler.postDelayed({
            if(generation==identityGeneration && !received) {
                runtimeIdentity=InjectionDiagnosis.describe(false,expected,null,0,"",false);render()
            }
        },1500)
        fun probe(index:Int) {
            val intent=Intent(actions[index]).setPackage("com.android.systemui")
            activity.sendOrderedBroadcast(intent,null,object:BroadcastReceiver(){
                override fun onReceive(c:Context?,i:Intent?) {
                    if(generation!=identityGeneration || activity.isDestroyed)return
                    val bundle=getResultExtras(false);val build=bundle?.getString("loaded_build")
                    if(build==null) {
                        if(index+1<actions.size)probe(index+1)
                        else {runtimeIdentity=InjectionDiagnosis.describe(false,expected,null,0,"",false);render()}
                        return
                    }
                    received=true
                    val reply=checkNotNull(bundle)
                    val pid=reply.getInt("loaded_pid")
                    val started=reply.getLong("process_started",-1)
                    val prefs=activity.getSharedPreferences("atomic_injection_observation_v1",Context.MODE_PRIVATE)
                    val boot=runCatching { android.provider.Settings.Global.getInt(activity.contentResolver,"boot_count",-1) }.getOrDefault(-1)
                    val changed=prefs.contains("pid") && prefs.getInt("boot",-2)==boot &&
                        (prefs.getInt("pid",-1)!=pid || prefs.getLong("started",-1)!=started)
                    prefs.edit().putInt("pid",pid).putLong("started",started).putInt("boot",boot).apply()
                    val failures=reply.getString("hook_failures").orEmpty().take(8000)
                    runtimeIdentity=InjectionDiagnosis.describe(true,expected,build,reply.getInt("framework_api"),failures,changed)+
                        "\n实际代码：$build · PID $pid · ${reply.getString("loaded_process").orEmpty()}\n${reply.getString("framework").orEmpty()}\n"+
                        reply.getString("init_stages").orEmpty().take(8000)+
                        "\n迁移实测：\n"+reply.getString("exchange_metrics").orEmpty().take(4000)
                    render()
                }
            },handler,0,null,Bundle())
        }
        probe(0)
    }
    private var queryPending = false
    private fun command(name: String, id: String? = null, title: String? = null, launch: Intent? = null, slot: Int? = null, expectedTask: Int? = null) {
        if (activity.isDestroyed || name == "query" && queryPending) return
        if (name == "query") queryPending = true
        var completed = false
        fun unavailable() {
            connected = false; status = "系统组件未连接"; stateText?.text = homeState()
            if (page == "diagnostics") open("diagnostics")
        }
        val timeout = Runnable { if (!completed && !activity.isDestroyed) { completed = true; queryPending = false; unavailable() } }
        handler.postDelayed(timeout, 6000)
        val intent = Intent(StageControlContract.ACTION).setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
            .putExtra(StageControlContract.COMMAND, name).putExtra(StageControlContract.ID, id).putExtra(StageControlContract.NAME, title)
            .putExtra(TriggerBroadcastContract.EXTRA_LAUNCH_INTENT, launch)
            .putExtra("slot", slot ?: -1).putExtra("expected_task", expectedTask ?: -1)
        runCatching {
            activity.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, request: Intent?) {
                    if (completed || activity.isDestroyed) return
                    completed = true; handler.removeCallbacks(timeout); queryPending = false
                    val reply = getResultExtras(false)
                    if (resultCode != TriggerBroadcastContract.RESULT_HANDLED || reply == null || reply.getInt(TriggerBroadcastContract.EXTRA_RUNTIME_PROTOCOL_VERSION) != TriggerBroadcastContract.RUNTIME_PROTOCOL_VERSION) { unavailable(); return }
                    connected = true; active = reply.getBoolean(TriggerBroadcastContract.EXTRA_ACTIVE)
                    status = if (active) "已开启" else "已关闭"
                    stateText?.text = homeState(); syncSwitch()
                    if (!reply.getBoolean(StageControlContract.OK)) { toast(reply.getString(StageControlContract.MESSAGE) ?: "操作失败"); return }
                    reply.getString(StageControlContract.GROUPS)?.let { raw -> runCatching {
                        val snapshot = JSONObject(raw); groups = snapshot.getJSONArray("groups")
                        activeId = snapshot.optString("active").takeIf { it.isNotBlank() && it != "null" }
                    } }
                    slotDiagnostics = reply.getString("slot_diagnostics") ?: "系统组件未返回槽位诊断"
                    cinemaMode = reply.getString("cinema_mode", "AUTO")
                    quickFixed = reply.getBoolean("quick_fixed"); quickExpanded = reply.getBoolean("quick_expanded")
                    adaptive = reply.getBoolean("adaptive", true)
                    mainPriority = reply.getBoolean("main_priority", true)
                    reply.getString("windows")?.let { raw -> runCatching { windows = JSONArray(raw) } }
                    if (page == "groups" || page == "diagnostics" || page == "windows" || page == "core") open(page)
                    if (name == "add_window" || name == "replace_window" || name == "close_window") {
                        for (delay in listOf(500L, 1800L, 4000L)) handler.postDelayed({ if (page == "windows") refresh() }, delay)
                    }
                }
            }, handler, TriggerBroadcastContract.RESULT_NOT_READY, null, Bundle())
        }.onFailure { completed = true; queryPending = false; handler.removeCallbacks(timeout); unavailable() }
    }
}

/** Small colored setting glyphs, drawn natively and aligned like system list icons. */
private class StageSettingsIcon(context: Context, private val kind: String, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2.1f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        val save = canvas.save(); val scale = minOf(width, height) / 24f
        canvas.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2); canvas.scale(scale, scale)
        fun rect(l: Float, t: Float, r: Float, b: Float) = canvas.drawRoundRect(l, t, r, b, 1.5f, 1.5f, paint)
        when (kind) {
            "stage", "windows" -> { rect(9f, 3f, 22f, 21f); for (y in listOf(4f, 10f, 16f)) rect(2f, y, 5f, y + 3) }
            "feedback" -> { rect(3f, 3f, 21f, 17f); canvas.drawLine(3f, 17f, 3f, 21f, paint); canvas.drawLine(3f, 21f, 8f, 17f, paint); canvas.drawLine(8f, 9f, 16f, 9f, paint) }
            "groups" -> { rect(3f, 3f, 17f, 17f); canvas.drawLine(8f, 21f, 21f, 21f, paint); canvas.drawLine(21f, 8f, 21f, 21f, paint) }
            "apps" -> { for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) rect(x, y, x + 7, y + 7) }
            "tile" -> { rect(2f, 4f, 22f, 10f); rect(2f, 14f, 22f, 20f); canvas.drawCircle(7f, 7f, 1f, paint); canvas.drawCircle(17f, 17f, 1f, paint) }
            "exit" -> { canvas.drawArc(3f, 4f, 21f, 22f, -48f, 276f, false, paint); canvas.drawLine(12f, 2f, 12f, 12f, paint) }
            "gesture" -> { canvas.drawLine(4f, 13f, 10f, 19f, paint); canvas.drawLine(10f, 19f, 21f, 5f, paint); canvas.drawCircle(5f, 5f, 2f, paint) }
            "diagnostics" -> { canvas.drawLine(2f, 13f, 6f, 13f, paint); canvas.drawLine(6f, 13f, 10f, 5f, paint); canvas.drawLine(10f, 5f, 14f, 20f, paint); canvas.drawLine(14f, 20f, 18f, 10f, paint); canvas.drawLine(18f, 10f, 22f, 10f, paint) }
            else -> { canvas.drawCircle(12f, 12f, 9f, paint); canvas.drawLine(12f, 11f, 12f, 17f, paint); canvas.drawPoint(12f, 7f, paint) }
        }; canvas.restoreToCount(save)
    }
}
