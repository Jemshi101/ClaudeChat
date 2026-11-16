package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.util.SystemInfo
import `in`.bleuphoenix.claudechat.plan.PlanService
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * A plan as a readable document, with Build actions on top while Claude waits for a decision. [onEdit] applies a
 * change to the plan's markdown (ticking or adding to-dos).
 */
@Composable
fun PlanDocumentView(
    markdown: String,
    pending: PlanService.PendingPlan?,
    onUrlClick: (String) -> Unit,
    onEdit: (((String) -> String) -> Unit)? = null,
) {
    val colors = chatColors()
    Column(Modifier.fillMaxSize().background(colors.background)) {
        AnimatedVisibility(pending != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            if (pending != null) PlanActionBar(pending)
        }
        // Named arguments pick the ScrollState overload, which scrolls its content; a positional ScrollState picks
        // the ScrollableState one, which only draws a scrollbar for content that scrolls itself (like a LazyColumn).
        VerticallyScrollableContainer(modifier = Modifier.weight(1f).fillMaxWidth(), scrollState = rememberScrollState()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val clipboard = LocalClipboardManager.current
                RichMarkdown(
                    markdown,
                    onUrlClick = onUrlClick,
                    modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                    onEditTasks = onEdit,
                )
            }
        }
    }
}

/**
 * What this is on the left, the choices on the right with Build, the main one, last and showing its shortcut. A
 * narrow tab puts the choices on their own line under the title rather than squeezing it.
 */
@Composable
private fun PlanActionBar(pending: PlanService.PendingPlan) {
    val colors = chatColors()
    Column(Modifier.fillMaxWidth().background(colors.subtleFill)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (maxWidth >= 640.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanTitle(Modifier.weight(1f))
                    PlanButtons(pending)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanTitle(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        PlanButtons(pending)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun PlanTitle(modifier: Modifier) {
    val colors = chatColors()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DocumentGlyph(colors.brand)
        Text("Plan ready", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        Text("Edit below, then build", style = smallStyle(), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlanButtons(pending: PlanService.PendingPlan) {
    val colors = chatColors()
    pending.revise?.let { revise -> OutlinedButton(onClick = revise) { Text("Keep planning", maxLines = 1) } }
    OutlinedButton(onClick = { pending.build(false) }) { Text("Build, ask before edits", maxLines = 1) }
    DefaultButton(onClick = { pending.build(true) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Build", maxLines = 1)
            Text(BUILD_SHORTCUT, style = smallStyle(), color = colors.onBrand.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

/** Builds the plan from its tab, as Cursor's Build Plan (Cmd/Ctrl+Enter in its plan editor). */
val BUILD_SHORTCUT: String = if (SystemInfo.isMac) "⌘⏎" else "Ctrl+Enter"
