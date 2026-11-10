package io.github.x647368maker.atomicworkbench.workspace

/** Physical display identity, not product names. Fold states and axes keep independent choices. */
internal object StageDisplayPolicy {
    data class Choice(val top: Boolean, val side: SidebarSide) {
        fun encode() = "1,$top,${side.name}"
        companion object {
            fun decode(value: String?): Choice? = runCatching {
                val p=value?.split(',') ?: return@runCatching null
                if(p.size!=3 || p[0]!="1") return@runCatching null
                Choice(p[1].toBooleanStrict(),SidebarSide.valueOf(p[2]))
            }.getOrNull()
        }
    }
    /** The ROM can report rotation 0 or 1 for the same native-landscape
     * Lenovo panel. Use the *physical* wide canvas, never its spoofed model
     * name or the Android orientation enum. Compact phones and near-square
     * foldables do not qualify.
     */
    fun wideLandscapeTablet(width: Int, height: Int): Boolean =
        width > height && height >= 1300 && width.toFloat() / height >= 1.45f

    fun expanded(width: Int, height: Int, dpi: Int): Boolean {
        require(width>1 && height>1 && dpi>0)
        return minOf(width,height)*160f/dpi >= 600f
    }
    fun scope(width: Int, height: Int, dpi: Int): String =
        "${if(expanded(width,height,dpi)) "expanded" else "compact"}:${minOf(width,height)}x${maxOf(width,height)}:$dpi:${if(width>height) "land" else "port"}"
    fun initial(width: Int, height: Int, dpi: Int, migrated: Boolean,
        legacyTop: Boolean?, legacySide: SidebarSide): Choice {
        if(!migrated && legacyTop!=null) return Choice(legacyTop,legacySide)
        // A rooted tablet port may inflate DPI so that a true 2560x1600
        // landscape tablet incorrectly appears "compact" in dp. Start it in
        // the native horizontal top/bottom rail, but keep saved choices.
        return Choice(!expanded(width,height,dpi) && !wideLandscapeTablet(width,height),SidebarSide.LEFT)
    }
    fun layoutKey(scope: String?, field: String): String {
        require(field in setOf("main_priority","adaptive"))
        return scope?.let { "layout_v1:$it:$field" } ?: field
    }
    fun sameCanvas(w1:Int,h1:Int,d1:Int,w2:Int,h2:Int,d2:Int) =
        d1==d2 && minOf(w1,h1)==minOf(w2,h2) && maxOf(w1,h1)==maxOf(w2,h2)
}
