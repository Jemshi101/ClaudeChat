package `in`.bleuphoenix.claudechat.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Claude's changes waiting for review in one project, file by file. Each file keeps a baseline (its text before
 * Claude's first unreviewed edit); its hunks are the differences between that and the current text. Mutated on the
 * EDT by [ReviewService]; Compose recomposes from it.
 */
class ReviewState {
    val files = mutableStateListOf<FileReview>()

    fun find(path: String): FileReview? = files.firstOrNull { it.path == path }

    /** Files that still have changes to review, in the order Claude first changed them. */
    val pending: List<FileReview> get() = files.filter { it.hunks.isNotEmpty() }
}

/** One file under review. [created] means Claude made the file; its baseline is empty. */
class FileReview(val path: String, baseline: String, val created: Boolean) {
    var baseline by mutableStateOf(baseline)
    var current by mutableStateOf<String?>(null)
    var hunks by mutableStateOf<List<Hunk>>(emptyList())

    /** Whether the chat's Claude made changes here, so it's told when they're rejected (not for inline edits only). */
    var fromChat = true

    val name: String get() = path.substringAfterLast('/')
    val added: Int get() = hunks.sumOf { it.added.size }
    val removed: Int get() = hunks.sumOf { it.removed.size }

    /** Recomputes the hunks from [baseline] and [text], the file's current content. */
    fun update(text: String) {
        current = text
        hunks = ReviewText.hunks(baseline, text)
    }
}

/** What the review tab and the chat's changes card can ask for. Implemented by [ReviewService]; call on the EDT. */
interface ReviewActions {
    fun acceptHunk(file: FileReview, hunk: Hunk)
    fun rejectHunk(file: FileReview, hunk: Hunk)
    fun acceptFiles(files: List<FileReview>)
    fun rejectFiles(files: List<FileReview>)

    /** Opens [file] in the editor at the 0-based [line]. */
    fun openAt(file: FileReview, line: Int)
}
