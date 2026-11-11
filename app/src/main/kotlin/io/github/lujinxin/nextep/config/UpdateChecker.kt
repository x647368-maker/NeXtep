package io.github.x647368maker.atomicworkbench.config

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The lightweight public update feed is preferred over the GitHub Releases API.
 * This avoids shared-IP API rate limits (HTTP 403) on client devices.
 * Every offered APK still goes through the fixed package, signer, size and digest checks.
 */
/**
 * An empty or irrelevant feed is not evidence that the installed app is current.
 * Keep this decision pure so we can exercise the failover without network calls.
 */
internal object UpdateFeedAuthority {
    fun validSize(total: Int) = total in 1..40
    fun hasApplicableRecords(total: Int, applicable: Int) =
        validSize(total) && applicable in 1..total

    // A well-formed index can be stale just after a new Release is published.
    // Never treat a null result as authoritative; confirm with Releases API.
    fun hasConfirmedUpgrade(feedSucceeded: Boolean, updateFound: Boolean) =
        feedSucceeded && updateFound
}

internal object UpdateChecker {
    const val RELEASES_URL = UpdatePolicy.REPO + "/releases"
    const val FEED_URL = "https://raw.githubusercontent.com/x647368-maker/NeXtep/atomic-update-feed/index.json"
    private const val API_URL = "https://api.github.com/repos/x647368-maker/NeXtep/releases?per_page=100"

    data class Release(val metadata: UpdatePolicy.Metadata, val notes: String, val downloadUrl: String, val releaseUrl: String) {
        val version get() = metadata.versionName
    }

    fun check(channel: UpdatePolicy.Channel, installedCode: Long): Release? {
        // A populated, validated feed is authoritative for the selected channel.
        // An empty or channel-inapplicable feed MUST fall back to Releases API
        // rather than reporting a false "already latest" result.
        val feed = runCatching { checkFeed(channel, installedCode) }
        if (UpdateFeedAuthority.hasConfirmedUpgrade(feed.isSuccess, feed.getOrNull() != null))
            return feed.getOrThrow()
        val api = runCatching { checkReleasesApi(channel, installedCode) }
        if (api.isSuccess) return api.getOrThrow()
        throw IOException(
            "更新源暂不可用；备用：${feed.exceptionOrNull()?.message.orEmpty().take(120)}；" +
                "GitHub API：${api.exceptionOrNull()?.message.orEmpty().take(120)}",
            api.exceptionOrNull()
        )
    }

    /** Single small JSON request; not a push service and never a background poll. */
    private fun checkFeed(channel: UpdatePolicy.Channel, installedCode: Long): Release? {
        val root = JSONObject(UpdateTransport.bytes(FEED_URL, 128 * 1024).toString(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == 1) { "更新索引格式不支持" }
        val entries = root.getJSONArray("releases")
        require(UpdateFeedAuthority.validSize(entries.length())) { "更新索引为空或过大，改用 Releases API" }
        val eligible = mutableListOf<Release>()
        var applicable = 0
        for (i in 0 until entries.length()) {
            val json = entries.getJSONObject(i)
            val meta = metadata(json)
            val tag = json.getString("releaseTag")
            require(Regex("^[A-Za-z0-9._-]{1,128}$").matches(tag)) { "更新标签无效" }
            val page = "$RELEASES_URL/tag/$tag"
            val apk = UpdatePolicy.asset("$RELEASES_URL/download/$tag/${meta.apkName}")
            if (channel == UpdatePolicy.Channel.DEV || meta.channel == UpdatePolicy.Channel.STABLE) applicable++
            if (UpdatePolicy.eligible(meta, installedCode, channel)) {
                eligible += Release(meta, json.optString("releaseNotes").take(16000), apk, page)
            }
        }
        require(UpdateFeedAuthority.hasApplicableRecords(entries.length(), applicable)) { "更新索引没有当前渠道的有效发布记录，改用 Releases API" }
        return eligible.maxByOrNull { it.metadata.versionCode }
    }

    private fun checkReleasesApi(channel: UpdatePolicy.Channel, installedCode: Long): Release? {
        val releases = JSONArray(UpdateTransport.bytes(API_URL, 2 * 1024 * 1024).toString(Charsets.UTF_8))
        val candidates = mutableListOf<Release>()
        var fetched = 0
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || (release.optBoolean("prerelease") && channel == UpdatePolicy.Channel.STABLE)) continue
            val assets = release.optJSONArray("assets") ?: continue
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("state") == "uploaded" }
            val manifests = all.filter { it.optString("name") == "atomic-update.json" }
            // Historic debug APKs lacking verified versionCode/signing metadata are never offered.
            if (manifests.size != 1 || fetched++ >= 10) continue
            val manifestUrl = UpdatePolicy.asset(manifests.single().getString("browser_download_url"))
            val json = JSONObject(UpdateTransport.bytes(manifestUrl, 64 * 1024).toString(Charsets.UTF_8))
            val meta = metadata(json)
            val apk = all.singleOrNull { it.optString("name") == meta.apkName } ?: error("发布缺少唯一匹配 APK")
            require(apk.getLong("size") == meta.size) { "发布大小与校验元数据不符" }
            val download = UpdatePolicy.asset(apk.getString("browser_download_url"))
            val page = release.getString("html_url")
            require(page.startsWith(RELEASES_URL + "/tag/")) { "发布页面来源无效" }
            if (UpdatePolicy.eligible(meta, installedCode, channel)) {
                candidates += Release(meta, json.optString("releaseNotes", release.optString("body")).take(16000), download, page)
            }
        }
        return candidates.maxByOrNull { it.metadata.versionCode }
    }

    private fun metadata(j: JSONObject): UpdatePolicy.Metadata {
        require(j.getInt("schemaVersion") == 1) { "更新元数据版本不支持" }
        return UpdatePolicy.Metadata(
            j.getString("versionName"), j.getLong("versionCode"),
            when (j.getString("channel")) {
                "dev" -> UpdatePolicy.Channel.DEV
                "stable" -> UpdatePolicy.Channel.STABLE
                else -> error("渠道无效")
            },
            j.getString("sha256"), j.getLong("size"), j.getString("apkAsset"),
            j.getString("certificateSha256"), j.getString("applicationId")
        ).also { it.validate() }
    }

    fun encode(r: Release): String = JSONObject().apply {
        val m = r.metadata
        put("schemaVersion", 1)
        put("versionName", m.versionName); put("versionCode", m.versionCode)
        put("channel", if (m.channel == UpdatePolicy.Channel.DEV) "dev" else "stable")
        put("sha256", m.sha256); put("size", m.size); put("apkAsset", m.apkName)
        put("certificateSha256", m.certificate); put("applicationId", m.applicationId)
        put("notes", r.notes); put("download", r.downloadUrl); put("release", r.releaseUrl)
    }.toString()

    fun decode(raw: String): Release {
        val j = JSONObject(raw)
        val releaseUrl = requireHttps(j.getString("release"))
        require(releaseUrl.startsWith(RELEASES_URL + "/tag/")) { "发布页面来源无效" }
        return Release(metadata(j), j.getString("notes"), UpdatePolicy.asset(j.getString("download")), releaseUrl)
    }

    fun requireHttps(value: String): String {
        val u = java.net.URI(value)
        require(u.scheme == "https" && u.host != null && u.userInfo == null)
        return value
    }
}
