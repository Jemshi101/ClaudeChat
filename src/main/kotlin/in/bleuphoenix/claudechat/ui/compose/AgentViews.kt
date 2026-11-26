package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ui.AgentProgress
import `in`.bleuphoenix.claudechat.ui.ContextUsage
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** A sub-agent's live line under its tool card header: what it's doing, and how much it has done. */
@Composable
fun AgentProgressLine(progress: AgentProgress, modifier: Modifier = Modifier) {
    val colors = chatColors()
    val parts = listOfNotNull(
        progress.activity?.takeIf { !progress.done } ?: "Done".takeIf { progress.done },
        progress.toolUses.takeIf { it > 0 }?.let { if (it == 1) "1 tool call" else "$it tool calls" },
        progress.tokens.takeIf { it > 0 }?.let { "${compactCount(it)} tokens" },
    )
    if (parts.isEmpty()) return
    Text(parts.joinToString(" · "), style = smallStyle(), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * A small ring showing how full the context window is, with the numbers in a tooltip. Clicking offers to compact the
 * conversation, now or keeping something in focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextMeter(usage: ContextUsage, actions: ChatActions) {
    val colors = chatColors()
    val fraction = usage.fraction ?: return
    val color = when {
        fraction >= 0.85f -> colors.error
        fraction >= 0.6f -> colors.brand
        else -> colors.accent
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip(tooltip = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Context: ${compactCount(usage.used)} of ${compactCount(usage.window ?: 0)} tokens used")
                Text("Click to compact the conversation", color = colors.muted)
            }
        }) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { open = !open }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Canvas(Modifier.size(12.dp)) {
                    val stroke = size.minDimension * 0.18f
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(colors.border, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("${(fraction * 100).toInt().coerceAtLeast(if (usage.used > 0) 1 else 0)}%", style = smallStyle(), color = colors.muted)
            }
        }
        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.End,
            ) {
                selectableItem(selected = false, onClick = { open = false; actions.compact() }) {
                    Text("Compact now")
                }
                selectableItem(selected = false, onClick = { open = false; actions.compactWithFocus() }) {
                    Text("Compact, keeping in focus…")
                }
            }
        }
    }
}

/**
 * Above the message box once a chat is long (see [ContextUsage.isLong]): compacting or a new chat keeps Claude focused
 * and each message cheaper. Closing it hides it until the next compaction.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LongChatBar(usage: ContextUsage, actions: ChatActions, onClose: () -> Unit) {
    val colors = chatColors()
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) {
            Tooltip({
                Text(
                    "Every message sends the whole chat again. Compacting replaces it with a summary, which keeps " +
                        "Claude focused and each message cheaper; a new chat starts clean.",
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            }) {
                Text(
                    "Long chat · ${compactCount(usage.used)} tokens",
                    style = smallStyle(),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Link("Compact now", onClick = actions::compact)
        Link("New chat", onClick = actions::newChat)
        Text(
            "✕",
            color = colors.muted,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClose)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 950, 15.2k, 1.2M. */
fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0).replace(".0M", "M")
    value >= 1_000 -> "%.1fk".format(value / 1_000.0).replace(".0k", "k")
    else -> value.toString()
}
