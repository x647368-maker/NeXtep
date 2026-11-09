package io.github.x647368maker.atomicworkbench.workspace

/** Single snapshot shared by compositor, Launcher and input. Old optional fields remain readable. */
internal object WorkspaceGeometryCodec {
    fun encode(g: WorkspaceGeometry): String = with(g) {listOf(screenWidth,screenHeight,topHeight,sidebarWidth,sidebarSide.name,
        navigationInsets.left,navigationInsets.top,navigationInsets.right,navigationInsets.bottom,displayRotation,railAtTop,mainTopOverlap,mainInset).joinToString(",")}
    fun decode(encoded: String): WorkspaceGeometry? = runCatching {
        val f=encoded.split(',');if(f.size !in 9..13) return@runCatching null
        WorkspaceGeometry(f[0].toInt(),f[1].toInt(),f[2].toInt(),f[3].toInt(),SidebarSide.valueOf(f[4]),
            WorkspaceNavigationInsets(f[5].toInt(),f[6].toInt(),f[7].toInt(),f[8].toInt()),f.getOrNull(9)?.toInt()?:0,
            f.getOrNull(10)?.toBooleanStrict()?:false,f.getOrNull(11)?.toInt()?:0,f.getOrNull(12)?.toInt()?:0)
    }.getOrNull()
}
