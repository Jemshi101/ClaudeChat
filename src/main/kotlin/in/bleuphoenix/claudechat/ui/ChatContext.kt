package `in`.bleuphoenix.claudechat.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import `in`.bleuphoenix.claudechat.ide.FileMatch
import `in`.bleuphoenix.claudechat.ide.IdeBridge
import `in`.bleuphoenix.claudechat.ide.ImageAttachments
import `in`.bleuphoenix.claudechat.ide.ProjectFileSearch
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * The context side of one chat: follows the active editor's file, looks up files for `@` mentions, and turns pasted
 * or dropped files and images into attachments. Call on the EDT.
 */
class ChatContext(private val project: Project, private val state: ChatState, parent: Disposable) {

    private val ide = IdeBridge(project)
    private val search = ProjectFileSearch(project)

    // Bumped for every mention change, so a slow search can't overwrite a newer one.
    private var mentionRequest = 0

    init {
        project.messageBus.connect(parent).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = showActiveFile(event.newFile)
        })
        showActiveFile(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    /** The active file with the caret's line, if it's included as context. */
    fun activeFileForMessage(): ActiveFile? {
        val active = state.activeFile?.takeIf { state.activeFileIncluded } ?: return null
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val line = editor?.takeIf { it.virtualFile?.let(ide::displayPath) == active.path }?.caretModel?.logicalPosition?.line
        return active.copy(line = line?.plus(1))
    }

    private fun showActiveFile(file: VirtualFile?) {
        val active = file
            ?.takeIf { it.isInLocalFileSystem && !it.isDirectory && ProjectFileIndex.getInstance(project).isInContent(it) }
            ?.let { ActiveFile(ide.displayPath(it), null) }
        if (active?.path == state.activeFile?.path) return
        state.activeFile = active
        state.activeFileIncluded = true
    }

    fun mentionChanged(query: String?, start: Int) {
        val current = state.mention
        if (query != null && current != null && current.query == query && current.start == start) return
        val request = ++mentionRequest
        if (query == null) {
            state.mention = null
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = runCatching { search.search(query) }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (request == mentionRequest) state.mention = MentionState(query, start, results)
            }, ModalityState.any(), project.disposed)
        }
    }

    fun moveMentionSelection(delta: Int) {
        val mention = state.mention ?: return
        if (mention.results.isEmpty()) return
        state.mention = MentionState(mention.query, mention.start, mention.results, (mention.selected + delta).mod(mention.results.size))
    }

    fun pickMention(match: FileMatch) {
        val mention = state.mention ?: return
        val end = (mention.start + 1 + mention.query.length).coerceAtMost(state.input.text.length)
        state.input.edit { replace(mention.start, end, "") }
        mentionRequest++
        state.mention = null
        attach(FileAttachment(state.newKey(), match.path, match.isDirectory))
    }

    /** Adds a file (with an optional 1-based line range) unless the same one is already attached. */
    fun attach(file: FileAttachment) {
        val duplicate = state.attachments.filterIsInstance<FileAttachment>().any { it.path == file.path && it.lines == file.lines }
        if (!duplicate) state.attachments += file
        state.focusRequests++
    }

    fun remove(attachment: Attachment) {
        state.attachments.remove(attachment)
    }

    /**
     * Attaches copied images or files from the clipboard, or a long text as a chip; false for anything else, which
     * the message box then pastes as text.
     */
    fun paste(): Boolean {
        val contents = CopyPasteManager.getInstance().contents ?: return false
        if (add(contents)) return true
        val text = runCatching { contents.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull() ?: return false
        if (!TextAttachment.isLong(text)) return false
        attachText("Pasted text", text)
        return true
    }

    /** Adds [text] as a chip named after its [source]. */
    fun attachText(source: String, text: String) {
        state.attachments += TextAttachment(state.newKey(), source, text)
        state.focusRequests++
    }

    /** Puts a collapsed text back into the message box, where the cursor is, in place of its chip. */
    fun expand(attachment: TextAttachment) {
        if (!state.attachments.remove(attachment)) return
        state.input.edit { replace(selection.min, selection.max, attachment.text) }
        state.focusRequests++
    }

    /** Whether a drag carries files (from the project tree, editor tabs, Finder or Explorer) or an image. */
    fun canDrop(transferable: Transferable): Boolean =
        ImageAttachments.hasImage(transferable) || ImageAttachments.filesIn(transferable).isNotEmpty()

    /** Attaches the files and images in [transferable]; false when it carries neither. */
    fun add(transferable: Transferable): Boolean {
        val images = ImageAttachments.fromTransferable(transferable, state::newKey)
        val files = ImageAttachments.filesIn(transferable).filterNot(ImageAttachments::isImageFile)
        state.attachments += images
        files.forEach(::addFile)
        if (images.isNotEmpty()) state.focusRequests++
        return images.isNotEmpty() || files.isNotEmpty()
    }

    /** Attaches [files]: images as images, anything else as an `@` mention. */
    fun addFiles(files: List<File>) {
        files.filter(ImageAttachments::isImageFile).mapNotNull { ImageAttachments.fromFile(it, state.newKey()) }.forEach { state.attachments += it }
        files.filterNot(ImageAttachments::isImageFile).forEach(::addFile)
    }

    private fun addFile(file: File) {
        val base = project.basePath
        val path = if (base != null && file.path.startsWith("$base/")) file.path.removePrefix("$base/") else file.path
        attach(FileAttachment(state.newKey(), path, file.isDirectory))
    }
}
