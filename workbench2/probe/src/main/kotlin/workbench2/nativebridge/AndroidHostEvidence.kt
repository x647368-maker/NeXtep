package workbench2.nativebridge

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/** Android-only source. Explicit one-shot diagnostic, never installed as a hook.
 * Caller must use an existing authorized host diagnostic worker. No launch, task query,
 * service call, private field read, input change, Surface creation, timer or upload. */
object AndroidHostEvidence {
    private const val HOST = "com.oplus.pscanvas"
    @Suppress("DEPRECATION")
    fun collect(context: Context): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Do not reflect on the host UI thread" }
        val process = Application.getProcessName()
        val uidMatches = context.applicationInfo.uid == Process.myUid()
        val report = JSONObject().put("schema",1)
            .put("package",context.packageName).put("process",process)
            .put("uid_matches_context",uidMatches).put("model",Build.MODEL)
            .put("sdk",Build.VERSION.SDK_INT).put("firmware",Build.FINGERPRINT)
            .put("read_only",true).put("oem_method_calls",0)
            .put("authorized_embedding",JSONObject.NULL).put("native_return_verified",JSONObject.NULL)
            .put("native_input_verified",JSONObject.NULL).put("production_admitted",false)
        if(context.packageName!=HOST || process!=HOST || !uidMatches) {
            return report.put("result","WRONG_HOST").toString(2)
        }
        val info = try { context.packageManager.getPackageInfo(HOST,0) }
        catch (e:Exception) { return report.put("result","HOST_METADATA_UNAVAILABLE")
            .put("error",e.javaClass.simpleName).toString(2) }
        val version = "${info.versionName.orEmpty()}/${info.longVersionCode}"
        report.put("host_version",version)
        val probe = NativeSymbolProbe(context.classLoader)
        val basic = probe.inspect(Build.FINGERPRINT,version)
        val details = probe.inspectLifecycleCandidates()
        report.put("basic_present",JSONArray(basic.present))
            .put("basic_unresolved",JSONArray(basic.missing))
            .put("basic_resolved",basic.symbolsResolved)
            .put("lifecycle_metadata",JSONArray().apply {
                details.forEach { put(JSONObject().put("group",it.group).put("symbol",it.label)
                    .put("status",it.status).put("detail",it.detail)) }
            })
            .put("result","METADATA_ONLY")
        return report.toString(2)
    }
}
