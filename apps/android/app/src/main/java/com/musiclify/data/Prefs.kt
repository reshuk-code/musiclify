package com.musiclify.data

import android.content.Context
import androidx.core.content.edit
import com.musiclify.engine.KeyManager
import org.json.JSONArray
import org.json.JSONObject

/** The queue when the app last stopped, so playback can pick up where it left off. */
data class SavedQueue(val songIds: List<Long>, val index: Int, val positionMs: Long)

data class Pairing(val host: String, val port: Int, val deviceId: String, val key: ByteArray, val pcName: String)

/** Small key-value settings. Everything stays on the device. */
class Prefs(context: Context, private val keys: KeyManager) {
    private val sp = context.getSharedPreferences("musiclify", Context.MODE_PRIVATE)

    var userName: String
        get() = sp.getString("user_name", "").orEmpty()
        set(value) = sp.edit { putString("user_name", value) }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(value) = sp.edit { putBoolean("onboarded", value) }

    /** Use and contribute to the shared song-match cache (no audio, no personal data). */
    var shareMatches: Boolean
        get() = sp.getBoolean("share_matches", true)
        set(value) = sp.edit { putBoolean("share_matches", value) }

    /** Long-term pairing with a PC for the Sync button (key stored encrypted). */
    var pairing: Pairing?
        get() {
            val host = sp.getString("pair_host", null) ?: return null
            val key = sp.getString("pair_key", null)?.let { runCatching { keys.decrypt(it) }.getOrNull() } ?: return null
            return Pairing(host, sp.getInt("pair_port", 0), sp.getString("pair_device", "").orEmpty(), key,
                sp.getString("pair_pc", "PC").orEmpty())
        }
        set(p) = sp.edit {
            if (p == null) {
                listOf("pair_host", "pair_port", "pair_device", "pair_key", "pair_pc", "last_sync").forEach(::remove)
            } else {
                putString("pair_host", p.host); putInt("pair_port", p.port); putString("pair_device", p.deviceId)
                putString("pair_key", keys.encrypt(p.key)); putString("pair_pc", p.pcName)
            }
        }

    /** Cover-coloured glow behind the app that pulses with the beat. */
    var ambientLight: Boolean
        get() = sp.getBoolean("ambient_light", true)
        set(value) = sp.edit { putBoolean("ambient_light", value) }

    /** Show the current lyric line on the lock screen and notification while a song plays. */
    var lockScreenLyrics: Boolean
        get() = sp.getBoolean("lock_screen_lyrics", true)
        set(value) = sp.edit { putBoolean("lock_screen_lyrics", value) }

    /** Check GitHub Releases for a newer version (at most once a day). */
    var checkUpdates: Boolean
        get() = sp.getBoolean("check_updates", true)
        set(value) = sp.edit { putBoolean("check_updates", value) }

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(value) = sp.edit { putLong("last_update_check", value) }

    /** The version the user dismissed, so the banner doesn't come back for it. */
    var dismissedUpdate: String
        get() = sp.getString("dismissed_update", "").orEmpty()
        set(value) = sp.edit { putString("dismissed_update", value) }

    /** When the queue runs out, keep playing similar songs. */
    var autoplay: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(value) = sp.edit { putBoolean("autoplay", value) }

    var savedQueue: SavedQueue?
        get() = runCatching {
            val o = JSONObject(sp.getString("saved_queue", null) ?: return null)
            val ids = o.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getLong(it) } }
            SavedQueue(ids, o.getInt("index"), o.getLong("position")).takeIf { ids.isNotEmpty() }
        }.getOrNull()
        set(q) = sp.edit {
            if (q == null) remove("saved_queue")
            else putString("saved_queue", JSONObject().put("ids", JSONArray(q.songIds)).put("index", q.index)
                .put("position", q.positionMs).toString())
        }

    /** Newest first, at most [MAX_RECENT_SEARCHES]. */
    var recentSearches: List<String>
        get() = runCatching {
            val a = JSONArray(sp.getString("recent_searches", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        set(value) = sp.edit { putString("recent_searches", JSONArray(value.take(MAX_RECENT_SEARCHES)).toString()) }

    /** "Not now" on the low-storage prompt: don't ask again before this time. */
    var storagePromptSnoozedUntil: Long
        get() = sp.getLong("storage_snooze", 0L)
        set(value) = sp.edit { putLong("storage_snooze", value) }

    var lastSync: Long
        get() = sp.getLong("last_sync", 0L)
        set(value) = sp.edit { putLong("last_sync", value) }

    @Volatile
    private var cookieCache: String? = null
    @Volatile
    private var cookieLoaded = false

    /**
     * Optional YouTube cookies ("name=value; name2=value2"), only needed if YouTube
     * starts blocking this device. Stored encrypted with a hardware-backed key.
     */
    var youtubeCookies: String?
        get() {
            if (!cookieLoaded) {
                cookieCache = sp.getString("yt_cookies", null)?.let {
                    runCatching { String(keys.decrypt(it)) }.getOrNull()
                }
                cookieLoaded = true
            }
            return cookieCache
        }
        set(value) {
            sp.edit {
                if (value.isNullOrBlank()) remove("yt_cookies")
                else putString("yt_cookies", keys.encrypt(value.toByteArray()))
            }
            cookieCache = value?.ifBlank { null }
            cookieLoaded = true
        }

    companion object {
        const val MAX_RECENT_SEARCHES = 10

        /** Convert a Netscape cookies.txt export into a Cookie header for youtube.com. */
        fun parseCookiesTxt(text: String): String? = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) }
            .map { it.removePrefix("#HttpOnly_").split('\t') }
            .filter { it.size >= 7 && it[0].trimStart('.').endsWith("youtube.com") }
            .joinToString("; ") { "${it[5]}=${it[6]}" }
            .ifEmpty { null }
    }
}
