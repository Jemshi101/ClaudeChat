package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.ui.MessageTokens

/** Commands and skills in Claude's orange, files in the link color, each on a faint tint of its color. */
fun tokenStyle(kind: MessageTokens.Kind, colors: ChatColors): SpanStyle = when (kind) {
    MessageTokens.Kind.Command -> SpanStyle(color = colors.brand, fontWeight = FontWeight.Medium, background = colors.brand.copy(alpha = 0.14f))
    MessageTokens.Kind.File -> SpanStyle(color = colors.accent, background = colors.accent.copy(alpha = 0.14f))
}

/** A sent message with its commands and file mentions colored as they were in the message box. */
fun highlighted(text: String, commands: List<SlashCommand>, colors: ChatColors): AnnotatedString = buildAnnotatedString {
    append(text)
    MessageTokens.find(text, commands).forEach { addStyle(tokenStyle(it.kind, colors), it.start, it.end) }
}

/** Colors commands and file mentions while they're typed; the text itself is unchanged. */
fun highlightTransformation(commands: List<SlashCommand>, colors: ChatColors) = OutputTransformation {
    MessageTokens.find(asCharSequence(), commands).forEach { addStyle(tokenStyle(it.kind, colors), it.start, it.end) }
}
