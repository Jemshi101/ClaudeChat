package `in`.bleuphoenix.claudechat.ui

import `in`.bleuphoenix.claudechat.ui.compose.MarkdownSegment
import `in`.bleuphoenix.claudechat.ui.compose.TableAlignment
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTablesTest {

    @Test
    fun `a table right after a line of text is a table, as on GitHub`() {
        val segments = MarkdownSegment.split(
            "It comes from two libraries:\n| Library | Adds |\n|---|--:|\n| `location` | `FOREGROUND_SERVICE` |\n| work | x |\n\nAfter.",
        )
        assertEquals(
            listOf(
                MarkdownSegment.Text("It comes from two libraries:"),
                MarkdownSegment.Table(
                    listOf("Library", "Adds"),
                    listOf(TableAlignment.Start, TableAlignment.End),
                    listOf(listOf("`location`", "`FOREGROUND_SERVICE`"), listOf("work", "x")),
                ),
                MarkdownSegment.Text("\nAfter."),
            ),
            segments,
        )
    }

    @Test
    fun `pipes inside code and escaped pipes stay in their cell`() {
        assertEquals(listOf("`a | b`", "c|d", ""), MarkdownSegment.cells("| `a | b` | c\\|d | |"))
    }

    @Test
    fun `pipes in ordinary text, and tables in code blocks, are not tables`() {
        val text = "Use a | b here\nand | more | text |\nnot a table"
        assertEquals(listOf(MarkdownSegment.Text(text)), MarkdownSegment.split(text))
        val fenced = "```\n| a | b |\n|---|---|\n```"
        assertEquals(1, MarkdownSegment.split(fenced).filterIsInstance<MarkdownSegment.Code>().size)
    }

    @Test
    fun `short rows are padded to the header's width`() {
        val table = MarkdownSegment.split("| a | b | c |\n|---|---|---|\n| 1 |").single() as MarkdownSegment.Table
        assertEquals(listOf(listOf("1", "", "")), table.rows)
    }
}
