package `in`.bleuphoenix.claudechat.ui

import `in`.bleuphoenix.claudechat.ui.compose.MarkdownSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSegmentTest {

    @Test
    fun `flowcharts become diagrams between text runs`() {
        val segments = MarkdownSegment.split("Intro\n\n```mermaid\nflowchart TD\n  A --> B\n```\n\nOutro")
        assertEquals(3, segments.size)
        assertEquals("Intro\n\n", (segments[0] as MarkdownSegment.Text).markdown)
        assertEquals(2, (segments[1] as MarkdownSegment.Diagram).chart.nodes.size)
        assertEquals("\n\nOutro", (segments[2] as MarkdownSegment.Text).markdown)
    }

    @Test
    fun `unsupported diagrams are code blocks and unclosed blocks stay as markdown`() {
        val sequence = "```mermaid\nsequenceDiagram\n  A->>B: hi\n```"
        assertEquals(listOf(MarkdownSegment.Code("mermaid", "sequenceDiagram\n  A->>B: hi", sequence)), MarkdownSegment.split(sequence))

        // Mid-stream: the closing fence hasn't arrived yet.
        val streaming = "Plan:\n```mermaid\nflowchart TD\n  A --> B"
        assertEquals(listOf(MarkdownSegment.Text(streaming)), MarkdownSegment.split(streaming))
    }

    @Test
    fun `code blocks are their own segments with language and code`() {
        val segments = MarkdownSegment.split("Use this:\n\n```kotlin title=\"x\"\nval x = 1\nprintln(x)\n```\nDone.")
        assertEquals(3, segments.size)
        val code = segments[1] as MarkdownSegment.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertTrue(segments[2] is MarkdownSegment.Text)
    }

    @Test
    fun `a code block knows its file from the fence or the line before it`() {
        fun path(markdown: String) = (MarkdownSegment.split(markdown).single { it is MarkdownSegment.Code } as MarkdownSegment.Code).path

        assertEquals("app/Main.kt", path("```kotlin app/Main.kt\nval x = 1\n```"))
        assertEquals("app/Main.kt", path("```kotlin:app/Main.kt\nval x = 1\n```"))
        assertEquals("Main.kt", path("```kotlin title=\"Main.kt\"\nval x = 1\n```"))
        assertEquals("src/Util.kt", path("First read `README.md`.\n\nThen in `src/Util.kt:12`, change it to:\n\n```kotlin\nval x = 1\n```"))
        // Only the paragraph just before the block counts.
        assertEquals(null, path("Look at `README.md`.\n\nChange it to:\n\n```kotlin\nval x = 1\n```"))
        assertEquals(null, path("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun `a path in the fence is left out of the fence the renderer highlights`() {
        val code = MarkdownSegment.split("```kotlin:app/Main.kt\nval x = 1\n```").single() as MarkdownSegment.Code
        assertEquals("kotlin", code.language)
        assertEquals("```kotlin\nval x = 1\n```", code.fence)
    }

    @Test
    fun `a longer fence can contain a shorter one`() {
        val markdown = "````md\n```kotlin\nval x = 1\n```\n````"
        val code = MarkdownSegment.split(markdown).single() as MarkdownSegment.Code
        assertEquals("```kotlin\nval x = 1\n```", code.code)
    }

    @Test
    fun `plain markdown is one segment`() {
        assertTrue(MarkdownSegment.split("Just **text**.").single() is MarkdownSegment.Text)
    }
}
