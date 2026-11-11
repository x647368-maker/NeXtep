package io.github.x647368maker.atomicworkbench.config

import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.x647368maker.atomicworkbench.monitoring.BatterySample
import io.github.x647368maker.atomicworkbench.monitoring.WorkspaceEnergyContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent, user-consented test session. No background pollers or screenshots.
 * When an inexact, non-wakeup alarm is delayed, the planned end remains the
 * selected deadline; the later actual capture time is labelled separately.
 */
internal class StageIssueSession(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("stage_issue_test_v1", Context.MODE_PRIVATE)

    fun active(): Boolean = preferences.getBoolean("active", false)
    fun currentToken(): Long = preferences.getLong("start_elapsed", -1L)
    fun durationMs(): Long = StageIssueReportPolicy.selectedDuration(
        preferences.getLong("duration_ms", StageIssueReportPolicy.DEFAULT_DURATION_MS))
    fun selectedCategory(): String = preferences.getString("category", null) ?: "other"
    fun latestReport(): String {
        val original = preferences.getString("report_core", null)
            ?: preferences.getString("report", null).orEmpty()
        val extra = preferences.getString("post_note", null).orEmpty()
        return (original + if (extra.isBlank()) "" else
            "\n补充说明（用户主动填写）：\n${StageIssueReportPolicy.note(extra)}\n")
            .take(StageIssueReportPolicy.MAX_REPORT_CHARS)
    }
    private val maxAutoEvents = 32
    fun autoEventCount(): Int = preferences.getInt("auto_event_count", 0).coerceIn(0, maxAutoEvents)

    /**
     * A bounded event log from real OS callbacks, not from periodic sampling.
     * If Android kills this process, transitions in that interval are unknown.
     */
    fun recordAutoEvent(token: Long, type: String, screen: String) {
        if (!active() || currentToken() != token || isDue()) return
        val count = autoEventCount()
        if (count >= maxAutoEvents) {
            preferences.edit().putBoolean("auto_events_truncated", true).apply()
            return
        }
        val elapsed = StageIssueReportPolicy.elapsedMs(token, SystemClock.elapsedRealtime())
            ?.div(1000L) ?: return
        val entry = "第${elapsed}秒｜${type.take(70)}｜屏幕：${screen.take(50)}"
        preferences.edit()
            .putString("auto_event_$count",entry.take(170))
            .putInt("auto_event_count",count+1)
            .apply()
    }

    fun markersCount(): Int = markers().size
    fun elapsedSeconds(): Long? = if (active())
        StageIssueReportPolicy.elapsedMs(currentToken(), SystemClock.elapsedRealtime())?.div(1000)
    else null
    fun remainingSeconds(): Long? = if (active())
        StageIssueReportPolicy.remainingMs(currentToken(), SystemClock.elapsedRealtime(),durationMs()).div(1000L)
    else null
    fun isDue(): Boolean = active() &&
        StageIssueReportPolicy.isDue(currentToken(), SystemClock.elapsedRealtime(), durationMs())
    fun mayMark(): Boolean = active() && !isDue() && StageIssueReportPolicy.canMark(
        currentToken(),SystemClock.elapsedRealtime(),markersCount())
    fun storedNote(): String = preferences.getString("note", null).orEmpty()
    fun storedVersion(): String = preferences.getString("version", "未知") ?: "未知"
    fun alarmScheduled(): Boolean = preferences.getBoolean("alarm_scheduled", false)

    fun begin(battery: BatterySample, categoryId: String, duration: Long,
              version: String, note: String, environment: String): Long {
        val now = SystemClock.elapsedRealtime()
        val chosen = StageIssueReportPolicy.selectedDuration(duration)
        preferences.edit().clear()
            .putBoolean("active", true)
            .putLong("start_elapsed", now)
            .putLong("start_wall", System.currentTimeMillis())
            .putLong("duration_ms", chosen)
            .putInt("app_pid", android.os.Process.myPid())
            .putString("version", version.take(60))
            .putString("note", StageIssueReportPolicy.note(note).takeIf { it != "用户未填写" } ?: "")
            .putString("category", StageIssueTestCatalog.find(categoryId).id)
            .putString("start_environment", environment.take(500))
            .putString("start_battery", batteryLine(battery))
            .putString("start_runtime", "系统界面进程：等待读取")
            .commit()
        val ok = StageIssueAutoStop.schedule(app, now,chosen)
        preferences.edit().putBoolean("alarm_scheduled", ok).apply()
        return now
    }

    fun saveNote(value: String) {
        val sanitized = StageIssueReportPolicy.note(value).takeIf { it != "用户未填写" }.orEmpty()
        if (active()) preferences.edit().putString("note", sanitized).apply()
        else if (latestReport().isNotBlank())
            preferences.edit().putString("post_note", sanitized).apply()
    }

    fun setStartRuntime(token: Long, snapshot: WorkspaceEnergyContract.Snapshot?) {
        if (!active() || currentToken() != token) return
        val edit = preferences.edit().putString("start_runtime", runtimeLine(snapshot))
        if (snapshot != null) {
            edit.putInt("start_pid", snapshot.processId)
                .putLong("start_cpu", snapshot.cpuMs)
                .putLong("start_runtime_elapsed", snapshot.elapsedMs)
                .putLong("start_main_cpu", snapshot.mainThreadCpuMs)
                .putLong("start_task", snapshot.taskQueries)
                .putLong("start_frames", snapshot.slotFrames)
                .putLong("start_captures", snapshot.previewCaptures)
        }
        edit.apply()
    }

    /** Each snapshot requires a real user tap; no automatic periodic markers. */
    fun mark(battery: BatterySample, environment: String): Int? {
        val start = currentToken()
        val now = SystemClock.elapsedRealtime()
        val recorded = markers()
        if (!active() || isDue() || !StageIssueReportPolicy.canMark(start, now, recorded.size)) return null
        val index = recorded.size + 1
        val sec = (now - start) / 1000L
        preferences.edit()
            .putString("markers", (recorded + sec).joinToString(","))
            .putString("marker_$index", "第${sec}秒 电池：${batteryLine(battery)}；${environment.take(500)}")
            .putString("marker_runtime_$index", "工作台：等待读取")
            .apply()
        return index
    }
    fun setMarkerRuntime(token: Long, index: Int, snapshot: WorkspaceEnergyContract.Snapshot?) {
        if (!active() || currentToken()!=token || index !in 1..markersCount()) return
        preferences.edit().putString("marker_runtime_$index",runtimeLine(snapshot)).apply()
    }

    fun finish(version: String, note: String, battery: BatterySample,
               snapshot: WorkspaceEnergyContract.Snapshot?, mode: String): String? {
        if (!active()) return null
        val token = currentToken()
        val elapsed = StageIssueReportPolicy.elapsedMs(token, SystemClock.elapsedRealtime())
        val planned = durationMs()
        val startWall = preferences.getLong("start_wall", 0)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val labels = markers()
        val category = StageIssueTestCatalog.find(selectedCategory())
        val originalPid = preferences.getInt("app_pid", -1)
        val finalNote = note.takeIf { it.isNotBlank() } ?: storedNote()
        val report = buildString {
            appendLine("台前调度｜用户自愿开启的故障测试报告")
            appendLine("只记录开始/结束快照、手动标记和系统主动通知的少量状态变化；不轮询、不截图、不抓取崩溃堆栈。")
            appendLine("屏幕变化通知和测试App前后台事件仅在测试已开启且App进程存活时可能记录；若进程被杀或ROM限制广播，中途会有空白。")
            appendLine("测试类型：${category.name}")
            appendLine("操作说明：${category.instructions}")
            appendLine("可检测范围限制：${category.boundary}")
            appendLine("版本：${version.take(60)}")
            appendLine("设备：${Build.MANUFACTURER.take(40)} ${Build.MODEL.take(60)}")
            appendLine("Android：${Build.VERSION.RELEASE.take(30)}（API ${Build.VERSION.SDK_INT}）")
            appendLine("开始时刻：${formatter.format(Date(startWall))}")
            appendLine("计划结束：${formatter.format(Date(startWall + planned))}（${planned/60_000}分钟）")
            appendLine("实际状态采集：${formatter.format(Date())}")
            appendLine("结束方式：${mode.take(80)}")
            appendLine("实际采集间隔：${elapsed?.div(1000L)?.toString() ?: "系统重启/时间基准异常"}秒")
            if (elapsed==null || elapsed>planned)
                appendLine("注意：到期后的系统休眠/任务调度可能使结束快照晚于预定时间；不代表持续测试了更久。")
            appendLine("定时提醒设置：${if(alarmScheduled()) "已安排（非唤醒型，可延期）" else "无法设置，需重新打开 App 补结束"}")
            appendLine("开始电池：${preferences.getString("start_battery","无法读取")}")
            appendLine("结束电池：${batteryLine(battery)}")
            appendLine("开始设备状态：${preferences.getString("start_environment","不可用")}")
            appendLine("结束设备状态：${StageIssueSceneSnapshot.read(app)}")
            appendLine("开始工作台：${preferences.getString("start_runtime","无法读取")}")
            appendLine("结束工作台：${runtimeLine(snapshot)}")
            appendLine("自动屏幕及测试App前后台事件：${autoEventCount()}条（最多${maxAutoEvents}条）")
            for (event in 0 until autoEventCount()) {
                appendLine("状态事件#${event+1}：${preferences.getString("auto_event_$event","不可用")}")
            }
            if (preferences.getBoolean("auto_events_truncated",false))
                appendLine("注意：状态事件超出上限，后续事件未写入。")
            appendLine("事件记录覆盖范围：无法保证系统杀掉本App后的间隔；没有事件不代表没有锁屏/切后台。")
            appendLine("判定区分：测试App前后台与工作台真实显示状态不同；工作台仅能读取开启/挂起，不推断第三方App焦点。")
            appendLine("运行计数变化：${runtimeDelta(snapshot)}")
            appendLine("手动标记：${labels.size}次（不要求使用）")
            for (index in labels.indices) {
                appendLine("标记#${index+1}：${preferences.getString("marker_${index+1}","不可用")}")
                appendLine("标记时工作台：${preferences.getString("marker_runtime_${index+1}","不可用")}")
            }
            appendLine("App进程线索：${if(originalPid == android.os.Process.myPid()) "目前进程号相同，不能排除曾发生异常" else "进程号有变化或不可用，不能据此认定闪退"}")
            appendLine("用户说明：")
            appendLine(StageIssueReportPolicy.note(finalNote))
            appendLine("注意：电量/温度属于整机，CPU计数来自整个SystemUI进程。接电测试不能用电量增长证明省电；判断模块独立增量须在相同条件下做断电、工作台关闭/开启对照。")
            appendLine("隐私：不含系统完整日志/私有聊天/应用列表；分享前请检查自行填写的内容。")
        }.take(StageIssueReportPolicy.MAX_REPORT_CHARS)
        preferences.edit().putBoolean("active", false).putString("report_core",report).apply()
        StageIssueAutoStop.cancel(app, token)
        StageIssueEventObserver.disable()
        return report
    }

    private fun markers(): List<Long> = preferences.getString("markers", null)
        .orEmpty().split(",").mapNotNull { it.toLongOrNull() }.take(StageIssueReportPolicy.MAX_MARKERS)

    private fun batteryLine(s: BatterySample): String {
        // EXTRA_PLUGGED is a *connection type*, not BATTERY_STATUS_CHARGING.
        val supply = when (s.plugged) {
            0 -> "未接电源"
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC电源"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB电源"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线电源"
            else -> "类型未知(${s.plugged ?: "无数据"})"
        }
        val state = when (s.status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "正在充电"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "正在放电"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "状态未知"
        }
        return "电量=${s.levelPercent?.let { "%.1f%%".format(Locale.US,it) } ?: "未知"} " +
            "温度=${s.temperatureC?.let { "%.1f°C".format(Locale.US,it) } ?: "未知"} " +
            "外部电源=$supply 电池状态=$state"
    }

    private fun runtimeLine(s: WorkspaceEnergyContract.Snapshot?): String = if (s==null)
        "系统界面进程未响应（状态不可用）"
    else "${StageIssueStateLabels.workspace(s.active,s.suspended)} 小窗=${s.slotCount} " +
        "帧计数=${s.slotFrames} 任务查询=${s.taskQueries} 截取计数=${s.previewCaptures}"

    private fun runtimeDelta(end: WorkspaceEnergyContract.Snapshot?): String {
        val pid=preferences.getInt("start_pid",-1)
        if (end==null || pid<0 || end.processId!=pid)
            return "不可比较（系统界面进程不同或数据缺失）"
        fun diff(v: Long,key:String)=(v-preferences.getLong(key,v))
            .takeIf{it>=0}?.toString()?:"不可用"
        val effective = StageCpuEvidence.averageCoreEquivalents(
            preferences.getLong("start_cpu",-1L),end.cpuMs,
            preferences.getLong("start_runtime_elapsed",-1L),end.elapsedMs)
        val normalized = effective?.let {
            "，平均约${String.format(Locale.US,"%.2f",it)}个CPU核心的累计忙碌时间"
        } ?: ""
        val mainBusy = StageCpuEvidence.averageCoreEquivalents(
            preferences.getLong("start_main_cpu", -1L), end.mainThreadCpuMs,
            preferences.getLong("start_runtime_elapsed", -1L), end.elapsedMs)
        val mainNote = mainBusy?.let {
            "，其中SystemUI主线程平均约${String.format(Locale.US,"%.3f",it)}个CPU核心时间"
        } ?: "，SystemUI主线程数据不可用"
        return "任务查询+${diff(end.taskQueries,"start_task")} " +
            "截取+${diff(end.previewCaptures,"start_captures")} " +
            "帧+${diff(end.slotFrames,"start_frames")} " +
            "整个SystemUI进程CPU+${diff(end.cpuMs,"start_cpu")}ms$normalized$mainNote（均不能归因模块独占）"
    }
}
