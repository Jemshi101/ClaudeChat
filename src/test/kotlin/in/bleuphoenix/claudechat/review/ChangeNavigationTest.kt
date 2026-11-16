package `in`.bleuphoenix.claudechat.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangeNavigationTest {

    private fun file(path: String, vararg lines: Int) = FileReview(path, "", created = false).apply {
        hunks = lines.map { Hunk(it, it, emptyList(), listOf("x")) }
    }

    private val a = file("a.kt", 3, 10, 20)
    private val b = file("b.kt", 5, 8)
    private val c = file("c.kt", 1)
    private val pending = listOf(a, b, c)

    private fun go(current: FileReview, caret: Int, forward: Boolean = true) =
        ChangeNavigation.target(pending, current, caret, forward)?.let { Triple(it.file.path, it.index, it.line) }

    @Test
    fun `F7 goes to the next change in the file`() {
        assertEquals(Triple("a.kt", 0, 3), go(a, 0))
        assertEquals(Triple("a.kt", 1, 10), go(a, 3))
        assertEquals(Triple("a.kt", 2, 20), go(a, 12))
    }

    @Test
    fun `past the last change F7 opens the next file, wrapping around`() {
        assertEquals(Triple("b.kt", 0, 5), go(a, 20))
        assertEquals(Triple("a.kt", 0, 3), go(c, 1))
    }

    @Test
    fun `Shift F7 goes back, then to the previous file's last change`() {
        assertEquals(Triple("a.kt", 1, 10), go(a, 15, forward = false))
        assertEquals(Triple("a.kt", 2, 20), go(b, 5, forward = false))
        assertEquals(Triple("c.kt", 0, 1), go(a, 3, forward = false))
    }

    @Test
    fun `one file wraps to its own first change, and nothing pending goes nowhere`() {
        assertEquals(Triple("a.kt", 0, 3), ChangeNavigation.target(listOf(a), a, 25, true)?.let { Triple(it.file.path, it.index, it.line) })
        assertNull(ChangeNavigation.target(emptyList(), file("d.kt"), 0, true))
    }

    @Test
    fun `the change at the caret is one of its added lines, or next to removed ones`() {
        val file = FileReview("e.kt", "", created = false).apply {
            hunks = listOf(Hunk(2, 2, listOf("old"), listOf("new 1", "new 2")), Hunk(9, 8, listOf("gone"), emptyList()))
        }
        assertNull(ChangeNavigation.hunkAt(file, 1))
        assertEquals(2, ChangeNavigation.hunkAt(file, 2)?.currentStart)
        assertEquals(2, ChangeNavigation.hunkAt(file, 3)?.currentStart)
        assertNull(ChangeNavigation.hunkAt(file, 4))
        assertEquals(8, ChangeNavigation.hunkAt(file, 7)?.currentStart)
        assertEquals(8, ChangeNavigation.hunkAt(file, 8)?.currentStart)
        assertNull(ChangeNavigation.hunkAt(file, 9))
    }
}
