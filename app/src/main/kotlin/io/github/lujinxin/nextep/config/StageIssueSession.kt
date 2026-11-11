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
 * Manual snapshots only. No timer, service, screen capture, background sampling,
 * logcat, private app names or system settings changes.
 */
internal class StageIssueSession(context: Context) {
    private val preferences = context.getSharedPreferences("stage_issue_test_v1", Context.MODE_PRIVATE)

    fun active(): Boolean = preferences.getBoolean("active", false)
    fun latestReport(): String = preferences.getString("report", null).orEmpty()
    fun markersCount(): Int = markers().size
    fun elapsedSeconds(): Long? = if (active())
        StageIssueReportPolicy.elapsedMs(preferences.getLong("start_elapsed", -1), SystemClock.elapsedRealtime())
            ?.div(1_000L)
        else null
    fun mayMark(): Boolean = active() && StageIssueReportPolicy.canMark(
        preferences.getLong("start_elapsed", -1), SystemClock.elapsedRealtime(), markersCount())

    fun begin(battery: BatterySample): Long {
        val now = SystemClock.elapsedRealtime()
        preferences.edit().clear()
            .putBoolean("active", true)
            .putLong("start_elapsed", now)
            .putLong("start_wall", System.currentTimeMillis())
            .putString("start_battery", batteryLine(battery))
            .putString("start_runtime", "系统界面进程：等待读取")
            .apply()
        return now
    }

    fun setStartRuntime(token: Long, snapshot: WorkspaceEnergyContract.Snapshot?) {
        if (!active() || preferences.getLong("start_elapsed", -1) != token) return
        val edit = preferences.edit().putString("start_runtime", runtimeLine(snapshot))
        if (snapshot != null) {
            edit.putInt("start_pid", snapshot.processId)
                .putLong("start_cpu", snapshot.cpuMs)
                .putLong("start_task", snapshot.taskQueries)
                .putLong("start_frames", snapshot.slotFrames)
                .putLong("start_captures", snapshot.previewCaptures)
        }
        edit.apply()
    }

    fun mark(): Boolean {
        val start = preferences.getLong("start_elapsed", -1)
        val now = SystemClock.elapsedRealtime()
        val recorded = markers()
        if (!active() || !StageIssueReportPolicy.canMark(start, now, recorded.size)) return false
        val seconds = (now - start) / 1_000L
        preferences.edit().putString("markers", (recorded + seconds).joinToString(",")).apply()
        return true
    }

    fun finish(
        version: String,
        note: String,
        battery: BatterySample,
        snapshot: WorkspaceEnergyContract.Snapshot?,
    ): String? {
        if (!active()) return null
        val elapsed = StageIssueReportPolicy.elapsedMs(
            preferences.getLong("start_elapsed", -1), SystemClock.elapsedRealtime())
        val startWall = preferences.getLong("start_wall", 0)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startText = formatter.format(Date(startWall))
        val endText = formatter.format(Date())
        val markers = markers()
        val report = buildString {
            appendLine("台前调度｜用户手动故障测试报告")
            appendLine("说明：只在开始和结束时读取状态；测试期间不持续采样，不代表完整系统日志。")
            appendLine("不自动含截图、应用列表、私有消息或完整系统日志；用户备注请自行检查隐私。")
            appendLine("版本：${version.take(60)}")
            appendLine("设备：${Build.MANUFACTURER.take(40)} ${Build.MODEL.take(60)}")
            appendLine("Android：${Build.VERSION.RELEASE.take(30)}（API ${Build.VERSION.SDK_INT}）")
            appendLine("开始：$startText")
            appendLine("结束：$endText")
            appendLine("测试持续：${elapsed?.div(1_000L)?.toString() ?: "设备时间已重置"} 秒")
            if (elapsed == null || elapsed > StageIssueReportPolicy.MAX_DURATION_MS)
                appendLine("注意：超过30分钟或经历重启，不保证事件记录完整。")
            appendLine("开始电池：${preferences.getString("start_battery", "无法读取")}")
            appendLine("结束电池：${batteryLine(battery)}")
            appendLine("开始工作台：${preferences.getString("start_runtime", "无法读取")}")
            appendLine("结束工作台：${runtimeLine(snapshot)}")
            appendLine("运行计数变化：${runtimeDelta(snapshot)}")
            appendLine("用户标记：${if (markers.isEmpty()) "无" else markers.joinToString("、") { "第${it}秒" }}")
            appendLine("问题描述（用户自填，最多400字）：")
            appendLine(StageIssueReportPolicy.note(note))
            appendLine("提醒：电池数据是整机状态，系统界面进程CPU/帧计数也不等于台前调度独占耗电。")
        }.take(StageIssueReportPolicy.MAX_REPORT_CHARS)
        preferences.edit().putBoolean("active", false).putString("report", report).apply()
        return report
    }

    private fun markers(): List<Long> = preferences.getString("markers", null)
        .orEmpty().split(",").mapNotNull { it.toLongOrNull() }
        .take(StageIssueReportPolicy.MAX_MARKERS)

    private fun batteryLine(sample: BatterySample): String =
        "电量=${sample.levelPercent?.let { "%.1f%%".format(Locale.US, it) } ?: "未知"} " +
        "温度=${sample.temperatureC?.let { "%.1f°C".format(Locale.US, it) } ?: "未知"} " +
        "充电标志=${sample.plugged?.toString() ?: "未知"} " +
        "省电模式=${sample.powerSave?.toString() ?: "未知"}"

    private fun runtimeLine(snapshot: WorkspaceEnergyContract.Snapshot?): String = if (snapshot == null)
        "系统界面进程未响应（数据不可用）"
    else "工作台开启=${snapshot.active} 挂起=${snapshot.suspended} 小窗数=${snapshot.slotCount} " +
        "画面帧计数=${snapshot.slotFrames} 任务查询计数=${snapshot.taskQueries} " +
        "预览截取计数=${snapshot.previewCaptures}"

    private fun runtimeDelta(end: WorkspaceEnergyContract.Snapshot?): String {
        val startPid = preferences.getInt("start_pid", -1)
        if (end == null || startPid < 0 || end.processId != startPid) return "不可比较（进程重启或数据不可用）"
        fun diff(value: Long, key: String) = (value - preferences.getLong(key, value))
            .takeIf { it >= 0 }?.toString() ?: "不可用"
        return "任务查询+${diff(end.taskQueries,"start_task")} " +
            "预览截取+${diff(end.previewCaptures,"start_captures")} " +
            "画面帧+${diff(end.slotFrames,"start_frames")} " +
            "系统界面进程CPU+${diff(end.cpuMs,"start_cpu")}毫秒（非模块独占）"
    }
}
