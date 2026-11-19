package workbench2.nativebridge

import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runs on stock Android, not a simulated Kotlin model and not OEM proof. */
class HostBoundaryTest {
    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instr.targetContext

    private fun assertNoAdmission(report: JSONObject) {
        assertTrue(report.getBoolean("read_only"))
        assertEquals(0, report.getInt("oem_method_calls"))
        assertFalse(report.getBoolean("production_admitted"))
        for (key in listOf("authorized_embedding", "native_return_verified", "native_input_verified")) {
            assertTrue("Missing or non-null: " + key, report.has(key) && report.isNull(key))
        }
    }

    @Test fun ordinaryProcessCannotEnterHostProbe() {
        assertNotEquals("com.oplus.pscanvas", context.packageName)
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        val report = JSONObject(AndroidHostEvidence.collect(context))
        assertEquals("WRONG_HOST", report.getString("result"))
        assertNoAdmission(report)
        assertFalse(report.has("basic_present"))
        assertFalse(report.has("lifecycle_metadata"))
    }

    @Test fun stockAndroidPackageManagerDoesNotAssertHostAvailability() {
        val pm = context.packageManager
        try {
            pm.getPackageInfo("com.oplus.pscanvas", PackageManager.PackageInfoFlags.of(0))
            fail("Stock test image unexpectedly exposes target package; do not count as absence gate.")
        } catch (_: PackageManager.NameNotFoundException) {
            // Expected on the explicitly provisioned stock emulator.
        }
        val report = JSONObject(AndroidHostInventory.collect(context))
        assertEquals("PUBLIC_PACKAGE_INVENTORY", report.getString("kind"))
        assertEquals("NOT_VISIBLE_OR_ABSENT", report.getString("result"))
        assertEquals(Build.FINGERPRINT, report.getString("firmware"))
        assertEquals(Build.VERSION.SDK_INT, report.getInt("sdk"))
        assertEquals("com.oplus.pscanvas", report.getString("host_package"))
        assertFalse(report.has("current_signer_sha256"))
        assertNoAdmission(report)
    }

    @Test fun bothCollectorsRejectMainThread() {
        var rejected = 0
        instr.runOnMainSync {
            try { AndroidHostInventory.collect(context) }
            catch (_: IllegalStateException) { rejected++ }
            try { AndroidHostEvidence.collect(context) }
            catch (_: IllegalStateException) { rejected++ }
        }
        assertEquals(2, rejected)
    }
}
