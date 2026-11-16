package `in`.bleuphoenix.claudechat.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The requests the Claude right-click menus send, and the console output they take. */
class ClaudeMenuActionsTest {

    @Test
    fun `each request names what it's about`() {
        val selection = listOf(ClaudeTarget("app/Main.kt", lines = 3..9))
        val file = listOf(ClaudeTarget("app/Main.kt"))
        val files = listOf(ClaudeTarget("A.kt"), ClaudeTarget("B.kt"))
        val folder = listOf(ClaudeTarget("app/src", isDirectory = true))
        val mixed = listOf(ClaudeTarget("app/src", isDirectory = true), ClaudeTarget("B.kt"))

        assertTrue(ClaudeTask.Explain.prompt(selection).startsWith("Explain the selected code:"))
        assertTrue(ClaudeTask.FindBugs.prompt(file).startsWith("Look for bugs in this file:"))
        assertTrue(ClaudeTask.Refactor.prompt(files).startsWith("Refactor these files "))
        assertTrue(ClaudeTask.WriteTests.prompt(folder).startsWith("Write unit tests for the code in this folder,"))
        assertTrue(ClaudeTask.Explain.prompt(mixed).startsWith("Explain the code in these folders and files:"))
        ClaudeTask.entries.forEach { task -> assertTrue(task.name, "{subject}" !in task.prompt(file)) }
    }

    @Test
    fun `Explain asks Claude not to change anything`() {
        assertTrue(ClaudeTask.Explain.prompt(listOf(ClaudeTarget("A.kt"))).endsWith("Don't change any files."))
    }

    @Test
    fun `without a selection the console sends its last lines`() {
        val output = (1..500).joinToString("\n") { "line $it" } + "\n\n"
        val tail = ConsoleOutput.tail(output)
        assertEquals(ConsoleOutput.MAX_LINES, tail.lines().size)
        assertTrue(tail.startsWith("line 201\n"))
        assertTrue(tail.endsWith("line 500"))
        assertEquals(ConsoleOutput.MAX_CHARS, ConsoleOutput.tail("x".repeat(40_000)).length)
    }
}
