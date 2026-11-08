package ai.opencode.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google Drive access with a *user supplied* OAuth client id.
 *
 * No secret is compiled into the APK: this is the "installed app" flow with PKCE
 * (RFC 7636). The browser is sent to Google, Google redirects to
 * `http://127.0.0.1:<port>` — the loopback redirect that Google allows for "Desktop app"
 * OAuth clients — and [RedirectServer] catches the authorization code. The refresh token
 * is stored in [IntegrationStore] so the connection survives restarts.
 *
 * Blocking; call from a background thread.
 */
object DriveIntegration {

    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

    /**
     * Full Drive access: listing and downloading *existing* files is the point of the
     * integration, and `drive.file` would only see files this app created.
     */
    const val SCOPE = "https://www.googleapis.com/auth/drive"

    data class Token(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresIn: Int = 3600,
        val account: String? = null,
    )

    // --------------------------------------------------------------------- pkce

    /** 43..128 chars, base64url without padding. */
    fun newVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizeUrl(clientId: String, redirectUri: String, challenge: String): String {
        val params = mapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            // "offline" + "consent" is what actually returns a refresh token.
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return AUTH_URL + "?" + params.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
    }

    fun exchange(clientId: String, code: String, verifier: String, redirectUri: String): Token {
        val response = HttpJson.postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to redirectUri,
            ),
        )
        val json = response.json()
        val error = json.optString("error")
        if (error.isNotEmpty()) {
            throw IOException(json.optString("error_description", error))
        }
        return Token(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresIn = json.optInt("expires_in", 3600),
        )
    }

    fun refresh(clientId: String, refreshToken: String): Token {
        val json = HttpJson.postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            ),
        ).requireOk().json()
        val error = json.optString("error")
        if (error.isNotEmpty()) {
            throw IOException(json.optString("error_description", error))
        }
        return Token(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresIn = json.optInt("expires_in", 3600),
        )
    }

    // ---------------------------------------------------------------------- api

    private fun headers(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "User-Agent" to "opencode-android",
    )

    /** Display name / e-mail of the connected account, for the status line. */
    fun account(accessToken: String): String = runCatching {
        val json = HttpJson.getJson(
            "$API/about?fields=user(emailAddress,displayName)",
            headers(accessToken),
        )
        val user = json.optJSONObject("user") ?: return@runCatching ""
        user.optString("emailAddress").ifEmpty { user.optString("displayName") }
    }.getOrDefault("")

    data class DriveFile(val id: String, val name: String, val size: Long, val modified: String)

    /** Files whose name contains [query] (empty = most recently modified files). */
    fun list(accessToken: String, query: String = "", limit: Int = 50): List<DriveFile> {
        val clauses = mutableListOf("trashed = false")
        if (query.isNotBlank()) {
            clauses += "name contains '" + query.replace("'", "\\'") + "'"
        }
        val url = "$API/files?" +
            "q=" + URLEncoder.encode(clauses.joinToString(" and "), "UTF-8") +
            "&pageSize=$limit" +
            "&orderBy=" + URLEncoder.encode("modifiedTime desc", "UTF-8") +
            "&fields=" + URLEncoder.encode("files(id,name,size,modifiedTime)", "UTF-8")
        val array = JSONArray(HttpJson.request("GET", url, headers(accessToken)).requireOk().body)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            DriveFile(
                id = item.optString("id"),
                name = item.optString("name"),
                size = item.optString("size").toLongOrNull() ?: 0L,
                modified = item.optString("modifiedTime"),
            )
        }
    }

    fun download(accessToken: String, fileId: String): ByteArray = HttpJson.getBytes(
        "$API/files/" + URLEncoder.encode(fileId, "UTF-8") + "?alt=media",
        headers(accessToken),
    )

    /**
     * Uploads a new file with a `multipart/related` POST (metadata + media in one request),
     * so no PATCH/`Content-Range` support is needed.
     */
    fun upload(accessToken: String, name: String, mime: String, bytes: ByteArray): String {
        val boundary = "ocb" + System.nanoTime().toString(16)
        val metadata = JSONObject().put("name", name).toString()
        val body = java.io.ByteArrayOutputStream(bytes.size + 512).apply {
            write(("--$boundary\r\n").toByteArray())
            write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(metadata.toByteArray())
            write("\r\n--$boundary\r\n".toByteArray())
            write("Content-Type: ${mime.ifEmpty { "application/octet-stream" }}\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val json = HttpJson.request(
            "POST",
            "$UPLOAD_URL?uploadType=multipart&fields=id,name,size",
            headers(accessToken),
            body,
            "multipart/related; boundary=$boundary",
        ).requireOk().json()
        return json.optString("id")
    }

    // ------------------------------------------------------------ loopback flow

    /**
     * One-shot HTTP server on `127.0.0.1` that receives the OAuth redirect.
     *
     * Google's loopback flow does not require a fixed port, so any free port works and
     * the redirect URI is built from it.
     */
    class RedirectServer : Closeable {

        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        val port: Int get() = socket.localPort

        /** Must match the `redirect_uri` sent in the authorization request. */
        val redirectUri: String get() = "http://127.0.0.1:$port"

        /**
         * Blocks until the browser hits the redirect (or [timeoutMs] passes).
         *
         * Browsers often open (and drop) a speculative connection before the real
         * redirect arrives, so connections without a `code`/`error` parameter are answered
         * with a "waiting" page and the accept loop continues.
         *
         * @return the `code` query parameter.
         */
        fun awaitCode(timeoutMs: Int = 300_000): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            var code: String? = null
            var failure: String? = null
            try {
                while (code == null && failure == null) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val incoming = try {
                        socket.accept()
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    incoming.use { client ->
                        // Only the request line matters: "GET /?code=... HTTP/1.1".
                        // Read it byte by byte: closing a BufferedReader would close the
                        // socket before the response below could be written.
                        val requestLine = readLine(client.getInputStream())
                        val params = parseQuery(requestLine)
                        val error = params["error"]
                        val authorized = params["code"]
                        when {
                            !error.isNullOrEmpty() -> {
                                failure = "Google вернул ошибку: $error"
                                respond(client, page(failure.orEmpty()))
                            }
                            !authorized.isNullOrEmpty() -> {
                                code = authorized
                                respond(client, page("Готово! Вернитесь в приложение."))
                            }
                            else -> respond(client, page("Ожидание ответа Google…"))
                        }
                    }
                }
            } finally {
                close()
            }
            failure?.let { throw IOException(it) }
            return code ?: throw IOException("Ответ от Google не получен")
        }

        /** Reads one `\r\n`-terminated line without closing the stream. */
        private fun readLine(input: InputStream): String {
            val buffer = StringBuilder(256)
            while (buffer.length < 2048) {
                val byte = try {
                    input.read()
                } catch (e: IOException) {
                    -1
                }
                if (byte < 0 || byte == '\n'.code) break
                if (byte != '\r'.code) buffer.append(byte.toChar())
            }
            return buffer.toString()
        }

        private fun respond(client: Socket, html: String) {
            val bytes = html.toByteArray()
            runCatching {
                client.getOutputStream().use { out ->
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    out.write(bytes)
                    out.flush()
                }
            }
        }

        private fun parseQuery(requestLine: String): Map<String, String> {
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val query = target.substringAfter('?', "")
            if (query.isEmpty()) return emptyMap()
            return query.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=').let { java.net.URLDecoder.decode(it, "UTF-8") }
                val value = pair.substringAfter('=', "").let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (key.isEmpty()) null else key to value
            }.toMap()
        }

        private fun page(message: String): String = """
            <!doctype html><html lang="ru"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>OpenCode</title></head>
            <body style="background:#0f0f0f;color:#e8e8e8;font-family:sans-serif;padding:32px">
            <h2 style="font-weight:600">Google Drive</h2>
            <p style="font-size:16px">$message</p>
            </body></html>
        """.trimIndent()

        override fun close() {
            runCatching { socket.close() }
        }
    }

    // -------------------------------------------------------------------- utils

    fun readBytes(file: File): ByteArray = file.inputStream().use { it.readBytes() }

    fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(bytes) }
    }

    fun copyStream(input: InputStream, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
    }
}
