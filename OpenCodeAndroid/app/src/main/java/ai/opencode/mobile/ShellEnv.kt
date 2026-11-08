package ai.opencode.mobile

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The single source of truth for the environment handed to every child process:
 * the opencode server, the in-app terminal, and anything they spawn.
 *
 * The agent's `shell` tool inherits this, so whatever is put on `PATH` here is
 * directly usable by the model — that is what makes `python`, `pip`, `sh` and the
 * usual Android tools work instead of failing with "command not found".
 */
object ShellEnv {

    /** Directories that exist on a normal Android device, in priority order. */
    private val SYSTEM_PATH = listOf(
        "/system/bin",
        "/system/xbin",
        "/vendor/bin",
        "/product/bin",
        "/apex/com.android.runtime/bin",
    )

    /** `PATH` for child processes: our own wrappers first, then the system. */
    fun path(ctx: Context): String {
        val own = listOf(
            AppEnv.binDir(ctx).absolutePath,
            AppEnv.home(ctx).absolutePath + "/.local/bin",
        )
        return (own + SYSTEM_PATH).joinToString(":")
    }

    /** `$HOME/.local/bin` etc. exist so scripts can drop executables there. */
    fun ensureDirs(ctx: Context) {
        for (dir in listOf(
            File(AppEnv.home(ctx), ".local/bin"),
            File(AppEnv.home(ctx), ".local/lib"),
            File(AppEnv.home(ctx), "work"),
        )) {
            if (!dir.isDirectory) dir.mkdirs()
        }
    }

    /**
     * Variables every child gets. `PYTHON*`/`SSL_CERT*` are only added when the
     * embedded runtime is installed, so a broken/absent runtime cannot make
     * unrelated commands fail.
     */
    fun environment(ctx: Context, python: Boolean = PythonRuntime.isInstalled(ctx)): Map<String, String> {
        val home = AppEnv.home(ctx)
        val tmp = AppEnv.tmp(ctx)
        val base = mutableMapOf(
            "HOME" to home.absolutePath,
            "XDG_CONFIG_HOME" to AppEnv.configHome(ctx).absolutePath,
            "XDG_DATA_HOME" to AppEnv.dataHome(ctx).absolutePath,
            "XDG_CACHE_HOME" to AppEnv.cacheHome(ctx).absolutePath,
            "XDG_STATE_HOME" to AppEnv.stateHome(ctx).absolutePath,
            "XDG_RUNTIME_DIR" to tmp.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "TMP" to tmp.absolutePath,
            "TEMP" to tmp.absolutePath,
            "PATH" to path(ctx),
            "SHELL" to "/system/bin/sh",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
            // Keep tools from trying to page or prompt: there is no tty in a pipe.
            "PAGER" to "cat",
            "GIT_PAGER" to "cat",
            "NO_COLOR" to "",
            "CLICOLOR" to "0",
        )
        if (python) base.putAll(PythonRuntime.environment(ctx))
        return base
    }

    /** Applies [environment] to a ProcessBuilder, dropping inherited junk. */
    fun apply(ctx: Context, pb: ProcessBuilder, python: Boolean = PythonRuntime.isInstalled(ctx)) {
        val env = pb.environment()
        env.putAll(environment(ctx, python))
        // Never leak the host process' linker settings into a musl child.
        env.remove("LD_PRELOAD")
        env.remove("LD_LIBRARY_PATH")
    }

    /** A short human-readable summary for the server log. */
    fun describe(ctx: Context): String {
        val python = PythonRuntime.isInstalled(ctx)
        return buildString {
            append("env: PATH=").append(path(ctx)).append('\n')
            append("env: python=")
            append(if (python) PythonRuntime.exe(ctx).absolutePath else "not installed")
            append(" (abi=").append(Build.SUPPORTED_ABIS.firstOrNull()).append(')')
        }
    }
}
