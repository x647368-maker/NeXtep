package io.github.x647368maker.atomicworkbench.config

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.x647368maker.atomicworkbench.monitoring.BatterySample
import io.github.x647368maker.atomicworkbench.monitoring.WorkspaceEnergyContract

/**
 * Manual opt-in. No periodic sampling: a single foreground UI deadline callback
 * and one OS non-wakeup alarm are the only time-based operations.
 */
class StageIssueDiagnosticActivity : AppCompatActivity() {
    private val session by lazy { StageIssueSession(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiDeadline = Runnable { if (session.active() && session.isDue()) finishTest("到期（测试页面）") else render() }

    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var category: Spinner
    private lateinit var duration: Spinner
    private lateinit var note: EditText
    private lateinit var startButton: Button
    private lateinit var markButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveNoteButton: Button
    private lateinit var exportButton: Button
    private var pending = false
    private val durationValues = StageIssueReportPolicy.DURATION_OPTIONS_MS

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val report = session.latestReport()
        if (report.isBlank()) { toast("还没有报告，请先结束测试"); return@registerForActivityResult }
        runCatching {
            checkNotNull(contentResolver.openOutputStream(uri)) { "保存位置无法打开" }.use {
                it.write(report.toByteArray(Charsets.UTF_8))
            }
        }.onSuccess { toast("报告保存成功，分享前请检查隐私") }
            .onFailure { toast("保存失败，请重试") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window,false)
        val density=resources.displayMetrics.density
        fun dp(n:Int)=(density*n).toInt()
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(18),dp(20),dp(34))
        }
        fun line(label:String,size:Float=14f)=TextView(this).apply {
            text=label
            textSize=size
            setPadding(0,dp(6),0,dp(8))
        }

        body.addView(line("故障测试与报告",24f).apply{typeface=Typeface.DEFAULT_BOLD})
        body.addView(line("一键开始 → 正常使用 → 到期自动结束 → 回来导出。期间不持续录屏、扫描或唤醒手机。深度休眠时到期报告可能延后生成。"))
        body.addView(line("选择问题类别和操作说明",16f))
        category=Spinner(this)
        category.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            StageIssueTestCatalog.cases.map{it.name})
        body.addView(category,LinearLayout.LayoutParams(-1,dp(50)))
        instruction=line("")
        body.addView(instruction)
        category.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                if (::instruction.isInitialized) {
                    val chosen=if (session.active()) session.selectedCategory()
                        else StageIssueTestCatalog.cases.getOrNull(position)?.id
                    instruction.text=StageIssueTestCatalog.guide(chosen)
                }
            }
            override fun onNothingSelected(parent:AdapterView<*>?) {}
        }

        body.addView(line("测试时长（自动结束）",16f))
        duration=Spinner(this)
        duration.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            durationValues.map{ "${it/60_000} 分钟" })
        duration.setSelection(1)
        body.addView(duration,LinearLayout.LayoutParams(-1,dp(50)))
        body.addView(line("测试期间会利用系统现有通知，尽力记录亮屏、息屏、解锁，以及测试App切到前台/后台。工作台是否开启/挂起会在开始和结束时分别读取；不持续轮询。系统杀掉本App后可能漏记。"))
        body.addView(line("自动结束是一次性、非唤醒型提醒：手机睡着时不会为测试强行叫醒它；系统可能稍晚生成报告。重新进入本页也会自动处理过期会话。"))

        status=line("")
        body.addView(status)
        startButton=Button(this).apply{text="开始自动测试";setOnClickListener{start()}}
        markButton=Button(this).apply{text="标记问题（可选）";setOnClickListener{mark()}}
        stopButton=Button(this).apply{text="提前结束并生成报告";setOnClickListener{finishTest("用户提前结束")}}
        for (b in listOf(startButton,markButton,stopButton))
            body.addView(b,LinearLayout.LayoutParams(-1,-2))

        body.addView(line("问题描述（可选，最多400字）",16f))
        note=EditText(this).apply{
            hint="例如：N5展开后右边小窗遮挡；恢复后仍未正常"
            minLines=3
            maxLines=6
            filters=arrayOf(InputFilter.LengthFilter(StageIssueReportPolicy.MAX_DESCRIPTION_CHARS))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            if(session.active())setText(session.storedNote())
        }
        body.addView(note,LinearLayout.LayoutParams(-1,-2))
        saveNoteButton=Button(this).apply{text="保存说明／结束后补充";setOnClickListener{
            session.saveNote(note.text.toString())
            toast("说明已保存到本机报告")
        }}
        body.addView(saveNoteButton,LinearLayout.LayoutParams(-1,-2))
        exportButton=Button(this).apply{text="导出TXT报告";setOnClickListener{
            if(session.latestReport().isNotBlank())
                saveDocument.launch("台前调度-故障报告.txt")
        }}
        body.addView(exportButton,LinearLayout.LayoutParams(-1,-2))
        body.addView(line("报告含机型、系统、测试前后电量温度、小窗数量、运行计数及结束时最近五次动画交换诊断，以及你选择的类别／说明。不含完整崩溃堆栈，不能单独证明软件耗电多少。报告不会自动上传。"))
        body.addView(Button(this).apply{text="返回台前调度";setOnClickListener{finish()}})
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(body)}
        ViewCompat.setOnApplyWindowInsetsListener(scroll){ view,insets ->
            val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left,safe.top,safe.right,maxOf(safe.bottom,keyboard.bottom))
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        render()
    }

    override fun onResume(){
        super.onResume()
        if(!::status.isInitialized)return
        if(session.active()){
            category.setSelection(StageIssueTestCatalog.cases.indexOfFirst{
                it.id==session.selectedCategory()
            }.coerceAtLeast(0))
            duration.setSelection(durationValues.indexOf(session.durationMs()).coerceAtLeast(0))
            if(note.text.isNullOrBlank() && session.storedNote().isNotBlank())
                note.setText(session.storedNote())
        }
        if (session.active()) StageIssueEventObserver.enable(this)
        checkDueAndArmUi()
        render()
    }
    override fun onPause(){
        uiHandler.removeCallbacks(uiDeadline)
        if(::note.isInitialized && session.active())session.saveNote(note.text.toString())
        super.onPause()
    }
    override fun onDestroy(){uiHandler.removeCallbacks(uiDeadline);super.onDestroy()}

    private fun start(){
        if(pending || session.active())return
        if(session.latestReport().isNotBlank()){
            MaterialAlertDialogBuilder(this).setTitle("开启新测试？")
                .setMessage("上次的报告还存在本机。开始新测试会覆盖旧报告，请先导出需要保留的内容。")
                .setNegativeButton("取消",null)
                .setPositiveButton("覆盖并开始"){_,_->begin()}
                .show()
        } else begin()
    }
    private fun begin(){
        if(pending || session.active())return
        val chosen=StageIssueTestCatalog.cases.getOrNull(category.selectedItemPosition)?.id ?: "other"
        val minutes=durationValues.getOrElse(duration.selectedItemPosition){StageIssueReportPolicy.DEFAULT_DURATION_MS}
        val version=runCatching{UpdateCoordinator.installed(this).versionName}.getOrNull()?:"未知"
        val token=session.begin(BatterySample.read(this),chosen,minutes,version,
            note.text.toString(),StageIssueSceneSnapshot.read(this))
        StageIssueEventObserver.enable(this)
        WorkspaceEnergyContract.query(this){ snapshot ->
            session.setStartRuntime(token,snapshot)
            if(!isDestroyed)render()
        }
        checkDueAndArmUi()
        render()
        toast("已开始，${minutes/60_000}分钟后自动结束")
    }
    private fun mark(){
        if(pending || !session.mayMark()){toast("标记已超时或达到次数上限");return}
        val index=session.mark(BatterySample.read(this),StageIssueSceneSnapshot.read(this))
        if(index!=null){
            val token=session.currentToken()
            WorkspaceEnergyContract.query(this){snap->session.setMarkerRuntime(token,index,snap)}
            toast("已记录标记#$index")
        }
        render()
    }

    private fun finishTest(mode:String){
        if(pending || !session.active())return
        pending=true
        uiHandler.removeCallbacks(uiDeadline)
        session.saveNote(note.text.toString())
        val token=session.currentToken()
        render()
        WorkspaceEnergyContract.query(this){snap->
            if(session.active() && session.currentToken()==token)
                session.finish(session.storedVersion(),session.storedNote(),
                    BatterySample.read(this),snap,mode)
            pending=false
            if(!isDestroyed){
                render()
                toast("报告已经生成，可以补充说明后导出")
            }
        }
    }
    private fun checkDueAndArmUi(){
        uiHandler.removeCallbacks(uiDeadline)
        if(!session.active() || pending)return
        if(session.isDue()) {finishTest("到期补结束（进入测试页时）");return}
        // Only one UI callback. This is not a periodic 1s countdown.
        val remaining=StageIssueReportPolicy.remainingMs(
            session.currentToken(),android.os.SystemClock.elapsedRealtime(),session.durationMs())
        uiHandler.postDelayed(uiDeadline,remaining.coerceAtLeast(1L))
    }
    private fun render(){
        if(!::status.isInitialized)return
        val active=session.active()
        val minutes=session.durationMs()/60_000
        status.text=when{
            pending->"正在读取结束状态（最多等待约1.5秒）"
            active->"测试已开启 · 设定${minutes}分钟 · 预计还剩${session.remainingSeconds()?.div(60L) ?: 0}分钟 · 可选标记${session.markersCount()}/12"
            session.latestReport().isNotBlank()->"已结束 · 报告已保存在本机，补充说明后即可导出"
            else->"未开启 · 不运行故障监测"
        }
        startButton.isEnabled=!active&&!pending
        markButton.isEnabled=active&&!pending&&session.mayMark()
        stopButton.isEnabled=active&&!pending
        exportButton.isEnabled=!active&&!pending&&session.latestReport().isNotBlank()
        saveNoteButton.isEnabled=!pending
        category.isEnabled=!active&&!pending
        duration.isEnabled=!active&&!pending
        if(active)instruction.text=StageIssueTestCatalog.guide(session.selectedCategory())
    }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
}
