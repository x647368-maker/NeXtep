package io.github.x647368maker.atomicworkbench.systemui

import android.content.*
import android.os.*

/** Reports the code actually executing inside SystemUI, never PackageManager's APK version. */
internal object StageRuntimeIdentity {
    const val BUILD = "0.9.11/26-glass-transition"
    const val ACTION = "io.github.x647368maker.atomicworkbench.action.RUNTIME_IDENTITY_26"
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
                    if(i.action!=ACTION)return
                    setResultExtras(Bundle().apply{
                        putString("loaded_build",BUILD)
                        putInt("loaded_pid",Process.myPid())
                        putString("native_panel",SystemUiPanelMonitor.refresh(context).name)
                        putString("panel_windows",SystemUiPanelMonitor.diagnostic())
                    });resultCode=26
                }
            },IntentFilter(ACTION),"io.github.x647368maker.atomicworkbench.permission.CONTROL_WORKSPACE",null,Context.RECEIVER_EXPORTED)
            registered=true
        }
    }
}
