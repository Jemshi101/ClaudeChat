package `in`.bleuphoenix.claudechat.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.TableView
import com.intellij.util.ui.UIUtil
import java.io.File

/** The MCP Servers page builds headless and lists the servers it finds. */
class McpServersConfigurableTest : BasePlatformTestCase() {

    fun `test the page lists the project's servers`() {
        val saved = ClaudeChatSettings.options.cliPath
        // No claude, so the page doesn't run `claude mcp list` against the real config.
        ClaudeChatSettings.options.cliPath = "/nonexistent/claude"
        File(project.basePath!!).mkdirs()
        File(project.basePath, ".mcp.json").writeText("""{"mcpServers": {"team-docs": {"type": "http", "url": "https://docs.example.com/mcp"}}}""")
        val configurable = McpServersConfigurable(project)
        try {
            val component = configurable.createComponent()
            val table = UIUtil.findComponentOfType(component, TableView::class.java)!!
            val names = (0 until table.rowCount).map { table.getValueAt(it, 0) }
            assertTrue(names.toString(), "team-docs" in names)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            File(project.basePath, ".mcp.json").delete()
            ClaudeChatSettings.options.cliPath = saved
        }
    }
}
