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
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.util.Base64
import java.security.MessageDigest
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
import io.github.x647368maker.atomicworkbench.config.StageIssueHistoryPolicy
import io.github.x647368maker.atomicworkbench.config.StageIssueTestCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private fun click(activity: Activity, title: String) {
        val view=ui {
            val button=descendants(activity.window.decorView).filterIsInstance<Button>()
                .firstOrNull{it.isShown && it.text.toString()==title}
                ?:error("Missing native diagnostic control: $title")
            check(button.isEnabled){"Diagnostic action disabled: $title"}
            // The long native scope and report cards are scrollable. A direct
            // performClick would pass even if a user could not reach the button.
            button.requestRectangleOnScreen(Rect(0,0,button.width,button.height),true)
            button
        }
        var point:Pair<Float,Float>?=null
        await("touchable $title") {
            point=ui {
                if(!activity.hasWindowFocus() || !view.isEnabled || !view.isAttachedToWindow ||
                    view.width<=0 || view.height<=0) return@ui null
                val local=Rect()
                if(!view.getLocalVisibleRect(local) || local.width()<view.width*.5f ||
                    local.height()<view.height*.5f) return@ui null
                val position=IntArray(2);view.getLocationOnScreen(position)
                val x=position[0]+local.exactCenterX()
                val y=position[1]+local.exactCenterY()
                val window=Rect();activity.window.decorView.getWindowVisibleDisplayFrame(window)
                if(!window.contains(x.toInt(),y.toInt()))null else x to y
            }
            point!=null
        }
        val (x,y)=checkNotNull(point)
        val down=SystemClock.uptimeMillis()
        fun event(action:Int){
            val motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            try{
                motion.source=InputDevice.SOURCE_TOUCHSCREEN
                check(inst.uiAutomation.injectInputEvent(motion,true)){"InputDispatcher rejected $title"}
            }finally{motion.recycle()}
        }
        event(MotionEvent.ACTION_DOWN)
        Thread.sleep(45)
        event(MotionEvent.ACTION_UP)
        phase("PASS dispatched real touch $title at $x,$y")
        // Drain posted UI callbacks without an unbounded waitForIdleSync.
        ui{Unit}
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
    // Sparse, test-only OS evidence. Do not wake the device, change standby
    // policy, trigger the receiver, or poll AlarmManager in the background.
    private fun alarmEvidence(out:File,label:String,token:Long){
        val pkg=inst.targetContext.packageName
        val stamp=SystemClock.elapsedRealtime()
        val report=File(out,"diagnostic-alarm-$label.txt")
        report.writeText("elapsed=$stamp token=$token planned=${token+300000L}\n")
        for(command in listOf("dumpsys alarm","dumpsys deviceidle","dumpsys power",
            "am get-standby-bucket $pkg","dumpsys activity broadcasts")){
            report.appendText("\n===== $command =====\n")
            val captured=runCatching{shell(command)}.getOrElse{"unavailable: ${it.message}"}
            report.appendText(captured+"\n")
            if(command=="dumpsys alarm"){
                val lines=captured.lineSequence().filter{
                    it.contains(pkg) || it.contains("ISSUE_TIMEOUT") || it.contains("stageissue://deadline")
                }.take(40).toList()
                phase("OS alarm evidence $label: ${lines.joinToString(" | ").take(1800)}")
            }
        }
    }
    private fun layoutEvidence(activity:Activity,comprehensive:Boolean){
        ui {
            val nodes=descendants(activity.window.decorView).filterIsInstance<TextView>()
            fun y(text:String):Int {
                val view=nodes.firstOrNull{it.isShown && it.text.toString()==text}
                    ?:error("Missing original-design layout element: $text")
                return IntArray(2).also{view.getLocationOnScreen(it)}[1]
            }
            val duration=y("5 分钟")
            val first=y(if(comprehensive)"窗口交换与动画" else "卡顿")
            val start=y(if(comprehensive)"开始综合诊断" else "开始专项诊断")
            val details=y("展开高级诊断详情")
            check((if(comprehensive) duration<first else first<duration) &&
                start>maxOf(duration,first) && details>start){"Wrong original-design control order"}
        }
        phase("PASS original-design control order comprehensive=$comprehensive")
    }
    // Capture actual compositor pixels only after the intended Activity has
    // focus and the entry transition has settled. Never draw a View to a fake bitmap.
    private fun screenshot(out: File, name: String, activity:Activity) {
        await("focused rendered window $name"){ui{activity.hasWindowFocus() &&
            activity.window.decorView.isShown && !activity.isFinishing}}
        Thread.sleep(850)
        fun sample(b:Bitmap):IntArray {
            val cols=64;val rows=96
            return IntArray(cols*rows){i->
                val x=((i%cols+0.5)*b.width/cols).toInt().coerceIn(0,b.width-1)
                val y=((i/cols+0.5)*b.height/rows).toInt().coerceIn(0,b.height-1)
                b.getPixel(x,y)
            }
        }
        var previous:IntArray?=null
        val deadline=SystemClock.elapsedRealtime()+5000L
        while(SystemClock.elapsedRealtime()<deadline) {
            val shot=checkNotNull(inst.uiAutomation.takeScreenshot()){"No actual HWUI screenshot"}
            try{
                val values=sample(shot)
                val interior=values.filterIndexed{i,_->i/64 in 8..87}
                val varied=interior.map{it and 0x00F0F0F0}.toSet().size>=10
                val old=previous
                val stable=old!=null && old.indices.count{i->old[i]!=values[i]}<=values.size/25
                if(varied && stable && ui{activity.hasWindowFocus()}){
                    File(out,name).outputStream().use{check(shot.compress(Bitmap.CompressFormat.PNG,100,it))}
                    phase("PASS settled compositor screenshot $name")
                    return
                }
                previous=values
            }finally{shot.recycle()}
            Thread.sleep(300)
        }
        error("No stable non-blank focused compositor image: $name")
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
            screenshot(out,"diagnostic-home-$name.png",activity)
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
                screenshot(out,"diagnostic-home-wide-section-$index.png",activity)
            }
            if(navigation) {
                if(wide)clickLabel(activity,"故障诊断")
                clickLabel(activity,"专项测试与报告")
                await("confirmed diagnostic landing"){
                    hasText(activity,"故障测试与报告") && hasText(activity,"立即诊断") &&
                    listOf("性能与流畅度","窗口与布局体验","应用与内容状态","系统与兼容性","功能与服务","历史报告").all{hasText(activity,it)}
                }
                screenshot(out,"diagnostic-landing-$name.png",activity)
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
        verifyOpenPicker(expected,out,"latest")
    }
    private fun verifyOpenPicker(expected:String,out:File,kind:String) {
        await("system document picker $kind") {
            val root=inst.uiAutomation.rootInActiveWindow
            root!=null && root.packageName?.toString()?.contains("documentsui",ignoreCase=true)==true &&
                node(root){ it.className?.toString()=="android.widget.EditText" }!=null
        }
        val editor=checkNotNull(node(inst.uiAutomation.rootInActiveWindow){
            it.className?.toString()=="android.widget.EditText"
        })
        val name="stage-diagnostic-$kind.txt"
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
        // UiAutomation executes a command, not a shell program. The old
        // pipeline/redirect/quoted cat could fail even when DocumentsUI created
        // the file. Read the actual bytes through a simple base64 command.
        val wanted=expected.toByteArray(Charsets.UTF_8)
        fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val directories=listOf("Download","Documents","Pictures")
        val deadline=SystemClock.elapsedRealtime()+20000L
        var actual:ByteArray?=null
        var location=""
        while(SystemClock.elapsedRealtime()<deadline) {
            for(dir in directories){
                val path="/storage/emulated/0/$dir/$name"
                val encoded=shell("base64 $path").trim()
                if(encoded.isEmpty())continue
                val bytes=runCatching{Base64.decode(encoded,Base64.DEFAULT)}.getOrNull()?:continue
                actual=bytes;location=path
                File(out,"diagnostic-export-$kind-observed.bin").writeBytes(bytes)
                if(bytes.contentEquals(wanted)){
                    File(out,if(kind=="latest") "diagnostic-exported-report.txt" else "diagnostic-history-exported-report.txt").writeBytes(bytes)
                    phase("PASS exact SAF bytes path=$path size=${bytes.size} SHA256=${digest(bytes)}")
                    return
                }
            }
            Thread.sleep(300)
        }
        error("SAF $kind bytes mismatch expected=${wanted.size}/${digest(wanted)}, actual=${actual?.size}/${actual?.let{digest(it)}} location=$location")
    }

    private fun verifyHistoryExport(activity:Activity, first:StageIssueHistoryPolicy.Record, latest:StageIssueHistoryPolicy.Record, out:File) {
        phase("BEGIN archived report dialog and export")
        check(first.id!=latest.id && first.text!=latest.text)
        val format=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault())
        fun title(record:StageIssueHistoryPolicy.Record)=format.format(Date(record.startedAt))+" · "+StageIssueTestCatalog.find(record.category).name
        fun activeNode(pred:(AccessibilityNodeInfo)->Boolean)=node(inst.uiAutomation.rootInActiveWindow,pred)
        fun exact(text:String)=activeNode{it.text?.toString()==text}
        fun press(n:AccessibilityNodeInfo){
            var target:AccessibilityNodeInfo?=n
            while(target!=null && !target.isClickable)target=target.parent
            val clickTarget=checkNotNull(target){"No clickable historical entry"}
            check(clickTarget.isEnabled && clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        click(activity,"查看历史报告（最近10份）")
        await("history dialog displays both report titles"){
            exact("诊断历史（最近10份）")!=null && exact(title(first))!=null && exact(title(latest))!=null
        }
        press(checkNotNull(exact(title(first))))
        await("archived report dialog content"){
            exact(title(first))!=null && exact(first.text)!=null && exact("导出TXT")!=null
        }
        phase("PASS exact archived dialog text")
        press(checkNotNull(exact("导出TXT")))
        verifyOpenPicker(first.text,out,"archive")
        await("latest report remains intact after archived export"){
            ui {
                val reopened=StageIssueSession(inst.targetContext)
                reopened.latestRecord()?.id==latest.id &&
                    reopened.report(first.id)==first.text && reopened.report(latest.id)==latest.text
            }
        }
    }

    /** UI-only capture path. Does not start a diagnostic session or touch alarms/reports. */
    fun runVisualEvidence(out:File):String {
        evidence=File(out,"ui-visual-evidence-log.txt")
        phase("BEGIN stable Apple-style home and secondary-page visual evidence")
        val activity=awaitActivity(MainActivity::class.java,"visual-only settings home") {
            inst.targetContext.startActivity(Intent(inst.targetContext,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
        fun homeScroll()=checkNotNull(descendants(activity.window.decorView).filterIsInstance<ScrollView>().firstOrNull()) {
            "Missing real native home ScrollView"
        }
        fun visibleText(value:String):Boolean=ui {
            val view=descendants(activity.window.decorView).filterIsInstance<TextView>()
                .firstOrNull{it.text?.toString()==value}?:return@ui false
            val rect=Rect()
            view.isShown && view.getGlobalVisibleRect(rect) && rect.height()>=minOf(view.height,(view.height*0.8f).toInt().coerceAtLeast(1))
        }
        fun returnHome(){
            val back=ui {
                descendants(activity.window.decorView).firstOrNull {
                    it.isShown && it.contentDescription?.toString()=="返回" && it.isClickable
                }?:error("Missing real secondary-page back control")
            }
            check(ui{back.isEnabled && back.performClick()}){"Secondary-page back rejected"}
            await("return to real phone home"){hasText(activity,"台前调度") && hasText(activity,"窗口布局")}
        }
        try {
            await("real native phone home"){hasText(activity,"台前调度") &&
                hasText(activity,"窗口布局") && hasText(activity,"一键综合诊断")}
            screenshot(out,"ui-home-phone-top.png",activity)
            val homeScroller=ui{homeScroll()}
            val contentHeight=ui{homeScroller.getChildAt(0)?.height?:0}
            val viewportHeight=ui{homeScroller.height}
            phase("HOME scroll before y=${ui{homeScroller.scrollY}} content=$contentHeight viewport=$viewportHeight")
            ui {
                homeScroller.isFocusableInTouchMode=true
                homeScroller.requestFocus()
                homeScroller.scrollTo(0,(contentHeight-viewportHeight).coerceAtLeast(0))
                homeScroller.fullScroll(View.FOCUS_DOWN)
            }
            Thread.sleep(350)
            phase("HOME scroll after y=${ui{homeScroller.scrollY}} max=${ui{(homeScroller.getChildAt(0)?.height?:0)-homeScroller.height}} about=${visibleText("关于与使用帮助")} credit=${visibleText("ZMW小武 × GPT")} origin=${visibleText("基于 NeXtep 二次开发")}")
            await("home bottom rows and original attribution fully visible"){
                visibleText("关于与使用帮助") && visibleText("二次开发：ZMW小武 × GPT") &&
                    visibleText("基于 NeXtep 二次开发")
            }
            screenshot(out,"ui-home-phone-bottom.png",activity)
            phase("PASS phone home bottom includes final support row, credits and original-project attribution")
            val pages=listOf(
                "windows" to "窗口布局",
                "core" to "影音与快捷栏",
                "groups" to "工作组",
                "gestures" to "启动手势",
                "tile" to "快捷开关",
                "feedback" to "建议与反馈",
                "about" to "关于与使用帮助"
            )
            for((route,title) in pages){
                ui{homeScroll().fullScroll(if(route=="feedback" || route=="about") View.FOCUS_DOWN else View.FOCUS_UP)}
                await("home navigation target visible $title"){visibleText(title)}
                clickLabel(activity,title)
                await("real secondary page $route"){hasText(activity,title)}
                screenshot(out,"ui-secondary-$route.png",activity)
                phase("PASS stable secondary page $route")
                returnHome()
            }
            ui{homeScroll().fullScroll(View.FOCUS_UP)}
            clickLabel(activity,"专项测试与报告")
            await("diagnostic center landing actual page"){
                hasText(activity,"故障测试与报告") && hasText(activity,"立即诊断") &&
                    listOf("性能与流畅度","窗口与布局体验","应用与内容状态","系统与兼容性","功能与服务","历史报告").all{hasText(activity,it)}
            }
            screenshot(out,"ui-diagnostic-center-landing.png",activity)
            val comprehensive=awaitActivity(StageIssueDiagnosticActivity::class.java,"visual-only comprehensive diagnostic"){
                click(activity,"立即诊断")
            }
            try{
                await("comprehensive diagnostic original layout"){
                    hasText(comprehensive,"全面综合诊断") && hasText(comprehensive,"开始综合诊断")
                }
                layoutEvidence(comprehensive,true)
                screenshot(out,"ui-diagnostic-center-comprehensive.png",comprehensive)
            }finally{close(comprehensive)}
            val result="PASS actual Android UI visual evidence: phone home top/bottom, 7 native secondary pages, diagnostic center landing and comprehensive screen"
            File(out,"ui-visual-evidence-results.txt").writeText(result+"\n")
            phase(result)
            return result
        }finally{close(activity)}
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
                if(id=="performance")layoutEvidence(screen,false)
                screenshot(out,"diagnostic-scope-$id.png",screen)
                close(screen)
                activities.remove(screen)
            }
            val all = page("all").also { activities.add(it) }
            await("all scope") { hasText(all, "故障测试与报告") && hasText(all, "全面综合诊断") && hasText(all, "开始综合诊断") }
            check(hasText(all,"展开高级诊断详情"))
            layoutEvidence(all,true)
            screenshot(out,"diagnostic-scope-all.png",all)
            click(all,"展开高级诊断详情")
            check(hasText(all,"收起高级诊断详情"))
            click(all,"收起高级诊断详情")
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
            val safFailure=runCatching{verifySaf(layout,records.first().text,out)}.exceptionOrNull()
            if(safFailure!=null)phase("FAIL SAF: ${safFailure.message}; continue independent expiry test")
            val historyFailure=if(safFailure==null)runCatching{verifyHistoryExport(layout,first,records.first(),out)}.exceptionOrNull()
                else IllegalStateException("Latest file export failed; archived export not verified")
            if(historyFailure!=null)phase("FAIL archived export: ${historyFailure.message}")
            // Close both prior editors before starting the independent alarm test.
            close(layout);activities.remove(layout)
            close(all);activities.remove(all)
            val expiry=page("performance").also { activities.add(it) }
            click(expiry,"5 分钟")
            click(expiry,"开始专项诊断")
            await("actual time-limited test started") { ui { session.active() && session.durationMs()==300000L } }
            val expiryToken=ui { session.currentToken() }
            check(ui{session.alarmScheduled()}){"Actual non-wakeup alarm was not scheduled"}
            alarmEvidence(out,"scheduled",expiryToken)
            close(expiry);activities.remove(expiry)
            shell("input keyevent 3")
            val deadline=expiryToken+420000L
            // Keep all diagnostic Activities destroyed: only the real one-shot
            // OS alarm may end this session. No fake time or test alarm.
            phase("BEGIN five-minute background OS alarm, no diagnostic Activity")
            while(ui { session.active() } && SystemClock.elapsedRealtime()<deadline) Thread.sleep(1000)
            val delivered=ui{!session.active()}
            alarmEvidence(out,if(delivered)"completed" else "deadline-missed",expiryToken)
            if(!delivered){
                // Preserve the original strict background gate. Explicit
                // re-entry is a separate product recovery check, never a pass
                // for the natural background alarm.
                phase("FAIL natural background alarm; checking explicit overdue recovery separately")
                val reopened=page("performance").also{activities.add(it)}
                await("overdue report recovered on real page re-entry"){ui{!session.active() && session.latestRecord()!=null}}
                val recovered=ui{checkNotNull(session.latestRecord())}
                check(recovered.category=="performance" && recovered.text.contains("到期"))
                check(ui{session.reports().size==3})
                File(out,"diagnostic-overdue-recovery-report.txt").writeText(recovered.text)
                close(reopened);activities.remove(reopened)
                error("Real background automatic expiry failed within 120s allowance; explicit re-entry recovery verified separately")
            }
            check(SystemClock.elapsedRealtime()-expiryToken>=300000L)
            val expired=ui { checkNotNull(session.latestRecord()) }
            check(expired.category=="performance" && expired.text.contains("到期"))
            check(ui { session.reports().size==3 })
            phase("PASS genuine background expiration elapsed=${SystemClock.elapsedRealtime()-expiryToken}ms")
            File(out,"diagnostic-expiry-report.txt").writeText(expired.text)
            if(safFailure!=null)throw IllegalStateException("SAF failed; independent expiry completed",safFailure)
            if(historyFailure!=null)throw IllegalStateException("Archived report export failed; independent expiry completed",historyFailure)
            val result = "PASS actual Android diagnostics: narrow/wide native home, six scopes, real sessions, marker, stale-note rejection, history, latest and archived SAF text bytes, real five-minute background OS alarm; OEM not tested"
            File(out, "diagnostic-ui-results.txt").writeText(result + "\n")
            return result
        } finally {
            // Normal Activity lifecycle; never clear pre-existing application data.
            ui { activities.asReversed().forEach { if(!it.isDestroyed)it.finish() } }
        }
    }
}
