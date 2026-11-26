package `in`.bleuphoenix.claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomCommandsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `commands are read from the project and the user, subfolders named with a colon`() {
        val project = temp.newFolder("project")
        val user = temp.newFolder("user")
        File(project, ".claude/commands/review").mkdirs()
        File(project, ".claude/commands/review/mr.md").writeText("---\ndescription: \"Write the MR description\"\n---\n\nUse the house format.\n")
        File(user, "prefix.md").writeText("use _get prefix\n\n\$ARGUMENTS\n")
        File(user, "notes.txt").writeText("not a command")

        val commands = CustomCommands.read(project.path, user)

        assertEquals(listOf("review:mr" to CommandScope.PROJECT, "prefix" to CommandScope.USER), commands.map { it.name to it.scope })
        assertEquals("Write the MR description", commands[0].summary)
        assertEquals("use _get prefix", commands[1].summary)
    }

    @Test
    fun `saving keeps frontmatter it doesn't edit`() {
        val parsed = CustomCommands.parse(
            "fix",
            CommandScope.USER,
            "---\ndescription: 'Fix it'\nallowed-tools: Bash(git:*)\nargument-hint: [issue]\nmodel: sonnet\n---\n\nFix issue \$1.\n",
        )
        assertEquals("Fix it", parsed.description)
        assertEquals("[issue]", parsed.argumentHint)
        assertEquals("Fix issue \$1.", parsed.body)

        val changed = parsed.copy(description = "Fix the \"issue\"")
        assertEquals(
            "---\ndescription: \"Fix the \\\"issue\\\"\"\nargument-hint: \"[issue]\"\nallowed-tools: Bash(git:*)\nmodel: sonnet\n---\n\nFix issue \$1.\n",
            CustomCommands.render(changed),
        )
        assertEquals(changed.copy(file = null), CustomCommands.parse("fix", CommandScope.USER, CustomCommands.render(changed)))
    }

    @Test
    fun `a command without a description is just its prompt`() {
        assertEquals("Do it\n", CustomCommands.render(CustomCommand("x", CommandScope.USER, "", "", "Do it")))
    }

    @Test
    fun `renaming or moving a command replaces its old file`() {
        val project = temp.newFolder("project")
        val user = temp.newFolder("user")
        val first = CustomCommands.save(CustomCommand("team:mr", CommandScope.PROJECT, "MR", "", "Write it"), null, project.path, user)
        assertEquals(File(project, ".claude/commands/team/mr.md"), first)

        val saved = CustomCommands.read(project.path, user).single()
        CustomCommands.save(saved.copy(name = "mr", scope = CommandScope.USER), saved, project.path, user)

        assertFalse(first.exists())
        assertFalse("the emptied subfolder goes too", File(project, ".claude/commands/team").exists())
        assertTrue(File(project, ".claude/commands").isDirectory)
        assertEquals(listOf("mr" to CommandScope.USER), CustomCommands.read(project.path, user).map { it.name to it.scope })

        CustomCommands.delete(CustomCommands.read(project.path, user).single(), project.path, user)
        assertEquals(emptyList<CustomCommand>(), CustomCommands.read(project.path, user))
    }

    @Test
    fun `names are checked`() {
        assertTrue(CustomCommands.validName("review:mr-2"))
        assertFalse(CustomCommands.validName("../etc"))
        assertFalse(CustomCommands.validName("two words"))
        assertFalse(CustomCommands.validName(":x"))
    }
}
