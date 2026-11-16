package `in`.bleuphoenix.claudechat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same failure several times in a row shows once, with a count. */
class RepeatedFailuresTest {

    private var next = 0

    private fun tool(command: String, status: ToolStatus = ToolStatus.Failed) =
        ToolItem("t${next++}", "Bash", command, """{"command":"$command"}""", null, null, false).apply { this.status = status }

    private fun notice(text: String, isError: Boolean = true) = NoticeItem("n${next++}", text, isError)

    @Test
    fun `a failing command retried shows once, on its last try`() {
        val items = listOf(tool("ls"), tool("gradle test"), tool("gradle test"), tool("gradle test"), tool("ls"))
        assertEquals(listOf(1, 0, 0, 3, 1), RepeatedFailures.counts(items).toList())
    }

    @Test
    fun `only failures with the same input, one after another, count`() {
        val items = listOf(
            tool("gradle test"), tool("gradle test", ToolStatus.Succeeded),
            tool("gradle test"), tool("gradle build"),
            tool("gradle test"), tool("gradle test", ToolStatus.Running),
        )
        assertEquals(listOf(1, 1, 1, 1, 1, 1), RepeatedFailures.counts(items).toList())
        val withMessage = listOf(tool("a"), notice("Claude exited"), tool("a"))
        assertEquals(listOf(1, 1, 1), RepeatedFailures.counts(withMessage).toList())
    }

    @Test
    fun `repeated errors collapse, other notices don't`() {
        val items = listOf(notice("Claude exited with code 1"), notice("Claude exited with code 1"), notice("Stopped.", false), notice("Stopped.", false))
        assertEquals(listOf(0, 2, 1, 1), RepeatedFailures.counts(items).toList())
        assertEquals(emptyList<Int>(), RepeatedFailures.counts(emptyList()).toList())
    }
}
