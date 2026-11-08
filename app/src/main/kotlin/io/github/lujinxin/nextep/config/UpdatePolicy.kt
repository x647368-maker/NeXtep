package io.github.x647368maker.atomicworkbench.config

import java.net.URI
import java.security.MessageDigest
import java.io.File

internal object UpdatePolicy {
    const val APP = "io.github.x647368maker.atomicworkbench"
    const val CERT = "b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd"
    const val REPO = "https://github.com/x647368-maker/NeXtep"
    const val MAX_APK = 256L * 1024 * 1024
    enum class Channel { DEV, STABLE }
    data class Version(val major: Long, val minor: Long, val patch: Long, val development: Boolean)
    fun version(raw: String): Version? {
        val m = Regex("^(?:atomic-)?[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(-dev(?:-[A-Za-z0-9]+)?)?$").matchEntire(raw) ?: return null
        return runCatching { Version(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong(), m.groupValues[4].isNotEmpty()) }.getOrNull()
    }
    data class Metadata(val versionName: String, val versionCode: Long, val channel: Channel, val sha256: String,
        val size: Long, val apkName: String, val certificate: String = CERT, val applicationId: String = APP) {
        fun validate() {
            val parsed = requireNotNull(UpdatePolicy.version(versionName)) { "版本格式无效" }
            require(versionCode > 0 && size in 1..MAX_APK && applicationId == APP && certificate == CERT) { "更新身份或大小无效" }
            require(parsed.development == (channel == Channel.DEV)) { "版本与渠道不一致" }
            require(Regex("^[a-f0-9]{64}$").matches(sha256)) { "缺少有效 SHA-256" }
            require(Regex("^[A-Za-z0-9._-]+\\.apk$").matches(apkName)) { "安装包名称无效" }
        }
    }
    fun eligible(metadata: Metadata, installed: Long, channel: Channel): Boolean {
        metadata.validate()
        return metadata.versionCode > installed && (channel == Channel.DEV || metadata.channel == Channel.STABLE)
    }
    fun secure(address: String): String {
        val u = URI(address)
        require(u.scheme == "https" && u.userInfo == null && u.fragment == null && (u.port == -1 || u.port == 443)) { "更新仅支持安全 HTTPS" }
        require(u.host in setOf("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "更新来源不受信任" }
        return address
    }
    fun asset(address: String): String {
        secure(address)
        val u = URI(address)
        require(u.host == "github.com" && u.path.startsWith("/x647368-maker/NeXtep/releases/download/") && ".." !in u.path.split('/')) { "不是本项目发布文件" }
        return address
    }
    fun digest(file: File): String = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    fun requireArchive(meta: Metadata, packageName: String, versionCode: Long, versionName: String,
        archiveSigners: Set<String>, installedSigners: Set<String>, installedCode: Long) {
        meta.validate()
        require(packageName == APP && versionCode == meta.versionCode && versionName == meta.versionName && versionCode > installedCode) { "安装包身份/版本不匹配" }
        require(archiveSigners == installedSigners && archiveSigners == setOf(CERT)) { "安装包签名不兼容" }
    }
}
