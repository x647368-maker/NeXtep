package workbench2.field

import android.content.Intent
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class FrameworkCodeArchiveTest {
    @Test fun frameworkExportDisabledOffTarget() {
        val i=InstrumentationRegistry.getInstrumentation()
        val a=i.startActivitySync(Intent(i.targetContext,EvidenceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as EvidenceActivity
        try {
            assertFalse(FrameworkCodeArchive.isTarget())
            i.runOnMainSync { assertFalse(a.findViewById<Button>(R.id.export_framework_code).isEnabled) }
            try { FrameworkCodeArchive.scan(); fail("wrong device") } catch (_: IllegalStateException) {}
        } finally { i.runOnMainSync { a.finish() } }
    }

    // Minimal synthetic DEX table fixture: distinguishes class_defs from type refs.
    private fun dex(defined: Boolean): ByteArray {
        val name=FrameworkCodeArchive.targets.first().toByteArray(Charsets.US_ASCII)
        val data=ByteArray(160+name.size+2)
        val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        "dex\n039\u0000".toByteArray(Charsets.US_ASCII).copyInto(data)
        b.putInt(32,data.size); b.putInt(36,112); b.putInt(40,0x12345678)
        b.putInt(56,1); b.putInt(60,112)
        b.putInt(64,1); b.putInt(68,116)
        b.putInt(96,if(defined) 1 else 0); b.putInt(100,if(defined) 120 else 0)
        b.putInt(112,160); b.putInt(116,0); b.putInt(120,0)
        data[160]=name.size.toByte(); name.copyInto(data,161)
        return data
    }

    @Test fun frameworkDexRequiresClassDefinition() {
        assertEquals(setOf(FrameworkCodeArchive.targets.first()),FrameworkCodeArchive.definedTargets(dex(true)))
        assertTrue(FrameworkCodeArchive.definedTargets(dex(false)).isEmpty())
        val bad=dex(true).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(120,1) }
        try { FrameworkCodeArchive.definedTargets(bad); fail("bad class index") } catch (_: IllegalArgumentException) {}
        assertFalse(FrameworkCodeArchive.allowed("/data/local/tmp/framework.jar"))
        assertFalse(FrameworkCodeArchive.allowed("/system/framework/../private.jar"))
        assertTrue(FrameworkCodeArchive.allowed("/system/framework/example.jar"))
    }

    @Test fun frameworkArchiveMatchesBytesAndRejectsDigestChange() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val f=File.createTempFile("framework-fixture",".jar",ctx.cacheDir)
        try {
            val bytes=dex(true)
            f.writeBytes(bytes)
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val m=FrameworkCodeArchive.Match(f,f.length(),f.lastModified(),hash,setOf(FrameworkCodeArchive.targets.first()))
            val out=ByteArrayOutputStream()
            FrameworkCodeArchive.write(listOf(m),JSONObject()) { out }
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
                assertEquals("framework-1.jar",z.nextEntry.name)
                assertArrayEquals(bytes,z.readBytes())
                assertEquals("manifest.json",z.nextEntry.name)
                val row=JSONObject(String(z.readBytes(),Charsets.UTF_8)).getJSONArray("files").getJSONObject(0)
                assertEquals(hash,row.getString("sha256"))
                assertEquals(bytes.size.toLong(),row.getLong("bytes"))
                assertNull(z.nextEntry)
            }
            val bad=ByteArrayOutputStream()
            try {
                FrameworkCodeArchive.write(listOf(m.copy(sha="0".repeat(64))),JSONObject()) { bad }
                fail("changed digest")
            } catch (_: IllegalStateException) {}
            ZipInputStream(ByteArrayInputStream(bad.toByteArray())).use { z ->
                while(true) { val entry=z.nextEntry ?: break; assertNotEquals("manifest.json",entry.name); z.closeEntry() }
            }
        } finally { f.delete() }
    }
}
