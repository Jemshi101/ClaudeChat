package `in`.bleuphoenix.claudechat.ui

/**
 * A chat mode with a readable label and a sentence on what Claude may do without asking. [cliId] is the CLI permission
 * mode it runs in (`--permission-mode`); it's [id] except for Ask, which the chat keeps
 * read-only itself, and Debug, which is Agent with its own instructions.
 */
data class PermissionMode(val id: String, val label: String, val description: String = "", val cliId: String = id) {

    companion object {
        /**
         * Modes offered in the selector. bypassPermissions and dontAsk are left out on purpose:
         * one skips every prompt, the other silently denies anything that would prompt.
         */
        val CHOICES = listOf(
            PermissionMode("manual", "Agent", "You approve each edit and command."),
            PermissionMode("acceptEdits", "Agent · auto-edit", "File edits apply without asking, for you to review after. Commands still ask."),
            PermissionMode("plan", "Plan", "It reads and plans first; nothing changes until you approve the plan."),
            // As in Cursor: questions about the code, without edits or commands (see ChatPanel.declineInAskMode).
            PermissionMode("ask", "Ask", "It answers questions from the code; it doesn't edit files or run commands.", cliId = "manual"),
            // As in Cursor: a bug's cause from logs Claude adds and the user's reproduction (see DebugMode).
            PermissionMode(DebugMode.ID, "Debug", "It adds logging, you reproduce the bug, and it fixes the cause the logs show.", cliId = "manual"),
            PermissionMode("auto", "Auto", "It runs most actions without asking."),
        )

        /** The mode after [current] when cycling with Shift+Tab. */
        fun next(current: PermissionMode): PermissionMode {
            val index = CHOICES.indexOfFirst { it.id == current.id }
            return CHOICES[(index + 1).mod(CHOICES.size)]
        }

        /** The mode after the CLI reports [cliMode]: [current] if that's the mode it runs in (Ask runs as manual). */
        fun reported(current: PermissionMode, cliMode: String): PermissionMode =
            fromCli(cliMode).takeUnless { it.cliId == current.cliId } ?: current

        /** Maps a mode reported by the CLI; it reports "manual" as "default". */
        fun fromCli(id: String): PermissionMode {
            val normalized = if (id == "default") "manual" else id
            return CHOICES.firstOrNull { it.id == normalized } ?: PermissionMode(normalized, normalized)
        }
    }
}
