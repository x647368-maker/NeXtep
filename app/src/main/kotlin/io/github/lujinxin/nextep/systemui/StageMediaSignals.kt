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
    private val registered = mutableSetOf<MediaController>()
    private val sessionKeys = mutableMapOf<MediaController, StageMediaKey>()
    private var destroyRefresh: Runnable? = null
    // A framework callback already queued before unregister can arrive after
    // stop() or even after a new start(). Scope registrations to their epoch.
    private var epoch = 0
    private var callback: MediaController.Callback? = null
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private fun current(token: Int) = listening && token == epoch
    fun observations(): List<StagePlaybackObservation> = sessions.mapNotNull { controller ->
        runCatching {
            val key = sessionKeys[controller] ?: run {
                val token = controller.sessionToken
                val directUid = runCatching { token.javaClass.getMethod("getUid").invoke(token) as? Int }.getOrNull()
                    ?.takeIf { it >= 0 }
                val uid = directUid
                    ?: controller.sessionActivity?.takeIf { it.creatorPackage == controller.packageName }?.creatorUid
                        ?.takeIf { it >= 0 }
                    ?: return@runCatching null
                // Only the session owner's immutable token UID is safe to cache.
                // The fallback activity may change or become unavailable at any time.
                StageMediaKey(controller.packageName, uid / 100000, token).also {
                    if (directUid != null) sessionKeys[controller] = it
                }
            }
            val state = when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> StagePlayback.PLAYING
                PlaybackState.STATE_PAUSED -> StagePlayback.PAUSED
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> StagePlayback.BUFFERING
                PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> StagePlayback.STOPPED
                else -> StagePlayback.UNKNOWN
            }
            StagePlaybackObservation(key, state)
        }.getOrNull()
    }
    fun start() {
        if (listening) return
        val service = manager ?: return
        val token = ++epoch
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (current(token)) changed()
            }
            override fun onSessionDestroyed() {
                if (!current(token) || destroyRefresh != null) return
                // Collapse queued destruction callbacks into one authoritative read.
                val refresh = Runnable {
                    if (!current(token)) return@Runnable
                    destroyRefresh = null
                    val next = runCatching { service.getActiveSessions(null) }.getOrNull() ?: return@Runnable
                    bind(token, next)
                }
                destroyRefresh = refresh
                if (!handler.post(refresh)) destroyRefresh = null
            }
        }
        val activeListener = MediaSessionManager.OnActiveSessionsChangedListener {
            if (current(token)) bind(token, it.orEmpty())
        }
        callback = cb
        listener = activeListener
        listening = true
        runCatching {
            service.addOnActiveSessionsChangedListener(activeListener, null, handler)
            bind(token, service.getActiveSessions(null).orEmpty())
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
            registered.remove(it)
            sessionKeys.remove(it)
            runCatching { it.unregisterCallback(cb) }
        }
        sessions = unique.map { controller -> oldByToken[controller.sessionToken] ?: controller }
        // A failed registration is not a successful subscription; retry only
        // on the next actual session-list event, without a timer or polling.
        sessions.filter { it !in registered }.forEach { controller ->
            runCatching { controller.registerCallback(cb, handler) }.onSuccess { registered.add(controller) }
        }
        changed()
    }
    fun stop() {
        // Invalidate first, including callbacks delivered during unregister.
        epoch++
        listening = false
        destroyRefresh?.let { handler.removeCallbacks(it) }
        destroyRefresh = null
        registered.clear()
        sessionKeys.clear()
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
