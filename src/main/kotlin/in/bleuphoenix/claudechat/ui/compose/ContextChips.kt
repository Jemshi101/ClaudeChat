package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ui.ActiveFile
import `in`.bleuphoenix.claudechat.ui.Attachment
import `in`.bleuphoenix.claudechat.ui.ChatState
import `in`.bleuphoenix.claudechat.ui.FileAttachment
import `in`.bleuphoenix.claudechat.ui.ImageAttachment
import `in`.bleuphoenix.claudechat.ui.MentionState
import `in`.bleuphoenix.claudechat.ui.TextAttachment
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** Chips for the next message's context: an `@` button, the active editor's file, then attached files and images. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextRow(state: ChatState, actions: ChatActions, onAddContext: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AddContextChip(onAddContext)
        state.activeFile?.let { ActiveFileChip(it, state.activeFileIncluded, actions) }
        state.attachments.forEach { attachment ->
            AttachmentChip(
                attachment,
                onRemove = { actions.removeAttachment(attachment) },
                onExpand = (attachment as? TextAttachment)?.let { text -> { actions.expandAttachment(text) } },
            )
        }
    }
}

@Composable
private fun AddContextChip(onClick: () -> Unit) {
    val colors = chatColors()
    ChipFrame(Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)) {
        Text("@", style = smallStyle(), color = colors.muted)
        Text("Add context", style = smallStyle(), color = colors.muted)
    }
}

/** The active file: sent as a pointer while included; clicking a left-out chip includes it again. */
@Composable
private fun ActiveFileChip(file: ActiveFile, included: Boolean, actions: ChatActions) {
    val colors = chatColors()
    val toggle = if (included) Modifier else Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.includeActiveFile(true) }
    ChipFrame(toggle.alpha(if (included) 1f else 0.55f), faded = !included) {
        DocumentGlyph(colors.muted, Modifier.size(12.dp))
        Text(file.name, style = smallStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        Text(if (included) "current file" else "add current file", style = smallStyle(), color = colors.muted)
        if (included) RemoveButton { actions.includeActiveFile(false) }
    }
}

/**
 * A chip for an attachment; [onRemove] adds a remove button (left out in sent messages), and [onExpand] puts a
 * collapsed text back into the message box on double-click.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentChip(attachment: Attachment, onRemove: (() -> Unit)? = null, onExpand: (() -> Unit)? = null) {
    val colors = chatColors()
    val expandable = if (onExpand == null) Modifier else {
        Modifier.pointerHoverIcon(PointerIcon.Hand).combinedClickable(onClick = {}, onDoubleClick = onExpand)
    }
    val chip = @Composable {
      ChipFrame(expandable) {
        when (attachment) {
            is ImageAttachment -> attachment.thumbnail?.let {
                Image(it, contentDescription = attachment.name, contentScale = ContentScale.Crop, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)))
            }
            is FileAttachment -> FileGlyph(attachment.isDirectory)
            is TextAttachment -> DocumentGlyph(colors.muted, Modifier.size(12.dp))
        }
        // Files in the same color as @mentions in the text.
        Text(
            attachment.label,
            style = smallStyle(),
            color = if (attachment is FileAttachment) colors.accent else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
        if (onRemove != null) RemoveButton(onRemove)
      }
    }
    if (onExpand == null) chip() else Tooltip(tooltip = { Text("Double-click to put the text back in the message") }) { chip() }
}

@Composable
private fun FileGlyph(isDirectory: Boolean) {
    val color = chatColors().accent
    if (isDirectory) FolderGlyph(color, Modifier.size(12.dp)) else DocumentGlyph(color, Modifier.size(12.dp))
}

@Composable
private fun ChipFrame(modifier: Modifier = Modifier, faded: Boolean = false, content: @Composable () -> Unit) {
    val colors = chatColors()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .clip(shape)
            .border(1.dp, if (faded) colors.border.copy(alpha = 0.6f) else colors.border, shape)
            .background(colors.background)
            .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    val colors = chatColors()
    Box(
        Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CrossGlyph(colors.muted, Modifier.size(8.dp))
    }
}

/** Files matching the `@` mention being typed; Up/Down choose, Enter or Tab picks, Escape closes. */
@Composable
fun MentionPopup(mention: MentionState, actions: ChatActions) {
    val colors = chatColors()
    val shape = RoundedCornerShape(10.dp)
    val listState = rememberLazyListState()
    LaunchedEffect(mention.selected) { listState.animateScrollToItem((mention.selected - 3).coerceAtLeast(0)) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .background(colors.background)
            .padding(vertical = 4.dp),
    ) {
        if (mention.results.isEmpty()) {
            Text(
                if (mention.query.isEmpty()) "Type to search files" else "No files match \"${mention.query}\"",
                style = smallStyle(),
                color = colors.muted,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 220.dp)) {
            itemsIndexed(mention.results, key = { _, match -> match.path }) { index, match ->
                val selected = index == mention.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { actions.pickMention(match) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileGlyph(match.isDirectory)
                    Text(if (match.isDirectory) "${match.name}/" else match.name, maxLines = 1)
                    Text(
                        match.directory,
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
