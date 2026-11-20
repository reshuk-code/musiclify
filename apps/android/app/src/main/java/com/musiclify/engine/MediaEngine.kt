package com.musiclify.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.AesCipherDataSink
import androidx.media3.datasource.AesCipherDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.musiclify.data.MusicDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Play-while-downloading engine.
 *
 * Songs are addressed as `musiclify://song/<id>`. When the player (or a download) needs
 * bytes, [CacheDataSource] serves them from the encrypted on-device cache; anything missing is
 * resolved to a YouTube audio URL and streamed, and written to the cache (AES-CTR encrypted)
 * at the same time. A song that has been fully played or downloaded plays offline.
 */
@OptIn(UnstableApi::class)
class MediaEngine(
    context: Context,
    keys: KeyManager,
    private val resolver: YouTubeResolver,
    private val dao: MusicDao,
    http: OkHttpClient,
) {
    private val cache = SimpleCache(
        File(context.filesDir, "media"),
        NoOpCacheEvictor(), // the user's downloads are kept until they remove them
        StandaloneDatabaseProvider(context),
    )
    private val key = keys.mediaKey
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writers = ConcurrentHashMap<Long, CacheWriter>()

    private val _downloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    /** Songs currently being downloaded, with progress 0..1. */
    val downloadProgress: StateFlow<Map<Long, Float>> = _downloadProgress

    private val upstream = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(http).setUserAgent(NewPipeHttp.USER_AGENT),
    ) { spec ->
        if (spec.uri.scheme == SCHEME) spec.withUri(Uri.parse(resolver.audioUrl(songId(spec.uri)))) else spec
    }

    val dataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheReadDataSourceFactory { AesCipherDataSource(key, FileDataSource()) }
        // The scratch buffer matters: without it the sink encrypts the player's buffer in place.
        .setCacheWriteDataSinkFactory {
            AesCipherDataSink(key, CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE), ByteArray(64 * 1024))
        }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Download a song completely (for offline use / prefetching the next song). */
    fun download(songId: Long) {
        scope.launch { downloadNow(songId) }
    }

    /**
     * Download several songs (e.g. ones that were offline on a linked device) one after another,
     * so YouTube sees a gentle, steady pace rather than a burst.
     */
    fun downloadMany(songIds: List<Long>) {
        scope.launch {
            songIds.forEach { id ->
                downloadNow(id)
                kotlinx.coroutines.delay(2_000)
            }
        }
    }

    private fun downloadNow(songId: Long) {
        if (writers.containsKey(songId) || isFullyCached(songId)) {
            if (!writers.containsKey(songId)) markDownloaded(songId)
            return
        }
        val spec = DataSpec.Builder().setUri(uri(songId)).setKey(cacheKey(songId)).build()
        val writer = CacheWriter(dataSourceFactory.createDataSource(), spec, null) { length, cached, _ ->
            if (length > 0) _downloadProgress.update { it + (songId to cached.toFloat() / length) }
        }
        writers[songId] = writer
        try {
            writer.cache()
            kotlinx.coroutines.runBlocking { dao.setDownloaded(songId, true) }
        } catch (e: Exception) {
            Log.w(TAG, "Download of $songId failed or cancelled: ${e.message}")
        } finally {
            writers.remove(songId)
            _downloadProgress.update { it - songId }
        }
    }

    /** Mark a song as downloaded if the player happened to cache all of it. */
    fun markDownloaded(songId: Long) {
        if (isFullyCached(songId)) scope.launch { dao.setDownloaded(songId, true) }
    }

    fun removeDownload(songId: Long) {
        writers.remove(songId)?.cancel()
        scope.launch {
            cache.removeResource(cacheKey(songId))
            dao.setDownloaded(songId, false)
        }
    }

    fun clearAll(onDone: () -> Unit = {}) {
        writers.values.forEach { it.cancel() }
        scope.launch {
            cache.keys.toList().forEach { cache.removeResource(it) }
            dao.clearDownloaded()
            onDone()
        }
    }

    fun storageBytes(): Long = cache.cacheSpace

    private fun isFullyCached(songId: Long): Boolean {
        val k = cacheKey(songId)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(k))
        return length > 0 && cache.isCached(k, 0, length)
    }

    companion object {
        private const val TAG = "MediaEngine"
        const val SCHEME = "musiclify"

        fun uri(songId: Long): Uri = Uri.parse("$SCHEME://song/$songId")
        fun cacheKey(songId: Long) = "song:$songId"
        fun songId(uri: Uri): Long = uri.lastPathSegment!!.toLong()
    }
}
