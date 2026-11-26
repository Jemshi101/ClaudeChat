package `in`.bleuphoenix.claudechat.cli

import java.io.File

/** Where a custom command lives: the project's `.claude/commands` or the user's, for every project. */
enum class CommandScope(val label: String) {
    PROJECT("This project"),
    USER("All projects"),
}

/**
 * A custom slash command: a Markdown file whose name is the command. [description] and [argumentHint] come from its
 * frontmatter; [otherFrontmatter] keeps the rest (`allowed-tools`, `model`, …) as written, so saving doesn't lose it.
 */
data class CustomCommand(
    val name: String,
    val scope: CommandScope,
    val description: String,
    val argumentHint: String,
    val body: String,
    val file: File? = null,
    val otherFrontmatter: List<String> = emptyList(),
) {
    /** The description, or the prompt's first line when there's none, as the `/` list shows it. */
    val summary: String
        get() = description.ifBlank { body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty() }
}

/**
 * Reads and writes Claude Code's custom commands: `.md` files in `<project>/.claude/commands` and in
 * `~/.claude/commands` (or `$CLAUDE_CONFIG_DIR/commands`). A file in a subfolder is named `folder:name`.
 */
object CustomCommands {

    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*(:[A-Za-z0-9][A-Za-z0-9_.-]*)*")

    fun userDir(): File =
        System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { File(it, "commands") }
            ?: File(System.getProperty("user.home"), ".claude/commands")

    fun projectDir(workDir: String): File = File(workDir, ".claude/commands")

    fun dir(scope: CommandScope, workDir: String?, userDir: File): File? = when (scope) {
        CommandScope.PROJECT -> workDir?.let(::projectDir)
        CommandScope.USER -> userDir
    }

    /** The project's commands, then the user's, each sorted by name. Never throws. */
    fun read(workDir: String?, userDir: File = userDir()): List<CustomCommand> =
        CommandScope.entries.flatMap { scope -> dir(scope, workDir, userDir)?.let { readDir(it, scope) }.orEmpty() }

    private fun readDir(dir: File, scope: CommandScope): List<CustomCommand> = runCatching {
        dir.walkTopDown().filter { it.isFile && it.extension == "md" }.mapNotNull { file ->
            val name = file.relativeTo(dir).path.removeSuffix(".md").replace(File.separatorChar, ':')
            runCatching { parse(name, scope, file.readText(), file) }.getOrNull()
        }.sortedBy { it.name.lowercase() }.toList()
    }.getOrDefault(emptyList())

    fun validName(name: String): Boolean = NAME.matches(name)

    /** The file a command named [name] is saved in. */
    fun fileFor(dir: File, name: String): File = File(dir, name.replace(':', File.separatorChar) + ".md")

    fun parse(name: String, scope: CommandScope, text: String, file: File? = null): CustomCommand {
        val normalized = text.replace("\r\n", "\n")
        var description = ""
        var hint = ""
        val other = mutableListOf<String>()
        var body = normalized
        if (normalized.startsWith("---\n")) {
            val end = normalized.indexOf("\n---", 4)
            if (end >= 0) {
                normalized.substring(4, end).lines().forEach { line ->
                    when (line.substringBefore(':').trim()) {
                        "description" -> description = unquote(line.substringAfter(':'))
                        "argument-hint" -> hint = unquote(line.substringAfter(':'))
                        else -> if (line.isNotBlank()) other += line
                    }
                }
                body = normalized.substring(end + 4).substringAfter('\n', "")
            }
        }
        return CustomCommand(name, scope, description, hint, body.trim('\n'), file, other)
    }

    /** The command as a file: frontmatter (when it has any) and the prompt. */
    fun render(command: CustomCommand): String {
        val front = listOfNotNull(
            command.description.takeIf { it.isNotBlank() }?.let { "description: ${quote(it)}" },
            command.argumentHint.takeIf { it.isNotBlank() }?.let { "argument-hint: ${quote(it)}" },
        ) + command.otherFrontmatter
        val body = command.body.trim('\n') + "\n"
        return if (front.isEmpty()) body else "---\n${front.joinToString("\n")}\n---\n\n$body"
    }

    /**
     * Writes [command] to its scope's folder; when it replaces [replacing] under another name or scope, the old file is
     * deleted. Returns the file written.
     */
    fun save(command: CustomCommand, replacing: CustomCommand?, workDir: String?, userDir: File = userDir()): File {
        require(validName(command.name)) { "Not a valid command name: ${command.name}" }
        val dir = dir(command.scope, workDir, userDir) ?: error("This project has no folder for its commands.")
        val file = fileFor(dir, command.name)
        file.parentFile.mkdirs()
        file.writeText(render(command))
        replacing?.file?.takeIf { it.canonicalFile != file.canonicalFile }?.let { delete(it, dir(replacing.scope, workDir, userDir)) }
        return file
    }

    fun delete(command: CustomCommand, workDir: String?, userDir: File = userDir()) {
        command.file?.let { delete(it, dir(command.scope, workDir, userDir)) }
    }

    /** Deletes [file], and the subfolders it leaves empty up to [root]. */
    private fun delete(file: File, root: File?) {
        file.delete()
        var folder = file.parentFile
        while (root != null && folder != null && folder.canonicalFile != root.canonicalFile && folder.list()?.isEmpty() == true) {
            folder.delete()
            folder = folder.parentFile
        }
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2 && trimmed.first() == trimmed.last() && trimmed.first() in "\"'") {
            return trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return trimmed
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
