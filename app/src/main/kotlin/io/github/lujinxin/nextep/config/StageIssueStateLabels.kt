package io.github.x647368maker.atomicworkbench.config

/** Stable, human-readable categories. Unknown input is never guessed. */
internal object StageIssueStateLabels {
    fun screen(interactive: Boolean?, locked: Boolean?, displayState: Int?): String {
        if (interactive == false || displayState == 1) return "息屏"
        if (displayState == 3 || displayState == 4) return "息屏显示或低功耗显示"
        if (interactive == true && locked == true) return "亮屏但锁屏"
        if (interactive == true && locked == false && displayState == 2) return "亮屏已解锁"
        return "状态未知"
    }

    fun workspace(active: Boolean?, suspended: Boolean?): String = when {
        active == null || suspended == null -> "工作台状态未知"
        !active -> "工作台未开启"
        suspended -> "工作台已开启、处于挂起状态"
        else -> "工作台已开启、未挂起（不代表每个小窗都在前台）"
    }
}
