package ai.opencode.mobile

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tiny JSON-over-HTTPS helper.
 *
 * The integrations (GitHub, Google Drive) only need a handful of requests, so this uses
 * [HttpURLConnection] instead of pulling in OkHttp/Retrofit. Everything is blocking and
 * must be called off the main thread.
 */
object HttpJson {

    /** Non-2xx answer; [body] keeps the provider's error message for the UI. */
    class HttpError(val code: Int, val body: String) :
        IOException("HTTP $code: " + body.take(400).replace('\n', ' '))

    data class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        fun json(): JSONObject = JSONObject(body.ifBlank { "{}" })
        fun requireOk(): Response {
            if (!ok) throw HttpError(code, body)
            return this
        }
    }

    /** `application/x-www-form-urlencoded` body from a map. */
    fun form(fields: Map<String, String>): String = fields.entries.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 30_000,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            useCaches = false
            for ((key, value) in headers) setRequestProperty(key, value)
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        request("GET", url, headers).requireOk().json()

    fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Response = request(
        "POST",
        url,
        headers,
        form(fields).toByteArray(),
        "application/x-www-form-urlencoded",
    )

    fun putJson(
        url: String,
        json: JSONObject,
        headers: Map<String, String> = emptyMap(),
    ): Response = request(
        "PUT",
        url,
        headers,
        json.toString().toByteArray(),
        "application/json",
    )

    /** Downloads a binary body, refusing anything that is not 2xx. */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 120_000,
    ): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            useCaches = false
            for ((key, value) in headers) setRequestProperty(key, value)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw HttpError(code, text)
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
