package ai.opencode.mobile

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Re-implementation of the server's session cookie scheme so the embedded WebView can
 * authenticate without a reverse proxy.
 *
 * Server side (see `@opencode/ServerAuthConfig`):
 *   inner = HMAC-SHA256(key = password, msg = "opencode-session-v1")
 *   sig   = base64url(HMAC-SHA256(key = inner, msg = exp))   // no padding
 *   cookie opencode_session_<port> = "<exp>.<sig>"
 *
 * The same signature also works for the bare `opencode_session` cookie (non port-bound).
 */
object OpenCodeAuth {

    private const val SESSION_VERSION = "opencode-session-v1"
    private const val DEFAULT_TTL_SECONDS = 2_592_000L // 30 days, same as the server

    fun cookieName(port: Int): String = "opencode_session_$port"

    fun forgeToken(password: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS): String {
        val exp = (System.currentTimeMillis() / 1000L + ttlSeconds).toString()
        val inner = hmac(password.toByteArray(Charsets.UTF_8), SESSION_VERSION.toByteArray(Charsets.UTF_8))
        val sig = hmac(inner, exp.toByteArray(Charsets.UTF_8))
        return "$exp." + Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun cookieHeader(password: String, port: Int): String = "${cookieName(port)}=${forgeToken(password)}"

    fun basicHeader(password: String): String =
        "Basic " + Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun newPassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
