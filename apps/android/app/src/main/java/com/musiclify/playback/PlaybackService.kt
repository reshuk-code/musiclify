package com.musiclify.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musiclify.MainActivity
import com.musiclify.MusiclifyApp
import com.musiclify.data.HistoryEntry
import com.musiclify.data.SavedQueue
import com.musiclify.engine.MediaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

        // Tap the decoded audio for the ambient light's beat (no microphone needed).
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(BeatMeter)))
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.engine.dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val events = PlayerEvents(player)
        player.addListener(events)
        // Remember the position now and then, so "continue listening" is close even if the app is killed.
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) events.saveQueue()
            }
        }
        Companion.player = player
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
        Companion.player?.let { saveQueue(it) }
        lyrics?.stop()
        Companion.player = null
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
        /** Widget updates load artwork off the main thread; run them in order so a slow one can't land last. */
        private val widgetLock = Mutex()

        private var radioFor: Long? = null

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED)) updateWidget()
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) autoplay()
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY)) saveQueue()
        }

        fun saveQueue() = saveQueue(player)

        /**
         * Autoplay: once the last song in the queue starts, add similar songs (the artist's Deezer
         * radio, minus anything queued or played lately) so the music doesn't just stop.
         */
        private fun autoplay() {
            if (!graph.prefs.autoplay || player.repeatMode != Player.REPEAT_MODE_OFF) return
            if (player.mediaItemCount == 0 || player.nextMediaItemIndex != C.INDEX_UNSET) return
            val current = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
            if (radioFor == current) return
            radioFor = current
            scope.launch {
                val seed = withContext(Dispatchers.IO) { graph.dao.song(current) } ?: return@launch
                val skip = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }.toSet() +
                    withContext(Dispatchers.IO) { graph.dao.recentSongIds(100) }
                val songs = runCatching { similarSongs(seed.artistId, skip) }.getOrDefault(emptyList())
                if (songs.isEmpty() || player.currentMediaItem?.mediaId != current.toString()) return@launch
                graph.dao.insertSongs(songs)
                player.addMediaItems(songs.map { it.toMediaItem().forPlayback() })
                prefetchNext()
            }
        }

        private suspend fun similarSongs(artistId: Long, skip: Set<Long>): List<com.musiclify.data.Song> {
            val pool = if (artistId > 0) graph.deezer.artistRadio(artistId, 40) else graph.deezer.chartTracks(40)
            val picked = pool.filter { it.id !in skip }.distinctBy { it.id }.take(AUTOPLAY_BATCH)
            return graph.deezer.withOriginalCovers(picked)
        }

        /** Push song + play state to the home-screen widget (lyric-line metadata changes are skipped). */
        private fun updateWidget() {
            val item = player.currentMediaItem ?: return
            val meta = item.mediaMetadata
            val key = "${item.mediaId}|${player.isPlaying}|${meta.artworkUri}"
            if (key == widgetKey) return
            widgetKey = key
            val title = meta.title?.toString().orEmpty()
            val artist = meta.realArtist()
            val art = meta.artworkUri?.toString()
            val playing = player.isPlaying
            scope.launch(Dispatchers.IO) {
                widgetLock.withLock { PlayerWidget.update(this@PlaybackService, title, artist, art, playing) }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            // Lock-screen lyrics update the current item's metadata, which reports a transition
            // to the same song: that's not a new play.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && id == lastId) return
            lastId = id
            scope.launch(Dispatchers.IO) { graph.dao.addHistory(HistoryEntry(songId = id, playedAt = System.currentTimeMillis())) }
            prefetchNext()
            fixArtwork(id)
        }

        /**
         * Songs saved before (or played from a list that hadn't been checked yet) may carry a
         * greatest-hits compilation's cover. Look up the original album and, if it differs, save it
         * and swap it into the queue so the notification, lock screen and widget show the right art.
         */
        private fun fixArtwork(id: Long) = scope.launch {
            val song = withContext(Dispatchers.IO) { graph.dao.song(id) } ?: return@launch
            val fixed = graph.deezer.originalRelease(song)
            if (fixed.cover == song.cover && fixed.coverBig == song.coverBig) return@launch
            graph.dao.setAlbumArt(id, fixed.album, fixed.cover, fixed.coverBig)
            val art = Uri.parse(fixed.coverBig.ifEmpty { fixed.cover })
            for (i in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(i)
                if (item.mediaId != id.toString() || item.mediaMetadata.artworkUri == art) continue
                val meta = item.mediaMetadata.buildUpon().setArtworkUri(art).setAlbumTitle(fixed.album).build()
                // Same URI, so ExoPlayer only updates the metadata and keeps playing.
                player.replaceMediaItem(i, item.buildUpon().setMediaMetadata(meta).build())
            }
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
        private const val AUTOPLAY_BATCH = 10
        /** Songs around the current one that are kept for "continue listening". */
        private const val MAX_SAVED = 300

        private fun saveQueue(player: Player) {
            val prefs = MusiclifyApp.graph.prefs
            val count = player.mediaItemCount
            if (count == 0) return
            val index = player.currentMediaItemIndex.coerceIn(0, count - 1)
            val from = (index - MAX_SAVED / 2).coerceAtLeast(0)
            val to = (from + MAX_SAVED).coerceAtMost(count)
            val ids = (from until to).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
            prefs.savedQueue = SavedQueue(ids, index - from, player.currentPosition.coerceAtLeast(0))
        }

        /** The live player while the service runs (same process), for the home-screen widget. */
        @Volatile
        var player: Player? = null
            private set

        fun MediaItem.forPlayback(): MediaItem {
            val id = mediaId.toLong()
            return buildUpon().setUri(MediaEngine.uri(id)).setCustomCacheKey(MediaEngine.cacheKey(id)).build()
        }
    }
}
