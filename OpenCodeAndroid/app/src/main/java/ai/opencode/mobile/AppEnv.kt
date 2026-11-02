package ai.opencode.mobile

import android.content.Context
import java.io.File

/**
 * Filesystem layout of the embedded opencode installation.
 *
 * Everything lives inside the app sandbox, mirroring a desktop `$HOME`:
 *
 *   files/home/                     -> HOME
 *   files/home/.config/opencode     -> XDG_CONFIG_HOME/opencode   (opencode.jsonc lives here)
 *   files/home/.local/share/opencode-> XDG_DATA_HOME/opencode
 *   files/home/.cache/opencode      -> XDG_CACHE_HOME/opencode
 *   files/home/.local/state/opencode-> XDG_STATE_HOME/opencode
 *   files/home/tmp                  -> TMPDIR
 *   files/bin/opencode              -> fallback copy of the CLI (if the native lib dir is not executable)
 */
object AppEnv {

    /** File name of the musl loader inside the native library directory. */
    private const val LOADER_NAME = "ld-musl-aarch64.so"

    fun home(ctx: Context): File = File(ctx.filesDir, "home")
    fun configHome(ctx: Context): File = File(home(ctx), ".config")
    fun configDir(ctx: Context): File = File(configHome(ctx), "opencode")
    fun configFile(ctx: Context): File = File(configDir(ctx), "opencode.jsonc")
    fun dataHome(ctx: Context): File = File(home(ctx), ".local/share")
    fun cacheHome(ctx: Context): File = File(home(ctx), ".cache")
    fun stateHome(ctx: Context): File = File(home(ctx), ".local/state")
    fun tmp(ctx: Context): File = File(home(ctx), "tmp")
fun logFile(ctx: Context): File = File(home(ctx), "logs/opencode.log")
    fun binDir(ctx: Context): File = File(ctx.filesDir, "bin")
    fun libDir(ctx: Context): File = File(ctx.filesDir, "lib")

    fun ensureDirs(ctx: Context) {
        for (dir in listOf(
            home(ctx),
            configDir(ctx),
            File(dataHome(ctx), "opencode"),
            File(cacheHome(ctx), "opencode"),
            File(stateHome(ctx), "opencode"),
            tmp(ctx),
            File(home(ctx), "logs"),
            binDir(ctx),
            libDir(ctx),
        )) {
            if (!dir.isDirectory) dir.mkdirs()
        }
    }

    const val DEFAULT_CONFIG = """{
  // OpenCode on Android - edit this file, or use the in-app provider editor.
  // Docs: https://opencode.ai/docs/config
  "providers": {}
}
"""

    fun ensureConfig(ctx: Context) {
        val file = configFile(ctx)
        if (!file.isFile || file.length() == 0L) {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_CONFIG)
        }
    }

    /**
     * The CLI is shipped as `jniLibs/<abi>/libopencode.so`. With `extractNativeLibs=true`
     * Android extracts it to the (executable) native library directory, which is the only
     * place an app targeting a modern API level is allowed to `exec` from.
     *
     * If that path is not usable we fall back to a private copy in `files/bin`.
     */
    fun findBinary(ctx: Context): File? {
        val native = File(ctx.applicationInfo.nativeLibraryDir, "libopencode.so")
        if (native.isFile && native.canExecute()) return native
        val copy = File(binDir(ctx), "opencode")
        if (copy.isFile && copy.canExecute()) return copy
        return null
    }

    /** musl dynamic loader that ships next to the CLI (see `jniLibs/arm64-v8a`). */
    fun muslLoader(ctx: Context): File? {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        for (name in listOf(LOADER_NAME, "$LOADER_NAME.1")) {
            val file = File(dir, name)
            if (file.isFile && file.canExecute()) return file
        }
        return null
    }

    /**
     * Libraries that the CLI needs at runtime, shipped under a name the Android build
     * tools accept.
     *
     * AGP only packages files whose name ends with `.so`, so the versioned names musl
     * asks for (`libstdc++.so.6`, `libgcc_s.so.1`) cannot be placed in `jniLibs` as-is.
     * They ship as `libstdcxx.so` / `libgcc_s.so` and are exposed here under their real
     * names as symlinks into the (executable, mapped-from-APK) native library directory.
     */
    private val SHIPPED_LIBS = listOf(
        "libstdc++.so.6" to "libstdcxx.so",
        "libgcc_s.so.1" to "libgcc_s.so",
    )

    /** Creates `files/lib` and (re)links the runtime libraries there. Returns that directory. */
    fun ensureRuntimeLibs(ctx: Context): File {
        val dir = libDir(ctx)
        if (!dir.isDirectory) dir.mkdirs()
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        for ((wanted, shipped) in SHIPPED_LIBS) {
            val src = File(nativeDir, shipped)
            if (!src.isFile) continue
            val dst = File(dir, wanted)
            // exists() follows symlinks, so a stale link is recreated as well.
            if (dst.exists() && dst.length() == src.length()) continue
            dst.delete()
            val linked = runCatching {
                java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath())
            }.isSuccess
            if (!linked) {
                runCatching {
                    src.inputStream().use { input ->
                        dst.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                    }
                }
            }
        }
        return dir
    }

    /**
     * Builds the argv used to start the CLI.
     *
     * The CLI is a Bun executable linked against musl (`PT_INTERP=/lib/ld-musl-aarch64.so.1`)
     * plus `libstdc++.so.6` / `libgcc_s.so.1`. Android has none of those, and `PT_INTERP`
     * cannot point into the app sandbox (the path is per-install), so the loader is started
     * explicitly: `ld-musl-aarch64.so --library-path <nativeLibDir>:<files/lib> libopencode.so ...`.
     * musl treats `libc.musl-aarch64.so.1` as itself (it matches the reserved `libc` prefix)
     * and resolves the C++ runtime from the library path.
     */
    fun launchCommand(ctx: Context, bin: File, args: List<String>): List<String> {
        val loader = muslLoader(ctx) ?: return listOf(bin.absolutePath) + args
        val libPath = listOfNotNull(
            bin.parentFile?.absolutePath,
            ensureRuntimeLibs(ctx).absolutePath,
        ).distinct().joinToString(":")
        return listOf(loader.absolutePath, "--library-path", libPath, bin.absolutePath) + args
    }

    fun installBinaryCopy(ctx: Context): File? {
        val native = File(ctx.applicationInfo.nativeLibraryDir, "libopencode.so")
        if (!native.isFile) return null
        val target = File(binDir(ctx), "opencode")
        if (!target.isFile || target.length() != native.length()) {
            target.parentFile?.mkdirs()
            native.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
        }
        target.setReadable(true, true)
        target.setExecutable(true, true)
        target.setWritable(false, true)
        return if (target.canExecute()) target else null
    }
}
