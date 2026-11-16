package `in`.bleuphoenix.claudechat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.bleuphoenix.claudechat.history.SessionInfo
import `in`.bleuphoenix.claudechat.ui.compose.ChatTabStrip
import org.jetbrains.jewel.bridge.JewelComposePanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/** One chat in the tool window: its panel and the name its tab shows. */
class ChatTab(val panel: ChatPanel, val key: String) {
    var title by mutableStateOf("New chat")
}

/**
 * The tool window's chats, each in a tab with its own close button, like Cursor's: the IDE's own content tabs only
 * show × on hover, and turn into a list without any once the window is narrow.
 */
class ChatTabs(private val project: Project) : Disposable {

    val tabs = mutableStateListOf<ChatTab>()
    var selected by mutableStateOf<ChatTab?>(null)
        private set

    private val cards = CardLayout()
    private val chats = JPanel(cards)
    private var nextKey = 0

    val component: JComponent = JPanel(BorderLayout()).apply {
        val strip = JewelComposePanel(focusOnClickInside = false) {
            ChatTabStrip(
                titles = tabs.map { it.title },
                selected = tabs.indexOf(selected),
                onSelect = { select(tabs[it]) },
                // The close may ask first, and a dialog can't open inside a Compose click handler.
                onClose = { index -> tabs.getOrNull(index)?.let { tab -> later { close(tab) } } },
            )
        }
        add(StripHolder(strip.withSwingSizes()), BorderLayout.NORTH)
        add(chats, BorderLayout.CENTER)
    }

    val selectedPanel: ChatPanel? get() = selected?.panel

    /** Opens a new chat, or [past] continued, in a tab of its own and selects it. */
    fun add(past: SessionInfo? = null): ChatPanel {
        // Null until the tab exists; the panel may name its chat while it's being created.
        var tab: ChatTab? = null
        val panel = ChatPanel(
            project,
            onTitle = { title -> tab?.title = title.lineSequence().first().trim().ifEmpty { "New chat" } },
            isWatched = { tab?.let(::isWatched) ?: false },
            bringForward = { tab?.let(::bringForward) },
            chatTitle = { tab?.title.orEmpty() },
            openInNewChat = { add(it) },
        )
        val created = ChatTab(panel, "chat-${nextKey++}")
        tab = created
        Disposer.register(this, panel)
        chats.add(panel.component, created.key)
        tabs += created
        select(created)
        past?.let(panel::load)
        return panel
    }

    fun select(tab: ChatTab) {
        selected = tab
        cards.show(chats, tab.key)
        tab.panel.focusInput()
    }

    /**
     * Closes [tab], stopping its Claude process; asks first while Claude is still working there. The window always
     * has a chat, so closing the last one starts a new one.
     */
    fun close(tab: ChatTab) {
        if (tab.panel.state.busy && !confirmClose(tab)) return
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        chats.remove(tab.panel.component)
        Disposer.dispose(tab.panel)
        if (tabs.isEmpty()) {
            add()
        } else if (selected == tab) {
            select(tabs[index.coerceAtMost(tabs.lastIndex)])
        }
    }

    /** Whether the user can see [tab]: Android Studio focused on this project, the Claude window open, the tab selected. */
    private fun isWatched(tab: ChatTab): Boolean {
        if (!ApplicationManager.getApplication().isActive || selected != tab) return false
        if (IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project != project) return false
        return ToolWindowManager.getInstance(project).getToolWindow(ClaudeChatToolWindowFactory.TOOL_WINDOW_ID)?.isVisible == true
    }

    /** Brings [tab] to the front: this project's window, the Claude window, then the tab. */
    private fun bringForward(tab: ChatTab) {
        if (tab !in tabs) return
        ProjectUtil.focusProjectWindow(project, true)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ClaudeChatToolWindowFactory.TOOL_WINDOW_ID)
        if (toolWindow == null) select(tab) else toolWindow.activate { select(tab) }
    }

    fun closeOthers(keep: ChatTab) {
        tabs.filter { it != keep }.forEach(::close)
        select(keep)
    }

    private fun confirmClose(tab: ChatTab): Boolean = Messages.showOkCancelDialog(
        project,
        "Claude is still working in \"${tab.title}\". Close the chat and stop it?",
        "Close Chat",
        "Close",
        Messages.getCancelButton(),
        Messages.getQuestionIcon(),
    ) == Messages.OK

    /**
     * Gives the tab strip its height without asking Compose (see [withSwingSizes]): one line of the IDE's small font
     * plus the strip's padding (4 above, 5 + 5 around the title, 2 for the underline, 1 for the border). The width
     * is the window's, which BorderLayout gives it anyway.
     */
    private class StripHolder(strip: JComponent) : JPanel(BorderLayout()) {
        init {
            add(strip, BorderLayout.CENTER)
        }

        override fun getPreferredSize(): Dimension {
            val font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            val line = getFontMetrics(font).height
            return Dimension(0, line + JBUI.scale(4 + 10 + 2 + 1 + 4))
        }

        override fun getMinimumSize(): Dimension = preferredSize
    }

    private fun later(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.nonModal(), project.disposed)

    override fun dispose() = Unit
}
