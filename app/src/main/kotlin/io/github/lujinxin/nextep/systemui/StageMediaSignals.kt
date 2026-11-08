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
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { changed() }
        override fun onSessionDestroyed() { bind(runCatching { manager?.getActiveSessions(null) }.getOrNull().orEmpty()) }
    }
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { bind(it.orEmpty()) }
    fun presentPackages(): Set<String> = sessions.filter {
        runCatching { it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) }.getOrDefault(false)
    }.mapTo(mutableSetOf()) { it.packageName }
    fun playingPackages(): Set<String> = sessions.filter {
        runCatching { it.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
    }.mapTo(mutableSetOf()) { it.packageName }
    fun start() {
        if (listening) return
        runCatching {
            manager?.addOnActiveSessionsChangedListener(listener, null, handler)
            listening = true
            bind(manager?.getActiveSessions(null).orEmpty())
        }.onFailure { stop() }
    }
    private fun bind(next: List<MediaController>) {
        sessions.forEach { runCatching { it.unregisterCallback(callback) } }
        sessions = next
        sessions.forEach { runCatching { it.registerCallback(callback, handler) } }
        changed()
    }
    fun stop() {
        if (listening) runCatching { manager?.removeOnActiveSessionsChangedListener(listener) }
        listening = false
        sessions.forEach { runCatching { it.unregisterCallback(callback) } }; sessions = emptyList()
    }
}
