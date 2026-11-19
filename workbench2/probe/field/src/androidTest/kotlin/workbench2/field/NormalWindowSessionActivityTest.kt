package workbench2.field

import android.content.Intent
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NormalWindowSessionActivityTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private fun launch(): NormalWindowSessionActivity =
        instr.startActivitySync(Intent(instr.targetContext, NormalWindowSessionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NormalWindowSessionActivity

    @Test fun normalSessionRecordsOwnTaskAndBoundedEvents() {
        val activity = launch()
        try {
            instr.runOnMainSync {
                repeat(80) {
                    assertTrue(activity.window.decorView.findViewWithTag<Button>("session_increment").performClick())
                }
                val report = JSONObject(activity.observationReport())
                assertEquals("OWN_NORMAL_WINDOW_OBSERVATION", report.getString("kind"))
                assertEquals(BuildConfig.SOURCE_SHA, report.getString("source_sha"))
                assertEquals(activity.taskId, report.getJSONObject("current").getInt("task_id"))
                assertEquals(80, report.getJSONObject("current").getInt("clicks"))
                assertEquals(64, report.getJSONArray("events").length())
                assertTrue(report.getInt("discarded_events") > 0)
                assertEquals("CLICK", report.getJSONArray("events").getJSONObject(63).getString("event"))
                assertEquals(0, report.getInt("oem_method_calls"))
                assertFalse(report.getBoolean("host_observed"))
                assertTrue(report.isNull("native_embedding_verified"))
                assertTrue(report.isNull("native_return_verified"))
                assertFalse(report.getBoolean("production_admitted"))
            }
        } finally { instr.runOnMainSync { activity.finish() } }
    }

    @Test fun normalSessionRecreationPreservesMarker() {
        val first = launch()
        var before: JSONObject? = null
        var second: NormalWindowSessionActivity? = null
        val monitor = instr.addMonitor(NormalWindowSessionActivity::class.java.name, null, false)
        try {
            instr.runOnMainSync {
                first.window.decorView.findViewWithTag<Button>("session_increment").performClick()
                before = JSONObject(first.observationReport())
                first.recreate()
            }
            second = instr.waitForMonitorWithTimeout(monitor, 10000) as? NormalWindowSessionActivity
            val recreated = requireNotNull(second)
            assertNotSame(first, recreated)
            instr.runOnMainSync {
                val old = requireNotNull(before)
                val now = JSONObject(recreated.observationReport())
                assertEquals(old.getString("session"), now.getString("session"))
                assertEquals(1, now.getJSONObject("current").getInt("clicks"))
                assertEquals(old.getJSONObject("current").getInt("task_id"),
                    now.getJSONObject("current").getInt("task_id"))
                assertNotEquals(old.getJSONObject("current").getString("instance"),
                    now.getJSONObject("current").getString("instance"))
                val events = now.getJSONArray("events")
                assertTrue((0 until events.length()).any {
                    events.getJSONObject(it).getString("event") == "RECREATE"
                })
            }
        } finally {
            instr.removeMonitor(monitor)
            instr.runOnMainSync { (second ?: first).finish() }
        }
    }
}
