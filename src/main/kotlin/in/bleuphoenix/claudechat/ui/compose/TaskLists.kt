package `in`.bleuphoenix.claudechat.ui.compose

/**
 * Markdown task lists ("- [ ] step", "- [x] done"): splitting them out of text as to-do cards, and ticking or adding
 * tasks in the document. Tasks are numbered in document order, skipping fenced code blocks.
 */
object TaskLists {

    data class Task(val text: String, val done: Boolean)

    private val TASK = Regex("""^\s{0,3}[-*+]\s+\[([ xX])]\s+(.*)$""")
    private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})""")

    /** A "To-dos" heading right above a task list is drawn as the card's own header instead. */
    private val TODO_HEADING = Regex("""^#{1,6}\s*(to-?dos?|tasks?|checklist)\s*:?\s*$""", RegexOption.IGNORE_CASE)

    /** [markdown] with its task lists as [MarkdownSegment.Todos], numbered from [firstIndex]. */
    fun split(markdown: String, firstIndex: Int): List<MarkdownSegment> {
        val lines = markdown.lines()
        val segments = mutableListOf<MarkdownSegment>()
        val text = mutableListOf<String>()
        var index = firstIndex
        fun flushText() {
            text.joinToString("\n").takeIf { it.isNotBlank() }?.let { segments += MarkdownSegment.Text(it) }
            text.clear()
        }
        var i = 0
        while (i < lines.size) {
            if (TASK.matchEntire(lines[i]) == null) {
                text += lines[i++]
                continue
            }
            val items = mutableListOf<Task>()
            while (i < lines.size) {
                val task = TASK.matchEntire(lines[i]) ?: break
                items += Task(task.groupValues[2].trim(), task.groupValues[1] != " ")
                i++
            }
            // Drop a "## To-dos" heading (and blank lines) just above: the card shows its own.
            val headingAt = text.indexOfLast { it.isNotBlank() }
            if (headingAt >= 0 && TODO_HEADING.matches(text[headingAt].trim())) {
                while (text.size > headingAt) text.removeAt(text.lastIndex)
            }
            flushText()
            segments += MarkdownSegment.Todos(items, index)
            index += items.size
        }
        flushText()
        return segments
    }

    /** The document's tasks, in order (outside code blocks). */
    fun tasks(markdown: String): List<Task> {
        val lines = markdown.lines()
        return taskLines(lines).map { i -> TASK.matchEntire(lines[i])!!.let { Task(it.groupValues[2].trim(), it.groupValues[1] != " ") } }
    }

    /** [markdown] with task [index] ticked (unchanged if it already is). */
    fun complete(markdown: String, index: Int): String =
        if (tasks(markdown).getOrNull(index)?.done == false) toggle(markdown, index) else markdown

    /**
     * The open task that [subject] (a task Claude tracked while building the plan) stands for: the same text, or else
     * the one sharing most of its words (at least half). Null if none is close enough.
     */
    fun match(tasks: List<Task>, subject: String): Int? {
        val wanted = words(subject)
        if (wanted.isEmpty()) return null
        return tasks.withIndex()
            .filter { !it.value.done }
            .map { (index, task) ->
                val have = words(task.text)
                val score = if (have == wanted) 2.0 else (have intersect wanted).size.toDouble() / (have union wanted).size
                index to score
            }
            .filter { it.second >= 0.5 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()

    /** [markdown] with task [index] ticked or unticked. */
    fun toggle(markdown: String, index: Int): String = editTask(markdown, index) { line ->
        val box = line.indexOf('[')
        val done = line[box + 1] != ' '
        line.substring(0, box + 1) + (if (done) " " else "x") + line.substring(box + 2)
    }

    /** [markdown] with a new, open task after the last one (or at the end, under a "To-dos" heading, if there's none). */
    fun add(markdown: String, text: String): String {
        val lines = markdown.lines().toMutableList()
        val last = taskLines(lines).lastOrNull()
        val task = "- [ ] ${text.trim()}"
        if (last != null) {
            lines.add(last + 1, task)
        } else {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines += listOf("", "## To-dos", "", task, "")
        }
        return lines.joinToString("\n")
    }

    private fun editTask(markdown: String, index: Int, edit: (String) -> String): String {
        val lines = markdown.lines().toMutableList()
        val at = taskLines(lines).getOrNull(index) ?: return markdown
        lines[at] = edit(lines[at])
        return lines.joinToString("\n")
    }

    /** The line numbers of the task lines, outside fenced code blocks, in order. */
    private fun taskLines(lines: List<String>): List<Int> {
        var fence: String? = null
        return lines.indices.filter { i ->
            val line = lines[i]
            val marker = FENCE.find(line)?.groupValues?.get(1)
            when {
                fence != null -> {
                    if (marker != null && marker[0] == fence!![0] && marker.length >= fence!!.length) fence = null
                    false
                }
                marker != null -> {
                    fence = marker
                    false
                }
                else -> TASK.matchEntire(line) != null
            }
        }
    }
}
