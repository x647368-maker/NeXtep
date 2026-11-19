package workbench2.field

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class HostCodeArchiveTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun stockHostExportRefusesBeforeOpeningDocument() {
        var opened = false
        try {
            HostCodeArchive.export(ctx) { opened=true; ByteArrayOutputStream() }
            fail("Stock Android must not be admitted")
        } catch (_: IllegalArgumentException) {}
        assertFalse(opened)
    }

    @Test fun archiveContainsExactPublicFixtureCodeAndHash() {
        val app = ctx.packageManager.getApplicationInfo(ctx.packageName,PackageManager.ApplicationInfoFlags.of(0))
        val file = File(app.sourceDir)
        val out = ByteArrayOutputStream()
        var rechecked = false
        HostCodeArchive.write(listOf(file),JSONObject().put("fixture",true),{out}) { rechecked=true }
        assertTrue(rechecked)
        val entries = mutableMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name]=z.readBytes()
                z.closeEntry()
            }
        }
        assertEquals(setOf("base.apk","manifest.json"),entries.keys)
        assertArrayEquals(file.readBytes(),entries.getValue("base.apk"))
        val meta=JSONObject(String(entries.getValue("manifest.json"),Charsets.UTF_8))
        val row=meta.getJSONArray("files").getJSONObject(0)
        val hash=MessageDigest.getInstance("SHA-256").digest(entries.getValue("base.apk"))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("base.apk",row.getString("file"))
        assertEquals(file.length(),row.getLong("bytes"))
        assertEquals(hash,row.getString("sha256"))
    }

    @Test fun incompleteArchiveCannotContainManifest() {
        val file=File(ctx.applicationInfo.sourceDir)
        val out=ByteArrayOutputStream()
        try {
            HostCodeArchive.write(listOf(file),JSONObject(),{out}) { error("Fixture code changed") }
            fail("Must refuse changed package")
        } catch (_: IllegalStateException) {}
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e=z.nextEntry ?: break; names.add(e.name);z.closeEntry() }
        }
        assertEquals(listOf("base.apk"),names)
    }
}
