package `in`.bleuphoenix.claudechat.review

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Cursor
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files

/** The Reject / Accept buttons under a change in the editor, used with the mouse: hover shows a hand, clicks apply. */
class InlineReviewClickTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private val reviews get() = ReviewService.getInstance(project)

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("claude-click").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
    }

    override fun tearDown() {
        try {
            reviews.state.files.toList().let { if (it.isNotEmpty()) reviews.acceptFiles(it) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            FileEditorManager.getInstance(project).let { manager -> manager.openFiles.forEach(manager::closeFile) }
            dir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test clicking Reject puts the old lines back and Accept keeps Claude's`() {
        val file = File(dir, "Main.kt").apply { writeText("a\nB\nc\nd\n") }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, virtualFile), false) as EditorEx
        reviews.recordEdit(file.path, "a\nb\nc\nd\ne\n")
        waitFor { reviews.state.files.singleOrNull()?.hunks?.size == 2 && blocks(editor).size == 3 }

        // Hunk 1 (b -> B) has its buttons in the block below line 2; hunk 2 (e deleted) in the block at the end.
        val (rejectAt, acceptAt) = buttons(editor, blocks(editor).maxBy { it.offset })
        click(editor, rejectAt)
        waitFor { file.readText() == "a\nB\nc\nd\ne\n" }
        assertEquals(1, reviews.state.files.single().hunks.size)

        waitFor { blocks(editor).size == 2 }
        val (_, accept) = buttons(editor, blocks(editor).maxBy { it.offset })
        click(editor, accept)
        waitFor { reviews.state.files.isEmpty() }
        assertEquals("a\nB\nc\nd\ne\n", file.readText())
        assertTrue(acceptAt.x > rejectAt.x)
    }

    fun `test the buttons still work after the block is painted somewhere else, as sticky lines do`() {
        val file = File(dir, "Sticky.kt").apply { writeText("a\nB\nc\n") }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, virtualFile), false) as EditorEx
        reviews.recordEdit(file.path, "a\nb\nc\n")
        waitFor { reviews.state.files.singleOrNull()?.hunks?.size == 1 && blocks(editor).size == 2 }

        val block = blocks(editor).maxBy { it.offset }
        val (_, accept) = buttons(editor, block)
        // Another surface paints the same block at the top of its own area.
        val image = BufferedImage(900, 200, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val region: java.awt.geom.Rectangle2D = java.awt.geom.Rectangle2D.Double(0.0, 0.0, 900.0, block.heightInPixels.toDouble())
        block.renderer.paint(block, g, region, TextAttributes())
        g.dispose()

        click(editor, accept)
        waitFor { reviews.state.files.isEmpty() }
        assertEquals("a\nB\nc\n", file.readText())
    }

    private fun blocks(editor: EditorEx): List<Inlay<*>> = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)

    /**
     * Finds the two buttons in [block] the way a user would: moving the mouse along it until the cursor turns into a
     * hand. Returns the middle of the left (Reject) and right (Accept) one.
     */
    private fun buttons(editor: EditorEx, block: Inlay<*>): Pair<Point, Point> {
        paintFitted(editor)
        val bounds = block.bounds!!
        val width = editor.contentComponent.width
        val hands = mutableListOf<Point>()
        for (y in bounds.y until bounds.y + bounds.height step 3) {
            for (x in width - 1 downTo width - 400 step 4) {
                mouse(editor, MouseEvent.MOUSE_MOVED, Point(x, y))
                if (editor.contentComponent.cursor.type == Cursor.HAND_CURSOR) hands += Point(x, y)
            }
        }
        assertTrue("no buttons found in the block at offset ${block.offset} ($bounds, editor width $width)", hands.isNotEmpty())
        mouse(editor, MouseEvent.MOUSE_MOVED, Point(bounds.x + 1, bounds.y + 1))
        assertFalse("the hand goes away off the buttons", editor.contentComponent.cursor.type == Cursor.HAND_CURSOR)
        // The middle of each button, so a pointer drifting a pixel stays on it.
        val split = (hands.minOf { it.x } + hands.maxOf { it.x }) / 2
        fun middle(points: List<Point>) = Point(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
        return middle(hands.filter { it.x < split }) to middle(hands.filter { it.x >= split })
    }

    /**
     * Presses and releases the button with the pointer drifting a pixel in between, as on a trackpad: AWT then sends no
     * MOUSE_CLICKED, and the button still has to act.
     */
    private fun click(editor: EditorEx, at: Point) {
        val drifted = Point(at.x + 1, at.y)
        mouse(editor, MouseEvent.MOUSE_PRESSED, at)
        mouse(editor, MouseEvent.MOUSE_DRAGGED, drifted)
        mouse(editor, MouseEvent.MOUSE_RELEASED, drifted)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun mouse(editor: EditorEx, id: Int, at: Point) {
        val component = editor.contentComponent
        val modifiers = if (id == MouseEvent.MOUSE_MOVED) 0 else InputEvent.BUTTON1_DOWN_MASK
        component.dispatchEvent(MouseEvent(component, id, System.currentTimeMillis(), modifiers, at.x, at.y, 1, false, MouseEvent.BUTTON1))
    }

    /** Headless editors aren't laid out in a window; size the text area and paint it, which places the buttons. */
    private fun paint(editor: EditorEx) {
        val component = editor.contentComponent
        component.setSize(900, component.preferredSize.height.coerceAtLeast(200))
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        component.paint(g)
        g.dispose()
    }

    /** Paints, lets blocks refit to the width, and paints again. */
    private fun paintFitted(editor: EditorEx) {
        paint(editor)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        paint(editor)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }
}
