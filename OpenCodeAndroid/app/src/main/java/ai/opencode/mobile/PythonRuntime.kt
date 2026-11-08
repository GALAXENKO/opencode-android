package ai.opencode.mobile

import android.content.Context
import android.os.Build
import java.io.File
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
    private const val LAYOUT_VERSION = 3

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
                // The archive is produced on Windows, so entry names may use '\' as the
                // separator. ZipInputStream hands names back verbatim and '\' is a legal
                // (if useless) character in a Linux file name, which used to create one
                // flat file called `lib\python3.12\...` instead of a tree.
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.isEmpty() || name.contains("..")) {
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
     * pip ships inside `lib/python3.12/site-packages`, so normally there is nothing to
     * bootstrap. `ensurepip` cannot be used as a fallback: it re-executes
     * `sys.executable`, and the interpreter's `PT_INTERP` (`/lib/ld-musl-aarch64.so.1`)
     * does not exist on Android, so exec'ing it directly fails. The interpreter is only
     * ever usable through the musl loader (see [writeWrappers]).
     */
    private fun bootstrapPip(ctx: Context, log: (String) -> Unit) {
        val marker = File(dir(ctx), PIP_MARKER)
        if (marker.isFile) return
        if (File(sitePackages(ctx), "pip/__init__.py").isFile) {
            marker.writeText("bundled")
            log("python: pip ready (bundled)")
            return
        }
        log("python: pip missing from site-packages; run `python -m ensurepip` manually")
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
        val ca = caBundle(ctx).absolutePath
        val sslDir = File(dir(ctx), "ssl").absolutePath
        // A plain `sh` does not get [environment] (only processes the app spawns do), so
        // everything that matters for https:// and pip is baked into the wrappers.
        val env = """
            PYTHONHOME="$home"
            PYTHONPATH="$home/lib/python3.12/site-packages"
            PYTHONNOUSERSITE="1"
            SSL_CERT_FILE="$ca"
            SSL_CERT_DIR="$sslDir"
            REQUESTS_CA_BUNDLE="$ca"
            CURL_CA_BUNDLE="$ca"
            PIP_CERT="$ca"
            PIP_DISABLE_PIP_VERSION_CHECK="1"
            PIP_NO_INPUT="1"
            OPENCODE_MUSL_LOADER="$loader"
            export PYTHONHOME PYTHONPATH PYTHONNOUSERSITE SSL_CERT_FILE SSL_CERT_DIR
            export REQUESTS_CA_BUNDLE CURL_CA_BUNDLE PIP_CERT PIP_DISABLE_PIP_VERSION_CHECK PIP_NO_INPUT
            export OPENCODE_MUSL_LOADER
        """.trimIndent()
        // Concatenated, not a raw string with trimIndent(): interpolating the multi-line
        // [env] makes the common indent empty, which would leave the `#!` line indented.
        // A shell still runs such a file (it falls back to reading it as a script), but
        // execve() does not, so `subprocess.run(["python3", …])` would fail with ENOEXEC.
        fun wrapper(args: String) = buildString {
            append("#!/system/bin/sh\n")
            append("# generated by OpenCode for Android - do not edit\n")
            append(env).append('\n')
            append("exec \"$loader\" --library-path \"$libPath\" \"$target\"$args \"${'$'}@\"\n")
        }
        for (name in listOf("python3", "python")) writeScript(File(bin, name), wrapper(""))
        for (name in listOf("pip3", "pip")) writeScript(File(bin, name), wrapper(" -m pip"))
        writeSiteCustomize(ctx)
    }

    /**
     * Installs `sitecustomize.py` (see `assets/sitecustomize.py`), which patches two
     * Android-specific problems for every interpreter start:
     *
     *  - **exec** — the interpreter's ELF `PT_INTERP` (`/lib/ld-musl-aarch64.so.1`)
     *    does not exist here, so anything that re-executes it (`pip` building an
     *    sdist, `multiprocessing`, `subprocess.run([sys.executable, …])`) fails. The
     *    module rewrites those exec calls to loader + `--library-path` + interpreter.
     *  - **DNS** — the bundled musl opens `resolv.conf` relative to the *working
     *    directory*, so lookups only work when the process starts in `$HOME`. The
     *    module resolves names over UDP/53 against `$HOME/resolv.conf` instead.
     *
     * Placeholders are substituted here because only the app knows the install paths.
     */
    private fun writeSiteCustomize(ctx: Context) {
        val pkg = sitePackages(ctx)
        if (!pkg.isDirectory) pkg.mkdirs()
        val loader = AppEnv.muslLoader(ctx)
        val body = try {
            ctx.assets.open("sitecustomize.py").bufferedReader().use { it.readText() }
                .replace("{{FILES}}", ctx.filesDir.absolutePath)
                .replace("{{HOME}}", AppEnv.home(ctx).absolutePath)
                .replace("{{PYTHON}}", dir(ctx).absolutePath)
                .replace("{{EXE}}", exe(ctx).absolutePath)
                .replace("{{LIBPATH}}", libraryPath(ctx))
                .replace("{{LOADER}}", loader?.absolutePath.orEmpty())
        } catch (e: Exception) {
            return
        }
        writeScript(File(pkg, "sitecustomize.py"), body)
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
            "OPENCODE_MUSL_LOADER" to AppEnv.muslLoader(ctx)?.absolutePath.orEmpty(),
        )
    }
}
