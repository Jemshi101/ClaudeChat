package `in`.bleuphoenix.claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reading Claude Code's MCP config, the JSON `claude mcp add-json` gets, and API keys kept out of the config. */
class McpServersTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `servers from all projects, this project and its mcp json`() {
        val project = temp.newFolder("app")
        val userConfig = temp.newFile(".claude.json").apply {
            writeText(
                """
                {"mcpServers": {
                   "tracker": {"type": "http", "url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer ${'$'}{CLAUDE_CHAT_MCP_TRACKER}"}},
                   "socket": {"type": "ws", "url": "ws://localhost:1"}},
                 "projects": {"${project.path}": {"mcpServers": {"files": {"command": "npx", "args": ["-y", "fs-server"], "env": {"ROOT": "/tmp"}}}}}}
                """.trimIndent(),
            )
        }
        project.resolve(".mcp.json").writeText("""{"mcpServers": {"legacy": {"url": "https://example.com/sse"}}}""")

        val servers = McpServers.read(project.path, userConfig)

        assertEquals(listOf("tracker" to McpScope.USER, "files" to McpScope.LOCAL, "legacy" to McpScope.PROJECT), servers.map { it.name to it.scope })
        val files = servers[1]
        assertEquals(McpTransport.STDIO, files.transport)
        assertEquals("npx -y fs-server", files.target)
        assertEquals(mapOf("ROOT" to "/tmp"), files.env)
        // No type with a URL is the old SSE form.
        assertEquals(McpTransport.SSE, servers[2].transport)
        assertEquals(setOf("CLAUDE_CHAT_MCP_TRACKER"), McpKeys.referencedBy(servers[0]))
        assertEquals(mapOf("CLAUDE_CHAT_MCP_TRACKER" to "k3y"), McpKeys.environment(project.path, { if (it == "CLAUDE_CHAT_MCP_TRACKER") "k3y" else null }, userConfig))
    }

    @Test
    fun `json for add-json`() {
        val http = McpServer("tracker", McpScope.USER, McpTransport.HTTP, url = "https://p/mcp", headers = mapOf("Authorization" to "Bearer ${McpKeys.reference("tracker")}"))
        assertEquals(
            """{"type":"http","url":"https://p/mcp","headers":{"Authorization":"Bearer ${'$'}{CLAUDE_CHAT_MCP_TRACKER}"}}""",
            http.toJson().toString(),
        )
        val stdio = McpServer("fs", McpScope.LOCAL, McpTransport.STDIO, command = "npx", args = listOf("-y", "fs"))
        assertEquals("""{"type":"stdio","command":"npx","args":["-y","fs"]}""", stdio.toJson().toString())
        assertEquals(http, McpServer.fromJson("tracker", McpScope.USER, http.toJson()))
    }

    @Test
    fun `where the key goes`() {
        assertEquals("CLAUDE_CHAT_MCP_MY_SERVER_2", McpKeys.variable("my-server.2"))
        assertEquals("Authorization" to "Bearer \${CLAUDE_CHAT_MCP_TRACKER}", McpKeys.placement("Authorization: Bearer <key>", McpTransport.HTTP, "tracker"))
        assertEquals("API_KEY" to "\${CLAUDE_CHAT_MCP_FS}", McpKeys.placement("API_KEY=<key>", McpTransport.STDIO, "fs"))
        assertNull(McpKeys.placement("Authorization: Bearer", McpTransport.HTTP, "tracker"))
    }

    @Test
    fun `a key written in the config is offered for the keychain`() {
        // As `claude mcp add --header "Authorization: Bearer ..."` leaves it.
        val plain = McpServer("tracker", McpScope.USER, McpTransport.HTTP, url = "https://p/mcp", headers = mapOf("Authorization" to "Bearer abc123", "X-Team" to "core"))
        val found = McpKeys.find(plain)!!
        assertEquals("Authorization: Bearer <key>", found.sentAs)
        assertEquals("abc123", found.plainKey)

        val kept = plain.copy(headers = mapOf("Authorization" to "Bearer ${McpKeys.reference("tracker")}"))
        McpKeys.find(kept)!!.let {
            assertEquals("Authorization: Bearer <key>", it.sentAs)
            assertNull(it.plainKey)
        }
        assertNull(McpKeys.find(plain.copy(headers = mapOf("X-Team" to "core"))))
    }

    @Test
    fun `health from claude mcp list`() {
        val output = """
            Checking MCP server health…

            tracker: https://mcp.example.com/mcp (HTTP) - ✔ Connected
            files: npx -y fs-server - ✗ Failed to connect
            linear: https://mcp.linear.app/mcp (HTTP) - ! Needs authentication
        """.trimIndent()
        assertEquals(
            mapOf("tracker" to "Connected", "files" to "Failed to connect", "linear" to "Needs authentication"),
            McpServers.parseStatuses(output),
        )
    }
}
