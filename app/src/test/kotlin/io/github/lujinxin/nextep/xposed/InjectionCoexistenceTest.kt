package io.github.x647368maker.atomicworkbench.xposed

import io.github.x647368maker.atomicworkbench.config.InjectionDiagnosis
import io.github.x647368maker.atomicworkbench.systemui.SystemUiApplicationHooker
import io.github.libxposed.api.XposedInterface
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class InjectionCoexistenceTest {
    @Test fun laterSystemUiPackageIsAcceptedOnlyInKnownMainProcess() {
        assertTrue(InjectionDispatchPolicy.accept(false,"com.android.systemui","com.android.systemui"))
        assertFalse(InjectionDispatchPolicy.accept(false,"com.android.systemui","com.oplus.launcher"))
        assertFalse(InjectionDispatchPolicy.accept(false,"com.android.systemui",""))
        assertFalse(InjectionDispatchPolicy.accept(false,"com.oplus.launcher","com.android.systemui"))
        assertTrue(InjectionDispatchPolicy.accept(true,"com.oplus.launcher","com.oplus.launcher"))
    }
    @Test fun simultaneousInitializationClaimsOnlyOneInstaller() {
        val gate=InjectionOnce();val count=AtomicInteger();val start=CountDownLatch(1)
        val threads=List(16){Thread{start.await();if(gate.claim())count.incrementAndGet()}.apply{start()}}
        start.countDown();threads.forEach{it.join()};assertEquals(1,count.get());assertFalse(gate.claim())
    }
    @Test fun failedPhaseDoesNotEraseLaterEvidenceOrGrowUnbounded() {
        val ledger=InjectionLedger();ledger.record("critical","FAILED","LinkageError",1)
        ledger.record("receiver","OK","",2)
        assertEquals("FAILED",ledger.snapshot().first().state)
        assertFalse(ledger.record("receiver","OK","",3))
        repeat(80){ledger.record("phase$it","OK","",it.toLong())}
        assertEquals(48,ledger.snapshot().size);assertTrue(ledger.snapshot().any{it.phase=="critical" && it.state=="FAILED"})
    }
    @Test fun noResponseDoesNotInventConflictOrUnsupportedApi() {
        val text=InjectionDiagnosis.describe(false,"new",null,0,"",false)
        assertTrue(text.contains("无法区分"));assertTrue(text.contains("不能据此认定"))
        assertTrue(InjectionDiagnosis.describe(true,"new","old",101,"",false).contains("其他版本"))
        assertTrue(InjectionDiagnosis.describe(true,"new","new",100,"",false).contains("不受支持"))
    }
    @Test fun optionalFailureDoesNotMasqueradeAsCriticalFailure() {
        assertFalse(InjectionDiagnosis.describe(true,"new","new",102,"systemui_root_touch_override: NoSuchMethodException",false).contains("关键初始化/Hook 失败"))
        assertTrue(InjectionDiagnosis.describe(true,"new","new",102,"task_surface_ownership: HookFailedError",false).contains("关键初始化/Hook 失败"))
        assertTrue(InjectionDiagnosis.describe(true,"new","new",102,"",true).contains("进程标识变化"))
    }
    private fun chain(proceed:()->Any?):XposedInterface.Chain=Proxy.newProxyInstance(XposedInterface.Chain::class.java.classLoader,arrayOf(XposedInterface.Chain::class.java)){_,method,_->
        when(method.name){"getArg"->null;"proceed"->proceed();else->null}
    } as XposedInterface.Chain
    @Test fun bootstrapKeepsNextInterceptorResultAndCallsItExactlyOnce() {
        var calls=0;val token=Any()
        assertSame(token,SystemUiApplicationHooker().intercept(chain{calls++;token}));assertEquals(1,calls)
    }
    @Test fun downstreamExceptionIsNotSwallowedOrRetried() {
        var calls=0;val error=IllegalStateException("downstream sentinel")
        try{SystemUiApplicationHooker().intercept(chain{calls++;throw error});fail("Expected exception")}
        catch(actual:IllegalStateException){assertSame(error,actual)}
        assertEquals(1,calls)
    }
}
