package ai.opencode.mobile

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * Reads and writes `$XDG_CONFIG_HOME/opencode/opencode.jsonc` — the very same file the
 * desktop CLI uses, so anything configured here behaves exactly like on the desktop.
 *
 * Two editing modes:
 *  - raw JSONC editor (comments and trailing commas are allowed and validated locally),
 *  - "Add provider" dialog which writes a `providers.<id>` entry for you.
 */
class ConfigEditorActivity : AppCompatActivity() {

    private lateinit var editor: EditText
    private lateinit var problems: TextView
    private lateinit var problemsBox: ScrollView
    private lateinit var title: TextView

    private val configFile: File by lazy { AppEnv.configFile(this) }
    private var pristine: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        editor = findViewById(R.id.configText)
        problems = findViewById(R.id.configProblems)
        problemsBox = findViewById(R.id.configProblemsBox)
        title = findViewById(R.id.configTitle)

        findViewById<Button>(R.id.btnConfigValidate).setOnClickListener { validate(announce = true) }
        findViewById<Button>(R.id.btnConfigAddProvider).setOnClickListener { showAddProviderDialog() }
        findViewById<Button>(R.id.btnConfigSave).setOnClickListener { save() }
        findViewById<ImageButton>(R.id.btnConfigReload).setOnClickListener { confirmReload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDirty()) confirmDiscard() else finish()
            }
        })

        AppEnv.ensureDirs(this)
        AppEnv.ensureConfig(this)
        loadFromDisk()
    }

    // --------------------------------------------------------------------- disk

    private fun loadFromDisk() {
        val text = try {
            configFile.readText()
        } catch (t: Throwable) {
            "// could not read ${configFile.absolutePath}: ${t.message}\n"
        }
        pristine = text
        editor.setText(text)
        title.text = configFile.name
        hideProblems()
    }

    private fun isDirty(): Boolean = editor.text.toString() != pristine

    private fun confirmReload() {
        if (!isDirty()) {
            loadFromDisk()
            Toast.makeText(this, R.string.config_reload, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.config_discard_title)
            .setMessage(R.string.config_reload_confirm)
            .setPositiveButton(R.string.config_reload) { _, _ -> loadFromDisk() }
            .setNegativeButton(R.string.config_cancel, null)
            .show()
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.config_discard_title)
            .setMessage(R.string.config_discard_text)
            .setPositiveButton(R.string.config_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.config_keep, null)
            .show()
    }

    // ------------------------------------------------------------------- saving

    private fun save() {
        val text = editor.text.toString()
        val found = Jsonc.validate(text)
        if (found.isEmpty()) {
            writeAndRestart(text)
            return
        }
        showProblems(found)
        AlertDialog.Builder(this)
            .setTitle(R.string.config_invalid)
            .setMessage(found.joinToString("\n") { "• $it" })
            .setPositiveButton(R.string.config_save_anyway) { _, _ -> writeAndRestart(text) }
            .setNegativeButton(R.string.config_cancel, null)
            .show()
    }

    private fun writeAndRestart(text: String) {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(if (text.endsWith("\n")) text else "$text\n")
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.config_write_failed, t.message), Toast.LENGTH_LONG)
                .show()
            return
        }
        pristine = editor.text.toString()
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        ServerService.restart(this)
        finish()
    }

    // --------------------------------------------------------------- validation

    /** @return true when the config is clean. */
    private fun validate(announce: Boolean): Boolean {
        val found = Jsonc.validate(editor.text.toString())
        if (found.isEmpty()) {
            hideProblems()
            if (announce) Toast.makeText(this, R.string.config_ok, Toast.LENGTH_SHORT).show()
            return true
        }
        showProblems(found)
        return false
    }

    private fun showProblems(list: List<String>) {
        problems.text = list.joinToString("\n") { "• $it" }
        problemsBox.visibility = View.VISIBLE
    }

    private fun hideProblems() {
        problems.text = ""
        problemsBox.visibility = View.GONE
    }

    // ------------------------------------------------------------ add provider

    private fun showAddProviderDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val hint = TextView(this).apply {
            setText(R.string.provider_help)
            setTextColor(0xFF8A8A8A.toInt())
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        }
        val idInput = field(R.string.provider_id)
        val nameInput = field(R.string.provider_name)
        val packageInput = field(R.string.provider_package).apply {
            setText(DEFAULT_PROVIDER_PACKAGE)
            setSelection(text.length)
        }
        val urlInput = field(R.string.provider_base_url).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val keyInput = field(R.string.provider_api_key).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelIdInput = field(R.string.provider_model_id)
        val modelNameInput = field(R.string.provider_model_name)

        container.addView(hint)
        for (view in listOf(idInput, nameInput, packageInput, urlInput, keyInput, modelIdInput, modelNameInput)) {
            container.addView(view)
        }

        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.provider_title)
            .setView(scroll)
            .setPositiveButton(R.string.provider_add, null)
            .setNegativeButton(R.string.config_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = idInput.text.toString().trim()
                val baseUrl = urlInput.text.toString().trim()
                val modelId = modelIdInput.text.toString().trim()
                when {
                    id.isEmpty() -> {
                        idInput.error = getString(R.string.provider_error_id)
                        idInput.requestFocus()
                    }
                    baseUrl.isEmpty() -> {
                        urlInput.error = getString(R.string.provider_error_url)
                        urlInput.requestFocus()
                    }
                    modelId.isEmpty() -> {
                        modelIdInput.error = getString(R.string.provider_error_model)
                        modelIdInput.requestFocus()
                    }
                    else -> {
                        val merged = applyProvider(
                            id = id,
                            name = nameInput.text.toString().trim(),
                            pkg = packageInput.text.toString().trim()
                                .ifEmpty { DEFAULT_PROVIDER_PACKAGE },
                            baseUrl = baseUrl,
                            apiKey = keyInput.text.toString().trim(),
                            modelId = modelId,
                            modelName = modelNameInput.text.toString().trim(),
                        )
                        if (merged == null) {
                            Toast.makeText(
                                this@ConfigEditorActivity,
                                R.string.config_invalid,
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            editor.setText(merged)
                            validate(announce = false)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Inserts/merges a provider into the current editor text.
     * Returns the new text, or null when the current text cannot be parsed at all.
     */
    private fun applyProvider(
        id: String,
        name: String,
        pkg: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        modelName: String,
    ): String? {
        val current = editor.text.toString()
        val root = if (current.isBlank()) {
            JSONObject()
        } else {
            try {
                Jsonc.parse(current)
            } catch (t: Throwable) {
                return null
            }
        }

        val providers = root.optJSONObject("providers") ?: JSONObject().also { root.put("providers", it) }
        val provider = providers.optJSONObject(id) ?: JSONObject()

        if (name.isNotEmpty()) provider.put("name", name)
        provider.put("package", pkg)

        val settings = provider.optJSONObject("settings") ?: JSONObject()
        settings.put("baseURL", baseUrl)
        if (apiKey.isNotEmpty()) settings.put("apiKey", apiKey)
        provider.put("settings", settings)

        val models = provider.optJSONObject("models") ?: JSONObject()
        val model = models.optJSONObject(modelId) ?: JSONObject()
        if (modelName.isNotEmpty()) model.put("name", modelName)
        if (!model.has("capabilities")) {
            // Same defaults the desktop provider dialog writes: text in/out with tool use.
            model.put("capabilities", JSONObject().apply {
                put("tools", true)
                put("input", org.json.JSONArray().put("text"))
                put("output", org.json.JSONArray().put("text"))
            })
        }
        model.put("limit", model.optJSONObject("limit") ?: JSONObject().apply {
            put("context", 128000)
            put("output", 32768)
        })
        models.put(modelId, model)
        provider.put("models", models)

        providers.put(id, provider)

        val selected = root.optString("model", "")
        if (selected.isBlank() || selected.substringAfter('/', "").isBlank()) {
            root.put("model", "$id/$modelId")
        }

        return root.toString(2) + "\n"
    }

    // -------------------------------------------------------------------- utils

    private fun field(hintRes: Int): EditText = EditText(this).apply {
        hint = getString(hintRes)
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_NEXT
        textSize = 13f
        setSingleLine(true)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Same package the desktop UI writes for an openai-compatible endpoint. */
        const val DEFAULT_PROVIDER_PACKAGE = "@opencode/ai/providers/openai-compatible"
    }
}
