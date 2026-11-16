package `in`.bleuphoenix.claudechat.ide

import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import `in`.bleuphoenix.claudechat.plan.PlanEditorProvider
import `in`.bleuphoenix.claudechat.plan.PlanFiles
import java.awt.datatransfer.StringSelection
import java.io.File

/** Everything the chat does to the IDE: opening files, showing diffs and refreshing files Claude changed. */
class IdeBridge(private val project: Project) {

    /** Opens a link from a reply: web links in the browser, anything else as a file path (see [FileLink]). */
    fun openLink(target: String) {
        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
            BrowserUtil.browse(target)
            return
        }
        val link = FileLink.parse(target) ?: return
        openFile(link.path, link.line)
    }

    /** Opens [path] (absolute, project-relative, or a bare file name) at the 1-based [line]. */
    fun openFile(path: String, line: Int? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = resolve(path)
            ApplicationManager.getApplication().invokeLater({
                if (file == null) {
                    Messages.showInfoMessage(project, "Couldn't find $path in this project.", "Claude Chat")
                    return@invokeLater
                }
                val descriptor = if (line != null) {
                    OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0)
                } else {
                    OpenFileDescriptor(project, file)
                }
                descriptor.navigate(true)
            }, project.disposed)
        }
    }

    /**
     * Opens a plan file on its rendered Plan view (the text editor is the next tab). Refreshes first: the CLI may
     * have just rewritten it.
     */
    fun openPlanTab(path: String, onFailure: (String) -> Unit = {}) {
        LOG.info("Opening the Plan tab for $path")
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = runCatching { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(absolutePath(path))) }
                .onFailure { LOG.warn("Couldn't find the plan file $path", it) }
                .getOrNull()
            // Finding alone doesn't re-read a file the IDE already has, e.g. a plan Claude revised.
            file?.let { VfsUtil.markDirtyAndRefresh(false, false, false, it) }
            ApplicationManager.getApplication().invokeLater({
                if (file == null) {
                    LOG.warn("Plan file not found: $path (exists: ${File(absolutePath(path)).exists()})")
                    onFailure("Couldn't open the plan: $path wasn't found.")
                    return@invokeLater
                }
                try {
                    val editors = FileEditorManager.getInstance(project)
                    val opened = editors.openFile(file, true)
                    LOG.info("Plan tab: ${file.path} opened with ${opened.map { it.javaClass.simpleName }} (plan file: ${PlanFiles.isPlan(file)})")
                    if (opened.isEmpty()) onFailure("Couldn't open the plan in an editor tab: ${file.path}")
                    editors.setSelectedEditor(file, PlanEditorProvider.TYPE_ID)
                } catch (e: Exception) {
                    LOG.warn("Couldn't open the Plan tab for ${file.path}", e)
                    onFailure("Couldn't open the plan in an editor tab: ${e.message ?: e}")
                }
            }, ModalityState.nonModal(), project.disposed)
        }
    }

    /**
     * Applies [change] to the text of the file at [path] through its document, so an open editor (e.g. the Plan tab)
     * shows it, as an undoable edit saved right away. Nothing happens if [change] returns the text unchanged.
     */
    fun editFile(path: String, commandName: String, change: (String) -> String) {
        ApplicationManager.getApplication().invokeLater({
            val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(absolutePath(path))) ?: return@invokeLater
            val documents = FileDocumentManager.getInstance()
            val document = documents.getDocument(file) ?: return@invokeLater
            val updated = change(document.text)
            if (updated == document.text) return@invokeLater
            WriteCommandAction.runWriteCommandAction(project, commandName, null, { document.setText(updated) })
            documents.saveDocument(document)
        }, ModalityState.nonModal(), project.disposed)
    }

    /**
     * Saves any unsaved edits to the file at [path] and passes its text to [then] on the EDT, or null if the file is
     * gone. Runs via invokeLater because saving needs a write-safe context.
     */
    fun saveAndReadFile(path: String, then: (String?) -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            val ioFile = File(absolutePath(path))
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(ioFile)
            val documents = FileDocumentManager.getInstance()
            virtualFile?.let { documents.getDocument(it) }?.let { document ->
                runCatching { documents.saveDocument(document) }.onFailure { LOG.warn("Couldn't save $path", it) }
            }
            then(if (ioFile.isFile) ioFile.readText() else null)
        }, ModalityState.nonModal(), project.disposed)
    }

    /**
     * Shows what an Edit, MultiEdit or Write call changes. Works both before the call runs (the file still has the
     * old text) and after (the file already has the new text).
     */
    fun showDiff(toolName: String, input: JsonObject) {
        // Called from Swing click handlers; invokeLater runs with the lock the diff viewer and document access need.
        ApplicationManager.getApplication().invokeLater({ doShowDiff(toolName, input) }, project.disposed)
    }

    private fun doShowDiff(toolName: String, input: JsonObject) {
        val path = input.str("file_path") ?: return
        val file = File(absolutePath(path))
        val current = currentText(file)
        val (before, after) = when (toolName) {
            "Write" -> current to input.str("content").orEmpty()
            "Edit" -> EditDiff.beforeAfter(current, listOf(EditDiff.Edit.from(input)))
            "MultiEdit" -> EditDiff.beforeAfter(
                current,
                input.getAsJsonArray("edits")?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.let(EditDiff.Edit::from) }.orEmpty(),
            )
            else -> return
        }
        val type = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val contents = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                "Claude: ${file.name}",
                contents.create(project, before, type),
                contents.create(project, after, type),
                "Before",
                "After Claude's change",
            ),
        )
    }

    fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    /**
     * Inserts [text] at the caret of the active editor, replacing its selection, as one undoable command. Returns
     * false (and does nothing) when no editor is open. Runs via invokeLater: it's called from click handlers.
     */
    fun insertAtCaret(text: String, onNoEditor: () -> Unit = {}) {
        ApplicationManager.getApplication().invokeLater({
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null || !editor.document.isWritable) {
                onNoEditor()
                return@invokeLater
            }
            WriteCommandAction.runWriteCommandAction(project, "Insert Code from Claude", null, {
                val caret = editor.caretModel.primaryCaret
                val start = if (caret.hasSelection()) caret.selectionStart else caret.offset
                val end = if (caret.hasSelection()) caret.selectionEnd else caret.offset
                editor.document.replaceString(start, end, text)
                caret.removeSelection()
                caret.moveToOffset(start + text.length)
            })
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
        }, ModalityState.nonModal(), project.disposed)
    }

    /** Makes the editor show a file Claude just changed, without waiting for the IDE to notice. */
    fun refreshFile(path: String) {
        val file = File(absolutePath(path))
        // Finding the file reads the disk even for an async refresh, which is not allowed on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            LocalFileSystem.getInstance().refreshIoFiles(listOf(file), true, false, null)
        }
    }

    /** Picks up changes from shell commands, which can touch any file in the project. */
    fun refreshProject() {
        val base = project.basePath?.let(::File) ?: return
        ApplicationManager.getApplication().executeOnPooledThread { VfsUtil.markDirtyAndRefresh(true, true, true, base) }
    }

    /**
     * Saves open editors so Claude reads what the user sees, then runs [then] on the EDT.
     * Saving must happen in a write-safe context, which Swing listeners (Send button, Enter key) aren't,
     * so it's scheduled with invokeLater. A failed save is logged and [then] still runs.
     */
    fun saveAllDocumentsThen(then: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            runCatching { FileDocumentManager.getInstance().saveAllDocuments() }
                .onFailure { LOG.warn("Couldn't save open documents", it) }
            then()
        }, ModalityState.nonModal(), project.disposed)
    }

    /** Path relative to the project root when the file is inside it, for references in prompts. */
    fun displayPath(file: VirtualFile): String {
        val base = project.basePath ?: return file.path
        return if (file.path.startsWith("$base/")) file.path.removePrefix("$base/") else file.path
    }

    /** [path] (relative to the project, absolute, or `~/…`) as an absolute path. */
    fun absolute(path: String): String = absolutePath(path)

    /** The file [path] names, looked up by name when it's not a full path. Call off the EDT. */
    fun findFile(path: String): VirtualFile? = resolve(path)

    private fun absolutePath(path: String): String {
        val expanded = if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path
        if (File(expanded).isAbsolute) return expanded
        return File(project.basePath ?: return expanded, expanded).path
    }

    /** Call off the EDT: may refresh the file system and query the file name index. */
    private fun resolve(path: String): VirtualFile? {
        val direct = File(absolutePath(path))
        if (direct.isFile) return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(direct)
        if (DumbService.isDumb(project)) return null

        // Claude often names a file without its full path; look it up by name and prefer a matching path suffix.
        val name = path.substringAfterLast('/')
        return runReadActionBlocking {
            val candidates = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
            candidates.firstOrNull { it.path.endsWith("/$path") } ?: candidates.firstOrNull()
        }
    }

    /** The file as the user sees it: the editor's unsaved text if open, else the disk contents, else empty. */
    private fun currentText(file: File): String = runReadActionBlocking {
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
        virtualFile?.let { FileDocumentManager.getInstance().getDocument(it)?.text }
            ?: virtualFile?.let { String(it.contentsToByteArray(), it.charset) }
            ?: ""
    }
}

private val LOG = logger<IdeBridge>()

internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
