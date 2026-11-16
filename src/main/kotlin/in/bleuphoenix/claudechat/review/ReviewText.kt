package `in`.bleuphoenix.claudechat.review

import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException

/**
 * One changed block between a file's baseline (its text before Claude's edits) and its current text:
 * baseline lines from [baseStart] ([removed]) became current lines from [currentStart] ([added]). 0-based.
 */
data class Hunk(val baseStart: Int, val currentStart: Int, val removed: List<String>, val added: List<String>)

/** A replacement of the text range [start, end) with [replacement]. */
data class TextEdit(val start: Int, val end: Int, val replacement: String)

/**
 * Line-based diff and per-hunk accept/reject on plain text. Lines are split on '\n' only, so joining them again
 * gives back the exact text (a trailing newline shows up as a final empty line).
 */
object ReviewText {

    fun lines(text: String): List<String> = text.split('\n')

    fun hunks(baseline: String, current: String): List<Hunk> {
        if (baseline == current) return emptyList()
        val base = lines(baseline)
        val now = lines(current)
        val changes = try {
            Diff.buildChanges(base.toTypedArray(), now.toTypedArray())
        } catch (_: FilesTooBigForDiffException) {
            return listOf(Hunk(0, 0, base, now))
        } ?: return emptyList()
        return changes.toList().map { change ->
            Hunk(
                baseStart = change.line0,
                currentStart = change.line1,
                removed = base.subList(change.line0, change.line0 + change.deleted),
                added = now.subList(change.line1, change.line1 + change.inserted),
            )
        }
    }

    /** The baseline with [hunk] taken in: once accepted, the change no longer shows as a difference. */
    fun accept(baseline: String, hunk: Hunk): String = replace(lines(baseline), hunk.baseStart, hunk.removed.size, hunk.added)

    /** The current text with [hunk] put back to the baseline. */
    fun reject(current: String, hunk: Hunk): String = replace(lines(current), hunk.currentStart, hunk.added.size, hunk.removed)

    /** Whether [hunk] still describes [current], i.e. the file hasn't changed under it since it was computed. */
    fun matches(current: String, hunk: Hunk): Boolean {
        val now = lines(current)
        val end = hunk.currentStart + hunk.added.size
        return end <= now.size && now.subList(hunk.currentStart, end) == hunk.added
    }

    /** The smallest single edit that turns [old] into [new], so the editor keeps the caret and undo stays small. */
    fun minimalEdit(old: String, new: String): TextEdit {
        val prefix = old.commonPrefixWith(new).length
        val maxSuffix = minOf(old.length, new.length) - prefix
        var suffix = 0
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return TextEdit(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
    }

    private fun replace(lines: List<String>, start: Int, count: Int, with: List<String>): String =
        (lines.subList(0, start) + with + lines.subList(start + count, lines.size)).joinToString("\n")
}
