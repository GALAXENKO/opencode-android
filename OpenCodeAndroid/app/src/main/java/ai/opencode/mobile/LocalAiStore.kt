package ai.opencode.mobile

import android.content.Context
import java.io.File

/**
 * Persistent settings of the built-in local AI (see [LocalAiRuntime]).
 *
 * Only *choices* live here — the model file itself sits in `~/models`, and the engine
 * provider is written into `opencode.jsonc` while the engine runs. Keeping the two apart
 * means a user can wipe `~/models` (or reinstall the app) without breaking the settings,
 * and the config always reflects what is actually running.
 */
object LocalAiStore {

    private const val FILE = "local_ai"

    /** Chosen model: a [LocalAiRuntime.CATALOG] id, or `file:<absolute path>` for a custom GGUF. */
    private const val KEY_MODEL = "model"
    private const val KEY_CONTEXT = "context"
    private const val KEY_THREADS = "threads"

    /**
     * Whether the provider advertises tool calling.
     *
     * Tool schemas are by far the largest part of opencode's system prompt: with them the
     * first request is ~6k tokens, without them ~2.5k. On a phone every one of those tokens
     * has to be pre-filled by the CPU before the first word appears, so the default is off —
     * a small local model cannot drive tools reliably anyway. The screen offers a switch for
     * the cases where the user really wants the agent to touch files.
     */
    private const val KEY_TOOLS = "tools"

    /**
     * Pid and port of the running engine.
     *
     * `llama-server` is a child process of the app, but Android may kill the app while it
     * keeps running. These two values let the next app start find that leftover process
     * again instead of claiming "stopped" while a gigabyte of weights still sits in RAM.
     */
    private const val KEY_PID = "engine_pid"
    private const val KEY_PORT = "engine_port"

    /**
     * Context window used when the user never changed it.
     *
     * opencode sends the whole conversation on every turn, so a small window fails with
     * "request exceeds the available context size" after a handful of messages. 8192 tokens
     * with the quantised KV cache the engine uses costs roughly 600 MB — acceptable on a
     * 4 GB device — and still leaves room for a long chat.
     */
    const val DEFAULT_CONTEXT = 8192

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun modelId(ctx: Context): String = prefs(ctx).getString(KEY_MODEL, "") ?: ""

    fun setModelId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_MODEL, id).apply()
    }

    fun contextSize(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CONTEXT, DEFAULT_CONTEXT).coerceIn(512, 131_072)

    fun setContextSize(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_CONTEXT, value.coerceIn(512, 131_072)).apply()
    }

    /** 0 means "decide from the core count" — see [LocalAiRuntime.threadsFor]. */
    fun threads(ctx: Context): Int = prefs(ctx).getInt(KEY_THREADS, 0).coerceIn(0, 32)

    fun setThreads(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_THREADS, value.coerceIn(0, 32)).apply()
    }

    fun tools(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TOOLS, false)

    fun setTools(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TOOLS, value).apply()
    }

    /** Clears the selection (used when the file behind a custom path disappeared). */
    fun clearModel(ctx: Context) {
        prefs(ctx).edit().remove(KEY_MODEL).apply()
    }

    fun enginePid(ctx: Context): Int = prefs(ctx).getInt(KEY_PID, 0)

    fun enginePort(ctx: Context): Int = prefs(ctx).getInt(KEY_PORT, 0)

    fun setEngine(ctx: Context, pid: Int, port: Int) {
        prefs(ctx).edit().putInt(KEY_PID, pid).putInt(KEY_PORT, port).apply()
    }

    fun clearEngine(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PID).remove(KEY_PORT).apply()
    }

    /** The selected model file, or null when nothing usable is selected. */
    fun modelFile(ctx: Context): File? {
        val id = modelId(ctx)
        if (id.isEmpty()) return null
        if (id.startsWith(CUSTOM_PREFIX)) {
            val file = File(id.removePrefix(CUSTOM_PREFIX))
            return if (file.isFile) file else null
        }
        val entry = LocalAiRuntime.CATALOG.firstOrNull { it.id == id } ?: return null
        val file = File(AppEnv.modelsDir(ctx), entry.fileName)
        return if (file.isFile) file else null
    }

    /** Prefix for a model the user pointed at manually instead of downloading it. */
    const val CUSTOM_PREFIX = "file:"
}
