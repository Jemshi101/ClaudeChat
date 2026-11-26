package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ui.ChatState
import `in`.bleuphoenix.claudechat.ui.ContextMessage
import `in`.bleuphoenix.claudechat.ui.DebugMode
import `in`.bleuphoenix.claudechat.ui.PermissionMode
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text

/** The message box: grows with its text, with the mode selector and a send button that turns into Stop. */
@Composable
fun Composer(state: ChatState, actions: ChatActions) {
    val colors = chatColors()
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // In the mode's color: bright while typing, softer otherwise.
    val mode = modeColor(state.mode.id)
    val borderColor by animateColorAsState(if (focused) mode else mode.copy(alpha = 0.45f), tween(150), label = "border")
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(state.focusRequests) {
        if (state.focusRequests > 0) focusRequester.requestFocus()
    }

    // Typing "@name" looks up files; Escape hides the list until the mention changes.
    var dismissedMention by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.input) {
        snapshotFlow { state.input.text.toString() to state.input.selection.end }.collect { (text, cursor) ->
            val mention = ContextMessage.mentionQuery(text, cursor)
            if (mention == null || mention.second != dismissedMention) dismissedMention = null
            if (mention == null || dismissedMention != null) actions.mentionChanged(null, 0)
            else actions.mentionChanged(mention.first, mention.second)
        }
    }

    // A "/" at the start, or after a space later on, lists commands; Escape hides it until the message changes.
    var dismissedSlash by remember { mutableStateOf(false) }
    LaunchedEffect(state.input) {
        snapshotFlow { state.input.text.toString() to state.input.selection.end }.collect { (text, cursor) ->
            val query = ContextMessage.slashQuery(text, cursor)
            if (query == null) dismissedSlash = false
            if (dismissedSlash || query == null) actions.slashChanged(null) else actions.slashChanged(query.first, query.second)
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
        state.mention?.let { mention ->
            Box(Modifier.padding(bottom = 6.dp)) { MentionPopup(mention, actions) }
        }
        state.slash?.let { slash ->
            Box(Modifier.padding(bottom = 6.dp)) { SlashPopup(slash, actions) }
        }
        ActivityStrip(state, actions)
        DebugMode.pendingSteps(state)?.let { steps ->
            Box(Modifier.padding(bottom = 6.dp)) { ReproductionBar(steps, actions) }
        }
        if (state.queued.isNotEmpty()) {
            Box(Modifier.padding(bottom = 6.dp)) { QueuedMessages(state.queued, actions) }
        }
        state.context?.takeIf { it.isLong && !state.busy && !state.longChatHintDismissed }?.let { usage ->
            Box(Modifier.padding(bottom = 6.dp)) { LongChatBar(usage, actions) { state.longChatHintDismissed = true } }
        }
        // A message typed while Claude works: wait for it, send it now, or stop Claude and send it.
        if (state.busy && state.editing == null && state.input.text.isNotBlank()) {
            Box(Modifier.padding(bottom = 6.dp)) { WhileWorkingChoices(actions) }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, borderColor, shape)
                .background(colors.subtleFill)
                .animateContentSize(tween(120))
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.editing?.let { EditingBanner(actions) }
            ContextRow(state, actions, onAddContext = {
                // Starts a mention at the cursor, which opens the file list.
                val text = state.input.text
                val cursor = state.input.selection.end
                val prefix = if (cursor > 0 && !text[cursor - 1].isWhitespace()) " @" else "@"
                state.input.edit { replace(cursor, cursor, prefix) }
                focusRequester.requestFocus()
            })
            Box(Modifier.fillMaxWidth().padding(end = 4.dp)) {
                if (state.input.text.isEmpty()) {
                    Text(
                        if (state.busy) "Queue a message for when Claude finishes…" else "Ask Claude…  @ for files, / for commands",
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    state = state.input,
                    textStyle = JewelTheme.defaultTextStyle.copy(color = colors.text),
                    outputTransformation = remember(state.commands, colors) { highlightTransformation(state.commands, colors) },
                    cursorBrush = SolidColor(colors.text),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 10),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            // Enter sends; Shift+Enter falls through to the text field and inserts a line break.
                            // Shift+Tab cycles the mode, as in the Claude Code CLI. While the @ file list is open,
                            // Up/Down/Enter/Tab/Escape drive it instead, and the same for the / command list.
                            val mention = state.mention
                            val slash = state.slash
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                slash != null && event.key == Key.DirectionDown -> {
                                    actions.moveSlashSelection(1)
                                    true
                                }
                                slash != null && event.key == Key.DirectionUp -> {
                                    actions.moveSlashSelection(-1)
                                    true
                                }
                                // Enter on a fully typed command sends it; otherwise Enter or Tab completes the name
                                // (and, later in the message, moves the command to the front).
                                slash != null && slash.start == 0 && event.key == Key.Enter && !event.isShiftPressed &&
                                    slash.results.getOrNull(slash.selected)?.name == slash.query -> {
                                    actions.send()
                                    true
                                }
                                slash != null && (event.key == Key.Enter || event.key == Key.Tab) && !event.isShiftPressed -> {
                                    slash.results.getOrNull(slash.selected)?.let(actions::pickSlash)
                                    true
                                }
                                slash != null && event.key == Key.Escape -> {
                                    dismissedSlash = true
                                    actions.slashChanged(null)
                                    true
                                }
                                mention != null && event.key == Key.DirectionDown -> {
                                    actions.moveMentionSelection(1)
                                    true
                                }
                                mention != null && event.key == Key.DirectionUp -> {
                                    actions.moveMentionSelection(-1)
                                    true
                                }
                                mention != null && (event.key == Key.Enter || event.key == Key.Tab) && !event.isShiftPressed -> {
                                    mention.results.getOrNull(mention.selected)?.let(actions::pickMention)
                                    true
                                }
                                mention != null && event.key == Key.Escape -> {
                                    dismissedMention = mention.start
                                    actions.mentionChanged(null, 0)
                                    true
                                }
                                state.editing != null && event.key == Key.Escape -> {
                                    actions.cancelEdit()
                                    true
                                }
                                event.key == Key.V && (event.isMetaPressed || event.isCtrlPressed) -> actions.pasteAttachments()
                                // While Claude works: Alt+Enter sends now, Cmd/Ctrl+Enter stops Claude and sends,
                                // Enter queues (below).
                                state.busy && event.key == Key.Enter && event.isAltPressed -> {
                                    actions.sendNow()
                                    true
                                }
                                state.busy && event.key == Key.Enter && (event.isMetaPressed || event.isCtrlPressed) -> {
                                    actions.stopAndSend()
                                    true
                                }
                                event.key == Key.Enter && !event.isShiftPressed -> {
                                    actions.send()
                                    true
                                }
                                event.key == Key.Tab && event.isShiftPressed -> {
                                    actions.selectMode(PermissionMode.next(state.mode))
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeSelector(state.mode, actions)
                ModelSelector(state.models, state.model, actions)
                EffortSelector(state.models, state.model, state.effort, actions)
                Spacer(Modifier.weight(1f))
                if (state.mcpServers.isNotEmpty()) McpIndicator(state.mcpServers, actions)
                state.context?.let { ContextMeter(it, actions) }
                SendButton(busy = state.busy, canSend = state.input.text.isNotBlank(), actions = actions)
            }
        }
    }
}

/** Shown while an earlier message is being edited: sending replaces it and everything after it. */
@Composable
private fun EditingBanner(actions: ChatActions) {
    val colors = chatColors()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Editing message", style = smallStyle(), color = colors.brand)
        Text(
            "sending replaces it and what came after",
            style = smallStyle(),
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ActionLink("Cancel") { actions.cancelEdit() }
    }
}

/** A quiet chip showing the permission mode; clicking it opens a menu of modes. */
@Composable
private fun ModeSelector(mode: PermissionMode, actions: ChatActions) {
    val colors = chatColors()
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box {
        val color = modeColor(mode.id)
        Row(
            Modifier
                .clip(shape)
                .border(1.dp, color.copy(alpha = 0.5f), shape)
                .background(color.copy(alpha = 0.1f))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { open = !open }
                .padding(start = 8.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(mode.label, style = smallStyle(), color = color, fontWeight = FontWeight.Medium)
            ChevronGlyph(color, Modifier.rotate(90f))
        }
        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
            ) {
                PermissionMode.CHOICES.forEach { choice ->
                    selectableItem(
                        selected = choice == mode,
                        onClick = {
                            open = false
                            actions.selectMode(choice)
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(modeColor(choice.id)))
                            Text(choice.label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Round brand-colored send button. While Claude works it's a Stop button, until something is typed: then it sends
 * (queues the message, like Enter), and turns back into Stop when the box is cleared.
 */
@Composable
private fun SendButton(busy: Boolean, canSend: Boolean, actions: ChatActions) {
    val colors = chatColors()
    val stops = busy && !canSend
    val enabled = busy || canSend
    val background by animateColorAsState(
        when {
            stops -> colors.text
            canSend -> colors.brand
            else -> colors.userBubble
        },
        tween(180),
        label = "send",
    )
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { if (stops) actions.stop() else actions.send() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = stops,
            transitionSpec = { (fadeIn(tween(150)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(100)) + scaleOut(targetScale = 0.6f)) },
            label = "sendIcon",
        ) { isStop ->
            if (isStop) StopGlyph(colors.background) else ArrowUpGlyph(if (canSend) colors.onBrand else colors.muted)
        }
    }
}
