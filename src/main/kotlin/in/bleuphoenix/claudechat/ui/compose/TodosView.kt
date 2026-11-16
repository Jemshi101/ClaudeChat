package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ide.FileLink
import org.jetbrains.jewel.ui.component.Text

/**
 * A task list as a to-do card, like Cursor's: "4 To-dos", a circle per task, done ones ticked and struck through.
 * With [onEdit] (the plan tab) a click ticks a task in the file and "+ New" adds one; without it the card is read-only.
 */
@Composable
fun TodosView(todos: MarkdownSegment.Todos, onUrlClick: (String) -> Unit, onEdit: (((String) -> String) -> Unit)?) {
    val colors = chatColors()
    val shape = RoundedCornerShape(10.dp)
    var adding by remember { mutableStateOf(false) }
    val done = todos.items.count { it.done }
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border, shape).background(colors.subtleFill)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (done == 0) "${todos.items.size} To-dos" else "$done of ${todos.items.size} To-dos done",
                style = smallStyle(),
                color = colors.muted,
            )
            Spacer(Modifier.weight(1f))
            if (onEdit != null) ActionLink("+ New") { adding = true }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.6f)))
        Column(Modifier.padding(vertical = 6.dp)) {
            todos.items.forEachIndexed { i, task ->
                val index = todos.first + i
                TodoRow(task, onUrlClick, onToggle = onEdit?.let { edit -> { edit { TaskLists.toggle(it, index) } } })
            }
            if (adding && onEdit != null) {
                NewTodo(
                    onAdd = { text -> onEdit { TaskLists.add(it, text) } },
                    onDone = { adding = false },
                )
            }
        }
    }
}

@Composable
private fun TodoRow(task: TaskLists.Task, onUrlClick: (String) -> Unit, onToggle: (() -> Unit)?) {
    val colors = chatColors()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 4.dp)) { TodoCircle(task.done) }
        val text = remember(task.text) { FileLink.linkifyMarkdown(task.text) }
        Box(Modifier.weight(1f)) {
            if (task.done) {
                // Done tasks read as plain, struck-through text.
                Text(
                    task.text.replace("`", "").replace("**", ""),
                    style = readingStyle().copy(textDecoration = TextDecoration.LineThrough),
                    color = colors.muted,
                )
            } else {
                org.jetbrains.jewel.markdown.Markdown(text, selectable = onToggle == null, onUrlClick = onUrlClick)
            }
        }
    }
}

/** An empty circle, or a filled one with a tick. */
@Composable
private fun TodoCircle(done: Boolean) {
    val colors = chatColors()
    Canvas(Modifier.size(15.dp)) {
        val radius = size.minDimension / 2
        if (done) {
            drawCircle(colors.success, radius)
            val tick = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.67f)
                lineTo(size.width * 0.72f, size.height * 0.36f)
            }
            drawPath(tick, colors.onBrand, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        } else {
            drawCircle(colors.muted, radius - 0.75.dp.toPx(), style = Stroke(1.2.dp.toPx()), center = Offset(size.width / 2, size.height / 2))
        }
    }
}

/** A field for a new task: Enter adds it, Escape (or an empty Enter) closes the field. */
@Composable
private fun NewTodo(onAdd: (String) -> Unit, onDone: () -> Unit) {
    val colors = chatColors()
    val field = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TodoCircle(done = false)
        Box(Modifier.weight(1f)) {
            if (field.text.isEmpty()) Text("New to-do…", style = readingStyle(), color = colors.muted)
            BasicTextField(
                state = field,
                textStyle = readingStyle().copy(color = colors.text),
                cursorBrush = SolidColor(colors.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                val text = field.text.toString()
                                if (text.isNotBlank()) {
                                    onAdd(text)
                                    field.clearText()
                                } else {
                                    onDone()
                                }
                                true
                            }
                            Key.Escape -> {
                                onDone()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
    }
}
