package ai.opencode.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale

/**
 * One downloadable GGUF model offered by the local-AI screen.
 *
 * The URLs are the public Hugging Face `resolve/main` links, i.e. the same files
 * llama.cpp users download by hand. [size] mirrors the server's `content-length` and is
 * only used for the "x MB" label and the progress bar before headers arrive.
 */
data class LocalModel(
    val id: String,
    val label: String,
    val url: String,
    val size: Long,
    val maxContext: Int,
    val hintRes: Int,
) {
    val fileName: String get() = url.substringAfterLast('/')
}

/**
 * Runs the bundled `llama-server` (llama.cpp) as a child process on loopback and exposes
 * it to opencode as an OpenAI-compatible provider.
 *
 * The app does not reimplement a chat UI: opencode already has one. The engine is started
 * on demand, `providers.local` is written into `opencode.jsonc` pointing at
 * `http://127.0.0.1:<port>/v1`, and the opencode server is restarted so the model shows up
 * in the normal model picker. Stopping the engine removes that provider again, so the
 * config never advertises a model that cannot answer.
 *
 * The binary ships as `jniLibs/arm64-v8a/libllama-server.so` (AGP only packages `*.so`).
 * It is a bionic executable, so it is started directly — no musl loader, unlike the
 * opencode CLI.
 */
object LocalAiRuntime {

    /** Provider id written into `opencode.jsonc`. */
    const val PROVIDER_ID = "local"

    /** Stable model id inside that provider, so a saved selection survives model swaps. */
    const val MODEL_ID = "local"

    private const val ENGINE_NAME = "libllama-server.so"
    private const val READY_TIMEOUT_MS = 180_000L
    private const val MAX_LOG_LINES = 400

    /** Verified live on huggingface.co (HTTP 200 + content-length). */
    val CATALOG: List<LocalModel> = listOf(
        LocalModel(
            id = "qwen2.5-0.5b-instruct-q4_k_m",
            label = "Qwen2.5 0.5B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            size = 491_400_032L,
            maxContext = 32_768,
            hintRes = R.string.local_ai_hint_tiny,
        ),
        LocalModel(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            label = "Qwen2.5 1.5B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            size = 1_117_320_736L,
            maxContext = 32_768,
            hintRes = R.string.local_ai_hint_balanced,
        ),
        LocalModel(
            id = "qwen2.5-coder-1.5b-instruct-q4_k_m",
            label = "Qwen2.5 Coder 1.5B",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            size = 1_117_320_768L,
            maxContext = 32_768,
            hintRes = R.string.local_ai_hint_coder,
        ),
        LocalModel(
            id = "gemma-3-1b-it-q4_k_m",
            label = "Gemma 3 1B IT",
            url = "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            size = 806_058_240L,
            maxContext = 32_768,
            hintRes = R.string.local_ai_hint_multilingual,
        ),
        LocalModel(
            id = "qwen3-1.7b-q4_k_m",
            label = "Qwen3 1.7B",
            url = "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            size = 1_282_439_264L,
            maxContext = 40_960,
            hintRes = R.string.local_ai_hint_reasoning,
        ),
        LocalModel(
            id = "qwen2.5-coder-3b-instruct-q4_k_m",
            label = "Qwen2.5 Coder 3B",
            url = "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
            size = 1_929_903_360L,
            maxContext = 32_768,
            hintRes = R.string.local_ai_hint_large,
        ),
    )

    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    @Volatile private var process: Process? = null
    @Volatile private var port = 0
    @Volatile private var logger: ((String) -> Unit)? = null

    fun isRunning(): Boolean = process?.isAlive == true

    /** Port the engine listens on, or 0 when it is not running. */
    fun activePort(): Int = if (isRunning()) port else 0

    /** Lines produced since the app started, newest last — shown when the screen opens. */
    fun recent(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clearLog() = synchronized(buffer) { buffer.clear() }

    /** The screen attaches its log view here; pass null when it goes away. */
    fun setLogger(sink: ((String) -> Unit)?) {
        logger = sink
    }

    // ------------------------------------------------------------------- engine

    /** `libllama-server.so` from the extracted native library directory. */
    fun engine(ctx: Context): File? {
        val file = File(ctx.applicationInfo.nativeLibraryDir, ENGINE_NAME)
        return if (file.isFile && file.canExecute()) file else null
    }

    /** Threads to pass to llama.cpp: user choice, otherwise all cores but one. */
    fun threadsFor(ctx: Context): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val stored = LocalAiStore.threads(ctx)
        return if (stored > 0) stored.coerceAtMost(cores) else (cores - 1).coerceIn(1, cores)
    }

    /** Context window actually requested: the setting, capped by what the model supports. */
    fun contextFor(ctx: Context, model: LocalModel?): Int {
        val wanted = LocalAiStore.contextSize(ctx)
        val max = model?.maxContext ?: 131_072
        return wanted.coerceIn(512, max)
    }

    /** Starts the engine and registers the provider. Blocking — call from a worker thread. */
    fun start(ctx: Context): String {
        synchronized(lock) {
            if (isRunning()) return ctx.getString(R.string.local_ai_already_running, port)

            val engine = engine(ctx)
                ?: throw IOException(ctx.getString(R.string.local_ai_engine_missing))
            val model = LocalAiStore.modelFile(ctx)
                ?: throw IOException(ctx.getString(R.string.local_ai_need_model))
            val entry = CATALOG.firstOrNull { it.id == LocalAiStore.modelId(ctx) }
            val contextSize = contextFor(ctx, entry)
            val threads = threadsFor(ctx)
            val chosenPort = freePort()

            emit(ctx.getString(R.string.local_ai_engine_binary, engine.absolutePath))
            emit(ctx.getString(R.string.local_ai_engine_model, model.absolutePath, humanSize(model.length())))

            val argv = buildList {
                add(engine.absolutePath)
                add("-m"); add(model.absolutePath)
                add("--host"); add("127.0.0.1")
                add("--port"); add(chosenPort.toString())
                add("-c"); add(contextSize.toString())
                add("-t"); add(threads.toString())
                add("-tb"); add(threads.toString())
                add("-np"); add("1")
                add("--jinja")
                add("--no-warmup")
                add("--no-webui")
                add("-a"); add(MODEL_ID)
            }
            emit(ctx.getString(R.string.local_ai_engine_command, argv.joinToString(" ")))

            val pb = ProcessBuilder(argv)
            pb.directory(AppEnv.home(ctx))
            pb.redirectErrorStream(true)
            ShellEnv.apply(ctx, pb, python = false)

            val proc = try {
                pb.start()
            } catch (t: Throwable) {
                throw IOException(ctx.getString(R.string.local_ai_engine_spawn_failed, t.message ?: t.toString()))
            }
            process = proc
            port = chosenPort
            Thread({ pumpOutput(proc) }, "llama-server-log").apply { isDaemon = true }.start()

            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) {
                    val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                    teardown()
                    throw IOException(ctx.getString(R.string.local_ai_engine_exited, code))
                }
                if (healthy(chosenPort)) {
                    emit(ctx.getString(R.string.local_ai_engine_listening, chosenPort))
                    writeProvider(ctx, chosenPort, contextSize, model)
                    emit(ctx.getString(R.string.local_ai_provider_written))
                    ServerService.restart(ctx)
                    return ctx.getString(R.string.local_ai_started, chosenPort)
                }
                Thread.sleep(500)
            }

            teardown()
            throw IOException(ctx.getString(R.string.local_ai_engine_timeout))
        }
    }

    /** Stops the engine and removes the provider again. Blocking. */
    fun stop(ctx: Context): String {
        synchronized(lock) {
            val wasRunning = isRunning()
            val p = process
            teardown()
            if (p != null) {
                runCatching {
                    p.destroy()
                    if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
            emit(ctx.getString(R.string.local_ai_engine_stopped_log))
            val removed = removeProvider(ctx)
            ServerService.restart(ctx)
            return if (wasRunning || removed) {
                ctx.getString(R.string.local_ai_stopped)
            } else {
                ctx.getString(R.string.local_ai_nothing_to_stop)
            }
        }
    }

    private fun teardown() {
        process = null
        port = 0
    }

    private fun pumpOutput(p: Process) {
        try {
            p.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) emit(line)
                }
            }
        } catch (_: Throwable) {
            // process died / stream closed
        }
    }

    private fun emit(line: String) {
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LOG_LINES) buffer.removeFirst()
        }
        logger?.invoke(line)
    }

    private fun healthy(port: Int): Boolean = try {
        val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        conn.connectTimeout = 1_500
        conn.readTimeout = 1_500
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    } catch (_: Throwable) {
        false
    }

    private fun freePort(): Int =
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    // --------------------------------------------------------------- downloads

    /**
     * Downloads [model] into `~/models` (`.part` file, then rename, so an interrupted
     * download never looks complete). Blocking — call from a worker thread.
     */
    fun download(ctx: Context, model: LocalModel, onProgress: (Long, Long) -> Unit): File {
        val dir = AppEnv.modelsDir(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException(ctx.getString(R.string.local_ai_models_dir_failed, dir.absolutePath))
        }
        val target = File(dir, model.fileName)
        if (target.isFile && target.length() == model.size) return target

        val part = File(dir, model.fileName + ".part")
        if (part.isFile) part.delete()

        emit(ctx.getString(R.string.local_ai_download_started, model.label))
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "opencode-android")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code ${conn.responseMessage ?: ""}".trim())
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: model.size
            var copied = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val chunk = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        output.write(chunk, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(copied, total)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            onProgress(copied, total)
            if (copied < total / 2) {
                // A truncated body (proxy error page) must not be treated as success.
                throw IOException(ctx.getString(R.string.local_ai_download_truncated, copied, total))
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            runCatching { conn.disconnect() }
        }

        if (target.isFile) target.delete()
        if (!part.renameTo(target)) {
            part.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            part.delete()
        }
        emit(ctx.getString(R.string.local_ai_download_done, target.absolutePath, humanSize(target.length())))
        return target
    }

    /** Deletes the file behind the current selection. Returns true when something went. */
    fun deleteSelected(ctx: Context): Boolean {
        val file = LocalAiStore.modelFile(ctx)
        val gone = file?.let { it.delete() } ?: false
        if (gone) emit(ctx.getString(R.string.local_ai_deleted, file!!.absolutePath))
        return gone
    }

    // ------------------------------------------------------------------ config

    /**
     * Writes `providers.local` into `opencode.jsonc`.
     *
     * The file is rewritten through [Jsonc], which drops comments — the same trade-off the
     * in-app provider dialog makes, and the original is kept next to it as a `.bak` copy.
     */
    private fun writeProvider(ctx: Context, port: Int, contextSize: Int, model: File) {
        updateConfig(ctx) { root ->
            val providers = root.optJSONObject("providers") ?: JSONObject().also { root.put("providers", it) }
            providers.put(PROVIDER_ID, JSONObject().apply {
                put("name", "Local AI")
                put("package", "@opencode/ai/providers/openai-compatible")
                put("settings", JSONObject().apply {
                    put("baseURL", "http://127.0.0.1:$port/v1")
                    put("apiKey", "local")
                })
                put("models", JSONObject().apply {
                    put(MODEL_ID, JSONObject().apply {
                        put("name", model.nameWithoutExtension)
                        put("capabilities", JSONObject().apply {
                            put("tools", true)
                            put("input", JSONArray().put("text"))
                            put("output", JSONArray().put("text"))
                        })
                        put("limit", JSONObject().apply {
                            put("context", contextSize)
                            put("output", (contextSize / 4).coerceIn(512, 4_096))
                        })
                    })
                })
            })
            // Only claim the default model when the user has not chosen one yet.
            if (root.optString("model").isBlank()) {
                root.put("model", "$PROVIDER_ID/$MODEL_ID")
            }
        }
    }

    /** Removes `providers.local`; returns true when it was there. */
    private fun removeProvider(ctx: Context): Boolean {
        var removed = false
        updateConfig(ctx) { root ->
            val providers = root.optJSONObject("providers") ?: return@updateConfig
            if (!providers.has(PROVIDER_ID)) return@updateConfig
            providers.remove(PROVIDER_ID)
            removed = true
            if (root.optString("model").startsWith("$PROVIDER_ID/")) root.remove("model")
        }
        return removed
    }

    private fun updateConfig(ctx: Context, mutate: (JSONObject) -> Unit) {
        val file = AppEnv.configFile(ctx)
        val text = if (file.isFile) file.readText() else ""
        val root = if (text.isBlank()) JSONObject() else Jsonc.parse(text)
        mutate(root)
        file.parentFile?.mkdirs()
        if (file.isFile) runCatching { file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true) }
        file.writeText(root.toString(2) + "\n")
    }

    // ----------------------------------------------------------------- helpers

    /** `468.6 MB` style size, matching the label in the model picker. */
    fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
