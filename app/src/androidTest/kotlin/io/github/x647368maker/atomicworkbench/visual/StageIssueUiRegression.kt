package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.graphics.Bitmap
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.accessibility.AccessibilityNodeInfo
import android.os.ParcelFileDescriptor
import io.github.x647368maker.atomicworkbench.config.MainActivity
import io.github.x647368maker.atomicworkbench.config.StageHomeLayout
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

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    private fun screenshot(out: File, name: String) {
        inst.waitForIdleSync()
        val shot=checkNotNull(inst.uiAutomation.takeScreenshot()) { "No actual HWUI screenshot" }
        try { File(out,name).outputStream().use { check(shot.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { shot.recycle() }
    }
    private fun verifyHome(out: File, wide: Boolean) {
        val size=if(wide) "1920x1440" else "1272x2772"
        val density=if(wide) "320" else "512"
        shell("wm size $size")
        shell("wm density $density")
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("measured native settings ${if(wide) "wide" else "phone"}") {
                ui {
                    val content=activity.findViewById<ViewGroup>(android.R.id.content)
                    val settings=content.getChildAt(0) as? ViewGroup ?: return@ui false
                    val child=settings.getChildAt(0)
                    val measuredWide=child is LinearLayout && child.orientation==LinearLayout.HORIZONTAL &&
                        child.childCount==3 && child.getChildAt(0) is ScrollView &&
                        child.getChildAt(2) is ScrollView
                    (if(wide) measuredWide else child is ScrollView) &&
                        settings.width>0 && settings.height>0 &&
                        (if(wide) StageHomeLayout.sections.first().items else StageHomeLayout.sections.flatMap { it.items })
                            .all { item -> descendants(settings).filterIsInstance<TextView>().any { it.text.toString()==item.name } }
                }
            }
            screenshot(out,if(wide) "diagnostic-home-wide.png" else "diagnostic-home-phone.png")
            if(wide) for((index,section) in StageHomeLayout.sections.withIndex()){
                ui {
                    val target=descendants(activity.window.decorView).firstOrNull {
                        it.isClickable && it.contentDescription?.toString()==section.title
                    }?:error("Missing actual section navigation ${section.title}")
                    check(target.performClick())
                }
                await("wide section ${section.title}"){
                    ui {
                        val content=activity.findViewById<ViewGroup>(android.R.id.content)
                        val split=(content.getChildAt(0) as ViewGroup).getChildAt(0) as? ViewGroup ?: return@ui false
                        if(split.childCount!=3)return@ui false
                        val rail=descendants(split.getChildAt(0))
                        val detail=descendants(split.getChildAt(2)).filterIsInstance<TextView>()
                        StageHomeLayout.sections.all{group->rail.any{it.contentDescription?.toString()==group.title}} &&
                            rail.any{it.isSelected && it.contentDescription?.toString()==section.title} &&
                            section.items.all { item->detail.any{it.text.toString()==item.name}} &&
                            StageHomeLayout.sections.filterIndexed{i,_->i!=index}.flatMap{it.items}
                                .none { item->detail.any{it.text.toString()==item.name}}
                    }
                }
                screenshot(out,"diagnostic-home-wide-section-$index.png")
            }
        } finally { ui { activity.finish() }; inst.waitForIdleSync() }
    }
    private fun node(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if(root==null)return null
        if(predicate(root))return root
        for(i in 0 until root.childCount) node(root.getChild(i),predicate)?.let { return it }
        return null
    }
    private fun verifySaf(activity: Activity, expected: String, out: File) {
        click(activity,"导出最新TXT报告")
        await("system document picker") {
            val root=inst.uiAutomation.rootInActiveWindow
            root!=null && root.packageName?.toString()?.contains("documentsui",ignoreCase=true)==true &&
                node(root){ it.className?.toString()=="android.widget.EditText" }!=null
        }
        val editor=checkNotNull(node(inst.uiAutomation.rootInActiveWindow){
            it.className?.toString()=="android.widget.EditText"
        })
        val name="stage-diagnostic-runtime.txt"
        check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name)
        })) { "DocumentsUI filename not editable" }
        await("system save action") {
            node(inst.uiAutomation.rootInActiveWindow) {
                it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_save")==true ||
                    it.text?.toString()?.equals("Save",ignoreCase=true)==true)
            }!=null
        }
        val save=checkNotNull(node(inst.uiAutomation.rootInActiveWindow) {
            it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_save")==true ||
                it.text?.toString()?.equals("Save",ignoreCase=true)==true)
        })
        check(save.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "DocumentsUI save action not accepted" }
        var actual=""
        await("SAF report bytes written") {
            val path=shell("find /sdcard/Download /sdcard/Documents -name $name 2>/dev/null | head -n 1").trim()
            if(path.isEmpty()) false else {
                actual=shell("cat '$path'")
                actual==expected
            }
        }
        File(out,"diagnostic-exported-report.txt").writeText(actual)
    }

    fun run(out: File): String {
        val session = StageIssueSession(inst.targetContext)
        check(ui { !session.active() && session.reports().isEmpty() }) {
            "Diagnostic regression requires an empty disposable emulator; no existing reports were modified"
        }
        val activities = mutableListOf<Activity>()
        try {
            verifyHome(out,true)
            verifyHome(out,false)
            val scopes=listOf("performance" to "性能与流畅度","layout" to "窗口与布局体验",
                "content" to "应用与内容状态","compatibility" to "系统与兼容性","services" to "功能与服务")
            for((id,title) in scopes) {
                val screen=page(id)
                activities.add(screen)
                await("specialty $id") { hasText(screen,title) && hasText(screen,"开始专项诊断") }
                screenshot(out,"diagnostic-scope-$id.png")
                ui { screen.finish() };inst.waitForIdleSync()
                activities.remove(screen)
            }
            val all = page("all").also { activities.add(it) }
            await("all scope") { hasText(all, "故障测试与报告") && hasText(all, "全面综合诊断") && hasText(all, "开始综合诊断") }
            check(hasText(all,"展开高级诊断详情"))
            click(all,"展开高级诊断详情")
            check(hasText(all,"收起高级诊断详情"))
            click(all,"收起高级诊断详情")
            screenshot(out,"diagnostic-scope-all.png")
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
                hasText(layout, "故障测试与报告") && hasText(layout,"全面综合诊断") && ui { session.active() && session.currentToken() == firstToken }
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
            verifySaf(layout,records.first().text,out)
            val expiry=page("performance").also { activities.add(it) }
            click(expiry,"5 分钟")
            click(expiry,"开始专项诊断")
            await("actual time-limited test started") { ui { session.active() && session.durationMs()==300000L } }
            val expiryToken=ui { session.currentToken() }
            val deadline=SystemClock.elapsedRealtime()+330000L
            // Five real minutes on the disposable emulator. No shortened
            // production duration, fake clock, preference injection or test alarm.
            while(ui { session.active() } && SystemClock.elapsedRealtime()<deadline) Thread.sleep(1000)
            check(!ui { session.active() }) { "Real automatic expiry failed" }
            check(SystemClock.elapsedRealtime()-expiryToken>=300000L)
            val expired=ui { checkNotNull(session.latestRecord()) }
            check(expired.category=="performance" && expired.text.contains("到期"))
            check(ui { session.reports().size==3 })
            File(out,"diagnostic-expiry-report.txt").writeText(expired.text)
            val result = "PASS actual Android diagnostics: narrow/wide native home, six scopes, real sessions, marker, stale-note rejection, history, SAF text bytes, real five-minute expiration; OEM not tested"
            File(out, "diagnostic-ui-results.txt").writeText(result + "\n")
            return result
        } finally {
            // Normal Activity lifecycle; never clear pre-existing application data.
            ui { activities.asReversed().forEach { it.finish() } }
            inst.waitForIdleSync()
        }
    }
}
