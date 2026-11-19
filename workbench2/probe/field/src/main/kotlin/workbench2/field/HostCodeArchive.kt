package workbench2.field

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import workbench2.nativebridge.AndroidHostInventory
import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One explicit, user-selected export of publicly readable installed code only.
 * Fixed target matches the actual stage 11A evidence; never follows an Intent
 * path, reads app data, invokes OEM code, or loads classes from an APK.
 * A missing or mismatched identity/blocked file access is a refusal.
 */
internal object HostCodeArchive {
    private const val HOST = "com.oplus.pscanvas"
    private const val FIRMWARE = "OnePlus/PMS110/OP64F1L1:17/CP2A.260605.016/C.147dc4b_1201fc3_11f9c73:user/release-keys"
    private const val SIGNER = "e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e"
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MAX_APKS = 32

    internal fun matches(report: JSONObject): Boolean {
        val signers = report.optJSONArray("current_signer_sha256")
        return report.optString("kind") == "PUBLIC_PACKAGE_INVENTORY" &&
            report.optString("result") == "VISIBLE_METADATA_ONLY" &&
            report.optString("host_package") == HOST && report.optString("firmware") == FIRMWARE &&
            report.optString("model") == "PMS110" && report.optInt("sdk") == 37 &&
            report.optString("host_version_name") == "2.0.0" && report.optLong("host_version_code") == 20L &&
            report.optBoolean("system_or_updated_system_app") && report.optBoolean("enabled") &&
            signers?.length() == 1 && signers.optString(0) == SIGNER
    }

    private fun getInfo(context: Context): PackageInfo =
        context.packageManager.getPackageInfo(HOST,PackageManager.PackageInfoFlags.of(
            (PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES).toLong()))

    private fun files(info: PackageInfo): List<File> {
        val app = requireNotNull(info.applicationInfo)
        val entries = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty().toList()
        require(entries.size in 1..MAX_APKS)
        val paths = entries.map { File(it).canonicalFile }
        require(paths.distinct().size == paths.size)
        require(paths.all { it.name.endsWith(".apk") && it.isFile && it.canRead() && it.length() in 1..MAX_BYTES })
        require(paths.sumOf { it.length() } <= MAX_BYTES)
        return paths
    }

    private fun components(info: PackageInfo): JSONArray {
        val list = mutableListOf<JSONObject>()
        fun add(kind: String, name: String, exported: Boolean, enabled: Boolean,
                permission: String?, read: String? = null, write: String? = null) {
            list += JSONObject().put("kind",kind).put("name",name).put("exported",exported)
                .put("enabled",enabled).put("permission",permission ?: JSONObject.NULL)
                .put("read_permission",read ?: JSONObject.NULL).put("write_permission",write ?: JSONObject.NULL)
        }
        info.activities.orEmpty().forEach { add("activity",it.name,it.exported,it.enabled,it.permission) }
        info.services.orEmpty().forEach { add("service",it.name,it.exported,it.enabled,it.permission) }
        info.receivers.orEmpty().forEach { add("receiver",it.name,it.exported,it.enabled,it.permission) }
        info.providers.orEmpty().forEach { add("provider",it.name,it.exported,it.enabled,null,it.readPermission,it.writePermission) }
        return JSONArray(list.sortedWith(compareBy({it.getString("kind")},{it.getString("name")})))
    }

    private fun permissions(context: Context, info: PackageInfo): JSONArray {
        val pm = context.packageManager
        val out = info.requestedPermissions.orEmpty().mapIndexed { index,name ->
            val flags = info.requestedPermissionsFlags?.getOrNull(index)
            val protection = try {
                pm.getPermissionInfo(name,0).protectionLevel
            } catch (_: PackageManager.NameNotFoundException) { null }
            catch (_: SecurityException) { null }
            JSONObject().put("name",name)
                .put("host_granted", flags?.let { it and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 } ?: JSONObject.NULL)
                .put("collector_granted",pm.checkPermission(name,context.packageName) == PackageManager.PERMISSION_GRANTED)
                .put("protection_level_raw",protection ?: JSONObject.NULL)
        }
        return JSONArray(out.sortedBy { it.getString("name") })
    }

    /** The stream factory is not called if current public identity/file access fails. */
    fun export(context: Context, open: () -> OutputStream) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val report = JSONObject(AndroidHostInventory.collect(context))
        require(matches(report)) { "Target identity no longer matches confirmed device" }
        val info = getInfo(context)
        val initial = files(info)
        val meta = JSONObject().put("schema",1).put("kind","PUBLIC_INSTALLED_HOST_CODE_ONLY")
            .put("source_sha",BuildConfig.SOURCE_SHA).put("inventory",report)
            .put("components",components(info)).put("requested_permissions",permissions(context,info))
            .put("last_update_time",info.lastUpdateTime)
            .put("production_admitted",false)
        write(initial,meta,open) {
            val after = getInfo(context)
            check(after.lastUpdateTime == info.lastUpdateTime && files(after) == initial)
            check(matches(JSONObject(AndroidHostInventory.collect(context))))
        }
    }

    /** Internal stream primitive. Package source selection stays in export(). */
    internal fun write(apks: List<File>, metadata: JSONObject, open: () -> OutputStream, verifyUnchanged: () -> Unit) {
        require(apks.size in 1..MAX_APKS && apks.distinct().size == apks.size)
        val sizes = apks.map { it.length() }
        require(sizes.all { it in 1..MAX_BYTES } && sizes.sum() <= MAX_BYTES)
        val rows = JSONArray()
        val buffer = ByteArray(64 * 1024)
        ZipOutputStream(open().buffered()).use { zip ->
            // APKs are already compressed. No additional costly compression needed.
            zip.setLevel(0)
            var total = 0L
            apks.forEachIndexed { index,file ->
                val name = if(index==0) "base.apk" else "split-$index.apk"
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n; total += n
                        if (count > sizes[index] || total > MAX_BYTES) throw IOException("Installed code changed or size limit")
                        digest.update(buffer,0,n)
                        zip.write(buffer,0,n)
                    }
                }
                check(count == sizes[index] && file.length() == sizes[index])
                zip.closeEntry()
                rows.put(JSONObject().put("file",name).put("bytes",count)
                    .put("sha256",digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
            }
            // A failed source recheck leaves no manifest. Treat any such ZIP as invalid.
            verifyUnchanged()
            metadata.put("files",rows)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
