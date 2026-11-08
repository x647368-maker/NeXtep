package io.github.x647368maker.atomicworkbench.config

import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.x647368maker.atomicworkbench.R
import io.github.x647368maker.atomicworkbench.trigger.*
import org.json.JSONArray
import org.json.JSONObject

/** All persistent stage controls live in the ordinary app, not over real tasks. */
internal object StageSettingsScreen {
    fun create(activity: AppCompatActivity, repository: SettingsRepository): View {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        fun text(value: String, size: Float = 14f) = TextView(activity).apply {
            text = value; textSize = size; setTextColor(SettingsPalette.text(activity))
            setPadding(0, dp(8), 0, dp(8)); setLineSpacing(dp(3).toFloat(), 1f)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(24))
        }
        root.addView(text("台前调度", 30f).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(text("0.3.1 · Android 个人使用版\n工作区只显示真实主应用和侧窗。所有控制选项集中在这里。"))
        val state = text("正在读取系统组件状态…")
        root.addView(state)
        fun toast(s: String) { if (!activity.isDestroyed) Toast.makeText(activity, s, Toast.LENGTH_LONG).show() }
        fun section(title: String): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(SettingsPalette.surface(activity)); cornerRadius = dp(20).toFloat()
            }
            addView(text(title, 19f).apply { typeface = Typeface.DEFAULT_BOLD })
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        fun button(parent: LinearLayout, title: String, action: (MaterialButton) -> Unit) = MaterialButton(activity).apply {
            text = title; isAllCaps = false
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
            setOnClickListener { action(this) }
        }
        val groupList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var latest = JSONArray()
        var active: String? = null
        lateinit var renderGroups: () -> Unit
        fun command(name: String, id: String? = null, title: String? = null, launch: Intent? = null, done: ((Bundle) -> Unit)? = null) {
            val handler = Handler(Looper.getMainLooper())
            var completed = false
            val timeout = Runnable {
                if (!completed && !activity.isDestroyed) {
                    completed = true; state.text = "系统组件没有响应。若刚更新 APK，请正常重启手机一次。"
                }
            }
            handler.postDelayed(timeout, 6000)
            val request = Intent(StageControlContract.ACTION).setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                .putExtra(StageControlContract.COMMAND, name)
                .putExtra(StageControlContract.ID, id).putExtra(StageControlContract.NAME, title)
                .putExtra(TriggerBroadcastContract.EXTRA_LAUNCH_INTENT, launch)
            runCatching {
                activity.sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (completed || activity.isDestroyed) return
                        completed = true; handler.removeCallbacks(timeout)
                        val reply = getResultExtras(false)
                        if (resultCode != TriggerBroadcastContract.RESULT_HANDLED || reply == null ||
                            reply.getInt(TriggerBroadcastContract.EXTRA_RUNTIME_PROTOCOL_VERSION) != TriggerBroadcastContract.RUNTIME_PROTOCOL_VERSION) {
                            state.text = "尚未连接本版系统组件。安装后请正常重启手机，Vector 中只启用台前调度。"
                            return
                        }
                        if (!reply.getBoolean(StageControlContract.OK)) {
                            toast(reply.getString(StageControlContract.MESSAGE) ?: "操作失败"); return
                        }
                        state.text = if (reply.getBoolean(TriggerBroadcastContract.EXTRA_ACTIVE)) "台前调度：已开启 · 系统组件 0.3.1 已连接" else "台前调度：已关闭 · 系统组件 0.3.1 已连接"
                        reply.getString(StageControlContract.GROUPS)?.let { value ->
                            runCatching {
                                val snapshot = JSONObject(value)
                                latest = snapshot.getJSONArray("groups")
                                active = snapshot.optString("active").takeIf { it.isNotBlank() && it != "null" }
                                renderGroups()
                            }.onFailure { toast("工作组数据读取失败") }
                        }
                        done?.invoke(reply)
                    }
                }, handler, TriggerBroadcastContract.RESULT_NOT_READY, null, Bundle())
            }.onFailure {
                completed = true; handler.removeCallbacks(timeout); toast("无法连接系统组件：${it.message}")
            }
        }
        fun setStage(enabled: Boolean, source: MaterialButton) {
            source.isEnabled = false
            TriggerCoordinator.requestSet(activity, enabled, TriggerSource.SYSTEM_RECOVERY) { result ->
                if (activity.isDestroyed) return@requestSet
                source.isEnabled = true
                when (result) {
                    is TriggerCoordinator.RequestResult.Ready -> {
                        if (result.active != enabled) toast("状态尚未同步，请检查控制中心开关")
                        else toast(if (enabled) "已开启台前调度，返回应用即可使用" else "已退出台前调度，应用恢复普通显示")
                        command("query")
                    }
                    is TriggerCoordinator.RequestResult.NotReady -> toast("系统组件未就绪；若刚更新，请正常重启手机。${result.reason}")
                }
            }
        }
        val controls = section("开启与退出")
        button(controls, "开启台前调度") { setStage(true, it) }
        button(controls, "退出台前调度 · 恢复普通显示") { setStage(false, it) }
        controls.addView(text("退出方法：下拉控制中心，关闭“台前调度”快捷开关；或打开本应用，点击上方退出按钮。\n返回键和 Home 键不会退出。收起侧栏也不会退出，只会扩大主应用。退出沿用原生任务恢复流程，已保存的工作组记录会保留。"))
        button(controls, "添加控制中心快捷开关") { source ->
            runCatching {
                source.isEnabled = false
                activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(activity, NeXtepTileService::class.java), "台前调度",
                    Icon.createWithResource(activity, R.drawable.ic_nextep_tile), activity.mainExecutor,
                ) { result ->
                    if (!activity.isDestroyed) source.isEnabled = true
                    toast(if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                        "开关已添加，可用它随时开启或退出" else "请在控制中心编辑开关，添加“台前调度”")
                }
            }.onFailure { source.isEnabled = true; toast("请在控制中心编辑开关，添加“台前调度”") }
        }
        val layout = section("窗口与侧栏")
        button(layout, "侧栏放左边") { command("left") }
        button(layout, "侧栏放右边") { command("right") }
        button(layout, "收起侧栏 · 扩大主应用") { command("collapse") }
        button(layout, "展开四个侧窗") { command("expand") }
        layout.addView(text("也可滑动屏幕边缘的窄把手收起或展开侧栏。点击侧窗交换真实应用任务；点击空侧窗添加应用。"))
        val gestures = repository.topBarSettings()
        layout.addView(SwitchMaterial(activity).apply {
            text = "状态栏右侧滑动开关"; isChecked = gestures.statusBarGestureEnabled
            setOnCheckedChangeListener { _, checked -> repository.setStatusBarGestureEnabled(checked) }
        })
        layout.addView(SwitchMaterial(activity).apply {
            text = "四指捏合进入／张开退出"; isChecked = gestures.fourFingerGestureEnabled
            setOnCheckedChangeListener { _, checked -> repository.setFourFingerGestureEnabled(checked) }
        })
        val apps = section("应用")
        button(apps, "选择应用进入主窗口") { source ->
            source.isEnabled = false
            Thread({
                val loaded = runCatching {
                    io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository(activity.applicationContext)
                        .load().filter { it.component.packageName != activity.packageName }.sortedBy { it.label }
                }
                activity.runOnUiThread {
                    if (!activity.isDestroyed) {
                        source.isEnabled = true
                        loaded.onSuccess { entries ->
                            MaterialAlertDialogBuilder(activity).setTitle("选择主应用")
                                .setItems(entries.map { it.label }.toTypedArray()) { _, index ->
                                    command("open", launch = entries[index].launchIntent())
                                }.setNegativeButton("取消", null).show()
                        }.onFailure { toast("无法读取应用列表：${it.message}") }
                    }
                }
            }, "Stage-Settings-Apps").start()
        }
        apps.addView(text("先开启台前调度，再从这里选择主应用；也可照常从系统桌面打开应用。"))
        val groups = section("工作组")
        groups.addView(text("保存时记录工作应用和四个侧窗，不把本设置页当作主应用。切换会恢复真实任务；已结束的应用可能重新启动。"))
        button(groups, "保存当前工作组") { command("save") }
        button(groups, "另存为新工作组") { command("new") }
        button(groups, "刷新工作组与系统状态") { command("query") }
        groups.addView(groupList)
        renderGroups = {
            groupList.removeAllViews()
            if (latest.length() == 0) groupList.addView(text("尚无工作组。先安排好窗口，再保存。"))
            for (i in 0 until latest.length()) {
                val item = latest.getJSONObject(i); val id = item.getString("id"); val name = item.getString("name")
                val main = item.optJSONObject("main")?.optString("component")?.let { flat ->
                    runCatching {
                        val component = android.content.ComponentName.unflattenFromString(flat)!!
                        activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(component.packageName, 0)).toString()
                    }.getOrDefault(flat)
                } ?: "未记录主应用"
                button(groupList, (if (id == active) "● " else "") + name + " · " + main) {
                    MaterialAlertDialogBuilder(activity).setTitle(name)
                        .setItems(arrayOf("切换到此工作组", "重命名", "删除保存记录")) { _, which ->
                            when (which) {
                                0 -> command("restore", id)
                                1 -> {
                                    val input = EditText(activity).apply {
                                        setText(name); isSingleLine = true
                                        filters = arrayOf(android.text.InputFilter.LengthFilter(32))
                                    }
                                    val dialog = MaterialAlertDialogBuilder(activity).setTitle("重命名工作组").setView(input)
                                        .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
                                    dialog.setOnShowListener {
                                        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                                            val value = input.text.toString().trim()
                                            if (value.isEmpty()) input.error = "请输入名称" else { dialog.dismiss(); command("rename", id, value) }
                                        }
                                    }; dialog.show()
                                }
                                2 -> MaterialAlertDialogBuilder(activity).setTitle("删除“$name”？")
                                    .setMessage("只删除保存记录，不关闭或卸载应用。")
                                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> command("delete", id) }.show()
                            }
                        }.setNegativeButton("取消", null).show()
                }
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { command("query") }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        root.addView(text("基于 NeXtep · 原作者 lujinxin / JensenLu\n交互参考 macOS Stage Manager；此 Android 衍生版不是 Apple 官方应用。", 12f))
        return root
    }
}
