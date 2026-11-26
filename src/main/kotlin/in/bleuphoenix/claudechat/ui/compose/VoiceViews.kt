package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ui.ChatState
import `in`.bleuphoenix.claudechat.ui.VoicePhase
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** A microphone, drawn so it takes any color. */
@Composable
fun MicGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(14.dp)) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.11f
    // The capsule, filled.
    drawRoundRect(color, Offset(w * 0.34f, h * 0.06f), Size(w * 0.32f, h * 0.5f), CornerRadius(w * 0.16f))
    // The stand: an arc under it, a stem and a foot.
    drawArc(color, 0f, 180f, false, Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.5f), style = Stroke(stroke, cap = StrokeCap.Round))
    drawLine(color, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.88f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.34f, h * 0.9f), Offset(w * 0.66f, h * 0.9f), stroke, StrokeCap.Round)
}

/** The mic button beside Send: starts voice input, and while listening stops it (shown as a red stop). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MicButton(phase: VoicePhase, actions: ChatActions) {
    val colors = chatColors()
    val recording = phase == VoicePhase.Recording
    val tip = when (phase) {
        VoicePhase.Idle -> "Speak your message"
        VoicePhase.Recording -> "Stop and add what you said"
        VoicePhase.Transcribing -> "Turning your recording into text…"
    }
    Tooltip(tooltip = { Text(tip) }) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (recording) colors.error else Color.Transparent)
                .then(
                    if (phase == VoicePhase.Transcribing) Modifier
                    else Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.toggleVoice() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) MicGlyph(Color.White) else MicGlyph(if (phase == VoicePhase.Transcribing) colors.muted.copy(alpha = 0.5f) else colors.muted)
        }
    }
}

/** Above the message box while listening or transcribing, or with a voice message (how to set it up, an error). */
@Composable
fun VoiceBar(state: ChatState, actions: ChatActions) {
    val colors = chatColors()
    val message = state.voiceMessage
    if (state.voice == VoicePhase.Idle && message == null) return
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state.voice) {
            VoicePhase.Recording -> {
                val pulse by rememberInfiniteTransition(label = "listening")
                    .animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
                Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(colors.error))
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.voiceStartedAt) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(250)
                    }
                }
                val seconds = ((now - state.voiceStartedAt) / 1000).coerceAtLeast(0)
                Text(
                    "Listening… ${seconds / 60}:${"%02d".format(seconds % 60)}",
                    style = smallStyle(),
                    color = colors.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Link("Done", onClick = actions::toggleVoice)
                Link("Cancel", onClick = actions::cancelVoice)
            }
            VoicePhase.Transcribing -> {
                Text("Transcribing…", style = smallStyle(), color = colors.muted, maxLines = 1, modifier = Modifier.weight(1f))
            }
            VoicePhase.Idle -> {
                Text(
                    message.orEmpty(),
                    style = smallStyle(),
                    color = colors.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Link("Settings", onClick = actions::openVoiceSettings)
                Text(
                    "✕",
                    color = colors.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.voiceMessage = null }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
