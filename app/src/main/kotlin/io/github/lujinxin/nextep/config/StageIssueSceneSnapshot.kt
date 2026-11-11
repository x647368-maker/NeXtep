package io.github.x647368maker.atomicworkbench.config

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import java.util.Locale

/**
 * Tiny, on-demand device/physical-display environment snapshot.
 * No periodic listeners, permissions, screenshots or persistent services.
 */
internal object StageIssueSceneSnapshot {
    fun read(context: Context): String {
        val dm = context.getSystemService(DisplayManager::class.java)
        val physical = runCatching { dm?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
        val mode = runCatching { physical?.mode }.getOrNull()
        val orientation = context.resources.configuration.orientation
        val locked = runCatching {
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
        }.getOrNull()
        val interactive = runCatching {
            context.getSystemService(PowerManager::class.java)?.isInteractive
        }.getOrNull()
        val memory = ActivityManager.MemoryInfo()
        val memOk = runCatching {
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            memory.totalMem > 0
        }.getOrDefault(false)
        val vm = Runtime.getRuntime()
        val heapMib = (vm.totalMemory() - vm.freeMemory()) / (1024L * 1024L)
        val displayLabel = if (mode == null) "不可用" else
            "${mode.physicalWidth}x${mode.physicalHeight}@${String.format(Locale.US, "%.1f", mode.refreshRate)}Hz"
        return "主屏=$displayLabel 主屏状态=${physical?.state ?: "未知"} " +
            "资源方向=$orientation 锁屏=${locked ?: "未知"} 交互=${interactive ?: "未知"} " +
            "系统低内存=${if (memOk) memory.lowMemory else "未知"} " +
            "App进程Java堆≈${heapMib}MiB（不包含系统进程的小窗模块）"
    }
}
