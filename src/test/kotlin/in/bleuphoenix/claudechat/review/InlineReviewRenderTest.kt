package `in`.bleuphoenix.claudechat.review

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/** Paints an editor with pending changes to build/renders/inline-review.png, for checking the in-editor blocks. */
class InlineReviewRenderTest : BasePlatformTestCase() {

    fun `test render inline review`() {
        val dir = Files.createTempDirectory("claude-inline").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val file = File(dir, "Main.kt").apply { writeText(AFTER) }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, virtualFile), false) as EditorEx
        val reviews = ReviewService.getInstance(project)
        reviews.recordEdit(file.path, BEFORE)
        val deadline = System.currentTimeMillis() + 10_000
        while (editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size < 3) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }

        // Headless editors aren't laid out in a window; size the text area and paint it directly.
        val component = editor.contentComponent
        component.setSize(900, component.preferredSize.height.coerceAtLeast(200))
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        component.paint(g)
        g.dispose()
        File("build/renders").mkdirs()
        ImageIO.write(image, "png", File("build/renders/inline-review.png"))

        reviews.acceptFiles(reviews.state.files.toList())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        FileEditorManager.getInstance(project).closeFile(virtualFile)
        dir.deleteRecursively()
    }

    private companion object {
        val BEFORE = """
            |fun main(args: Array<String>) {
            |    println("Hello, world!")
            |    val verbose = false
            |    if (verbose) println("done")
            |}
            |
            |fun usage() = "usage: app"
            |""".trimMargin()
        val AFTER = """
            |fun main(args: Array<String>) {
            |    val name = args.getOrNull(1) ?: "world"
            |    println("Hello, ${'$'}name!")
            |    val verbose = false
            |    if (verbose) println("done")
            |}
            |""".trimMargin()
    }
}
