package ai.opencode.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state of the embedded opencode server.
 *
 * The service owns the process; the activity only observes this object, so the UI
 * survives activity re-creation (rotation, returning from background, etc.).
 */
object ServerState {

    enum class Phase { IDLE, STARTING, RUNNING, ERROR, STOPPED }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val port: Int = 0,
        val password: String = "",
        val message: String = "",
        val log: List<String> = emptyList(),
    ) {
        val url: String get() = if (port > 0) "http://127.0.0.1:$port/" else ""
        val isRunning: Boolean get() = phase == Phase.RUNNING && port > 0
    }

    private const val MAX_LOG = 500

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Optional persistent sink (log file) so a log survives process restarts. */
    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun attachSink(newSink: ((String) -> Unit)?) {
        sink = newSink
    }

    fun set(phase: Phase, message: String = "") {
        _state.value = _state.value.copy(phase = phase, message = message)
    }

    fun running(port: Int, password: String) {
        _state.value = _state.value.copy(
            phase = Phase.RUNNING,
            port = port,
            password = password,
            message = "http://127.0.0.1:$port/",
        )
    }

    fun error(message: String) {
        _state.value = _state.value.copy(phase = Phase.ERROR, message = message)
    }

    fun stopped() {
        _state.value = _state.value.copy(phase = Phase.STOPPED, port = 0, message = "")
    }

    fun appendLog(line: String) {
        val current = _state.value
        val next = (current.log + line).takeLast(MAX_LOG)
        _state.value = current.copy(log = next)
        val target = sink
        if (target != null) {
            try {
                target(line)
            } catch (_: Throwable) {
                // a broken log file must never break the server lifecycle
            }
        }
    }

    fun log(): String = _state.value.log.joinToString("\n")
}
