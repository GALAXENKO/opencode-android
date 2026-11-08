package ai.opencode.mobile

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A persistent `sh` running inside the app sandbox.
 *
 * It is process-wide (owned by this object, not by the activity) so rotating the
 * device or leaving the screen does not kill a running build. Every command runs with
 * [ShellEnv.environment], i.e. exactly the same `PATH`, `HOME` and Python setup the
 * agent's `shell` tool gets.
 *
 * Note: this is a pipe-backed shell, not a PTY (Android exposes no public PTY API to
 * Java code). Line-oriented commands, pipes, redirections, scripts and interactive
 * programs that only need stdin work; full-screen curses UIs do not.
 */
object TerminalSession {

    interface Listener {
        fun onLine(line: String)
        fun onState(state: State, detail: String)
    }

    enum class State { STOPPED, RUNNING, EXITED }

    private const val MAX_LINES = 3000

    private val listeners = mutableListOf<Listener>()
    private val buffer = ArrayDeque<String>()

    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var state: State = State.STOPPED
    @Volatile private var cwd: String = ""

    val isRunning: Boolean get() = state == State.RUNNING

    fun lines(): List<String> = synchronized(buffer) { buffer.toList() }

    fun state(): State = state

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
        synchronized(buffer) { buffer.forEach(listener::onLine) }
        listener.onState(state, cwd)
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun emit(line: String) {
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (listener in snapshot) listener.onLine(line)
    }

    private fun setState(next: State, detail: String = cwd) {
        state = next
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (listener in snapshot) listener.onState(next, detail)
    }

    /** Starts a shell unless one is already alive. */
    fun start(ctx: Context) {
        if (state == State.RUNNING && process?.isAlive == true) return
        val home = AppEnv.home(ctx)
        AppEnv.ensureDirs(ctx)
        ShellEnv.ensureDirs(ctx)
        cwd = home.absolutePath
        val pb = ProcessBuilder("/system/bin/sh")
        pb.directory(home)
        pb.redirectErrorStream(true)
        ShellEnv.apply(ctx, pb)
        val p = try {
            pb.start()
        } catch (t: Throwable) {
            emit("cannot start shell: ${t.message ?: t}")
            setState(State.STOPPED)
            return
        }
        process = p
        stdin = p.outputStream
        setState(State.RUNNING)
        emit("# shell started (cwd=$cwd)")
        emit("# PATH=" + ShellEnv.path(ctx))
        Thread({ pump(p, ctx) }, "terminal-reader").apply { isDaemon = true }.start()
    }

    private fun pump(p: Process, ctx: Context) {
        try {
            readStream(p.inputStream) { line -> emit(line) }
        } catch (_: Throwable) {
            // stream closed
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        if (process === p) {
            process = null
            stdin = null
            emit("# shell exited with code $code")
            setState(State.EXITED)
        }
    }

    private fun readStream(input: InputStream, sink: (String) -> Unit) {
        val reader = input.bufferedReader()
        val sb = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch < 0) break
            if (ch == '\n'.code) {
                sink(sb.toString().trimEnd('\r'))
                sb.setLength(0)
            } else {
                sb.append(ch.toChar())
                // Long lines without a newline (progress bars) still need to be shown.
                if (sb.length >= 4096) {
                    sink(sb.toString())
                    sb.setLength(0)
                }
            }
        }
        if (sb.isNotEmpty()) sink(sb.toString())
    }

    /** Sends a raw line (a trailing newline is added when missing). */
    fun submit(ctx: Context, command: String) {
        if (state != State.RUNNING) start(ctx)
        val out = stdin ?: return
        val text = command.trimEnd('\n')
        emit("❯ " + text)
        try {
            out.write((text + "\n").toByteArray())
            out.flush()
        } catch (t: Throwable) {
            emit("# cannot write to shell: ${t.message ?: t}")
        }
    }

    /** Kills the shell and its direct children (the closest thing to Ctrl-C). */
    fun interrupt(ctx: Context) {
        val p = process ?: return
        val pid = pidOf(p)
        emit("# interrupting shell (pid=$pid)")
        if (pid > 0) {
            runCatching {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "/system/bin/sh", "-c",
                        "for c in \$(pgrep -P $pid 2>/dev/null); do kill -9 \$c 2>/dev/null; done; " +
                            "kill -9 $pid 2>/dev/null",
                    ),
                    null,
                    AppEnv.home(ctx),
                )
            }
        }
        runCatching { p.destroyForcibly() }
        process = null
        stdin = null
        setState(State.STOPPED)
    }

    /**
     * `Process.pid()` is not part of every SDK stub set, so look it up at runtime. When
     * the platform lacks it the interrupt still falls back to [Process.destroyForcibly].
     */
    private fun pidOf(p: Process): Long = runCatching {
        (java.lang.Process::class.java.getMethod("pid").invoke(p) as? Number)?.toLong() ?: 0L
    }.getOrDefault(0L)

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    /** Working directory of the shell, as far as we can tell. */
    fun cwd(ctx: Context): File = cwd.takeIf { it.isNotEmpty() }?.let { File(it) } ?: AppEnv.home(ctx)
}
