package `in`.bleuphoenix.claudechat.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Tracks the files Claude changes so they can be reviewed hunk by hunk in the "Claude Changes" tab: accepting a hunk
 * folds it into the file's baseline, rejecting it puts the baseline's lines back into the document (undoable).
 *
 * Baselines come from the CLI's tool results (`originalFile`, see docs/PROTOCOL.md), so they're exact even though the
 * CLI writes the file before the IDE hears about it.
 */
@Service(Service.Level.PROJECT)
class ReviewService(private val project: Project) : ReviewActions, Disposable {

    val state = ReviewState()

    /** A single tab per project shows every pending change. */
    val reviewFile: ReviewVirtualFile by lazy { ReviewVirtualFile() }

    // Changes are also shown in the editors themselves, with their own Reject and Accept.
    private val inline = InlineReview(project, this).also { Disposer.register(this, it) }

    private val listeners = HashMap<String, Disposable>()
    private val documents = HashMap<String, Document>()
    private val rejected = linkedSetOf<String>()

    /**
     * Records that Claude changed [path]. [original] is the text before this edit, or null if the edit created the
     * file. A file already under review keeps its earlier baseline. [fromChat] is false for an inline edit, which the
     * chat doesn't know about. Call on the EDT.
     */
    fun recordEdit(path: String, original: String?, fromChat: Boolean = true) {
        val key = key(path)
        val review = state.find(key) ?: FileReview(key, StringUtil.convertLineSeparators(original.orEmpty()), created = original == null)
            .also { it.fromChat = false; state.files += it }
        if (fromChat) review.fromChat = true
        // The CLI wrote the file on disk; load the new text into the IDE, then follow the document.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Finding alone doesn't re-read a file the IDE already knows; refresh it too.
                val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(key))
                file?.let { VfsUtil.markDirtyAndRefresh(false, false, false, it) }
                onEdt { file?.let { follow(key, it) } }
            } catch (e: Exception) {
                LOG.warn("Couldn't load $key for review", e)
            }
        }
    }

    fun openTab(focus: Boolean) {
        FileEditorManager.getInstance(project).openFile(reviewFile, focus)
    }

    /**
     * A note for Claude naming files where the user rejected changes since the last call, or null. The files no
     * longer match what Claude last wrote, so it should read them again before editing.
     */
    fun takeRejectionNote(): String? {
        if (rejected.isEmpty()) return null
        val names = rejected.joinToString(", ") { it.substringAfterLast('/') }
        rejected.clear()
        return "(From the IDE: the user rejected some of your changes in $names. These files now differ from what " +
            "you last wrote; read them again before editing them.)"
    }

    /**
     * Puts files back as they were at a checkpoint (see [Checkpoints.restoreTo]): text for each path, or null to
     * delete a file the later turns created. Edits go through the documents, so each file's restore can be undone.
     * Files under review leave the review, since the change being reviewed is gone. Runs [then] on the EDT after.
     */
    fun restore(files: Map<String, String?>, then: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = files.keys.associateWith { path ->
                runCatching { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path)) }.getOrNull()
            }
            later {
                files.forEach { (path, text) ->
                    state.find(key(path))?.let(::drop)
                    val file = found[path]
                    when {
                        text == null -> file?.let(::delete)
                        file == null -> recreate(File(path), text)
                        else -> FileDocumentManager.getInstance().getDocument(file)?.let {
                            setText(it, StringUtil.convertLineSeparators(text), "Restore Checkpoint")
                        }
                    }
                }
                then()
            }
        }
    }

    /** A file Claude deleted (e.g. with a shell command) after the checkpoint. */
    private fun recreate(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        } catch (e: Exception) {
            LOG.warn("Couldn't restore ${file.path}", e)
        }
    }

    // ---- ReviewActions --------------------------------------------------------------------------------------------
    // Called from Compose click handlers, which aren't write-safe: changes go through invokeLater (see IdeBridge).

    override fun acceptHunk(file: FileReview, hunk: Hunk) = later {
        val current = file.current ?: return@later LOG.warn("Accept ignored: ${file.name} has no text yet")
        val hunk = fresh(file, hunk, current) ?: return@later
        file.baseline = ReviewText.accept(file.baseline, hunk)
        file.update(current)
        documents[file.path]?.let { inline.update(file, it) }
        dropIfDone(file)
    }

    override fun rejectHunk(file: FileReview, hunk: Hunk) = later {
        val document = documents[file.path] ?: return@later LOG.warn("Reject ignored: ${file.name} isn't open for review")
        val hunk = fresh(file, hunk, document.text) ?: return@later
        setText(document, ReviewText.reject(document.text, hunk), "Reject Claude's Change")
        if (file.fromChat) rejected += file.path
        dropIfDone(file)
    }

    override fun acceptFiles(files: List<FileReview>) = later {
        files.forEach { file ->
            file.current?.let { file.baseline = it }
            drop(file)
        }
    }

    override fun rejectFiles(files: List<FileReview>) = later {
        files.forEach { file ->
            val document = documents[file.path]
            if (file.created) {
                // Undoing a new file means removing it.
                document?.let { FileDocumentManager.getInstance().getFile(it) }?.let { delete(it) }
            } else if (document != null) {
                setText(document, file.baseline, "Reject Claude's Changes")
            }
            if (file.fromChat) rejected += file.path
            drop(file)
        }
    }

    /** The review of the file open in [document], if it has changes waiting. */
    fun reviewFor(document: Document): FileReview? =
        documents.entries.firstOrNull { it.value === document }?.key?.let(state::find)?.takeIf { it.hunks.isNotEmpty() }

    override fun openAt(file: FileReview, line: Int) {
        val virtualFile = documents[file.path]?.let { FileDocumentManager.getInstance().getFile(it) } ?: return
        OpenFileDescriptor(project, virtualFile, line.coerceAtLeast(0), 0).navigate(true)
    }

    // ---- Internals ------------------------------------------------------------------------------------------------

    /**
     * [hunk] as it stands in [text] now. The file may have changed since the button was drawn (formatting, saving,
     * typing), shifting or recomputing its hunks: the review is refreshed and the same change looked up again by its
     * lines, nearest to where it was. Null, and logged, if it's gone.
     */
    private fun fresh(file: FileReview, hunk: Hunk, text: String): Hunk? {
        if (ReviewText.matches(text, hunk)) return hunk
        file.update(text)
        val same = file.hunks.filter { it.added == hunk.added && it.removed == hunk.removed }
            .minByOrNull { kotlin.math.abs(it.currentStart - hunk.currentStart) }
        if (same == null) LOG.warn("Change at line ${hunk.currentStart + 1} of ${file.name} no longer matches the file; ignored")
        return same
    }

    /** Keeps [path]'s hunks in step with its document, including edits made by the user or by Claude later on. */
    private fun follow(path: String, file: VirtualFile) {
        val review = state.find(path) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        if (documents.put(path, document) == null) {
            val disposable = Disposer.newDisposable(this, "Claude review: $path")
            listeners[path] = disposable
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val changed = state.find(path) ?: return
                    changed.update(event.document.text)
                    inline.update(changed, event.document)
                }
            }, disposable)
        }
        review.update(document.text)
        inline.update(review, document)
        LOG.info("Reviewing ${review.name}: ${review.hunks.size} change(s), +${review.added} -${review.removed}")
    }

    private fun setText(document: Document, text: String, commandName: String) {
        val edit = ReviewText.minimalEdit(document.text, text)
        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.replaceString(edit.start, edit.end, edit.replacement)
        })
        // Save, so Claude reads the reverted text from disk.
        FileDocumentManager.getInstance().saveDocument(document)
    }

    private fun delete(file: VirtualFile) {
        try {
            WriteAction.run<Exception> { file.delete(this) }
        } catch (e: Exception) {
            LOG.warn("Couldn't delete ${file.path}", e)
        }
    }

    private fun dropIfDone(file: FileReview) {
        if (file.hunks.isEmpty()) drop(file)
    }

    private fun drop(file: FileReview) {
        state.files.remove(file)
        inline.clear(file.path)
        documents.remove(file.path)
        listeners.remove(file.path)?.let(Disposer::dispose)
    }

    private fun later(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.nonModal(), project.disposed)

    private fun onEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any(), project.disposed)

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<ReviewService>()

        fun getInstance(project: Project): ReviewService = project.service()

        fun key(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)
    }
}
