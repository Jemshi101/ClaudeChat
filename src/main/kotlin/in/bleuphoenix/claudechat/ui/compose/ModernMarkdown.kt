package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownBlockRenderer
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownProcessor
import org.jetbrains.jewel.markdown.extensions.LocalMarkdownStyling
import org.jetbrains.jewel.markdown.extensions.autolink.AutolinkProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughRendererExtension
import org.jetbrains.jewel.markdown.extensions.markdownBlockRenderer
import org.jetbrains.jewel.markdown.extensions.markdownMode
import org.jetbrains.jewel.markdown.extensions.markdownStyling
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling

/**
 * The chat's markdown look, on top of the styling the IDE (or a test theme) provides: strikethrough and bare links; headings without rules; code as quiet tinted spans and rounded blocks.
 */
@Composable
fun ProvideModernMarkdown(content: @Composable () -> Unit) {
    val colors = chatColors()
    val base = JewelTheme.markdownStyling
    val baseRenderer = JewelTheme.markdownBlockRenderer
    val mode = JewelTheme.markdownMode
    val styling = remember(base, colors) { modernStyling(base, colors) }
    val processor = remember(mode) {
        MarkdownProcessor(
            // Tables are drawn by TableView (see MarkdownSegment.Table), not the markdown renderer.
            extensions = listOf(GitHubStrikethroughProcessorExtension(), AutolinkProcessorExtension),
            markdownMode = mode,
        )
    }
    val renderer = remember(baseRenderer, styling, colors) {
        baseRenderer.createCopy(
            styling,
            listOf(GitHubStrikethroughRendererExtension),
        )
    }
    CompositionLocalProvider(
        LocalMarkdownStyling provides styling,
        LocalMarkdownProcessor provides processor,
        LocalMarkdownBlockRenderer provides renderer,
        content = content,
    )
}

private fun modernStyling(base: MarkdownStyling, colors: ChatColors): MarkdownStyling {
    val body = base.paragraph.inlinesStyling
    val size = body.textStyle.fontSize
    // Inline code: the editor font a touch smaller, in its own color on a faint tint, so it reads as part of the
    // sentence yet stands apart from links.
    val inlineCode = body.inlineCode.copy(
        fontSize = 0.92.em,
        color = colors.code,
        // Faint: at a reading line height the tint fills the whole line, so a strong one turns into blocks.
        background = colors.code.copy(alpha = 0.08f),
    )
    // Body text at a reading line height (the IDE's default, about 1.2, is tight for prose), a shade softer than bold
    // text so lead-ins like "**Conclusion.**" stand out.
    val strong = body.strongEmphasis.copy(color = colors.text)
    val paragraph = body.with(
        textStyle = body.textStyle.copy(lineHeight = BODY_LINE_HEIGHT, lineHeightStyle = CENTERED, color = colors.prose),
        inlineCode = inlineCode,
        strongEmphasis = strong,
    )
    fun heading(scale: Float, weight: FontWeight, top: Int, bottom: Int = 6, color: Color = colors.text) =
        paragraph.with(
            textStyle = body.textStyle.copy(
                fontSize = size * scale,
                fontWeight = weight,
                lineHeight = 1.3.em,
                lineHeightStyle = CENTERED,
                color = color,
            ),
            inlineCode = inlineCode,
        ) to PaddingValues(top = top.dp, bottom = bottom.dp)
    val (h1, h1Padding) = heading(1.6f, FontWeight.SemiBold, top = 4, bottom = 12)
    // Sections in Claude's orange: a plan's structure shows at a glance.
    val (h2, h2Padding) = heading(1.25f, FontWeight.SemiBold, top = 22, bottom = 8, color = colors.heading)
    val (h3, h3Padding) = heading(1.08f, FontWeight.SemiBold, top = 16, bottom = 6)
    val (h4, h4Padding) = heading(1f, FontWeight.SemiBold, top = 12, bottom = 4)
    val noRule = 0.dp
    val fenced = base.code.fenced
    val indented = base.code.indented
    val codeShape = RoundedCornerShape(8.dp)
    return MarkdownStyling(
        blockVerticalSpacing = 16.dp,
        paragraph = MarkdownStyling.Paragraph(paragraph),
        heading = MarkdownStyling.Heading(
            h1 = MarkdownStyling.Heading.H1(h1, noRule, Color.Transparent, noRule, h1Padding),
            h2 = MarkdownStyling.Heading.H2(h2, noRule, Color.Transparent, noRule, h2Padding),
            h3 = MarkdownStyling.Heading.H3(h3, noRule, Color.Transparent, noRule, h3Padding),
            h4 = MarkdownStyling.Heading.H4(h4, noRule, Color.Transparent, noRule, h4Padding),
            h5 = MarkdownStyling.Heading.H5(h4, noRule, Color.Transparent, noRule, h4Padding),
            h6 = MarkdownStyling.Heading.H6(h4, noRule, Color.Transparent, noRule, h4Padding),
        ),
        blockQuote = MarkdownStyling.BlockQuote(
            padding = PaddingValues(start = 14.dp),
            lineWidth = 3.dp,
            lineColor = colors.heading.copy(alpha = 0.55f),
            pathEffect = null,
            strokeCap = base.blockQuote.strokeCap,
            textColor = colors.muted,
        ),
        code = MarkdownStyling.Code(
            indented = MarkdownStyling.Code.Indented(
                editorTextStyle = indented.editorTextStyle,
                padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                shape = codeShape,
                background = colors.codeFill,
                borderWidth = 1.dp,
                borderColor = colors.border.copy(alpha = 0.6f),
                fillWidth = true,
                scrollsHorizontally = indented.scrollsHorizontally,
            ),
            // Fenced blocks sit inside CodeBlockView's card, which draws the frame and the header.
            fenced = MarkdownStyling.Code.Fenced(
                editorTextStyle = fenced.editorTextStyle,
                padding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
                shape = codeShape,
                background = Color.Transparent,
                borderWidth = 0.dp,
                borderColor = Color.Transparent,
                fillWidth = true,
                scrollsHorizontally = fenced.scrollsHorizontally,
                infoTextStyle = fenced.infoTextStyle,
                infoPadding = fenced.infoPadding,
                infoPosition = MarkdownStyling.Code.Fenced.InfoPosition.Hide,
            ),
        ),
        list = roomierLists(base.list, marker = colors.heading),
        image = base.image,
        thematicBreak = base.thematicBreak,
        htmlBlock = base.htmlBlock,
    )
}

/** Lists with room between items, and bullets and numbers in [marker]. */
private fun roomierLists(base: MarkdownStyling.List, marker: Color): MarkdownStyling.List {
    val ordered = base.ordered
    val unordered = base.unordered
    return MarkdownStyling.List(
        ordered = MarkdownStyling.List.Ordered(
            numberStyle = ordered.numberStyle.copy(lineHeight = BODY_LINE_HEIGHT, lineHeightStyle = CENTERED, color = marker),
            numberContentGap = ordered.numberContentGap,
            numberMinWidth = ordered.numberMinWidth,
            numberTextAlign = ordered.numberTextAlign,
            itemVerticalSpacing = 8.dp,
            itemVerticalSpacingTight = 5.dp,
            padding = ordered.padding,
            numberFormatStyles = ordered.numberFormatStyles,
        ),
        unordered = MarkdownStyling.List.Unordered(
            bullet = unordered.bullet,
            bulletStyle = unordered.bulletStyle.copy(lineHeight = BODY_LINE_HEIGHT, lineHeightStyle = CENTERED, color = marker),
            bulletContentGap = unordered.bulletContentGap,
            itemVerticalSpacing = 8.dp,
            itemVerticalSpacingTight = 5.dp,
            padding = unordered.padding,
            markerMinWidth = unordered.markerMinWidth,
            bulletCharStyles = unordered.bulletCharStyles,
        ),
    )
}

/** Line height for reading text, here and in chat bubbles and plan summaries. */
val BODY_LINE_HEIGHT = 1.6.em

/** Extra line height split above and below the text, so it stays centred in its line. */
val CENTERED = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun InlinesStyling.with(
    textStyle: TextStyle = this.textStyle,
    inlineCode: SpanStyle = this.inlineCode,
    strongEmphasis: SpanStyle = this.strongEmphasis,
) =
    InlinesStyling(
        textStyle = textStyle,
        inlineCode = inlineCode,
        link = link,
        linkDisabled = linkDisabled,
        linkFocused = linkFocused,
        linkHovered = linkHovered,
        linkPressed = linkPressed,
        linkVisited = linkVisited,
        emphasis = emphasis,
        strongEmphasis = strongEmphasis,
        inlineHtml = inlineHtml,
    )
