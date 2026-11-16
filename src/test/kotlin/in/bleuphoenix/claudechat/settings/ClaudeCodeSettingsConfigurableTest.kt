package `in`.bleuphoenix.claudechat.settings

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import `in`.bleuphoenix.claudechat.cli.SettingsScope
import java.io.File
import java.nio.file.Files

/** The Claude Code Settings page edits the chosen file's rules and variables, on temp folders. */
class ClaudeCodeSettingsConfigurableTest : BasePlatformTestCase() {

    private lateinit var projectDir: File
    private lateinit var home: File

    override fun setUp() {
        super.setUp()
        projectDir = Files.createTempDirectory("claude-settings-project").toFile()
        home = Files.createTempDirectory("claude-settings-home").toFile()
    }

    override fun tearDown() {
        try {
            projectDir.deleteRecursively()
            home.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test rules added to two files are saved on Apply`() {
        File(projectDir, ".claude").mkdirs()
        File(projectDir, ".claude/settings.local.json").writeText("""{"permissions": {"allow": ["Bash(ls)"]}, "outputStyle": "x"}""")
        val page = ClaudeCodeSettingsConfigurable(null, projectDir.path, home)
        try {
            page.createComponent()
            assertEquals(listOf("Bash(ls)"), page.rules("ALLOW").items)
            assertFalse(page.isModified)

            page.rules("DENY").add("Read(./.env)")
            page.select(SettingsScope.USER)
            assertEquals(emptyList<String>(), page.rules("DENY").items)
            page.env.addRow(ClaudeCodeSettingsConfigurable.EnvVar("FOO", "bar"))
            page.env.addRow(ClaudeCodeSettingsConfigurable.EnvVar(" ", "dropped"))
            assertTrue(page.isModified)
            page.apply()
            assertFalse(page.isModified)

            val local = JsonParser.parseString(File(projectDir, ".claude/settings.local.json").readText()).asJsonObject
            assertEquals("x", local.get("outputStyle").asString)
            assertEquals("Read(./.env)", local.getAsJsonObject("permissions").getAsJsonArray("deny")[0].asString)
            val user = JsonParser.parseString(File(home, ".claude/settings.json").readText()).asJsonObject
            assertEquals(setOf("FOO"), user.getAsJsonObject("env").keySet())
            assertFalse("the shared project file wasn't touched", File(projectDir, ".claude/settings.json").exists())
        } finally {
            page.disposeUIResources()
        }
    }

    fun `test a broken file is shown read-only and left alone`() {
        File(projectDir, ".claude").mkdirs()
        val broken = File(projectDir, ".claude/settings.local.json").apply { writeText("{ nope") }
        val page = ClaudeCodeSettingsConfigurable(null, projectDir.path, home)
        try {
            page.createComponent()
            assertTrue(page.errorText, page.errorText.contains("isn't valid JSON"))
            assertFalse(page.isModified)
            page.apply()
            assertEquals("{ nope", broken.readText())
        } finally {
            page.disposeUIResources()
        }
    }
}
