package com.musiclify

import android.app.Application
import android.content.Context
import com.musiclify.data.Deezer
import com.musiclify.data.LrcLib
import com.musiclify.data.MusicDatabase
import com.musiclify.data.Prefs
import com.musiclify.data.Sync
import com.musiclify.engine.DeviceLink
import com.musiclify.engine.KeyManager
import com.musiclify.engine.MatchCache
import com.musiclify.engine.MediaEngine
import com.musiclify.engine.NewPipeHttp
import com.musiclify.engine.YouTubeResolver
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

class MusiclifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }

    companion object {
        lateinit var graph: Graph
            private set
    }
}

/** App-wide singletons (manual dependency injection; small enough not to need Hilt). */
class Graph(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val keys = KeyManager(context)
    val prefs = Prefs(context, keys)
    val db = MusicDatabase.create(context)
    val dao = db.dao()
    val sync = Sync(db, prefs)
    val link = DeviceLink(http, sync, prefs)
    val deezer = Deezer(http)
    val lrclib = LrcLib(http)
    val matchCache = MatchCache(http, BuildConfig.MATCH_CACHE_URL) { prefs.shareMatches }
    val resolver = YouTubeResolver(dao, matchCache)
    val engine = MediaEngine(context, keys, resolver, dao, http)

    init {
        val locale = Locale.getDefault()
        NewPipe.init(
            NewPipeHttp(http) { prefs.youtubeCookies },
            Localization(locale.language.ifEmpty { "en" }, locale.country.ifEmpty { null }),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
    }
}
