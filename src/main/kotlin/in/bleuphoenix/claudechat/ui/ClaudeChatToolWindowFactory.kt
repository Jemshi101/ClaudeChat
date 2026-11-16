package `in`.bleuphoenix.claudechat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/** The Claude tool window: one content holding [ChatTabs], which draws its own tabs with close buttons. */
class ClaudeChatToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabs = createTabs(project, toolWindow)
        toolWindow.setTitleActions(listOf(NewChatAction(tabs), HistoryAction(tabs)))
        toolWindow.setAdditionalGearActions(DefaultActionGroup(CloseChatAction(tabs), CloseOtherChatsAction(tabs)))
        tabs.add()
    }

    private class NewChatAction(private val tabs: ChatTabs) :
        DumbAwareAction("New Chat", "Start a new Claude chat", AllIcons.General.Add) {

        override fun actionPerformed(e: AnActionEvent) {
            tabs.add()
        }
    }

    private class HistoryAction(private val tabs: ChatTabs) :
        DumbAwareAction("Chat History", "Show past chats in this project", AllIcons.Vcs.History) {

        override fun actionPerformed(e: AnActionEvent) {
            tabs.selectedPanel?.openHistory()
        }
    }

    private class CloseChatAction(private val tabs: ChatTabs) :
        DumbAwareAction("Close Chat", "Close this chat and stop its Claude process", AllIcons.Actions.Close) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            tabs.selected?.let(tabs::close)
        }
    }

    private class CloseOtherChatsAction(private val tabs: ChatTabs) :
        DumbAwareAction("Close Other Chats", "Close every chat but this one", null) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = tabs.tabs.size > 1
        }

        override fun actionPerformed(e: AnActionEvent) {
            tabs.selected?.let(tabs::closeOthers)
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Claude"
        private val CHAT_TABS = Key.create<ChatTabs>("ClaudeChat.ChatTabs")

        /** Opens the Claude tool window and adds a file, or lines of it, to the selected chat's context. */
        fun attachToChat(project: Project, path: String, lines: IntRange?) = withChat(project) { it.attachFile(path, lines) }

        /** Opens the Claude tool window with the caret in the selected chat's message box. */
        fun focusChat(project: Project) = withChat(project) { it.focusInput() }

        /** Opens the Claude tool window and opens or closes the selected chat's activity strip. */
        fun toggleActivity(project: Project) = withChat(project) { it.toggleActivity() }

        /** Opens the Claude tool window and passes [action] the selected chat, or a new one when [newChat]. */
        fun withChat(project: Project, newChat: Boolean = false, action: (ChatPanel) -> Unit) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            // Activation creates the first chat if the tool window was never opened.
            toolWindow.activate {
                val tabs = toolWindow.contentManager.contents.firstNotNullOfOrNull { it.getUserData(CHAT_TABS) } ?: return@activate
                val selected = tabs.selectedPanel
                // A chat nobody has written in yet is as good as a new one.
                action(if (newChat && selected?.state?.items?.isNotEmpty() != false) tabs.add() else selected ?: tabs.add())
            }
        }

        /** The tool window's single content: the chats and their tab strip. */
        internal fun createTabs(project: Project, toolWindow: ToolWindow): ChatTabs {
            val tabs = ChatTabs(project)
            val content = ContentFactory.getInstance().createContent(tabs.component, "", false).apply {
                setDisposer(tabs)
                putUserData(CHAT_TABS, tabs)
                isCloseable = false
            }
            toolWindow.contentManager.addContent(content)
            return tabs
        }
    }
}
