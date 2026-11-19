package workbench2.field

import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EvidenceActivityTest {
    @Test fun manualReadOnlyReportOnStockDevice() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val activity = instr.startActivitySync(Intent(ctx, EvidenceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as EvidenceActivity
        try {
            instr.runOnMainSync {
                assertEquals("尚未取证", activity.findViewById<TextView>(R.id.evidence_report).text.toString())
                assertFalse(activity.findViewById<Button>(R.id.copy_evidence).isEnabled)
                assertFalse(activity.findViewById<Button>(R.id.share_evidence).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.collect_evidence).performClick())
            }
            val deadline = SystemClock.elapsedRealtime() + 12000L
            var value = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                instr.runOnMainSync {
                    value = activity.findViewById<TextView>(R.id.evidence_report).text.toString()
                }
                if (value.trimStart().startsWith("{")) break
                SystemClock.sleep(50)
            }
            val report = JSONObject(value)
            assertEquals("io.github.x647368maker.atomicworkbench.hostevidence", report.getString("collector_package"))
            assertEquals("NOT_VISIBLE_OR_ABSENT", report.getString("result"))
            assertEquals(BuildConfig.SOURCE_SHA, report.getString("source_sha"))
            assertTrue(report.getBoolean("read_only"))
            assertEquals(0, report.getInt("oem_method_calls"))
            assertFalse(report.getBoolean("production_admitted"))
            for (key in listOf("authorized_embedding", "native_input_verified", "native_return_verified"))
                assertTrue(report.has(key) && report.isNull(key))
            instr.runOnMainSync {
                assertTrue(activity.findViewById<Button>(R.id.copy_evidence).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.share_evidence).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.collect_evidence).isEnabled)
            }
        } finally {
            instr.runOnMainSync { activity.finish() }
        }
    }
}
