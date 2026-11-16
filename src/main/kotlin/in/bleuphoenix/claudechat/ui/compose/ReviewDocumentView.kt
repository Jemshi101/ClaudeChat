package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.review.FileReview
import `in`.bleuphoenix.claudechat.review.Hunk
import `in`.bleuphoenix.claudechat.review.ReviewActions
import `in`.bleuphoenix.claudechat.review.ReviewState
import `in`.bleuphoenix.claudechat.review.ReviewText
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

private const val CONTEXT_LINES = 2
private const val MAX_HUNK_LINES = 400

/** The "Claude Changes" tab: every pending change, file by file, each hunk with Reject and Accept. */
@Composable
fun ReviewDocumentView(review: ReviewState, actions: ReviewActions) {
    val colors = chatColors()
    val pending = review.pending
    Column(Modifier.fillMaxSize().background(colors.background)) {
        if (pending.isEmpty()) {
            NothingToReview()
            return@Column
        }
        ReviewActionBar(pending, actions)
        VerticallyScrollableContainer(modifier = Modifier.weight(1f).fillMaxWidth(), scrollState = rememberScrollState()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    pending.forEach { file -> key(file.path) { FileSection(file, actions) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewActionBar(pending: List<FileReview>, actions: ReviewActions) {
    val colors = chatColors()
    Column(Modifier.fillMaxWidth().background(colors.subtleFill)) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review changes", fontWeight = FontWeight.SemiBold)
                Text(filesLabel(pending.size), style = smallStyle(), color = colors.muted)
                DiffStat(pending.sumOf { it.added }, pending.sumOf { it.removed })
            }
            Spacer(Modifier.widthIn(min = 8.dp))
            OutlinedButton(onClick = { actions.rejectFiles(pending) }) { Text("Reject all") }
            DefaultButton(onClick = { actions.acceptFiles(pending) }) { Text("Accept all") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun NothingToReview() {
    val colors = chatColors()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CheckGlyph(colors.success)
        Text("No changes to review", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        Text(
            "Files Claude changes show up here, to accept or reject change by change.",
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FileSection(file: FileReview, actions: ReviewActions) {
    val colors = chatColors()
    val currentLines = remember(file.current) { ReviewText.lines(file.current.orEmpty()) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DocumentGlyph(colors.muted)
            Text(
                file.name,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.openAt(file, file.hunks.first().currentStart) },
            )
            if (file.created) Badge("New")
            Text(
                file.path.substringBeforeLast('/', "").replace(System.getProperty("user.home"), "~"),
                style = smallStyle(),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DiffStat(file.added, file.removed)
            Pill("Reject file", primary = false) { actions.rejectFiles(listOf(file)) }
            Pill("Accept file", primary = true) { actions.acceptFiles(listOf(file)) }
        }
        file.hunks.forEach { hunk -> HunkView(file, hunk, currentLines, actions) }
    }
}

/** One change: a little context, the removed lines, the added lines, and its own Reject and Accept. */
@Composable
private fun HunkView(file: FileReview, hunk: Hunk, currentLines: List<String>, actions: ReviewActions) {
    val colors = chatColors()
    val shape = RoundedCornerShape(8.dp)
    val rows = remember(hunk, currentLines) { diffRows(hunk, currentLines) }
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border.copy(alpha = 0.8f), shape).background(colors.codeFill)) {
        Row(
            Modifier.fillMaxWidth().background(colors.subtleFill).padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Line ${hunk.currentStart + 1}",
                style = smallStyle(),
                color = colors.muted,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.openAt(file, hunk.currentStart) },
            )
            Spacer(Modifier.weight(1f))
            Pill("Reject", primary = false) { actions.rejectHunk(file, hunk) }
            Pill("Accept", primary = true) { actions.acceptHunk(file, hunk) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Long lines scroll sideways; row backgrounds stretch to the widest line.
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .width(IntrinsicSize.Max)
                    .widthIn(min = maxWidth)
                    .padding(vertical = 4.dp),
            ) {
                rows.forEach { row -> DiffLine(row) }
            }
        }
    }
}

private enum class RowKind { Context, Removed, Added, Elided }

private class DiffRow(val kind: RowKind, val number: Int?, val text: String)

/** The rows of a hunk in unified-diff order, with [CONTEXT_LINES] of unchanged text around it. */
private fun diffRows(hunk: Hunk, current: List<String>): List<DiffRow> {
    val rows = mutableListOf<DiffRow>()
    val before = (hunk.currentStart - CONTEXT_LINES).coerceAtLeast(0) until hunk.currentStart
    before.forEach { rows += DiffRow(RowKind.Context, it + 1, current[it]) }
    hunk.removed.forEachIndexed { i, line -> rows += DiffRow(RowKind.Removed, hunk.baseStart + i + 1, line) }
    hunk.added.forEachIndexed { i, line -> rows += DiffRow(RowKind.Added, hunk.currentStart + i + 1, line) }
    val afterStart = hunk.currentStart + hunk.added.size
    // A final newline splits off an empty last "line"; it isn't worth showing as context.
    val lineCount = if (current.lastOrNull() == "") current.size - 1 else current.size
    (afterStart until (afterStart + CONTEXT_LINES).coerceAtMost(lineCount)).forEach {
        rows += DiffRow(RowKind.Context, it + 1, current[it])
    }
    if (rows.size <= MAX_HUNK_LINES) return rows
    return rows.take(MAX_HUNK_LINES) + DiffRow(RowKind.Elided, null, "… ${rows.size - MAX_HUNK_LINES} more lines")
}

@Composable
private fun DiffLine(row: DiffRow) {
    val colors = chatColors()
    val (background, sign, signColor) = when (row.kind) {
        RowKind.Removed -> Triple(colors.error.copy(alpha = 0.13f), "−", colors.error)
        RowKind.Added -> Triple(colors.success.copy(alpha = 0.13f), "+", colors.success)
        RowKind.Context, RowKind.Elided -> Triple(Color.Transparent, " ", colors.muted)
    }
    Row(Modifier.fillMaxWidth().background(background).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            row.number?.toString().orEmpty(),
            style = monoStyle(),
            color = colors.muted.copy(alpha = 0.7f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp).padding(end = 8.dp),
        )
        Text(sign, style = monoStyle(), color = signColor, modifier = Modifier.width(14.dp))
        Text(
            row.text.replace("\t", "    ").ifEmpty { " " },
            style = monoStyle(),
            color = if (row.kind == RowKind.Context || row.kind == RowKind.Elided) colors.muted else colors.text,
            softWrap = false,
            maxLines = 1,
        )
    }
}

/** "+12 −3" in the usual green and red. */
@Composable
fun DiffStat(added: Int, removed: Int) {
    val colors = chatColors()
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (added > 0) Text("+$added", style = smallStyle(), color = colors.success)
        if (removed > 0) Text("−$removed", style = smallStyle(), color = colors.error)
    }
}

/** A compact button for actions repeated on every file and hunk, lighter than a full Jewel button. */
@Composable
fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    val colors = chatColors()
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        style = smallStyle(),
        color = if (primary) colors.onBrand else colors.text,
        modifier = Modifier
            .clip(shape)
            .then(if (primary) Modifier.background(colors.success) else Modifier.border(1.dp, colors.border, shape))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

@Composable
private fun Badge(text: String) {
    val colors = chatColors()
    Text(
        text,
        style = smallStyle(),
        color = colors.success,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(colors.success.copy(alpha = 0.14f)).padding(horizontal = 6.dp),
    )
}

fun filesLabel(count: Int) = if (count == 1) "1 file" else "$count files"
