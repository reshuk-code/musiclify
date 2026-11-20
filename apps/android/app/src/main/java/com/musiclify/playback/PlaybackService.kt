package com.musiclify.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musiclify.MainActivity
import com.musiclify.MusiclifyApp
import com.musiclify.data.HistoryEntry
import com.musiclify.engine.MediaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background playback with notification + lock-screen controls (Media3 handles both).
 * The UI talks to it through a MediaController.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var lyrics: LockScreenLyrics? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val graph = MusiclifyApp.graph

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.engine.dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerEvents(player))
        PlayerWidget.started(this)
        lyrics = LockScreenLyrics(player, scope, enabled = { graph.prefs.lockScreenLyrics }, load = graph::lyricsFor)
            .also { it.start() }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        lyrics?.stop()
        PlayerWidget.stopped(this)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Items from the controller arrive without a URI; attach our playback URI + cache key. */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.forPlayback() }.toMutableList())
    }

    private inner class PlayerEvents(private val player: ExoPlayer) : Player.Listener {
        private val graph = MusiclifyApp.graph
        private var retriedId: Long? = null
        private var lastId: Long? = null
        private var widgetKey: String? = null

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED)) updateWidget()
        }

        /** Push song + play state to the home-screen widget (lyric-line metadata changes are skipped). */
        private fun updateWidget() {
            val item = player.currentMediaItem ?: return
            val meta = item.mediaMetadata
            val key = "${item.mediaId}|${player.isPlaying}"
            if (key == widgetKey) return
            widgetKey = key
            val title = meta.title?.toString().orEmpty()
            val artist = meta.realArtist()
            val art = meta.artworkUri?.toString()
            val playing = player.isPlaying
            scope.launch(Dispatchers.IO) { PlayerWidget.update(this@PlaybackService, title, artist, art, playing) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            // Lock-screen lyrics update the current item's metadata, which reports a transition
            // to the same song: that's not a new play.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && id == lastId) return
            lastId = id
            scope.launch(Dispatchers.IO) { graph.dao.addHistory(HistoryEntry(songId = id, playedAt = System.currentTimeMillis())) }
            prefetchNext()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retriedId = null
            }
            // Song finished buffering completely: it's now playable offline.
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                player.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                    if (player.bufferedPosition >= player.duration && player.duration > 0) graph.engine.markDownloaded(id)
                }
            }
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            if (!isLoading) {
                val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                if (player.duration > 0 && player.bufferedPosition >= player.duration) graph.engine.markDownloaded(id)
            }
        }

        /** Stream URLs expire; retry once with a fresh one before giving up. */
        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
            Log.w(TAG, "Playback error for $id: ${error.errorCodeName}", error)
            if (retriedId != id) {
                retriedId = id
                graph.resolver.invalidate(id)
                player.prepare()
                player.play()
            }
        }

        /** Download the next song in the background so skipping to it is instant. */
        private fun prefetchNext() {
            val next = player.nextMediaItemIndex
            if (next == C.INDEX_UNSET) return
            player.getMediaItemAt(next).mediaId.toLongOrNull()?.let(graph.engine::download)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        fun MediaItem.forPlayback(): MediaItem {
            val id = mediaId.toLong()
            return buildUpon().setUri(MediaEngine.uri(id)).setCustomCacheKey(MediaEngine.cacheKey(id)).build()
        }
    }
}
