package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** Test APK only; no changes to the historically pinned default visual runner. */
class PerformanceEvidenceInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result=Bundle()
        val out=File(targetContext.filesDir,"performance-evidence").apply{mkdirs()}
        try {
            val status=StagePerformanceEvidence(this).run(out)
            check(status.startsWith("PERFORMANCE_EVIDENCE_PASS:"))
            result.putString("stream",status+"\n")
            finish(Activity.RESULT_OK,result)
        } catch(error:Throwable) {
            runCatching {File(out,"failure.txt").writeText(error.stackTraceToString())}
            result.putString("stream",error.stackTraceToString()+"\nPERFORMANCE_EVIDENCE_FAILED\n")
            finish(Activity.RESULT_CANCELED,result)
        }
    }
}
