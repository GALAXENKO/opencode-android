package ai.opencode.mobile

import org.json.JSONObject

/**
 * Minimal JSONC support: the opencode config is JSON with comments and trailing commas.
 * `org.json` cannot parse that, so comments/trailing commas are stripped first (string
 * literals are preserved byte for byte).
 */
object Jsonc {

    private val KNOWN_TOP_LEVEL = setOf(
        "\$schema", "shell", "model", "default_agent", "update", "share", "enterprise",
        "username", "timezone", "permissions", "policies", "agents", "snapshots", "watcher",
        "formatter", "lsp", "media", "tool_output", "mcp", "compaction", "skills", "commands",
        "instructions", "references", "websearch", "plugins", "worktree", "warming",
        "providers", "experimental", "logLevel", "server", "command", "reference", "snapshot",
        "plugin", "autoshare", "autoupdate", "disabled_providers", "enabled_providers",
        "small_model", "subagent_depth", "mode", "agent", "provider", "layout", "permission",
        "tools", "attachment",
    )

    fun strip(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return dropTrailingCommas(out.toString())
    }

    private fun dropTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            if (c == '"') {
                inString = true
                out.append(c)
                i++
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) {
                    i++ // skip the comma
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    fun parse(text: String): JSONObject = JSONObject(strip(text))

    fun validate(text: String): List<String> {
        val problems = mutableListOf<String>()
        if (text.isBlank()) {
            problems += "Config is empty"
            return problems
        }
        val root = try {
            parse(text)
        } catch (t: Throwable) {
            problems += "JSON syntax error: ${t.message}"
            return problems
        }

        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in KNOWN_TOP_LEVEL) {
                problems += "Unknown top-level key \"$key\" (the server rejects unknown keys)"
            }
        }

        val providers = root.optJSONObject("providers")
        if (providers != null) {
            val ids = providers.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                if (id.isBlank()) {
                    problems += "A provider has an empty ID"
                    continue
                }
                val provider = providers.optJSONObject(id)
                if (provider == null) {
                    problems += "providers.$id must be an object"
                    continue
                }
                if (provider.optString("package").isBlank() && provider.optString("canonical").isBlank()) {
                    problems += "providers.$id: \"package\" is required"
                }
                val settings = provider.optJSONObject("settings")
                if (settings != null && settings.optString("baseURL").isBlank()) {
                    problems += "providers.$id: settings.baseURL is empty"
                }
                val models = provider.optJSONObject("models")
                if (models == null) {
                    problems += "providers.$id: no models defined"
                } else {
                    val modelIds = models.keys()
                    while (modelIds.hasNext()) {
                        val modelId = modelIds.next()
                        if (modelId.isBlank()) {
                            problems += "providers.$id: a model has an empty ID"
                        }
                        val model = models.optJSONObject(modelId)
                        if (model == null) problems += "providers.$id.models.$modelId must be an object"
                    }
                }
            }
        }

        val selected = root.optString("model", "")
        if (selected.isNotBlank()) {
            val providerId = selected.substringBefore('/', "")
            val modelId = selected.substringAfter('/', "")
            if (modelId.isBlank()) {
                problems += "\"model\": \"$selected\" has no model part (expected \"provider/model\")"
            } else if (providers != null && !providers.has(providerId)) {
                problems += "\"model\" refers to unknown provider \"$providerId\""
            }
        }
        val small = root.optString("small_model", "")
        if (small.isNotBlank() && small.substringAfter('/', "").isBlank()) {
            problems += "\"small_model\": \"$small\" has no model part"
        }

        return problems
    }
}
