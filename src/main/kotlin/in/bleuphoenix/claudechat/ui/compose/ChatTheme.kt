package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme

/** Colors derived from the current Jewel theme, so the chat follows the IDE's light or dark theme. */
@Immutable
class ChatColors(
    val background: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val subtleFill: Color,
    val userBubble: Color,
    val codeFill: Color,
    val accent: Color,
    val brand: Color,
    val onBrand: Color,
    val success: Color,
    val error: Color,
    /** Reading text in replies and plans: a shade softer than [text], so bold and headings stand out. */
    val prose: Color,
    /** Section headings: Claude's orange, tuned to read well on the background. */
    val heading: Color,
    /** Inline `code`: a calm sage green, apart from blue links and orange headings. */
    val code: Color,
)

/** Claude's brand orange, used sparingly: send button, logo, pending approvals. */
private val Brand = Color(0xFFD97757)

@Composable
fun chatColors(): ChatColors {
    val global = JewelTheme.globalColors
    val dark = JewelTheme.isDark
    val background = global.panelBackground
    val text = global.text.normal
    return ChatColors(
        background = background,
        text = text,
        muted = global.text.info,
        border = global.borders.normal,
        subtleFill = lerp(background, text, if (dark) 0.05f else 0.035f),
        userBubble = lerp(background, text, if (dark) 0.11f else 0.075f),
        codeFill = lerp(background, Color.Black, if (dark) 0.25f else 0.04f),
        accent = global.outlines.focused,
        brand = Brand,
        onBrand = Color.White,
        success = if (dark) Color(0xFF5FB865) else Color(0xFF208A3C),
        error = if (dark) Color(0xFFF75464) else Color(0xFFDB3B4B),
        prose = lerp(text, background, if (dark) 0.12f else 0.1f),
        heading = if (dark) Color(0xFFE8956F) else Color(0xFFB4532A),
        code = if (dark) Color(0xFF9DBFA6) else Color(0xFF3D6B4C),
    )
}

/** Each permission mode's color, on the mode chip and the message box's border, so the mode shows at a glance. */
@Composable
fun modeColor(id: String): Color {
    val dark = JewelTheme.isDark
    return when (id) {
        "manual" -> if (dark) Color(0xFF6E9EF2) else Color(0xFF2F64C8)
        "acceptEdits" -> if (dark) Color(0xFF4DB6AC) else Color(0xFF13827A)
        "plan" -> if (dark) Color(0xFFE2B04A) else Color(0xFF9C6A06)
        "ask" -> if (dark) Color(0xFF6CC27A) else Color(0xFF2E8540)
        // Cursor's red for Debug.
        "debug" -> if (dark) Color(0xFFE06C75) else Color(0xFFB8323F)
        "auto" -> if (dark) Color(0xFFB48CF2) else Color(0xFF7446C0)
        // A mode set outside the chat, e.g. bypassPermissions: a warning.
        else -> chatColors().error
    }
}

/** The chat's text size, shared by every open chat so a change in Settings shows at once. */
object ChatAppearance {
    val FONT_PERCENTS = listOf(90, 100, 110, 125, 150)

    var fontPercent by mutableIntStateOf(100)
}

/** Scales all chat text (messages, markdown, code, the message box) by [ChatAppearance.fontPercent]. */
@Composable
fun ProvideChatFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * ChatAppearance.fontPercent / 100f)
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

@Composable
fun monoStyle(): TextStyle = JewelTheme.editorTextStyle.copy(fontSize = JewelTheme.defaultTextStyle.fontSize * 0.92f)

/** Text meant to be read in full (messages, summaries): the default font at a comfortable line height. */
@Composable
fun readingStyle(): TextStyle = JewelTheme.defaultTextStyle.copy(lineHeight = BODY_LINE_HEIGHT, lineHeightStyle = CENTERED)

@Composable
fun smallStyle(): TextStyle = JewelTheme.defaultTextStyle.copy(fontSize = (JewelTheme.defaultTextStyle.fontSize.value - 1.5f).sp)
