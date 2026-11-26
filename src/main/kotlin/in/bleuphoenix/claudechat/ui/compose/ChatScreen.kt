package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import `in`.bleuphoenix.claudechat.history.SessionInfo
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.bleuphoenix.claudechat.ui.ChatItem
import `in`.bleuphoenix.claudechat.ui.CompactedItem
import `in`.bleuphoenix.claudechat.ui.ChatState
import `in`.bleuphoenix.claudechat.ui.DebugMode
import `in`.bleuphoenix.claudechat.ui.ToolItem
import `in`.bleuphoenix.claudechat.ui.TurnMetaItem
import `in`.bleuphoenix.claudechat.ui.RepeatedFailures
import `in`.bleuphoenix.claudechat.ui.UserItem
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/** The whole chat: conversation (or a welcome screen) above the composer. Files and images can be dropped anywhere on it. */
@Composable
fun ChatScreen(state: ChatState, actions: ChatActions, modifier: Modifier = Modifier) {
    val colors = chatColors()
    Box(modifier.fillMaxSize().background(colors.background).dropAttachments(state, actions)) {
        Column(Modifier.fillMaxSize()) {
            val history = state.history
            Crossfade(targetState = history, label = "history", modifier = Modifier.weight(1f).fillMaxWidth()) { shown ->
                if (shown != null) {
                    HistoryView(shown, actions)
                } else {
                    Crossfade(targetState = state.items.isEmpty(), label = "empty") { empty ->
                        if (empty) EmptyState(state, actions) else Conversation(state, actions)
                    }
                }
            }
            if (history == null) Composer(state, actions)
        }
        if (state.dropping) DropOverlay()
    }
}

/**
 * Messages in a bottom-anchored lazy list (reverseLayout), so streaming text grows upward while the view stays
 * pinned to the newest content, and scrolling up to read isn't disturbed by new output.
 */
@Composable
private fun Conversation(state: ChatState, actions: ChatActions) {
    val colors = chatColors()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val items = state.items
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24 } }

    // Sending a message always brings the conversation back to the bottom.
    LaunchedEffect(items.size) {
        if (items.lastOrNull() is UserItem) listState.animateScrollToItem(0)
    }

    // Jump to an item picked elsewhere (a command in the activity strip). The list is reversed, with the busy row
    // first while Claude works.
    LaunchedEffect(state.scrollTo) {
        val key = state.scrollTo ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0) listState.animateScrollToItem((if (state.busy) 1 else 0) + items.size - 1 - index)
        state.scrollTo = null
    }

    Box(Modifier.fillMaxSize()) {
        VerticallyScrollableContainer(listState, Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.busy) {
                    item(key = "busy") {
                        ThinkingRow(state.status, Modifier.animateItem().padding(top = 10.dp))
                    }
                }
                // The same failure several times in a row shows once, with a count.
                val repeats = RepeatedFailures.counts(items)
                val shown = items.indices.filter { repeats[it] > 0 }
                val count = shown.size
                val lastUserKey = items.lastOrNull { it is UserItem }?.key
                items(count, key = { items[shown[count - 1 - it]].key }) { reversedIndex ->
                    val position = count - 1 - reversedIndex
                    val index = shown[position]
                    val item = items[index]
                    val above = shown.getOrNull(position - 1)?.let(items::get)
                    Box(Modifier.animateItem().padding(top = spacingAbove(above, item))) {
                        ChatItemView(
                            item, actions, busy = state.busy, lastUserMessage = item.key == lastUserKey, commands = state.commands,
                            repeats = repeats[index],
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !atBottom,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(colors.userBubble)
                    .border(1.dp, colors.border, CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { scope.launch { listState.animateScrollToItem(0) } },
                contentAlignment = Alignment.Center,
            ) {
                ArrowDownGlyph(colors.text)
            }
        }
    }
}

/** Tighter spacing inside a run of tool calls and before turn stats; more between messages. */
private fun spacingAbove(above: ChatItem?, item: ChatItem) = when {
    above == null -> 0.dp
    above is ToolItem && item is ToolItem -> 5.dp
    item is TurnMetaItem -> 4.dp
    item is UserItem || item is CompactedItem || above is CompactedItem -> 22.dp
    else -> 12.dp
}

private val SUGGESTIONS = listOf(
    "Summarize this project",
    "Explain what the current branch changes",
    "Review my uncommitted changes",
    "Find TODOs worth fixing",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(state: ChatState, actions: ChatActions) {
    val colors = chatColors()
    val recent = state.recent
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(colors.brand, 44.dp)
        Spacer(Modifier.height(14.dp))
        Text("How can I help?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.mode.id == "ask") {
                "Claude reads and searches this project to answer your questions. It doesn't edit files or run commands."
            } else if (state.mode.id == DebugMode.ID) {
                "Describe the bug. Claude adds logging to test its ideas, you reproduce the bug, and it fixes the cause the logs show."
            } else {
                "Claude can read files, run commands and make edits in this project. ${state.mode.description}".trim()
            },
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(18.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            SUGGESTIONS.forEach { prompt ->
                val shape = RoundedCornerShape(16.dp)
                Text(
                    prompt,
                    style = smallStyle(),
                    modifier = Modifier
                        .clip(shape)
                        .border(1.dp, colors.border, shape)
                        .background(colors.subtleFill)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { actions.suggest(prompt) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            RecentChats(recent.take(RECENT_SHOWN), actions)
        }
        if (state.instructionsNote.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(state.instructionsNote, style = smallStyle(), color = colors.muted, textAlign = TextAlign.Center)
        }
        if (state.shortcutTips.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                state.shortcutTips,
                style = smallStyle(),
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
    }
}

/** A few past chats to pick up where you left off; "View all" opens the history. */
@Composable
private fun RecentChats(recent: List<SessionInfo>, actions: ChatActions) {
    val colors = chatColors()
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent chats", style = smallStyle(), color = colors.muted)
            Spacer(Modifier.weight(1f))
            Text(
                "View all",
                style = smallStyle(),
                color = colors.accent,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.openHistory() },
            )
        }
        recent.forEach { session ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { actions.openSession(session) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(relativeTime(session.updated), style = smallStyle(), color = colors.muted)
            }
        }
    }
}

private const val RECENT_SHOWN = 3
