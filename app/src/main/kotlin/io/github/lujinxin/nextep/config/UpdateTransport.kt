package io.github.x647368maker.atomicworkbench.config

import java.net.URL
import java.io.File
import java.io.IOException
import javax.net.ssl.HttpsURLConnection

/** Bounded HTTPS only; default platform TLS certificate/hostname verification stays enabled. */
internal object UpdateTransport {
    fun bytes(address: String, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        read(address, limit.toLong()) { bytes, n -> out.write(bytes, 0, n) }
        return out.toByteArray()
    }
    fun download(address: String, target: File, expectedSize: Long) {
        target.outputStream().use { out -> read(address, expectedSize) { bytes, n -> out.write(bytes, 0, n) } }
        require(target.length() == expectedSize) { "下载大小不符，请重试" }
    }
    private fun read(address: String, limit: Long, write: (ByteArray, Int) -> Unit) {
        var current = UpdatePolicy.secure(address)
        repeat(5) {
            check(!Thread.currentThread().isInterrupted) { "下载已取消" }
            val c = URL(current).openConnection() as HttpsURLConnection
            try {
                c.connectTimeout = 12000; c.readTimeout = 20000; c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("Cache-Control", "no-cache")
                c.setRequestProperty("User-Agent", "AtomicWorkbench-Update")
                c.setRequestProperty("Accept", if (URL(current).host == "raw.githubusercontent.com") "application/json" else "application/vnd.github+json, application/octet-stream")
                when(val code = c.responseCode) {
                    301, 302, 303, 307, 308 -> current = UpdatePolicy.secure(URL(URL(current), c.getHeaderField("Location") ?: error("更新重定向无效")).toString())
                    200 -> {
                        var total = 0L
                        c.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while(true) {
                                check(!Thread.currentThread().isInterrupted) { "下载已取消" }
                                val n = input.read(buffer); if(n < 0) break
                                total += n; require(total <= limit) { "更新文件超过允许大小" }; write(buffer, n)
                            }
                        }
                        return
                    }
                    else -> throw IOException("更新服务 HTTP $code，请稍后重试")
                }
            } finally { c.disconnect() }
        }
        error("更新重定向次数过多")
    }
}
