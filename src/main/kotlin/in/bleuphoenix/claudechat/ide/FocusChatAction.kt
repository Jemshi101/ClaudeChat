package `in`.bleuphoenix.claudechat.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.bleuphoenix.claudechat.ui.ClaudeChatToolWindowFactory

/**
 * Cursor's Cmd+L: moves the caret to the chat's message box, adding the editor's selected lines to its context first.
 * Bound to Cmd+Shift+J on macOS and Ctrl+Shift+; elsewhere (Cmd+L is Go to Line in Android Studio).
 */
class FocusChatAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = AskClaudeAboutSelectionAction.selectedLines(e)
        if (selection != null) {
            ClaudeChatToolWindowFactory.attachToChat(project, selection.first, selection.second)
        } else {
            ClaudeChatToolWindowFactory.focusChat(project)
        }
    }
}
