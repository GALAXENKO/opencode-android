package ai.opencode.mobile

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * A terminal screen wired to [TerminalSession].
 *
 * The shell itself is process-wide, so leaving this screen (or rotating the device)
 * keeps long-running commands alive; the activity just attaches to the shared buffer
 * and prints whatever arrives. Output is capped at [MAX_LINES] lines to keep the
 * `TextView` responsive on long builds.
 */
class TerminalActivity : AppCompatActivity(), TerminalSession.Listener {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var title: TextView

    private val rendered = ArrayDeque<String>()
    private val history = ArrayList<String>()
    private var historyIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        output = findViewById(R.id.termOutput)
        scroll = findViewById(R.id.termScroll)
        input = findViewById(R.id.termInput)
        title = findViewById(R.id.termTitle)

        findViewById<ImageButton>(R.id.btnTermSend).setOnClickListener { send() }
        findViewById<ImageButton>(R.id.btnTermNew).setOnClickListener { restartShell() }
        findViewById<ImageButton>(R.id.btnTermClear).setOnClickListener {
            TerminalSession.clear()
            rendered.clear()
            output.text = ""
        }
        findViewById<ImageButton>(R.id.btnTermStop).setOnClickListener {
            TerminalSession.interrupt(this)
            TerminalSession.start(this)
        }

        findViewById<Button>(R.id.btnTermPython).setOnClickListener {
            fill("python3 -c \"import sys, platform; print(sys.version); print(platform.machine())\"")
        }
        findViewById<Button>(R.id.btnTermPip).setOnClickListener { fill("pip --version") }
        findViewById<Button>(R.id.btnTermHome).setOnClickListener { fill("cd \"${AppEnv.home(this)}\" && pwd") }
        findViewById<Button>(R.id.btnTermWork).setOnClickListener { fill("cd \"${AppEnv.workDir(this)}\" && ls -la") }
        findViewById<Button>(R.id.btnTermEnv).setOnClickListener { fill("echo \"\$PATH\"; echo \"\$PYTHONHOME\"") }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_GO) {
                send()
                true
            } else {
                false
            }
        }

        AppEnv.ensureDirs(this)
        ShellEnv.ensureDirs(this)
        TerminalSession.start(this)
        TerminalSession.addListener(this)
    }

    override fun onDestroy() {
        TerminalSession.removeListener(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session

    override fun onLine(line: String) {
        runOnUiThread {
            rendered.addLast(line)
            var dropped = false
            while (rendered.size > MAX_LINES) {
                rendered.removeFirst()
                dropped = true
            }
            if (dropped) {
                // The deque dropped its head: re-render once so the view matches.
                output.text = rendered.joinToString("\n")
            } else {
                output.append(if (output.text.isEmpty()) line else "\n$line")
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onState(state: TerminalSession.State, detail: String) {
        runOnUiThread {
            title.text = when (state) {
                TerminalSession.State.RUNNING -> getString(R.string.terminal_state_running)
                TerminalSession.State.EXITED -> getString(R.string.terminal_state_exited)
                TerminalSession.State.STOPPED -> getString(R.string.terminal_state_stopped)
            }
        }
    }

    // -------------------------------------------------------------------- input

    private fun fill(command: String) {
        input.setText(command)
        input.setSelection(command.length)
        input.requestFocus()
    }

    private fun send() {
        val command = input.text.toString()
        if (command.isBlank()) {
            // An empty line is meaningful for `sh` (it just echoes the prompt), but a
            // stray tap on send should not spam the buffer.
            return
        }
        history.add(command)
        historyIndex = history.size
        input.setText("")
        TerminalSession.submit(this, command)
    }

    private fun restartShell() {
        TerminalSession.interrupt(this)
        TerminalSession.start(this)
        Toast.makeText(this, R.string.terminal_restarted, Toast.LENGTH_SHORT).show()
    }

    /** Up/down arrow keys coming from a hardware keyboard walk the history. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && history.isNotEmpty()) {
            historyIndex = (historyIndex - 1).coerceAtLeast(0)
            replaceInput(history[historyIndex])
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && history.isNotEmpty()) {
            historyIndex = (historyIndex + 1).coerceAtMost(history.size)
            replaceInput(if (historyIndex >= history.size) "" else history[historyIndex])
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun replaceInput(text: String) {
        input.setText(text)
        input.setSelection(text.length)
    }

    companion object {
        private const val MAX_LINES = 1500
    }
}
