package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/** Public media-session callbacks only. Permission denial means manual mode remains available. */
internal class StageMediaSignals(context: Context, private val changed: () -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var sessions = emptyList<MediaController>()
    // A framework callback already queued before unregister can arrive after
    // stop() or even after a new start(). Scope registrations to their epoch.
    private var epoch = 0
    private var callback: MediaController.Callback? = null
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private fun current(token: Int) = listening && token == epoch
    fun observations(): List<StagePlaybackObservation> = sessions.mapNotNull { controller ->
        runCatching {
            val uid = (runCatching { controller.sessionToken.javaClass.getMethod("getUid").invoke(controller.sessionToken) as? Int }.getOrNull()
                ?: controller.sessionActivity?.takeIf { it.creatorPackage == controller.packageName }?.creatorUid)
                ?: return@runCatching null
            val state = when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> StagePlayback.PLAYING
                PlaybackState.STATE_PAUSED -> StagePlayback.PAUSED
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> StagePlayback.BUFFERING
                PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> StagePlayback.STOPPED
                else -> StagePlayback.UNKNOWN
            }
            StagePlaybackObservation(StageMediaKey(controller.packageName, uid / 100000, controller.sessionToken), state)
        }.getOrNull()
    }
    fun start() {
        if (listening || manager == null) return
        val token = ++epoch
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (current(token)) changed()
            }
            override fun onSessionDestroyed() {
                if (!current(token)) return
                val next = runCatching { manager.getActiveSessions(null) }.getOrNull() ?: return
                bind(token, next)
            }
        }
        val activeListener = MediaSessionManager.OnActiveSessionsChangedListener {
            if (current(token)) bind(token, it.orEmpty())
        }
        callback = cb
        listener = activeListener
        listening = true
        runCatching {
            manager.addOnActiveSessionsChangedListener(activeListener, null, handler)
            bind(token, manager.getActiveSessions(null).orEmpty())
        }.onFailure { stop() }
    }
    private fun bind(token: Int, next: List<MediaController>) {
        if (!current(token)) return
        val cb = callback ?: return
        val unique = next.distinctBy { it.sessionToken }
        // Unchanged sessions keep their existing callback registration.
        val oldByToken = sessions.associateBy { it.sessionToken }
        val nextTokens = unique.map { it.sessionToken }.toSet()
        sessions.filter { it.sessionToken !in nextTokens }.forEach {
            runCatching { it.unregisterCallback(cb) }
        }
        sessions = unique.map { controller ->
            oldByToken[controller.sessionToken] ?: controller.also {
                runCatching { it.registerCallback(cb, handler) }
            }
        }
        changed()
    }
    fun stop() {
        // Invalidate first, including callbacks delivered during unregister.
        epoch++
        listening = false
        val oldListener = listener
        val oldCallback = callback
        val oldSessions = sessions
        listener = null; callback = null; sessions = emptyList()
        oldListener?.let { runCatching { manager?.removeOnActiveSessionsChangedListener(it) } }
        oldCallback?.let { cb ->
            oldSessions.forEach { runCatching { it.unregisterCallback(cb) } }
        }
    }
}
