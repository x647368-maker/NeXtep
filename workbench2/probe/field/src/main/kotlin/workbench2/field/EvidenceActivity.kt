package workbench2.field

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import workbench2.nativebridge.AndroidHostInventory

/**
 * A separate opt-in field instrument. No modules, permissions, tasks, IPC to the
 * production app, persistent storage, network or service. No automatic collection.
 */
class EvidenceActivity : Activity() {
    private var running = false
    private var report: String? = null
    private lateinit var collect: Button
    private lateinit var copy: Button
    private lateinit var share: Button
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(padding, padding, padding, padding)
            addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(padding + safe.left, padding + safe.top, padding + safe.right, padding + safe.bottom)
                insets
            }
        }
        body.addView(TextView(this).apply {
            text = "台前调度 2.0 · 宿主取证"
            textSize = 22f
        })
        body.addView(TextView(this).apply {
            text = "独立诊断工具，不替换台前调度。点击后只读取公开系统包信息、固件版本和证书摘要，不调用原生任务接口，不上传。报告用于人工核对，不能证明原生授权。"
            textSize = 16f
        })
        collect = Button(this).apply {
            id = R.id.collect_evidence
            text = "一次性读取宿主信息"
            setOnClickListener { collectOnce() }
        }
        copy = Button(this).apply {
            id = R.id.copy_evidence
            text = "复制报告"
            isEnabled = false
            setOnClickListener {
                report?.let {
                    val clipboard = getSystemService(ClipboardManager::class.java) ?: return@setOnClickListener
                    clipboard.setPrimaryClip(ClipData.newPlainText("台前调度宿主取证", it))
                    Toast.makeText(this@EvidenceActivity, "报告已复制", Toast.LENGTH_SHORT).show()
                }
            }
        }
        share = Button(this).apply {
            id = R.id.share_evidence
            text = "手动分享报告"
            isEnabled = false
            setOnClickListener {
                report?.let {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it)
                    startActivity(Intent.createChooser(send, "分享前请检查报告内容"))
                }
            }
        }
        output = TextView(this).apply {
            id = R.id.evidence_report
            text = "尚未取证"
            textSize = 14f
            setTextIsSelectable(true)
        }
        for (view in listOf(collect, copy, share, output)) body.addView(view)
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun collectOnce() {
        if (running || isFinishing || isDestroyed) return
        running = true
        report = null
        collect.isEnabled = false
        copy.isEnabled = false
        share.isEnabled = false
        output.text = "正在读取公开信息…"
        val app = applicationContext
        // Exactly one finite worker per user action; never scheduled or retained.
        Thread({
            val result = try {
                JSONObject(AndroidHostInventory.collect(app))
                    .put("source_sha", BuildConfig.SOURCE_SHA)
                    .put("collector_package", app.packageName)
                    .toString(2)
            } catch (_: Exception) { null }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                running = false
                report = result
                collect.isEnabled = true
                copy.isEnabled = result != null
                share.isEnabled = result != null
                output.text = result ?: "读取失败，未产生报告。"
            }
        }, "Workbench2-ExplicitInventory").start()
    }
}
