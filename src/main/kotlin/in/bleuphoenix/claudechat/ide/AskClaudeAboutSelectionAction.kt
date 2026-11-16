package `in`.bleuphoenix.claudechat.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import `in`.bleuphoenix.claudechat.ui.ClaudeChatToolWindowFactory

/** Editor context-menu action: adds the selected lines to the Claude chat's context, as a chip like `Main.kt:12-20`. */
class AskClaudeAboutSelectionAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (path, lines) = selectedLines(e) ?: return
        ClaudeChatToolWindowFactory.attachToChat(project, path, lines)
    }

    companion object {
        /** The file and line range selected in the event's editor, or null without a selection. */
        fun selectedLines(e: AnActionEvent): Pair<String, IntRange>? {
            val project = e.project ?: return null
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: editor.virtualFile ?: return null
            val selection = editor.selectionModel
            if (!selection.hasSelection()) return null

            val document = editor.document
            val startLine = document.getLineNumber(selection.selectionStart) + 1
            // A selection ending at the start of a line doesn't include that line.
            val endOffset = (selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)
            val endLine = document.getLineNumber(endOffset) + 1
            return IdeBridge(project).displayPath(file) to startLine..endLine
        }
    }
}
