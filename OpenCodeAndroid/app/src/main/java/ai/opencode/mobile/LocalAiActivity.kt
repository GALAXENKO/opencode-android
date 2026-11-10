package ai.opencode.mobile

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
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
        btnStart.setOnClickListener { startEngine() }
        btnStop.setOnClickListener { stopEngine() }
        findViewById<Button>(R.id.btnParams).setOnClickListener { paramsDialog() }

        AppEnv.ensureDirs(this)
        LocalAiRuntime.recent().forEach { appendLog(it) }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        // Engine output goes straight into this screen's log while it is visible.
        LocalAiRuntime.setLogger { line -> appendLog(line) }
        refresh()
    }

    override fun onStop() {
        LocalAiRuntime.setLogger(null)
        super.onStop()
    }

    // -------------------------------------------------------------------- state

    private fun refresh() {
        val id = LocalAiStore.modelId(this)
        val file = LocalAiStore.modelFile(this)
        val entry = LocalAiRuntime.CATALOG.firstOrNull { it.id == id }

        modelStatus.text = when {
            id.isEmpty() -> getString(R.string.local_ai_no_model)
            entry != null && file != null ->
                getString(R.string.local_ai_model_selected, entry.label, LocalAiRuntime.humanSize(file.length()))
            entry != null -> getString(R.string.local_ai_model_missing, entry.label)
            file != null -> getString(R.string.local_ai_model_custom, file.name)
            else -> getString(R.string.local_ai_no_model)
        }

        val port = LocalAiRuntime.activePort()
        engineStatus.text = if (port > 0) {
            getString(R.string.local_ai_engine_running, port)
        } else {
            getString(R.string.local_ai_engine_stopped)
        }

        paramsStatus.text = getString(
            R.string.local_ai_params_summary,
            LocalAiStore.contextSize(this),
            LocalAiRuntime.threadsFor(this),
        )

        btnDownload.isEnabled = !busy && entry != null && file == null
        btnStart.isEnabled = !busy && port == 0 && file != null && LocalAiRuntime.engine(this) != null
        btnStop.isEnabled = !busy && (port > 0)
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
        if (busy) {
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

    // ------------------------------------------------------------------- engine

    private fun startEngine() {
        if (busy) {
            toast(R.string.local_ai_busy)
            return
        }
        setBusy(true)
        Thread({
            val message = try {
                LocalAiRuntime.start(this)
            } catch (t: Throwable) {
                getString(R.string.integrations_error, describe(t))
            }
            runOnUiThread {
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
            setTextColor(0xFF8A8A8A.toInt())
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
            setTextColor(0xFFE8E8E8.toInt())
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
                setTextColor(0xFFE8E8E8.toInt())
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
