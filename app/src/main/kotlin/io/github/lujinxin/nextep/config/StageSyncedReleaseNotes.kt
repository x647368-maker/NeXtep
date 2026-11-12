package io.github.x647368maker.atomicworkbench.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight offline cache of notes obtained during an existing successful
 * update check. No second request, background job or device identifier.
 * Only stores notes accompanying releases with validated update metadata.
 */
internal object StageSyncedReleaseNotes {
    private const val PREFS = "atomic_synced_release_notes_v1"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 8
    private const val MAX_NOTES = 8000

    fun capture(context: Context, release: UpdateChecker.Release) {
        runCatching {
            release.metadata.validate()
            val notes = release.notes.trim().take(MAX_NOTES)
            if (notes.isBlank()) return
            val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = runCatching { JSONArray(store.getString(KEY, "[]")) }.getOrDefault(JSONArray())
            val entries = linkedMapOf<Long, JSONObject>()
            val item = JSONObject().put("versionName",release.metadata.versionName)
                .put("versionCode",release.metadata.versionCode)
                .put("notes",notes)
            entries[release.metadata.versionCode] = item
            for (i in 0 until existing.length()) {
                val obj = existing.optJSONObject(i) ?: continue
                val code = obj.optLong("versionCode",-1)
                if (code > 0 && code !in entries && obj.optString("notes").length in 1..MAX_NOTES)
                    entries[code] = obj
            }
            val array = JSONArray()
            entries.values.sortedByDescending { it.optLong("versionCode") }.take(MAX_ENTRIES).forEach { array.put(it) }
            store.edit().putString(KEY,array.toString()).apply()
        }
    }

    fun load(context: Context): List<StageChange> = runCatching {
        val store = context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val array = JSONArray(store.getString(KEY,"[]"))
        (0 until minOf(array.length(),MAX_ENTRIES)).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("versionName")
            val versionCode = obj.optLong("versionCode")
            val notes = obj.optString("notes").trim()
            if (UpdatePolicy.version(name)==null || versionCode<=0 || notes.length !in 1..MAX_NOTES)
                return@mapNotNull null
            val highlights = notes.replace("\r\n","\n").split(Regex("\\n[ \\t]*\\n|(?<=。)"))
                .map { it.trim() }.filter { it.isNotBlank() }.take(16)
            StageChange(
                version=name,
                title="v$name 发布更新说明",
                status="在线同步 · 来自已验证的更新元数据",
                highlights=highlights.ifEmpty { listOf(notes.take(240)) },
                caution="发布说明来自官方更新源。功能是否完全兼容仍以实际设备为准。"
            )
        }
    }.getOrDefault(emptyList())
}
