package workbench2.field

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** One user-initiated, bounded, read-only scan of public system framework JARs.
 * No class loading, invocation, dex extraction from odex, shell or elevated access.
 */
internal object FrameworkCodeArchive {
    private const val FINGERPRINT = "OnePlus/PMS110/OP64F1L1:17/CP2A.260605.016/C.147dc4b_1201fc3_11f9c73:user/release-keys"
    internal val targets = setOf(
        "Lcom/oplus/flexiblewindow/FlexibleTaskView;",
        "Lcom/oplus/flexiblewindow/FlexibleWindowManager;"
    )
    private val roots = listOf("/system/framework", "/system_ext/framework", "/product/framework",
        "/vendor/framework", "/odm/framework", "/system/system_ext/framework",
        "/system/product/framework", "/system/vendor/framework", "/my_product/framework",
        "/my_region/framework", "/my_company/framework", "/my_stock/framework",
        "/my_carrier/framework", "/my_preload/framework", "/my_engineering/framework")
    private val apexJar = Regex("/apex/[A-Za-z0-9_.@-]+/javalib/[^/]+\\.jar")
    private val dexName = Regex("classes([2-9]|[1-9][0-9]+)?\\.dex")
    private const val MAX_JAR = 96L * 1024 * 1024
    private const val MAX_DEX = 32L * 1024 * 1024
    private const val MAX_EXPORT = 128L * 1024 * 1024
    private const val MAX_SCAN = 768L * 1024 * 1024
    private const val MAX_CANDIDATES = 384
    private const val TIME_MS = 60_000L
    private val busy = AtomicBoolean(false)

    data class Match(val file: File, val size: Long, val modified: Long, val sha: String, val definitions: Set<String>)
    data class Scan(val matches: List<Match>, val inspected: Int, val skipped: Int,
        val noDex: Int, val limited: Boolean, val candidates: Int) {
        val definitions: Set<String> get() = matches.flatMap { it.definitions }.toSet()
        fun summary(): String = "扫描 $inspected/$candidates，跳过 $skipped，无 DEX $noDex；命中 ${matches.size} 个文件 / ${definitions.size}/2 类" +
            if (limited) "；触及扫描限额，结果不完整" else ""
    }

    internal fun isTarget(): Boolean = Build.MODEL == "PMS110" && Build.VERSION.SDK_INT == 37 &&
        Build.FINGERPRINT == FINGERPRINT

    internal fun allowed(path: String): Boolean = path.endsWith(".jar") &&
        (roots.any { path.substringBeforeLast('/') == it } || apexJar.matches(path))

    private fun candidates(): List<File> {
        val out = linkedSetOf<File>()
        fun add(path: String) {
            if (!allowed(path)) return
            val file = try { File(path).canonicalFile } catch (_: IOException) { return }
            if (allowed(file.path)) out.add(file)
        }
        // Actual runtime boot classpath first, then only fixed framework directories.
        System.getenv("BOOTCLASSPATH").orEmpty().split(':').forEach { add(it) }
        for (root in roots) {
            File(root).listFiles()?.sortedBy { it.name }?.forEach { add(it.path) }
        }
        return out.toList()
    }

    /** Standard single-header DEX only. A string/type reference is never a definition.
     * Malformed, compact, or newer multi-header DEX is unsupported, not a negative match.
     */
    internal fun definedTargets(bytes: ByteArray): Set<String> {
        require(bytes.size >= 112)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.copyOfRange(0,4).contentEquals(byteArrayOf(100,101,120,10)))
        val version = String(bytes,4,3,Charsets.US_ASCII)
        require(version in setOf("035","037","038","039","040") && bytes[7] == 0.toByte())
        require(b.getInt(32) == bytes.size && b.getInt(36) == 112 && b.getInt(40) == 0x12345678)
        fun table(countAt: Int, offsetAt: Int, width: Int): Pair<Int,Int> {
            val count = b.getInt(countAt)
            val offset = b.getInt(offsetAt)
            require(count >= 0 && offset >= 0 && (count == 0 || offset >= 112))
            require(offset.toLong() + count.toLong() * width <= bytes.size)
            return count to offset
        }
        val (strings, stringsAt) = table(56,60,4)
        val (types, typesAt) = table(64,68,4)
        val (classes, classesAt) = table(96,100,32)
        val result = mutableSetOf<String>()
        for (i in 0 until classes) {
            val type = b.getInt(classesAt + i*32)
            require(type in 0 until types)
            val string = b.getInt(typesAt + type*4)
            require(string in 0 until strings)
            var p = b.getInt(stringsAt + string*4)
            require(p in 112 until bytes.size)
            var end = false
            for (n in 0 until 5) {
                require(p < bytes.size)
                if ((bytes[p++].toInt() and 128) == 0) { end = true; break }
            }
            require(end)
            for (target in targets) {
                if (target.length < bytes.size - p &&
                    target.indices.all { bytes[p+it].toInt() == target[it].code } &&
                    bytes[p+target.length] == 0.toByte()) result.add(target)
            }
        }
        return result
    }

    fun scan(): Scan {
        check(Looper.myLooper() != Looper.getMainLooper() && isTarget())
        check(busy.compareAndSet(false,true)) { "Export already running" }
        try {
            val all = candidates()
            val start = SystemClock.elapsedRealtime()
            var budget = MAX_SCAN
            var skipped = 0
            var inspected = 0
            var noDex = 0
            var limited = all.size > MAX_CANDIDATES
            val matches = mutableListOf<Match>()
            for (file in all.take(MAX_CANDIDATES)) {
                if (budget <= 0L || SystemClock.elapsedRealtime()-start >= TIME_MS) { limited=true; break }
                if (!file.isFile || !file.canRead() || file.length() !in 1..MAX_JAR) { skipped++; continue }
                try {
                    val size = file.length()
                    val modified = file.lastModified()
                    val found = mutableSetOf<String>()
                    ZipFile(file).use { zip ->
                        val entries = zip.entries().asSequence().filter { dexName.matches(it.name) }.take(17).toList()
                        require(entries.size <= 16)
                        if (entries.isEmpty()) noDex++
                        for (entry in entries) {
                            require(entry.size in 1..MAX_DEX && entry.size <= budget)
                            budget -= entry.size
                            val payload = ByteArray(entry.size.toInt())
                            zip.getInputStream(entry).use { input ->
                                var pos = 0
                                while (pos < payload.size) {
                                    if (SystemClock.elapsedRealtime()-start >= TIME_MS) throw IOException("Scan limit")
                                    val n = input.read(payload,pos,payload.size-pos)
                                    if (n <= 0) throw IOException("Incomplete DEX")
                                    pos += n
                                }
                                require(input.read() == -1)
                            }
                            found.addAll(definedTargets(payload))
                        }
                    }
                    if (found.isNotEmpty()) {
                        if (matches.sumOf { it.size } + size > MAX_EXPORT || budget < size) { limited=true; break }
                        budget -= size
                        val sha = digest(file,start)
                        check(file.length()==size && file.lastModified()==modified)
                        matches.add(Match(file,size,modified,sha,found.toSet()))
                    }
                    inspected++
                } catch (_: Exception) { skipped++ }
            }
            return Scan(matches.toList(),inspected,skipped,noDex,limited,all.size)
        } finally { busy.set(false) }
    }

    private fun digest(file: File, start: Long): String {
        val md=MessageDigest.getInstance("SHA-256")
        val buf=ByteArray(64*1024)
        var total=0L
        file.inputStream().use { input ->
            while(true) {
                check(SystemClock.elapsedRealtime()-start < TIME_MS)
                val n=input.read(buf)
                if(n<0) break
                total+=n
                check(total<=MAX_JAR)
                md.update(buf,0,n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun export(scan: Scan, open: () -> OutputStream) {
        check(Looper.myLooper()!=Looper.getMainLooper() && isTarget())
        check(busy.compareAndSet(false,true))
        try {
            require(scan.matches.all { allowed(it.file.path) && it.file.canonicalFile == it.file && it.file.canRead() })
            val metadata = JSONObject().put("schema",1).put("kind","PUBLIC_FRAMEWORK_DEFINITIONS_ONLY")
                .put("source_sha",BuildConfig.SOURCE_SHA).put("firmware",Build.FINGERPRINT)
                .put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT)
                .put("scanned",scan.inspected).put("skipped",scan.skipped).put("no_dex",scan.noDex)
                .put("candidates",scan.candidates).put("scan_limited",scan.limited)
                .put("target_definitions",JSONArray(scan.definitions.sorted()))
                .put("both_definitions_found",scan.definitions == targets)
                .put("production_admitted",false).put("oem_method_calls",0)
            write(scan.matches,metadata,open)
        } finally { busy.set(false) }
    }

    // Testable archive primitive; only production export() selects system sources.
    internal fun write(matches: List<Match>, metadata: JSONObject, open: ()->OutputStream) {
        require(matches.isNotEmpty() && matches.map { it.file }.distinct().size == matches.size)
        require(matches.all { it.size in 1..MAX_JAR && it.definitions.isNotEmpty() &&
            targets.containsAll(it.definitions) && it.file.length()==it.size && it.file.lastModified()==it.modified })
        require(matches.sumOf { it.size } <= MAX_EXPORT)
        val start=SystemClock.elapsedRealtime()
        val rows=JSONArray()
        val buf=ByteArray(64*1024)
        ZipOutputStream(open().buffered()).use { zip ->
            zip.setLevel(0)
            for ((index,m) in matches.withIndex()) {
                val name="framework-${index+1}.jar"
                zip.putNextEntry(ZipEntry(name))
                val md=MessageDigest.getInstance("SHA-256")
                var count=0L
                m.file.inputStream().use { input ->
                    while(true) {
                        check(SystemClock.elapsedRealtime()-start < TIME_MS)
                        val n=input.read(buf)
                        if(n<0) break
                        count+=n
                        check(count<=m.size)
                        md.update(buf,0,n)
                        zip.write(buf,0,n)
                    }
                }
                check(count==m.size && m.file.length()==m.size && m.file.lastModified()==m.modified && hex(md.digest())==m.sha)
                zip.closeEntry()
                rows.put(JSONObject().put("file",name).put("system_path",m.file.path)
                    .put("bytes",count).put("sha256",m.sha).put("definitions",JSONArray(m.definitions.sorted())))
            }
            metadata.put("files",rows)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
