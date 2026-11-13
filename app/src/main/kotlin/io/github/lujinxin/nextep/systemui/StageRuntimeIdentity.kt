package io.github.x647368maker.atomicworkbench.systemui

import android.content.*
import android.os.*

/** Reports the code actually executing inside SystemUI, never PackageManager's APK version. */
internal object StageRuntimeIdentity {
    const val BUILD = "0.9.42-dev/57"
    const val ACTION = "io.github.x647368maker.atomicworkbench.action.RUNTIME_IDENTITY_30"
    const val AUDIT_ACTION="io.github.x647368maker.atomicworkbench.action.INJECTION_DIAGNOSTIC_V1"
    private var registered=false
    fun bootstrap() {
        runCatching {
            val c=Class.forName("android.app.ActivityThread")
            (c.getMethod("currentApplication").invoke(null) as? Context)?.let(::install)
        }
    }
    fun install(context:Context) {
        if(context.packageName!="com.android.systemui")return
        if(Looper.myLooper()!=Looper.getMainLooper()){Handler(Looper.getMainLooper()).post{install(context)};return}
        if(registered)return
        runCatching {
            context.registerReceiver(object:BroadcastReceiver(){
                override fun onReceive(c:Context,i:Intent){
                    if(i.action!=ACTION && i.action!=AUDIT_ACTION)return
                    setResultExtras(Bundle().apply{
                        putString("loaded_build",BUILD)
                        putLong("process_started",Process.getStartElapsedRealtime())
                        putString("framework",io.github.x647368maker.atomicworkbench.xposed.InjectionDiagnostics.framework)
                        putInt("framework_api",io.github.x647368maker.atomicworkbench.xposed.InjectionDiagnostics.api)
                        putString("hook_failures",io.github.x647368maker.atomicworkbench.xposed.InjectionDiagnostics.failures())
                        putString("init_stages",io.github.x647368maker.atomicworkbench.xposed.InjectionDiagnostics.summary())
                        putString("exchange_metrics",io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.describe())
                        putInt("loaded_pid",Process.myPid())
                        putString("loaded_process",android.app.Application.getProcessName())
                        putString("native_panel",SystemUiPanelMonitor.refresh(context).name)
                        putString("panel_windows",SystemUiPanelMonitor.diagnostic())
                    });resultCode=30
                }
            },IntentFilter(ACTION).apply{addAction(AUDIT_ACTION)},"io.github.x647368maker.atomicworkbench.permission.CONTROL_WORKSPACE",null,Context.RECEIVER_EXPORTED)
            registered=true
        }.onFailure { io.github.x647368maker.atomicworkbench.xposed.InjectionDiagnostics.stage("diagnostic_receiver",false,it) }
    }
}
