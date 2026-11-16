package `in`.bleuphoenix.claudechat.edit

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import `in`.bleuphoenix.claudechat.review.ReviewService
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.io.File
import java.nio.file.Files

/** Inline edits against the fake CLI's one-shot mode: what Claude is asked, and how the answer lands in the editor. */
class InlineEditTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private var savedCliPath: String? = null
    private val reviews get() = ReviewService.getInstance(project)

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("inline-edit").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val script = File(dir, "fake_claude.py").apply { writeText(File("src/test/resources/fake-cli/fake_claude.py").readText()) }
        val launcher = File(dir, "claude").apply {
            writeText("#!/bin/sh\nexec /usr/bin/python3 -I '${script.path}' \"$@\"\n")
            setExecutable(true)
        }
        savedCliPath = ClaudeChatSettings.options.cliPath
        ClaudeChatSettings.options.cliPath = launcher.path
        File(project.basePath!!).mkdirs()
        reviews.takeRejectionNote()
    }

    override fun tearDown() {
        try {
            ClaudeChatSettings.options.cliPath = savedCliPath
            reviews.state.files.toList().let { if (it.isNotEmpty()) reviews.acceptFiles(it) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            FileEditorManager.getInstance(project).let { manager -> manager.openFiles.forEach(manager::closeFile) }
            dir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test the selection is replaced with Claude's code and offered for review`() {
        val source = File(dir, "Main.kt").apply { writeText("fun main(args: Array<String>) {\n    val name = if (args.size > 0) args[0] else \"world\"\n}\n") }
        File(dir, "reply.txt").writeText("```kotlin\n    val name = args.getOrNull(0) ?: \"world\"\n```")
        val editor = open(source)
        val start = editor.document.text.indexOf("    val")
        editor.selectionModel.setSelection(start, editor.document.text.indexOf("}"))

        InlineEdit.start(project, editor, "use getOrNull")
        waitFor { editor.document.text.contains("getOrNull") }
        assertEquals("fun main(args: Array<String>) {\n    val name = args.getOrNull(0) ?: \"world\"\n}\n", editor.document.text)

        val prompt = File(dir, "prompts.txt").readText()
        assertTrue(prompt.contains("<selection>    val name = if (args.size > 0) args[0] else \"world\"\n</selection>}"))
        assertTrue(prompt.contains("Instruction: use getOrNull"))
        val args = File(dir, "args.jsonl").readText()
        assertTrue("no tools, no MCP, no session", listOf("\"--tools\", \"\"", "--strict-mcp-config", "--no-session-persistence").all { it in args })

        // Shown as a change to review in the editor; rejecting it restores the code and isn't reported to the chat.
        waitFor { reviews.state.find(source.path)?.hunks?.size == 1 }
        reviews.rejectFiles(listOf(reviews.state.find(source.path)!!))
        waitFor { editor.document.text.contains("args.size > 0") }
        assertNull(reviews.takeRejectionNote())
    }

    fun `test a single change from an inline edit can be rejected or accepted on its own`() {
        val source = File(dir, "Hunk.kt").apply { writeText("val a = 1\nval b = 2\nval c = 3\n") }
        File(dir, "reply.txt").writeText("```kotlin\nval b = 20\n```")
        val editor = open(source)
        editor.selectionModel.setSelection(editor.document.text.indexOf("val b"), editor.document.text.indexOf("val c"))

        InlineEdit.start(project, editor, "make b 20")
        waitFor { reviews.state.find(source.path)?.hunks?.size == 1 }
        val review = reviews.state.find(source.path)!!
        reviews.rejectHunk(review, review.hunks.single())
        waitFor { editor.document.text == "val a = 1\nval b = 2\nval c = 3\n" }
        waitFor { reviews.state.find(source.path) == null }

        File(dir, "reply.txt").writeText("```kotlin\nval b = 21\n```")
        editor.selectionModel.setSelection(editor.document.text.indexOf("val b"), editor.document.text.indexOf("val c"))
        InlineEdit.start(project, editor, "make b 21")
        waitFor { reviews.state.find(source.path)?.hunks?.size == 1 }
        val again = reviews.state.find(source.path)!!
        reviews.acceptHunk(again, again.hunks.single())
        waitFor { reviews.state.find(source.path) == null }
        assertEquals("val a = 1\nval b = 21\nval c = 3\n", editor.document.text)
    }

    fun `test a change still rejects and accepts after the file shifted under it`() {
        val source = File(dir, "Shift.kt").apply { writeText("val a = 1\nval b = 2\nval c = 3\n") }
        File(dir, "reply.txt").writeText("```kotlin\nval b = 20\n```")
        val editor = open(source)
        editor.selectionModel.setSelection(editor.document.text.indexOf("val b"), editor.document.text.indexOf("val c"))
        InlineEdit.start(project, editor, "make b 20")
        waitFor { reviews.state.find(source.path)?.hunks?.size == 1 }
        val review = reviews.state.find(source.path)!!
        val shown = review.hunks.single()

        // E.g. a formatter or an import added above the change after the button was drawn.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, "import x\n")
        }
        reviews.rejectHunk(review, shown)
        waitFor { editor.document.text == "import x\nval a = 1\nval b = 2\nval c = 3\n" }
    }

    fun `test without a selection the code is inserted at the caret`() {
        val source = File(dir, "Insert.kt").apply { writeText("fun main() {\n}\n") }
        File(dir, "reply.txt").writeText("```kotlin\n    println(\"hi\")\n```")
        val editor = open(source)
        editor.caretModel.moveToOffset(editor.document.text.indexOf("}"))

        InlineEdit.start(project, editor, "print hi")
        waitFor { editor.document.text.contains("println") }
        assertEquals("fun main() {\n    println(\"hi\")\n}\n", editor.document.text)
        assertTrue(File(dir, "prompts.txt").readText().contains("fun main() {\n<cursor/>}"))
    }

    fun `test an empty answer changes nothing`() {
        val source = File(dir, "Same.kt").apply { writeText("val x = 1\n") }
        File(dir, "reply.txt").writeText("")
        val editor = open(source)
        editor.selectionModel.setSelection(0, 9)
        InlineEdit.start(project, editor, "do something")
        waitFor { File(dir, "prompts.txt").exists() }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("val x = 1\n", editor.document.text)
        assertNull(reviews.state.find(source.path))
    }

    private fun open(file: File) = FileEditorManager.getInstance(project)
        .openTextEditor(OpenFileDescriptor(project, LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!), true)!!

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }
}
