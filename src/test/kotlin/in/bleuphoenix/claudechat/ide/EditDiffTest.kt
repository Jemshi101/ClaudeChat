package `in`.bleuphoenix.claudechat.ide

import `in`.bleuphoenix.claudechat.ide.EditDiff.Edit
import org.junit.Assert.assertEquals
import org.junit.Test

class EditDiffTest {

    private val original = "fun a() = 1\nfun b() = 2\n"

    @Test
    fun `edit not yet applied`() {
        val (before, after) = EditDiff.beforeAfter(original, listOf(Edit("= 2", "= 3", false)))
        assertEquals(original, before)
        assertEquals("fun a() = 1\nfun b() = 3\n", after)
    }

    @Test
    fun `edit already applied`() {
        val edited = "fun a() = 1\nfun b() = 3\n"
        val (before, after) = EditDiff.beforeAfter(edited, listOf(Edit("= 2", "= 3", false)))
        assertEquals(original, before)
        assertEquals(edited, after)
    }

    @Test
    fun `multi edit applies in order`() {
        val edits = listOf(Edit("a()", "x()", false), Edit("x() = 1", "x() = 10", false))
        val (_, after) = EditDiff.beforeAfter(original, edits)
        assertEquals("fun x() = 10\nfun b() = 2\n", after)
    }

    @Test
    fun `replace all`() {
        val (_, after) = EditDiff.beforeAfter(original, listOf(Edit("fun", "val", true)))
        assertEquals("val a() = 1\nval b() = 2\n", after)
    }

    @Test
    fun `new file from empty`() {
        assertEquals("" to "hello", EditDiff.beforeAfter("", listOf(Edit("", "hello", false))))
    }

    @Test
    fun `falls back to fragments when the file matches neither side`() {
        assertEquals("= 9" to "= 8", EditDiff.beforeAfter(original, listOf(Edit("= 9", "= 8", false))))
    }

    @Test
    fun `applied deletion falls back to fragments`() {
        val (before, after) = EditDiff.beforeAfter("fun a() = 1\n", listOf(Edit("fun b() = 2\n", "", false)))
        assertEquals("fun b() = 2\n", before)
        assertEquals("", after)
    }
}
