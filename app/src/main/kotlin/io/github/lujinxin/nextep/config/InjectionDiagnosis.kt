package io.github.x647368maker.atomicworkbench.config

internal object InjectionDiagnosis {
    private val critical=setOf("workspace_display_transition","slot_retention_display_area","system_dialog_layout","task_surface_ownership","task_surface_transaction_guard","systemui_framework_startup","systemui_install","systemui_surface_access","runtime_ready")
    fun describe(responded:Boolean,expected:String,loaded:String?,api:Int,failures:String,processChanged:Boolean):String {
        if(!responded)return "未收到注入诊断响应：无法区分未加载、作用域、旧代码、进程重启或通信问题；不能据此认定其他模块冲突。"
        if(loaded!=expected)return "SystemUI 正在运行其他版本代码：${loaded.orEmpty()}，安装 APK 后需正常重启以加载本版。"
        if(api in 1..100)return "SystemUI 已执行本版，但报告 API $api，低于本模块要求的现代 API 101；框架能力不受支持。"
        val core=failures.lines().filter{it.substringBefore(':') in critical}
        val state=if(core.isEmpty()) "SystemUI 已加载本版代码；${if(api==0)"尚未取得框架 API 凭据" else "现代 API $api"}" else "SystemUI 已加载，但关键初始化/Hook 失败：\n${core.joinToString("\n")}"
        return state+if(processChanged) "\n观测到 SystemUI 进程标识变化（不等于模块冲突）。" else ""
    }
}
