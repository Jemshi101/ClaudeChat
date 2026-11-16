package `in`.bleuphoenix.claudechat.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** OAuth settings on MCP servers, and the client secret kept off the command line. */
class McpOAuthTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `oauth settings round-trip through the config`() {
        val server = McpServer(
            "docs", McpScope.USER, McpTransport.HTTP, url = "https://docs.example.com/mcp",
            oauth = McpOAuth(clientId = "abc", callbackPort = 8765, scopes = "read write"),
        )
        assertEquals(
            """{"type":"http","url":"https://docs.example.com/mcp","oauth":{"clientId":"abc","callbackPort":8765,"scopes":"read write"}}""",
            server.toJson().toString(),
        )
        assertEquals(server, McpServer.fromJson("docs", McpScope.USER, server.toJson()))
        // Nothing set is no oauth entry at all.
        assertFalse(server.copy(oauth = McpOAuth()).toJson().has("oauth"))
        assertNull(McpOAuth.fromJson(JsonParser.parseString("{}").asJsonObject))
    }

    @Test
    fun `the client secret goes in the environment, not the arguments`() {
        val script = File("src/test/resources/fake-cli/fake_claude.py").readText()
        val dir = temp.newFolder("cli")
        val fake = File(dir, "fake_claude.py").apply { writeText(script) }
        val launcher = File(dir, "claude").apply {
            writeText("#!/bin/sh\nexec /usr/bin/python3 -I '${fake.path}' \"$@\"\n")
            setExecutable(true)
        }
        val server = McpServer("docs", McpScope.USER, McpTransport.HTTP, url = "https://docs.example.com/mcp", oauth = McpOAuth(clientId = "abc"))
        assertNull(McpServers.save(launcher.path, temp.root.path, server, null, clientSecret = "s3cret"))
        val recorded = JsonParser.parseString(File(dir, "mcp-add.json").readText()).asJsonObject
        assertEquals("s3cret", recorded.get("secret").asString)
        val args = recorded.getAsJsonArray("args").map { it.asString }
        assertEquals(listOf("--scope", "user", "--client-secret", "docs", server.toJson().toString()), args)
        assertFalse(args.any { "s3cret" in it })
    }
}
