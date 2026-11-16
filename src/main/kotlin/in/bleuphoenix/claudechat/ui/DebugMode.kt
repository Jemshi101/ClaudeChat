package `in`.bleuphoenix.claudechat.ui

/**
 * Debug mode, as in Cursor: Claude lists hypotheses for a bug, adds logging to test them and stops with
 * `<reproduction_steps>`. The user reproduces the bug and presses Proceed; Claude reads the logs and fixes the cause it
 * shows. "Mark as fixed" has Claude remove the logging and goes back to Agent mode.
 */
object DebugMode {
    const val ID = "debug"

    /** The tag on the logging Claude adds, in the code and in logcat. */
    const val TAG = "ClaudeDebug"

    /** Cursor's message for Proceed. */
    const val PROCEED_MESSAGE = "Issue reproduced, please proceed"

    /** Cursor's message for Mark as fixed. */
    const val MARK_FIXED_MESSAGE = "The issue has been fixed. Please clean up the instrumentation."

    private val STEPS = Regex("""<reproduction_steps>([\s\S]*?)(?:</reproduction_steps>|$)""")

    /** The first message in Debug mode explains the method; later ones only remind Claude of it. */
    fun note(logFile: String) =
        "(Debug mode: find the cause of this bug from runtime evidence, not guesses.\n" +
            "1. List a few hypotheses for what could cause it.\n" +
            "2. Add temporary logging that would confirm or rule out each one, with `$TAG` in a comment on every line " +
            "or block you add, so it can all be found and removed later. In Android app code, log with " +
            "`android.util.Log.d(\"$TAG\", …)`; to read it, clear logcat with `adb logcat -c` before the user " +
            "reproduces the bug and read it with `adb logcat -d -s $TAG` after. For code that runs on this computer, " +
            "append lines to $logFile instead.\n" +
            "3. Then stop: give the steps to reproduce the bug inside <reproduction_steps></reproduction_steps> as a " +
            "short numbered list, and wait. Don't fix anything yet.\n" +
            "4. When I say I've reproduced it, read the logs and say which hypotheses they confirm or rule out. Fix the " +
            "cause only once the logs show it; otherwise add logging and give new reproduction steps. After a fix, " +
            "keep the logging and give reproduction steps to check it.\n" +
            "5. When I say it's fixed, remove all the `$TAG` logging and delete $logFile.)"

    const val REMINDER = "(Debug mode, as described earlier: find the cause from logs, and end with <reproduction_steps> when the user should reproduce the bug.)"

    /** The steps in [text]'s last finished `<reproduction_steps>` block, or null if it has none. */
    fun reproductionSteps(text: String): String? =
        STEPS.findAll(text).lastOrNull()?.takeIf { it.value.endsWith("</reproduction_steps>") }?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    /** [text] without its `<reproduction_steps>` blocks, which the bar above the message box shows instead. */
    fun withoutSteps(text: String): String =
        if ("<reproduction_steps>" in text) STEPS.replace(text, "").trimEnd() else text

    /**
     * The steps for the Proceed bar: in Debug mode, once Claude has stopped, from its last reply to the last message.
     */
    fun pendingSteps(state: ChatState): String? {
        if (state.mode.id != ID || state.busy || state.editing != null) return null
        for (item in state.items.asReversed()) {
            when (item) {
                is UserItem -> return null
                is AssistantTextItem -> reproductionSteps(item.text)?.let { return it }
                else -> Unit
            }
        }
        return null
    }
}
