package `in`.bleuphoenix.claudechat.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apply on a reply's code block: the request, reading the reply, and the range that's replaced. */
class ApplyCodeTest {

    @Test
    fun `the request holds the file and the suggestion`() {
        val prompt = ApplyCode.prompt("app/Main.kt", "kotlin", "fun a() = 1\n", "fun a() = 2")
        assertTrue(prompt.contains("File: app/Main.kt\n<current>\nfun a() = 1\n\n</current>"))
        assertTrue(prompt.endsWith("Suggestion (kotlin):\n<suggestion>\nfun a() = 2\n</suggestion>\n"))
        assertTrue(prompt.contains("between <file> and </file> tags"))
    }

    @Test
    fun `the reply's file keeps the original's last line break`() {
        assertEquals("a\nb\n", ApplyCode.parseReply("Here:\n<file>\na\nb\n</file>", "a\n"))
        assertEquals("a\nb", ApplyCode.parseReply("<file>\na\nb\n</file>", "a"))
        // Tags inside the file's own text don't cut it short.
        assertEquals("x = \"</file>\"\n", ApplyCode.parseReply("<file>\nx = \"</file>\"\n</file>", "\n"))
        assertNull(ApplyCode.parseReply("I can't do that.", "a"))
    }

    @Test
    fun `only the changed part is replaced`() {
        assertEquals(Triple(6, 7, "2"), ApplyCode.changedRange("val x=1\nend", "val x=2\nend"))
        assertEquals(Triple(2, 2, "b\n"), ApplyCode.changedRange("a\nc\n", "a\nb\nc\n"))
        val (start, end, text) = ApplyCode.changedRange("aaa", "aaaa")
        assertEquals("aaa".substring(0, start) + text + "aaa".substring(end), "aaaa")
        assertEquals(Triple(3, 3, ""), ApplyCode.changedRange("abc", "abc"))
    }

    @Test
    fun `commands and output can't be applied`() {
        assertTrue(ApplyCode.applies("kotlin"))
        assertTrue(ApplyCode.applies(""))
        assertFalse(ApplyCode.applies("bash"))
        assertFalse(ApplyCode.applies("Shell"))
        assertFalse(ApplyCode.applies("text"))
    }
}
