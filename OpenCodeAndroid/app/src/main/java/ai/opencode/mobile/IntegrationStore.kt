package ai.opencode.mobile

import android.content.Context
import java.io.IOException

/**
 * Persistent storage for the optional cloud integrations.
 *
 * Everything is kept in a private `SharedPreferences` file: the GitHub OAuth client id +
 * access token, and the Google OAuth client id + refresh token. The client ids are
 * supplied by the user (this build ships no secrets, so it works with *your* OAuth apps
 * only) and the tokens never leave the app sandbox.
 */
object IntegrationStore {

    private const val FILE = "integrations"

    // ------------------------------------------------------------------ storage

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun get(ctx: Context, key: String): String =
        prefs(ctx).getString(key, "").orEmpty()

    private fun put(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }

    // ------------------------------------------------------------------- github

    fun githubClientId(ctx: Context): String = get(ctx, "github_client_id")

    fun setGithubClientId(ctx: Context, value: String) = put(ctx, "github_client_id", value.trim())

    fun githubToken(ctx: Context): String = get(ctx, "github_token")

    fun githubLogin(ctx: Context): String = get(ctx, "github_login")

    fun setGithubSession(ctx: Context, token: String, login: String) {
        prefs(ctx).edit()
            .putString("github_token", token)
            .putString("github_login", login)
            .apply()
    }

    fun clearGithub(ctx: Context) {
        prefs(ctx).edit()
            .remove("github_token")
            .remove("github_login")
            .apply()
    }

    // -------------------------------------------------------------------- drive

    fun driveClientId(ctx: Context): String = get(ctx, "drive_client_id")

    fun setDriveClientId(ctx: Context, value: String) = put(ctx, "drive_client_id", value.trim())

    fun driveAccount(ctx: Context): String = get(ctx, "drive_account")

    fun hasDrive(ctx: Context): Boolean = driveRefreshToken(ctx).isNotEmpty()

    private fun driveRefreshToken(ctx: Context): String = get(ctx, "drive_refresh")

    /** Stores a freshly obtained token pair (expiry is relative, in seconds). */
    fun setDriveToken(ctx: Context, token: DriveIntegration.Token) {
        val editor = prefs(ctx).edit()
            .putString("drive_access", token.accessToken)
            .putString("drive_expires", (System.currentTimeMillis() + token.expiresIn * 1000L).toString())
        if (!token.refreshToken.isNullOrEmpty()) {
            editor.putString("drive_refresh", token.refreshToken)
        }
        if (!token.account.isNullOrEmpty()) {
            editor.putString("drive_account", token.account)
        }
        editor.apply()
    }

    fun clearDrive(ctx: Context) {
        prefs(ctx).edit()
            .remove("drive_access")
            .remove("drive_refresh")
            .remove("drive_expires")
            .remove("drive_account")
            .apply()
    }

    /**
     * A valid Drive access token, refreshing it when it is about to expire.
     *
     * Blocking (may hit the network) — call it from a background thread.
     */
    fun driveAccessToken(ctx: Context): String? {
        val clientId = driveClientId(ctx)
        val refresh = driveRefreshToken(ctx)
        val access = get(ctx, "drive_access")
        val expires = get(ctx, "drive_expires").toLongOrNull() ?: 0L
        val fresh = access.isNotEmpty() && System.currentTimeMillis() < expires - 60_000L
        if (fresh) return access
        if (clientId.isEmpty() || refresh.isEmpty()) return access.ifEmpty { null }
        val token = DriveIntegration.refresh(clientId, refresh)
        setDriveToken(ctx, token)
        return token.accessToken
    }

    /** Wraps a missing-token case into something the UI can show. */
    fun requireDrive(ctx: Context): String =
        driveAccessToken(ctx) ?: throw IOException("Google Drive не подключён")
}
