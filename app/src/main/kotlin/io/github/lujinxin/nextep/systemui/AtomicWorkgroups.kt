package io.github.x647368maker.atomicworkbench.systemui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.view.View
import android.widget.Toast
import io.github.x647368maker.atomicworkbench.display.SlotState
import io.github.x647368maker.atomicworkbench.display.SlotTaskCoordinator
import io.github.x647368maker.atomicworkbench.display.WORKSPACE_SLOT_COUNT
import io.github.x647368maker.atomicworkbench.task.TaskRepository
import io.github.x647368maker.atomicworkbench.trigger.TriggerBroadcastContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores identities, never screenshots. Live tasks are reused by ID and user when available. */
internal class AtomicWorkgroups(
    private val context: Context,
    private val repository: TaskRepository,
    private val coordinator: () -> SlotTaskCoordinator?,
    private val interactive: () -> Boolean,
    private val expandSidebar: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("atomic_workgroups_v1", Context.MODE_PRIVATE)
    }
    private var restoring = false
    private var generation = 0

    fun cancel() {
        generation++
        restoring = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun groups(): JSONArray = runCatching {
        JSONArray(preferences.getString("groups", "[]"))
    }.getOrElse { JSONArray() }

    private fun entry(task: TaskRepository.TaskSnapshot): JSONObject {
        val user = checkNotNull(task.userId) { "无法确认任务所属用户，未保存" }
        return JSONObject().put("taskId", task.taskId).put("userId", user)
            .put("component", task.component.flattenToString())
    }

    fun save() {
        if (!interactive() || restoring) return message("工作区正在切换，请稍后保存")
        val engine = coordinator() ?: return message("任务服务尚未就绪")
        if (!engine.isGroupRestoreReady()) return message("任务正在切换，请稍后保存")
        runCatching {
            val main = repository.foregroundTask()
            val slots = JSONArray()
            engine.slotStates().forEach { state ->
                if (state is SlotState.Occupied) {
                    slots.put(entry(checkNotNull(repository.findTask(state.taskId)) { "侧窗任务已消失" }))
                } else {
                    check(state is SlotState.Ready) { "四个侧窗尚未全部就绪" }
                    slots.put(JSONObject.NULL)
                }
            }
            val name = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val group = JSONObject().put("version", 1).put("name", name)
                .put("main", main?.let(::entry) ?: JSONObject.NULL).put("slots", slots)
            val all = groups()
            val updated = JSONArray().put(group)
            for (index in 0 until minOf(all.length(), 19)) updated.put(all.getJSONObject(index))
            check(preferences.edit().putString("groups", updated.toString()).commit()) { "保存失败" }
            message("已保存工作组 $name")
        }.onFailure { message(it.message ?: "工作组保存失败") }
    }

    fun choose(anchor: View) {
        if (restoring) return message("正在恢复工作组")
        val all = groups()
        if (all.length() == 0) return message("请先保存一个工作组")
        runCatching {
            AtomicPopupMenu.show(context, anchor, (0 until all.length()).map { index ->
                val group = all.getJSONObject(index)
                group.getString("name") to { restore(group) }
            })
        }.onFailure { message("无法打开工作组列表：${it.message}") }
    }

    private fun intent(entry: JSONObject): Intent {
        val component = checkNotNull(ComponentName.unflattenFromString(entry.getString("component")))
        val launch = context.packageManager.getLaunchIntentForPackage(component.packageName)
            ?: throw IllegalStateException("应用不可用：${component.packageName}")
        // The saved task can belong to a non-exported child activity. Use the public
        // launcher for fallback startup, and the separately checked ID for live reuse.
        return Intent(launch).putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER,
            UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, entry.getInt("userId")) as UserHandle)
    }

    private fun restore(group: JSONObject) {
        if (!interactive() || restoring) return message("工作区忙碌")
        val engine = coordinator() ?: return message("任务服务未就绪")
        val slots = group.optJSONArray("slots") ?: return message("工作组数据无效")
        if (group.optInt("version") != 1 || slots.length() != WORKSPACE_SLOT_COUNT) return message("工作组版本不兼容")
        // Validate every launch before changing any existing task placement.
        val launches = runCatching {
            (0 until slots.length()).map { i -> slots.optJSONObject(i)?.let { it to intent(it) } }
        }.getOrElse { return message("恢复未开始：${it.message}") }
        val main = group.optJSONObject("main")
        val mainIntent = runCatching { main?.let(::intent) }.getOrElse { return message("恢复未开始：${it.message}") }
        if (!engine.isGroupRestoreReady()) return message("任务正在切换，请稍后恢复")
        expandSidebar()
        engine.prepareGroupRestore().onFailure { return message("恢复未开始：${it.message}") }
        restoring = true
        val token = ++generation
        val deadline = SystemClock.uptimeMillis() + 20000L
        var index = 0
        var launched = false
        fun step() {
            if (token != generation) return
            if (!interactive() || SystemClock.uptimeMillis() > deadline) {
                cancel()
                message("工作组恢复已中止；已移动的任务保留，请检查侧窗")
                return
            }
            if (!engine.isGroupRestoreReady()) { handler.postDelayed({ step() }, 200); return }
            while (index < launches.size && launches[index] == null) index++
            if (index == launches.size) {
                val result = if (mainIntent != null && main != null) {
                    engine.openInMain(mainIntent, preferredTaskId = main.getInt("taskId"))
                } else Result.success(Unit)
                restoring = false
                result.onFailure { message("侧窗已恢复，主窗恢复失败：${it.message}") }
                    .onSuccess { message("已提交工作组恢复，请确认五个窗口") }
                return
            }
            val (entry, launch) = checkNotNull(launches[index])
            if (!launched) {
                val result = engine.restoreInSlot(launch, index, entry.getInt("taskId"))
                if (result.isFailure) {
                    cancel()
                    message("工作组部分恢复，侧窗 ${index + 1} 失败：${result.exceptionOrNull()?.message}")
                    return
                }
                launched = true
            } else {
                val state = engine.slotStates()[index]
                val task = (state as? SlotState.Occupied)?.let { repository.findTask(it.taskId) }
                if (task?.component?.packageName == launch.component?.packageName && task?.userId == entry.getInt("userId")) {
                    index++
                    launched = false
                }
            }
            handler.postDelayed({ step() }, 200)
        }
        step()
    }
}
