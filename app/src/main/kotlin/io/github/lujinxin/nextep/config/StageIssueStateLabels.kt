package io.github.x647368maker.atomicworkbench.config

/** Stable, human-readable categories. Unknown input is never guessed. */
internal object StageIssueStateLabels {
    fun screen(interactive: Boolean?, locked: Boolean?, displayState: Int?): String {
        // Display.STATE_DOZE/DOZE_SUSPEND is not the same as screen OFF.
        if (displayState == 3 || displayState == 4) return "息屏显示或低功耗显示"
        if (displayState == 1) return "息屏"
        if (displayState == 2) {
            if (locked == true) return "亮屏但锁屏"
            if (interactive == false) return "亮屏但不可交互（状态切换中）"
            if (interactive == true && locked == false) return "亮屏已解锁"
            return "亮屏（锁屏或交互状态未知）"
        }
        if (interactive == false) return "不可交互（物理屏状态未知）"
        return "状态未知"
    }

    fun workspace(active: Boolean?, suspended: Boolean?): String = when {
        active == null || suspended == null -> "工作台状态未知"
        !active -> "工作台未开启"
        suspended -> "工作台已开启、处于挂起状态"
        else -> "工作台已开启、未挂起（不代表每个小窗都在前台）"
    }
}
