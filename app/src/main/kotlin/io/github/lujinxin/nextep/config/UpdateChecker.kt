package io.github.x647368maker.atomicworkbench.config

import org.json.JSONArray
import org.json.JSONObject

internal object UpdateChecker {
    const val RELEASES_URL = UpdatePolicy.REPO + "/releases"
    private const val API_URL = "https://api.github.com/repos/x647368-maker/NeXtep/releases?per_page=100"
    data class Release(val metadata: UpdatePolicy.Metadata, val notes: String, val downloadUrl: String, val releaseUrl: String) {
        val version get() = metadata.versionName
    }
    fun check(channel: UpdatePolicy.Channel, installedCode: Long): Release? {
        val releases = JSONArray(UpdateTransport.bytes(API_URL, 2 * 1024 * 1024).toString(Charsets.UTF_8))
        val candidates = mutableListOf<Release>()
        var fetched = 0
        for(index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if(release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("state") == "uploaded" }
            val manifests = all.filter { it.optString("name") == "atomic-update.json" }
            // Legacy tags/APKs without explicit versionCode and integrity metadata are never offered.
            if(manifests.size != 1 || fetched++ >= 10) continue
            val manifestUrl = UpdatePolicy.asset(manifests.single().getString("browser_download_url"))
            val json = JSONObject(UpdateTransport.bytes(manifestUrl, 64 * 1024).toString(Charsets.UTF_8))
            val meta = metadata(json)
            val apk = all.singleOrNull { it.optString("name") == meta.apkName } ?: error("发布缺少唯一匹配 APK")
            require(apk.getLong("size") == meta.size) { "发布大小与校验元数据不符" }
            val download = UpdatePolicy.asset(apk.getString("browser_download_url"))
            val page = release.getString("html_url")
            require(page.startsWith(RELEASES_URL + "/tag/")) { "发布页面来源无效" }
            if(UpdatePolicy.eligible(meta, installedCode, channel)) candidates += Release(meta,
                json.optString("releaseNotes", release.optString("body")).take(16000), download, page)
        }
        return candidates.maxByOrNull { it.metadata.versionCode }
    }
    private fun metadata(j: JSONObject): UpdatePolicy.Metadata {
        require(j.getInt("schemaVersion") == 1) { "更新元数据版本不支持" }
        return UpdatePolicy.Metadata(j.getString("versionName"), j.getLong("versionCode"),
            when(j.getString("channel")) { "dev" -> UpdatePolicy.Channel.DEV; "stable" -> UpdatePolicy.Channel.STABLE; else -> error("渠道无效") },
            j.getString("sha256"), j.getLong("size"), j.getString("apkAsset"), j.getString("certificateSha256"), j.getString("applicationId")).also { it.validate() }
    }
    fun encode(r: Release): String = JSONObject().apply {
        val m = r.metadata
        put("schemaVersion", 1); put("versionName",m.versionName);put("versionCode",m.versionCode);put("channel",if(m.channel == UpdatePolicy.Channel.DEV) "dev" else "stable")
        put("sha256",m.sha256);put("size",m.size);put("apkAsset",m.apkName);put("certificateSha256",m.certificate);put("applicationId",m.applicationId)
        put("notes",r.notes);put("download",r.downloadUrl);put("release",r.releaseUrl)
    }.toString()
    fun decode(raw: String): Release { val j=JSONObject(raw);return Release(metadata(j),j.getString("notes"),UpdatePolicy.asset(j.getString("download")),j.getString("release")) }
    fun requireHttps(value: String): String { val u=java.net.URI(value);require(u.scheme=="https" && u.host!=null && u.userInfo==null);return value }
}
