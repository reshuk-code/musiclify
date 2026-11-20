package com.musiclify.engine

import android.net.Uri
import android.os.Build
import com.musiclify.data.ImportSummary
import com.musiclify.data.Pairing
import com.musiclify.data.Prefs
import com.musiclify.data.Sync
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Parsed contents of the QR code another Musiclify device shows. */
data class LinkTarget(val host: String, val port: Int, val key: ByteArray) {
    val baseUrl get() = "http://$host:$port"

    companion object {
        /** `musiclify://link?v=1&h=192.168.1.5&p=53123&k=<base64url key>` */
        fun parse(payload: String): LinkTarget? {
            val uri = runCatching { Uri.parse(payload.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "musiclify" || uri.host != "link") return null
            val host = uri.getQueryParameter("h") ?: return null
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
            val key = runCatching { Base64.getUrlDecoder().decode(uri.getQueryParameter("k")) }.getOrNull() ?: return null
            // Only private LAN addresses: the QR must not be able to send your library to the internet.
            if (key.size != 32 || !isPrivateIpv4(host)) return null
            return LinkTarget(host, port, key)
        }

        fun isPrivateIpv4(host: String): Boolean {
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || p.any { it !in 0..255 }) return false
            return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
        }
    }
}

/**
 * Talks to another device's link server over the local network. Every payload is sealed with
 * AES-256-GCM using the one-time key from the QR code (same scheme as desktop/src-tauri/src/crypto.rs).
 */
class DeviceLink(http: OkHttpClient, private val sync: Sync, private val prefs: Prefs) {
    private val client = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val quick = http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()

    class NotPairedException(msg: String) : IOException(msg)

    /** Ask the PC for a long-term pairing (done during QR linking) so Sync works without scanning again. */
    suspend fun pair(target: LinkTarget) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim()).toString()
        val req = Request.Builder().url("${target.baseUrl}/v1/pair")
            .post(seal(target.key, body.toByteArray()).toRequestBody("text/plain".toMediaType())).build()
        val resp = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Pairing refused (${r.code})")
            JSONObject(String(open(target.key, r.body!!.string())))
        }
        prefs.pairing = Pairing(
            host = target.host,
            port = resp.getInt("port"),
            deviceId = resp.getString("deviceId"),
            key = Base64.getUrlDecoder().decode(resp.getString("pairKey")),
            pcName = resp.optString("pcName", "PC"),
        )
    }

    /**
     * Two-way sync with the paired PC: pull its library, then push ours, so both end up with
     * everything. Finds the PC again on the LAN if its address changed.
     */
    suspend fun syncNow(): ImportSummary = withContext(Dispatchers.IO) {
        val p = prefs.pairing ?: throw NotPairedException("Not paired with a PC yet.")
        val base = resolveBase(p)
        fun Request.Builder.auth() = header("X-Musiclify-Device", p.deviceId)

        val sealed = client.newCall(Request.Builder().url("$base/v1/sync/export").auth().build()).execute().use { r ->
            if (r.code == 401) { prefs.pairing = null; throw NotPairedException("${p.pcName} doesn't know this phone anymore. Link again with the QR code.") }
            if (!r.isSuccessful) throw IOException("Sync failed (${r.code})")
            r.body!!.string()
        }
        val summary = sync.import(String(open(p.key, sealed)))

        val push = Request.Builder().url("$base/v1/sync/import").auth()
            .post(seal(p.key, sync.export().toByteArray()).toRequestBody("text/plain".toMediaType())).build()
        client.newCall(push).execute().use { r -> if (!r.isSuccessful) throw IOException("Sync failed (${r.code})") }

        prefs.lastSync = System.currentTimeMillis()
        summary
    }

    /** The paired PC's address; finds it again on the LAN if it moved. */
    private fun resolveBase(p: Pairing): String {
        if (reachable(p.host, p.port)) return "http://${p.host}:${p.port}"
        val (host, port) = discover()
            ?: throw IOException("Couldn't find ${p.pcName}. Open Musiclify on the PC and make sure both are on the same Wi-Fi.")
        prefs.pairing = p.copy(host = host, port = port)
        return "http://$host:$port"
    }

    /** Hand playback to the PC: it continues the same queue at the same position. */
    suspend fun handoff(payload: JSONObject) = withContext(Dispatchers.IO) {
        val p = prefs.pairing ?: throw NotPairedException("Not paired with a PC yet.")
        val base = resolveBase(p)
        val req = Request.Builder().url("$base/v1/sync/handoff").header("X-Musiclify-Device", p.deviceId)
            .post(seal(p.key, payload.toString().toByteArray()).toRequestBody("text/plain".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) { prefs.pairing = null; throw NotPairedException("${p.pcName} doesn't know this phone anymore. Link again with the QR code.") }
            if (!r.isSuccessful) throw IOException("${p.pcName} couldn't take over playback (${r.code}).")
        }
    }

    private fun reachable(host: String, port: Int): Boolean = runCatching {
        quick.newCall(Request.Builder().url("http://$host:$port/v1/sync/hello").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** UDP broadcast "MUSICLIFY_DISCOVER" -> PC answers "MUSICLIFY <port>". */
    private fun discover(): Pair<String, Int>? = runCatching {
        java.net.DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 2500
            val msg = "MUSICLIFY_DISCOVER".toByteArray()
            socket.send(java.net.DatagramPacket(msg, msg.size, java.net.InetAddress.getByName("255.255.255.255"), 47816))
            val buf = ByteArray(64)
            val reply = java.net.DatagramPacket(buf, buf.size)
            socket.receive(reply)
            val text = String(reply.data, 0, reply.length)
            val port = text.removePrefix("MUSICLIFY ").trim().toIntOrNull() ?: return null
            val host = reply.address.hostAddress ?: return null
            if (LinkTarget.isPrivateIpv4(host)) host to port else null
        }
    }.getOrNull()

    /** Send this phone's library to the other device. */
    suspend fun send(target: LinkTarget) = withContext(Dispatchers.IO) {
        val sealed = seal(target.key, sync.export().toByteArray())
        val req = Request.Builder().url("${target.baseUrl}/v1/import")
            .post(sealed.toRequestBody("text/plain".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The other device refused the data (${r.code}).")
        }
    }

    /** Copy the other device's library into this phone. */
    suspend fun receive(target: LinkTarget): ImportSummary = withContext(Dispatchers.IO) {
        val sealed = client.newCall(Request.Builder().url("${target.baseUrl}/v1/export").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The other device didn't respond (${r.code}).")
            r.body!!.string()
        }
        sync.import(String(open(target.key, sealed)))
    }

    private fun seal(key: ByteArray, plain: ByteArray): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain))
    }

    private fun open(key: ByteArray, sealed: String): ByteArray {
        val bytes = Base64.getDecoder().decode(sealed.trim())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
}
