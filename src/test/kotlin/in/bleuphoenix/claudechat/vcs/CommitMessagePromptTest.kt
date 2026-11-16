package `in`.bleuphoenix.claudechat.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diff and request behind the commit message button. */
class CommitMessagePromptTest {

    @Test
    fun `a change is a unified diff with three lines of context`() {
        val before = (1..20).joinToString("\n") { "line $it" }
        val after = before.replace("line 10", "line ten")
        assertEquals(
            """
            --- a/app/Main.kt
            +++ b/app/Main.kt
            @@ -7,7 +7,7 @@
             line 7
             line 8
             line 9
            -line 10
            +line ten
             line 11
             line 12
             line 13

            """.trimIndent(),
            CommitMessagePrompt.fileDiff(FileChange("app/Main.kt", before, after)),
        )
    }

    @Test
    fun `nearby changes share a hunk, distant ones don't`() {
        val before = (1..40).map { "line $it" }
        val near = before.toMutableList().apply { set(4, "five"); set(9, "ten") }
        assertEquals(1, Regex("@@ -").findAll(CommitMessagePrompt.hunks(before, near)).count())
        val far = before.toMutableList().apply { set(2, "three"); set(30, "thirty-one") }
        assertEquals(2, Regex("@@ -").findAll(CommitMessagePrompt.hunks(before, far)).count())
    }

    @Test
    fun `added, deleted, moved and binary files`() {
        val added = CommitMessagePrompt.fileDiff(FileChange("New.kt", null, "a\nb"))
        assertTrue(added, added.startsWith("--- /dev/null\n+++ b/New.kt\n@@ -0,0 +1,2 @@\n+a\n+b\n"))
        val deleted = CommitMessagePrompt.fileDiff(FileChange("Old.kt", "a", null))
        assertTrue(deleted, deleted.startsWith("--- a/Old.kt\n+++ /dev/null\n@@ -1,1 +0,0 @@\n-a\n"))
        assertTrue(CommitMessagePrompt.fileDiff(FileChange("B.kt", "x", "x", oldPath = "A.kt")).endsWith("Moved without changes\n"))
        assertTrue(CommitMessagePrompt.fileDiff(FileChange("logo.png", "", "", binary = true)).endsWith("Binary file changed\n"))
    }

    @Test
    fun `files past the limit are named, not diffed`() {
        val big = FileChange("Big.kt", null, "x\n".repeat(2000))
        val small = FileChange("A.kt", "a", "b")
        val diff = CommitMessagePrompt.diff(listOf(big, small), limit = 1000)
        assertTrue(diff.contains("+++ b/A.kt"))
        assertFalse(diff.contains("+++ b/Big.kt"))
        assertTrue(diff.endsWith("(Diff too long; also changed: Big.kt)\n"))
    }

    @Test
    fun `the request carries the draft and the repo's recent subjects`() {
        val prompt = CommitMessagePrompt.build(listOf(FileChange("A.kt", "a", "b")), "PROJ-12 fix login", listOf("PROJ-11: Add logout"))
        assertTrue(prompt.contains("- PROJ-11: Add logout\n"))
        assertTrue(prompt.contains("The user's draft; keep its intent and any ticket IDs or prefixes it has:\nPROJ-12 fix login\n"))
        assertTrue(prompt.endsWith("+b\n"))
        val plain = CommitMessagePrompt.build(listOf(FileChange("A.kt", "a", "b")), "  ", emptyList())
        assertFalse(plain.contains("draft"))
        assertFalse(plain.contains("Recent commit subjects"))
    }

    @Test
    fun `the reply loses code fences and quotes`() {
        assertEquals("Add login\n\nBody", CommitMessagePrompt.clean("```\nAdd login\n\nBody\n```\n"))
        assertEquals("Add login", CommitMessagePrompt.clean("```text\nAdd login\n```"))
        assertEquals("Add login", CommitMessagePrompt.clean("\"Add login\""))
        assertEquals("Fix \"quoted\" name", CommitMessagePrompt.clean("Fix \"quoted\" name"))
    }
}
