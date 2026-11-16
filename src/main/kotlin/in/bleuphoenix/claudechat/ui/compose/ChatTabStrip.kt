package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text

/** The chats as tabs along the top, each with its own ×; scrolls sideways when they don't fit. */
@Composable
fun ChatTabStrip(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, onClose: (Int) -> Unit) {
    val colors = chatColors()
    Column(Modifier.fillMaxWidth().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            titles.forEachIndexed { index, title ->
                ChatTabView(title, selected = index == selected, onSelect = { onSelect(index) }, onClose = { onClose(index) })
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.6f)))
    }
}

@Composable
private fun ChatTabView(title: String, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val colors = chatColors()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)
    Column(Modifier.widthIn(max = 200.dp).width(IntrinsicSize.Max)) {
        Row(
            Modifier
                .clip(shape)
                .background(
                    when {
                        selected -> colors.userBubble
                        hovered -> colors.subtleFill
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onSelect)
                .padding(start = 10.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = smallStyle(),
                color = if (selected) colors.text else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            CloseButton(selected || hovered, onClose)
        }
        // The selected tab is underlined in Claude's orange.
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) colors.brand else Color.Transparent))
    }
}

/** Always there, so a tab can be closed without hunting for it; brighter on the selected or hovered tab. */
@Composable
private fun CloseButton(prominent: Boolean, onClick: () -> Unit) {
    val colors = chatColors()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) colors.text.copy(alpha = 0.12f) else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CrossGlyph(if (prominent || hovered) colors.text.copy(alpha = 0.8f) else colors.muted.copy(alpha = 0.6f), Modifier.size(8.dp))
    }
}
