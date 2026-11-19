package workbench2.nativebridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Ordinary application-side preflight; no OEM class loading or host invocation.
 * Explicit user action on an existing diagnostic worker only. Returns to caller,
 * never writes a file, starts a component, requests a permission or uploads.
 * Visibility and certificate identity are NOT proof of embedding authorization.
 */
object AndroidHostInventory {
    private const val HOST = "com.oplus.pscanvas"
    fun collect(context: Context): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Do not query package metadata on UI thread" }
        val report = JSONObject().put("schema", 1)
            .put("kind", "PUBLIC_PACKAGE_INVENTORY")
            .put("host_package", HOST)
            .put("firmware", Build.FINGERPRINT)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("read_only", true)
            .put("oem_method_calls", 0)
            .put("authorized_embedding", JSONObject.NULL)
            .put("native_return_verified", JSONObject.NULL)
            .put("native_input_verified", JSONObject.NULL)
            .put("production_admitted", false)
        val info = try {
            context.packageManager.getPackageInfo(HOST, PackageManager.PackageInfoFlags.of(
                PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } catch (_: PackageManager.NameNotFoundException) {
            // Package visibility can hide an installed package; do NOT infer absence.
            return report.put("result", "NOT_VISIBLE_OR_ABSENT").toString(2)
        } catch (_: SecurityException) {
            return report.put("result", "METADATA_ACCESS_DENIED").toString(2)
        }
        val app = info.applicationInfo
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
            .map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            }.distinct().sorted()
        return report
            .put("host_version_name", info.versionName ?: JSONObject.NULL)
            .put("host_version_code", info.longVersionCode)
            .put("system_or_updated_system_app", app?.let {
                it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } ?: JSONObject.NULL)
            .put("enabled", app?.enabled ?: JSONObject.NULL)
            .put("current_signer_sha256", JSONArray(signers))
            .put("result", "VISIBLE_METADATA_ONLY").toString(2)
    }
}
