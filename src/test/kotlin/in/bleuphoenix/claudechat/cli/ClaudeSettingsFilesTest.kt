package `in`.bleuphoenix.claudechat.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Permission rules and environment variables in Claude Code's settings files, keeping everything else. */
class ClaudeSettingsFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `scopes map to the project's and the user's files`() {
        val home = temp.newFolder("home")
        assertEquals(File("/work/app/.claude/settings.local.json"), ClaudeSettingsFiles.file(SettingsScope.LOCAL, "/work/app", home))
        assertEquals(File("/work/app/.claude/settings.json"), ClaudeSettingsFiles.file(SettingsScope.PROJECT, "/work/app", home))
        assertEquals(File(home, ".claude/settings.json"), ClaudeSettingsFiles.file(SettingsScope.USER, null, home))
        assertEquals(null, ClaudeSettingsFiles.file(SettingsScope.PROJECT, null, home))
    }

    @Test
    fun `reads rules and variables, and an absent file is empty`() {
        val file = temp.newFile("settings.json").apply {
            writeText("""{"permissions": {"allow": ["Bash(git diff:*)"], "deny": ["Read(./.env)"]}, "env": {"FOO": "1", "N": 2}, "model": "opus"}""")
        }
        val settings = ClaudeSettingsFiles.read(file)
        assertEquals(listOf("Bash(git diff:*)"), settings.allow)
        assertEquals(emptyList<String>(), settings.ask)
        assertEquals(listOf("Read(./.env)"), settings.deny)
        assertEquals(listOf("FOO" to "1", "N" to "2"), settings.env)
        assertEquals(ClaudeSettings(), ClaudeSettingsFiles.read(File(temp.root, "missing.json")))
    }

    @Test
    fun `writing keeps other keys and drops emptied ones`() {
        val file = temp.newFile("settings.json").apply {
            writeText("""{"model": "opus", "permissions": {"allow": ["Bash(ls)"], "defaultMode": "plan"}, "env": {"A": "1"}, "hooks": {"Stop": []}}""")
        }
        ClaudeSettingsFiles.write(file, ClaudeSettings(ask = listOf("Bash(git push:*)")))
        val json = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals("opus", json.get("model").asString)
        assertTrue(json.has("hooks"))
        val permissions = json.getAsJsonObject("permissions")
        assertEquals("plan", permissions.get("defaultMode").asString)
        assertFalse(permissions.has("allow"))
        assertEquals("Bash(git push:*)", permissions.getAsJsonArray("ask")[0].asString)
        assertFalse("no variables left", json.has("env"))
        assertTrue(file.readText().endsWith("}\n"))
    }

    @Test
    fun `a new file and its folder are created, without empty sections`() {
        val file = File(temp.root, "project/.claude/settings.local.json")
        ClaudeSettingsFiles.write(file, ClaudeSettings(env = listOf("URL" to "https://example.com/a?b=c&d")))
        assertEquals("{\n  \"env\": {\n    \"URL\": \"https://example.com/a?b=c&d\"\n  }\n}\n", file.readText())
        ClaudeSettingsFiles.write(file, ClaudeSettings())
        assertEquals("{}\n", file.readText())
    }

    @Test(expected = IOException::class)
    fun `a file that isn't JSON is reported`() {
        ClaudeSettingsFiles.read(temp.newFile("settings.json").apply { writeText("{ \"permissions\": ") })
    }
}
