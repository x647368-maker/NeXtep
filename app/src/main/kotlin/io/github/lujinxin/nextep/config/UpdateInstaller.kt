package io.github.x647368maker.atomicworkbench.config

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

internal object UpdateInstaller {
    private fun signatures(info: android.content.pm.PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners.orEmpty().mapTo(mutableSetOf()) {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }
    fun verify(context: Context, file: File, release: UpdateChecker.Release) {
        val m=release.metadata
        require(file.length()==m.size && UpdatePolicy.digest(file)==m.sha256) { "安装包 SHA-256 或大小不符，已拒绝安装" }
        val pm=context.packageManager
        val archive=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("无法解析安装包")
        val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        UpdatePolicy.requireArchive(m,archive.packageName,archive.longVersionCode,archive.versionName.orEmpty(),signatures(archive),signatures(installed),installed.longVersionCode)
    }
    @Synchronized
    fun download(context: Context, release: UpdateChecker.Release): File {
        val folder=File(context.cacheDir,"atomic-updates").apply { mkdirs() }
        val file=File(folder,"stage-${release.metadata.versionCode}.apk")
        if(file.exists()) { if(runCatching { verify(context,file,release) }.isSuccess) return file;file.delete() }
        val part=File(folder,"download.part")
        try {
            var failure: java.io.IOException?=null
            for(attempt in 0..1) {
                try { UpdateTransport.download(release.downloadUrl,part,release.metadata.size);failure=null;break }
                catch(e: java.io.IOException) { failure=e;part.delete();if(Thread.currentThread().isInterrupted) throw e }
            }
            failure?.let { throw it }
            verify(context,part,release)
            check(part.renameTo(file)) { "无法保存验证后的更新" }
            return file
        } finally { part.delete() }
    }
    fun install(context: Context,file: File,release: UpdateChecker.Release) {
        verify(context,file,release) // Recheck immediately before handing a read-only URI to the system installer.
        check(context.packageManager.canRequestPackageInstalls()) { "请先允许安装此来源的应用，再点击安装" }
        val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
