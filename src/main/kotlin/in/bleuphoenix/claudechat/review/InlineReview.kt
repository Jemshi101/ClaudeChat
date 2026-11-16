package `in`.bleuphoenix.claudechat.review

import com.intellij.codeInsight.hint.HintUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.HintHint
import com.intellij.ui.LightweightHint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Timer

/**
 * Shows pending changes inside the editor, like Cursor: added lines tinted green, removed lines as a red block where
 * they were, and Reject / Accept buttons under each change. Rebuilt whenever a file's hunks change.
 */
class InlineReview(private val project: Project, private val actions: ReviewActions) : Disposable {

    private class Decorations(val highlighters: List<RangeHighlighter>, val inlays: MutableList<Inlay<*>>)

    private val decorations = HashMap<String, Decorations>()
    private val reviews = HashMap<String, Pair<FileReview, Document>>()
    private val scheduled = HashSet<String>()

    init {
        val factory = EditorFactory.getInstance()
        // Editors opened later get the same blocks.
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project != null && editor.project != project) return
                reviews.values.firstOrNull { it.second == editor.document }?.let { (file, _) -> schedule(file.path) }
            }
        }, this)
        // A button acts when pressed and released on it. Not on mouseClicked: AWT drops the click when the pointer moves
        // between press and release, as it often does a little on a trackpad.
        factory.eventMulticaster.addEditorMouseListener(object : EditorMouseListener {
            private var pressed: Pair<HunkBlock, HunkBlock.Button>? = null

            override fun mousePressed(event: EditorMouseEvent) {
                hover(null)
                pressed = buttonAt(event)?.let { it.first to it.second }
                // Keeps the caret and selection where they were.
                if (pressed != null) event.consume()
            }

            override fun mouseReleased(event: EditorMouseEvent) {
                val start = pressed ?: return
                pressed = null
                event.consume()
                val (block, button) = buttonAt(event)?.let { it.first to it.second }?.takeIf { it == start } ?: return
                if (button == HunkBlock.Button.Accept) actions.acceptHunk(block.file, block.hunk)
                else actions.rejectHunk(block.file, block.hunk)
            }

            override fun mouseClicked(event: EditorMouseEvent) {
                if (buttonAt(event) != null) event.consume()
            }

            override fun mouseExited(event: EditorMouseEvent) = hover(null)
        }, this)
        factory.eventMulticaster.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val editor = event.editor as? EditorEx ?: return
                val hit = buttonAt(event)
                editor.setCustomCursor(this@InlineReview, if (hit != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
                hover(hit?.let { Hover(editor, it.first, it.second, it.third) })
            }
        }, this)
    }

    /** The button the pointer rests on, for its shortcut hint. */
    private data class Hover(val editor: Editor, val block: HunkBlock, val button: HunkBlock.Button, val bounds: Rectangle)

    private var hovered: Hover? = null
    private var hint: LightweightHint? = null
    private val hintDelay = Timer(HINT_DELAY_MS) { hovered?.let(::showHint) }.apply { isRepeats = false }

    /** Starts (or, for null, ends) a hover: after a short rest on a button, a hint shows its keyboard shortcut. */
    private fun hover(next: Hover?) {
        if (next?.block === hovered?.block && next?.button == hovered?.button) return
        hovered = next
        hint?.hide()
        hint = null
        hintDelay.stop()
        if (next != null) hintDelay.restart()
    }

    private fun showHint(at: Hover) {
        val editor = at.editor
        // An editor not shown in a window (as in tests) has nowhere to put a hint.
        if (editor.isDisposed || !editor.contentComponent.isShowing) return
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText(at.button.actionId)
        val label = JBLabel(hintHtml(at.button, shortcut)).apply {
            background = HintUtil.getInformationColor()
            isOpaque = true
            border = JBUI.Borders.empty(4, 8)
        }
        val content = editor.contentComponent
        val below = Point(at.bounds.x, at.bounds.y + at.bounds.height + JBUI.scale(4))
        hint = LightweightHint(label).also { it.show(content, below.x, below.y, content, HintHint(content, below)) }
    }

    /**
     * The Reject or Accept button under the mouse. Looked up among this review's blocks by position, as the event's own
     * inlay isn't always set for a point in a block.
     */
    private fun buttonAt(event: EditorMouseEvent): Triple<HunkBlock, HunkBlock.Button, Rectangle>? {
        val x = event.mouseEvent.x
        val y = event.mouseEvent.y
        for (decoration in decorations.values) {
            for (inlay in decoration.inlays) {
                if (!inlay.isValid || inlay.editor != event.editor) continue
                val block = inlay.renderer as? HunkBlock ?: continue
                block.buttonAt(inlay, x, y)?.let { (button, bounds) -> return Triple(block, button, bounds) }
            }
        }
        return null
    }

    /** Redraws [file]'s changes in [document]'s editors, soon: hunks change on every keystroke while the user types. */
    fun update(file: FileReview, document: Document) {
        reviews[file.path] = file to document
        schedule(file.path)
    }

    fun clear(path: String) {
        reviews.remove(path)
        decorations.remove(path)?.let(::remove)
    }

    private fun schedule(path: String) {
        if (!scheduled.add(path)) return
        ApplicationManager.getApplication().invokeLater({
            scheduled.remove(path)
            reviews[path]?.let { (file, document) -> redraw(file, document) }
        }, ModalityState.any(), project.disposed)
    }

    private fun redraw(file: FileReview, document: Document) {
        decorations.remove(file.path)?.let(::remove)
        // Hunks describe the document as of the last update; skip a stale set rather than draw it in the wrong place.
        if (file.current != document.text) return
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val highlighters = file.hunks.filter { it.added.isNotEmpty() }.mapNotNull { hunk ->
            val start = lineStart(document, hunk.currentStart) ?: return@mapNotNull null
            val end = lineStart(document, hunk.currentStart + hunk.added.size - 1) ?: return@mapNotNull null
            markup.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, ADDED_ATTRIBUTES, HighlighterTargetArea.LINES_IN_RANGE)
        }
        val inlays = mutableListOf<Inlay<*>>()
        EditorFactory.getInstance().getEditors(document, project).forEach { editor ->
            file.hunks.forEach { hunk -> inlays += blocksFor(editor, document, file, hunk) }
        }
        decorations[file.path] = Decorations(highlighters, inlays)
    }

    /** Removed lines above the change, and the buttons below it (or both together for a pure deletion). */
    private fun blocksFor(editor: Editor, document: Document, file: FileReview, hunk: Hunk): List<Inlay<*>> {
        val inlays = editor.inlayModel
        val lineCount = document.lineCount
        if (hunk.added.isEmpty()) {
            val atEnd = hunk.currentStart >= lineCount
            val offset = if (atEnd) document.textLength else document.getLineStartOffset(hunk.currentStart)
            return listOfNotNull(inlays.addBlockElement(offset, atEnd, !atEnd, 0, HunkBlock(editor, file, hunk, hunk.removed, buttons = true)))
        }
        val first = hunk.currentStart.coerceAtMost(lineCount - 1)
        val last = (hunk.currentStart + hunk.added.size - 1).coerceAtMost(lineCount - 1)
        val removed = if (hunk.removed.isEmpty()) null else {
            inlays.addBlockElement(document.getLineStartOffset(first), false, true, 0, HunkBlock(editor, file, hunk, hunk.removed, buttons = false))
        }
        val buttons = inlays.addBlockElement(document.getLineEndOffset(last), true, false, 0, HunkBlock(editor, file, hunk, emptyList(), buttons = true))
        return listOfNotNull(removed, buttons)
    }

    private fun lineStart(document: Document, line: Int): Int? =
        if (line in 0 until document.lineCount) document.getLineStartOffset(line) else null

    private fun remove(decorations: Decorations) {
        decorations.highlighters.forEach { it.dispose() }
        decorations.inlays.forEach { if (it.isValid) it.dispose() }
    }

    override fun dispose() {
        hover(null)
        decorations.values.forEach(::remove)
        decorations.clear()
    }

    companion object {
        private const val HINT_DELAY_MS = 500

        /**
         * The hover hint for [button]: what it does and its [shortcut] (empty when none is set). The keys act on the
         * change at the caret, not the one under the pointer, so the hint says so.
         */
        internal fun hintHtml(button: HunkBlock.Button, shortcut: String): String {
            val action = StringUtil.escapeXmlEntities("${button.label} change")
            if (shortcut.isBlank()) return "<html>$action</html>"
            val keys = StringUtil.escapeXmlEntities(shortcut)
            return "<html>$action&nbsp;&nbsp;<b>$keys</b><br><small>Keys act on the change at the caret, then go to the next</small></html>"
        }

        private val ADDED = JBColor(Color(46, 160, 67, 40), Color(46, 160, 67, 46))
        private val REMOVED = JBColor(Color(218, 54, 51, 48), Color(248, 81, 73, 48))
        private val ACCEPT = JBColor(Color(31, 136, 61), Color(46, 160, 67))
        private val ADDED_ATTRIBUTES = TextAttributes().apply {
            backgroundColor = ADDED
            errorStripeColor = ACCEPT
        }
    }

    /**
     * A block between editor lines: the hunk's removed lines in the editor font on a red tint, then, if [buttons],
     * a row with Reject and Accept at the right edge of the visible area.
     */
    internal class HunkBlock(
        private val editor: Editor,
        val file: FileReview,
        val hunk: Hunk,
        private val lines: List<String>,
        private val buttons: Boolean,
    ) : EditorCustomElementRenderer {

        enum class Button(val label: String, val actionId: String) {
            Reject("Reject", "ClaudeChat.RejectChange"),
            Accept("Accept", "ClaudeChat.AcceptChange"),
        }

        /**
         * The button at ([x], [y]) in editor coordinates. Worked out from where the block is in the editor rather than
         * from the last paint: the IDE also paints editor content elsewhere (sticky lines, previews), at other places.
         */
        fun buttonAt(inlay: Inlay<*>, x: Int, y: Int): Pair<Button, Rectangle>? {
            if (!buttons) return null
            val bounds = inlay.bounds ?: return null
            return buttonLayout(bounds.y + lines.size * editor.lineHeight).entries.firstOrNull { it.value.contains(x, y) }?.toPair()
        }

        private fun buttonFont() = UIUtil.getLabelFont().deriveFont(editor.colorsScheme.editorFontSize2D * 0.85f)

        /** Each button's place for a button row starting at [top]: right-aligned in the visible area, Accept at the edge. */
        private fun buttonLayout(top: Int): Map<Button, Rectangle> {
            val metrics = editor.contentComponent.getFontMetrics(buttonFont())
            val padX = JBUI.scale(8)
            val gap = JBUI.scale(6)
            val y = top + JBUI.scale(3)
            val visible = visibleArea()
            var right = visible.x + visible.width - JBUI.scale(16)
            val layout = LinkedHashMap<Button, Rectangle>()
            Button.entries.reversed().forEach { button ->
                val width = metrics.stringWidth(button.label) + 2 * padX
                layout[button] = Rectangle(right - width, y, width, editor.lineHeight)
                right -= width + gap
            }
            return layout
        }

        private val buttonRowHeight get() = editor.lineHeight + JBUI.scale(6)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int = maxOf(1, visibleArea().width)

        /** The visible part of the editor; before the editor is laid out in a window, its whole text area. */
        private fun visibleArea(): Rectangle =
            editor.scrollingModel.visibleArea.takeIf { it.width > 0 } ?: Rectangle(0, 0, editor.contentComponent.width, 0)

        override fun calcHeightInPixels(inlay: Inlay<*>): Int =
            lines.size * editor.lineHeight + if (buttons) buttonRowHeight else 0

        override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
            // The block keeps the width it was created with, but the buttons sit at the edge of the view as it is now:
            // when the editor has grown, refit the block, or clicks on the buttons would land outside it.
            if (inlay.widthInPixels != calcWidthInPixels(inlay)) {
                ApplicationManager.getApplication().invokeLater({ if (inlay.isValid) inlay.update() }, ModalityState.any())
            }
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val x = targetRegion.x.toInt()
                var y = targetRegion.y.toInt()
                val lineHeight = editor.lineHeight
                if (lines.isNotEmpty()) {
                    val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                    g2.color = REMOVED
                    // Inlay widths are cached; reach the visible edge even if the editor has grown since.
                    val visible = visibleArea()
                    g2.fillRect(x, y, maxOf(targetRegion.width.toInt(), visible.x + visible.width - x), lines.size * lineHeight)
                    g2.font = font
                    g2.color = editor.colorsScheme.defaultForeground
                    val ascent = editor.ascent
                    lines.forEachIndexed { i, line ->
                        g2.drawString(line.replace("\t", " ".repeat(editor.settings.getTabSize(editor.project))), x, y + i * lineHeight + ascent)
                    }
                    y += lines.size * lineHeight
                }
                if (buttons) paintButtons(g2, y)
            } finally {
                g2.dispose()
            }
        }

        private fun paintButtons(g: Graphics2D, top: Int) {
            g.font = buttonFont()
            val metrics = g.fontMetrics
            val padX = JBUI.scale(8)
            val height = editor.lineHeight
            buttonLayout(top).forEach { (button, bounds) ->
                val shape = RoundRectangle2D.Float(bounds.x.toFloat(), bounds.y.toFloat(), bounds.width.toFloat(), bounds.height.toFloat(), 10f, 10f)
                if (button == Button.Accept) {
                    g.color = ACCEPT
                    g.fill(shape)
                    g.color = Color.WHITE
                } else {
                    g.color = editor.colorsScheme.defaultBackground
                    g.fill(shape)
                    g.color = JBColor.border()
                    g.draw(shape)
                    g.color = editor.colorsScheme.defaultForeground
                }
                g.drawString(button.label, bounds.x + padX, bounds.y + (height + metrics.ascent - metrics.descent) / 2)
            }
        }
    }
}
