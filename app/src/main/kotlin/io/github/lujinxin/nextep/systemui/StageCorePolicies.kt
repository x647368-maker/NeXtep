package io.github.x647368maker.atomicworkbench.systemui

/** Policies operate on real task IDs, never on package-created pseudo instances. */
internal object StageQuickPolicy {
    // Persist up to 12 across fold states; compact phone renders only its
    // original five-auto/six-fixed visible slots, never changes task identities.
    fun normalize(pins: List<String>) = pins.filter { it.isNotBlank() }.distinct().take(12)
    fun toggle(pins: List<String>, key: String): List<String> =
        if (key in pins) pins.filter { it != key } else normalize(pins + key)
    fun move(pins: List<String>, key: String, delta: Int): List<String> {
        val result = normalize(pins).toMutableList(); val from = result.indexOf(key)
        if (from < 0) return result
        val to = (from + delta).coerceIn(0, result.lastIndex)
        result.removeAt(from); result.add(to, key); return result
    }
    fun select(fixed: Boolean, pins: List<String>, recent: List<String>, available: Set<String>,
        capacity: Int = 5): List<String> {
        val cap = if (fixed) maxOf(6,capacity) else capacity
        return (if (fixed) normalize(pins) else normalize(pins) + recent)
            .distinct().filter { it in available }.take(cap.coerceIn(0,12))
    }
    fun existing(key: String, running: Map<String, Int>): Int? = running[key]
}

internal class StageCinemaPolicy {
    enum class Mode { AUTO, MANUAL, LANDSCAPE, PORTRAIT, OFF }
    var mode = Mode.AUTO
    var task: Int? = null; private set
    private var manual = false
    val isManual: Boolean get() = manual
    private var blocked = emptySet<Int>()
    private var candidate: Int? = null
    private var candidateSince = 0L
    fun enter(id: Int) { task = id; manual = true; candidate = null }
    fun leave() { task?.let { blocked = blocked + it }; task = null; manual = false; candidate = null }
    fun reset() { task = null; manual = false; blocked = emptySet(); candidate = null }
    /** One delayed confirmation follows callbacks, never a repeating task/media poll. */
    fun update(trusted: Int?, live: Set<Int>, playing: Set<Int>, now: Long, present: Set<Int> = playing): Int? {
        blocked = blocked.intersect(present)
        if (mode == Mode.OFF || task != null && (task !in live || !manual && task !in present)) {
            task = null; manual = false
        }
        if (mode != Mode.AUTO || manual || task != null) return task
        val next = trusted?.takeIf { it in live && it in playing && it !in blocked }
        if (candidate != next) { candidate = next; candidateSince = now }
        if (next != null && now - candidateSince >= 700) task = next
        return task
    }
}

internal object StageTaskMigrationPolicy {
    fun rollback(restoreTask: () -> Unit, restorePresentation: () -> Unit): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        runCatching(restoreTask).onFailure(failures::add)
        runCatching(restorePresentation).onFailure(failures::add)
        return failures
    }

    fun eligible(packageName: String, user: Int?, expectedUser: Int?, task: Int, expectedTask: Int,
        defaultDisplay: Boolean, ready: Boolean): Boolean = packageName == "com.tencent.mm" &&
        user != null && user == expectedUser && task == expectedTask && defaultDisplay && ready
}

internal data class StageQuickState(val pins: List<String> = emptyList(), val fixed: Boolean = false,
    val expanded: Boolean = false, val enabled: Boolean = false) {
    fun encode() = listOf("1", fixed, expanded, enabled, StageQuickPolicy.normalize(pins).joinToString(";")).joinToString("|")
    companion object {
        fun decode(raw: String?): StageQuickState = runCatching {
            val p = raw!!.split('|'); require(p.size == 5 && p[0] == "1")
            StageQuickState(StageQuickPolicy.normalize(p[4].split(';')), p[1].toBooleanStrict(), p[2].toBooleanStrict(), p[3].toBooleanStrict())
        }.getOrDefault(StageQuickState())
    }
}
