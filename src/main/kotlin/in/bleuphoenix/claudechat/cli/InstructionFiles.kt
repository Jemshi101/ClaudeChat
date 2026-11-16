package `in`.bleuphoenix.claudechat.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import java.io.File
import java.security.MessageDigest

/**
 * Project guides given to Claude instead of CLAUDE.md (Settings → Instruction files, e.g. `agent.md, spec.md`).
 * When any are found, CLAUDE.md loading is turned off with `CLAUDE_CODE_DISABLE_CLAUDE_MDS=1` (this turns off every
 * CLAUDE.md, the project's and its parents') and the files' text is appended to the system prompt (see
 * docs/PROTOCOL.md, "Instruction files").
 */
object InstructionFiles {

    /** Splits the setting's text ("agent.md, spec.md" or one per line) into file names. */
    fun names(setting: String?): List<String> =
        setting.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Each of [names] found in [workDir] or the nearest parent folder that has it, up to the git repository root
     * (the first folder with a `.git`), or not at all. Absolute paths are taken as they are.
     */
    fun resolve(workDir: String, names: List<String>): List<File> {
        val start = File(workDir).canonicalFile
        val folders = generateSequence(start) { folder -> folder.parentFile?.takeUnless { File(folder, ".git").exists() } }.toList()
        return names.mapNotNull { name ->
            val path = File(name)
            if (path.isAbsolute) path.takeIf { it.isFile }
            else folders.map { File(it, name) }.firstOrNull { it.isFile }
        }.distinct()
    }

    /** The text appended to Claude's system prompt: each file's content under its name and path. */
    fun prompt(files: List<File>): String = buildString {
        append("# Project instructions\n\n")
        append("These are the user's guides for this project, given instead of CLAUDE.md files. Follow them.\n")
        files.forEach { file ->
            append("\n## ${file.name} (${file.path})\n\n")
            append(file.readText().trim())
            append('\n')
        }
    }

    /**
     * Applies the instructions in [setting] to [commandLine], appending them to [appendPrompt]; returns the files used.
     * The text goes in a file in [promptDir] (`--append-system-prompt-file`): guides like spec.md easily pass the
     * 32,767 characters Windows allows a command line ("CreateProcess error=206").
     */
    fun apply(
        commandLine: GeneralCommandLine,
        workDir: String,
        setting: String?,
        appendPrompt: String?,
        promptDir: File = defaultPromptDir(),
    ): List<File> {
        val files = resolve(workDir, names(setting))
        val prompt = listOfNotNull(appendPrompt, files.takeIf { it.isNotEmpty() }?.let(::prompt)).joinToString("\n\n")
        if (files.isNotEmpty()) commandLine.withEnvironment(DISABLE_CLAUDE_MDS, "1")
        if (prompt.isNotEmpty()) commandLine.addParameters("--append-system-prompt-file", promptFile(prompt, promptDir).path)
        return files
    }

    /** A file holding [prompt], named by its content, so chats with the same instructions share one. */
    fun promptFile(prompt: String, dir: File): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
        val file = File(dir, "prompt-$hash.md")
        if (!file.isFile) {
            dir.mkdirs()
            // Written whole, then moved in place, so a chat starting at the same moment never reads half of it.
            val partial = File.createTempFile("prompt-", ".tmp", dir)
            partial.writeText(prompt, Charsets.UTF_8)
            if (!partial.renameTo(file)) partial.delete()
        }
        return file
    }

    private fun defaultPromptDir() = File(PathManager.getSystemPath(), "claude-chat/prompts")

    const val DISABLE_CLAUDE_MDS = "CLAUDE_CODE_DISABLE_CLAUDE_MDS"
}
