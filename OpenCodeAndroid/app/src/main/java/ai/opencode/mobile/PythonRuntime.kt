package ai.opencode.mobile

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The embedded CPython runtime.
 *
 * `assets/python.zip` is produced by `tools/build-python.ps1` from a
 * python-build-standalone `aarch64-unknown-linux-musl` build, i.e. the same libc the
 * embedded opencode CLI links against. The archive is a plain prefix tree:
 *
 *   bin/python3.12        interpreter (statically linked libpython, only needs musl libc)
 *   lib/python3.12/…     stdlib + ensurepip (so `pip` can be bootstrapped offline)
 *   ssl/cacert.pem        CA bundle — Android has no /etc/ssl/certs
 *
 * The interpreter's `PT_INTERP` points at `/lib/ld-musl-aarch64.so.1`, which does not
 * exist on Android, so it is always started through the musl loader that ships next to
 * the opencode binary (see [AppEnv.launchCommand]).
 *
 * To make `python3` usable from a plain `sh` (the agent's `shell` tool, the in-app
 * terminal, `#!/usr/bin/env python3` scripts) thin wrapper scripts are generated in
 * `files/bin`, which is first on `PATH`.
 */
object PythonRuntime {

    private const val ASSET = "python.zip"
    private const val MARKER = ".installed"
    private const val PIP_MARKER = ".pip"

    /** Bumped when the extraction logic changes, so an update re-extracts. */
    private const val LAYOUT_VERSION = 1

    fun dir(ctx: Context): File = File(ctx.filesDir, "python")
    fun exe(ctx: Context): File = File(dir(ctx), "bin/python3.12")
    fun stdlib(ctx: Context): File = File(dir(ctx), "lib/python3.12")
    fun sitePackages(ctx: Context): File = File(stdlib(ctx), "site-packages")
    fun caBundle(ctx: Context): File = File(dir(ctx), "ssl/cacert.pem")

    /** The runtime is only shipped for arm64 (see `build-python.ps1`). */
    fun isSupported(ctx: Context): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return abi.startsWith("arm64") && AppEnv.muslLoader(ctx) != null
    }

    fun isInstalled(ctx: Context): Boolean =
        exe(ctx).isFile && File(dir(ctx), MARKER).isFile

    /**
     * Extracts the runtime on first boot (or after an app update) and generates the
     * `python` / `pip` wrappers. Safe to call on every start: it is a no-op once the
     * marker matches. Never throws — a broken runtime must not stop the server.
     *
     * @return true when a usable interpreter is on disk.
     */
    fun ensure(ctx: Context, log: (String) -> Unit = {}): Boolean {
        if (!isSupported(ctx)) {
            log("python: not available on ${Build.SUPPORTED_ABIS.joinToString(",")}")
            return false
        }
        return try {
            val dir = dir(ctx)
            val marker = File(dir, MARKER)
            val stamp = stamp(ctx)
            if (isInstalled(ctx) && marker.readText().trim() == stamp) {
                writeWrappers(ctx)
                return true
            }
            log("python: extracting runtime to $dir …")
            if (dir.isDirectory) dir.deleteRecursively()
            dir.mkdirs()
            val count = extract(ctx)
            log("python: $count files extracted")
            bootstrapPip(ctx, log)
            marker.writeText(stamp)
            writeWrappers(ctx)
            exe(ctx).isFile
        } catch (t: Throwable) {
            log("python: install failed: ${t.message ?: t}")
            false
        }
    }

    private fun stamp(ctx: Context): String {
        val updated = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return "$updated-$LAYOUT_VERSION"
    }

    private fun extract(ctx: Context): Int {
        val root = dir(ctx)
        var count = 0
        ZipInputStream(ctx.assets.open(ASSET).buffered(1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.contains("..")) {
                    zip.closeEntry()
                    continue
                }
                val target = File(root, name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered(1 shl 16).use { out -> zip.copyTo(out, 1 shl 16) }
                    if (name.startsWith("bin/")) {
                        target.setExecutable(true, true)
                    }
                    count++
                }
                zip.closeEntry()
            }
        }
        return count
    }

    /**
     * `ensurepip` ships inside the stdlib, so pip works without network access. It runs
     * once, at install time; a failure (e.g. read-only storage) is not fatal because
     * the interpreter itself is still usable.
     */
    private fun bootstrapPip(ctx: Context, log: (String) -> Unit) {
        if (File(dir(ctx), PIP_MARKER).isFile) return
        try {
            val pb = ProcessBuilder(launchCommand(ctx) + listOf("-m", "ensurepip", "--upgrade"))
            pb.directory(AppEnv.home(ctx))
            pb.redirectErrorStream(true)
            pb.environment().putAll(environment(ctx))
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val done = process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0
            if (done) {
                File(dir(ctx), PIP_MARKER).writeText("1")
                log("python: pip ready")
            } else {
                log("python: ensurepip failed: " + output.takeLast(400).trim())
            }
        } catch (t: Throwable) {
            log("python: ensurepip error: ${t.message ?: t}")
        }
    }

    /**
     * `bin/python3` / `bin/pip3` wrappers. They re-exec the loader, so the caller does
     * not have to know about musl at all:
     *
     *   python3 -c 'print(1)'
     */
    fun writeWrappers(ctx: Context) {
        val loader = AppEnv.muslLoader(ctx) ?: return
        val bin = AppEnv.binDir(ctx)
        if (!bin.isDirectory) bin.mkdirs()
        val libPath = libraryPath(ctx)
        val home = dir(ctx).absolutePath
        val target = exe(ctx).absolutePath
        val script = """
            #!/system/bin/sh
            # generated by OpenCode for Android - do not edit
            PYTHONHOME="$home"
            PYTHONNOUSERSITE="1"
            export PYTHONHOME PYTHONNOUSERSITE
            exec "$loader" --library-path "$libPath" "$target" "${'$'}@"
        """.trimIndent() + "\n"
        for (name in listOf("python3", "python")) writeScript(File(bin, name), script)
        val pip = """
            #!/system/bin/sh
            # generated by OpenCode for Android - do not edit
            PYTHONHOME="$home"
            PYTHONNOUSERSITE="1"
            export PYTHONHOME PYTHONNOUSERSITE
            exec "$loader" --library-path "$libPath" "$target" -m pip "${'$'}@"
        """.trimIndent() + "\n"
        for (name in listOf("pip3", "pip")) writeScript(File(bin, name), pip)
    }

    private fun writeScript(file: File, body: String) {
        // Rewriting an identical file would invalidate nothing but costs I/O on every
        // boot; compare first.
        if (file.isFile && runCatching { file.readText() }.getOrNull() == body) {
            file.setExecutable(true, true)
            return
        }
        file.writeText(body)
        file.setExecutable(true, true)
    }

    /** Libraries musl has to search: the app's own libs plus the interpreter's. */
    fun libraryPath(ctx: Context): String = listOf(
        ctx.applicationInfo.nativeLibraryDir,
        AppEnv.libDir(ctx).absolutePath,
        File(dir(ctx), "lib").absolutePath,
    ).joinToString(":")

    /** argv that starts the interpreter (loader + --library-path + python3.12). */
    fun launchCommand(ctx: Context): List<String> {
        val loader = AppEnv.muslLoader(ctx)
            ?: return listOf(exe(ctx).absolutePath)
        return listOf(
            loader.absolutePath,
            "--library-path",
            libraryPath(ctx),
            exe(ctx).absolutePath,
        )
    }

    /**
     * Environment variables that make the runtime behave like a normal CPython install:
     * stdlib discovery, TLS trust store, and a writable bytecode cache.
     */
    fun environment(ctx: Context): Map<String, String> {
        val home = dir(ctx).absolutePath
        val ca = caBundle(ctx).absolutePath
        return mapOf(
            "PYTHONHOME" to home,
            "PYTHONPATH" to sitePackages(ctx).absolutePath,
            "PYTHONNOUSERSITE" to "1",
            "SSL_CERT_FILE" to ca,
            "SSL_CERT_DIR" to File(dir(ctx), "ssl").absolutePath,
            "REQUESTS_CA_BUNDLE" to ca,
            "CURL_CA_BUNDLE" to ca,
            "PIP_CERT" to ca,
            "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
            "PIP_NO_INPUT" to "1",
        )
    }
}
