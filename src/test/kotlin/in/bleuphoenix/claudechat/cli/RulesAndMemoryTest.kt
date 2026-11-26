package `in`.bleuphoenix.claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RulesAndMemoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `lists rules from the project, its parents and the user, then memory`() {
        val config = temp.newFolder("config")
        val workspace = temp.newFolder("workspace")
        val project = File(workspace, "app").apply { mkdirs() }
        File(workspace, "CLAUDE.md").writeText("# Workspace\n\n## MR description format\n- Keep it short\n")
        File(project, "CLAUDE.local.md").writeText("my rule\n")
        File(project, ".claude/rules").mkdirs()
        File(project, ".claude/rules/kotlin.md").writeText("Use val\n")
        val memory = RulesAndMemory.memoryDir(project.path, config)
        memory.mkdirs()
        File(memory, "MEMORY.md").writeText("- [MR format](mr.md) — house style\n")
        File(memory, "mr.md").writeText("---\nname: mr\ndescription: always use the house MR format\n---\n\nbody\n")

        val files = RulesAndMemory.list(project.path, configDir = config)

        assertEquals(
            listOf(
                RuleKind.USER to File(config, "CLAUDE.md"),
                RuleKind.PROJECT to File(project, "CLAUDE.md"),
                RuleKind.LOCAL to File(project, "CLAUDE.local.md"),
                RuleKind.RULES_FOLDER to File(project, ".claude/rules/kotlin.md"),
                RuleKind.PARENT to File(workspace, "CLAUDE.md"),
                RuleKind.MEMORY_INDEX to File(memory, "MEMORY.md"),
                RuleKind.MEMORY to File(memory, "mr.md"),
            ),
            files.map { it.kind to it.file },
        )
        assertFalse("the user's and project's CLAUDE.md can be created", files[0].exists || files[1].exists)
        assertEquals("Workspace · 3 lines", files[4].about)
        assertEquals("always use the house MR format · 5 lines", files[6].about)
        assertTrue(files.all { it.loaded })
    }

    @Test
    fun `instruction files replace CLAUDE md`() {
        val config = temp.newFolder("config")
        val project = temp.newFolder("project")
        val agent = File(project, "agent.md").apply { writeText("Be brief\n") }

        val files = RulesAndMemory.list(project.path, listOf(agent), config)

        assertFalse(files.first { it.kind == RuleKind.PROJECT }.loaded)
        assertEquals(agent, files.single { it.kind == RuleKind.INSTRUCTION }.file)
        assertTrue(files.single { it.kind == RuleKind.MEMORY_INDEX }.loaded)
    }

    @Test
    fun `memory is kept per repository root, or where the settings say`() {
        val config = temp.newFolder("config")
        val repo = temp.newFolder("repo")
        File(repo, ".git").mkdirs()
        val module = File(repo, "app").apply { mkdirs() }
        assertEquals(File(config, "projects/" + repo.path.replace(Regex("[^A-Za-z0-9]"), "-") + "/memory"), RulesAndMemory.memoryDir(module.path, config))

        val custom = temp.newFolder("custom")
        File(config, "settings.json").writeText("""{"autoMemoryDirectory": "${custom.path}"}""")
        assertEquals(custom, RulesAndMemory.memoryDir(module.path, config))
    }

    @Test
    fun `a rule is added as a bullet`() {
        val file = File(temp.root, "new/CLAUDE.md")
        RulesAndMemory.addRule(file, "Run the tests")
        RulesAndMemory.addRule(file, "  - Keep it short  ")
        assertEquals("- Run the tests\n- Keep it short\n", file.readText())

        file.writeText("# Rules")
        RulesAndMemory.addRule(file, "One more")
        assertEquals("# Rules\n- One more\n", file.readText())
    }
}
