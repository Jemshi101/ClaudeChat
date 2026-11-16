package `in`.bleuphoenix.claudechat.ui

import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.io.File
import java.nio.file.Files

/** The chat tabs: closing one, the others, the last one, and one that's still working. */
class ChatTabsTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private var savedCliPath: String? = null
    private lateinit var tabs: ChatTabs

    override fun setUp() {
        super.setUp()
        // Chats read the CLI's commands when they open; the fake CLI answers without the real one.
        dir = Files.createTempDirectory("fake-claude").toFile().canonicalFile
        val script = File(dir, "fake_claude.py").apply { writeText(File("src/test/resources/fake-cli/fake_claude.py").readText()) }
        val launcher = File(dir, "claude").apply {
            writeText("#!/bin/sh\nexec /usr/bin/python3 -I '${script.path}' \"$@\"\n")
            setExecutable(true)
        }
        savedCliPath = ClaudeChatSettings.options.cliPath
        ClaudeChatSettings.options.cliPath = launcher.path
        File(project.basePath!!).mkdirs()
        tabs = ChatTabs(project)
        Disposer.register(testRootDisposable, tabs)
        tabs.add()
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            ClaudeChatSettings.options.cliPath = savedCliPath
            dir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test new chats get a tab and are selected, closing one selects its neighbour`() {
        val first = tabs.tabs.single()
        tabs.add()
        tabs.add()
        assertEquals(3, tabs.tabs.size)
        val second = tabs.tabs[1]
        tabs.select(second)

        tabs.close(second)
        assertEquals(listOf(first, tabs.tabs[1]), tabs.tabs.toList())
        assertSame("the tab after it", tabs.tabs[1], tabs.selected)
        assertTrue(Disposer.isDisposed(second.panel))

        tabs.closeOthers(first)
        assertEquals(listOf(first), tabs.tabs.toList())
        assertSame(first, tabs.selected)
    }

    fun `test the tab strip takes room above the chat`() {
        assertTrue(tabs.component.getComponent(0).preferredSize.height > 20)
    }

    fun `test closing the last chat starts a new one`() {
        val first = tabs.tabs.single()
        tabs.close(first)
        val now = tabs.tabs.single()
        assertNotSame(first, now)
        assertSame(now, tabs.selected)
    }

    fun `test the tab is named after the chat`() {
        val tab = tabs.tabs.single()
        assertEquals("New chat", tab.title)
        tab.panel.suggest("Fix the login bug\nwith details")
        tab.panel.send()
        assertEquals("Fix the login bug", tab.title)
    }

    fun `test closing a chat that's still working asks first`() {
        tabs.add()
        val busy = tabs.selected!!
        busy.panel.state.busy = true
        TestDialogManager.setTestDialog { Messages.CANCEL }
        tabs.close(busy)
        assertEquals("Cancel keeps it", 2, tabs.tabs.size)

        TestDialogManager.setTestDialog { Messages.OK }
        tabs.close(busy)
        assertEquals(1, tabs.tabs.size)
    }
}
