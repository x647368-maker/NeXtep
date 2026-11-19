package workbench2.field

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Opt-in observable fixture for the OS's own normal multi-window entry.
 * Only this Activity's callbacks and its own SurfaceView are observed.
 * No host/module calls, task control, injected input, background polling or
 * claim that a third-party task is embedded. Rotation/recreation is explicit.
 */
class NormalWindowSessionActivity : Activity() {
    private companion object { const val MAX_EVENTS = 64 }
    private val events = ArrayDeque<JSONObject>()
    private var discarded = 0
    private var marker = ""
    private val instance = UUID.randomUUID().toString()
    private var clicks = 0
    private var active = false
    private lateinit var status: TextView
    private var ownSurfaceValid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        marker = savedInstanceState?.getString("marker") ?: UUID.randomUUID().toString()
        clicks = savedInstanceState?.getInt("clicks") ?: 0
        discarded = savedInstanceState?.getInt("discarded") ?: 0
        savedInstanceState?.getString("events")?.let { saved ->
            val array = JSONArray(saved)
            for (i in maxOf(0, array.length() - MAX_EVENTS) until array.length()) {
                events.addLast(array.getJSONObject(i))
            }
        }
        active = true
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val scroll = ScrollView(this).apply {
            addView(panel)
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(pad + safe.left, pad + safe.top, pad + safe.right, pad + safe.bottom)
                insets
            }
        }
        panel.addView(TextView(this).apply {
            text = "台前调度 · 正常多窗验证"
            textSize = 22f
        })
        panel.addView(TextView(this).apply {
            text = "先点击计数，再通过系统最近任务或侧栏的正常浮窗/分屏入口操作本测试页，回到全屏后再次点击计数并复制报告。只观察本页，不创建或移动其他任务。无需启用模块。"
            textSize = 16f
        })
        status = TextView(this).apply { textSize = 16f }
        panel.addView(status)
        panel.addView(Button(this).apply {
            tag = "session_increment"
            text = "点击测试计数"
            setOnClickListener { clicks++; record("CLICK") }
        })
        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                ownSurfaceValid = holder.surface.isValid
                record("OWN_SURFACE_CREATED")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                ownSurfaceValid = holder.surface.isValid
                // This is our own test layer, not an OEM task leash or a physical
                // presentation receipt. Do not capture another window or poll.
                record("OWN_SURFACE_CHANGED", JSONObject().put("width", width).put("height", height))
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                ownSurfaceValid = false
                record("OWN_SURFACE_DESTROYED")
            }
        })
        panel.addView(surface, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (40 * resources.displayMetrics.density).toInt()))
        panel.addView(Button(this).apply {
            text = "复制本次观察报告"
            setOnClickListener {
                record("COPY")
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("正常多窗验证", observationReport()))
                Toast.makeText(this@NormalWindowSessionActivity, "已复制本页观察报告", Toast.LENGTH_SHORT).show()
            }
        })
        panel.addView(Button(this).apply {
            text = "结束测试"
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.requestApplyInsets()
        record(if (savedInstanceState == null) "CREATE" else "RECREATE")
    }

    override fun onStart() { super.onStart(); record("START") }
    override fun onResume() { super.onResume(); record("RESUME") }
    override fun onPause() { record("PAUSE"); super.onPause() }
    override fun onStop() { record("STOP"); super.onStop() }
    override fun onDestroy() { record("DESTROY"); active = false; super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        record("SAVE_STATE")
        outState.putString("marker", marker)
        outState.putInt("clicks", clicks)
        outState.putInt("discarded", discarded)
        outState.putString("events", JSONArray(events.toList()).toString())
        super.onSaveInstanceState(outState)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        record("MULTI_WINDOW", JSONObject().put("callback_value", isInMultiWindowMode))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        record("FOCUS", JSONObject().put("callback_value", hasFocus))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val consumed = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            // Callback observation only. We do not assert physical provenance.
            record("TOUCH_UP", JSONObject().put("consumed", consumed))
        }
        return consumed
    }

    private fun snapshot(): JSONObject = JSONObject()
        .put("task_id", taskId)
        .put("instance", instance)
        .put("display_id", display?.displayId ?: -1)
        .put("multi_window", isInMultiWindowMode)
        .put("window_focus", hasWindowFocus())
        .put("own_surface_valid", ownSurfaceValid)
        .put("clicks", clicks)
        .put("screen_width_dp", resources.configuration.screenWidthDp)
        .put("screen_height_dp", resources.configuration.screenHeightDp)

    private fun record(kind: String, detail: JSONObject? = null) {
        if (!active) return
        if (events.size >= MAX_EVENTS) { events.removeFirst(); discarded++ }
        events.addLast(snapshot().put("event", kind)
            .put("elapsed_ms", SystemClock.elapsedRealtime())
            .put("detail", detail ?: JSONObject.NULL))
        if (::status.isInitialized) {
            status.text = "测试标记：" + marker.take(8) + "\n计数：" + clicks +
                "\n任务：" + taskId + " / 多窗：" + isInMultiWindowMode +
                "\n事件：" + events.size + " / 已丢弃：" + discarded
        }
    }

    fun observationReport(): String = JSONObject()
        .put("schema", 1)
        .put("kind", "OWN_NORMAL_WINDOW_OBSERVATION")
        .put("source_sha", BuildConfig.SOURCE_SHA)
        .put("session", marker)
        .put("model", android.os.Build.MODEL)
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("firmware", android.os.Build.FINGERPRINT)
        .put("current", snapshot())
        .put("discarded_events", discarded)
        .put("events", JSONArray(events.toList()))
        .put("oem_method_calls", 0)
        .put("host_observed", false)
        .put("native_embedding_verified", JSONObject.NULL)
        .put("native_return_verified", JSONObject.NULL)
        .put("production_admitted", false)
        .toString(2)
}
