package io.github.x647368maker.atomicworkbench.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import io.github.x647368maker.atomicworkbench.BuildConfig
import io.github.x647368maker.atomicworkbench.logging.StageSurfaceHandoffTrace
import io.github.x647368maker.atomicworkbench.trigger.TriggerBroadcastContract
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit diagnosis start/end only. No polling, files, root commands or third-party content. */
internal object StageHandoffEvidenceContract {
    private const val ARM="io.github.x647368maker.atomicworkbench.action.ARM_HANDOFF_EVIDENCE_V1"
    private const val READ="io.github.x647368maker.atomicworkbench.action.READ_HANDOFF_EVIDENCE_V1"
    private const val RESULT=0x4157
    private val installed=AtomicBoolean(false)
    private val targets=listOf(TriggerBroadcastContract.SYSTEM_UI_PACKAGE,"android")

    fun register(context:Context,handler:Handler) {
        if(!installed.compareAndSet(false,true))return
        runCatching {
            context.registerReceiver(object:BroadcastReceiver() {
                override fun onReceive(c:Context,i:Intent) {
                    val token=i.getLongExtra("token",-1L)
                    when(i.action) {
                        ARM -> StageSurfaceHandoffTrace.evidence.arm(token,SystemClock.elapsedRealtime(),
                            i.getLongExtra("duration",-1L))
                        READ -> {
                            if(!isOrderedBroadcast)return
                            val text=StageSurfaceHandoffTrace.evidence.finish(token) ?: return
                            setResultExtras(Bundle().apply {
                                putLong("token",token)
                                putInt("pid",Process.myPid())
                                putString("revision",BuildConfig.ATOMIC_BUILD_REVISION)
                                putString("evidence",text)
                            })
                            resultCode=RESULT
                        }
                    }
                }
            },IntentFilter(ARM).apply { addAction(READ) },TriggerBroadcastContract.CONTROL_PERMISSION,
                handler,Context.RECEIVER_EXPORTED)
        }.onFailure { installed.set(false) }
    }

    fun arm(context:Context,token:Long,duration:Long) {
        targets.forEach { target ->
            runCatching { context.sendBroadcast(Intent(ARM).setPackage(target)
                .putExtra("token",token).putExtra("duration",duration)) }
        }
    }

    /** Bounded parallel end snapshots; a missing reply is explicitly unknown. */
    fun finish(context:Context,token:Long,onResult:(String)->Unit) {
        val handler=Handler(Looper.getMainLooper())
        val results=linkedMapOf<String,String>()
        var done=false
        fun complete() {
            if(done)return
            done=true
            onResult(buildString {
                targets.forEach { target ->
                    appendLine("$target:")
                    appendLine(results[target] ?: "不可用：未收到本会话记录（未启动、重启、广播限制或超时）。")
                }
            })
        }
        val timeout=Runnable { complete() }
        fun receive(target:String,text:String) {
            if(done || results.containsKey(target))return
            results[target]=text
            if(results.size==targets.size) {
                handler.removeCallbacks(timeout)
                complete()
            }
        }
        if(!handler.postDelayed(timeout,1_500L)) { complete();return }
        targets.forEach { target ->
            val receiver=object:BroadcastReceiver() {
                override fun onReceive(c:Context,i:Intent) {
                    val b=getResultExtras(false)
                    val text=if(resultCode==RESULT && b?.getLong("token")==token)
                        b.getString("evidence")?.takeIf { it.length<=5_000 }?.let {
                            "pid=${b.getInt("pid")} revision=${b.getString("revision")}\n$it"
                        } else null
                    receive(target,text ?: "不可用：没有匹配本会话的完整记录。")
                }
            }
            runCatching {
                context.sendOrderedBroadcast(Intent(READ).setPackage(target).putExtra("token",token),
                    null,receiver,handler,0,null,null)
            }.onFailure { receive(target,"不可用：读取广播失败。") }
        }
    }
}
