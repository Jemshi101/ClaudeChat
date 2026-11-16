package `in`.bleuphoenix.claudechat.edit

/**
 * The prompt for an inline edit (Cursor's Cmd+K) and the reading of Claude's reply. Claude sees the file with the
 * selection marked and answers with only the new text for it, which the plugin applies itself (see
 * docs/PROTOCOL.md, "Inline edits").
 */
object InlineEditPrompt {

    /**
     * [text] is the whole file; [start]..[end] (character offsets) is the selection, empty for an insertion at the
     * caret. Long files are cut to [CONTEXT_LINES] lines around the selection.
     */
    fun build(path: String, language: String, text: String, start: Int, end: Int, instruction: String): String {
        val insert = start == end
        val marked = if (insert) {
            text.substring(0, start) + CURSOR + text.substring(start)
        } else {
            text.substring(0, start) + OPEN + text.substring(start, end) + CLOSE + text.substring(end)
        }
        val task = if (insert) {
            "Write code to insert where $CURSOR is in the file, as the instruction says, consistent with the rest of " +
                "the file. Reply with nothing but the code to insert, in one fenced code block, indented as it will " +
                "sit in the file. No explanation."
        } else {
            "Rewrite only the part of the file between $OPEN and $CLOSE as the instruction says, keeping it " +
                "consistent with the rest of the file. Reply with nothing but the new text for that part, in one " +
                "fenced code block, indented as it sits in the file. No explanation."
        }
        return "Edit code in the user's editor. $task\n\n" +
            "File: $path\n```$language\n${window(marked, insert)}\n```\n\n" +
            "Instruction: ${instruction.trim()}\n"
    }

    /**
     * The new text from Claude's [reply]: the first fenced code block's content, or the whole reply when there is no
     * fence. Null when it's empty. A selection's trailing line break is kept as it was, since replies drop it.
     */
    fun parseReply(reply: String, selected: String): String? {
        val fenced = FENCE.find(reply)?.groupValues?.get(1)
        val code = (fenced ?: reply.trim('\n')).removeSuffix("\n")
        if (code.isBlank()) return null
        return if (selected.endsWith("\n")) code + "\n" else code
    }

    /**
     * Fits [code] for insertion at [offset] in [text]. Claude indents it as it will sit in the file, so when the caret
     * is in a line's leading whitespace that indent isn't repeated, and when code follows on the line, it moves to
     * the next line with its indent.
     */
    fun fitInsertion(code: String, text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val before = text.substring(lineStart, offset)
        val after = text.substring(offset, lineEnd)
        if (before.isNotBlank()) return code
        var fitted = if (code.startsWith(before)) code.substring(before.length) else code.trimStart(' ', '\t')
        if (after.isNotBlank()) fitted = fitted.removeSuffix("\n") + "\n" + before
        return fitted
    }

    /** The lines around the selection markers when the file is long, with a note where lines were left out. */
    private fun window(marked: String, insert: Boolean): String {
        val lines = marked.lines()
        if (lines.size <= MAX_LINES) return marked
        val first = lines.indexOfFirst { (if (insert) CURSOR else OPEN) in it }
        val last = lines.indexOfFirst { (if (insert) CURSOR else CLOSE) in it }.coerceAtLeast(first)
        val from = (first - CONTEXT_LINES).coerceAtLeast(0)
        val to = (last + CONTEXT_LINES).coerceAtMost(lines.size - 1)
        val head = if (from > 0) listOf("// … ($from lines above not shown)") else emptyList()
        val tail = if (to < lines.size - 1) listOf("// … (${lines.size - 1 - to} lines below not shown)") else emptyList()
        return (head + lines.subList(from, to + 1) + tail).joinToString("\n")
    }

    const val OPEN = "<selection>"
    const val CLOSE = "</selection>"
    const val CURSOR = "<cursor/>"
    private const val MAX_LINES = 1500
    private const val CONTEXT_LINES = 300
    private val FENCE = Regex("""```[^\n]*\n(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)
}
