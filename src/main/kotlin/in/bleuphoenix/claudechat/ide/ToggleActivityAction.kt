package `in`.bleuphoenix.claudechat.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.bleuphoenix.claudechat.ui.ClaudeChatToolWindowFactory

/**
 * Opens or closes the activity strip above the chat's message box (tasks, background work, changed files and
 * commands). Bound to Cmd+Alt+J on macOS and Ctrl+Alt+' elsewhere, free in the IDE's keymaps.
 */
class ToggleActivityAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeChatToolWindowFactory.toggleActivity(project)
    }
}
