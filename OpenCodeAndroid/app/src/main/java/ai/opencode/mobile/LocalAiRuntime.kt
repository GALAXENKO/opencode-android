package ai.opencode.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
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

    /** Bundled light-mode plugin, copied into `<config>/plugins/<name>` on first use. */
    private const val PLUGIN_NAME = "local-lite"

    /** How that plugin is listed in `plugins` of `opencode.jsonc`. */
    private const val PLUGIN_ENTRY = "./plugins/$PLUGIN_NAME"

    private const val ENGINE_NAME = "libllama-server.so"
    /**
     * Portable build of the same engine, shipped for CPUs without the ARMv8.2 dot-product
     * instruction (anything older than a Cortex-A75). The fast build uses NEON kernels that
     * such a CPU cannot execute, so it would die with SIGILL.
     */
    private const val ENGINE_NAME_BASELINE = "libllama-server-baseline.so"
    private const val READY_TIMEOUT_MS = 600_000L
    private const val MAX_LOG_LINES = 400

    /** How many times a short body is retried (each time resuming) before giving up. */
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    /** Progress of the GGUF download that is running right now. */
    data class DownloadProgress(val label: String, val copied: Long, val total: Long)

    private val downloadLock = Any()
    private var downloadLabel: String? = null
    private var downloadCopied = 0L
    private var downloadTotal = 0L

    /**
     * Non-null while a model is being downloaded.
     *
     * The download runs on a plain thread, so it outlives the local-AI screen. Keeping the
     * counters here lets that screen show the bar again after a rotation or a trip back to
     * opencode, and stops a second download from starting in parallel.
     */
    fun activeDownload(): DownloadProgress? = synchronized(downloadLock) {
        val label = downloadLabel ?: return null
        DownloadProgress(label, downloadCopied, downloadTotal)
    }

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

    /** Guards the long engine operations ([start], [stop], [adopt]) — never take it on the UI thread. */
    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    @Volatile private var process: Process? = null
    @Volatile private var port = 0

    /**
     * Engine port as known without any blocking work.
     *
     * [start] holds [lock] for minutes while llama.cpp loads the weights, so the screen cannot
     * ask [lock] for the state: doing that froze the local-AI screen for the whole startup and
     * ended in "Input dispatching timed out" ANRs. The worker threads keep this field up to
     * date instead, and [runningPort] only reads it.
     */
    @Volatile private var livePort = 0
    private val adopting = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var logger: ((String) -> Unit)? = null
    @Volatile private var dotProd: Boolean? = null
    @Volatile private var bigCores = 0

    /** Common prefix of both engine builds, used to recognise our child in `/proc`. */
    private val engineMarker = "libllama-server"

    /**
     * Port of the engine that is alive right now; 0 when there is none.
     *
     * Cheap and non-blocking, so it is safe to call from the UI thread every half second.
     * Call [adoptAsync] once when the screen opens to pick up an engine that outlived the app.
     */
    fun runningPort(): Int = if (process?.isAlive == true) port else livePort

    /**
     * Looks for an engine left behind by a previous app run, on a worker thread.
     *
     * Safe to call from the UI thread and cheap to call repeatedly: a second call while the
     * first check is still queued does nothing.
     */
    fun adoptAsync(ctx: Context) {
        if (!adopting.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread({
            try {
                synchronized(lock) { livePort = if (adopt(app) != 0) port else 0 }
            } catch (_: Throwable) {
                // Nothing to adopt.
            } finally {
                adopting.set(false)
            }
        }, "llama-adopt").apply { isDaemon = true }.start()
    }

    /**
     * Returns a non-zero pid when an engine is alive, restoring [port] from [LocalAiStore]
     * when the process survived a restart of the app.
     */
    private fun adopt(ctx: Context): Int {
        val child = process
        if (child != null) {
            if (child.isAlive) return LocalAiStore.enginePid(ctx).takeIf { it > 0 } ?: -1
            process = null
        }
        val pid = LocalAiStore.enginePid(ctx)
        if (pid <= 0) return 0
        if (!pidAlive(pid) || !ownsEngine(pid)) {
            LocalAiStore.clearEngine(ctx)
            return 0
        }
        port = LocalAiStore.enginePort(ctx)
        return pid
    }

    private fun pidAlive(pid: Int): Boolean = pid > 0 && File("/proc/$pid").isDirectory

    /** Guards against pid reuse after a reinstall: only our own engine counts. */
    private fun ownsEngine(pid: Int): Boolean = try {
        File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).contains(engineMarker)
    } catch (_: Throwable) {
        false
    }

    /** Pid of the child that was just started on [port], found by its unique command line. */
    private fun findEnginePid(port: Int): Int {
        val marker = "\u0000$port\u0000"
        repeat(10) {
            val dirs = File("/proc").listFiles()
            if (dirs != null) {
                for (dir in dirs) {
                    val pid = dir.name.toIntOrNull() ?: continue
                    val cmdline = try {
                        File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
                    } catch (_: Throwable) {
                        continue
                    }
                    if (cmdline.contains(engineMarker) && cmdline.contains(marker)) return pid
                }
            }
            Thread.sleep(100)
        }
        return 0
    }

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
        val dir = ctx.applicationInfo.nativeLibraryDir
        val wanted = if (hasDotProd()) ENGINE_NAME else ENGINE_NAME_BASELINE
        val fallback = if (hasDotProd()) ENGINE_NAME_BASELINE else ENGINE_NAME
        for (name in listOf(wanted, fallback)) {
            val file = File(dir, name)
            if (file.isFile && file.canExecute()) return file
        }
        return null
    }

    /**
     * True when the CPU implements the ARMv8.2 dot-product instruction (`vdotq_s32`).
     *
     * The bundled fast engine is compiled for `armv8.2-a+dotprod+fp16`; running it on an older
     * core is an illegal instruction, not a slow path. `/proc/cpuinfo` lists the kernel's HWCAP
     * names, and `asimddp` is exactly this feature.
     */
    fun hasDotProd(): Boolean = dotProd ?: run {
        val value = runCatching {
            File("/proc/cpuinfo").readText().contains("asimddp")
        }.getOrDefault(false)
        dotProd = value
        value
    }

    /**
     * Threads to pass to llama.cpp: the user's choice, otherwise the number of "big" cores.
     *
     * Using every core is measurably worse on big.LITTLE phones: llama.cpp runs the same work
     * on all threads, so the little cores become the stragglers and every matrix multiply
     * waits for them. On a 10-core device (4×1.96 GHz + 6×2.6-3.2 GHz) 6 threads gave
     * 34 tok/s prefill and 11 tok/s generation, versus 23/6 with 8 threads and 21/8 with 4.
     */
    fun threadsFor(ctx: Context): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val stored = LocalAiStore.threads(ctx)
        return if (stored > 0) stored.coerceAtMost(cores) else bigCoreCount(cores)
    }

    /** Cores whose peak clock is within 70 % of the fastest one — the performance cluster. */
    private fun bigCoreCount(cores: Int): Int {
        val cached = bigCores
        if (cached > 0) return cached.coerceAtMost(cores)
        val freqs = (0 until cores).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        if (freqs.size < cores) return (cores / 2).coerceIn(1, cores)
        val fastest = freqs.max()
        val big = freqs.count { it >= fastest * 7 / 10 }
        val result = big.coerceIn(1, cores)
        bigCores = result
        return result
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
            if (adopt(ctx) != 0) {
                livePort = port
                val running = LocalAiStore.modelFile(ctx)
                if (running != null) ensureProvider(ctx, contextFor(ctx, entryOf(ctx)), running)
                return ctx.getString(R.string.local_ai_already_running, port)
            }
            // A previous app run may have left engines behind; they hold the weights in RAM.
            killStrayEngines()

            val engine = engine(ctx)
                ?: throw IOException(ctx.getString(R.string.local_ai_engine_missing))
            val model = LocalAiStore.modelFile(ctx)
                ?: throw IOException(ctx.getString(R.string.local_ai_need_model))
            val entry = CATALOG.firstOrNull { it.id == LocalAiStore.modelId(ctx) }
            val contextSize = contextFor(ctx, entry)
            val threads = threadsFor(ctx)
            val chosenPort = freePort()

            emit(ctx.getString(R.string.local_ai_engine_binary, engine.absolutePath))
            emit(
                if (hasDotProd()) ctx.getString(R.string.local_ai_engine_fast)
                else ctx.getString(R.string.local_ai_engine_portable),
            )
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
                // Flash attention is what makes a quantised KV cache usable, and a q8_0 cache
                // is half the size of the default f16 one: on a 4B model a 16k window costs
                // ~1.2 GB instead of ~2.4 GB, which is the difference between fitting in RAM
                // and being killed by the low-memory killer on a 6-8 GB phone.
                add("-fa"); add("on")
                add("-ctk"); add("q8_0")
                add("-ctv"); add("q8_0")
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
            LocalAiStore.setEngine(ctx, findEnginePid(chosenPort), chosenPort)
            Thread({ pumpOutput(ctx, proc) }, "llama-server-log").apply { isDaemon = true }.start()

            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) {
                    val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                    teardown(ctx)
                    throw IOException(ctx.getString(R.string.local_ai_engine_exited, code))
                }
                if (healthy(chosenPort)) {
                    livePort = chosenPort
                    emit(ctx.getString(R.string.local_ai_engine_listening, chosenPort))
                    writeProvider(ctx, chosenPort, contextSize, model)
                    emit(ctx.getString(R.string.local_ai_provider_written))
                    ServerService.restart(ctx)
                    return ctx.getString(R.string.local_ai_started, chosenPort)
                }
                Thread.sleep(500)
            }

            teardown(ctx)
            throw IOException(ctx.getString(R.string.local_ai_engine_timeout))
        }
    }

    /** Stops the engine and removes the provider again. Blocking. */
    fun stop(ctx: Context): String {
        synchronized(lock) {
            val child = process
            val storedPid = LocalAiStore.enginePid(ctx)
            val wasRunning = (child?.isAlive == true) || (pidAlive(storedPid) && ownsEngine(storedPid))
            process = null
            port = 0
            livePort = 0
            LocalAiStore.clearEngine(ctx)
            if (child != null) {
                runCatching {
                    child.destroy()
                    if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly()
                }
            } else if (storedPid > 0) {
                killPid(storedPid)
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

    /**
     * Drops every trace of the engine and makes sure the child really dies: a failed start
     * must not leave a multi-gigabyte process behind, holding the weights in RAM.
     */
    private fun teardown(ctx: Context) {
        val child = process
        val storedPid = LocalAiStore.enginePid(ctx)
        process = null
        port = 0
        livePort = 0
        LocalAiStore.clearEngine(ctx)
        if (child != null) {
            runCatching {
                child.destroy()
                if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly()
            }
        }
        if (storedPid > 0 && pidAlive(storedPid) && ownsEngine(storedPid)) killPid(storedPid)
    }

    private fun killPid(pid: Int) {
        runCatching { android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL) }
        runCatching {
            Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor()
        }
    }

    /**
     * Kills every `libllama-server.so` of this app that nobody is tracking.
     *
     * Only our own processes are readable under `/proc`, so foreign entries are skipped
     * silently.
     */
    private fun killStrayEngines() {
        val me = android.os.Process.myPid()
        val dirs = File("/proc").listFiles() ?: return
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid == me) continue
            if (ownsEngine(pid)) killPid(pid)
        }
    }

    private fun pumpOutput(ctx: Context, p: Process) {
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
        if (process === p) {
            teardown(ctx)
            emit(ctx.getString(R.string.local_ai_engine_gone))
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
        // NO_PROXY matters: with a system-wide HTTP proxy configured (VPN apps, or a stale
        // `settings put global http_proxy` left behind by another app) HttpURLConnection routes
        // even 127.0.0.1 through it, the health probe fails forever and the engine never gets
        // registered as a provider.
        val conn = URL("http://127.0.0.1:$port/health").openConnection(Proxy.NO_PROXY) as HttpURLConnection
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
    fun download(ctx: Context, model: LocalModel, onProgress: (Long, Long) -> Unit): File =
        downloadFile(ctx, model.url, model.fileName, model.size, model.label, onProgress)

    /**
     * Downloads any GGUF into `~/models` — a catalogue entry or a Hugging Face search
     * result. [expectedSize] may be 0 when the server did not report it.
     */
    fun downloadFile(
        ctx: Context,
        url: String,
        fileName: String,
        expectedSize: Long,
        label: String,
        onProgress: (Long, Long) -> Unit,
    ): File {
        synchronized(downloadLock) {
            if (downloadLabel != null) throw IOException(ctx.getString(R.string.local_ai_busy))
            downloadLabel = label
            downloadCopied = 0
            downloadTotal = expectedSize
        }
        try {
            return fetchFile(ctx, url, fileName, expectedSize, label) { copied, total ->
                synchronized(downloadLock) {
                    downloadCopied = copied
                    downloadTotal = total
                }
                onProgress(copied, total)
            }
        } finally {
            synchronized(downloadLock) {
                downloadLabel = null
                downloadCopied = 0
                downloadTotal = 0
            }
        }
    }

    private fun fetchFile(
        ctx: Context,
        url: String,
        fileName: String,
        expectedSize: Long,
        label: String,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val dir = AppEnv.modelsDir(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException(ctx.getString(R.string.local_ai_models_dir_failed, dir.absolutePath))
        }
        val target = File(dir, fileName)
        if (target.isFile && expectedSize > 0 && target.length() == expectedSize) return target
        // A final file of the wrong size is a leftover from an older run; resume from a .part.
        if (target.isFile) target.delete()

        val part = File(dir, fileName + ".part")
        emit(ctx.getString(R.string.local_ai_download_started, label))

        var total = expectedSize
        var attempt = 0
        while (true) {
            attempt++
            val result = downloadInto(ctx, url, part, total, onProgress)
            if (result.second > 0) total = result.second
            val copied = result.first
            if (total <= 0 || copied == total) break
            // The body stopped early (mobile networks do this a lot). Keep what we have and
            // ask the server for the rest instead of throwing the whole file away.
            if (attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                throw IOException(ctx.getString(R.string.local_ai_download_truncated, copied, total))
            }
            emit(ctx.getString(R.string.local_ai_download_retry, attempt, copied, total))
            Thread.sleep(1_000)
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

    /**
     * One HTTP request that appends to [part], resuming with a `Range` header when the file is
     * already there. Returns `copied to total`, where `total` is 0 when the server hid the size.
     */
    private fun downloadInto(
        ctx: Context,
        url: String,
        part: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit,
    ): Pair<Long, Long> {
        var resumeFrom = if (part.isFile) part.length() else 0L
        // A partial file is only useful when we know where it should end.
        if (resumeFrom > 0 && expectedSize > 0 && resumeFrom >= expectedSize) {
            part.delete()
            resumeFrom = 0
        }
        if (resumeFrom > 0 && expectedSize <= 0) {
            part.delete()
            resumeFrom = 0
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "opencode-android")
            setRequestProperty("Accept", "*/*")
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code ${conn.responseMessage ?: ""}".trim())
            // 206 = the range was honoured; a plain 200 means the server sends the whole file.
            val appending = resumeFrom > 0 && code == 206
            if (resumeFrom > 0 && !appending) {
                part.delete()
                resumeFrom = 0
            }
            val length = conn.contentLengthLong
            val total = if (length > 0) resumeFrom + length else expectedSize
            var copied = resumeFrom
            var lastReport = 0L
            onProgress(copied, total)
            conn.inputStream.use { input ->
                FileOutputStream(part, appending).use { output ->
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
            if (total > 0 && copied < total / 2) {
                // A truncated body (proxy error page) must not be treated as success.
                part.delete()
                throw IOException(ctx.getString(R.string.local_ai_download_truncated, copied, total))
            }
            return copied to total
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Deletes the file behind the current selection. Returns true when something went. */
    fun deleteSelected(ctx: Context): Boolean {
        val file = LocalAiStore.modelFile(ctx)
        val gone = file?.let { it.delete() } ?: false
        if (gone) emit(ctx.getString(R.string.local_ai_deleted, file!!.absolutePath))
        return gone
    }

    // ------------------------------------------------------------------ config

    /** Catalogue entry behind the current selection, if any. */
    private fun entryOf(ctx: Context): LocalModel? =
        CATALOG.firstOrNull { it.id == LocalAiStore.modelId(ctx) }

    /**
     * Re-registers the provider when the config no longer points at the running engine — for
     * example when the user edited `opencode.jsonc` by hand while the engine was up. The
     * server is only restarted when the file actually changed.
     */
    private fun ensureProvider(ctx: Context, contextSize: Int, model: File) {
        val file = AppEnv.configFile(ctx)
        val text = if (file.isFile) file.readText() else ""
        if (providerIsCurrent(ctx, text)) {
            // The plugin file itself can be missing after an app update; put it back.
            if (!LocalAiStore.tools(ctx)) installPlugin(ctx)
            return
        }
        writeProvider(ctx, port, contextSize, model)
        emit(ctx.getString(R.string.local_ai_provider_written))
        ServerService.restart(ctx)
    }

    /**
     * Re-applies the provider after the user flipped the tools switch. Nothing happens while
     * the engine is stopped: the setting is written out the next time it starts.
     */
    fun applyTools(ctx: Context) {
        val running = runningPort()
        if (running == 0) return
        val model = LocalAiStore.modelFile(ctx) ?: return
        writeProvider(ctx, running, contextFor(ctx, entryOf(ctx)), model)
        emit(ctx.getString(R.string.local_ai_provider_written))
        ServerService.restart(ctx)
    }

    /**
     * Writes `providers.local` into `opencode.jsonc`.
     *
     * The file is rewritten through [Jsonc], which drops comments — the same trade-off the
     * in-app provider dialog makes, and the original is kept next to it as a `.bak` copy.
     */
    private fun writeProvider(ctx: Context, port: Int, contextSize: Int, model: File) {
        // Light mode is a plugin; without a successfully unpacked plugin file the entry would
        // point at nothing and the server would fail to boot, so it stays off in that case.
        val lite = !LocalAiStore.tools(ctx) && installPlugin(ctx)
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
                            put("tools", LocalAiStore.tools(ctx))
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
            setLite(root, lite)
            // Only claim the default model when the user has not chosen one yet.
            if (root.optString("model").isBlank()) {
                root.put("model", "$PROVIDER_ID/$MODEL_ID")
            }
        }
    }

    /**
     * Copies the bundled light-mode plugin into `<config>/plugins/local-lite`.
     *
     * The server only loads plugins from disk, so the APK asset has to be unpacked into the
     * config directory before `plugins` can point at it. Rewritten only when the bytes differ,
     * because the server watches that file and reloads on every change.
     */
    private fun installPlugin(ctx: Context): Boolean = try {
        val dir = File(AppEnv.configDir(ctx), "plugins/$PLUGIN_NAME")
        dir.mkdirs()
        val target = File(dir, "index.js")
        val bytes = ctx.assets.open("plugin/$PLUGIN_NAME/index.js").use { it.readBytes() }
        if (!target.isFile || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Adds or removes the light-mode plugin entry in a parsed config.
     *
     * Entries the user added by hand are kept: only ours is touched, and the array is dropped
     * entirely when nothing else is left, so a config written by the app never grows a
     * `"plugins": []` the user did not ask for.
     */
    private fun setLite(root: JSONObject, on: Boolean) {
        val existing = root.optJSONArray("plugins")
        val kept = JSONArray()
        for (i in 0 until (existing?.length() ?: 0)) {
            val item = existing!!.opt(i)
            val name = if (item is JSONObject) item.optString("package") else item?.toString().orEmpty()
            if (name == PLUGIN_ENTRY) continue
            kept.put(item)
        }
        if (on) kept.put(PLUGIN_ENTRY)
        if (kept.length() == 0) root.remove("plugins") else root.put("plugins", kept)
    }

    /**
     * Raw-text test for the light-mode entry.
     *
     * The config is written by [JSONObject], which escapes every `/` as `\/`, so the file on
     * disk holds `.\/plugins\/local-lite`. A plain [String.contains] on the logical path misses
     * it, which used to leave the plugin behind after the engine stopped.
     */
    private fun mentionsPlugin(text: String): Boolean =
        text.contains(PLUGIN_ENTRY) || text.contains(PLUGIN_ENTRY.replace("/", "\\/"))

    /** True when [text] already carries everything [writeProvider] would write. */
    private fun providerIsCurrent(ctx: Context, text: String): Boolean {
        if (!text.contains("127.0.0.1:$port/v1")) return false
        if (!text.contains("\"tools\": ${LocalAiStore.tools(ctx)}")) return false
        return mentionsPlugin(text) == !LocalAiStore.tools(ctx)
    }

    /**
     * Removes `providers.local` and the light-mode plugin entry; returns true when the config
     * actually changed.
     *
     * Checks the parsed config first: [updateConfig] rewrites the whole file (dropping comments),
     * so it must not run on a boot where there is nothing to remove.
     */
    private fun removeProvider(ctx: Context): Boolean {
        val file = AppEnv.configFile(ctx)
        val text = if (file.isFile) file.readText() else ""
        if (text.isBlank()) return false
        val current = runCatching { Jsonc.parse(text) }.getOrNull() ?: return false
        val hasProvider = current.optJSONObject("providers")?.has(PROVIDER_ID) == true
        if (!hasProvider && !mentionsPlugin(text)) return false
        var removed = false
        updateConfig(ctx) { root ->
            val providers = root.optJSONObject("providers")
            if (providers != null && providers.has(PROVIDER_ID)) {
                providers.remove(PROVIDER_ID)
                removed = true
            }
            if (root.optString("model").startsWith("$PROVIDER_ID/")) root.remove("model")
            if (mentionsPlugin(text)) {
                setLite(root, false)
                removed = true
            }
        }
        return removed
    }

    /**
     * Makes the config match reality before the server boots: keeps the `local` provider only
     * while its engine is actually alive.
     *
     * Without this a dead engine leaves `providers.local` pointing at a closed port, and every
     * chat that still had that model selected fails with `ECONNREFUSED` even though the user
     * never asked for the local model in this run. Blocking — call it from the server thread.
     */
    fun reconcile(ctx: Context) {
        synchronized(lock) {
            if (adopt(ctx) != 0) {
                livePort = port
                val model = LocalAiStore.modelFile(ctx) ?: return
                val text = AppEnv.configFile(ctx).takeIf { it.isFile }?.readText().orEmpty()
                if (!providerIsCurrent(ctx, text)) {
                    writeProvider(ctx, port, contextFor(ctx, entryOf(ctx)), model)
                    emit(ctx.getString(R.string.local_ai_provider_written))
                } else if (!LocalAiStore.tools(ctx)) {
                    installPlugin(ctx)
                }
                return
            }
            if (removeProvider(ctx)) emit(ctx.getString(R.string.local_ai_provider_dropped))
        }
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

    // ------------------------------------------------------------- hugging face

    /** A GGUF repository found by [searchHf]. */
    data class HfRepo(val id: String, val downloads: Long, val likes: Long) {
        val name: String get() = id.substringAfter('/')
    }

    /** One downloadable GGUF inside [repo]. */
    data class HfFile(val repo: String, val path: String, val size: Long) {
        val name: String get() = path.substringAfterLast('/')
        val url: String get() = "https://huggingface.co/$repo/resolve/main/$path"
    }

    private const val HF_API = "https://huggingface.co/api"

    /**
     * Searches huggingface.co for GGUF repositories, most downloaded first.
     *
     * Blocking — call from a worker thread. The public API needs no token; gated
     * repositories simply show up without downloadable files.
     */
    fun searchHf(query: String, limit: Int = 25): List<HfRepo> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$HF_API/models?search=$encoded&filter=gguf&sort=downloads&direction=-1&limit=$limit"
        val body = HttpJson.request("GET", url, HF_HEADERS, timeoutMs = 20_000).requireOk().body
        val array = JSONArray(body.ifBlank { "[]" })
        val out = mutableListOf<HfRepo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            out += HfRepo(id, item.optLong("downloads"), item.optLong("likes"))
        }
        return out
    }

    /**
     * Lists the single-file GGUF weights of [repo], smallest first.
     *
     * Split shards (`-00001-of-0000N`) are skipped: llama.cpp needs every part next to each
     * other, which is a poor fit for a phone download.
     */
    fun hfFiles(repo: String): List<HfFile> {
        val url = "$HF_API/models/$repo?blobs=true"
        val body = HttpJson.request("GET", url, HF_HEADERS, timeoutMs = 30_000).requireOk().body
        val siblings = JSONObject(body.ifBlank { "{}" }).optJSONArray("siblings") ?: return emptyList()
        val out = mutableListOf<HfFile>()
        for (i in 0 until siblings.length()) {
            val file = siblings.optJSONObject(i) ?: continue
            val path = file.optString("rfilename")
            if (!path.endsWith(".gguf", ignoreCase = true)) continue
            if (path.contains("-of-0", ignoreCase = true)) continue
            out += HfFile(repo, path, file.optLong("size"))
        }
        return out.sortedBy { it.size }
    }

    private val HF_HEADERS = mapOf(
        "User-Agent" to "opencode-android",
        "Accept" to "application/json",
    )

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
