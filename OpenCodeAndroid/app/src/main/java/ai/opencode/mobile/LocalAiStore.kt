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

    /** Context window used when the user never changed it. Fits 4 GB devices comfortably. */
    const val DEFAULT_CONTEXT = 4096

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

    /** Clears the selection (used when the file behind a custom path disappeared). */
    fun clearModel(ctx: Context) {
        prefs(ctx).edit().remove(KEY_MODEL).apply()
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
