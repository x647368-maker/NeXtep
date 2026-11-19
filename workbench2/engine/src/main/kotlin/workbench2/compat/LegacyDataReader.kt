package workbench2.compat

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.util.Collections

/**
 * Pure, read-only compatibility boundary for an already authorized caller's
 * atomic_settings values and an already obtained legacy workgroup reply.
 * Never opens a preference store, sends IPC, starts a task or writes data.
 * This is NOT a storage migration or a live task identity provider.
 */
object LegacyDataReader {
    private const val MAX_TEXT = 1_048_576
    private const val MAX_KEYS = 512
    private const val MAX_JSON_NODES = 16_384
    private const val MAX_DEPTH = 24

    data class Settings(val original: Map<String, Any>, val topApps: List<String>?)
    data class Member(val component: String, val userId: Int, val historicalTaskId: Int)
    data class Group(val id: String?, val name: String, val main: Member?,
        val slots: List<Member?>, val depthOrder: List<Int>)
    data class Groups(val originalJson: String, val activeId: String?, val switching: Boolean,
        val entries: List<Group>) {
        // Missing legacy IDs must be resolved later by a separately authorized
        // transactional migration. Never synthesize or write them during reading.
        val readyForPlanning: Boolean get() = !switching && entries.all { it.id != null }
    }

    private val strings = setOf("top_title", "top_title_draft", "top_content_mode",
        "top_apps", "top_text_font")
    private val booleans = setOf("top_show_seconds", "manual_app_order", "top_text_scroll",
        "top_text_bold", "status_bar_gesture_enabled", "slot_background_swipe_enabled",
        "auto_minimize_main_on_top_app_switch", "navigation_buttons_enabled",
        "four_finger_gesture_enabled", "four_finger_gesture_block_games")
    private val ints = setOf("frost_strength", "top_text_size",
        "window_swap_animation_duration_ms")
    private const val BLACKLIST = "four_finger_gesture_blacklist"

    /** Preserve all supported SharedPreferences types, including unknown keys.
     * Missing keys remain absent; no defaults, normalization or truncation.
     */
    fun settings(values: Map<String, *>): Settings {
        require(values.size <= MAX_KEYS) { "Too many settings" }
        var budget = MAX_TEXT.toLong()
        fun account(s: String) {
            budget -= s.length.toLong()
            require(budget >= 0L) { "Settings too large" }
        }
        val copy = linkedMapOf<String, Any>()
        for ((key, value) in values) {
            account(key)
            val v: Any = when (value) {
                is String -> value.also { account(it) }
                is Boolean, is Int, is Long -> value
                is Float -> value.also { require(it.isFinite()) }
                is Set<*> -> {
                    require(value.size <= MAX_KEYS)
                    val set = linkedSetOf<String>()
                    for (item in value) {
                        require(item is String) { "Invalid string set" }
                        account(item)
                        set.add(item)
                    }
                    Collections.unmodifiableSet(set)
                }
                else -> throw IllegalArgumentException("Unsupported preference type")
            }
            require(when (key) {
                in strings -> v is String
                in booleans -> v is Boolean
                in ints -> v is Int
                BLACKLIST -> v is Set<*>
                else -> true
            }) { "Wrong legacy preference type" }
            copy[key] = v
        }
        val apps = (copy["top_apps"] as? String)?.split('\n')?.filter { it.isNotBlank() }
        return Settings(Collections.unmodifiableMap(copy), apps?.let(::immutableList))
    }

    /**
     * Reads the existing settingsSnapshot() object (active/switching/groups).
     * Exact original JSON is retained, including unrecognized fields.
     * No task ID is treated as an authority token or a launch instruction.
     */
    fun groups(raw: String): Groups {
        require(raw.length in 1..MAX_TEXT) { "Invalid snapshot size" }
        val root = try {
            JsonReader(StringReader(raw)).use { reader ->
                reader.isLenient = false
                var remaining = MAX_JSON_NODES
                fun read(depth: Int): Any? {
                    require(depth <= MAX_DEPTH && --remaining >= 0) { "Snapshot too complex" }
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            val map = linkedMapOf<String, Any?>()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                require(!map.containsKey(key)) { "Duplicate JSON field" }
                                map[key] = read(depth + 1)
                            }
                            reader.endObject()
                            map
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            val list = mutableListOf<Any?>()
                            while (reader.hasNext()) list.add(read(depth + 1))
                            reader.endArray()
                            list
                        }
                        JsonToken.STRING -> reader.nextString()
                        JsonToken.NUMBER -> NumberText(reader.nextString())
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> { reader.nextNull(); null }
                        else -> throw IllegalArgumentException("Invalid JSON value")
                    }
                }
                val value = read(0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing data" }
                obj(value)
            }
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("Invalid JSON snapshot", e)
        }
        // Android JSONObject.put(key, null) omits the key. Older active IDs
        // may be absent; a literal string "null" is not a valid group ID.
        val active = nullableId(root, "active")
        val switching = root["switching"] as? Boolean
            ?: throw IllegalArgumentException("Missing switching state")
        val entries = arr(root["groups"])
        require(entries.size <= 20) { "Unsupported workgroup count" }
        val ids = hashSetOf<String>()
        val result = entries.map { item ->
            val g = obj(item)
            require(int(g["version"]) == 1) { "Unsupported workgroup version" }
            val id = nullableId(g, "id")
            if (id != null) require(ids.add(id)) { "Duplicate workgroup ID" }
            val name = g["name"] as? String ?: throw IllegalArgumentException("Missing name")
            require(g.containsKey("main")) { "Missing main slot" }
            val slots = arr(g["slots"])
            require(slots.size == 4) { "Invalid slot count" }
            val depth = if (!g.containsKey("depth_order")) listOf(0,1,2,3)
                else arr(g["depth_order"]).map(::int)
            require(depth.sorted() == listOf(0,1,2,3)) { "Invalid depth order" }
            Group(id, name, member(g["main"]), immutableList(slots.map(::member)), immutableList(depth))
        }
        require(active == null || active in ids) { "Unknown active group" }
        return Groups(raw, active, switching, immutableList(result))
    }

    private data class NumberText(val text: String)
    private fun int(value: Any?): Int {
        val text = (value as? NumberText)?.text ?: throw IllegalArgumentException("Expected integer")
        require(text.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Expected integer" }
        return text.toIntOrNull() ?: throw IllegalArgumentException("Integer out of bounds")
    }
    private fun obj(value: Any?): Map<*, *> =
        value as? Map<*, *> ?: throw IllegalArgumentException("Expected object")
    private fun arr(value: Any?): List<*> =
        value as? List<*> ?: throw IllegalArgumentException("Expected array")
    private fun nullableId(value: Map<*, *>, key: String): String? {
        if (value[key] == null) return null
        val id = value[key] as? String ?: throw IllegalArgumentException("Invalid group ID")
        require(id.isNotBlank() && id != "null") { "Invalid group ID" }
        return id
    }
    private fun member(value: Any?): Member? {
        if (value == null) return null
        val m = obj(value)
        val component = m["component"] as? String ?: throw IllegalArgumentException("Missing component")
        val parts = component.split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() && it.none(Char::isWhitespace) }) {
            "Invalid component"
        }
        val user = int(m["userId"])
        val task = int(m["taskId"])
        require(user >= 0 && task >= 0) { "Negative legacy identity" }
        return Member(component,user,task)
    }
    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
