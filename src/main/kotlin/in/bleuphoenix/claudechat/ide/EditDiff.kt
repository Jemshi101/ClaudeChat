package `in`.bleuphoenix.claudechat.ide

import com.google.gson.JsonObject

/** Reconstructs before/after text for Edit and MultiEdit calls from the file's current text. */
object EditDiff {

    data class Edit(val old: String, val new: String, val replaceAll: Boolean) {
        companion object {
            fun from(input: JsonObject) = Edit(
                old = input.str("old_string").orEmpty(),
                new = input.str("new_string").orEmpty(),
                replaceAll = input.get("replace_all")?.asBoolean == true,
            )
        }
    }

    fun beforeAfter(current: String, edits: List<Edit>): Pair<String, String> {
        if (edits.isEmpty()) return current to current
        applyForward(current, edits)?.let { return current to it }
        applyBackward(current, edits)?.let { return it to current }
        // The file matches neither side (it changed since); show just the edited fragments.
        return edits.joinToString("\n…\n") { it.old } to edits.joinToString("\n…\n") { it.new }
    }

    /** Not yet applied: the file still contains every old string. */
    private fun applyForward(current: String, edits: List<Edit>): String? {
        var text = current
        for (edit in edits) {
            text = when {
                edit.old.isEmpty() && text.isEmpty() -> edit.new // creating a new file
                edit.old.isEmpty() || edit.old !in text -> return null
                edit.replaceAll -> text.replace(edit.old, edit.new)
                else -> text.replaceFirst(edit.old, edit.new)
            }
        }
        return text
    }

    /** Already applied: undo the edits in reverse order to recover the earlier text. */
    private fun applyBackward(current: String, edits: List<Edit>): String? {
        var text = current
        for (edit in edits.asReversed()) {
            // An empty new string (a deletion) can't be located, so the earlier text can't be recovered.
            if (edit.new.isEmpty() || edit.new !in text) return null
            text = if (edit.replaceAll) text.replace(edit.new, edit.old) else text.replaceFirst(edit.new, edit.old)
        }
        return text
    }
}
