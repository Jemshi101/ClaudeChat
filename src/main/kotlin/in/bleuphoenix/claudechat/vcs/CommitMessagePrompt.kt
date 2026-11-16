package `in`.bleuphoenix.claudechat.vcs

import com.intellij.util.diff.Diff

/** One changed file for the commit message: its path, and its text before and after (null when added or deleted). */
data class FileChange(
    val path: String,
    val before: String?,
    val after: String?,
    val oldPath: String? = null,
    val binary: Boolean = false,
)

/** The `claude -p` request for a commit message: a unified diff of the changes, the user's draft and the repo's style. */
object CommitMessagePrompt {
    const val MAX_DIFF_CHARS = 60_000
    private const val CONTEXT = 3

    fun build(changes: List<FileChange>, draft: String?, recentSubjects: List<String>): String = buildString {
        append(
            """
            Write a git commit message for the changes below.
            - First line: a summary of at most 72 characters, in the imperative mood ("Add", "Fix"), with no trailing period.
            - Then, only if the change needs explaining, a blank line and a short body on what changed and why, wrapped at 72 characters.
            - Describe the change itself; don't list the files one by one or mention the diff.
            Reply with only the commit message: no quotes, code fences or commentary.
            """.trimIndent(),
        )
        if (recentSubjects.isNotEmpty()) {
            append("\n\nRecent commit subjects in this repository; follow their style (prefixes, ticket IDs, tense):\n")
            recentSubjects.forEach { append("- ").append(it).append('\n') }
        }
        draft?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("\n\nThe user's draft; keep its intent and any ticket IDs or prefixes it has:\n").append(it).append('\n')
        }
        append("\n\nChanges:\n\n").append(diff(changes))
    }

    /** Unified diffs of [changes], at most [MAX_DIFF_CHARS] in all; files past the limit are listed by path only. */
    fun diff(changes: List<FileChange>, limit: Int = MAX_DIFF_CHARS): String {
        val out = StringBuilder()
        val skipped = mutableListOf<String>()
        for (change in changes.sortedBy { it.path }) {
            val fileDiff = fileDiff(change)
            if (out.length + fileDiff.length <= limit) out.append(fileDiff)
            else skipped += change.path
        }
        if (skipped.isNotEmpty()) {
            out.append("\n(Diff too long; also changed: ").append(skipped.joinToString(", ")).append(")\n")
        }
        return out.toString()
    }

    fun fileDiff(change: FileChange): String = buildString {
        val from = if (change.before == null) "/dev/null" else "a/${change.oldPath ?: change.path}"
        val to = if (change.after == null) "/dev/null" else "b/${change.path}"
        append("--- ").append(from).append('\n').append("+++ ").append(to).append('\n')
        when {
            change.binary -> append("Binary file changed\n")
            change.oldPath != null && change.before == change.after -> append("Moved without changes\n")
            else -> append(hunks(change.before?.lines().orEmpty(), change.after?.lines().orEmpty()))
        }
    }

    /** The `@@` hunks turning [a] into [b], with [CONTEXT] lines around each change. */
    fun hunks(a: List<String>, b: List<String>): String {
        val changes = generateSequence(
            runCatching { Diff.buildChanges(a.toTypedArray(), b.toTypedArray()) }.getOrElse { return "(Too large to diff)\n" },
        ) { it.link }.toList()
        if (changes.isEmpty()) return ""
        val groups = mutableListOf(mutableListOf(changes.first()))
        for (change in changes.drop(1)) {
            val last = groups.last().last()
            if (change.line0 - (last.line0 + last.deleted) <= 2 * CONTEXT) groups.last() += change
            else groups += mutableListOf(change)
        }
        val out = StringBuilder()
        for (group in groups) {
            val first = group.first()
            val last = group.last()
            val start0 = (first.line0 - CONTEXT).coerceAtLeast(0)
            val end0 = (last.line0 + last.deleted + CONTEXT).coerceAtMost(a.size)
            val start1 = first.line1 - (first.line0 - start0)
            val end1 = last.line1 + last.inserted + (end0 - (last.line0 + last.deleted))
            out.append("@@ -${range(start0, end0 - start0)} +${range(start1, end1 - start1)} @@\n")
            var i = start0
            for (change in group) {
                while (i < change.line0) out.append(' ').append(a[i++]).append('\n')
                repeat(change.deleted) { out.append('-').append(a[change.line0 + it]).append('\n') }
                repeat(change.inserted) { out.append('+').append(b[change.line1 + it]).append('\n') }
                i = change.line0 + change.deleted
            }
            while (i < end0) out.append(' ').append(a[i++]).append('\n')
        }
        return out.toString()
    }

    // As git writes it: an empty range names the line before it.
    private fun range(start: Int, count: Int) = if (count == 0) "$start,0" else "${start + 1},$count"

    /** Claude's reply without code fences or wrapping quotes, as the commit box should show it. */
    fun clean(reply: String): String {
        var text = reply.trim()
        if (text.startsWith("```")) text = text.substringAfter('\n', "").substringBeforeLast("```").trim()
        if (text.length > 1 && text.first() == text.last() && text.first() in "\"'`") text = text.substring(1, text.length - 1).trim()
        return text
    }
}
