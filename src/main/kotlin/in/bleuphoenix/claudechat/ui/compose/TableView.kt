package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ide.FileLink
import org.jetbrains.jewel.markdown.Markdown

enum class TableAlignment { Start, Center, End }

/**
 * A markdown table as a rounded card: a tinted header row, hairlines between rows, and columns as wide as their
 * content needs (up to a limit, wrapping beyond it). Wide tables scroll sideways.
 */
@Composable
fun TableView(table: MarkdownSegment.Table, selectable: Boolean, onUrlClick: (String) -> Unit) {
    val colors = chatColors()
    val shape = RoundedCornerShape(8.dp)
    // Each column wants a width from its longest cell, in characters, so short columns stay narrow; when they don't
    // all fit, they share the width in that proportion and their cells wrap.
    val wanted = remember(table) {
        table.header.indices.map { column ->
            val longest = (listOf(table.header[column]) + table.rows.map { it[column] }).maxOf { visibleLength(it) }
            (longest * 7.2f + 24f).coerceIn(MIN_COLUMN, 420f)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth.value - wanted.size - 2
        val scale = (available / wanted.sum()).coerceAtMost(1f)
        val widths = wanted.map { (it * scale).coerceAtLeast(MIN_COLUMN).dp }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .clip(shape)
                    .border(1.dp, colors.border, shape),
            ) {
                TableRow(table.header, table.alignments, widths, header = true, selectable, onUrlClick)
                table.rows.forEachIndexed { index, row ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.7f)))
                    TableRow(row, table.alignments, widths, header = false, selectable, onUrlClick, striped = index % 2 == 1)
                }
            }
        }
    }
}

private const val MIN_COLUMN = 64f

@Composable
private fun TableRow(
    cells: List<String>,
    alignments: List<TableAlignment>,
    widths: List<androidx.compose.ui.unit.Dp>,
    header: Boolean,
    selectable: Boolean,
    onUrlClick: (String) -> Unit,
    striped: Boolean = false,
) {
    val colors = chatColors()
    val background = when {
        header -> colors.text.copy(alpha = 0.06f)
        striped -> colors.text.copy(alpha = 0.025f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(Modifier.height(IntrinsicSize.Min).background(background)) {
        cells.forEachIndexed { column, cell ->
            if (column > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border.copy(alpha = 0.5f)))
            Box(
                Modifier.width(widths[column]).padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = when (alignments.getOrNull(column)) {
                    TableAlignment.Center -> Alignment.TopCenter
                    TableAlignment.End -> Alignment.TopEnd
                    else -> Alignment.TopStart
                },
            ) {
                val markdown = remember(cell, header) { FileLink.linkifyMarkdown(if (header && cell.isNotBlank()) "**$cell**" else cell) }
                Markdown(markdown, selectable = selectable, onUrlClick = onUrlClick)
            }
        }
    }
}

/** The length a cell shows: markdown markers like ` and ** don't take room. */
private fun visibleLength(cell: String) = cell.count { it != '`' && it != '*' && it != '_' }
