package com.musiclify.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** HTTP bridge NewPipeExtractor uses for all YouTube requests. */
class NewPipeHttp(
    private val client: OkHttpClient,
    private val cookies: () -> String?,
) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val body = request.dataToSend()?.toRequestBody()
            ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null

        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(request.url())
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        // Optional user-imported cookies, only ever sent to youtube.com.
        val host = request.url().toHttpUrlOrNull()?.host.orEmpty()
        val userCookies = cookies()
        if (userCookies != null && (host == "youtube.com" || host.endsWith(".youtube.com"))) {
            val existing = request.headers()["Cookie"]?.joinToString("; ")
            builder.header("Cookie", listOfNotNull(existing, userCookies).joinToString("; "))
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("YouTube is rate-limiting this device", request.url())
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
