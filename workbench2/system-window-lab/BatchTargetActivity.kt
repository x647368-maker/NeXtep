package workbench2.field

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.util.Log
import android.os.Process
import org.json.JSONObject
import java.util.UUID

/** Disposable CI-only target. Separate package/UID from the controller. */
class BatchTargetActivity : Activity() {
    private val instance=UUID.randomUUID().toString()
    private var marker=""
    private var slot=""
    private var clicks=0
    private lateinit var button: Button
    private lateinit var label: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slot=requireNotNull(intent.getStringExtra("slot"))
        require(slot=="A" || slot=="B")
        marker=savedInstanceState?.getString("marker") ?: UUID.randomUUID().toString()
        clicks=savedInstanceState?.getInt("clicks") ?: 0
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setBackgroundColor(if(slot=="A") Color.rgb(24,95,160) else Color.rgb(148,73,31))
        }
        label=TextView(this).apply { textSize=20f; setTextColor(Color.WHITE) }
        button=Button(this).apply {
            text="Increment "+slot
            setOnClickListener { clicks++; updateText(); report("CLICK") }
        }
        panel.addView(label)
        panel.addView(button)
        setContentView(panel)
        updateText()
        panel.viewTreeObserver.addOnGlobalLayoutListener { report("LAYOUT") }
    }
    private fun updateText() { label.text="Fixture "+slot+" count="+clicks+" "+marker.take(8) }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("marker",marker); outState.putInt("clicks",clicks)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); if(::button.isInitialized) button.post { report("RESUME") } }
    override fun onWindowFocusChanged(focus:Boolean) {
        super.onWindowFocusChanged(focus)
        if(::button.isInitialized) button.post { report("FOCUS") }
    }
    override fun onDestroy() { if(::button.isInitialized) report("DESTROY"); super.onDestroy() }
    private fun report(event:String) {
        if(!::button.isInitialized || button.width<=0 || button.height<=0) return
        val xy=IntArray(2); button.getLocationOnScreen(xy)
        val b=windowManager.currentWindowMetrics.bounds
        Log.i("WB2BatchTarget",JSONObject()
            .put("slot",slot).put("event",event).put("task",taskId)
            .put("pid",Process.myPid()).put("instance",instance).put("marker",marker)
            .put("clicks",clicks).put("multi",isInMultiWindowMode)
            .put("display",display?.displayId ?: -1)
            .put("shown",button.isShown).put("focus",hasWindowFocus())
            .put("bounds",listOf(b.left,b.top,b.right,b.bottom).joinToString(","))
            .put("x",xy[0]+button.width/2).put("y",xy[1]+button.height/2).toString())
    }
}
