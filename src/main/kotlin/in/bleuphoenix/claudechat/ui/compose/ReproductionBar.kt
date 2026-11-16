package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ui.DebugMode
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Debug mode's bar above the message box, as in Cursor: the steps Claude asked the user to follow, then Proceed once
 * the bug has been reproduced (Enter in an empty box does the same), or Mark as fixed.
 */
@Composable
fun ReproductionBar(steps: String, actions: ChatActions) {
    val colors = chatColors()
    val accent = modeColor(DebugMode.ID)
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
            .background(colors.subtleFill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text("Reproduction Steps", fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            RichMarkdown(steps, onUrlClick = actions::openLink, modifier = Modifier.fillMaxWidth(), onCopy = actions::copyText)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reproduce the bug, then Proceed (Enter).", style = smallStyle(), color = colors.muted, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = actions::markFixed) { Text("Mark as fixed") }
            DefaultButton(onClick = actions::proceedDebugging) { Text("Proceed") }
        }
    }
}
