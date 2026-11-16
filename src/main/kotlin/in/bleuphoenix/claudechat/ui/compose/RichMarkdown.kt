package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.edit.ApplyCode
import `in`.bleuphoenix.claudechat.ide.FileLink
import `in`.bleuphoenix.claudechat.mermaid.Flowchart
import `in`.bleuphoenix.claudechat.mermaid.FlowchartParser
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** A run of markdown, a complete fenced code block, a Mermaid block that parsed as a flowchart, or a table. */
sealed interface MarkdownSegment {
    data class Text(val markdown: String) : MarkdownSegment
    /**
     * A fenced code block. [path] is the file it's for, when the fence names one (```` ```kotlin app/Main.kt ````)
     * or the text just before it does ("In `app/Main.kt`:"); Apply uses it.
     */
    data class Code(val language: String, val code: String, val fence: String, val path: String? = null) : MarkdownSegment
    data class Diagram(val chart: Flowchart) : MarkdownSegment

    /**
     * A task list ("- [ ] step"), drawn as a to-do card. [first] is the document-wide index of its first task (see
     * [TaskLists]), so a click can tick the right line in the file.
     */
    data class Todos(val items: List<TaskLists.Task>, val first: Int) : MarkdownSegment

    /** A GitHub table; cells hold inline markdown. */
    data class Table(val header: List<String>, val alignments: List<TableAlignment>, val rows: List<List<String>>) : MarkdownSegment

    companion object {
        // Only complete, top-level blocks: while a reply streams in, an unclosed block stays in the markdown until its
        // closing fence arrives.
        private val FENCED_BLOCK = Regex("""(?ms)^[ \t]{0,3}(`{3,})[ \t]*([^`\n]*)\n(.*?)^[ \t]{0,3}\1[ \t]*$""")

        private val DELIMITER_ROW = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

        fun split(markdown: String): List<MarkdownSegment> {
            // Tasks are numbered across the document (outside code blocks), in order, as TaskLists counts them.
            var taskIndex = 0
            return splitFences(markdown)
                .flatMap { if (it is Text) splitTables(it.markdown) else listOf(it) }
                .flatMap { segment ->
                    if (segment !is Text) return@flatMap listOf(segment)
                    TaskLists.split(segment.markdown, taskIndex).also { parts ->
                        taskIndex += parts.filterIsInstance<Todos>().sumOf { it.items.size }
                    }
                }
        }

        /**
         * Tables (a "| a | b |" row, a "|---|---|" row, then rows) drawn by the chat, even right after a line of text,
         * as GitHub does.
         */
        fun splitTables(markdown: String): List<MarkdownSegment> {
            val lines = markdown.lines()
            val segments = mutableListOf<MarkdownSegment>()
            val text = mutableListOf<String>()
            fun flushText() {
                text.joinToString("\n").takeIf { it.isNotBlank() }?.let { segments += Text(it) }
                text.clear()
            }
            var i = 0
            while (i < lines.size) {
                val header = cells(lines[i])
                val delimiter = lines.getOrNull(i + 1)
                if ('|' in lines[i] && header.size > 1 && delimiter != null && DELIMITER_ROW.matches(delimiter) &&
                    cells(delimiter).size == header.size
                ) {
                    val alignments = cells(delimiter).map {
                        when {
                            it.startsWith(':') && it.endsWith(':') -> TableAlignment.Center
                            it.endsWith(':') -> TableAlignment.End
                            else -> TableAlignment.Start
                        }
                    }
                    var end = i + 2
                    val rows = mutableListOf<List<String>>()
                    while (end < lines.size && '|' in lines[end] && lines[end].isNotBlank()) {
                        rows += cells(lines[end]).let { row -> List(header.size) { row.getOrElse(it) { "" } } }
                        end++
                    }
                    flushText()
                    segments += Table(header, alignments, rows)
                    i = end
                } else {
                    text += lines[i]
                    i++
                }
            }
            flushText()
            return segments
        }

        /** A row's cells: split on "|" outside `code` and not escaped, without the outer pipes. */
        fun cells(row: String): List<String> {
            val cells = mutableListOf<String>()
            val cell = StringBuilder()
            var inCode = false
            var k = 0
            val trimmed = row.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
            while (k < trimmed.length) {
                val c = trimmed[k]
                when {
                    c == '\\' && trimmed.getOrNull(k + 1) == '|' -> { cell.append('|'); k++ }
                    c == '`' -> { inCode = !inCode; cell.append(c) }
                    c == '|' && !inCode -> { cells += cell.toString().trim(); cell.clear() }
                    else -> cell.append(c)
                }
                k++
            }
            cells += cell.toString().trim()
            return cells
        }

        /** A path in a fence's info string after the language: `kotlin app/Main.kt`, `kotlin:app/Main.kt`, `kotlin title="Main.kt"`. */
        private fun pathInInfo(info: String, language: String): String? = info.removePrefix(language)
            .split(' ', ':')
            .map { it.substringAfter('=').trim('"', '\'', '`') }
            .firstOrNull { it.contains('.') || it.contains('/') }

        private fun splitFences(markdown: String): List<MarkdownSegment> {
            val segments = mutableListOf<MarkdownSegment>()
            var last = 0
            for (block in FENCED_BLOCK.findAll(markdown)) {
                val info = block.groupValues[2].trim()
                val language = info.substringBefore(' ').substringBefore(':')
                val code = block.groupValues[3].removeSuffix("\n")
                // The renderer highlights by the info string, so it keeps only the language.
                val fence = if (info == language) block.value else block.value.replaceFirst(info, language)
                val before = markdown.substring(last, block.range.first)
                val path = pathInInfo(info, language) ?: FileLink.lastPathIn(before.trimEnd().substringAfterLast("\n\n"))
                val segment = if (language == "mermaid") {
                    FlowchartParser.parse(code)?.let(::Diagram) ?: Code(language, code, fence)
                } else {
                    Code(language, code, fence, path)
                }
                markdown.substring(last, block.range.first).takeIf { it.isNotBlank() }?.let { segments += Text(it) }
                segments += segment
                last = block.range.last + 1
            }
            markdown.substring(last).takeIf { it.isNotBlank() }?.let { segments += Text(it) }
            return segments
        }
    }
}

/**
 * Markdown with file references linked, Mermaid flowcharts drawn in place, and code blocks with Copy and, when
 * given, Insert at the editor's caret ([onInsertCode]) and Apply to a file ([onApplyCode]). Task lists are to-do
 * cards; with [onEditTasks] (which applies
 * a change to the markdown, e.g. in the plan file) their tasks can be ticked and added.
 */
@Composable
fun RichMarkdown(
    markdown: String,
    onUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
    onCopy: ((String) -> Unit)? = null,
    onInsertCode: ((String) -> Unit)? = null,
    onApplyCode: ((MarkdownSegment.Code) -> Unit)? = null,
    onEditTasks: (((String) -> String) -> Unit)? = null,
) {
    val segments = remember(markdown) { MarkdownSegment.split(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Text -> {
                    val linked = remember(segment.markdown) { FileLink.linkifyMarkdown(segment.markdown) }
                    Markdown(linked, modifier = Modifier.fillMaxWidth(), selectable = selectable, onUrlClick = onUrlClick)
                }
                is MarkdownSegment.Diagram -> FlowchartView(segment.chart)
                is MarkdownSegment.Code -> CodeBlockView(segment, selectable, onCopy, onInsertCode, onApplyCode)
                is MarkdownSegment.Table -> TableView(segment, selectable, onUrlClick)
                is MarkdownSegment.Todos -> TodosView(segment, onUrlClick, onEditTasks)
            }
        }
    }
}

/**
 * A fenced code block as a rounded card: the language and actions on top, the code drawn by the markdown renderer
 * (keeping its highlighting) below.
 */
@OptIn(ExperimentalFoundationApi::class) // Jewel's Tooltip
@Composable
private fun CodeBlockView(
    block: MarkdownSegment.Code,
    selectable: Boolean,
    onCopy: ((String) -> Unit)?,
    onInsert: ((String) -> Unit)?,
    onApply: ((MarkdownSegment.Code) -> Unit)?,
) {
    val colors = chatColors()
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border.copy(alpha = 0.6f), shape)
            .background(colors.codeFill),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                listOfNotNull(block.language.ifEmpty { "code" }, block.path).joinToString(" · "),
                style = smallStyle(),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onApply != null && ApplyCode.applies(block.language)) {
                Tooltip({ Text(block.path?.let { "Merge this code into $it, then review the change" } ?: "Merge this code into the file open in the editor, then review the change") }) {
                    ActionLink("Apply") { onApply(block) }
                }
            }
            onInsert?.let { insert -> ActionLink("Insert at cursor") { insert(block.code) } }
            onCopy?.let { copy -> ActionLink("Copy") { copy(block.code) } }
        }
        Markdown(block.fence, modifier = Modifier.fillMaxWidth(), selectable = selectable, onUrlClick = {})
    }
}

/** A small muted text action that highlights on hover. */
@Composable
fun ActionLink(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = chatColors()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text,
        style = smallStyle(),
        color = if (hovered) colors.accent else colors.muted,
        modifier = modifier.then(
            if (enabled) Modifier.hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier,
        ),
    )
}
