package `in`.bleuphoenix.claudechat.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.UIUtil
import `in`.bleuphoenix.claudechat.cli.McpKeys
import `in`.bleuphoenix.claudechat.cli.McpOAuth
import `in`.bleuphoenix.claudechat.cli.McpScope
import `in`.bleuphoenix.claudechat.cli.McpServer
import `in`.bleuphoenix.claudechat.cli.McpTransport

/** The add/edit dialog builds headless and turns what's typed into a server with its key kept out of the config. */
class McpServerDialogTest : BasePlatformTestCase() {

    fun `test a key written in the config moves to the keychain`() {
        val plain = McpServer(
            "tracker", McpScope.USER, McpTransport.HTTP, url = "https://mcp.example.com/mcp",
            headers = mapOf("Authorization" to "Bearer abc123", "X-Team" to "core"),
        )
        val dialog = McpServerDialog(project, plain, setOf("tracker" to McpScope.USER))
        try {
            val key = UIUtil.findComponentOfType(dialog.form, JBPasswordField::class.java)!!
            assertEquals("abc123", String(key.password))
            assertNull(dialog.doValidate())
            dialog.doOKAction()
            val saved = dialog.result!!
            assertEquals(mapOf("X-Team" to "core", "Authorization" to "Bearer \${CLAUDE_CHAT_MCP_TRACKER}"), saved.headers)
            assertEquals("abc123", dialog.key)
            assertEquals(McpScope.USER, saved.scope)
        } finally {
            if (!dialog.isDisposed) dialog.close(0)
        }
    }

    fun `test a new server with a key`() {
        val dialog = McpServerDialog(project, null, emptySet())
        try {
            val pane = dialog.form
            val fields = UIUtil.findComponentsOfType(pane, com.intellij.ui.components.JBTextField::class.java)
            fields.first { it.emptyText.text == "" && it.text.isEmpty() }.text = "tracker"
            fields.first { it.emptyText.text == "https://example.com/mcp" }.text = "https://mcp.example.com/mcp"
            UIUtil.findComponentOfType(pane, JBPasswordField::class.java)!!.text = "s3cret"
            assertNull(dialog.doValidate())
            dialog.doOKAction()
            val saved = dialog.result!!
            assertEquals("tracker", saved.name)
            assertEquals("""{"type":"http","url":"https://mcp.example.com/mcp","headers":{"Authorization":"Bearer ${'$'}{CLAUDE_CHAT_MCP_TRACKER}"}}""", saved.toJson().toString())
            assertEquals("s3cret", dialog.key)
            assertEquals(setOf(McpKeys.variable("tracker")), McpKeys.referencedBy(saved))
        } finally {
            if (!dialog.isDisposed) dialog.close(0)
        }
    }

    fun `test OAuth settings and a client secret`() {
        val dialog = McpServerDialog(project, null, emptySet())
        try {
            val fields = UIUtil.findComponentsOfType(dialog.form, com.intellij.ui.components.JBTextField::class.java)
            fields.first { it.emptyText.text == "" && it.text.isEmpty() }.text = "docs"
            fields.first { it.emptyText.text == "https://example.com/mcp" }.text = "https://docs.example.com/mcp"
            val passwords = UIUtil.findComponentsOfType(dialog.form, JBPasswordField::class.java)
            val secret = passwords.first { it.emptyText.text == "Optional" && it !== passwords.first() }
            secret.text = "s3cret"
            assertNotNull("a secret needs a client ID", dialog.doValidate())

            val empties = fields.filter { it.text.isEmpty() && it.emptyText.text.isEmpty() }
            // Client ID, scopes, callback port and metadata URL, in that order.
            val (clientId, scopes, port) = empties.takeLast(4)
            clientId.text = "abc"
            scopes.text = " read   write "
            port.text = "70000"
            assertNotNull("port out of range", dialog.doValidate())
            port.text = "8765"
            assertNull(dialog.doValidate())
            dialog.doOKAction()
            assertEquals(McpOAuth(clientId = "abc", callbackPort = 8765, scopes = "read write"), dialog.result!!.oauth)
            assertEquals("s3cret", dialog.clientSecret)
            assertNull("no API key typed", dialog.key)
        } finally {
            if (!dialog.isDisposed) dialog.close(0)
        }
    }
}
