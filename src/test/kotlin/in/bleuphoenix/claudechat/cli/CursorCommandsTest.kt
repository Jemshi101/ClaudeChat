package `in`.bleuphoenix.claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CursorCommandsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a cursor command becomes a claude command`() {
        assertEquals(
            "---\ndescription: \"use _get prefix\"\n---\n\nuse _get prefix\npass \"context\"\n\n\$ARGUMENTS\n",
            CursorCommands.convert("# prefix\r\n\r\nuse _get prefix\r\npass \"context\"\r\n"),
        )
    }

    @Test
    fun `a long first line is cut at a word`() {
        val long = "padding between the components is the duty of the next widget, wrap the next widget in padding widget, if"
        assertTrue(CursorCommands.convert(long).startsWith("---\ndescription: \"padding between the components is the duty of the next widget, wrap the next widget in padding…\""))
    }

    @Test
    fun `antigravity workflows get no arguments placeholder`() {
        assertEquals("---\ndescription: \"wrap it\"\n---\n\nwrap it\n", CursorCommands.convert("# padding\n\nwrap it\n", arguments = false))
    }

    @Test
    fun `new and edited commands are copied, others left alone`() {
        val cursor = temp.newFolder("cursor")
        val claude = CursorCommands.claude().copy(dir = File(temp.root, "claude"))
        File(cursor, "padding.md").writeText("# padding\n\nwrap the next widget")
        File(cursor, "simplify.md").writeText("# simplify\n\nsimplify it")
        File(cursor, "notes.txt").writeText("not a command")

        assertEquals(listOf("padding.md", "simplify-flow.md"), CursorCommands.sync(cursor, claude).map { it.name })
        assertTrue(CursorCommands.sync(cursor, claude).isEmpty())

        // Edited by hand in Claude's folder: kept until the Cursor command changes.
        File(claude.dir, "padding.md").writeText("mine")
        assertTrue(CursorCommands.sync(cursor, claude).isEmpty())
        File(cursor, "padding.md").apply { writeText("# padding\n\nnew text") }.setLastModified(System.currentTimeMillis() + 5_000)
        assertEquals(listOf("padding.md"), CursorCommands.sync(cursor, claude).map { it.name })
        assertTrue(File(claude.dir, "padding.md").readText().contains("new text"))
    }

    @Test
    fun `antigravity keeps cursor's names`() {
        val cursor = temp.newFolder("cursor")
        File(cursor, "simplify.md").writeText("# simplify\n\nsimplify it")
        val written = CursorCommands.sync(cursor, CursorCommands.Target(File(temp.root, "workflows"), arguments = false))
        assertEquals(listOf("simplify.md"), written.map { it.name })
    }

    @Test
    fun `no cursor folder does nothing`() {
        assertTrue(CursorCommands.sync(File(temp.root, "missing"), CursorCommands.Target(File(temp.root, "claude"))).isEmpty())
    }
}
