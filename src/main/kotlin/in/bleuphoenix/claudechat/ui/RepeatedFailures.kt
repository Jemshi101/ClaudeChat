package `in`.bleuphoenix.claudechat.ui

/**
 * Runs of the same failure in a row, shown once with a count: tool calls with the same tool and input that all
 * failed (Claude retrying a command that keeps failing), or the same error notice. Only the chat's display changes;
 * the items, the activity strip and the history keep every call.
 */
object RepeatedFailures {

    /**
     * For each item: 0 when it's hidden (an earlier repeat in a run), otherwise how many times it happened in a row,
     * shown on the last of the run (1 for an item that isn't repeated).
     */
    fun counts(items: List<ChatItem>): IntArray {
        val counts = IntArray(items.size) { 1 }
        var runStart = 0
        for (index in 1..items.size) {
            val ended = index == items.size || !sameFailure(items[index - 1], items[index])
            if (!ended) continue
            val length = index - runStart
            if (length > 1) {
                for (hidden in runStart until index - 1) counts[hidden] = 0
                counts[index - 1] = length
            }
            runStart = index
        }
        return counts
    }

    private fun sameFailure(a: ChatItem, b: ChatItem): Boolean = when {
        a is ToolItem && b is ToolItem ->
            a.status == ToolStatus.Failed && b.status == ToolStatus.Failed && a.agent == null && b.agent == null &&
                a.name == b.name && a.inputJson == b.inputJson
        a is NoticeItem && b is NoticeItem -> a.isError && b.isError && a.text == b.text
        else -> false
    }
}
