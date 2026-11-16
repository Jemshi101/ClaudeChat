package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.review.FileReview
import `in`.bleuphoenix.claudechat.ui.BackgroundStatus
import `in`.bleuphoenix.claudechat.ui.BackgroundTask
import `in`.bleuphoenix.claudechat.ui.ChatState
import `in`.bleuphoenix.claudechat.ui.TaskEntry
import `in`.bleuphoenix.claudechat.ui.TaskStatus
import `in`.bleuphoenix.claudechat.ui.ToolItem
import `in`.bleuphoenix.claudechat.ui.ToolStatus
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import kotlinx.coroutines.delay

/**
 * What this chat has going on, above the message box, like Cursor's: background shell commands and agents, and the
 * files Claude changed that are still waiting for review. Collapsed to "› 1 running · 3 Files +42 −7" with Stop (while
 * Claude works) and Review; open, each running task with its time, activity, Output and Stop, then each file with its
 * counts and Reject / Accept, plus Reject all / Accept all. Hidden when there's nothing to show. The files are shared
 * with the Review tab.
 */
@Composable
fun ActivityStrip(state: ChatState, actions: ChatActions) {
    val files = state.changedFiles
    val background = state.background.toList()
    val tasks = state.tasks.toList()
    val commands = state.shellCommands
    if (files.isEmpty() && background.isEmpty() && tasks.isEmpty() && commands.isEmpty()) return
    val running = background.count { it.status == BackgroundStatus.Running }
    val tasksDone = tasks.count { it.status == TaskStatus.Completed }
    val currentTask = tasks.firstOrNull { it.status == TaskStatus.InProgress }
    val colors = chatColors()
    val shape = RoundedCornerShape(10.dp)
    val turn by animateFloatAsState(if (state.activityExpanded) 90f else 0f, tween(150))
    Column(
        Modifier
            .padding(bottom = 6.dp)
            .fillMaxWidth()
            .animateContentSize(tween(150))
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .background(colors.subtleFill),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { state.activityExpanded = !state.activityExpanded }
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChevronGlyph(colors.muted, Modifier.rotate(turn))
            val parts = mutableListOf<@Composable () -> Unit>()
            if (tasks.isNotEmpty()) parts += {
                Text("Tasks $tasksDone/${tasks.size}", style = smallStyle(), fontWeight = FontWeight.Medium)
            }
            if (running > 0) parts += {
                Spinner(colors.accent)
                Text("$running running", style = smallStyle(), fontWeight = FontWeight.Medium)
            } else if (background.isNotEmpty()) parts += {
                Text("${background.size} finished", style = smallStyle(), color = colors.muted)
            }
            if (files.isNotEmpty()) parts += {
                Text(if (files.size == 1) "1 File" else "${files.size} Files", style = smallStyle(), fontWeight = FontWeight.Medium)
                DiffStat(files.sumOf { it.added }, files.sumOf { it.removed })
            }
            if (parts.isEmpty()) parts += {
                Text(if (commands.size == 1) "1 command" else "${commands.size} commands", style = smallStyle(), color = colors.muted)
            }
            parts.forEachIndexed { i, part ->
                if (i > 0) Text("·", style = smallStyle(), color = colors.muted)
                part()
            }
            // Collapsed, the header still says what Claude is doing now.
            Text(
                currentTask?.let { it.activeForm ?: it.subject }?.takeIf { !state.activityExpanded }.orEmpty(),
                style = smallStyle(),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.busy) Pill("Stop", primary = false, onClick = actions::stop)
            if (files.isNotEmpty()) Pill("Review", primary = true, onClick = actions::openReview)
        }
        if (state.activityExpanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.6f)))
            VerticallyScrollableContainer(modifier = Modifier.heightIn(max = 260.dp), scrollState = rememberScrollState()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (tasks.isNotEmpty()) Section(state, "Tasks", "$tasksDone/${tasks.size}") {
                        tasks.forEach { task -> StripTaskRow(task) }
                    }
                    if (background.isNotEmpty()) Section(state, "Background", if (running > 0) "$running running" else "${background.size}") {
                        background.forEach { task -> BackgroundRow(task, actions) }
                    }
                    if (files.isNotEmpty()) Section(state, "Changed files", "${files.size}") {
                        files.forEach { file -> ChangedFileRow(file, actions) }
                    }
                    if (commands.isNotEmpty()) Section(state, "Commands", "${commands.size}") {
                        commands.forEach { command -> CommandRow(command, actions) }
                    }
                }
            }
            if (files.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.6f)))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Pill("Reject all", primary = false) { actions.rejectChanges(files) }
                    Pill("Accept all", primary = true) { actions.acceptChanges(files) }
                }
            }
        }
    }
}

/** One of Claude's tasks: an open circle, a spinner while in progress (with what it's doing), or a tick. */
@Composable
private fun StripTaskRow(task: TaskEntry) {
    val colors = chatColors()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when (task.status) {
                TaskStatus.Completed -> CheckGlyph(colors.success)
                TaskStatus.InProgress -> Spinner(colors.brand)
                TaskStatus.Pending -> Canvas(Modifier.size(10.dp)) {
                    drawCircle(colors.muted, radius = size.minDimension / 2.2f, style = Stroke(size.minDimension * 0.12f))
                }
            }
        }
        Text(
            if (task.status == TaskStatus.InProgress) task.activeForm ?: task.subject else task.subject,
            style = smallStyle(),
            color = if (task.status == TaskStatus.Completed) colors.muted else colors.text,
            textDecoration = if (task.status == TaskStatus.Completed) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A terminal command Claude ran: its status, the command, how long it took. Click to see its card in the chat. */
@Composable
private fun CommandRow(item: ToolItem, actions: ChatActions) {
    val colors = chatColors()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (item.status == ToolStatus.Running) {
        LaunchedEffect(item.key) {
            while (true) {
                delay(1000)
                now = System.currentTimeMillis()
            }
        }
    }
    val seconds = ((item.endedAt ?: now) - item.startedAt).coerceAtLeast(0) / 1000.0
    Row(
        Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { actions.showCommand(item) }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when (item.status) {
                ToolStatus.Running -> Spinner(colors.accent)
                ToolStatus.Succeeded -> CheckGlyph(colors.success)
                ToolStatus.Failed -> CrossGlyph(colors.error)
            }
        }
        Text(item.summary, style = monoStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            when {
                item.background -> "background"
                seconds >= 60 -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
                else -> "%.1fs".format(seconds)
            },
            style = smallStyle(),
            color = colors.muted,
        )
    }
}

/** A section of the open strip: a heading with a chevron and [count] that folds and unfolds its [rows]. */
@Composable
private fun Section(state: ChatState, title: String, count: String, rows: @Composable () -> Unit) {
    val colors = chatColors()
    val open = title !in state.collapsedSections
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(150))
    Row(
        Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { state.toggleSection(title) }
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChevronGlyph(colors.muted, Modifier.rotate(turn))
        Text(title, style = smallStyle(), color = colors.muted)
        Text(count, style = smallStyle(), color = colors.muted.copy(alpha = 0.7f))
    }
    if (open) rows()
}

/**
 * A background task: a spinner while it runs (then ✓, ✗ or ■), Shell or Agent, what it is, how long it has run, an
 * agent's current activity, and Output (a shell's log) and Stop.
 */
@Composable
private fun BackgroundRow(task: BackgroundTask, actions: ChatActions) {
    val colors = chatColors()
    // The elapsed time ticks while the task runs.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (task.status == BackgroundStatus.Running) {
        LaunchedEffect(task.id) {
            while (true) {
                delay(1000)
                now = System.currentTimeMillis()
            }
        }
    }
    val elapsed = ((task.endedAt ?: now) - task.startedAt).coerceAtLeast(0) / 1000
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when (task.status) {
                BackgroundStatus.Running -> Spinner(colors.accent)
                BackgroundStatus.Completed -> CheckGlyph(colors.success)
                BackgroundStatus.Failed -> CrossGlyph(colors.error)
                BackgroundStatus.Stopped -> StopGlyph(colors.muted)
            }
        }
        Text(if (task.isShell) "Shell" else "Agent", style = smallStyle(), color = colors.muted)
        Column(Modifier.weight(1f)) {
            Text(task.description.ifEmpty { task.id }, style = smallStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            task.activity?.takeIf { task.status == BackgroundStatus.Running }?.let {
                Text(it, style = smallStyle(), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(if (elapsed >= 60) "${elapsed / 60}m ${elapsed % 60}s" else "${elapsed}s", style = smallStyle(), color = colors.muted)
        if (task.outputFile != null) Pill("Output", primary = false) { actions.openBackgroundOutput(task) }
        if (task.status == BackgroundStatus.Running) Pill("Stop", primary = false) { actions.stopBackground(task) }
    }
}

/** A changed file: its name (opens it at the change), its folder, its counts and Reject / Accept for the whole file. */
@Composable
private fun ChangedFileRow(file: FileReview, actions: ChatActions) {
    val colors = chatColors()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { actions.openChangedFile(file) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(file.name, style = monoStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (file.created) Text("new", style = smallStyle(), color = colors.success)
            Text(
                file.path.substringBeforeLast('/').substringAfterLast('/').let { if (it.isEmpty()) "" else "$it/" },
                style = smallStyle(),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        DiffStat(file.added, file.removed)
        Pill("Reject", primary = false) { actions.rejectChanges(listOf(file)) }
        Pill("Accept", primary = true) { actions.acceptChanges(listOf(file)) }
    }
}
