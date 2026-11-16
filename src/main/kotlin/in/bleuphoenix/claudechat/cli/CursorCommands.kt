package `in`.bleuphoenix.claudechat.cli

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.io.File

/**
 * Copies Cursor's commands (the .md files in `~/.cursor/commands`) into Claude Code's (`~/.claude/commands`, or the one in
 * `$CLAUDE_CONFIG_DIR`), so they show up under / in the chat, and, when Antigravity is installed, into its global
 * workflows (`~/.gemini/antigravity/global_workflows`). New commands are added and a Cursor command edited after its
 * copy is copied again; nothing is deleted. Runs before the CLI starts, so new commands appear in new chats.
 */
object CursorCommands {

    private val LOG = logger<CursorCommands>()

    /** Claude Code's own commands; a Cursor command with one of these names gets a suffix there. */
    private val CLAUDE_TAKEN = setOf(
        "simplify", "review", "init", "help", "clear", "compact", "config", "model", "memory", "login", "logout",
        "status", "cost", "doctor", "mcp", "agents", "hooks", "permissions", "resume", "exit", "context", "export",
        "plugin", "skills",
    )

    /** Where commands are copied to: the folder, how a name changes, and whether text typed after it is passed on. */
    data class Target(val dir: File, val rename: (String) -> String = { it }, val arguments: Boolean = true)

    private val home get() = File(System.getProperty("user.home"))

    fun cursorDir(): File = File(home, ".cursor/commands")

    fun claude(): Target = Target(
        System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { File(it, "commands") } ?: File(home, ".claude/commands"),
        rename = { if (it in CLAUDE_TAKEN) "$it-flow" else it },
    )

    /** Antigravity's global workflows, or null when Antigravity hasn't been run on this machine. */
    fun antigravity(): Target? = File(home, ".gemini/antigravity").takeIf { it.isDirectory }
        ?.let { Target(File(it, "global_workflows"), arguments = false) }

    /** Copies what's new or changed to Claude Code and Antigravity; returns the files written. Never throws. */
    fun syncAll(): List<File> {
        // Tests start chats too; they mustn't write to the real home folder.
        if (ApplicationManager.getApplication()?.isUnitTestMode != false) return emptyList()
        return listOfNotNull(claude(), antigravity()).flatMap { sync(cursorDir(), it) }
    }

    /** Copies what's new or changed in [from] to [to]; returns the files written. Never throws. */
    fun sync(from: File, to: Target): List<File> = runCatching {
        val sources = from.listFiles { f -> f.isFile && f.extension == "md" }.orEmpty().sortedBy { it.name }
        sources.mapNotNull { source ->
            val target = File(to.dir, "${to.rename(source.nameWithoutExtension)}.md")
            if (target.exists() && source.lastModified() <= target.lastModified()) return@mapNotNull null
            to.dir.mkdirs()
            target.writeText(convert(source.readText(), to.arguments))
            target
        }
    }.onFailure { LOG.warn("Couldn't copy Cursor's commands to ${to.dir}", it) }.getOrDefault(emptyList())

    /** A Cursor command without its `# name` title, described by its first line, as Claude Code and Antigravity read them. */
    fun convert(cursor: String, arguments: Boolean = true): String {
        val lines = cursor.replace("\r\n", "\n").lines()
        val body = (if (lines.firstOrNull()?.startsWith("# ") == true) lines.drop(1) else lines)
            .joinToString("\n").trim()
        val description = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().replace("\"", "").trim().let {
            if (it.length <= 100) it else it.take(100).substringBeforeLast(' ').trimEnd(',', '.', ' ') + "…"
        }
        return "---\ndescription: \"$description\"\n---\n\n$body\n" + if (arguments) "\n\$ARGUMENTS\n" else ""
    }
}
