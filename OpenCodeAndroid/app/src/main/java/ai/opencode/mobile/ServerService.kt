package ai.opencode.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL

/**
 * Runs the real opencode server (`opencode serve`) inside the app sandbox as a
 * foreground service, so it keeps running while the UI is in the background and
 * survives screen-off long agent runs.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var bootJob: Job? = null
    private var reader: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null

    @Volatile private var stopping = false
    @Volatile private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        attachLogFile()
    }

    /**
     * Mirrors the in-memory server log into `$HOME/logs/opencode.log`, so a failing
     * start can be reported (and shared from the app menu) without adb access.
     */
    private fun attachLogFile() {
        val file = AppEnv.logFile(this)
        file.parentFile?.mkdirs()
        if (file.length() > MAX_LOG_BYTES) file.delete()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        ServerState.attachSink { line ->
            val entry = stamp.format(java.util.Date()) + "  " + line + "\n"
            file.appendText(entry)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // The previous boot coroutine is still parked on `p.waitFor()`; it has to
                // finish before `starting` is cleared, otherwise startServer() bails out.
                val previous = bootJob
                scope.launch {
                    stopServer(keepServiceAlive = true)
                    previous?.join()
                    startServer()
                }
            }
            else -> startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer(finalTeardown = true)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startServer() {
        if (process?.isAlive == true || starting) return
        starting = true
        stopping = false
        goForeground(getString(R.string.notif_text_starting))
        acquireWakeLock()
        bootJob = scope.launch {
            try {
                boot()
            } catch (t: Throwable) {
                Log.e(TAG, "boot failed", t)
                ServerState.error(t.message ?: t.toString())
                goForeground(getString(R.string.notif_text_error))
            } finally {
                starting = false
            }
        }
    }

    private fun stopServer(keepServiceAlive: Boolean = false, finalTeardown: Boolean = false) {
        stopping = true
        reader?.cancel()
        reader = null
        val p = process
        process = null
        if (p != null) {
            try {
                p.destroy()
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        if (!keepServiceAlive) {
            releaseWakeLock()
            ServerState.stopped()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
            if (!finalTeardown) stopSelf()
        }
    }

    // ---------------------------------------------------------------- bootstrap

    private suspend fun boot() {
        ServerState.set(ServerState.Phase.STARTING, "Preparing environment…")
        AppEnv.ensureDirs(this)
        AppEnv.ensureConfig(this)

        val home = AppEnv.home(this)
        ServerState.appendLog(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT}), abis=${Build.SUPPORTED_ABIS.joinToString(",")}, " +
                "nativeLibraryDir=${applicationInfo.nativeLibraryDir}"
        )
        // The bundled binary has its /etc/resolv.conf and /etc/hosts lookups patched to
        // relative paths, so it reads these files from its working directory ($HOME).
        writeNetworkFiles(home)

        val password = password()
        val bin = AppEnv.findBinary(this) ?: AppEnv.installBinaryCopy(this)
        if (bin == null) {
            ServerState.error(
                "opencode binary not found. nativeLibraryDir=${applicationInfo.nativeLibraryDir}"
            )
            goForeground(getString(R.string.notif_text_error))
            return
        }
        ServerState.appendLog("binary: ${bin.absolutePath} (${bin.length()} bytes, exec=${bin.canExecute()})")
        val loader = AppEnv.muslLoader(this)
        ServerState.appendLog(
            "musl loader: " + (
                loader?.let { "${it.absolutePath} (exec=${it.canExecute()})" } ?: "not found"
                )
        )
        val libs = AppEnv.ensureRuntimeLibs(this)
        ServerState.appendLog(
            "runtime libs: " + (
                libs.listFiles()?.joinToString(", ") { "${it.name}(${it.length()})" } ?: "none"
                )
        )

        val tmp = AppEnv.tmp(this)
        var port = freePort()
        var attempt = 0

        while (attempt < 3) {
            if (stopping) return
            attempt++
            val argv = AppEnv.launchCommand(
                this,
                bin,
                listOf("serve", "--hostname", "127.0.0.1", "--port", port.toString()),
            )
            val pb = ProcessBuilder(argv)
            // cwd must be $HOME: the CLI resolves its relative helper files
            // (resolv.conf / hosts) from the working directory.
            pb.directory(home)
            pb.redirectErrorStream(true)

            val env = pb.environment()
            env["HOME"] = home.absolutePath
            env["XDG_CONFIG_HOME"] = AppEnv.configHome(this).absolutePath
            env["XDG_DATA_HOME"] = AppEnv.dataHome(this).absolutePath
            env["XDG_CACHE_HOME"] = AppEnv.cacheHome(this).absolutePath
            env["XDG_STATE_HOME"] = AppEnv.stateHome(this).absolutePath
            env["TMPDIR"] = tmp.absolutePath
            env["TMP"] = tmp.absolutePath
            env["TEMP"] = tmp.absolutePath
            env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin:/usr/bin:/bin"
            env["SHELL"] = "/system/bin/sh"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["OPENCODE_PASSWORD"] = password
            env["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
            env["OPENCODE_DISABLE_FFF"] = "1"
            env.remove("LD_PRELOAD")
            env.remove("LD_LIBRARY_PATH")

            ServerState.appendLog("starting: ${argv.joinToString(" ")} (cwd=$home)")

            val p = try {
                pb.start()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to start process", t)
                ServerState.error("failed to start opencode: ${t.message}")
                goForeground(getString(R.string.notif_text_error))
                return
            }
            process = p

            reader = scope.launch { pumpOutput(p) }

            val ready = waitReady(port, password, 90_000)
            if (ready) {
                ServerState.running(port, password)
                goForeground(getString(R.string.notif_text_running, port))
                ServerState.appendLog("server ready on http://127.0.0.1:$port/")
                // Keep the process alive for the lifetime of the service.
                val code = withContext(Dispatchers.IO) { p.waitFor() }
                if (!stopping) {
                    ServerState.error("opencode exited with code $code")
                    goForeground(getString(R.string.notif_text_error))
                }
                return
            }

            // Not ready: collect diagnostics and maybe retry on a different port.
            val alive = p.isAlive
            if (alive) {
                p.destroy()
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            }
            val log = ServerState.log()
            ServerState.appendLog("server did not become ready (alive=$alive), attempt $attempt/3")
            if (log.contains("EADDRINUSE") || log.contains("address already in use")) {
                port = freePort()
                continue
            }
            if (attempt >= 3) {
                ServerState.error("opencode did not start. Last output:\n" + log.takeLast(1500))
                goForeground(getString(R.string.notif_text_error))
                return
            }
            delay(700)
        }
    }

    /**
     * Materialises the two `/etc` files the CLI would normally read on Linux.
     *
     * Android has no `/etc/resolv.conf`, so the DNS servers of the active network are
     * copied here. The binary's lookups for both files were patched to relative paths,
     * which means they are resolved against its working directory (`$HOME`).
     */
    private fun writeNetworkFiles(home: File) {
        val servers = activeDnsServers()
        try {
            val resolv = buildString {
                for (server in servers) append("nameserver ").append(server.hostAddress).append('\n')
                append("options ndots:1 timeout:5 attempts:2\n")
            }
            File(home, "resolv.conf").writeText(resolv)
            ServerState.appendLog("dns: " + servers.joinToString(", ") { it.hostAddress ?: "?" })
        } catch (t: Throwable) {
            Log.w(TAG, "cannot write resolv.conf", t)
        }
        try {
            File(home, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        } catch (t: Throwable) {
            Log.w(TAG, "cannot write hosts", t)
        }
    }

    private fun activeDnsServers(): List<InetAddress> {
        val fallback = listOfNotNull(
            runCatching { InetAddress.getByName("8.8.8.8") }.getOrNull(),
            runCatching { InetAddress.getByName("1.1.1.1") }.getOrNull(),
        )
        val cm = getSystemService(ConnectivityManager::class.java) ?: return fallback
        val network = cm.activeNetwork ?: return fallback
        val props = cm.getLinkProperties(network) ?: return fallback
        val servers = props.dnsServers.filter { it.hostAddress != null }
        return if (servers.isEmpty()) fallback else servers
    }

    private fun pumpOutput(p: Process) {
        try {
            p.inputStream.bufferedReader().use { br ->
                while (!stopping) {
                    val line = br.readLine() ?: break
                    if (line.isNotBlank()) {
                        Log.i(TAG, line)
                        ServerState.appendLog(line)
                    }
                }
            }
        } catch (_: Throwable) {
            // process died / stream closed
        }
    }

    private suspend fun waitReady(port: Int, password: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return false
            try {
                val conn = URL("http://127.0.0.1:$port/api/info").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.setRequestProperty("Authorization", OpenCodeAuth.basicHeader(password))
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return true
            } catch (_: Throwable) {
                // not up yet
            }
            delay(400)
        }
        return false
    }

    private fun freePort(): Int =
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun password(): String {
        val prefs = getSharedPreferences("server", Context.MODE_PRIVATE)
        val existing = prefs.getString("password", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = OpenCodeAuth.newPassword()
        prefs.edit().putString("password", generated).apply()
        return generated
    }

    // -------------------------------------------------------------- foreground

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.notif_channel_desc)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun goForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCode:server").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    // ------------------------------------------------------------------ static

    companion object {
        const val TAG = "OpenCodeServer"
        const val ACTION_START = "ai.opencode.mobile.action.START"
        const val ACTION_STOP = "ai.opencode.mobile.action.STOP"
        const val ACTION_RESTART = "ai.opencode.mobile.action.RESTART"
        private const val CHANNEL_ID = "opencode_server"
        private const val NOTIF_ID = 42
        private const val MAX_LOG_BYTES = 4L * 1024 * 1024

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun restart(context: Context) = send(context, ACTION_RESTART)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, ServerService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
