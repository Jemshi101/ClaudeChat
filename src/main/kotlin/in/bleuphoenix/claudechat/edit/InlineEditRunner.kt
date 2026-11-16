package `in`.bleuphoenix.claudechat.edit

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import `in`.bleuphoenix.claudechat.cli.ClaudeCli
import `in`.bleuphoenix.claudechat.cli.InstructionFiles
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Asks Claude for an inline edit (or a commit message) with a one-shot `claude -p` that has no tools at all (no built-in tools, no MCP
 * servers) and keeps no session: Claude only answers with text, which the plugin applies.
 */
object InlineEditRunner {
    private const val EDIT_FAILED = "Claude couldn't make the edit"

    /** Claude's reply text. Blocks until it arrives; [indicator]'s cancel stops the CLI. Throws IOException on failure. */
    fun run(
        workDir: String,
        prompt: String,
        model: String?,
        indicator: ProgressIndicator,
        failure: String = EDIT_FAILED,
    ): String {
        val executable = ClaudeCli.findExecutable()
            ?: throw IOException("Could not find the claude CLI. Set its path in Settings → Tools → Claude Chat.")
        val commandLine = commandLine(executable, workDir, model)
        InstructionFiles.apply(commandLine, workDir, ClaudeChatSettings.options.instructionFiles, null)
        val process = commandLine.createProcess()
        try {
            process.outputStream.use { it.write(prompt.toByteArray(StandardCharsets.UTF_8)) }
            // Read output while waiting, so a full pipe can't stall the CLI.
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { output.append(it.readText()) }
            }.apply { isDaemon = true; start() }
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (indicator.isCanceled) throw ProcessCanceledException()
            }
            reader.join(2000)
            return reply(output.toString(), process.exitValue(), process.errorStream.bufferedReader().readText(), failure)
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    fun commandLine(executable: String, workDir: String, model: String?): GeneralCommandLine {
        val args = mutableListOf(
            executable, "-p",
            "--output-format", "json",
            "--tools", "",
            "--strict-mcp-config",
            "--no-session-persistence",
        )
        if (model != null) args += listOf("--model", model)
        return GeneralCommandLine(args)
            .withWorkDirectory(workDir)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
    }

    /** The `result` text from the CLI's JSON [output]; throws IOException with the CLI's message when it failed. */
    fun reply(output: String, exitCode: Int, stderr: String, failure: String = EDIT_FAILED): String {
        val json = runCatching { JsonParser.parseString(output.trim()).asJsonObject }.getOrNull()
        val result = json?.get("result")?.takeIf { it.isJsonPrimitive }?.asString
        if (json == null || json.get("is_error")?.asBoolean == true || result == null) {
            val message = result ?: stderr.trim().ifEmpty { output.trim() }.ifEmpty { "exit code $exitCode" }
            throw IOException("$failure: ${message.take(300)}")
        }
        return result
    }
}
