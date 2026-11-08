package ai.opencode.mobile

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * GitHub access without git.
 *
 * Two halves:
 *
 *  - **OAuth device flow** (`https://github.com/login/device/code`): the user enters a
 *    short code on github.com, no client secret and no redirect URI is involved, which is
 *    exactly what a mobile app needs. Only the *client id* of a user-created OAuth App is
 *    required — see [IntegrationsActivity] / README for the registration fields.
 *  - **REST file access**: download a single file, download a whole repository as a zip,
 *    or create/update a file (one commit per call). This works everywhere the app runs,
 *    even though Android ships no `git` binary.
 *
 * All calls are blocking; run them on a background thread.
 */
object GitHubIntegration {

    private const val API = "https://api.github.com"
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"

    /** `repo` = read/write private and public repositories, `read:user` = login name. */
    const val SCOPE = "repo read:user"

    const val VERIFY_URL = "https://github.com/login/device"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int,
    )

    data class Poll(
        val token: String? = null,
        val pending: Boolean = false,
        val slowDown: Boolean = false,
        val error: String? = null,
    )

    private fun headers(token: String? = null, accept: String = "application/vnd.github+json"): Map<String, String> {
        val map = mutableMapOf(
            "Accept" to accept,
            "User-Agent" to "opencode-android",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
        if (token != null) map["Authorization"] = "Bearer $token"
        return map
    }

    // ------------------------------------------------------------------- oauth

    fun startDeviceFlow(clientId: String): DeviceCode {
        val response = HttpJson.postForm(
            DEVICE_CODE_URL,
            mapOf("client_id" to clientId, "scope" to SCOPE),
            mapOf("Accept" to "application/json", "User-Agent" to "opencode-android"),
        )
        val json = response.requireOk().json()
        val error = json.optString("error")
        if (error.isNotEmpty()) {
            throw IOException(json.optString("error_description", error))
        }
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.optString("user_code"),
            verificationUri = json.optString("verification_uri", VERIFY_URL),
            interval = json.optInt("interval", 5).coerceAtLeast(1),
            expiresIn = json.optInt("expires_in", 900),
        )
    }

    /** One poll of the token endpoint; callers loop until [Poll.token] or [Poll.error]. */
    fun poll(clientId: String, deviceCode: String): Poll {
        val response = HttpJson.postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
            mapOf("Accept" to "application/json", "User-Agent" to "opencode-android"),
        )
        val json = response.json()
        val token = json.optString("access_token")
        if (token.isNotEmpty()) return Poll(token = token)
        return when (val error = json.optString("error")) {
            "authorization_pending" -> Poll(pending = true)
            "slow_down" -> Poll(pending = true, slowDown = true)
            "expired_token" -> Poll(error = "код истёк, попробуйте снова")
            "access_denied" -> Poll(error = "доступ отклонён")
            else -> Poll(error = json.optString("error_description", error.ifEmpty { "неизвестная ошибка" }))
        }
    }

    fun user(token: String): JSONObject = HttpJson.getJson("$API/user", headers(token))

    /** `full_name` values of the repositories the token can push to, newest first. */
    fun repos(token: String, perPage: Int = 100): List<String> {
        val array = org.json.JSONArray(
            HttpJson.request(
                "GET",
                "$API/user/repos?per_page=$perPage&sort=updated&affiliation=owner,collaborator,organization_member",
                headers(token),
            ).requireOk().body,
        )
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optString("full_name")?.takeIf { it.isNotEmpty() }
        }
    }

    // -------------------------------------------------------------------- files

    /** `owner/name` -> `owner/name`, URL-encoding each segment of a repository path. */
    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    private fun contentsUrl(repo: String, path: String): String =
        "$API/repos/${encodePath(repo)}/contents/${encodePath(path)}"

    fun downloadFile(token: String, repo: String, path: String, branch: String = ""): ByteArray {
        var url = contentsUrl(repo, path)
        if (branch.isNotEmpty()) url += "?ref=" + URLEncoder.encode(branch, "UTF-8")
        return HttpJson.getBytes(url, headers(token, accept = "application/vnd.github.raw"))
    }

    /**
     * Creates or updates `path` in `repo` (one commit).
     *
     * @return the commit sha reported by the API.
     */
    fun uploadFile(
        token: String,
        repo: String,
        path: String,
        bytes: ByteArray,
        message: String,
        branch: String = "",
    ): String {
        val sha = existingSha(token, repo, path, branch)
        val body = JSONObject().apply {
            put("message", message.ifBlank { "Update $path from OpenCode for Android" })
            put("content", Base64.getEncoder().encodeToString(bytes))
            if (sha != null) put("sha", sha)
            if (branch.isNotEmpty()) put("branch", branch)
        }
        val response = HttpJson.putJson(contentsUrl(repo, path), body, headers(token)).requireOk()
        return response.json().optJSONObject("commit")?.optString("sha").orEmpty()
    }

    private fun existingSha(token: String, repo: String, path: String, branch: String): String? = runCatching {
        var url = contentsUrl(repo, path)
        if (branch.isNotEmpty()) url += "?ref=" + URLEncoder.encode(branch, "UTF-8")
        HttpJson.getJson(url, headers(token)).optString("sha").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Downloads the default-branch archive of `repo` and extracts it into [dest].
     *
     * The zip contains a single `owner-repo-sha/` directory, which is stripped so the
     * checkout looks like a normal `git clone`.
     *
     * @return number of extracted files.
     */
    fun downloadRepo(token: String, repo: String, dest: File): Int {
        val bytes = HttpJson.getBytes("$API/repos/${encodePath(repo)}/zipball", headers(token))
        if (!dest.isDirectory) dest.mkdirs()
        var count = 0
        ZipInputStream(bytes.inputStream().buffered(1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.contains("..")) {
                    zip.closeEntry()
                    continue
                }
                // Strip the "<owner>-<repo>-<sha>/" prefix.
                val relative = name.substringAfter('/', "")
                if (relative.isEmpty()) {
                    zip.closeEntry()
                    continue
                }
                val target = File(dest, relative)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered(1 shl 16).use { out -> zip.copyTo(out, 1 shl 16) }
                    count++
                }
                zip.closeEntry()
            }
        }
        return count
    }
}
