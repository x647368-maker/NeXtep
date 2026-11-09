package io.github.x647368maker.atomicworkbench.xposed

import android.os.SystemClock
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog

object InjectionDiagnostics {
    private val ledger=InjectionLedger()
    @Volatile var framework="尚未收到模块加载凭据"; private set
    @Volatile var api=0; private set
    fun loaded(name:String,version:String,apiVersion:Int,systemServer:Boolean) {
        api=apiVersion;framework="${name.take(64)} ${version.take(64)} · API $apiVersion"
        stage(if(systemServer)"module_system_server" else "module_app",true)
    }
    fun stage(phase:String,ok:Boolean,error:Throwable?=null) {
        val type=error?.javaClass?.simpleName.orEmpty().take(80)
        val now=runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
        if(ledger.record(phase,if(ok)"OK" else "FAILED",type,now))
            runCatching { NeXtepLog.info("injection_stage","phase=$phase state=${if(ok)"OK" else "FAILED"} error=$type elapsed_ms=$now") }
    }
    fun failures():String=ledger.snapshot().filter{it.state=="FAILED"}.joinToString("\n"){"${it.phase}: ${it.error}"}
    fun summary():String=ledger.snapshot().joinToString("\n"){"${it.phase} ${it.state} ${it.error} @${it.time}ms"}
}
