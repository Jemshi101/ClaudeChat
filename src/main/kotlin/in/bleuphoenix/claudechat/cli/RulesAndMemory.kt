package `in`.bleuphoenix.claudechat.cli

import com.google.gson.JsonParser
import `in`.bleuphoenix.claudechat.history.ChatHistory
import java.io.File

/** What a rules or memory file is to Claude, for the Rules & Memory page. */
enum class RuleKind(val label: String) {
    USER("Rules · all projects"),
    PROJECT("Rules · this project"),
    PARENT("Rules · parent folder"),
    LOCAL("Rules · only you"),
    RULES_FOLDER("Rules folder"),
    INSTRUCTION("Instruction file"),
    MEMORY_INDEX("Memory index"),
    MEMORY("Memory"),
}

/**
 * A file Claude reads at the start of every chat in the project. [exists] is false for the files a rule can be added
 * to but that haven't been created yet; [loaded] is false for CLAUDE.md files the Instruction files setting replaces.
 */
data class RuleFile(val kind: RuleKind, val file: File, val about: String, val exists: Boolean = true, val loaded: Boolean = true)

/**
 * Finds the instructions and memory Claude Code loads for a project: CLAUDE.md files (yours, the project's and its
 * parent folders'), CLAUDE.local.md, `.claude/rules`, the Instruction files that replace them, and the project's auto
 * memory (`~/.claude/projects/<project path>/memory`, or the `autoMemoryDirectory` setting).
 */
object RulesAndMemory {

    fun configDir(): File =
        System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".claude")

    fun list(workDir: String, instructionFiles: List<File> = emptyList(), configDir: File = configDir()): List<RuleFile> {
        val start = File(workDir).absoluteFile
        val claudeMdLoaded = instructionFiles.isEmpty()
        val result = mutableListOf<RuleFile>()
        fun add(kind: RuleKind, file: File, always: Boolean = false, loaded: Boolean = true) {
            if (file.isFile) result += RuleFile(kind, file, about(file), loaded = loaded)
            else if (always) result += RuleFile(kind, file, "Not created yet", exists = false, loaded = loaded)
        }

        add(RuleKind.USER, File(configDir, "CLAUDE.md"), always = true, loaded = claudeMdLoaded)
        rulesFolder(File(configDir, "rules")).forEach { add(RuleKind.RULES_FOLDER, it) }
        add(RuleKind.PROJECT, File(start, "CLAUDE.md"), always = !File(start, ".claude/CLAUDE.md").isFile, loaded = claudeMdLoaded)
        add(RuleKind.PROJECT, File(start, ".claude/CLAUDE.md"), loaded = claudeMdLoaded)
        add(RuleKind.LOCAL, File(start, "CLAUDE.local.md"), loaded = claudeMdLoaded)
        rulesFolder(File(start, ".claude/rules")).forEach { add(RuleKind.RULES_FOLDER, it) }
        generateSequence(start.parentFile) { it.parentFile }.forEach { folder ->
            add(RuleKind.PARENT, File(folder, "CLAUDE.md"), loaded = claudeMdLoaded)
            add(RuleKind.PARENT, File(folder, ".claude/CLAUDE.md"), loaded = claudeMdLoaded)
            add(RuleKind.LOCAL, File(folder, "CLAUDE.local.md"), loaded = claudeMdLoaded)
        }
        instructionFiles.forEach { add(RuleKind.INSTRUCTION, it) }

        val memory = memoryDir(workDir, configDir)
        add(RuleKind.MEMORY_INDEX, File(memory, "MEMORY.md"), always = true)
        memory.listFiles { f -> f.isFile && f.extension == "md" && f.name != "MEMORY.md" }.orEmpty()
            .sortedBy { it.name }.forEach { add(RuleKind.MEMORY, it) }
        // The same file reached twice (a symlinked folder, the user folder being a parent) is listed once.
        return result.distinctBy { runCatching { it.file.canonicalPath }.getOrDefault(it.file.path) }
    }

    /** The project's auto-memory folder; it may not exist yet. */
    fun memoryDir(workDir: String, configDir: File = configDir()): File {
        val custom = listOf(File(configDir, "settings.json"), File(workDir, ".claude/settings.local.json"))
            .firstNotNullOfOrNull(::autoMemoryDirectory)
        if (custom != null) return custom
        val root = gitRoot(File(workDir).absoluteFile) ?: File(workDir).absoluteFile
        return File(configDir, "projects/${ChatHistory.directoryName(root.path)}/memory")
    }

    /** The repository root holding [folder] (the nearest folder with a `.git`), or null outside a repository. */
    fun gitRoot(folder: File): File? = generateSequence(folder) { it.parentFile }.firstOrNull { File(it, ".git").exists() }

    /** Appends [rule] to [file] as a bullet, creating it (and its folder) if needed. */
    fun addRule(file: File, rule: String) {
        val text = rule.trim().lines().joinToString("\n") { it.trimEnd() }
        if (text.isEmpty()) return
        val bullet = if (text.startsWith("-") || text.startsWith("*") || text.startsWith("#")) text else "- $text"
        val before = if (file.isFile) file.readText() else ""
        file.parentFile?.mkdirs()
        val separator = when {
            before.isEmpty() -> ""
            before.endsWith("\n") -> ""
            else -> "\n"
        }
        file.writeText(before + separator + bullet + "\n")
    }

    private fun rulesFolder(dir: File): List<File> =
        dir.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "md" }?.sortedBy { it.path }?.toList().orEmpty()

    private fun autoMemoryDirectory(settings: File): File? = runCatching {
        if (!settings.isFile) return null
        val value = JsonParser.parseString(settings.readText()).asJsonObject.get("autoMemoryDirectory")
            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("~/")) File(System.getProperty("user.home"), value.removePrefix("~/")) else File(value)
    }.getOrNull()

    /** A memory file's `description:`, or the first heading or line, and how long the file is. */
    fun about(file: File): String = runCatching {
        val text = file.readText().replace("\r\n", "\n")
        val lines = text.lines()
        val described = if (text.startsWith("---\n")) {
            lines.drop(1).takeWhile { it.trim() != "---" }.firstOrNull { it.trimStart().startsWith("description:") }
                ?.substringAfter(':')?.trim()?.trim('"', '\'')
        } else {
            null
        }
        val first = described ?: lines.asSequence()
            .dropWhileFrontmatter()
            .map { it.trim().removePrefix("#").trim() }
            .firstOrNull { it.isNotEmpty() }
        val count = lines.count { it.isNotBlank() }
        listOfNotNull(first?.let { if (it.length > 120) it.take(119) + "…" else it }, if (count == 1) "1 line" else "$count lines")
            .joinToString(" · ")
    }.getOrDefault("")

    private fun Sequence<String>.dropWhileFrontmatter(): Sequence<String> {
        val list = toList()
        if (list.firstOrNull() != "---") return list.asSequence()
        val end = list.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end < 0) list.asSequence() else list.drop(end + 2).asSequence()
    }
}
