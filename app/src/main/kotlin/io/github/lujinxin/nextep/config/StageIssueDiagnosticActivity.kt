package io.github.x647368maker.atomicworkbench.config

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

/** Manually armed, no background monitors. Export only after user picks a destination. */
class StageIssueDiagnosticActivity : AppCompatActivity() {
    private val session by lazy { StageIssueSession(this) }
    private lateinit var status: TextView
    private lateinit var note: EditText
    private lateinit var startButton: Button
    private lateinit var markButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportButton: Button
    private var pending = false
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val report = session.latestReport()
        if (report.isBlank()) { toast("报告不存在，请先结束测试"); return@registerForActivityResult }
        runCatching {
            checkNotNull(contentResolver.openOutputStream(uri)) { "无法打开保存位置" }.use {
                it.write(report.toByteArray(Charsets.UTF_8))
            }
        }.onSuccess { toast("已保存报告，请检查隐私后自行发送") }
            .onFailure { toast("报告保存失败，请重试") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),dp(24),dp(20),dp(36))
        }
        fun text(value: String, size: Float = 14f): TextView = TextView(this).apply {
            this.text = value
            textSize = size
            setPadding(0,dp(6),0,dp(8))
        }
        body.addView(text("故障测试 · 手动模式",24f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(text("仅在点开始、点标记、点结束时取数据；不录屏、不截取聊天内容、不增加后台监控，也不自动上传。最多记录30分钟、12次标记。"))
        status = text("")
        body.addView(status)
        startButton = Button(this).apply { text = "开始测试"; setOnClickListener { start() } }
        markButton = Button(this).apply { text = "标记问题发生"; setOnClickListener {
            toast(if (session.mark()) "已记录问题发生时间" else "不能继续标记（已超时或达到12次）")
            render()
        } }
        stopButton = Button(this).apply { text = "结束测试并生成报告"; setOnClickListener { stop() } }
        exportButton = Button(this).apply { text = "导出报告（自行选择保存位置）"; setOnClickListener {
            if (session.latestReport().isNotBlank()) saveDocument.launch("台前调度-故障报告.txt")
        } }
        for (button in listOf(startButton,markButton,stopButton,exportButton)) {
            body.addView(button,LinearLayout.LayoutParams(-1,-2))
        }
        body.addView(text("可选：描述出现的问题或复现步骤。请不要填写姓名、手机号、聊天内容、账号等隐私。"))
        note = EditText(this).apply {
            hint = "例如：切换主窗后右侧小窗没恢复；重复3次"
            minLines = 3; maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        body.addView(note,LinearLayout.LayoutParams(-1,-2))
        body.addView(text("报告只包含机型/版本、测试前后电池状态、工作台小窗数量、有限运行计数和你手动标记的时间。不含完整系统崩溃日志，不能单独证明耗电原因。"))
        body.addView(Button(this).apply {
            text = "返回台前调度"; setOnClickListener { finish() }
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime.bottom))
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        render()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) render() }

    private fun start() {
        if (pending || session.active()) return
        if (session.latestReport().isNotBlank()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("开始新测试？")
                .setMessage("上一次报告还保存在 App 中。开始新测试会覆盖它；如果还需要，请先导出。")
                .setNegativeButton("取消", null)
                .setPositiveButton("覆盖并开始") { _, _ -> beginNewSession() }
                .show()
        } else beginNewSession()
    }

    private fun beginNewSession() {
        if (pending || session.active()) return
        val token = session.begin(BatterySample.read(this))
        render()
        WorkspaceEnergyContract.query(this) { snapshot ->
            session.setStartRuntime(token,snapshot)
            if (!isDestroyed) render()
        }
    }

    private fun stop() {
        if (pending || !session.active()) return
        pending = true
        render()
        val battery = BatterySample.read(this)
        val userNote = note.text.toString()
        val version = runCatching { UpdateCoordinator.installed(this).versionName }.getOrNull() ?: "未知"
        WorkspaceEnergyContract.query(this) { snapshot ->
            session.finish(version,userNote,battery,snapshot)
            pending = false
            if (!isDestroyed) { render(); toast("报告已生成，点击导出选择保存位置") }
        }
    }

    private fun render() {
        val recording = session.active()
        val elapsed = session.elapsedSeconds()
        status.text = when {
            pending -> "正在读取结束状态（最多等待约1.5秒）"
            recording -> "测试进行中 · ${elapsed ?: 0}秒 · 已标记${session.markersCount()}次" +
                if (elapsed == null || elapsed > StageIssueReportPolicy.MAX_DURATION_MS / 1_000L)
                    " · 已超过30分钟，请结束并导出" else ""
            session.latestReport().isNotEmpty() -> "测试已结束 · 报告已就绪（未自动上传）"
            else -> "默认关闭 · 不占用后台监控资源"
        }
        startButton.isEnabled = !pending && !recording
        markButton.isEnabled = recording && !pending && session.mayMark()
        stopButton.isEnabled = recording && !pending
        exportButton.isEnabled = !pending && !recording && session.latestReport().isNotBlank()
    }

    private fun toast(s: String) {
        Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    }
}
