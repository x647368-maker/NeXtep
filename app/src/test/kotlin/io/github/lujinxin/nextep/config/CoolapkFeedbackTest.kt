package io.github.x647368maker.atomicworkbench.config
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class CoolapkFeedbackTest {
    @Test fun profileMatchesOfficialQrAndUserUid() {
        val url = URI(CoolapkFeedback.PROFILE)
        assertEquals("https", url.scheme); assertEquals("www.coolapk.com", url.host)
        assertEquals("/u/43881308", url.path); assertEquals("from=qr", url.query)
        assertEquals("43881308", CoolapkFeedback.UID); assertEquals("ZMW专用", CoolapkFeedback.ACCOUNT)
    }
    @Test fun installedAppGetsFirstAndOnlyAttempt() {
        val calls = mutableListOf<Pair<String,String?>>()
        assertEquals(CoolapkFeedback.Destination.APP, CoolapkFeedback.open { u,p -> calls.add(u to p); true })
        assertEquals(listOf(CoolapkFeedback.PROFILE to CoolapkFeedback.PACKAGE), calls)
    }
    @Test fun missingOrRejectingAppFallsBackToSameHttpsProfile() {
        val calls = mutableListOf<Pair<String,String?>>()
        assertEquals(CoolapkFeedback.Destination.WEB, CoolapkFeedback.open { u,p ->
            calls.add(u to p); if(p!=null) throw SecurityException("unavailable"); true
        })
        assertEquals(listOf(CoolapkFeedback.PROFILE to CoolapkFeedback.PACKAGE, CoolapkFeedback.PROFILE to null),calls)
    }
    @Test fun noHandlersCannotCrashSettings() {
        assertEquals(CoolapkFeedback.Destination.UNAVAILABLE, CoolapkFeedback.open { _,_ -> throw IllegalStateException("no activity") })
        assertEquals(CoolapkFeedback.Destination.UNAVAILABLE, CoolapkFeedback.open { _,_ -> false })
    }
    @Test fun templateIsExactlyBlankAndDoesNotAutofillPrivateData() {
        assertEquals(listOf("【台前调度反馈】", "App版本：", "手机型号：", "系统版本：", "Root / 模块环境：",
            "问题描述或功能建议：", "复现步骤：", "是否可以稳定复现：", "可附截图或日志（注意隐藏个人隐私）。"),
            CoolapkFeedback.template.lines().filter { it.isNotBlank() })
    }
}
