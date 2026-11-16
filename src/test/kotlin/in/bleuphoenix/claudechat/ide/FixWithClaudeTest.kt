package `in`.bleuphoenix.claudechat.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request Alt+Enter → Fix with Claude sends. */
class FixWithClaudeTest {

    @Test
    fun `one problem is named with its line`() {
        val prompt = FixWithClaudeIntention.prompt("app/Main.kt", listOf(CodeProblem(12, "Unresolved reference 'foo'", isError = true)))
        assertTrue(prompt, prompt.startsWith("The IDE reports an error in app/Main.kt at line 12: Unresolved reference 'foo'\n\n"))
        assertTrue(prompt.endsWith("Don't just silence the error."))
    }

    @Test
    fun `several problems are listed`() {
        val prompt = FixWithClaudeIntention.prompt(
            "A.kt",
            listOf(CodeProblem(3, "Type mismatch", isError = true), CodeProblem(3, "Unused variable 'x'", isError = false)),
        )
        assertEquals(
            "The IDE reports these problems in A.kt:\n- Line 3, an error: Type mismatch\n- Line 3, a warning: Unused variable 'x'",
            prompt.substringBefore("\n\n"),
        )
    }

    @Test
    fun `only warnings ask not to silence the warning`() {
        val prompt = FixWithClaudeIntention.prompt("A.kt", listOf(CodeProblem(1, "Deprecated", isError = false)))
        assertTrue(prompt.contains("a warning in A.kt"))
        assertTrue(prompt.endsWith("Don't just silence the warning."))
    }
}
