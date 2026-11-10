package io.github.x647368maker.atomicworkbench.config

/**
 * Current-build note parser. The APK builds this text from RELEASE_NOTES.md,
 * not from a remote page or a background update job.
 */
internal object StageEmbeddedReleaseNotes {
    fun parse(versionName: String, versionCode: Long, raw: String): StageChange? {
        val source = raw.replace("\r\n", "\n").trim()
        if (versionName.isBlank() || versionCode <= 0 || source.length !in 12..32768) return null
        // Fail closed for stale or mismatched notes; don't show another build's features.
        val matchingName = Regex("(?<![A-Za-z0-9.])${Regex.escape(versionName)}(?![A-Za-z0-9.-])")
        val matchingCode = Regex("(?<![0-9])${versionCode}(?![0-9])")
        if (!matchingName.containsMatchIn(source) || !matchingCode.containsMatchIn(source)) return null
        val paragraphs = source.split(Regex("\n[ \t]*\n"))
            .map { it.trim().replace(Regex("[ \t]*\n[ \t]*"), " ") }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return null
        val status = if ("-dev" in versionName) "当前安装开发版 · 真机验收以实际测试为准"
                     else "当前安装版 · 来自发布说明"
        return StageChange(
            version = versionName,
            title = "本次版本说明（构建时自动同步）",
            status = status,
            highlights = paragraphs,
            caution = "内容自动取自本次 APK 构建时的 RELEASE_NOTES.md。不能仅凭此说明认定所有功能已通过真机验收。"
        )
    }
}
