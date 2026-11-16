package `in`.bleuphoenix.claudechat.cli

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Signs the CLI in with `claude auth login`, which the chat can run without a terminal (the CLI's own `/login` isn't
 * available in stream-json mode). The CLI opens the browser and prints the sign-in address; if the browser shows a
 * code instead of finishing by itself, it's sent to the CLI with [sendCode]. Callbacks come on a background thread.
 */
class ClaudeLogin(
    private val workDir: String,
    private val onUrl: (String) -> Unit,
    private val onExit: (exitCode: Int, output: String) -> Unit,
) : Disposable {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var cancelled = false

    /** [console]: sign in for Anthropic Console (API billing) rather than a Claude subscription. */
    fun start(console: Boolean) {
        val executable = ClaudeCli.findExecutable()
            ?: throw IOException("Could not find the claude CLI. Install Claude Code, or set its path in Settings → Tools → Claude Chat.")
        val args = listOfNotNull(executable, "auth", "login", "--console".takeIf { console })
        val started = cliCommand(args, workDir).withRedirectErrorStream(true).createProcess()
        process = started
        thread(name = "claude-login", isDaemon = true) {
            val output = StringBuilder()
            var urlSent = false
            // The prompt for a code doesn't end with a newline, so read what comes rather than whole lines.
            val reader = started.inputStream.bufferedReader(StandardCharsets.UTF_8)
            val buffer = CharArray(1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                output.append(buffer, 0, read)
                if (!urlSent) signInUrl(output)?.let {
                    urlSent = true
                    onUrl(it)
                }
            }
            val exitCode = started.waitFor()
            if (!cancelled) onExit(exitCode, plain(output.toString()).trim())
        }
    }

    /** The code the browser shows after signing in, for the CLI's "Paste code here" prompt. */
    fun sendCode(code: String) {
        val stdin = process?.outputStream ?: return
        runCatching {
            stdin.write((code.trim() + "\n").toByteArray(StandardCharsets.UTF_8))
            stdin.flush()
        }.onFailure { LOG.warn("Could not send the sign-in code", it) }
    }

    override fun dispose() {
        cancelled = true
        process?.let { p ->
            runCatching { p.outputStream.close() }
            p.destroy()
        }
        process = null
    }

    companion object {
        private val LOG = logger<ClaudeLogin>()
        private val URL = Regex("""https://\S+""")

        // Terminal escapes: OSC 8 hyperlinks (ESC ] 8 ;; url BEL) and colours (ESC [ ... m).
        private val ESCAPES = Regex("""\u001B]8;;[^\u0007\u001B]*(?:\u0007|\u001B\\)|\u001B\[[0-9;?]*[A-Za-z]""")

        /** The address the CLI printed for signing in, once it has been printed in full. */
        fun signInUrl(output: CharSequence): String? {
            val text = plain(output.toString())
            val match = URL.find(text) ?: return null
            // Complete once something follows it.
            return match.value.takeIf { match.range.last < text.lastIndex }
        }

        fun plain(text: String): String = ESCAPES.replace(text, "")

        /**
         * `claude auth status`: the signed-in account's email (or the auth method when there's none), null when not
         * signed in or the CLI can't be asked.
         */
        fun account(workDir: String): String? = runCatching {
            val executable = ClaudeCli.findExecutable() ?: return null
            val process = cliCommand(listOf(executable, "auth", "status", "--json"), workDir).createProcess()
            val text = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            process.waitFor()
            val status = JsonParser.parseString(text).asJsonObject
            if (status.get("loggedIn")?.asBoolean != true) return null
            status.get("email")?.takeIf { it.isJsonPrimitive }?.asString ?: status.get("authMethod")?.asString ?: "Claude"
        }.getOrNull()

        private fun cliCommand(args: List<String>, workDir: String) = GeneralCommandLine(args)
            .withWorkDirectory(workDir)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
    }
}
