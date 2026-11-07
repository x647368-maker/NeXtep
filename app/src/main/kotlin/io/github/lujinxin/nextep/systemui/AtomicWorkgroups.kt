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
import java.util.UUID

/** Saved groups contain real task identities. Active membership is refreshed before leaving
 * a group; a new active group is committed only after the actual five-window state matches. */
internal class AtomicWorkgroups(
    private val context: Context,
    private val repository: TaskRepository,
    private val coordinator: () -> SlotTaskCoordinator?,
    private val interactive: () -> Boolean,
    private val expandSidebar: () -> Unit,
    private val onStateChanged: () -> Unit,
    private val depthOrder: () -> List<Int>,
    private val onGroupActivated: (List<Int>) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy {
        context.createDeviceProtectedStorageContext().getSharedPreferences("atomic_workgroups_v1", Context.MODE_PRIVATE)
    }
    private var restoring = false
    private var restoringName: String? = null
    private var generation = 0
    private data class Launch(val json: JSONObject, val intent: Intent, val identity: StageTaskIdentity)

    fun cancel() {
        if (restoring) runCatching {
            preferences.edit().remove("active_id").remove("active_name").commit()
        }
        generation++
        restoring = false
        restoringName = null
        handler.removeCallbacksAndMessages(null)
        onStateChanged()
    }

    fun statusLabel(): String = restoringName?.let { "切换中：$it" } ?: runCatching {
        preferences.getString("active_name", null) ?: "未分组"
    }.getOrDefault("未分组")

    private fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun groups(): JSONArray {
        val all = JSONArray(preferences.getString("groups", "[]"))
        var migrated = false
        for (i in 0 until all.length()) {
            val group = all.getJSONObject(i)
            if (!group.has("id")) { group.put("id", UUID.randomUUID().toString()); migrated = true }
        }
        if (migrated) check(preferences.edit().putString("groups", all.toString()).commit())
        return all
    }

    private fun entry(task: TaskRepository.TaskSnapshot): JSONObject = JSONObject()
        .put("taskId", task.taskId).put("userId", checkNotNull(task.userId) { "无法确认任务用户" })
        .put("component", task.component.flattenToString())

    private fun capture(id: String, name: String, engine: SlotTaskCoordinator): JSONObject {
        val slots = JSONArray()
        engine.slotStates().forEach { state ->
            if (state is SlotState.Occupied) slots.put(entry(checkNotNull(repository.findTask(state.taskId))))
            else { check(state is SlotState.Ready) { "四个侧窗尚未全部就绪" }; slots.put(JSONObject.NULL) }
        }
        return JSONObject().put("version", 1).put("id", id).put("name", name)
            .put("main", repository.foregroundTask()?.let(::entry) ?: JSONObject.NULL).put("slots", slots)
            .put("depth_order", JSONArray(depthOrder()))
    }

    fun save() = saveInternal(forceNew = false)
    fun saveNew() = saveInternal(forceNew = true)

    private fun saveInternal(forceNew: Boolean) {
        if (!interactive() || restoring) return message("工作区正在切换，请稍后保存")
        val engine = coordinator() ?: return message("任务服务未就绪")
        if (!engine.isGroupRestoreReady()) return message("任务正在切换，请稍后保存")
        runCatching {
            val all = groups()
            val active = if (forceNew) null else preferences.getString("active_id", null)
            val previous = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.getString("id") == active }
            val id = previous?.getString("id") ?: UUID.randomUUID().toString()
            val name = previous?.getString("name") ?: SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val group = capture(id, name, engine)
            val updated = JSONArray().put(group)
            for (i in 0 until all.length()) {
                val other = all.getJSONObject(i)
                if (other.getString("id") != id && updated.length() < 20) updated.put(other)
            }
            check(preferences.edit().putString("groups", updated.toString()).putString("active_id", id)
                .putString("active_name", name).commit()) { "工作组保存失败" }
            onStateChanged()
            message("已保存工作组 $name")
        }.onFailure { message(it.message ?: "工作组保存失败") }
    }

    fun choose(anchor: View) {
        if (restoring) return message("正在切换工作组")
        runCatching {
            val all = groups()
            if (all.length() == 0) { message("请先新建工作组"); return }
            val active = preferences.getString("active_id", null)
            AtomicPopupMenu.show(context, anchor, (0 until all.length()).map { index ->
                val group = all.getJSONObject(index)
                val prefix = if (group.getString("id") == active) "✓ " else ""
                (prefix + group.getString("name")) to { restore(group) }
            })
        }.onFailure { message("无法打开工作组：${it.message}") }
    }

    private fun launch(entry: JSONObject): Launch {
        val component = checkNotNull(ComponentName.unflattenFromString(entry.getString("component")))
        val userId = entry.getInt("userId")
        require(userId >= 0)
        val intent = context.packageManager.getLaunchIntentForPackage(component.packageName)
            ?: if (component.packageName == TriggerBroadcastContract.resolveHomePackage(context)) {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(component)
            } else throw IllegalStateException("应用不可用：${component.packageName}")
        intent.putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER,
            UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId) as UserHandle)
        return Launch(entry, intent, StageTaskIdentity(entry.getInt("taskId"), component.packageName, userId))
    }

    private fun identity(task: TaskRepository.TaskSnapshot?): StageTaskIdentity? = task?.let {
        it.userId?.let { user -> StageTaskIdentity(it.taskId, it.component.packageName, user) }
    }

    private fun matches(expected: Launch, task: TaskRepository.TaskSnapshot?,
        observed: List<TaskRepository.TaskSnapshot> = repository.runningTasks()): Boolean {
        val original = identity(observed.firstOrNull { it.taskId == expected.identity.taskId })
        val stillExists = original?.packageName == expected.identity.packageName && original?.userId == expected.identity.userId
        return StageGroupVerification.matches(expected.identity, identity(task), stillExists)
    }

    private fun restore(source: JSONObject) {
        if (!interactive() || restoring) return message("工作区忙碌")
        val engine = coordinator() ?: return message("任务服务未就绪")
        if (!engine.isGroupRestoreReady()) return message("任务正在切换，请稍后再试")
        runCatching {
            val group = JSONObject(source.toString())
            val slots = checkNotNull(group.optJSONArray("slots"))
            require(group.optInt("version") == 1 && slots.length() == WORKSPACE_SLOT_COUNT) { "工作组版本不兼容" }
            val launches = (0 until slots.length()).map { slots.optJSONObject(it)?.let(::launch) }
            val main = group.optJSONObject("main")?.let(::launch)
            val id = group.getString("id")
            val name = group.getString("name")
            val savedDepth = group.optJSONArray("depth_order")
            val order = if (savedDepth == null) (0 until WORKSPACE_SLOT_COUNT).toList()
                else (0 until savedDepth.length()).map { savedDepth.getInt(it) }
            require(order.sorted() == (0 until WORKSPACE_SLOT_COUNT).toList()) { "工作组层级记录无效" }
            val activeId = preferences.getString("active_id", null)
            // Selecting the active group explicitly restores its saved snapshot.
            // Switching to a different group first saves the current live membership.
            if (StageGroupSwitchPolicy.shouldSaveCurrent(activeId, id)) {
                val all = groups()
                for (i in 0 until all.length()) {
                    val old = all.getJSONObject(i)
                    if (old.getString("id") == activeId) all.put(i, capture(checkNotNull(activeId), old.getString("name"), engine))
                }
                check(preferences.edit().putString("groups", all.toString()).commit()) { "离开前保存当前组失败" }
            }
            expandSidebar()
            restoring = true
            restoringName = name
            val token = ++generation
            onStateChanged()
            engine.prepareGroupRestore().getOrThrow()
            val deadline = SystemClock.uptimeMillis() + 30000L
            var index = 0
            var requested = false
            var mainRequested = false
            lateinit var step: () -> Unit
            fun advance() {
                if (token != generation) return
                if (!interactive() || SystemClock.uptimeMillis() > deadline) {
                    cancel(); message("工作组切换已中止，已移动的任务保留；请检查窗口"); return
                }
                if (!engine.isGroupRestoreReady()) { handler.postDelayed({ step() }, 200); return }
                while (index < launches.size && launches[index] == null) index++
                if (index < launches.size) {
                    val target = checkNotNull(launches[index])
                    if (!requested) {
                        engine.restoreInSlot(target.intent, index, target.identity.taskId).getOrThrow()
                        requested = true
                    } else {
                        val state = engine.slotStates()[index] as? SlotState.Occupied
                        if (matches(target, state?.let { repository.findTask(it.taskId) })) { index++; requested = false }
                    }
                } else if (!mainRequested && main != null) {
                    engine.openInMain(main.intent, preferredTaskId = main.identity.taskId).getOrThrow()
                    mainRequested = true
                } else {
                    val states = engine.slotStates()
                    val observed = repository.runningTasks()
                    val byId = observed.associateBy { it.taskId }
                    val slotsMatch = launches.indices.map { i ->
                        val target = launches[i]
                        if (target == null) states[i] is SlotState.Ready else {
                            val state = states[i] as? SlotState.Occupied
                            matches(target, state?.let { byId[it.taskId] }, observed)
                        }
                    }
                    if (StageGroupSwitchPolicy.isComplete(slotsMatch, main == null || matches(main, observed.firstOrNull { it.displayId == 0 }, observed))) {
                        onGroupActivated(order)
                        check(preferences.edit().putString("active_id", id).putString("active_name", name).commit())
                        restoring = false
                        restoringName = null
                        onStateChanged()
                        message("已切换工作组 $name")
                        return
                    }
                }
                handler.postDelayed({ step() }, 200)
            }
            // A function value permits safe asynchronous recursion and catches stale-data/native failures.
            step = {
                if (token == generation) runCatching { advance() }.onFailure {
                    cancel(); message("工作组部分恢复：${it.message}")
                }
            }
            step()
        }.onFailure {
            if (restoring) cancel()
            message("工作组切换未完成：${it.message}")
        }
    }

}
