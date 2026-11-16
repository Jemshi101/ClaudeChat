package `in`.bleuphoenix.claudechat.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineEditPromptTest {

    private val file = "fun main() {\n    println(\"hi\")\n}\n"

    @Test
    fun `the selection is marked in the file and the instruction follows`() {
        val start = file.indexOf("    println")
        val end = file.indexOf("}")
        val prompt = InlineEditPrompt.build("src/Main.kt", "kt", file, start, end, "  say hello  ")
        assertTrue(prompt.contains("File: src/Main.kt\n```kt\nfun main() {\n<selection>    println(\"hi\")\n</selection>}\n"))
        assertTrue(prompt.endsWith("Instruction: say hello\n"))
        assertTrue(prompt.contains("Rewrite only the part of the file between <selection> and </selection>"))
    }

    @Test
    fun `without a selection Claude writes code to insert at the caret`() {
        val prompt = InlineEditPrompt.build("Main.kt", "kt", file, file.indexOf("}"), file.indexOf("}"), "add a loop")
        assertTrue(prompt.contains("<cursor/>}"))
        assertTrue(prompt.contains("Write code to insert where <cursor/> is"))
        assertFalse(prompt.contains("<selection>"))
    }

    @Test
    fun `long files are cut to the lines around the selection`() {
        val long = (1..5000).joinToString("\n") { "line $it" }
        val start = long.indexOf("line 2500")
        val prompt = InlineEditPrompt.build("Big.kt", "kt", long, start, start + "line 2500".length, "x")
        assertTrue(prompt.contains("<selection>line 2500</selection>"))
        assertTrue(prompt.contains("// … (2199 lines above not shown)"))
        assertTrue(prompt.contains("// … (2200 lines below not shown)"))
        assertFalse(prompt.contains("line 10\n"))
    }

    @Test
    fun `the reply's code block is the new text, keeping the selection's last line break`() {
        val reply = "```kotlin\n    val name = args.getOrNull(0) ?: \"world\"\n    println(\"Hello, \$name!\")\n```"
        assertEquals(
            "    val name = args.getOrNull(0) ?: \"world\"\n    println(\"Hello, \$name!\")\n",
            InlineEditPrompt.parseReply(reply, "selected lines\n"),
        )
        assertEquals("x + 1", InlineEditPrompt.parseReply("Sure:\n```\nx + 1\n```\nDone.", "x"))
        assertEquals("x + 1", InlineEditPrompt.parseReply("x + 1\n", "x"))
        assertNull(InlineEditPrompt.parseReply("```\n\n```", "x"))
    }

    @Test
    fun `inserted code gets its own line and isn't indented twice`() {
        val text = "fun main() {\n    foo()\n}\n"
        // Caret before "}": the code goes on its own line above it.
        assertEquals("    bar()\n", InlineEditPrompt.fitInsertion("    bar()", text, text.indexOf("}")))
        // Caret after the indent of "    foo()": the indent isn't repeated, and foo() keeps its own.
        assertEquals("bar()\n    ", InlineEditPrompt.fitInsertion("    bar()", text, text.indexOf("foo")))
        // Caret on an empty line: as is.
        val empty = "fun main() {\n    \n}\n"
        assertEquals("bar()", InlineEditPrompt.fitInsertion("    bar()", empty, empty.indexOf("\n}")))
        // Caret after code on the line: as is.
        assertEquals(" + 1", InlineEditPrompt.fitInsertion(" + 1", "val x = 1\n", 9))
    }
}
