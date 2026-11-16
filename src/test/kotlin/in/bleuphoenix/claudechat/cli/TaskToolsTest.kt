package `in`.bleuphoenix.claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskToolsTest {

    @Test
    fun `the learned default set is passed back with the task tools added`() {
        assertEquals(
            "Bash,Edit,Read,TaskCreate,TaskGet,TaskList,TaskUpdate",
            TaskTools.toolsArgument(enabled = true, known = "Bash,Edit,Read", knownVersion = "2.1.310", currentVersion = "2.1.310"),
        )
    }

    @Test
    fun `defaults are used when off, not yet learned, or learned from another CLI version`() {
        assertNull(TaskTools.toolsArgument(enabled = false, known = "Bash", knownVersion = "1", currentVersion = "1"))
        assertNull(TaskTools.toolsArgument(enabled = true, known = null, knownVersion = null, currentVersion = "1"))
        assertNull(TaskTools.toolsArgument(enabled = true, known = "Bash", knownVersion = "1", currentVersion = "2"))
    }

    @Test
    fun `MCP tools are not part of the built-in set`() {
        assertEquals("Bash,Read", TaskTools.builtIn(listOf("Read", "mcp__ide__getDiagnostics", "Bash")))
    }
}
