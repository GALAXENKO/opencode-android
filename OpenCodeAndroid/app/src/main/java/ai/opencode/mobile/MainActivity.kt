package ai.opencode.mobile

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hosts the real opencode web UI (`opencode serve`) in a WebView.
 *
 * The server binds to 127.0.0.1 and requires a session cookie; we mint that cookie
 * locally (see [OpenCodeAuth]) so no reverse proxy is needed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: View
    private lateinit var errorText: TextView

    private var loadedPort = 0
    private var desktopMode = false
    private var stateJob: Job? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var popupHolder: WebView? = null

    /** Target of a camera capture: the app writes it, the camera app only fills it in. */
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null

    /** Result of the `<input type="file">` picker opened from the web UI. */
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null

            val uris = mutableListOf<Uri>()
            Log.d(TAG, "file picker result: code=${result.resultCode} data=${result.data?.data} clip=${result.data?.clipData?.itemCount ?: 0}")
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val clip = data?.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                } else {
                    data?.data?.let { uris.add(it) }
                }
                // A capture writes straight into the URI we handed to the camera app. Some
                // camera apps also return a (thumbnail) data URI, which must not win.
                pendingCameraUri?.let {
                    uris.clear()
                    uris.add(it)
                }
            }

            // A cancelled (or empty) capture must not leave a zero-byte file behind.
            val captured = pendingCameraFile
            pendingCameraUri = null
            pendingCameraFile = null
            if (captured != null && (result.resultCode != RESULT_OK || captured.length() == 0L)) {
                runCatching { captured.delete() }
            }

            callback?.onReceiveValue(uris.toTypedArray().takeIf { it.isNotEmpty() })
        }

    /**
     * Attachment picker of the web UI.
     *
     * The stock `createIntent()` only opens the system picker, which on most phones cannot
     * produce a fresh photo, video or voice note. This chooser offers every source the page
     * accepts: camera photo, camera video, gallery, audio recording and any document.
     */
    private fun openAttachmentChooser(params: WebChromeClient.FileChooserParams): Boolean {
        val accept = params.acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val any = accept.isEmpty() || accept.any { it == "*/*" || it == "*" }
        fun allows(kind: String) = any || accept.any { it == kind || it.startsWith("$kind/") }
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val images = allows("image")
        val videos = allows("video")
        val sounds = allows("audio")

        // opencode's composer hands us an untyped input (`accept=""`), so the per-source
        // entries still need a sensible filter — otherwise "Gallery" would list APKs.
        val mediaTypes = accept.filter { it.startsWith("image/") || it.startsWith("video/") }
            .ifEmpty { listOf("image/*", "video/*") }
        val audioTypes = accept.filter { it.startsWith("audio/") }
            .ifEmpty { listOf("audio/*") }

        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (images) actions += R.string.attach_photo to ({ capture(MediaStore.ACTION_IMAGE_CAPTURE, "jpg") })
        if (videos) actions += R.string.attach_video to ({ capture(MediaStore.ACTION_VIDEO_CAPTURE, "mp4") })
        if (images || videos) {
            actions += R.string.attach_gallery to ({ pickMedia(mediaTypes, multiple, false) })
        }
        if (sounds) {
            actions += R.string.attach_audio to ({ pickMedia(audioTypes, multiple, false) })
            actions += R.string.attach_record to ({ recordSound() })
        }
        actions += R.string.attach_file to ({ pickMedia(accept, multiple, true) })

        if (actions.isEmpty()) return false
        AlertDialog.Builder(this)
            .setTitle(R.string.attach_title)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setOnCancelListener { cancelFileChooser() }
            .show()
        return true
    }

    private fun cancelFileChooser() {
        pendingCameraUri = null
        pendingCameraFile = null
        val callback = fileChooserCallback
        fileChooserCallback = null
        callback?.onReceiveValue(null)
    }

    private fun launchPicker(intent: Intent) {
        try {
            filePicker.launch(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open attachment picker", t)
            Toast.makeText(this, R.string.attach_unavailable, Toast.LENGTH_SHORT).show()
            cancelFileChooser()
        }
    }

    /** Camera capture: the app creates the target file, the camera app only fills it in. */
    private fun capture(action: String, ext: String) {
        pendingCameraUri = null
        pendingCameraFile = null
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.$ext")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.attach_unavailable, Toast.LENGTH_SHORT).show()
            cancelFileChooser()
            return
        }
        pendingCameraUri = uri
        pendingCameraFile = file
        launchPicker(intent)
    }

    private fun pickMedia(accept: List<String>, multiple: Boolean, documents: Boolean) {
        pendingCameraUri = null
        pendingCameraFile = null
        val types = accept.filter { it.isNotEmpty() && it != "*/*" && it != "*" }.distinct()
        val intent = Intent(if (documents) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (types.size == 1) types.first() else "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.attach_unavailable, Toast.LENGTH_SHORT).show()
            cancelFileChooser()
            return
        }
        launchPicker(intent)
    }

    private fun recordSound() {
        pendingCameraUri = null
        pendingCameraFile = null
        val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.attach_unavailable, Toast.LENGTH_SHORT).show()
            cancelFileChooser()
            return
        }
        launchPicker(intent)
    }

    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    /** Live recogniser used for the in-app microphone button. */
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    private val voicePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginListening()
            } else {
                Toast.makeText(this, R.string.voice_permission, Toast.LENGTH_LONG).show()
            }
        }

    /** Fallback path: the system recogniser dialog, used when the service API misbehaves. */
    private val voiceDialog =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            deliverVoiceText(words?.firstOrNull().orEmpty())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)

        desktopMode = prefs.getBoolean("desktop_ua", false)

        WebView.setWebContentsDebuggingEnabled(true)

        setupWebView()
        wireUi()

        askNotificationPermission()
        ServerService.start(this)
    }

    override fun onStart() {
        super.onStart()
        // Collect the server state only while the activity is visible.
        stateJob?.cancel()
        stateJob = lifecycleScope.launch {
            ServerState.state.collect { render(it) }
        }
    }

    override fun onStop() {
        stateJob?.cancel()
        stateJob = null
        super.onStop()
    }

    override fun onDestroy() {
        dropPopup()
        releaseRecognizer()
        super.onDestroy()
    }

    /** Destroys the throw-away WebView used to satisfy `window.open` transports. */
    private fun dropPopup() {
        val popup = popupHolder ?: return
        popupHolder = null
        try {
            popup.stopLoading()
            popup.loadUrl("about:blank")
            popup.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "cannot destroy popup webview", t)
        }
    }

    // ------------------------------------------------------------------- webview

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = desktopMode
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        if (desktopMode) settings.userAgentString = DESKTOP_UA

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                // Remember the name the page wanted for a download: blob URLs lose it.
                view.evaluateJavascript(DOWNLOAD_NAME_SHIM, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("OpenCodeWeb", "${message.messageLevel()} ${message.message()} @${message.sourceId()}:${message.lineNumber()}")
                return true
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    if (!openAttachmentChooser(fileChooserParams)) {
                        fileChooserCallback = null
                        filePathCallback.onReceiveValue(null)
                        false
                    } else {
                        true
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "cannot open file chooser", t)
                    fileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // Popups (OAuth / external links) go to the system browser. The popup WebView is a
                // transport token only: as soon as it tries to load anything we hand the URL to the
                // system and destroy it, so no hidden WebView is left running.
                dropPopup()
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = false
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        openExternally(request.url)
                        dropPopup()
                        return true
                    }

                    override fun onPageFinished(v: WebView, url: String?) {
                        if (url != null && !url.startsWith("about:")) dropPopup()
                    }
                }
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        dropPopup()
                    }
                }
                popupHolder = popup
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadToAppStorage(url, userAgent, contentDisposition, mimeType)
        }
    }

    // --------------------------------------------------------------- attachments

    /**
     * Downloads a file produced by the web UI (session export, generated asset) with the
     * WebView cookies attached — a plain external browser would be rejected by the server.
     * The file lands in the app's external files dir and is offered through the share sheet.
     */
    private fun downloadToAppStorage(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            saveInlineDownload(url, name, mimeType)
            return
        }
        lifecycleScope.launch {
            val target = try {
                withContext(Dispatchers.IO) {
                    val file = uniqueFile(downloadsDir(), name)
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        userAgent?.let { setRequestProperty("User-Agent", it) }
                        CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
                    }
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) error("HTTP $code")
                        conn.inputStream.use { input ->
                            file.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    file
                }
            } catch (t: Throwable) {
                Log.w(TAG, "download failed", t)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.download_failed, t.message ?: t.toString()),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            shareFile(target, mimeType)
        }
    }

    /**
     * Blob and data URLs only exist inside the page, so the bytes are read back through the
     * page itself and written here. This keeps exports produced by the web UI working
     * (`URL.createObjectURL` + `<a download>`), which is how the app saves sessions.
     */
    private fun saveInlineDownload(url: String, guessedName: String, mimeType: String?) {
        // evaluateJavascript returns the value of the expression itself and never waits for a
        // promise, so the async work parks its result on the window and we poll for it.
        val script = """
            (function () {
              window.__ocInlineResult = null;
              (async () => {
                try {
                  let info = null;
                  if (window.__ocPendingDownload) {
                    info = await window.__ocPendingDownload;
                    window.__ocPendingDownload = null;
                  }
                  if (!info) {
                    const r = await fetch(${JSONObject.quote(url)});
                    const b = await r.blob();
                    const data = await new Promise((ok, no) => {
                      const fr = new FileReader();
                      fr.onload = () => ok(fr.result);
                      fr.onerror = () => no(new Error('read failed'));
                      fr.readAsDataURL(b);
                    });
                    info = { name: window.__ocDownloadName || '', type: b.type || '', data };
                  }
                  window.__ocInlineResult = JSON.stringify(info);
                } catch (e) {
                  window.__ocInlineResult = 'ERR:' + (e && e.message ? e.message : e);
                }
              })();
              return true;
            })()
        """.trimIndent()

        webView.evaluateJavascript(script, null)
        awaitInlineResult(guessedName, mimeType, System.currentTimeMillis() + 30_000)
    }

    private fun awaitInlineResult(guessedName: String, mimeType: String?, deadline: Long) {
        webView.evaluateJavascript("window.__ocInlineResult") { raw ->
            val payload = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            if (payload == null) {
                if (System.currentTimeMillis() < deadline) {
                    webView.postDelayed({ awaitInlineResult(guessedName, mimeType, deadline) }, 100)
                } else {
                    failDownload("timeout reading page data")
                }
                return@evaluateJavascript
            }
            handleInlinePayload(payload, guessedName, mimeType)
        }
    }

    private fun failDownload(why: String) {
        Log.w(TAG, "inline download failed: $why")
        Toast.makeText(this, getString(R.string.download_failed, why), Toast.LENGTH_LONG).show()
    }

    private fun handleInlinePayload(payload: String, guessedName: String, mimeType: String?) {
        if (payload.startsWith("ERR:")) {
            failDownload(payload.removePrefix("ERR:"))
            return
        }
        val obj = runCatching { JSONObject(payload) }.getOrNull()
        val dataUrl = obj?.optString("data").orEmpty()
        val comma = dataUrl.indexOf(',')
        if (obj == null || comma < 0) {
            failDownload("no data")
            return
        }
        val header = dataUrl.substring(5, comma)
        val type = obj.optString("type").ifEmpty {
            header.substringBefore(';').ifEmpty { mimeType ?: "application/octet-stream" }
        }
        val name = renameForType(obj.optString("name").ifEmpty { guessedName }, type)
        lifecycleScope.launch {
            val target = try {
                withContext(Dispatchers.IO) {
                    val bytes = if (header.endsWith(";base64")) {
                        Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                    } else {
                        Uri.decode(dataUrl.substring(comma + 1)).toByteArray()
                    }
                    val file = uniqueFile(downloadsDir(), name)
                    file.outputStream().use { it.write(bytes) }
                    file
                }
            } catch (t: Throwable) {
                Log.w(TAG, "inline download failed", t)
                failDownload(t.message ?: t.toString())
                return@launch
            }
            shareFile(target, type)
        }
    }

    private fun downloadsDir(): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "downloads")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /** A blob URL carries no file name, so fall back to the MIME type for the extension. */
    private fun renameForType(name: String, type: String): String {
        if (name.isNotEmpty() && !name.equals("downloadfile.bin", ignoreCase = true)) return name
        val ext = when (type.substringBefore(';').trim().lowercase()) {
            "application/json" -> "json"
            "text/markdown" -> "md"
            "text/plain" -> "txt"
            "text/csv" -> "csv"
            "text/html" -> "html"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            else -> "bin"
        }
        return "opencode-${System.currentTimeMillis()}.$ext"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun shareFile(file: File, mimeType: String?) {
        val published = publishToDownloads(file, mimeType)
        Toast.makeText(
            this,
            if (published != null) {
                getString(R.string.download_saved, "Download/${file.name}")
            } else {
                getString(R.string.download_saved, file.absolutePath)
            },
            Toast.LENGTH_SHORT,
        ).show()
        try {
            val uri = published ?: FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.download_share)))
        } catch (t: Throwable) {
            Log.w(TAG, "cannot share download", t)
            Toast.makeText(this, getString(R.string.download_saved, file.absolutePath), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Copies the finished download into the shared Downloads collection so it is visible in the
     * Files app, and returns its content URI for the share sheet. Android 9 and older get a plain
     * copy into the public folder when the permission is granted; the app copy is always kept.
     */
    private fun publishToDownloads(file: File, mimeType: String?): Uri? {
        val type = mimeType ?: "application/octet-stream"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, type)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
                uri
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return null
                }
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.isDirectory) dir.mkdirs()
                val target = uniqueFile(dir, file.name)
                file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot publish download", t)
            null
        }
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return true
        val host = uri.host ?: ""
        val port = ServerState.state.value.port
        if ((scheme == "http" || scheme == "https") && (host == "127.0.0.1" || host == "localhost")) {
            val effective = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
            if (effective == port) return false
        }
        if (scheme == "http" || scheme == "https") openExternally(uri)
        return true
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.error_no_app, uri.toString()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun installCookies(port: Int, password: String) {
        val manager = CookieManager.getInstance()
        val token = OpenCodeAuth.forgeToken(password)
        for (host in listOf("127.0.0.1", "localhost")) {
            val url = "http://$host:$port/"
            manager.setCookie(url, "${OpenCodeAuth.cookieName(port)}=$token; Path=/; Max-Age=2592000")
            manager.setCookie(url, "opencode_session=$token; Path=/; Max-Age=2592000")
        }
        manager.flush()
    }

    // ------------------------------------------------------------------------ ui

    private fun wireUi() {
        findViewById<ImageButton>(R.id.btnMic).setOnClickListener { startVoiceInput() }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { webView.reload() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnRetry).setOnClickListener {
            errorBox.visibility = View.GONE
            ServerService.restart(this)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_RELOAD, 0, R.string.menu_reload)
        popup.menu.add(0, MENU_DESKTOP, 1, R.string.menu_desktop).apply {
            isCheckable = true
            isChecked = desktopMode
        }
        popup.menu.add(0, MENU_CONFIG, 2, R.string.menu_config)
        popup.menu.add(0, MENU_TERMINAL, 9, R.string.menu_terminal)
        popup.menu.add(0, MENU_INTEGRATIONS, 10, R.string.menu_integrations)
        popup.menu.add(0, MENU_LOCAL_AI, 11, LocalAiLocale.text(this, R.string.menu_local_ai))
        popup.menu.add(0, MENU_RESTART, 3, R.string.menu_restart)
        popup.menu.add(0, MENU_LOGS, 4, R.string.menu_logs)
        popup.menu.add(0, MENU_SHARE_LOG, 5, R.string.menu_share_log)
        popup.menu.add(0, MENU_COPY_URL, 6, R.string.menu_copy_url)
        popup.menu.add(0, MENU_STOP, 7, R.string.menu_stop)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RELOAD -> webView.reload()
                MENU_DESKTOP -> toggleDesktopMode(!desktopMode)
                MENU_CONFIG -> startActivity(Intent(this, ConfigEditorActivity::class.java))
                MENU_TERMINAL -> startActivity(Intent(this, TerminalActivity::class.java))
                MENU_INTEGRATIONS -> startActivity(Intent(this, IntegrationsActivity::class.java))
                MENU_LOCAL_AI -> startActivity(Intent(this, LocalAiActivity::class.java))
                MENU_RESTART -> {
                    loadedPort = 0
                    ServerService.restart(this)
                }
                MENU_LOGS -> showLogs()
                MENU_SHARE_LOG -> shareFile(AppEnv.logFile(this), "text/plain")
                MENU_COPY_URL -> {
                    val url = ServerState.state.value.url
                    if (url.isNotEmpty()) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("opencode", url))
                        Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
                    }
                }
                MENU_STOP -> ServerService.stop(this)
            }
            true
        }
        popup.show()
    }

    private fun toggleDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        prefs.edit().putBoolean("desktop_ua", enabled).apply()
        val settings = webView.settings
        settings.userAgentString = if (enabled) DESKTOP_UA else null
        settings.loadWithOverviewMode = enabled
        webView.reload()
    }

    private fun showLogs() {
        val text = ServerState.log().ifBlank { getString(R.string.log_empty) }
        val view = EditText(this).apply {
            setText(text)
            isFocusable = false
            isFocusableInTouchMode = false
            setTextIsSelectable(true)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_logs)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun render(snapshot: ServerState.Snapshot) {
        when (snapshot.phase) {
            ServerState.Phase.IDLE, ServerState.Phase.STARTING -> {
                loadedPort = 0
                status.text = getString(R.string.status_starting)
                progress.visibility = View.VISIBLE
                errorBox.visibility = View.GONE
            }

            ServerState.Phase.RUNNING -> {
                status.text = getString(R.string.status_running, snapshot.port)
                errorBox.visibility = View.GONE
                if (loadedPort != snapshot.port) {
                    loadedPort = snapshot.port
                    installCookies(snapshot.port, snapshot.password)
                    webView.loadUrl(snapshot.url)
                }
            }

            ServerState.Phase.ERROR -> {
                loadedPort = 0
                status.text = getString(R.string.status_error)
                progress.visibility = View.GONE
                errorBox.visibility = View.VISIBLE
                errorText.text = getString(R.string.error_server_output, snapshot.message, ServerState.log())
            }

            ServerState.Phase.STOPPED -> {
                loadedPort = 0
                status.text = getString(R.string.status_stopped)
                progress.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------------ voice

    /**
     * Voice input inserts the recognised phrase into whatever the web UI has focused (falling
     * back to the chat composer). The text is pushed through `execCommand('insertText')` so the
     * SolidJS store sees a real `input` event instead of a silently overwritten `value`.
     */
    private fun startVoiceInput() {
        if (listening) {
            releaseRecognizer()
            setMicActive(false)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginListening()
    }

    private fun beginListening() {
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(this) }.getOrDefault(false)
        if (!available) {
            launchSystemRecognizer()
            return
        }
        val recognizer = try {
            speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { created ->
                created.setRecognitionListener(voiceListener)
                speechRecognizer = created
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot create recogniser", t)
            launchSystemRecognizer()
            return
        }
        listening = true
        setMicActive(true)
        Toast.makeText(this, R.string.voice_listening, Toast.LENGTH_SHORT).show()
        try {
            recognizer.startListening(voiceIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
            listening = false
            setMicActive(false)
            launchSystemRecognizer()
        }
    }

    private fun voiceIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_listening))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private fun launchSystemRecognizer() {
        try {
            voiceDialog.launch(voiceIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "no speech recogniser", t)
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun releaseRecognizer() {
        listening = false
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        try {
            recognizer.cancel()
            recognizer.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "cannot release recogniser", t)
        }
    }

    private fun setMicActive(active: Boolean) {
        findViewById<ImageButton>(R.id.btnMic).imageTintList =
            ColorStateList.valueOf(getColor(if (active) R.color.oc_accent else R.color.oc_icon))
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            listening = false
            setMicActive(false)
            Log.w(TAG, "voice error $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    Toast.makeText(this@MainActivity, R.string.voice_no_speech, Toast.LENGTH_SHORT).show()

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    Toast.makeText(this@MainActivity, R.string.voice_permission, Toast.LENGTH_LONG).show()

                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // The service API is unhappy (common on vendor ROMs): use the system dialog.
                    releaseRecognizer()
                    launchSystemRecognizer()
                }

                else -> Toast.makeText(
                    this@MainActivity,
                    getString(R.string.voice_error, errorName(error)),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            setMicActive(false)
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            deliverVoiceText(words?.firstOrNull().orEmpty())
        }
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_SERVER -> "server"
        else -> "code $code"
    }

    private fun deliverVoiceText(text: String) {
        val phrase = text.trim()
        if (phrase.isEmpty()) {
            Toast.makeText(this, R.string.voice_no_speech, Toast.LENGTH_SHORT).show()
            return
        }
        webView.evaluateJavascript(voiceInsertScript(phrase)) { raw ->
            val result = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            if (result != "OK") {
                Toast.makeText(this, R.string.voice_no_field, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    companion object {
        private const val TAG = "OpenCodeMobile"
        private const val MENU_RELOAD = 1
        private const val MENU_DESKTOP = 2
        private const val MENU_CONFIG = 3
        private const val MENU_RESTART = 4
        private const val MENU_LOGS = 5
        private const val MENU_SHARE_LOG = 8
        private const val MENU_COPY_URL = 6
        private const val MENU_STOP = 7
        private const val MENU_TERMINAL = 9
        private const val MENU_INTEGRATIONS = 10
        private const val MENU_LOCAL_AI = 11

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * Blob downloads are created with a hidden `<a download="name">` click, and the WebView
         * drops that name. Worse, the page revokes the object URL right after the click, so the
         * bytes must be captured while the click is still being dispatched — hence this shim.
         */
        private val DOWNLOAD_NAME_SHIM = """
            (function () {
              if (window.__ocDownloadNameHooked) return;
              window.__ocDownloadNameHooked = true;
              document.addEventListener('click', function (ev) {
                var node = ev.target;
                while (node && node.tagName !== 'A') node = node.parentNode;
                if (!node || !node.hasAttribute || !node.hasAttribute('download')) return;
                window.__ocDownloadName = node.getAttribute('download') || '';
                var href = node.href || '';
                if (href.indexOf('blob:') !== 0) return;
                var pending = fetch(href)
                  .then(function (r) { return r.blob(); })
                  .then(function (b) {
                    return new Promise(function (ok, no) {
                      var fr = new FileReader();
                      fr.onload = function () {
                        ok({ name: window.__ocDownloadName || '', type: b.type || '', data: fr.result });
                      };
                      fr.onerror = function () { no(new Error('read failed')); };
                      fr.readAsDataURL(b);
                    });
                  });
                pending.catch(function () {});
                window.__ocPendingDownload = pending;
              }, true);
            })();
        """.trimIndent()

        /**
         * Inserts a phrase into the focused field of the web UI. `document.activeElement` is
         * preferred; otherwise the visible editable element closest to the bottom of the page is
         * used, which is the chat composer in the opencode UI. `execCommand('insertText')` is used
         * first because it emits the `beforeinput`/`input` pair the SolidJS store listens for.
         */
        private fun voiceInsertScript(text: String): String = """
            (function (text) {
              function visible(el) {
                if (!el) return false;
                var r = el.getBoundingClientRect();
                if (r.width <= 0 || r.height <= 0) return false;
                var s = window.getComputedStyle(el);
                return s.visibility !== 'hidden' && s.display !== 'none';
              }
              function editable(el) {
                if (!el || !el.tagName) return false;
                var tag = el.tagName;
                if (tag === 'TEXTAREA') return !el.readOnly && !el.disabled;
                if (tag === 'INPUT') {
                  var type = (el.type || 'text').toLowerCase();
                  var ok = type === 'text' || type === 'search' || type === 'url' ||
                           type === 'email' || type === 'tel';
                  return ok && !el.readOnly && !el.disabled;
                }
                return el.isContentEditable === true;
              }
              var target = editable(document.activeElement) ? document.activeElement : null;
              if (!target) {
                var list = Array.prototype.slice.call(
                  document.querySelectorAll('textarea, [contenteditable="true"], input')
                ).filter(function (el) { return editable(el) && visible(el); });
                list.sort(function (a, b) {
                  return b.getBoundingClientRect().top - a.getBoundingClientRect().top;
                });
                target = list[0] || null;
              }
              if (!target) return 'NOFIELD';
              try { target.focus(); } catch (e) {}
              if (target.isContentEditable) {
                var sel = window.getSelection();
                var range = document.createRange();
                range.selectNodeContents(target);
                range.collapse(false);
                sel.removeAllRanges();
                sel.addRange(range);
              } else {
                var len = (target.value || '').length;
                try { target.setSelectionRange(len, len); } catch (e) {}
              }
              var inserted = false;
              try { inserted = document.execCommand('insertText', false, text); } catch (e) { inserted = false; }
              if (!inserted) {
                if (target.isContentEditable) {
                  target.textContent = (target.textContent || '') + text;
                } else {
                  target.value = (target.value || '') + text;
                }
                target.dispatchEvent(new Event('input', { bubbles: true }));
              }
              return 'OK';
            })(${JSONObject.quote(text)})
        """.trimIndent()
    }
}
