package `in`.bleuphoenix.claudechat.review

import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/** Real files on disk, as the CLI writes them; the service follows them through the IDE's documents. */
class ReviewServiceTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private val reviews get() = ReviewService.getInstance(project)

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("claude-review").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
    }

    override fun tearDown() {
        try {
            reviews.state.files.toList().let { if (it.isNotEmpty()) reviews.acceptFiles(it) }
            dispatch()
            dir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test rejecting one hunk restores those lines on disk and keeps the others`() {
        val file = claudeWrote("Main.kt", "a\nB\nc\nd\nE\n")
        reviews.recordEdit(file.path, "a\nb\nc\nd\ne\n")
        val review = awaitHunks(file, 2)

        reviews.rejectHunk(review, review.hunks.first())
        waitFor { review.hunks.size == 1 }
        assertEquals("a\nb\nc\nd\nE\n", file.readText())
        assertTrue(reviews.takeRejectionNote()!!.contains("Main.kt"))
        assertNull("the note is given once", reviews.takeRejectionNote())

        reviews.acceptHunk(review, review.hunks.single())
        waitFor { reviews.state.files.isEmpty() }
        assertEquals("a\nb\nc\nd\nE\n", file.readText())
    }

    fun `test open editors show added lines and blocks for removed lines and buttons`() {
        val file = claudeWrote("Main.kt", "a\nB\nc\nd\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, virtualFile), false)!!
        reviews.recordEdit(file.path, "a\nb\nc\nd\ne\n")
        val review = awaitHunks(file, 2)

        // Hunk 1 (b -> B): a removed block above, buttons below. Hunk 2 (e deleted): one block with both.
        waitFor { editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size == 3 }
        val added = DocumentMarkupModel.forDocument(editor.document, project, true).allHighlighters
            .filter { it.targetArea == HighlighterTargetArea.LINES_IN_RANGE }
        assertEquals(1, added.size)

        reviews.acceptFiles(listOf(review))
        waitFor { editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).isEmpty() }
        FileEditorManager.getInstance(project).closeFile(virtualFile)
    }

    fun `test later edits keep the first baseline`() {
        val file = claudeWrote("Main.kt", "a\nB\nc\n")
        reviews.recordEdit(file.path, "a\nb\nc\n")
        val review = awaitHunks(file, 1)

        // Claude edits again; the CLI reports the already-edited text as "original".
        claudeWrote("Main.kt", "a\nB\nC\n")
        reviews.recordEdit(file.path, "a\nB\nc\n")
        waitFor { review.current == "a\nB\nC\n" }
        assertEquals("a\nb\nc\n", review.baseline)
        assertEquals(listOf("b", "c"), review.hunks.single().removed)
    }

    fun `test rejecting a created file deletes it`() {
        val file = claudeWrote("Greeter.kt", "class Greeter\n")
        reviews.recordEdit(file.path, null)
        val review = awaitHunks(file, 1)
        assertTrue(review.created)

        reviews.rejectFiles(listOf(review))
        waitFor { !file.exists() }
        assertTrue(reviews.state.files.isEmpty())
    }

    fun `test accepting a file keeps its text and ends its review`() {
        val file = claudeWrote("Main.kt", "x\n")
        reviews.recordEdit(file.path, "y\n")
        val review = awaitHunks(file, 1)

        reviews.acceptFiles(listOf(review))
        waitFor { reviews.state.files.isEmpty() }
        assertEquals("x\n", file.readText())
        assertNull(reviews.takeRejectionNote())
    }

    fun `test restoring a checkpoint rewrites changed files, deletes created ones and ends their review`() {
        val changed = claudeWrote("Main.kt", "a\nB\n")
        val created = claudeWrote("New.kt", "class New\n")
        val gone = File(dir, "Gone.kt") // deleted after the checkpoint, e.g. by a shell command
        reviews.recordEdit(changed.path, "a\nb\n")
        awaitHunks(changed, 1)

        var finished = false
        reviews.restore(
            mapOf(ReviewService.key(changed.path) to "a\nb\n", ReviewService.key(created.path) to null, gone.path to "class Gone\n"),
        ) { finished = true }
        waitFor { finished }

        assertEquals("a\nb\n", changed.readText())
        assertFalse(created.exists())
        assertEquals("class Gone\n", gone.readText())
        assertTrue(reviews.state.files.isEmpty())
    }

    /** Writes [text] behind the IDE's back, as the CLI does, and returns the file. */
    private fun claudeWrote(name: String, text: String): File {
        val file = File(dir, name)
        val previous = file.lastModified()
        file.writeText(text)
        // The VFS notices a change by size and timestamp; back-to-back writes here can share both.
        file.setLastModified(maxOf(previous + 2000, file.lastModified()))
        return file
    }

    private fun awaitHunks(file: File, count: Int): FileReview {
        waitFor { reviews.state.find(ReviewService.key(file.path))?.hunks?.size == count }
        return reviews.state.find(ReviewService.key(file.path))!!
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            dispatch()
            Thread.sleep(10)
        }
    }

    private fun dispatch() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
}
