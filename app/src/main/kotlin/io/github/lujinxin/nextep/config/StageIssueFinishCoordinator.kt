package io.github.x647368maker.atomicworkbench.config

import android.content.Context
import android.os.Looper
import io.github.x647368maker.atomicworkbench.monitoring.BatterySample
import io.github.x647368maker.atomicworkbench.monitoring.WorkspaceEnergyContract

/** One end snapshot for the opt-in session, even if UI and alarm finish together. */
internal object StageIssueFinishCoordinator {
    private val pending = StageIssueCompletionQueue<Result<String?>>()

    fun request(context: Context, token: Long, mode: String,
                query: ((WorkspaceEnergyContract.Snapshot?) -> Unit) -> Unit,
                onResult: (Result<String?>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = context.applicationContext
        val session = StageIssueSession(app)
        if (!session.active() || session.currentToken() != token) {
            onResult(Result.success(null))
            return
        }
        val ticket = pending.join(token, onResult) ?: return
        fun complete(snapshot: WorkspaceEnergyContract.Snapshot?) {
            if (!pending.owns(ticket)) return
            val result = runCatching {
                if (!session.active() || session.currentToken() != token) null
                else session.finish(session.storedVersion(), session.storedNote(),
                    BatterySample.read(app), snapshot, mode)
            }
            pending.complete(ticket, result)
        }
        runCatching {
            query(::complete)
        }.onFailure { complete(null) }
    }
}
