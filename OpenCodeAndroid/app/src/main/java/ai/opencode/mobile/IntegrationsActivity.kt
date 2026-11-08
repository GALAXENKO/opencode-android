package ai.opencode.mobile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Connects the app to GitHub and Google Drive.
 *
 * No client secret ships with the APK: GitHub uses a bundled public client id with the
 * **device flow** (the user only types a short code on github.com), and Drive uses the
 * *user's* own "Desktop app" client id with **PKCE** plus the loopback redirect Google
 * allows for that client type, caught by a one-shot server on 127.0.0.1.
 *
 * Files land in `~/work` (see [AppEnv.workDir]) so the embedded agent can open them
 * with the same tools it uses for everything else.
 */
class IntegrationsActivity : AppCompatActivity() {

    private lateinit var githubClientId: EditText
    private lateinit var driveClientId: EditText
    private lateinit var githubStatus: TextView
    private lateinit var driveStatus: TextView
    private lateinit var logView: TextView

    private var deviceDialog: AlertDialog? = null

    @Volatile
    private var pollingGithub = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_integrations)

        githubClientId = findViewById(R.id.githubClientId)
        driveClientId = findViewById(R.id.driveClientId)
        githubStatus = findViewById(R.id.githubStatus)
        driveStatus = findViewById(R.id.driveStatus)
        logView = findViewById(R.id.integrationLog)

        githubClientId.setText(IntegrationStore.githubClientId(this))
        driveClientId.setText(IntegrationStore.driveClientId(this))

        findViewById<Button>(R.id.btnGithubConnect).setOnClickListener { connectGithub() }
        findViewById<Button>(R.id.btnGithubDisconnect).setOnClickListener {
            pollingGithub = false
            IntegrationStore.clearGithub(this)
            appendLog(getString(R.string.integrations_disconnected_log))
            refreshStatus()
        }
        findViewById<Button>(R.id.btnGithubClone).setOnClickListener { pickGithubRepo() }
        findViewById<Button>(R.id.btnGithubPush).setOnClickListener { pushFileDialog() }

        findViewById<Button>(R.id.btnDriveConnect).setOnClickListener { connectDrive() }
        findViewById<Button>(R.id.btnDriveDisconnect).setOnClickListener {
            IntegrationStore.clearDrive(this)
            appendLog(getString(R.string.integrations_disconnected_log))
            refreshStatus()
        }
        findViewById<Button>(R.id.btnDriveList).setOnClickListener { pickDriveFile() }
        findViewById<Button>(R.id.btnDriveUpload).setOnClickListener { uploadDriveDialog() }

        AppEnv.ensureDirs(this)
        refreshStatus()
    }

    override fun onDestroy() {
        pollingGithub = false
        deviceDialog?.dismiss()
        deviceDialog = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------- status

    private fun refreshStatus() {
        val login = IntegrationStore.githubLogin(this)
        githubStatus.text = if (login.isNotEmpty()) {
            getString(R.string.integrations_github_connected, login)
        } else {
            getString(R.string.integrations_not_connected)
        }
        driveStatus.text = if (IntegrationStore.hasDrive(this)) {
            val account = IntegrationStore.driveAccount(this)
                .ifEmpty { getString(R.string.integrations_drive_account_unknown) }
            getString(R.string.integrations_drive_connected, account)
        } else {
            getString(R.string.integrations_not_connected)
        }
    }

    // --------------------------------------------------------------- background

    /**
     * Runs [work] on a background thread; whatever it returns is appended to the log.
     * An empty result means "the operation already reported itself" (e.g. opened a dialog).
     */
    private fun bg(label: String? = null, work: () -> String) {
        if (!label.isNullOrEmpty()) appendLog(label)
        Thread {
            val result = try {
                work()
            } catch (t: Throwable) {
                getString(R.string.integrations_error, describe(t))
            }
            runOnUiThread {
                appendLog(result)
                refreshStatus()
            }
        }.start()
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { appendLog(line) }
            return
        }
        val merged = if (logView.text.isEmpty()) line else logView.text.toString() + "\n" + line
        val lines = merged.lines()
        logView.text = if (lines.size > MAX_LOG_LINES) {
            lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        } else {
            merged
        }
    }

    private fun describe(t: Throwable): String {
        val message = t.message
        return if (message.isNullOrBlank()) t.javaClass.simpleName else message
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------- github

    private fun connectGithub() {
        val typed = githubClientId.text.toString().trim()
        // An empty field means "use the app's own OAuth App" instead of an error.
        val clientId = typed.ifEmpty { IntegrationStore.DEFAULT_GITHUB_CLIENT_ID }
        if (typed.isEmpty()) githubClientId.setText(clientId)
        IntegrationStore.setGithubClientId(this, clientId)
        bg(getString(R.string.integrations_github_connect_started)) {
            val code = GitHubIntegration.startDeviceFlow(clientId)
            runOnUiThread { showDeviceDialog(clientId, code) }
            ""
        }
    }

    /** Shows the `user_code` and starts polling in the background. */
    private fun showDeviceDialog(clientId: String, code: GitHubIntegration.DeviceCode) {
        if (isFinishing || isDestroyed) return
        pollingGithub = false
        deviceDialog?.dismiss()

        val verifyUrl = code.verificationUri.ifEmpty { GitHubIntegration.VERIFY_URL }
        deviceDialog = AlertDialog.Builder(this)
            .setTitle(R.string.integrations_device_title)
            .setMessage(getString(R.string.integrations_device_text, verifyUrl, code.userCode))
            .setPositiveButton(R.string.integrations_device_open) { _, _ -> launchUrl(verifyUrl) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pollingGithub = false }
            .setOnDismissListener { pollingGithub = false }
            .create()
        deviceDialog?.show()

        // The code expires quickly and has to be typed into the browser: copy it right away.
        copyToClipboard(code.userCode)
        toast(R.string.integrations_device_copied)

        pollingGithub = true
        Thread { pollGithub(clientId, code) }.start()
    }

    /**
     * Polls the token endpoint until the user approves the request.
     *
     * `slow_down` increases the interval by 5 s as the spec requires; the whole loop gives
     * up when the device code expires.
     */
    private fun pollGithub(clientId: String, code: GitHubIntegration.DeviceCode) {
        var interval = code.interval.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + code.expiresIn.coerceAtLeast(60) * 1000L
        try {
            while (pollingGithub && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval * 1000L)
                if (!pollingGithub) return

                val result = GitHubIntegration.poll(clientId, code.deviceCode)
                if (result.slowDown) interval += 5

                val token = result.token
                if (token != null) {
                    val login = runCatching {
                        GitHubIntegration.user(token).optString("login")
                    }.getOrDefault("")
                    val name = login.ifEmpty { "GitHub" }
                    IntegrationStore.setGithubSession(this, token, name)
                    finishDeviceFlow(getString(R.string.integrations_github_connected, name))
                    return
                }

                val error = result.error
                if (error != null) {
                    finishDeviceFlow(getString(R.string.integrations_error, error))
                    return
                }
            }
            val expired = pollingGithub
            pollingGithub = false
            runOnUiThread {
                deviceDialog?.dismiss()
                deviceDialog = null
                if (expired) appendLog(getString(R.string.integrations_device_expired))
            }
        } catch (t: Throwable) {
            finishDeviceFlow(getString(R.string.integrations_error, describe(t)))
        }
    }

    private fun finishDeviceFlow(line: String) {
        pollingGithub = false
        runOnUiThread {
            deviceDialog?.dismiss()
            deviceDialog = null
            appendLog(line)
            refreshStatus()
        }
    }

    /** Lists the account's repositories, then downloads the chosen one into `~/work`. */
    private fun pickGithubRepo() {
        val token = IntegrationStore.githubToken(this)
        if (token.isEmpty()) {
            toast(R.string.integrations_need_connection)
            return
        }
        bg(getString(R.string.integrations_loading_repos)) {
            val repos = GitHubIntegration.repos(token)
            runOnUiThread {
                if (repos.isEmpty()) {
                    appendLog(getString(R.string.integrations_repo_empty))
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.integrations_repo_picker)
                        .setItems(repos.toTypedArray()) { _, which -> cloneRepo(token, repos[which]) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            ""
        }
    }

    private fun cloneRepo(token: String, repo: String) {
        val name = repo.substringAfterLast('/').ifEmpty { "repository" }
        val dest = File(AppEnv.workDir(this), name)
        bg(getString(R.string.integrations_cloning, repo)) {
            if (dest.isDirectory) dest.deleteRecursively()
            val count = GitHubIntegration.downloadRepo(token, repo, dest)
            getString(R.string.integrations_cloned, repo, relativeToHome(dest), count)
        }
    }

    /** Creates or updates one file in a repository (a single commit per call). */
    private fun pushFileDialog() {
        val token = IntegrationStore.githubToken(this)
        if (token.isEmpty()) {
            toast(R.string.integrations_need_connection)
            return
        }
        formDialog(
            titleRes = R.string.integrations_github_push,
            hints = listOf(
                R.string.integrations_field_repo,
                R.string.integrations_field_local,
                R.string.integrations_field_remote,
                R.string.integrations_field_message,
            ),
            positiveRes = R.string.integrations_push_action,
        ) { values ->
            val repo = values[0]
            val local = values[1]
            val remote = values[2]
            val message = values[3]
            when {
                repo.isEmpty() -> toast(R.string.integrations_error_repo)
                local.isEmpty() -> toast(R.string.integrations_error_local)
                remote.isEmpty() -> toast(R.string.integrations_error_remote)
                else -> {
                    val file = resolveHome(local)
                    if (!file.isFile) {
                        appendLog(getString(R.string.integrations_local_missing, local))
                        return@formDialog
                    }
                    bg(getString(R.string.integrations_pushing, remote, repo)) {
                        val sha = GitHubIntegration.uploadFile(
                            token,
                            repo,
                            remote,
                            file.readBytes(),
                            message,
                        )
                        getString(R.string.integrations_pushed, remote, repo, sha.take(7).ifEmpty { "?" })
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------- drive

    /**
     * PKCE authorization code flow.
     *
     * A one-shot server on `127.0.0.1` catches the redirect, so no custom scheme and no
     * client secret are needed. Everything blocking runs on the [bg] thread; only the
     * browser launch and the log go through the UI thread.
     */
    private fun connectDrive() {
        val clientId = driveClientId.text.toString().trim()
        if (clientId.isEmpty()) {
            toast(R.string.integrations_need_client_id)
            return
        }
        IntegrationStore.setDriveClientId(this, clientId)
        bg(null) {
            DriveIntegration.RedirectServer().use { server ->
                val verifier = DriveIntegration.newVerifier()
                val url = DriveIntegration.authorizeUrl(
                    clientId,
                    server.redirectUri,
                    DriveIntegration.challenge(verifier),
                )
                runOnUiThread { appendLog(getString(R.string.integrations_opening_browser)) }
                if (!openBrowser(url)) {
                    throw IOException(getString(R.string.integrations_no_browser))
                }
                val code = server.awaitCode()
                val token = DriveIntegration.exchange(clientId, code, verifier, server.redirectUri)
                val account = DriveIntegration.account(token.accessToken)
                IntegrationStore.setDriveToken(this, token.copy(account = account))
                getString(
                    R.string.integrations_drive_connected_log,
                    account.ifEmpty { getString(R.string.integrations_drive_account_unknown) },
                )
            }
        }
    }

    /** Lists Drive files; tapping one downloads it into `~/work`. */
    private fun pickDriveFile() {
        if (!IntegrationStore.hasDrive(this)) {
            toast(R.string.integrations_need_connection)
            return
        }
        bg(getString(R.string.integrations_loading_drive)) {
            val token = IntegrationStore.requireDrive(this)
            val files = DriveIntegration.list(token)
            runOnUiThread {
                if (files.isEmpty()) {
                    appendLog(getString(R.string.integrations_drive_empty))
                } else {
                    val labels = files.map { file ->
                        val size = humanSize(file.size)
                        if (size.isEmpty()) file.name else "${file.name} · $size"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.integrations_drive_picker)
                        .setItems(labels) { _, which -> downloadDriveFile(files[which]) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            ""
        }
    }

    private fun downloadDriveFile(file: DriveIntegration.DriveFile) {
        val name = file.name.ifEmpty { file.id }
        val dest = File(AppEnv.workDir(this), name)
        bg(getString(R.string.integrations_downloading, name)) {
            val token = IntegrationStore.requireDrive(this)
            val bytes = DriveIntegration.download(token, file.id)
            DriveIntegration.writeBytes(dest, bytes)
            getString(
                R.string.integrations_downloaded,
                name,
                humanSize(bytes.size.toLong()) + " → " + relativeToHome(dest),
            )
        }
    }

    private fun uploadDriveDialog() {
        if (!IntegrationStore.hasDrive(this)) {
            toast(R.string.integrations_need_connection)
            return
        }
        formDialog(
            titleRes = R.string.integrations_drive_upload,
            hints = listOf(R.string.integrations_field_local, R.string.integrations_field_name),
            positiveRes = R.string.integrations_upload_action,
        ) { values ->
            val local = values[0]
            if (local.isEmpty()) {
                toast(R.string.integrations_error_local)
                return@formDialog
            }
            val file = resolveHome(local)
            if (!file.isFile) {
                appendLog(getString(R.string.integrations_local_missing, local))
                return@formDialog
            }
            val name = values[1].ifEmpty { file.name }
            bg(getString(R.string.integrations_uploading, name)) {
                val token = IntegrationStore.requireDrive(this)
                val id = DriveIntegration.upload(token, name, mimeOf(name), file.readBytes())
                getString(R.string.integrations_uploaded, name, id)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Vertical form with one single-line field per hint; [onOk] gets the trimmed values.
     */
    private fun formDialog(
        titleRes: Int,
        hints: List<Int>,
        positiveRes: Int,
        onOk: (List<String>) -> Unit,
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val inputs = hints.mapIndexed { index, hint ->
            EditText(this).apply {
                setHint(hint)
                setTextColor(0xFFE8E8E8.toInt())
                textSize = 13f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = if (index == hints.lastIndex) {
                    EditorInfo.IME_ACTION_DONE
                } else {
                    EditorInfo.IME_ACTION_NEXT
                }
                maxLines = 1
                setSingleLine(true)
            }.also { field ->
                container.addView(field, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(positiveRes) { _, _ -> onOk(inputs.map { it.text.toString().trim() }) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Resolves a typed path: absolute stays absolute, otherwise relative to `$HOME`. */
    private fun resolveHome(path: String): File {
        val trimmed = path.trim()
        val home = AppEnv.home(this)
        return when {
            trimmed.isEmpty() || trimmed == "~" -> home
            trimmed.startsWith("~/") -> File(home, trimmed.removePrefix("~/"))
            else -> File(trimmed).let { if (it.isAbsolute) it else File(home, trimmed) }
        }
    }

    /** Path relative to `$HOME` when possible, for compact log lines. */
    private fun relativeToHome(file: File): String = runCatching {
        file.relativeTo(AppEnv.home(this)).path
    }.getOrDefault(file.absolutePath)

    private fun mimeOf(name: String): String =
        java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "$bytes B"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenCode", text))
    }

    /** Opens [url] in a browser; safe to call from any thread. */
    private fun openBrowser(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: ActivityNotFoundException) {
        appendLog(getString(R.string.integrations_error, describe(e)))
        false
    }

    private fun launchUrl(url: String) {
        openBrowser(url)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** The log is a plain TextView: keep the tail small so it stays snappy. */
        const val MAX_LOG_LINES = 200
    }
}
