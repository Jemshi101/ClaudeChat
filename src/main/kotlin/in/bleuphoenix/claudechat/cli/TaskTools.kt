package `in`.bleuphoenix.claudechat.cli

/**
 * The CLI's task-tracking tools (TaskCreate, TaskUpdate, ...) are left out of its default tool set in `-p` mode, and
 * `--tools` can only replace that set, not add to it (see docs/PROTOCOL.md). So the chat learns the default set from
 * a session started without `--tools`, and later passes it back with the task tools added.
 */
object TaskTools {

    val NAMES = listOf("TaskCreate", "TaskGet", "TaskList", "TaskUpdate")

    /**
     * The `--tools` value for a new session, or null to start with the CLI's default set (and learn it). [known] was
     * learned from CLI [knownVersion]; [currentVersion] is the version the last session reported, so a CLI update
     * is followed by one session that refreshes the set.
     */
    fun toolsArgument(enabled: Boolean, known: String?, knownVersion: String?, currentVersion: String?): String? {
        if (!enabled || known.isNullOrBlank() || knownVersion != currentVersion) return null
        return (known.split(',') + NAMES).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
    }

    /** The built-in tools from an `init` event's tool list (MCP tools aren't affected by `--tools`). */
    fun builtIn(tools: List<String>): String = tools.filterNot { it.startsWith("mcp__") }.sorted().joinToString(",")
}
