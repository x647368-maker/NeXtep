package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private lateinit var evidence: File
    private val main=Handler(Looper.getMainLooper())
    private fun phase(label:String) {
        val message="${SystemClock.elapsedRealtime()} $label"
        Log.i("StageDiagnosticTest",message)
        if(::evidence.isInitialized) evidence.appendText(message+"\n")
        inst.sendStatus(0,Bundle().apply {putString("stream","\n$message\n")})
    }
    private fun <T> ui(block: () -> T): T {
        check(Looper.myLooper()!=Looper.getMainLooper())
        var result: Result<T>? = null
        val done=CountDownLatch(1)
        val action=Runnable { try {result=runCatching(block)} finally {done.countDown()} }
        check(main.post(action)){"Main thread unavailable"}
        if(!done.await(12,TimeUnit.SECONDS)){
            main.removeCallbacks(action)
            error("Main-thread action timed out")
        }
        return checkNotNull(result).getOrThrow()
    }
    private fun awaitActivity(
        clazz: Class<out Activity>, label:String, matches:(Activity)->Boolean={true},
        launch:()->Unit
    ):Activity {
        phase("BEGIN launch $label")
        val app=inst.targetContext.applicationContext as Application
        val resumed=CountDownLatch(1)
        var found:Activity?=null
        val callback=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:Activity,b:Bundle?){}
            override fun onActivityStarted(a:Activity){}
            override fun onActivityResumed(a:Activity){
                if(found==null && clazz.isInstance(a) && matches(a) && !a.isFinishing){
                    found=a
                    resumed.countDown()
                }
            }
            override fun onActivityPaused(a:Activity){}
            override fun onActivityStopped(a:Activity){}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle){}
            override fun onActivityDestroyed(a:Activity){}
        }
        try {
            ui { app.registerActivityLifecycleCallbacks(callback); launch() }
            check(resumed.await(15,TimeUnit.SECONDS)) {
                "No matching resumed Activity: $label (possible task reuse or failed launch)"
            }
            val activity=checkNotNull(found)
            check(ui{!activity.isDestroyed && !activity.isFinishing})
            phase("PASS launch $label instance=${System.identityHashCode(activity)} task=${activity.taskId}")
            return activity
        } finally {ui {app.unregisterActivityLifecycleCallbacks(callback)}}
    }
    private fun close(activity:Activity){
        ui{if(!activity.isFinishing && !activity.isDestroyed)activity.finish()}
        await("destroyed ${activity.javaClass.simpleName}"){ui{activity.isDestroyed}}
    }
    private fun clickLabel(activity:Activity,title:String) = ui {
        val text=descendants(activity.window.decorView).filterIsInstance<TextView>()
            .firstOrNull{it.isShown && it.text.toString()==title}
            ?: error("Missing real navigation label: $title")
        var target: View?=text
        while(target!=null && !target.isClickable)target=target.parent as? View
        val action=checkNotNull(target){"No clickable route: $title"}
        check(action.isEnabled && action.performClick()){"Navigation rejected: $title"}
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
            if (predicate()) { phase("PASS $label");return }
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Timed out: $label")
    }
    private fun page(mode: String, from:Activity?=null): Activity =
        awaitActivity(StageIssueDiagnosticActivity::class.java,"diagnostic $mode",
            { it!==from && it.intent.getStringExtra(StageIssueDiagnosticActivity.EXTRA_CATEGORY)==mode }) {
            val intent=Intent(inst.targetContext,StageIssueDiagnosticActivity::class.java)
                .putExtra(StageIssueDiagnosticActivity.EXTRA_CATEGORY,mode)
            // A test-context launch must create a fresh task. From an actual
            // Activity use the normal production startActivity contract.
            if(from!=null)from.startActivity(intent)
            else inst.targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    private fun screenshot(out: File, name: String) {
        ui { Unit }
        val shot=checkNotNull(inst.uiAutomation.takeScreenshot()) { "No actual HWUI screenshot" }
        try { File(out,name).outputStream().use { check(shot.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { shot.recycle() }
    }
    private fun verifyHome(out: File, wide: Boolean, size:String, density:String, name:String, navigation:Boolean=false) {
        phase("BEGIN viewport $name $size density=$density")
        shell("wm size $size")
        shell("wm density $density")
        val activity=awaitActivity(MainActivity::class.java,"home $name"){
            inst.targetContext.startActivity(Intent(inst.targetContext,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
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
            screenshot(out,"diagnostic-home-$name.png")
            if(wide && navigation) for((index,section) in StageHomeLayout.sections.withIndex()){
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
            if(navigation) {
                if(wide)clickLabel(activity,"故障诊断")
                clickLabel(activity,"专项测试与报告")
                await("confirmed diagnostic landing"){
                    hasText(activity,"故障测试与报告") && hasText(activity,"立即诊断") &&
                    listOf("性能与流畅度","窗口与布局体验","应用与内容状态","系统与兼容性","功能与服务","历史报告").all{hasText(activity,it)}
                }
                screenshot(out,"diagnostic-landing-$name.png")
                val all=awaitActivity(StageIssueDiagnosticActivity::class.java,"real landing entry"){
                    val button=descendants(activity.window.decorView).filterIsInstance<Button>()
                        .firstOrNull{it.isShown && it.text.toString()=="立即诊断"}?:error("Missing real comprehensive entry")
                    check(button.performClick())
                }
                try { await("landing opens actual comprehensive page"){hasText(all,"全面综合诊断")} }
                finally {close(all)}
            }
        } finally { close(activity) }
    }
    private fun node(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if(root==null)return null
        if(predicate(root))return root
        for(i in 0 until root.childCount) node(root.getChild(i),predicate)?.let { return it }
        return null
    }
    private fun verifySaf(activity: Activity, expected: String, out: File) {
        phase("BEGIN SAF cancel and export")
        click(activity,"导出最新TXT报告")
        await("first system document picker"){inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui",true)==true}
        shell("input keyevent 4")
        await("export cancellation restores button"){ui{descendants(activity.window.decorView).filterIsInstance<Button>().any{it.text.toString()=="导出最新TXT报告" && it.isEnabled}}}
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
            val path=shell("find /sdcard/Download /sdcard/Documents /sdcard/Pictures -name $name 2>/dev/null | head -n 1").trim()
            if(path.isEmpty()) false else {
                actual=shell("cat '$path'")
                actual==expected
            }
        }
        File(out,"diagnostic-exported-report.txt").writeText(actual)
        phase("PASS exact SAF text bytes, characters=${actual.length}")
    }

    fun run(out: File): String {
        evidence=File(out,"diagnostic-phase-log.txt")
        phase("BEGIN diagnostic suite SDK=${android.os.Build.VERSION.SDK_INT}")
        val session = StageIssueSession(inst.targetContext)
        check(ui { !session.active() && session.reports().isEmpty() }) {
            "Diagnostic regression requires an empty disposable emulator; no existing reports were modified"
        }
        val activities = mutableListOf<Activity>()
        try {
            verifyHome(out,true,"1920x1440","320","wide",true)
            verifyHome(out,true,"2248x2268","420","fold-inner-n3-shape")
            verifyHome(out,true,"2480x2268","480","fold-inner-n5-shape")
            verifyHome(out,false,"1600x2560","400","tablet-portrait")
            verifyHome(out,true,"2560x1600","400","tablet-landscape")
            verifyHome(out,false,"1140x2520","480","fold-outer")
            verifyHome(out,false,"1272x2772","512","phone",true)
            val scopes=listOf("performance" to "性能与流畅度","layout" to "窗口与布局体验",
                "content" to "应用与内容状态","compatibility" to "系统与兼容性","services" to "功能与服务")
            for((id,title) in scopes) {
                val screen=page(id)
                activities.add(screen)
                await("specialty $id") { hasText(screen,title) && hasText(screen,"开始专项诊断") }
                screenshot(out,"diagnostic-scope-$id.png")
                close(screen)
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

            phase("BEGIN cross-entry with existing real session")
            val layout = page("layout",all).also { activities.add(it) }
            check(layout!==all) {"Expected a distinct resumed specialty Activity in same task"}
            check(layout.taskId==all.taskId) {"Cross-entry must use original activity task"}
            await("active session preserved across specialty link") {
                hasText(layout, "故障测试与报告") && hasText(layout,"全面综合诊断") && ui { session.active() && session.currentToken() == firstToken }
            }
            click(layout, "提前结束并生成报告")
            await("first report persisted") { ui { !session.active() && session.latestRecord() != null } }
            val first = ui { checkNotNull(session.latestRecord()) }
            File(out,"diagnostic-first-report.txt").writeText(first.text)
            phase("PASS first report persisted")
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
            File(out,"diagnostic-second-report.txt").writeText(records.first().text)
            check(records.first().category == "layout" && records.last().id == first.id)
            check(records.first().text.contains("未选择（未测试）"))
            check(ui { session.report(first.id) } == first.text)
            check(!records.first().text.contains("Android diagnostic regression"))
            phase("PASS real report history and session isolation")
            verifySaf(layout,records.first().text,out)
            // Close both prior editors before starting the independent alarm test.
            close(layout);activities.remove(layout)
            close(all);activities.remove(all)
            val expiry=page("performance").also { activities.add(it) }
            click(expiry,"5 分钟")
            click(expiry,"开始专项诊断")
            await("actual time-limited test started") { ui { session.active() && session.durationMs()==300000L } }
            val expiryToken=ui { session.currentToken() }
            check(ui{session.alarmScheduled()}){"Actual non-wakeup alarm was not scheduled"}
            close(expiry);activities.remove(expiry)
            shell("input keyevent 3")
            val deadline=expiryToken+420000L
            // Keep all diagnostic Activities destroyed: only the real one-shot
            // OS alarm may end this session. No fake time or test alarm.
            phase("BEGIN five-minute background OS alarm, no diagnostic Activity")
            while(ui { session.active() } && SystemClock.elapsedRealtime()<deadline) Thread.sleep(1000)
            check(!ui { session.active() }) { "Real background automatic expiry failed within 120s allowance" }
            check(SystemClock.elapsedRealtime()-expiryToken>=300000L)
            val expired=ui { checkNotNull(session.latestRecord()) }
            check(expired.category=="performance" && expired.text.contains("到期"))
            check(ui { session.reports().size==3 })
            phase("PASS genuine background expiration elapsed=${SystemClock.elapsedRealtime()-expiryToken}ms")
            File(out,"diagnostic-expiry-report.txt").writeText(expired.text)
            val result = "PASS actual Android diagnostics: narrow/wide native home, six scopes, real sessions, marker, stale-note rejection, history, SAF text bytes, real five-minute background OS alarm; OEM not tested"
            File(out, "diagnostic-ui-results.txt").writeText(result + "\n")
            return result
        } finally {
            // Normal Activity lifecycle; never clear pre-existing application data.
            ui { activities.asReversed().forEach { if(!it.isDestroyed)it.finish() } }
        }
    }
}
