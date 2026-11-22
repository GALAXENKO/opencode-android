package ai.opencode.mobile

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Manages the built-in local AI: pick a GGUF model, download it, start/stop the bundled
 * llama.cpp engine and tune its parameters.
 *
 * There is no chat screen here on purpose — opencode already has one. Starting the engine
 * registers it as a provider (see [LocalAiRuntime]), so the model simply appears in
 * opencode's own model picker.
 *
 * The language is **not** chosen here: [LocalAiLocale] reads the locale opencode already
 * stores in its `oc_locale` cookie, so this screen follows the language picked inside
 * opencode's settings.
 */
class LocalAiActivity : AppCompatActivity() {

    private lateinit var modelStatus: TextView
    private lateinit var engineStatus: TextView
    private lateinit var paramsStatus: TextView
    private lateinit var logView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    /** True while a download or an engine transition runs, so buttons cannot re-enter. */
    @Volatile
    private var busy = false

    /** True while [LocalAiRuntime.start] is still loading the weights. */
    @Volatile
    private var starting = false

    private val handler = Handler(Looper.getMainLooper())

    /** Whether the progress bar is currently on screen (driven by [downloadTicker]). */
    private var downloadUiShown = false

    /**
     * Mirrors [LocalAiRuntime.activeDownload] into the progress bar, so a download started
     * before a rotation or before leaving this screen is still visible when it continues.
     */
    private val downloadTicker = object : Runnable {
        override fun run() {
            syncDownloadUi()
            // The engine can die on its own (OOM killer), so keep its status honest too.
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Follow the language opencode itself uses, instead of a separate app-wide setting.
        super.attachBaseContext(LocalAiLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_ai)

        modelStatus = findViewById(R.id.modelStatus)
        engineStatus = findViewById(R.id.engineStatus)
        paramsStatus = findViewById(R.id.paramsStatus)
        logView = findViewById(R.id.localAiLog)
        progress = findViewById(R.id.downloadProgress)
        progressText = findViewById(R.id.downloadText)
        btnDownload = findViewById(R.id.btnDownload)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        findViewById<Button>(R.id.btnPickModel).setOnClickListener { pickModel() }
        btnDownload.setOnClickListener { downloadSelected() }
        findViewById<Button>(R.id.btnUseFile).setOnClickListener { chooseFile() }
        findViewById<Button>(R.id.btnDeleteModel).setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.btnHfSearch).setOnClickListener { hfSearch() }
        btnStart.setOnClickListener { startEngine() }
        btnStop.setOnClickListener { stopEngine() }
        findViewById<Button>(R.id.btnParams).setOnClickListener { paramsDialog() }
        findViewById<CheckBox>(R.id.chkTools).apply {
            isChecked = !LocalAiStore.tools(this@LocalAiActivity)
            setOnCheckedChangeListener { _, checked ->
                val tools = !checked
                if (tools == LocalAiStore.tools(this@LocalAiActivity)) return@setOnCheckedChangeListener
                LocalAiStore.setTools(this@LocalAiActivity, tools)
                // The plugin entry in opencode.jsonc drives the prompt size, so a running
                // engine needs the server restarted before the next message uses it.
                Thread({
                    LocalAiRuntime.applyTools(this@LocalAiActivity)
                }, "local-ai-tools").start()
                toast(R.string.local_ai_tools_saved)
            }
        }

        AppEnv.ensureDirs(this)
        LocalAiRuntime.recent().forEach { appendLog(it) }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        // Engine output goes straight into this screen's log while it is visible.
        LocalAiRuntime.setLogger { line -> appendLog(line) }
        // Picks up an engine that outlived a previous app run; runs off the UI thread and
        // reports back through the ticker below, so opening this screen never blocks.
        LocalAiRuntime.adoptAsync(this)
        refresh()
        handler.post(downloadTicker)
    }

    override fun onStop() {
        handler.removeCallbacks(downloadTicker)
        LocalAiRuntime.setLogger(null)
        super.onStop()
    }

    // -------------------------------------------------------------------- state

    private fun refresh() {
        val id = LocalAiStore.modelId(this)
        val file = LocalAiStore.modelFile(this)
        val entry = LocalAiRuntime.CATALOG.firstOrNull { it.id == id }

        setText(
            modelStatus,
            when {
                id.isEmpty() -> getString(R.string.local_ai_no_model)
                entry != null && file != null ->
                    getString(R.string.local_ai_model_selected, entry.label, LocalAiRuntime.humanSize(file.length()))
                entry != null -> getString(R.string.local_ai_model_missing, entry.label)
                file != null -> getString(R.string.local_ai_model_custom, file.name)
                else -> getString(R.string.local_ai_no_model)
            },
        )

        val port = LocalAiRuntime.runningPort()
        setText(
            engineStatus,
            when {
                port > 0 -> getString(R.string.local_ai_engine_running, port)
                starting -> getString(R.string.local_ai_engine_starting)
                else -> getString(R.string.local_ai_engine_stopped)
            },
        )

        setText(
            paramsStatus,
            getString(
                R.string.local_ai_params_summary,
                LocalAiStore.contextSize(this),
                LocalAiRuntime.threadsFor(this),
            ),
        )

        val downloading = LocalAiRuntime.activeDownload() != null
        setEnabled(btnDownload, !busy && !downloading && entry != null && file == null)
        setEnabled(
            btnStart,
            !busy && !downloading && port == 0 && file != null && LocalAiRuntime.engine(this) != null,
        )
        setEnabled(btnStop, !busy && !downloading && port > 0)
    }

    /**
     * Sets a label only when the value really changed.
     *
     * [downloadTicker] calls [refresh] twice a second; an unconditional `setText` would request a
     * new layout each time and keep the whole window permanently "not idle", which breaks
     * accessibility services and screen readers as well as uiautomator.
     */
    private fun setText(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private fun setEnabled(view: View, value: Boolean) {
        if (view.isEnabled != value) view.isEnabled = value
    }

    /** Draws the progress of a running download, whoever started it. */
    private fun syncDownloadUi() {
        val state = LocalAiRuntime.activeDownload()
        if (state == null) {
            if (downloadUiShown) {
                downloadUiShown = false
                progress.visibility = View.GONE
                progressText.visibility = View.GONE
                refresh()
            }
            return
        }
        if (!downloadUiShown) {
            downloadUiShown = true
            progress.visibility = View.VISIBLE
            refresh()
        }
        val percent = if (state.total > 0) ((state.copied * 100) / state.total).toInt() else 0
        progress.progress = percent.coerceIn(0, 100)
        progressText.text = getString(
            R.string.local_ai_download_running,
            state.label,
            "${LocalAiRuntime.humanSize(state.copied)} / ${LocalAiRuntime.humanSize(state.total)}",
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        refresh()
    }

    private fun selectedEntry(): LocalModel? {
        val id = LocalAiStore.modelId(this)
        return LocalAiRuntime.CATALOG.firstOrNull { it.id == id }
    }

    // ------------------------------------------------------------------- models

    private fun pickModel() {
        val current = LocalAiStore.modelId(this)
        val labels = LocalAiRuntime.CATALOG.map { model ->
            val size = LocalAiRuntime.humanSize(model.size)
            val line = getString(R.string.local_ai_pick_item, model.label, size)
            val hint = getString(model.hintRes)
            val mark = if (File(AppEnv.modelsDir(this), model.fileName).isFile) " ✓" else ""
            "$line$mark\n$hint"
        }.toTypedArray()
        val checked = LocalAiRuntime.CATALOG.indexOfFirst { it.id == current }

        AlertDialog.Builder(this)
            .setTitle(R.string.local_ai_pick_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                LocalAiStore.setModelId(this, LocalAiRuntime.CATALOG[which].id)
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadSelected() {
        if (busy || LocalAiRuntime.activeDownload() != null) {
            toast(R.string.local_ai_busy)
            return
        }
        val entry = selectedEntry()
        if (entry == null) {
            toast(R.string.local_ai_pick_first)
            return
        }
        val target = File(AppEnv.modelsDir(this), entry.fileName)
        if (target.isFile && target.length() == entry.size) {
            toast(R.string.local_ai_already_downloaded)
            return
        }

        setBusy(true)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        progressText.visibility = View.VISIBLE
        progressText.text = getString(R.string.local_ai_download_running, entry.label, "")

        Thread({
            try {
                val file = LocalAiRuntime.download(this, entry) { copied, total ->
                    val percent = if (total > 0) ((copied * 100) / total).toInt() else 0
                    val done = LocalAiRuntime.humanSize(copied)
                    val all = LocalAiRuntime.humanSize(total)
                    runOnUiThread {
                        progress.progress = percent.coerceIn(0, 100)
                        progressText.text = getString(R.string.local_ai_download_running, entry.label, "$done / $all")
                    }
                }
                runOnUiThread {
                    toast(getString(R.string.local_ai_download_ok, file.name))
                    refresh()
                }
            } catch (t: Throwable) {
                val message = describe(t)
                runOnUiThread {
                    appendLog(getString(R.string.integrations_error, message))
                    toast(message)
                }
            } finally {
                runOnUiThread {
                    progress.visibility = View.GONE
                    progressText.visibility = View.GONE
                    setBusy(false)
                }
            }
        }, "local-ai-download").start()
    }

    /** Points the engine at a GGUF the user already has (e.g. copied over USB). */
    private fun chooseFile() {
        formDialog(
            R.string.local_ai_file_title,
            listOf(R.string.local_ai_file_hint),
            R.string.local_ai_file_ok,
        ) { values ->
            val path = values.firstOrNull().orEmpty()
            if (path.isEmpty()) return@formDialog
            val file = resolveHome(path)
            if (!file.isFile) {
                toast(getString(R.string.local_ai_file_missing, file.absolutePath))
                return@formDialog
            }
            LocalAiStore.setModelId(this, LocalAiStore.CUSTOM_PREFIX + file.absolutePath)
            appendLog(getString(R.string.local_ai_model_custom, file.name))
            refresh()
        }
    }

    private fun confirmDelete() {
        val file = LocalAiStore.modelFile(this)
        if (file == null) {
            toast(R.string.local_ai_no_model)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.local_ai_delete_title)
            .setMessage(getString(R.string.local_ai_delete_text, file.absolutePath))
            .setPositiveButton(R.string.local_ai_delete_yes) { _, _ ->
                val custom = LocalAiStore.modelId(this).startsWith(LocalAiStore.CUSTOM_PREFIX)
                Thread {
                    val gone = LocalAiRuntime.deleteSelected(this)
                    runOnUiThread {
                        if (gone && custom) LocalAiStore.clearModel(this)
                        toast(if (gone) R.string.local_ai_deleted_toast else R.string.local_ai_delete_failed)
                        refresh()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------- hugging face

    /**
     * Search huggingface.co and download any GGUF, not just the built-in catalogue.
     * Flow: query → repository → quantisation → `~/models` → selected as a custom model.
     */
    private fun hfSearch() {
        if (busy) {
            toast(R.string.local_ai_busy)
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val query = EditText(this).apply {
            setHint(R.string.local_ai_hf_query)
            setTextColor(getColor(R.color.oc_text))
            setHintTextColor(getColor(R.color.oc_text_dim))
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            setSingleLine(true)
        }
        container.addView(query)
        container.addView(TextView(this).apply {
            text = getString(R.string.local_ai_hf_help)
            setTextColor(getColor(R.color.oc_text_dim))
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.local_ai_hf_title)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.local_ai_hf_go, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = query.text.toString().trim()
                if (text.isEmpty()) {
                    query.error = getString(R.string.local_ai_hf_query)
                    return@setOnClickListener
                }
                dialog.dismiss()
                searchHf(text)
            }
        }
        dialog.show()
    }

    private fun searchHf(query: String) {
        toast(getString(R.string.local_ai_hf_searching, query))
        Thread({
            try {
                val repos = LocalAiRuntime.searchHf(query)
                runOnUiThread {
                    if (repos.isEmpty()) {
                        toast(R.string.local_ai_hf_empty)
                        return@runOnUiThread
                    }
                    val labels = repos.map { repo ->
                        repo.id + "\n" + getString(
                            R.string.local_ai_hf_downloads,
                            compact(repo.downloads),
                        )
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.local_ai_hf_results)
                        .setItems(labels) { _, which -> openRepo(repos[which]) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (t: Throwable) {
                val message = describe(t)
                runOnUiThread { toast(message) }
            }
        }, "hf-search").start()
    }

    private fun openRepo(repo: LocalAiRuntime.HfRepo) {
        Thread({
            try {
                val files = LocalAiRuntime.hfFiles(repo.id)
                runOnUiThread {
                    if (files.isEmpty()) {
                        toast(R.string.local_ai_hf_no_gguf)
                        return@runOnUiThread
                    }
                    val labels = files.map { file ->
                        val size = LocalAiRuntime.humanSize(file.size)
                        if (size.isEmpty()) file.name else "${file.name}\n$size"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(repo.name)
                        .setItems(labels) { _, which -> downloadHf(files[which]) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (t: Throwable) {
                val message = describe(t)
                runOnUiThread { toast(message) }
            }
        }, "hf-files").start()
    }

    private fun downloadHf(file: LocalAiRuntime.HfFile) {
        if (busy || LocalAiRuntime.activeDownload() != null) {
            toast(R.string.local_ai_busy)
            return
        }
        setBusy(true)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        progressText.visibility = View.VISIBLE
        progressText.text = getString(R.string.local_ai_download_running, file.name, "")

        Thread({
            try {
                val saved = LocalAiRuntime.downloadFile(
                    this, file.url, file.name, file.size, file.name,
                ) { copied, total ->
                    val percent = if (total > 0) ((copied * 100) / total).toInt() else 0
                    val done = LocalAiRuntime.humanSize(copied)
                    val all = LocalAiRuntime.humanSize(total)
                    runOnUiThread {
                        progress.progress = percent.coerceIn(0, 100)
                        progressText.text =
                            getString(R.string.local_ai_download_running, file.name, "$done / $all")
                    }
                }
                LocalAiStore.setModelId(this, LocalAiStore.CUSTOM_PREFIX + saved.absolutePath)
                runOnUiThread {
                    toast(getString(R.string.local_ai_download_ok, saved.name))
                    refresh()
                }
            } catch (t: Throwable) {
                val message = describe(t)
                runOnUiThread {
                    appendLog(getString(R.string.integrations_error, message))
                    toast(message)
                }
            } finally {
                runOnUiThread {
                    progress.visibility = View.GONE
                    progressText.visibility = View.GONE
                    setBusy(false)
                }
            }
        }, "hf-download").start()
    }

    /** `1.2M` style download counter for the repository list. */
    private fun compact(value: Long): String {
        if (value < 1000) return value.toString()
        val units = arrayOf("K", "M", "B")
        var scaled = value.toDouble()
        var unit = -1
        while (scaled >= 1000.0 && unit < units.lastIndex) {
            scaled /= 1000.0
            unit++
        }
        return String.format(java.util.Locale.US, "%.1f%s", scaled, units[unit])
    }

    // ------------------------------------------------------------------- engine

    private fun startEngine() {
        if (busy) {
            toast(R.string.local_ai_busy)
            return
        }
        setBusy(true)
        starting = true
        Thread({
            val message = try {
                LocalAiRuntime.start(this)
            } catch (t: Throwable) {
                getString(R.string.integrations_error, describe(t))
            }
            runOnUiThread {
                starting = false
                appendLog(message)
                toast(message)
                setBusy(false)
            }
        }, "local-ai-start").start()
    }

    private fun stopEngine() {
        if (busy) {
            toast(R.string.local_ai_busy)
            return
        }
        setBusy(true)
        Thread({
            val message = try {
                LocalAiRuntime.stop(this)
            } catch (t: Throwable) {
                getString(R.string.integrations_error, describe(t))
            }
            runOnUiThread {
                appendLog(message)
                toast(message)
                setBusy(false)
            }
        }, "local-ai-stop").start()
    }

    private fun paramsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val contextField = numberField(
            getString(R.string.local_ai_params_context),
            LocalAiStore.contextSize(this).toString(),
        )
        val threadsField = numberField(
            getString(R.string.local_ai_params_threads),
            LocalAiStore.threads(this).toString(),
        )
        container.addView(contextField)
        container.addView(threadsField)
        container.addView(TextView(this).apply {
            text = getString(R.string.local_ai_params_help)
            setTextColor(getColor(R.color.oc_text_dim))
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.local_ai_params_title)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val context = contextField.text.toString().trim().toIntOrNull()
                val threads = threadsField.text.toString().trim().toIntOrNull()
                if (context == null || threads == null) {
                    toast(R.string.local_ai_params_bad)
                    return@setPositiveButton
                }
                LocalAiStore.setContextSize(this, context)
                LocalAiStore.setThreads(this, threads)
                toast(R.string.local_ai_params_saved)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun numberField(hint: String, value: String): EditText =
        EditText(this).apply {
            setHint(hint)
            setText(value)
            setTextColor(getColor(R.color.oc_text))
            textSize = 13f
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_NEXT
            maxLines = 1
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    // -------------------------------------------------------------------- log

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

    // ---------------------------------------------------------------- helpers

    /** Vertical form with one single-line field per hint; [onOk] gets the trimmed values. */
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
        val inputs = hints.map { hint ->
            EditText(this).apply {
                setHint(hint)
                setTextColor(getColor(R.color.oc_text))
                textSize = 13f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_DONE
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

    /** Absolute stays absolute, everything else is resolved against `$HOME`. */
    private fun resolveHome(path: String): File {
        val trimmed = path.trim()
        val home = AppEnv.home(this)
        return when {
            trimmed == "~" -> home
            trimmed.startsWith("~/") -> File(home, trimmed.removePrefix("~/"))
            else -> File(trimmed).let { if (it.isAbsolute) it else File(home, trimmed) }
        }
    }

    private fun describe(t: Throwable): String {
        val message = t.message
        return if (message.isNullOrBlank()) t.javaClass.simpleName else message
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Plain TextView log: keep the tail short enough to stay responsive. */
        const val MAX_LOG_LINES = 200
    }
}
