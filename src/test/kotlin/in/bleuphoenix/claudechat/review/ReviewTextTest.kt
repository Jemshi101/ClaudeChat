package `in`.bleuphoenix.claudechat.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTextTest {

    private val before = "fun main() {\n    println(\"hello\")\n    val x = 1\n    println(x)\n}\n"
    private val after = "fun main() {\n    println(\"hi\")\n    val x = 2\n    println(x)\n}\n"

    @Test
    fun `adjacent changed lines form one hunk`() {
        val hunk = ReviewText.hunks(before, after).single()
        assertEquals(1, hunk.baseStart)
        assertEquals(1, hunk.currentStart)
        assertEquals(listOf("    println(\"hello\")", "    val x = 1"), hunk.removed)
        assertEquals(listOf("    println(\"hi\")", "    val x = 2"), hunk.added)
    }

    @Test
    fun `separate changes are separate hunks and each can be rejected alone`() {
        val base = "a\nb\nc\nd\ne\n"
        val current = "a\nB\nc\nd\nE\nf\n"
        val (first, second) = ReviewText.hunks(base, current)

        val firstRejected = ReviewText.reject(current, first)
        assertEquals("a\nb\nc\nd\nE\nf\n", firstRejected)
        // The other hunk is still there, unchanged.
        assertEquals(listOf(listOf("e") to listOf("E", "f")), ReviewText.hunks(base, firstRejected).map { it.removed to it.added })

        assertEquals("a\nB\nc\nd\ne\n", ReviewText.reject(current, second))
    }

    @Test
    fun `accepting a hunk folds it into the baseline`() {
        val base = "a\nb\nc\nd\ne\n"
        val current = "a\nB\nc\nd\nE\n"
        val (first, _) = ReviewText.hunks(base, current)
        val newBase = ReviewText.accept(base, first)
        assertEquals("a\nB\nc\nd\ne\n", newBase)
        assertEquals(1, ReviewText.hunks(newBase, current).size)
    }

    @Test
    fun `pure insertions and deletions`() {
        val inserted = ReviewText.hunks("a\nc\n", "a\nb\nc\n").single()
        assertEquals(emptyList<String>(), inserted.removed)
        assertEquals(listOf("b"), inserted.added)
        assertEquals("a\nc\n", ReviewText.reject("a\nb\nc\n", inserted))

        val deleted = ReviewText.hunks("a\nb\nc\n", "a\nc\n").single()
        assertEquals(listOf("b"), deleted.removed)
        assertEquals("a\nb\nc\n", ReviewText.reject("a\nc\n", deleted))
    }

    @Test
    fun `a new file is one hunk against an empty baseline`() {
        val hunk = ReviewText.hunks("", "class A\n").single()
        assertEquals("", ReviewText.reject("class A\n", hunk))
    }

    @Test
    fun `identical text has no hunks`() {
        assertTrue(ReviewText.hunks(before, before).isEmpty())
    }

    @Test
    fun `a hunk no longer matches once the file changed under it`() {
        val hunk = ReviewText.hunks(before, after).single()
        assertTrue(ReviewText.matches(after, hunk))
        assertFalse(ReviewText.matches(before, hunk))
    }

    @Test
    fun `minimal edit touches only the changed middle`() {
        assertEquals(TextEdit(4, 5, "X"), ReviewText.minimalEdit("abc\nd\nef", "abc\nX\nef"))
        assertEquals(TextEdit(3, 3, "b"), ReviewText.minimalEdit("aaa", "aaab"))
        assertEquals(TextEdit(0, 3, ""), ReviewText.minimalEdit("abc", ""))
        val edit = ReviewText.minimalEdit(after, before)
        assertEquals(before, after.substring(0, edit.start) + edit.replacement + after.substring(edit.end))
    }
}
