package com.musiclify.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.musiclify.data.LyricLine
import com.musiclify.data.Lyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shows the current synced-lyric line on the lock screen and notification by putting it in the
 * media item's artist field (the real artist stays in [EXTRA_ARTIST], which the app reads).
 *
 * Replacing a media item with the same URI only updates its metadata: ExoPlayer keeps playing
 * without re-buffering.
 */
class LockScreenLyrics(
    private val player: Player,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val load: suspend (songId: Long) -> Lyrics,
) : Player.Listener {

    private var songId: Long? = null
    private var lines: List<LyricLine> = emptyList()
    /** Index of the line in the artist field; -1 = the real artist; UNKNOWN = not shown yet. */
    private var shownIndex = UNKNOWN
    private var loadJob: Job? = null
    private var tickJob: Job? = null

    fun start() {
        player.addListener(this)
        onSongChanged()
        tickJob = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        player.removeListener(this)
        tickJob?.cancel()
        loadJob?.cancel()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Our own metadata updates report PLAYLIST_CHANGED for the same song; ignore those.
        if (mediaItem?.mediaId?.toLongOrNull() != songId) onSongChanged()
    }

    private fun onSongChanged() {
        loadJob?.cancel()
        lines = emptyList()
        shownIndex = UNKNOWN
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        songId = id ?: return
        loadJob = scope.launch {
            val lyrics = runCatching { load(id) }.getOrNull()
            if (songId == id && lyrics != null && lyrics.synced) lines = lyrics.lines
        }
    }

    private fun tick() {
        if (player.currentMediaItem?.mediaId?.toLongOrNull() != songId) return
        if (!enabled()) {
            if (shownIndex >= 0) show(-1) // switched off in Settings: put the real artist back
            return
        }
        if (lines.isEmpty()) return
        val position = player.currentPosition
        // Last line that has started; before the first line the artist is shown.
        val index = lines.indexOfLast { it.startMs <= position + LEAD_MS }
        if (index != shownIndex) show(index)
    }

    private fun show(index: Int) {
        val i = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (i == C.INDEX_UNSET) return
        val meta = item.mediaMetadata
        val artist = meta.realArtist()
        val text = lines.getOrNull(index)?.text?.let { "♪ $it" } ?: artist
        shownIndex = index
        if (meta.artist?.toString() == text) return
        // Keep the real artist in extras so it survives (items from a handoff may not carry it yet).
        val extras = Bundle(meta.extras ?: Bundle()).apply { putString(EXTRA_ARTIST, artist) }
        val updated = meta.buildUpon().setArtist(text).setExtras(extras).build()
        player.replaceMediaItem(i, item.buildUpon().setMediaMetadata(updated).build())
    }

    private companion object {
        const val UNKNOWN = Int.MIN_VALUE
        const val TICK_MS = 300L
        /** Show a line a moment early so it doesn't lag behind the singer. */
        const val LEAD_MS = 200L
    }
}
