package `in`.bleuphoenix.claudechat.settings

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.TableView
import com.intellij.util.ui.UIUtil
import `in`.bleuphoenix.claudechat.cli.CommandScope
import `in`.bleuphoenix.claudechat.cli.CustomCommand
import java.io.File

/** The Commands and Rules & Memory pages build headless and list what they find, never the real ~/.claude. */
class CommandsAndRulesConfigurableTest : BasePlatformTestCase() {

    fun `test the commands page lists project and user commands`() {
        val user = FileUtil.createTempDirectory("user-commands", null)
        File(user, "prefix.md").writeText("---\ndescription: use _get prefix\n---\n\nrename it\n")
        val projectCommands = File(project.basePath!!, ".claude/commands").apply { mkdirs() }
        File(projectCommands, "mr.md").writeText("Write the MR description\n")
        val configurable = CommandsConfigurable(project, user)
        try {
            val table = UIUtil.findComponentOfType(configurable.createComponent(), TableView::class.java)!!
            val rows = (0 until table.rowCount).map { row -> (0..2).map { table.getValueAt(row, it) } }
            assertEquals(
                listOf(listOf("/mr", "This project", "Write the MR description"), listOf("/prefix", "All projects", "use _get prefix")),
                rows,
            )
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            FileUtil.delete(File(project.basePath!!, ".claude"))
            FileUtil.delete(user)
        }
    }

    fun `test the rules page lists CLAUDE md and memory`() {
        val config = FileUtil.createTempDirectory("claude-config", null)
        File(project.basePath!!).mkdirs()
        File(project.basePath, "CLAUDE.md").writeText("# Project\n- MR description format\n")
        val configurable = RulesAndMemoryConfigurable(project, config)
        try {
            val table = UIUtil.findComponentOfType(configurable.createComponent(), TableView::class.java)!!
            val rows = (0 until table.rowCount).map { row -> (0..2).map { table.getValueAt(row, it).toString() } }
            assertTrue(rows.toString(), listOf("Rules · this project", "CLAUDE.md", "Project · 2 lines") in rows)
            assertTrue(rows.toString(), rows.any { it[0] == "Rules · all projects" && it[2] == "Not created yet" })
            assertTrue(rows.toString(), rows.any { it[0] == "Memory index" })
        } finally {
            configurable.disposeUIResources()
            File(project.basePath, "CLAUDE.md").delete()
            FileUtil.delete(config)
        }
    }

    fun `test the command dialog checks the name and keeps other frontmatter`() {
        val existing = CustomCommand("mr", CommandScope.USER, "MR", "", "Write it", otherFrontmatter = listOf("model: sonnet"))
        val dialog = CommandDialog(project, existing, setOf("mr" to CommandScope.USER, "fix" to CommandScope.USER))
        try {
            assertNull("its own name is fine", dialog.doValidate())
            dialog.nameField.text = "fix"
            assertNotNull("taken", dialog.doValidate())
            dialog.nameField.text = "two words"
            assertNotNull(dialog.doValidate())
            dialog.nameField.text = "/mr-2"
            dialog.promptArea.text = "Write it well"
            assertNull(dialog.doValidate())
            dialog.doOKAction()
            assertEquals(existing.copy(name = "mr-2", body = "Write it well"), dialog.result)
        } finally {
            if (!dialog.isDisposed) dialog.close(0)
        }
    }
}
