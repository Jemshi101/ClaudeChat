package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.cli.CliCatalog
import `in`.bleuphoenix.claudechat.cli.Effort
import `in`.bleuphoenix.claudechat.cli.ModelOption
import `in`.bleuphoenix.claudechat.ui.QueuedMessage
import `in`.bleuphoenix.claudechat.ui.SlashState
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text

/** Commands matching the `/` being typed; Up/Down choose, Enter or Tab picks, Escape closes. */
@Composable
fun SlashPopup(slash: SlashState, actions: ChatActions) {
    val colors = chatColors()
    val shape = RoundedCornerShape(10.dp)
    val listState = rememberLazyListState()
    LaunchedEffect(slash.selected) { listState.animateScrollToItem((slash.selected - 3).coerceAtLeast(0)) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .background(colors.background)
            .padding(vertical = 4.dp),
    ) {
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(slash.results, key = { _, command -> command.name }) { index, command ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (index == slash.selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { actions.pickSlash(command) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("/${command.name}", fontWeight = FontWeight.Medium, maxLines = 1)
                    if (command.argumentHint.isNotEmpty()) {
                        Text(command.argumentHint, style = smallStyle(), color = colors.muted, maxLines = 1)
                    }
                    Text(
                        command.description,
                        style = smallStyle(),
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * For a message typed while Claude works: queue it until Claude finishes (Enter), send it now for Claude to read at
 * its next step (Alt+Enter), or stop Claude and send it (Cmd/Ctrl+Enter).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhileWorkingChoices(actions: ChatActions) {
    val colors = chatColors()
    val mac = remember { System.getProperty("os.name").orEmpty().startsWith("Mac") }
    // Wraps in a narrow panel rather than squeezing a button.
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Claude is working:", style = smallStyle(), color = colors.muted, maxLines = 1)
        ChoicePill("Queue", "Enter", actions::send)
        ChoicePill("Send now", if (mac) "⌥Enter" else "Alt+Enter", actions::sendNow)
        ChoicePill("Stop & send", if (mac) "⌘Enter" else "Ctrl+Enter", actions::stopAndSend)
    }
}

@Composable
private fun ChoicePill(label: String, shortcut: String, onClick: () -> Unit) {
    val colors = chatColors()
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, style = smallStyle(), color = colors.text, maxLines = 1, softWrap = false)
        Text(shortcut, style = smallStyle(), color = colors.muted, maxLines = 1, softWrap = false)
    }
}

/** Messages waiting for Claude to finish: click one to edit it, send it now, or remove it with ×. */
@Composable
fun QueuedMessages(queued: List<QueuedMessage>, actions: ChatActions) {
    val colors = chatColors()
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .background(colors.subtleFill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (queued.size == 1) "Queued · sent when Claude finishes" else "${queued.size} queued · sent in order when Claude finishes",
            style = smallStyle(),
            color = colors.muted,
        )
        queued.forEach { message ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    message.text.lineSequence().first() + if (message.attachments.isEmpty()) "" else "  ·  ${message.attachments.size} attached",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { actions.unqueue(message, edit = true) },
                )
                ActionLink("Send now") { actions.sendQueuedNow(message, stopFirst = false) }
                ActionLink("Stop & send") { actions.sendQueuedNow(message, stopFirst = true) }
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { actions.unqueue(message, edit = false) },
                    contentAlignment = Alignment.Center,
                ) { CrossGlyph(colors.muted, Modifier.size(8.dp)) }
            }
        }
    }
}

/**
 * The effort level, as a quiet chip beside the model; clicking it lists the levels the model supports. Hidden for a
 * model without effort levels, unless one was chosen.
 */
@Composable
fun EffortSelector(models: List<ModelOption>, model: String?, selected: String?, actions: ChatActions) {
    val levels = Effort.levelsFor(models, model)
    if (levels.isEmpty() && selected == null) return
    val colors = chatColors()
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box {
        Row(
            Modifier
                .clip(shape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { open = !open }
                .padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (selected == null) "Effort" else "${Effort.label(selected)} effort",
                style = smallStyle(),
                color = colors.muted,
                maxLines = 1,
            )
            ChevronGlyph(colors.muted, Modifier.rotate(90f))
        }
        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
            ) {
                (listOf<String?>(null) + levels).forEach { level ->
                    selectableItem(
                        selected = level == selected,
                        onClick = {
                            open = false
                            actions.selectEffort(level)
                        },
                    ) {
                        Column(Modifier.widthIn(max = 280.dp)) {
                            Text(Effort.label(level))
                            Text(Effort.description(level), style = smallStyle(), color = colors.muted, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** The model in use, as a quiet chip like the mode selector; clicking it lists the models the CLI offers. */
@Composable
fun ModelSelector(models: List<ModelOption>, selected: String?, actions: ChatActions) {
    if (models.isEmpty() && selected == null) return
    val colors = chatColors()
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box {
        Row(
            Modifier
                .clip(shape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(enabled = models.isNotEmpty()) { open = !open }
                .padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(CliCatalog.label(models, selected), style = smallStyle(), color = colors.muted, maxLines = 1)
            if (models.isNotEmpty()) ChevronGlyph(colors.muted, Modifier.rotate(90f))
        }
        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
            ) {
                models.forEach { model ->
                    selectableItem(
                        selected = model.value == (selected ?: CliCatalog.DEFAULT),
                        onClick = {
                            open = false
                            actions.selectModel(model.value)
                        },
                    ) {
                        Column(Modifier.widthIn(max = 280.dp)) {
                            Text(model.displayName)
                            if (model.description.isNotEmpty()) {
                                Text(model.description, style = smallStyle(), color = colors.muted, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}
