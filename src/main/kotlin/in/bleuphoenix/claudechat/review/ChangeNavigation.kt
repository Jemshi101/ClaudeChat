package `in`.bleuphoenix.claudechat.review

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * Where F7 / Shift+F7 go among Claude's pending changes, as in the merge window: the next or previous change in the
 * file, and past the last (or first) one, the next (or previous) file with changes.
 */
object ChangeNavigation {

    /** A change to go to: [file]'s change number [index], which starts at the 0-based [line]. */
    data class Target(val file: FileReview, val index: Int, val line: Int)

    /** The line a change is shown at: its first added line, or where removed lines were. */
    fun lineOf(hunk: Hunk): Int = hunk.currentStart

    /**
     * The change the caret is on: one of its added lines, or for removed lines only, the line below the red block or
     * the one above it.
     */
    fun hunkAt(file: FileReview, caretLine: Int): Hunk? = file.hunks.firstOrNull { hunk ->
        if (hunk.added.isEmpty()) caretLine == hunk.currentStart || caretLine == hunk.currentStart - 1
        else caretLine in hunk.currentStart until hunk.currentStart + hunk.added.size
    }

    /**
     * The change after (or before, when not [forward]) [caretLine] in [current]; at either end, the first (or last)
     * change of the next (or previous) file in [pending], wrapping around. Null when there's nothing to go to.
     */
    fun target(pending: List<FileReview>, current: FileReview, caretLine: Int, forward: Boolean): Target? {
        val lines = current.hunks.map(::lineOf)
        val inFile = if (forward) lines.indexOfFirst { it > caretLine } else lines.indexOfLast { it < caretLine }
        if (inFile >= 0) return Target(current, inFile, lines[inFile])
        val files = pending.filter { it.hunks.isNotEmpty() }
        val at = files.indexOf(current)
        if (files.isEmpty()) return null
        val next = when {
            at < 0 -> files.first()
            forward -> files[(at + 1) % files.size]
            else -> files[(at - 1 + files.size) % files.size]
        }
        val index = if (forward) 0 else next.hunks.lastIndex
        return Target(next, index, lineOf(next.hunks[index]))
    }
}

/** F7 / Shift+F7 in an editor with Claude's pending changes. Disabled elsewhere, so F7 still steps into code. */
abstract class ChangeNavigationAction(private val forward: Boolean) : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = review(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (project, editor, file) = review(e) ?: return
        goTo(project, editor, file, editor.caretModel.logicalPosition.line, forward)
    }
}

/** The project, editor and review behind [e], when the editor's file has changes waiting. */
private fun review(e: AnActionEvent): Triple<Project, Editor, FileReview>? {
    val project = e.project ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val file = ReviewService.getInstance(project).reviewFor(editor.document) ?: return null
    return Triple(project, editor, file)
}

/** Moves to the change after (or before) [fromLine] in [file], or on to another file. */
private fun goTo(project: Project, editor: Editor, file: FileReview, fromLine: Int, forward: Boolean) {
    val service = ReviewService.getInstance(project)
    val target = ChangeNavigation.target(service.state.pending, file, fromLine, forward) ?: return
    if (target.file === file) {
        show(editor, target)
    } else {
        service.openAt(target.file, target.line)
        FileEditorManager.getInstance(project).selectedTextEditor?.let { show(it, target) }
    }
}

private fun show(editor: Editor, target: ChangeNavigation.Target) {
        val line = target.line.coerceIn(0, maxOf(0, editor.document.lineCount - 1))
        editor.selectionModel.removeSelection()
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        val count = target.file.hunks.size
        HintManager.getInstance().showInformationHint(editor, "Claude change ${target.index + 1} of $count")
}

/**
 * Accept (Cmd+Y) or Reject (Cmd+N) the change at the caret, as in Cursor, then go to the next one. Disabled when the
 * caret isn't on a change, so the keys keep their usual actions elsewhere.
 */
abstract class ChangeDecisionAction(private val accept: Boolean) : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val (_, editor, file) = review(e) ?: return run { e.presentation.isEnabledAndVisible = false }
        e.presentation.isEnabledAndVisible = ChangeNavigation.hunkAt(file, editor.caretModel.logicalPosition.line) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (project, editor, file) = review(e) ?: return
        val hunk = ChangeNavigation.hunkAt(file, editor.caretModel.logicalPosition.line) ?: return
        val service = ReviewService.getInstance(project)
        if (accept) service.acceptHunk(file, hunk) else service.rejectHunk(file, hunk)
        // Accepting and rejecting run on the next EDT turn; move on once the change is gone.
        ApplicationManager.getApplication().invokeLater({
            if (!editor.isDisposed) goTo(project, editor, file, ChangeNavigation.lineOf(hunk) - 1, forward = true)
        }, ModalityState.nonModal(), project.disposed)
    }
}

class AcceptClaudeChangeAction : ChangeDecisionAction(accept = true)

class RejectClaudeChangeAction : ChangeDecisionAction(accept = false)

class NextClaudeChangeAction : ChangeNavigationAction(forward = true)

class PreviousClaudeChangeAction : ChangeNavigationAction(forward = false)

/**
 * F7 is also the debugger's Step Into, Cmd+Y Quick Definition and Cmd+N Generate: where the review actions apply (they
 * are disabled elsewhere), they come first.
 */
class ChangeNavigationPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val ours = actions.filter { it is ChangeNavigationAction || it is ChangeDecisionAction }
        return ours + (actions - ours.toSet())
    }
}
