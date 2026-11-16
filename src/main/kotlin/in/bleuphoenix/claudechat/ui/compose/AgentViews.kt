package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ui.AgentProgress
import `in`.bleuphoenix.claudechat.ui.ContextUsage
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

/** A small ring showing how full the context window is, with the numbers in a tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextMeter(usage: ContextUsage) {
    val colors = chatColors()
    val fraction = usage.fraction ?: return
    val color = when {
        fraction >= 0.85f -> colors.error
        fraction >= 0.6f -> colors.brand
        else -> colors.accent
    }
    Tooltip(tooltip = {
        Text("Context: ${compactCount(usage.used)} of ${compactCount(usage.window ?: 0)} tokens used")
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
}

/** 950, 15.2k, 1.2M. */
fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0).replace(".0M", "M")
    value >= 1_000 -> "%.1fk".format(value / 1_000.0).replace(".0k", "k")
    else -> value.toString()
}
