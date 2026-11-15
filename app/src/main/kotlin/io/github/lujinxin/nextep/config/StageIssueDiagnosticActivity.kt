package io.github.x647368maker.atomicworkbench.config

import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.view.Gravity
import android.widget.FrameLayout
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
    companion object {
        const val EXTRA_CATEGORY = "atomic_issue_category"
    }
    private val session by lazy { StageIssueSession(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiDeadline = Runnable { if (session.active() && session.isDue()) finishTest("到期（测试页面）") else render() }

    private lateinit var pageTitle: TextView
    private lateinit var scope: LinearLayout
    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var category: Spinner
    private var chosenDuration = 1
    private val durationButtons = mutableListOf<Button>()
    private var boundToken: Long? = null
    private var boundWasActive = false
    private var renderedScopeId: String? = null
    private lateinit var note: EditText
    private lateinit var startButton: Button
    private lateinit var markButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveNoteButton: Button
    private lateinit var exportButton: Button
    private var pending = false
    private var pendingExportId: String? = null
    private var exporting = false
    private val durationValues = StageIssueReportPolicy.DURATION_OPTIONS_MS

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) { pendingExportId = null; render(); return@registerForActivityResult }
        val id = pendingExportId
        pendingExportId = null
        val report = id?.let { runCatching { session.report(it) }.getOrNull() }
        if (report.isNullOrBlank()) { render(); toast("所选报告已不可用，请重新选择"); return@registerForActivityResult }
        exporting = true
        render()
        val resolver = applicationContext.contentResolver
        Thread({
            val ok = runCatching {
                checkNotNull(resolver.openOutputStream(uri,"wt")) { "保存位置无法打开" }.use {
                    it.write(report.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            runOnUiThread {
                exporting = false
                if (!isDestroyed) {
                    render()
                    toast(if (ok) "报告保存成功，分享前请检查隐私" else "保存失败，请重试")
                }
            }
        }, "Stage-Diagnostic-Export").start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportId = savedInstanceState?.getString("diagnostic_export_id")
        chosenDuration = savedInstanceState?.getInt("diagnostic_duration",1)?.coerceIn(durationValues.indices) ?: 1
        boundToken = savedInstanceState?.takeIf { it.containsKey("diagnostic_token") }?.getLong("diagnostic_token")
        boundWasActive = savedInstanceState?.getBoolean("diagnostic_was_active") ?: false
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window,false)
        val density=resources.displayMetrics.density
        fun dp(n:Int)=(density*n).toInt()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = Color.parseColor(if (dark) "#F1F2F4" else "#121315")
        val surface = Color.parseColor(if (dark) "#202124" else "#FFFFFF")
        val background = Color.parseColor(if (dark) "#141519" else "#F3F5FA")
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(18),dp(20),dp(34))
        }
        fun line(label:String,size:Float=14f)=TextView(this).apply {
            text=label
            textSize=size
            setTextColor(ink)
            setPadding(0,dp(6),0,dp(8))
        }
        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(12),dp(16),dp(12))
            this.background=GradientDrawable().apply { setColor(surface);cornerRadius=dp(22).toFloat() }
            body.addView(this,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        }
        val top=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        top.addView(line("‹",32f).apply {
            gravity=Gravity.CENTER;contentDescription="返回";setOnClickListener{finish()}
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        pageTitle=line("故障测试与报告",26f).apply{typeface=Typeface.DEFAULT_BOLD}
        top.addView(pageTitle,LinearLayout.LayoutParams(0,-2,1f))
        body.addView(top)
        body.addView(line("正常使用 · 记录真实事件 · 生成本机报告"))
        val scopeCard=card()
        scopeCard.addView(line("诊断范围（所选项目，不代表测试通过）",16f).apply {typeface=Typeface.DEFAULT_BOLD})
        category=Spinner(this)
        category.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            StageIssueTestCatalog.cases.map{it.name})
        category.setSelection(savedInstanceState?.getInt("diagnostic_category")
            ?: StageIssueTestCatalog.selectionIndex(intent.getStringExtra(EXTRA_CATEGORY)))
        // Direct specialty navigation has its own title and scope; the existing
        // general entry still permits selecting any of the six real modes.
        category.visibility=if(intent.getStringExtra(EXTRA_CATEGORY)==null) View.VISIBLE else View.GONE
        scopeCard.addView(category,LinearLayout.LayoutParams(-1,dp(50)))
        scope=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        scopeCard.addView(scope,LinearLayout.LayoutParams(-1,-2))
        instruction=line("")
        scopeCard.addView(instruction)
        category.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                if (::instruction.isInitialized) {
                    if (::startButton.isInitialized) render()
                    else showScope(if(session.active())session.selectedCategory() else chosenCategory())
                }
            }
            override fun onNothingSelected(parent:AdapterView<*>?) {}
        }

        val timing=card()
        timing.addView(line("诊断时长（默认15分钟）",16f).apply {typeface=Typeface.DEFAULT_BOLD})
        val segments=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        for ((index,value) in durationValues.withIndex()) {
            val button=Button(this).apply {
                text="${value/60_000} 分钟";textSize=13f;isAllCaps=false
                minWidth=0;minimumWidth=0;setPadding(dp(2),0,dp(2),0)
                setOnClickListener{ if (!session.active() && !pending) { chosenDuration=index; renderDuration() } }
            }
            durationButtons.add(button)
            segments.addView(button,LinearLayout.LayoutParams(0,dp(52),1f))
        }
        timing.addView(segments)
        timing.addView(line("到期自动结束 · 不唤醒手机。系统休眠时报告可能延后生成。"))
        val controls=card()
        status=line("")
        controls.addView(status)
        startButton=Button(this).apply{
            text="开始诊断";isAllCaps=false
            this.background=GradientDrawable().apply {setColor(Color.rgb(15,103,241));cornerRadius=dp(26).toFloat()}
            setTextColor(Color.WHITE);minHeight=dp(54);setOnClickListener{start()}
        }
        markButton=Button(this).apply{text="标记问题（可选）";setOnClickListener{mark()}}
        stopButton=Button(this).apply{text="提前结束并生成报告";setOnClickListener{finishTest("用户提前结束")}}
        for (b in listOf(startButton,markButton,stopButton))
            controls.addView(b,LinearLayout.LayoutParams(-1,-2))

        val reportCard=card()
        reportCard.addView(line("问题描述（可选，最多400字）",16f))
        note=EditText(this).apply{
            hint="例如：N5展开后右边小窗遮挡；恢复后仍未正常"
            minLines=3
            maxLines=6
            filters=arrayOf(InputFilter.LengthFilter(StageIssueReportPolicy.MAX_DESCRIPTION_CHARS))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(savedInstanceState?.getString("diagnostic_note") ?: if(session.active()) session.storedNote() else "")
        }
        reportCard.addView(note,LinearLayout.LayoutParams(-1,-2))
        saveNoteButton=Button(this).apply{text="保存说明／结束后补充";setOnClickListener{
            val expected = boundToken
            if (expected == null || !session.saveNote(note.text.toString(), expected, boundWasActive)) {
                bindNote();render();toast("会话已经变化或尚无报告，请重新保存");return@setOnClickListener
            }
            toast("说明已保存到本机会话或报告")
        }}
        reportCard.addView(saveNoteButton,LinearLayout.LayoutParams(-1,-2))
        exportButton=Button(this).apply{text="导出最新TXT报告";setOnClickListener{
            session.latestRecord()?.let(::exportRecord)
        }}
        reportCard.addView(exportButton,LinearLayout.LayoutParams(-1,-2))
        reportCard.addView(Button(this).apply{text="查看历史报告（最近10份）";setOnClickListener{showHistory()}},
            LinearLayout.LayoutParams(-1,-2))
        body.addView(line("报告包含五大专项的证据覆盖说明、起止快照及近期交换记录。未触发、不可观测或未测试的项目不会标记为通过。最近10份报告仅在本机保留，详情与导出按需打开，不会自动上传。"))
        body.addView(Button(this).apply{text="返回台前调度";setOnClickListener{finish()}})
        val host=FrameLayout(this).apply {
            addView(body,FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addOnLayoutChangeListener { _,l,_,r,_,_,_,_,_ ->
                if(r>l) {
                    val width=StageHomeLayout.contentWidth(r-l,resources.displayMetrics.density)
                    if(body.layoutParams.width!=width)
                        body.layoutParams=(body.layoutParams as FrameLayout.LayoutParams).apply{this.width=width}
                }
            }
        }
        val scroll=ScrollView(this).apply{isFillViewport=true;setBackgroundColor(background);addView(host)}
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
            category.setSelection(StageIssueTestCatalog.selectionIndex(session.selectedCategory()))
            chosenDuration=durationValues.indexOf(session.durationMs()).coerceAtLeast(0)
        }
        bindNote()
        if (session.active()) StageIssueEventObserver.enable(this)
        checkDueAndArmUi()
        render()
    }
    override fun onPause(){
        uiHandler.removeCallbacks(uiDeadline)
        if(::note.isInitialized && session.active()) boundToken?.let { session.saveNote(note.text.toString(),it) }
        super.onPause()
    }
    override fun onDestroy(){uiHandler.removeCallbacks(uiDeadline);super.onDestroy()}

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("diagnostic_export_id", pendingExportId)
        outState.putInt("diagnostic_duration",chosenDuration)
        if(::category.isInitialized) outState.putInt("diagnostic_category",category.selectedItemPosition)
        if(::note.isInitialized) outState.putString("diagnostic_note",note.text.toString())
        boundToken?.let {outState.putLong("diagnostic_token",it)}
        outState.putBoolean("diagnostic_was_active",boundWasActive)
        super.onSaveInstanceState(outState)
    }

    private fun exportRecord(record: StageIssueHistoryPolicy.Record) {
        if (pendingExportId != null || exporting) return
        pendingExportId = record.id
        render()
        runCatching { saveDocument.launch("台前调度-故障报告-${record.startedAt}.txt") }
            .onFailure { pendingExportId = null; render(); toast("无法打开保存位置") }
    }

    private fun showHistory() {
        if (pending) return
        val records = runCatching { session.reports() }
            .getOrElse { toast("历史报告读取失败，最新报告仍可单独导出"); return }
        if (records.isEmpty()) { toast("暂无历史报告"); return }
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.getDefault())
        val titles = records.map {
            format.format(java.util.Date(it.startedAt)) + " · " + StageIssueTestCatalog.find(it.category).name
        }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("诊断历史（最近10份）")
            .setItems(titles) { _, which ->
                val record = records[which]
                MaterialAlertDialogBuilder(this).setTitle(titles[which])
                    .setMessage(record.text)
                    .setNegativeButton("关闭",null)
                    .setPositiveButton("导出TXT"){_,_->exportRecord(record)}
                    .show()
            }.setNegativeButton("关闭",null).show()
    }

    private fun start(){
        if(pending || session.active())return
        begin()
    }
    private fun chosenCategory(): String {
        // A direct specialty link stays that specialty once a different active
        // session finishes. The hidden spinner must not inherit its old mode.
        val requested = intent.getStringExtra(EXTRA_CATEGORY)
        val selection = if(requested == null) category.selectedItemPosition
            else StageIssueTestCatalog.selectionIndex(requested)
        return StageIssueTestCatalog.cases.getOrNull(selection)?.id ?: "all"
    }
    private fun begin(){
        if(pending || session.active())return
        val chosen=chosenCategory()
        val minutes=durationValues.getOrElse(chosenDuration){StageIssueReportPolicy.DEFAULT_DURATION_MS}
        val version=runCatching{UpdateCoordinator.installed(this).versionName}.getOrNull()?:"未知"
        // The previous report supplement is not a new session draft.
        val freshNote = if (session.latestRecord() == null) note.text.toString() else ""
        val token=runCatching { session.begin(BatterySample.read(this),chosen,minutes,version,
            freshNote,StageIssueSceneSnapshot.read(this)) }
            .getOrElse { toast("无法开启诊断：${it.message ?: "存储不可用"}"); render(); return }
        boundToken=token
        boundWasActive=true
        note.setText(freshNote)
        StageIssueEventObserver.enable(this)
        WorkspaceEnergyContract.query(this, includeExchangeDiagnostics = true){ snapshot ->
            session.setStartRuntime(token,snapshot)
            if(!isDestroyed)render()
        }
        checkDueAndArmUi()
        render()
        toast("已开始，${minutes/60_000}分钟到期（系统休眠可能延期生成报告）")
    }
    private fun mark(){
        if(pending || boundToken!=session.currentToken() || !session.mayMark()){toast("会话已变化、标记已超时或达到次数上限");return}
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
        val token=session.currentToken()
        if(boundToken!=token) { bindNote(); render(); return }
        pending=true
        uiHandler.removeCallbacks(uiDeadline)
        session.saveNote(note.text.toString(),token)
        render()
        fun completed(result: Result<String?>) {
            pending=false
            if(!isDestroyed){
                bindNote()
                render()
                if(result.isFailure) toast("报告保存失败，请重新尝试结束")
                else if(result.getOrNull()!=null) toast("报告已经生成，可以补充说明后导出")
                else if(!session.active()) toast("本次会话已经结束")
            }
        }
        runCatching {
            StageIssueFinishCoordinator.request(this,token,mode,
                { receive -> WorkspaceEnergyContract.query(this, includeExchangeDiagnostics = true) { receive(it) } },
                ::completed)
        }.onFailure { completed(Result.failure(it)) }
    }
    private fun bindNote() {
        if(!::note.isInitialized)return
        val active=session.active()
        val token=session.currentToken().takeIf { it >= 0L && (active || session.latestRecord()!=null) }
        if(token!=boundToken || active!=boundWasActive) {
            note.setText(when { active -> session.storedNote(); token!=null -> session.storedPostNote(); else -> "" })
        }
        boundToken=token
        boundWasActive=active
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
        bindNote()
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
        exportButton.isEnabled=!active&&!pending&&!exporting&&pendingExportId==null&&session.latestReport().isNotBlank()
        saveNoteButton.isEnabled=!pending&&boundToken!=null
        category.isEnabled=!active&&!pending
        renderDuration()
        val id=if(active)session.selectedCategory() else chosenCategory()
        showScope(id)
        startButton.visibility=if(active||pending) View.GONE else View.VISIBLE
        markButton.visibility=if(active) View.VISIBLE else View.GONE
        stopButton.visibility=if(active) View.VISIBLE else View.GONE
        startButton.alpha=if(startButton.isEnabled) 1f else .55f
        startButton.text=if(id=="all") "开始综合诊断" else "开始专项诊断"
    }
    private fun renderDuration(){
        val enabled=!session.active()&&!pending
        for((i,b) in durationButtons.withIndex()){
            b.isEnabled=enabled
            b.isSelected=i==chosenDuration
            b.setTextColor(if(b.isSelected) Color.WHITE else Color.rgb(70,79,98))
            b.background=GradientDrawable().apply{
                setColor(if(b.isSelected) Color.rgb(15,103,241) else Color.rgb(232,235,242))
                cornerRadius=resources.displayMetrics.density*14
            }
            b.alpha=if(enabled||b.isSelected) 1f else .65f
        }
    }
    private fun showScope(id:String?){
        if(!::scope.isInitialized||!::instruction.isInitialized)return
        val selected=StageIssueTestCatalog.find(id)
        if(renderedScopeId==selected.id)return
        renderedScopeId=selected.id
        val rows=when(selected.id){
            "all"->StageIssueTestCatalog.modules.map{it.name}
            "performance"->listOf("卡顿","动画","闪烁","耗电","发热")
            "layout"->listOf("四小窗","窗口交换","智能隐藏","空间利用")
            "content"->listOf("视频","游戏","触控","后台状态","内容保留")
            "compatibility"->listOf("折叠屏","平板","锁屏","系统手势","Root模块")
            "services"->listOf("工作组","快捷栏","在线更新","收款码")
            else->listOf(selected.name)
        }
        pageTitle.text=if(selected.id=="all") "综合诊断" else selected.name
        scope.removeAllViews()
        for(label in rows) scope.addView(TextView(this).apply{
            text=label+"   ·   纳入范围"
            textSize=16f;minimumHeight=(resources.displayMetrics.density*48).toInt()
            gravity=Gravity.CENTER_VERTICAL
        },LinearLayout.LayoutParams(-1,-2))
        instruction.text=StageIssueTestCatalog.guide(id)
    }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
}
