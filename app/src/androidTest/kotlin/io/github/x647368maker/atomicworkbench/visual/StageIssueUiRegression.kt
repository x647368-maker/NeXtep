package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.github.x647368maker.atomicworkbench.config.StageIssueDiagnosticActivity
import io.github.x647368maker.atomicworkbench.config.StageIssueSession
import java.io.File

/**
 * Real Android controls, lifecycle, battery read, ordered broadcast and local
 * persistence. Run only against a fresh disposable emulator install.
 * No fabricated SystemUI reply, no report injection, no fake elapsed time.
 */
internal class StageIssueUiRegression(private val inst: Instrumentation) {
    private fun <T> ui(block: () -> T): T {
        var result: Result<T>? = null
        inst.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun descendants(root: View): List<View> {
        val children = if (root is ViewGroup)
            (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) }
        else emptyList()
        return listOf(root) + children
    }
    private fun click(activity: Activity, title: String) = ui {
        val view = descendants(activity.window.decorView).filterIsInstance<Button>()
            .firstOrNull { it.isShown && it.text.toString() == title }
            ?: error("Missing native diagnostic control: $title")
        check(view.isEnabled && view.performClick()) { "Diagnostic action rejected: $title" }
    }
    private fun hasText(activity: Activity, title: String): Boolean = ui {
        descendants(activity.window.decorView).filterIsInstance<TextView>()
            .any { it.isShown && it.text.toString() == title }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        do {
            inst.waitForIdleSync()
            if (predicate()) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out: $label")
    }
    private fun page(mode: String): Activity =
        inst.startActivitySync(Intent(inst.targetContext, StageIssueDiagnosticActivity::class.java)
            .putExtra(StageIssueDiagnosticActivity.EXTRA_CATEGORY, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { inst.waitForIdleSync() }

    fun run(out: File): String {
        val session = StageIssueSession(inst.targetContext)
        check(ui { !session.active() && session.reports().isEmpty() }) {
            "Diagnostic regression requires an empty disposable emulator; no existing reports were modified"
        }
        val activities = mutableListOf<Activity>()
        try {
            val all = page("all").also { activities.add(it) }
            await("all scope") { hasText(all, "综合诊断") && hasText(all, "开始综合诊断") }
            click(all, "5 分钟")
            click(all, "开始综合诊断")
            await("real active session") { ui { session.active() && session.selectedCategory() == "all" && session.durationMs() == 300000L } }
            val firstToken = ui { session.currentToken() }
            ui { descendants(all.window.decorView).filterIsInstance<EditText>().single().setText("Android diagnostic regression") }
            click(all, "保存说明／结束后补充")
            click(all, "标记问题（可选）")
            await("real marker") { ui { session.markersCount() == 1 } }

            val layout = page("layout").also { activities.add(it) }
            await("active session preserved across specialty link") {
                hasText(layout, "综合诊断") && ui { session.active() && session.currentToken() == firstToken }
            }
            click(layout, "提前结束并生成报告")
            await("first report persisted") { ui { !session.active() && session.latestRecord() != null } }
            val first = ui { checkNotNull(session.latestRecord()) }
            check(first.category == "all" && first.text.contains("Android diagnostic regression"))
            check(first.text.contains("手动标记：1次") && first.text.contains("开始电池：") && first.text.contains("结束电池："))
            check(!ui { session.saveNote("stale running editor", firstToken, true) })
            check(ui { session.report(first.id) } == first.text)
            await("requested specialty restored after finish") {
                hasText(layout, "窗口与布局体验") && hasText(layout, "开始专项诊断")
            }
            click(layout, "开始专项诊断")
            await("second independent real session") {
                ui { session.active() && session.selectedCategory() == "layout" && session.currentToken() != firstToken }
            }
            click(layout, "提前结束并生成报告")
            await("second persisted report") { ui { !session.active() && session.reports().size == 2 } }
            val records = ui { session.reports() }
            check(records.first().category == "layout" && records.last().id == first.id)
            check(records.first().text.contains("未选择（未测试）"))
            check(ui { session.report(first.id) } == first.text)
            check(!records.first().text.contains("Android diagnostic regression"))
            val result = "PASS actual Android diagnostics: native controls, two real sessions, specialty route restoration, marker, stale-note rejection, durable report lookup; SAF and OEM not tested"
            File(out, "diagnostic-ui-results.txt").writeText(result + "\n")
            return result
        } finally {
            // Normal Activity lifecycle; never clear pre-existing application data.
            ui { activities.asReversed().forEach { it.finish() } }
            inst.waitForIdleSync()
        }
    }
}
