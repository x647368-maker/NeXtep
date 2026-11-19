package workbench2.field

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ContentResolver
import android.net.Uri
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
 * production app, network or service. No automatic collection. Only an explicit
 * Storage Access Framework selection writes the public installed-code archive.
 */
class EvidenceActivity : Activity() {
    private var running = false
    private var report: String? = null
    private lateinit var collect: Button
    private lateinit var copy: Button
    private lateinit var share: Button
    private lateinit var output: TextView
    private lateinit var export: Button
    private lateinit var exportStatus: TextView
    private var selectingDocument = false
    private lateinit var framework: Button
    private var pendingFramework: FrameworkCodeArchive.Scan? = null
    private companion object {
        const val SAVE_HOST_CODE = 2301
        const val SAVE_FRAMEWORK = 2302
    }

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
            text = "独立诊断工具，不替换台前调度。点击后只读取公开系统包信息、固件版本和证书摘要，不调用原生任务接口，不上传。报告用于人工核对，不能证明原生授权。仅当前已确认的 PMS110 固件可手动导出宿主安装代码 ZIP，用于静态接口检查，不包含应用数据。"
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
        export = Button(this).apply {
            id = R.id.export_host_code
            text = "导出宿主安装文件 ZIP"
            isEnabled = false
            setOnClickListener { chooseExport() }
        }
        exportStatus = TextView(this).apply { textSize = 14f }
        output = TextView(this).apply {
            id = R.id.evidence_report
            text = "尚未取证"
            textSize = 14f
            setTextIsSelectable(true)
        }
        framework = Button(this).apply {
            id = R.id.export_framework_code
            text = "定向导出原生框架 ZIP"
            isEnabled = FrameworkCodeArchive.isTarget()
            setOnClickListener { scanFramework() }
        }
        body.addView(framework)
        for (view in listOf(collect, copy, share, export, exportStatus, output)) body.addView(view)
        body.addView(Button(this).apply {
            text = "正常多窗任务验证"
            tag = "open_normal_session"
            setOnClickListener {
                startActivity(Intent(this@EvidenceActivity, NormalWindowSessionActivity::class.java))
            }
        })
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun collectOnce() {
        if (running || selectingDocument || isFinishing || isDestroyed) return
        running = true
        report = null
        framework.isEnabled = false
        collect.isEnabled = false
        copy.isEnabled = false
        share.isEnabled = false
        export.isEnabled = false
        exportStatus.text = ""
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
                framework.isEnabled = FrameworkCodeArchive.isTarget()
                copy.isEnabled = result != null
                share.isEnabled = result != null
                export.isEnabled = canExport()
                output.text = result ?: "读取失败，未产生报告。"
            }
        }, "Workbench2-ExplicitInventory").start()
    }

    private fun canExport(): Boolean = !running && !selectingDocument &&
        report?.let { HostCodeArchive.matches(JSONObject(it)) } == true

    @Suppress("DEPRECATION")
    private fun chooseExport() {
        if (!canExport() || isFinishing || isDestroyed) return
        selectingDocument = true
        framework.isEnabled = false
        collect.isEnabled = false
        export.isEnabled = false
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE,"PMS110-pscanvas-2.0.0-code.zip"),SAVE_HOST_CODE)
        } catch (_: Exception) {
            selectingDocument=false
            framework.isEnabled=FrameworkCodeArchive.isTarget()
            collect.isEnabled=true
            export.isEnabled=canExport()
            exportStatus.text="无法打开系统保存界面，未读取安装文件。"
        }
    }

    @Deprecated("Platform Activity result is retained to avoid an extra dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode==SAVE_FRAMEWORK) {
            val scan=pendingFramework
            pendingFramework=null
            val requested=selectingDocument
            selectingDocument=false
            resetButtons()
            val uri=data?.data
            if (requested && scan!=null && resultCode==RESULT_OK && uri?.scheme==ContentResolver.SCHEME_CONTENT &&
                FrameworkCodeArchive.isTarget() && !running && !isFinishing && !isDestroyed) exportFramework(scan,uri)
            return
        }
        if (requestCode!=SAVE_HOST_CODE) return
        val requested = selectingDocument
        selectingDocument=false
        collect.isEnabled=!running
        framework.isEnabled=!running && FrameworkCodeArchive.isTarget()
        export.isEnabled=canExport()
        val uri=data?.data
        if (!requested || resultCode!=RESULT_OK || !canExport() || uri?.scheme!=ContentResolver.SCHEME_CONTENT) return
        exportOnce(uri)
    }

    private fun exportOnce(uri: Uri) {
        running=true
        framework.isEnabled=false
        collect.isEnabled=false
        export.isEnabled=false
        exportStatus.text="正在导出公开安装文件…"
        val app=applicationContext
        Thread({
            val ok=try {
                HostCodeArchive.export(app) {
                    requireNotNull(app.contentResolver.openOutputStream(uri,"w"))
                }
                true
            } catch (_: Exception) { false }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                running=false
                framework.isEnabled=FrameworkCodeArchive.isTarget()
                collect.isEnabled=true
                export.isEnabled=canExport()
                exportStatus.text=if(ok) "导出完成，请将刚保存的 ZIP 发给研发。无需安装其中 APK。"
                    else "导出失败：身份不匹配、文件不可读或保存失败。请勿发送不完整 ZIP。"
            }
        },"Workbench2-ExplicitCodeExport").start()
    }

    private fun resetButtons() {
        collect.isEnabled=!running && !selectingDocument
        export.isEnabled=canExport()
        framework.isEnabled=!running && !selectingDocument && FrameworkCodeArchive.isTarget()
    }

    @Suppress("DEPRECATION")
    private fun scanFramework() {
        if (running || selectingDocument || isDestroyed || isFinishing || !FrameworkCodeArchive.isTarget()) return
        running=true
        resetButtons()
        exportStatus.text="正在一次性扫描公开框架文件（不加载框架代码）…"
        Thread({
            val scan=try { FrameworkCodeArchive.scan() } catch (_: Exception) { null }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                running=false
                resetButtons()
                if (scan==null) {
                    exportStatus.text="框架扫描失败或身份不匹配。未导出。"
                    return@runOnUiThread
                }
                exportStatus.text=scan.summary()
                if (scan.matches.isEmpty()) {
                    exportStatus.append("；没有可确认定义的可读文件，未创建 ZIP。")
                    return@runOnUiThread
                }
                pendingFramework=scan
                selectingDocument=true
                resetButtons()
                try {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                        .putExtra(Intent.EXTRA_TITLE,"PMS110-flexible-framework-code.zip"),SAVE_FRAMEWORK)
                } catch (_: Exception) {
                    pendingFramework=null
                    selectingDocument=false
                    resetButtons()
                    exportStatus.append("；无法打开保存界面。")
                }
            }
        },"Workbench2-ExplicitFrameworkScan").start()
    }

    private fun exportFramework(scan: FrameworkCodeArchive.Scan,uri: Uri) {
        running=true
        resetButtons()
        exportStatus.text="正在保存命中的框架文件…"
        val app=applicationContext
        Thread({
            val ok=try {
                FrameworkCodeArchive.export(scan) { requireNotNull(app.contentResolver.openOutputStream(uri,"w")) }
                true
            } catch (_: Exception) { false }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                running=false
                resetButtons()
                exportStatus.text=if(ok) scan.summary()+"；导出完成，请发送 ZIP（即使只命中一类）。"
                    else "导出失败或文件变化，请勿发送不完整 ZIP。"
            }
        },"Workbench2-ExplicitFrameworkExport").start()
    }
}
