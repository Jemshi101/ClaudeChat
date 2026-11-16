package `in`.bleuphoenix.claudechat.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.io.File
import java.nio.charset.StandardCharsets

/** Locates the `claude` executable and builds the stream-json command line (see docs/PROTOCOL.md). */
object ClaudeCli {

    /** Appended to Claude's system prompt so plans carry a flow diagram, as in Cursor. */
    const val PLAN_DIAGRAM_PROMPT =
        "When you present a plan with ExitPlanMode, include in the plan markdown a Mermaid flowchart " +
            "(a ```mermaid block starting with `flowchart TD`) showing the main flow of the change, placed after the " +
            "overview. Keep it to about 4 to 12 nodes with short labels, and use decision diamonds for branches."

    /** The Plan tab draws a closing task list as a to-do card that can be ticked off (see TodosView). */
    const val PLAN_TODOS_PROMPT =
        "When you present a plan with ExitPlanMode, end the plan markdown with a `## To-dos` section: a markdown task " +
            "list (`- [ ] ...`), one line per concrete step to carry out, in order, usually 3 to 8. When you revise a " +
            "plan, keep its to-dos in step with it. When you carry out an approved plan, track it with your task tools: " +
            "create one task per to-do, using the to-do's text as the task's subject, and mark each task completed as " +
            "soon as that step is done; the plan's to-dos are ticked off from them."

    /** Bold is what the chat makes stand out (see ModernMarkdown), so ask for it where it helps a reader skim. */
    const val EMPHASIS_PROMPT =
        "Your replies and plans are shown in a chat panel that renders markdown. Help the reader skim: put the key " +
            "facts in **bold** (the conclusion or decision, the file or component to change, the root cause, risks " +
            "and things not to do), and start list items that name a step or finding with a short bold lead-in. " +
            "Bold a few words per paragraph at most, never whole sentences."

    /**
     * Returns [configuredPath] if it's set (null when it isn't executable, rather than silently using another
     * install), otherwise the first `claude` found in the usual locations.
     */
    fun findExecutable(configuredPath: String? = ClaudeChatSettings.options.cliPath): String? {
        if (!configuredPath.isNullOrBlank()) return configuredPath.takeIf { File(it).canExecute() }
        // IDEs launched from the Dock don't inherit the shell PATH, so check the usual install locations first.
        return defaultLocations().firstOrNull { File(it).canExecute() }
            ?: EXECUTABLE_NAMES.firstNotNullOfOrNull { findInPath(it) }
            ?: if (SystemInfo.isWindows) desktopAppCli() else null
    }

    /** [name] in one of the `PATH`'s folders, the way a shell finds it. */
    fun findInPath(name: String, path: String? = System.getenv("PATH")): String? = path.orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .map { File(it.trim().removeSurrounding("\""), name) }
        .firstOrNull { it.isFile && it.canExecute() }?.path

    /**
     * The Claude Code the Claude desktop app keeps for itself on Windows, used when there's no install of its own:
     * `%LOCALAPPDATA%\Claude-Data\<id>\claude-code\<version>\<build>\claude.exe`. Each update adds a version
     * folder, so this takes the newest one rather than a fixed path.
     */
    fun desktopAppCli(localAppData: String? = System.getenv("LOCALAPPDATA")): String? {
        val data = localAppData?.let { File(it, "Claude-Data") } ?: return null
        return data.listFiles().orEmpty()
            .flatMap { File(it, "claude-code").listFiles().orEmpty().filter(File::isDirectory) }
            .flatMap { version -> version.listFiles().orEmpty().map { build -> version.name to File(build, "claude.exe") } }
            .filter { (_, exe) -> exe.isFile }
            .maxWithOrNull(compareBy<Pair<String, File>, List<Int>>(VERSION_ORDER) { (version, _) -> version.split('.').map { it.toIntOrNull() ?: 0 } }.thenBy { it.second.lastModified() })
            ?.second?.path
    }

    /** Compares "2.1.308"-style versions number by number. */
    private val VERSION_ORDER = Comparator<List<Int>> { a, b ->
        (0 until maxOf(a.size, b.size)).map { (a.getOrNull(it) ?: 0).compareTo(b.getOrNull(it) ?: 0) }.firstOrNull { it != 0 } ?: 0
    }

    /**
     * Where the installers put `claude`, most likely first. On Windows: the native installer's
     * `%USERPROFILE%\.local\bin\claude.exe`, then npm's global `%APPDATA%\npm\claude.cmd`. Elsewhere: the native
     * installer's `~/.local/bin`, then Homebrew's.
     */
    fun defaultLocations(
        windows: Boolean = SystemInfo.isWindows,
        home: String = System.getProperty("user.home"),
        appData: String? = System.getenv("APPDATA"),
    ): List<String> =
        if (windows) {
            listOfNotNull("$home\\.local\\bin\\claude.exe", appData?.let { "$it\\npm\\claude.cmd" })
        } else {
            listOf("$home/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude")
        }

    /** What `claude` is called on the PATH: on Windows the native installer's .exe or npm's .cmd shim. */
    private val EXECUTABLE_NAMES = if (SystemInfo.isWindows) listOf("claude.exe", "claude.cmd") else listOf("claude")

    fun commandLine(
        executable: String,
        workDir: String,
        resumeSessionId: String?,
        permissionMode: String?,
        model: String?,
        appendSystemPrompt: String? = null,
        /** With [resumeSessionId]: continue a copy of the session that ends at this message (see docs/PROTOCOL.md). */
        forkAtMessage: String? = null,
        /** `--tools` value; null for the CLI's default set. */
        tools: String? = null,
        /** `--effort` level; null for the CLI's default. */
        effort: String? = null,
        /** `--max-turns`; null or 0 for no limit. */
        maxTurns: Int? = null,
    ): GeneralCommandLine {
        val args = mutableListOf(
            executable,
            "-p",
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--permission-prompts", "host",
            "--permission-prompt-tool", "stdio",
        )
        if (resumeSessionId != null) args += listOf("--resume", resumeSessionId)
        if (resumeSessionId != null && forkAtMessage != null) {
            args += listOf("--resume-session-at", forkAtMessage, "--fork-session")
        }
        if (permissionMode != null) args += listOf("--permission-mode", permissionMode)
        if (model != null) args += listOf("--model", model)
        if (appendSystemPrompt != null) args += listOf("--append-system-prompt", appendSystemPrompt)
        if (tools != null) args += listOf("--tools", tools)
        if (effort != null) args += listOf("--effort", effort)
        if (maxTurns != null && maxTurns > 0) args += listOf("--max-turns", maxTurns.toString())
        return GeneralCommandLine(args)
            .withWorkDirectory(workDir)
            // CONSOLE loads the login-shell environment on macOS, so tools Claude runs (git, gradle, ...) resolve.
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
    }
}
