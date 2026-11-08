package io.github.x647368maker.atomicworkbench.icons

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.x647368maker.atomicworkbench.R

internal enum class LauncherIconStyle(
    val key: String,
    @StringRes val label: Int,
    @DrawableRes val artwork: Int,
    val aliasName: String,
) {
    DEFAULT("default", R.string.launcher_icon_default,
        R.drawable.ic_atomic_workbench, "DefaultLauncher"),
    SPRING("spring", R.string.launcher_icon_spring,
        R.drawable.ic_atomic_workbench, "SpringLauncher"),
    SUMMER("summer", R.string.launcher_icon_summer,
        R.drawable.ic_atomic_workbench, "SummerLauncher"),
    AUTUMN("autumn", R.string.launcher_icon_autumn,
        R.drawable.ic_atomic_workbench, "AutumnLauncher"),
    WINTER("winter", R.string.launcher_icon_winter,
        R.drawable.ic_atomic_workbench, "WinterLauncher"),
    NIGHT("night", R.string.launcher_icon_night,
        R.drawable.ic_atomic_workbench, "NightLauncher");

    companion object {
        val selectable = entries.filter { it != NIGHT }

        fun fromKey(key: String?): LauncherIconStyle? = selectable.firstOrNull { it.key == key }
    }
}
