package com.musiclify.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musiclify.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QueueItem(val songId: Long, val title: String, val artist: String, val artwork: String?)

/** Compose-friendly view of the playback service, via a MediaController. */
class PlayerConnection(context: Context, private val scope: CoroutineScope) {

    private var controller: MediaController? = null

    var currentId by mutableStateOf<Long?>(null)
        private set
    var currentTitle by mutableStateOf("")
        private set
    var currentArtist by mutableStateOf("")
        private set
    var currentArtwork by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** How far the current song is buffered (= saved to the encrypted cache). */
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var queue by mutableStateOf<List<QueueItem>>(emptyList())
        private set
    var queueIndex by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sleepTimerEndsAt by mutableStateOf<Long?>(null)
        private set
    /** Sleep timer set to "end of this song". */
    var sleepAtSongEnd by mutableStateOf(false)
        private set

    private var sleepJob: Job? = null

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = sync()
                    override fun onPlayerError(e: PlaybackException) {
                        error = friendlyError(e)
                    }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        if (sleepAtSongEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            sleepAtSongEnd = false
                            controller?.pause()
                        }
                    }
                })
            }
            sync()
        }, ContextCompat.getMainExecutor(context))

        scope.launch {
            while (isActive) {
                controller?.let {
                    positionMs = it.currentPosition
                    bufferedMs = it.bufferedPosition
                    durationMs = it.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0L
                }
                delay(250)
            }
        }
    }

    private fun sync() {
        val c = controller ?: return
        val item = c.currentMediaItem
        currentId = item?.mediaId?.toLongOrNull()
        currentTitle = item?.mediaMetadata?.title?.toString().orEmpty()
        currentArtist = item?.mediaMetadata?.realArtist().orEmpty()
        currentArtwork = item?.mediaMetadata?.artworkUri?.toString()
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        queueIndex = c.currentMediaItemIndex
        queue = (0 until c.mediaItemCount).map { i ->
            val m = c.getMediaItemAt(i)
            QueueItem(m.mediaId.toLong(), m.mediaMetadata.title.toString(), m.mediaMetadata.realArtist(),
                m.mediaMetadata.artworkUri?.toString())
        }
        if (c.playerError == null && c.playbackState != Player.STATE_IDLE) error = null
    }

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffled: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        error = null
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(songs.map { it.toMediaItem() }, if (shuffled) songs.indices.random() else startIndex, 0)
        c.prepare()
        c.play()
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(listOf(song))
        c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
    }

    fun addToQueue(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playSongs(listOf(song))
        c.addMediaItem(song.toMediaItem())
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() = controller?.pause()

    fun retry() {
        val c = controller ?: return
        error = null
        c.prepare()
        c.play()
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun skipToQueueIndex(index: Int) = controller?.seekToDefaultPosition(index)
    fun removeFromQueue(index: Int) = controller?.removeMediaItem(index)

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** off -> all -> one -> off */
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /** Pause when the current song ends (cancels a timed sleep timer). */
    fun setSleepAtSongEnd() {
        setSleepTimer(null)
        sleepAtSongEnd = true
    }

    /** Pause after [minutes]; null cancels the timer. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepAtSongEnd = false
        if (minutes == null) {
            sleepTimerEndsAt = null
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerEndsAt = endsAt
        sleepJob = scope.launch {
            delay(endsAt - System.currentTimeMillis())
            controller?.pause()
            sleepTimerEndsAt = null
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }

    private fun friendlyError(e: PlaybackException): String {
        val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            "rate-limit" in msg || "recaptcha" in msg || "sign in" in msg || "bot" in msg ->
                "YouTube is blocking requests from this device for now. Try again later, or import YouTube cookies in Settings."
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "No internet connection. Downloaded songs still play offline."
            "couldn't find" in msg -> "Couldn't find this song on YouTube."
            else -> "Couldn't play this song (${e.errorCodeName})."
        }
    }
}

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(Uri.parse(coverBig.ifEmpty { cover }))
            .setExtras(Bundle().apply { putString(EXTRA_ARTIST, artist) })
            .build()
    )
    .build()

/** Extras key holding the real artist: lock-screen lyrics put the current line in the artist field. */
const val EXTRA_ARTIST = "musiclify.artist"

/** The song's artist, even while the artist field is showing a lyric line. */
fun MediaMetadata.realArtist(): String = extras?.getString(EXTRA_ARTIST) ?: artist?.toString().orEmpty()
