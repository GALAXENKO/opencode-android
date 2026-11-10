package ai.opencode.mobile

import android.content.Context
import android.content.res.Configuration
import android.webkit.CookieManager
import java.util.Locale

/**
 * Language for the built-in local-AI screens.
 *
 * opencode already owns a language setting: its web UI writes the chosen locale to the
 * `oc_locale` cookie (`Path=/`, one year, `SameSite=Lax`) and mirrors it into local
 * storage. The native screens deliberately carry **no** language picker of their own —
 * they read that cookie and apply the same locale to their resources, so choosing
 * "Русский" inside opencode also translates the local-AI menu entry and screen.
 *
 * opencode locale ids are not plain BCP-47 tags (`zht`, `br`, `no`), so [localeFor]
 * normalises them into real [Locale]s whose resource qualifiers exist (`values-zh-rTW`,
 * `values-pt-rBR`, `values-nb`).
 */
object LocalAiLocale {

    /** Cookie opencode writes whenever its locale changes. */
    private const val COOKIE = "oc_locale"

    /** opencode locale id such as `ru`, `zht`, `br`; empty when nothing was chosen yet. */
    fun code(ctx: Context): String {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
        for (host in listOf("127.0.0.1", "localhost")) {
            val header = runCatching { manager.getCookie("http://$host/") }.getOrNull() ?: continue
            val value = parse(header)
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** The locale to render the local-AI screens with. */
    fun current(ctx: Context): Locale {
        val code = code(ctx)
        if (code.isEmpty()) return Locale.getDefault()
        return localeFor(code)
    }

    /** `newBase` with [current] applied, so `values-<locale>/strings.xml` is picked up. */
    fun wrap(newBase: Context): Context {
        val locale = runCatching { current(newBase) }.getOrDefault(Locale.getDefault())
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        return runCatching { newBase.createConfigurationContext(config) }.getOrDefault(newBase)
    }

    /** Resolves a string in opencode's language, for contexts outside the wrapped activity. */
    fun text(ctx: Context, resId: Int): String = wrap(ctx).getString(resId)

    // ------------------------------------------------------------------ mapping

    /** Extracts the `oc_locale` value from a `Cookie` request header. */
    private fun parse(header: String): String {
        for (part in header.split(';')) {
            val trimmed = part.trim()
            if (!trimmed.startsWith("$COOKIE=")) continue
            val raw = trimmed.substring(COOKIE.length + 1).trim()
            val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            return decoded.trim().lowercase(Locale.ROOT)
        }
        return ""
    }

    /** opencode locale id -> concrete [Locale] with an existing resource qualifier. */
    fun localeFor(code: String): Locale = when (code.lowercase(Locale.ROOT)) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "zht" -> Locale.TRADITIONAL_CHINESE
        "br" -> Locale.forLanguageTag("pt-BR")
        "no" -> Locale.forLanguageTag("nb-NO")
        "he" -> Locale.forLanguageTag("iw-IL")
        else -> Locale.forLanguageTag(code.lowercase(Locale.ROOT))
    }
}
