package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.ide.FileLink
import `in`.bleuphoenix.claudechat.ui.AssistantTextItem
import `in`.bleuphoenix.claudechat.ui.ChangesItem
import `in`.bleuphoenix.claudechat.ui.ChatItem
import `in`.bleuphoenix.claudechat.ui.CompactedItem
import `in`.bleuphoenix.claudechat.ui.DebugMode
import `in`.bleuphoenix.claudechat.ui.LoginItem
import `in`.bleuphoenix.claudechat.ui.LoginStatus
import `in`.bleuphoenix.claudechat.ui.NoticeItem
import `in`.bleuphoenix.claudechat.ui.PermissionItem
import `in`.bleuphoenix.claudechat.ui.PlanItem
import `in`.bleuphoenix.claudechat.ui.ToolItem
import `in`.bleuphoenix.claudechat.ui.ToolStatus
import `in`.bleuphoenix.claudechat.ui.TurnMetaItem
import `in`.bleuphoenix.claudechat.ui.UserItem
import org.jetbrains.jewel.ui.component.Tooltip
import androidx.compose.foundation.ExperimentalFoundationApi
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

private const val MAX_TOOL_TEXT = 8000

@Composable
fun ChatItemView(
    item: ChatItem,
    actions: ChatActions,
    busy: Boolean = false,
    lastUserMessage: Boolean = false,
    commands: List<SlashCommand> = emptyList(),
    /** How many times in a row this failure happened (see [`in`.bleuphoenix.claudechat.ui.RepeatedFailures]). */
    repeats: Int = 1,
) {
    when (item) {
        is UserItem -> UserBubble(item, actions, busy, lastUserMessage, commands)
        is AssistantTextItem -> AssistantText(item, actions)
        is ToolItem -> ToolCard(item, actions, repeats)
        is PermissionItem -> PermissionCard(item, actions)
        is PlanItem -> PlanCard(item, actions)
        is ChangesItem -> ChangesCard(item, actions)
        is NoticeItem -> Notice(item, repeats)
        is CompactedItem -> CompactedDivider(item)
        is LoginItem -> LoginCard(item, actions)
        is TurnMetaItem -> TurnMeta(item)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserBubble(item: UserItem, actions: ChatActions, busy: Boolean, last: Boolean, commands: List<SlashCommand>) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shown by animateFloatAsState(if (hovered) 1f else 0f, tween(150))
    Column(Modifier.fillMaxWidth().hoverable(interaction), horizontalAlignment = Alignment.End) {
        UserBubbleBody(item, commands)
        if (item.sentWhileWorking) {
            Text("Sent while Claude worked", style = smallStyle(), color = chatColors().muted, modifier = Modifier.padding(top = 3.dp, end = 4.dp))
        }
        // Actions appear on hover; "Restore checkpoint" stays faintly visible, like Cursor's.
        Row(Modifier.padding(top = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionLink("Copy", Modifier.alpha(shown), enabled = hovered) { actions.copyText(item.text) }
            if (!busy) {
                ActionLink("Edit", Modifier.alpha(shown), enabled = hovered) { actions.editMessage(item) }
                if (last) ActionLink("Retry", Modifier.alpha(shown), enabled = hovered) { actions.retry(item) }
            }
            if (item.restorable) {
                ActionLink("Restore checkpoint", Modifier.alpha(0.45f + 0.55f * shown)) { actions.restoreCheckpoint(item) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserBubbleBody(item: UserItem, commands: List<SlashCommand>) {
    val colors = chatColors()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A "tail" corner at the bottom right points the bubble at its sender.
        val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp)
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .widthIn(max = maxWidth * 0.85f)
                .clip(shape)
                .background(colors.userBubble)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.attachments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.attachments.forEach { AttachmentChip(it) }
                }
            }
            val text = remember(item.text, commands, colors) { highlighted(item.text, commands, colors) }
            SelectionContainer { Text(text, style = readingStyle()) }
        }
    }
}

@Composable
private fun AssistantText(item: AssistantTextItem, actions: ChatActions) {
    val colors = chatColors()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(interaction)) {
        RichMarkdown(
            // Debug mode's reproduction steps show in the bar above the message box.
            DebugMode.withoutSteps(item.text),
            onUrlClick = actions::openLink,
            modifier = Modifier.fillMaxWidth(),
            onCopy = actions::copyText,
            onInsertCode = actions::insertCode,
            onApplyCode = actions::applyCode,
        )
        // Copies the reply as markdown; shown over its last line on hover, so it takes no room.
        if (hovered && item.text.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.userBubble)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                ActionLink("Copy") { actions.copyText(item.text) }
            }
        }
    }
}

@Composable
private fun ToolCard(item: ToolItem, actions: ChatActions, repeats: Int = 1) {
    val colors = chatColors()
    val expanded = item.expanded
    val chevronAngle by animateFloatAsState(if (expanded) 90f else 0f, tween(180, easing = FastOutSlowInEasing))
    val shape = RoundedCornerShape(8.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border.copy(alpha = 0.7f), shape)
            .background(colors.subtleFill),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { item.expanded = !item.expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolStatusIndicator(item.status)
            // A sub-agent is named by its type ("Explore"), like Cursor's agent cards.
            Text(item.agent?.type?.let { "$it agent" } ?: item.name, fontWeight = FontWeight.SemiBold)
            FileSummary(item, actions, Modifier.weight(1f))
            if (repeats > 1) RepeatBadge(repeats, "Failed $repeats times in a row with the same input; this is the last try.")
            if (item.diffable && item.status == ToolStatus.Succeeded) {
                Link("Diff", onClick = { actions.showDiff(item) })
            }
            ChevronGlyph(colors.muted, Modifier.rotate(chevronAngle))
        }
        item.agent?.let { AgentProgressLine(it, Modifier.padding(start = 32.dp, end = 10.dp, bottom = 7.dp)) }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionLabel("Input")
                CodeBlock(item.inputJson)
                item.result?.let { result ->
                    val failed = item.status == ToolStatus.Failed
                    SectionLabel(if (failed) "Error" else "Result")
                    CodeBlock(result, if (failed) colors.error else null)
                }
            }
        }
    }
}

/** The tool's main argument; when it's a file, hovering highlights it and clicking opens the file. */
@Composable
private fun FileSummary(item: ToolItem, actions: ChatActions, modifier: Modifier) {
    val colors = chatColors()
    val path = item.filePath
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val clickable = if (path != null) {
        Modifier.hoverable(interaction).clickable { actions.openFile(path) }
    } else {
        Modifier
    }
    Text(
        item.summary,
        style = monoStyle(),
        color = if (path != null && hovered) colors.accent else colors.muted,
        textDecoration = if (path != null && hovered) TextDecoration.Underline else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(clickable),
    )
}

@Composable
private fun ToolStatusIndicator(status: ToolStatus) {
    val colors = chatColors()
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ToolStatus.Running -> Spinner(colors.accent)
            ToolStatus.Succeeded -> CheckGlyph(colors.success)
            ToolStatus.Failed -> CrossGlyph(colors.error)
        }
    }
}

/** A rotating arc; lighter than a full progress indicator for inline use. */
@Composable
fun Spinner(color: Color) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(12.dp).rotate(angle)) {
        drawArc(color, startAngle = 0f, sweepAngle = 270f, useCenter = false, style = Stroke(size.minDimension * 0.16f, cap = StrokeCap.Round))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = smallStyle(), color = chatColors().muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CodeBlock(text: String, color: Color? = null) {
    val colors = chatColors()
    val shape = RoundedCornerShape(6.dp)
    val shown = remember(text) { if (text.length > MAX_TOOL_TEXT) text.take(MAX_TOOL_TEXT) + "\n… (truncated)" else text }
    Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(shape).background(colors.codeFill)) {
        VerticallyScrollableContainer(scrollState = rememberScrollState()) {
            SelectionContainer {
                Text(shown, style = monoStyle(), color = color ?: colors.text, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionCard(item: PermissionItem, actions: ChatActions) {
    val colors = chatColors()
    val pending = item.outcome == null
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(200))
            .clip(shape)
            .border(1.dp, if (pending) colors.brand.copy(alpha = 0.8f) else colors.border, shape)
            .background(if (pending) colors.brand.copy(alpha = 0.06f) else Color.Transparent)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(item.title, fontWeight = FontWeight.SemiBold)
        Text(item.detail, style = monoStyle(), color = colors.muted)
        item.reason?.let { Text("Reason: $it", style = smallStyle(), color = colors.muted) }

        val outcome = item.outcome
        if (outcome == null) {
            item.alwaysOptions.forEach { option ->
                Text(
                    "${option.label}: ${option.scope}",
                    style = smallStyle(),
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                DefaultButton(onClick = { actions.answer(item, true, null) }) { Text("Allow") }
                item.alwaysOptions.forEach { option ->
                    OutlinedButton(onClick = { actions.answer(item, true, option) }) { Text(option.label) }
                }
                OutlinedButton(onClick = { actions.answer(item, false, null) }) { Text("Deny") }
                if (item.reviewable) {
                    Box(Modifier.align(Alignment.CenterVertically).padding(start = 4.dp)) {
                        Link("Review changes", onClick = { actions.review(item) })
                    }
                }
            }
        } else {
            Text(outcome, style = smallStyle(), color = colors.muted)
        }
    }
}

/** A proposed plan: a preview, where the file is, and the decision buttons. The full plan opens in an editor tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(item: PlanItem, actions: ChatActions) {
    val colors = chatColors()
    val pending = item.outcome == null
    val shape = RoundedCornerShape(12.dp)
    val outline = remember(item.planText) { PlanOutline.of(item.planText) }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(200))
            .clip(shape)
            .border(1.dp, if (pending) colors.brand.copy(alpha = 0.8f) else colors.border, shape)
            .background(colors.subtleFill)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DocumentGlyph(colors.brand)
            Text("Plan", style = smallStyle(), color = colors.muted)
            Spacer(Modifier.weight(1f))
            Link("Open plan", onClick = { actions.openPlan(item) })
        }
        // The expanded plan starts with its own heading.
        if (!item.expanded) {
            Text(item.title, style = readingStyle().copy(fontSize = readingStyle().fontSize * 1.1f), fontWeight = FontWeight.SemiBold)
        }

        // A short summary, or the whole plan when expanded; the editor tab has it too.
        if (item.expanded) {
            RichMarkdown(
                item.planText,
                onUrlClick = actions::openLink,
                modifier = Modifier.fillMaxWidth(),
                onCopy = actions::copyText,
            )
        } else if (outline.summary.isNotEmpty()) {
            Text(
                outline.summary,
                style = readingStyle(),
                color = colors.text.copy(alpha = 0.85f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!item.expanded && outline.sections.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                outline.sections.forEach { section ->
                    val chipShape = RoundedCornerShape(6.dp)
                    Text(
                        section,
                        style = smallStyle(),
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(chipShape).background(colors.userBubble).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        Link(if (item.expanded) "Show less" else "Show full plan", onClick = { item.expanded = !item.expanded })

        (item.openedPath ?: item.planFilePath)?.let { path ->
            Text(
                path.replace(System.getProperty("user.home"), "~"),
                style = smallStyle().merge(monoStyle()).copy(fontSize = smallStyle().fontSize),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val outcome = item.outcome
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            outcome?.let { Text(it, style = smallStyle(), color = colors.muted) }
            // Like Cursor, the latest plan can be built until it is, also after Keep planning.
            if (item.buildable) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DefaultButton(onClick = { actions.buildPlan(item, autoAcceptEdits = true) }) { Text("Build") }
                    OutlinedButton(onClick = { actions.buildPlan(item, autoAcceptEdits = false) }) { Text("Build, ask before edits") }
                    if (outcome == null) OutlinedButton(onClick = { actions.revisePlan(item) }) { Text("Keep planning") }
                }
            }
        }
    }
}

/** The files a turn changed, with what's left to review in each; the review tab has the hunks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangesCard(item: ChangesItem, actions: ChatActions) {
    val colors = chatColors()
    val shape = RoundedCornerShape(10.dp)
    val pending = item.review.pending.filter { it.path in item.paths }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(200))
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .background(colors.subtleFill)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DocumentGlyph(colors.muted)
            Text("${filesLabel(item.paths.size).replaceFirstChar { it.uppercase() }} changed", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (pending.isNotEmpty()) Link("Review", onClick = actions::openReview)
        }
        if (pending.isEmpty()) {
            Text("All changes reviewed", style = smallStyle(), color = colors.muted)
            return@Column
        }
        pending.forEach { file ->
            Row(
                Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable { actions.openReview() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.name, style = monoStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (file.created) Text("new", style = smallStyle(), color = colors.success)
                }
                DiffStat(file.added, file.removed)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            DefaultButton(onClick = { actions.acceptChanges(pending) }) { Text("Accept all") }
            OutlinedButton(onClick = { actions.rejectChanges(pending) }) { Text("Reject all") }
        }
    }
}

/** Claude Code isn't signed in: signs it in here, as the CLI's own /login can't run in a chat. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginCard(item: LoginItem, actions: ChatActions) {
    val colors = chatColors()
    val shape = RoundedCornerShape(10.dp)
    val done = item.status == LoginStatus.Done
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(200))
            .clip(shape)
            .border(1.dp, if (done) colors.border else colors.brand.copy(alpha = 0.8f), shape)
            .background(if (done) Color.Transparent else colors.brand.copy(alpha = 0.06f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sign in to Claude", fontWeight = FontWeight.SemiBold)
        when (item.status) {
            LoginStatus.Ready, LoginStatus.Failed -> {
                Text(
                    "Claude Code isn't signed in on this computer." +
                        if (item.retry != null) " Sign in and your message is sent again." else "",
                    style = readingStyle(),
                    color = colors.muted,
                )
                item.detail?.takeIf { item.status == LoginStatus.Failed }?.let { Text(it, style = smallStyle(), color = colors.error) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DefaultButton(onClick = { actions.signIn(item, console = false) }) { Text("Sign in with Claude") }
                    OutlinedButton(onClick = { actions.signIn(item, console = true) }) { Text("Use an Anthropic Console account") }
                }
            }
            LoginStatus.Waiting -> {
                ThinkingRow("Finish signing in in your browser…")
                item.url?.let { url -> Link("Open the sign-in page", onClick = { actions.openLink(url) }) }
                LoginCodeField(onSend = { actions.sendLoginCode(item, it) })
            }
            LoginStatus.Done -> Text("✓ ${item.detail.orEmpty()}", style = smallStyle(), color = colors.muted)
        }
    }
}

/** For when the browser shows a code rather than finishing by itself. */
@Composable
private fun LoginCodeField(onSend: (String) -> Unit) {
    val colors = chatColors()
    val code = rememberTextFieldState()
    val send = {
        onSend(code.text.toString())
        code.clearText()
    }
    Text("If the page shows a code, paste it here:", style = smallStyle(), color = colors.muted)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            state = code,
            placeholder = { Text("Code") },
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.Enter).also { if (it) send() }
            },
        )
        OutlinedButton(onClick = send, enabled = code.text.isNotBlank()) { Text("Continue") }
    }
}

@Composable
private fun Notice(item: NoticeItem, repeats: Int = 1) {
    val colors = chatColors()
    if (item.isError) {
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.error.copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer(Modifier.weight(1f)) { Text(item.text, color = colors.error) }
            if (repeats > 1) RepeatBadge(repeats, "The same error $repeats times in a row.")
        }
    } else {
        Text(item.text, style = smallStyle(), color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** A line across the chat where the CLI compacted it, with the context before and after; explained on hover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactedDivider(item: CompactedItem) {
    val colors = chatColors()
    Tooltip({
        Text(
            "Claude now works from a summary of the conversation above, which stays here for you to read." +
                if (item.automatic) " Claude Code compacts on its own when a chat gets long; when is set in Settings." else "",
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
            Text(compactedLabel(item), style = smallStyle(), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
        }
    }
}

/** "Context compacted automatically · 412k → 38k tokens". */
internal fun compactedLabel(item: CompactedItem): String {
    val how = if (item.automatic) "Context compacted automatically" else "Context compacted"
    val sizes = when {
        item.after != null -> "${compactCount(item.before)} → ${compactCount(item.after)} tokens"
        item.before > 0 -> "was ${compactCount(item.before)} tokens"
        else -> null
    }
    return listOfNotNull(how, sizes).joinToString(" · ")
}

/** "3×" on the last of a run of the same failure, explained on hover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepeatBadge(repeats: Int, explanation: String) {
    val colors = chatColors()
    Tooltip({ Text(explanation) }) {
        Text(
            "$repeats×",
            style = smallStyle(),
            color = colors.error,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.error.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun TurnMeta(item: TurnMetaItem) {
    Text(
        item.text,
        style = smallStyle(),
        color = chatColors().muted.copy(alpha = 0.8f),
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Three softly pulsing dots and what Claude is doing right now. */
@Composable
fun ThinkingRow(status: String, modifier: Modifier = Modifier) {
    val colors = chatColors()
    val transition = rememberInfiniteTransition(label = "thinking")
    val phase by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(width = 26.dp, height = 8.dp)) {
            val radius = size.height / 2.4f
            repeat(3) { dot ->
                // Each dot brightens as the phase passes it.
                val distance = ((phase - dot + 3f) % 3f).let { minOf(it, 3f - it) }
                val alpha = 1f - (distance / 1.5f).coerceIn(0f, 1f) * 0.75f
                drawCircle(colors.brand.copy(alpha = alpha), radius, Offset(radius + dot * (size.width - 2 * radius) / 2f, size.height / 2))
            }
        }
        Text(status, style = smallStyle(), color = colors.muted, modifier = Modifier.alpha(0.9f))
    }
}
