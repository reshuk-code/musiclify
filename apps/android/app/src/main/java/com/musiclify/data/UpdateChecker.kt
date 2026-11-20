package com.musiclify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** A newer Android release on GitHub. */
data class AppUpdate(val version: String, val apkUrl: String?, val pageUrl: String)

/**
 * Looks for a newer Android release on GitHub Releases (tags named `android-vX.Y`).
 * Only reads the public releases list: nothing about the user or device is sent.
 */
class UpdateChecker(private val http: OkHttpClient) {

    suspend fun check(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            res.body?.string() ?: return@withContext null
        }
        val releases = JSONArray(body)
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (!tag.startsWith(TAG_PREFIX)) continue
            val version = tag.removePrefix(TAG_PREFIX)
            if (compareVersions(version, currentVersion) <= 0) return@withContext null
            val assets = r.optJSONArray("assets") ?: JSONArray()
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
            return@withContext AppUpdate(version, apk, r.optString("html_url"))
        }
        null
    }

    companion object {
        private const val RELEASES_API = "https://api.github.com/repos/reshuk-code/musiclify/releases?per_page=30"
        private const val TAG_PREFIX = "android-v"

        /** Compares dotted versions numerically ("0.10" > "0.9"); suffixes like "-debug" are ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
