package com.musiclify.engine

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SharedMatch(val youtubeId: String, val durationSec: Int)

/**
 * Client for the shared match cache (a Neon Function, see services/matchcache).
 *
 * Only "Deezer track id -> YouTube video id" is exchanged: no audio, no user data.
 * Lookups are best-effort with a short timeout so a slow or offline cache never delays playback
 * by more than a moment; the app falls back to searching YouTube itself.
 */
class MatchCache(
    http: OkHttpClient,
    private val baseUrl: String,
    private val enabled: () -> Boolean,
) {
    private val client = http.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val background = Executors.newSingleThreadExecutor()

    private val active get() = baseUrl.isNotBlank() && enabled()

    /** Blocking. Returns null when unknown, disabled, or the cache can't be reached. */
    fun lookup(deezerId: Long): SharedMatch? {
        if (!active) return null
        return try {
            client.newCall(Request.Builder().url("$baseUrl/v1/match/$deezerId").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JSONObject(r.body!!.string())
                SharedMatch(o.getString("youtube_id"), o.getInt("duration_sec"))
            }
        } catch (e: Exception) {
            Log.d(TAG, "lookup failed: ${e.message}")
            null
        }
    }

    /** Fire-and-forget: tell the cache this match was verified on this device. */
    fun submit(deezerId: Long, youtubeId: String, durationSec: Int) {
        if (!active || durationSec <= 0) return
        background.execute {
            try {
                val body = JSONObject()
                    .put("deezer_id", deezerId)
                    .put("youtube_id", youtubeId)
                    .put("duration_sec", durationSec)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url("$baseUrl/v1/match").post(body).build()).execute().close()
            } catch (e: Exception) {
                Log.d(TAG, "submit failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "MatchCache"
    }
}
