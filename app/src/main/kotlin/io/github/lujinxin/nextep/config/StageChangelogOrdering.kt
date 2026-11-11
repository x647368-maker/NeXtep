package io.github.x647368maker.atomicworkbench.config

/** Offline, semantic changelog ordering. No network or background work. */
internal object StageChangelogOrdering {
    private fun versionParts(value: String): List<Int> {
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(-dev)?$").matchEntire(value)
            ?: return listOf(-1, -1, -1, -1)
        return listOf(
            match.groupValues[1].toIntOrNull() ?: -1,
            match.groupValues[2].toIntOrNull() ?: -1,
            match.groupValues[3].toIntOrNull() ?: -1,
            if (match.groupValues[4].isEmpty()) 1 else 0
        )
    }

    fun merge(current: StageChange?, archive: List<StageChange>, curated: List<StageChange>): List<StageChange> {
        val entries = linkedMapOf<String, StageChange>()
        curated.forEach { entries.putIfAbsent(it.version, it) }
        archive.forEach { entries.putIfAbsent(it.version, it) }
        current?.let { entries[it.version] = it }
        return entries.values.sortedWith { left, right ->
            val a = versionParts(left.version)
            val b = versionParts(right.version)
            var comparison = 0
            for (i in 0 until 4) {
                comparison = b[i].compareTo(a[i])
                if (comparison != 0) break
            }
            comparison
        }
    }
}
