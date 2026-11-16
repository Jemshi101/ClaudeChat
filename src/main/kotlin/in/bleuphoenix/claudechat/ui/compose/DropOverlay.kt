package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ui.ChatState
import org.jetbrains.jewel.ui.component.Text

/**
 * Takes files and images dropped anywhere on the chat, as in Cursor: from the project tree, editor tabs, Finder or
 * Explorer. Compose owns the panel's AWT drop target, so drops have to be handled here rather than with the IDE's
 * DnDManager.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.dropAttachments(state: ChatState, actions: ChatActions): Modifier {
    val target = remember(state, actions) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                state.dropping = true
            }

            override fun onExited(event: DragAndDropEvent) {
                state.dropping = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                state.dropping = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                state.dropping = false
                return actions.drop(event.awtTransferable)
            }
        }
    }
    // Not over the chat history, which has no message box to add them to.
    return dragAndDropTarget(shouldStartDragAndDrop = { state.history == null && actions.canDrop(it.awtTransferable) }, target = target)
}

/** Covers the chat while files are dragged over it, saying what dropping them does. */
@Composable
fun DropOverlay() {
    val colors = chatColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.8f))
            .padding(10.dp)
            .drawBehind {
                val radius = 10.dp.toPx()
                drawRoundRect(colors.accent.copy(alpha = 0.08f), cornerRadius = CornerRadius(radius))
                drawRoundRect(
                    colors.accent,
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Drop to add as context", fontWeight = FontWeight.SemiBold, color = colors.text)
            Text(
                "Files and folders are attached with @, images as images.",
                style = smallStyle(),
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
