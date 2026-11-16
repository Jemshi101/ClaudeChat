package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.history.SessionInfo
import `in`.bleuphoenix.claudechat.ui.HistoryState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Past chats for the project: search, open, rename, delete. */
@Composable
fun HistoryView(history: HistoryState, actions: ChatActions) {
    val colors = chatColors()
    val query = history.query.text.toString().trim()
    val sessions = history.sessions.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Chat history", fontWeight = FontWeight.SemiBold)
            Text("${history.sessions.size}", style = smallStyle(), color = colors.muted)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).pointerHoverIcon(PointerIcon.Hand).clickable { actions.closeHistory() },
                contentAlignment = Alignment.Center,
            ) { CrossGlyph(colors.muted, Modifier.size(10.dp)) }
        }
        SearchField(history, searchFocus, onEscape = actions::closeHistory)
        if (sessions.isEmpty()) {
            Text(
                if (history.sessions.isEmpty()) "No past chats in this project yet." else "No chats match \"$query\".",
                color = colors.muted,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        val listState = rememberLazyListState()
        VerticallyScrollableContainer(listState, Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, bottom = 12.dp)) {
                items(sessions, key = { it.id }) { session -> SessionRow(session, history, actions) }
            }
        }
    }
}

@Composable
private fun SearchField(history: HistoryState, focus: FocusRequester, onEscape: () -> Unit) {
    val colors = chatColors()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .background(colors.subtleFill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (history.query.text.isEmpty()) Text("Search chats", color = colors.muted)
        BasicTextField(
            state = history.query,
            textStyle = JewelTheme.defaultTextStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onEscape()
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun SessionRow(session: SessionInfo, history: HistoryState, actions: ChatActions) {
    val colors = chatColors()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val renaming = history.renaming == session.id
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .background(if (hovered && !renaming) colors.userBubble else Color.Transparent)
            .then(if (renaming) Modifier else Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.openSession(session) })
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (renaming) {
            RenameField(session, history, actions, Modifier.weight(1f))
            return@Row
        }
        Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (hovered) {
            Pill("Rename", primary = false) { history.renaming = session.id }
            Pill("Delete", primary = false) { actions.deleteSession(session) }
        } else {
            Text(relativeTime(session.updated), style = smallStyle(), color = colors.muted)
        }
    }
}

@Composable
private fun RenameField(session: SessionInfo, history: HistoryState, actions: ChatActions, modifier: Modifier) {
    val colors = chatColors()
    val state = rememberTextFieldState(session.title)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        state = state,
        textStyle = JewelTheme.defaultTextStyle.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = modifier
            .clip(shape)
            .border(1.dp, colors.accent, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter -> {
                        val title = state.text.toString().trim()
                        if (title.isNotEmpty() && title != session.title) actions.renameSession(session, title)
                        history.renaming = null
                        true
                    }
                    Key.Escape -> {
                        history.renaming = null
                        true
                    }
                    else -> false
                }
            },
    )
}

/** "just now", "5m ago", "3h ago", "Yesterday", a weekday within the week, then a date. */
fun relativeTime(epochMs: Long, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val then = Instant.ofEpochMilli(epochMs)
    val minutes = ChronoUnit.MINUTES.between(then, now)
    val days = ChronoUnit.DAYS.between(then.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        days == 0L -> "${minutes / 60}h ago"
        days == 1L -> "Yesterday"
        days < 7 -> DateTimeFormatter.ofPattern("EEEE").format(then.atZone(zone))
        then.atZone(zone).year == now.atZone(zone).year -> DateTimeFormatter.ofPattern("MMM d").format(then.atZone(zone))
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy").format(then.atZone(zone))
    }
}
