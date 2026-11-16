package `in`.bleuphoenix.claudechat.edit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import `in`.bleuphoenix.claudechat.ide.IdeBridge
import `in`.bleuphoenix.claudechat.review.ReviewService
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.io.File
import java.io.IOException

/**
 * Apply on a reply's code block: Claude merges the block into its file with a one-shot `claude -p` that has no tools
 * (like Edit with Claude), and the change shows in the editor for Accept / Reject. A file the block names that doesn't
 * exist yet is created with the block, after asking.
 */
object ApplyCode {
    private val LOG = logger<ApplyCode>()

    /** Larger files are left to the chat: Claude would have to write the whole file back. */
    const val MAX_FILE_CHARS = 80_000

    // Commands, output and diagrams, which aren't code to put in a file.
    private val NOT_CODE = setOf(
        "bash", "sh", "shell", "zsh", "fish", "console", "terminal", "shellsession", "powershell", "ps", "ps1", "cmd",
        "bat", "text", "txt", "plaintext", "output", "log", "mermaid", "diff", "patch",
    )

    fun applies(language: String) = language.lowercase() !in NOT_CODE

    fun prompt(path: String, language: String, fileText: String, code: String): String = """
        Apply a code suggestion to a file in the user's project. The suggestion may be the whole file or only part of
        it: a function, a class, a few lines, or an outline where comments such as "// ..." or "// existing code" stand
        for code that stays as it is. Merge it into the file where it belongs, replacing the code it updates and adding
        what's new. Keep everything else exactly as it is, including formatting and comments, and make no other changes.

        Reply with the complete updated file between <file> and </file> tags, and nothing else.
    """.trimIndent() + "\n\nFile: $path\n<current>\n$fileText\n</current>\n\nSuggestion${if (language.isNotEmpty()) " ($language)" else ""}:\n" +
        "<suggestion>\n$code\n</suggestion>\n"

    /** The updated file from Claude's [reply], ending in a line break when [original] does; null without `<file>` tags. */
    fun parseReply(reply: String, original: String): String? {
        val start = reply.indexOf("<file>")
        val end = reply.lastIndexOf("</file>")
        if (start < 0 || end < start) return null
        val text = reply.substring(start + "<file>".length, end).removePrefix("\r\n").removePrefix("\n").removeSuffix("\n")
        return if (original.endsWith("\n")) text + "\n" else text
    }

    /** The part of [old] that differs from [new], as start and end offsets in [old] and the text to put there. */
    fun changedRange(old: String, new: String): Triple<Int, Int, String> {
        val prefix = old.commonPrefixWith(new).length
        val maxSuffix = minOf(old.length, new.length) - prefix
        var suffix = 0
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return Triple(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
    }

    /** Applies [code] to the file at [path], or to the active editor's file without one. Call on the EDT. */
    fun start(project: Project, code: String, language: String, path: String?, onError: (String) -> Unit) {
        val ide = IdeBridge(project)
        if (path == null) {
            val file = FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile
            if (file == null) onError("Open the file to apply the code to, or ask Claude to make the change.")
            else apply(project, file, ide.displayPath(file), code, language, onError)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = ide.findFile(path)
            ApplicationManager.getApplication().invokeLater({
                if (file != null) apply(project, file, ide.displayPath(file), code, language, onError)
                else create(project, ide.absolute(path), path, code, onError)
            }, ModalityState.nonModal(), project.disposed)
        }
    }

    private fun create(project: Project, absolute: String, path: String, code: String, onError: (String) -> Unit) {
        val answer = Messages.showYesNoDialog(
            project, "$path doesn't exist yet. Create it with this code?", "Apply Code", "Create", "Cancel", null,
        )
        if (answer != Messages.YES) return
        try {
            val target = File(absolute)
            val file = WriteCommandAction.writeCommandAction(project).withName("Apply Code from Claude").compute<VirtualFile, IOException> {
                val parent = VfsUtil.createDirectories(target.parent)
                parent.createChildData(this, target.name).also { VfsUtil.saveText(it, code.removeSuffix("\n") + "\n") }
            }
            ReviewService.getInstance(project).recordEdit(file.path, null, fromChat = false)
            OpenFileDescriptor(project, file).navigate(true)
        } catch (e: IOException) {
            LOG.warn("Couldn't create $absolute", e)
            onError("Couldn't create $path: ${e.message}")
        }
    }

    private fun apply(project: Project, file: VirtualFile, path: String, code: String, language: String, onError: (String) -> Unit) {
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document == null || !document.isWritable || !file.isInLocalFileSystem) {
            onError("Can't apply code to $path: it isn't an editable text file.")
            return
        }
        val original = document.text
        if (original.length > MAX_FILE_CHARS) {
            onError("$path is too large to apply code to here; ask Claude to make the change instead.")
            return
        }
        OpenFileDescriptor(project, file).navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor?.takeIf { it.document == document }
        if (original.isBlank()) {
            replace(project, file, document, original, code.removeSuffix("\n") + "\n", editor)
            return
        }
        val workDir = project.basePath ?: System.getProperty("user.home")
        val prompt = prompt(path, language, original, code)
        LOG.info("Applying code to ${file.name} (${original.length} + ${code.length} chars)")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Claude is applying code to ${file.name}", true) {
            private var updated: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val reply = InlineEditRunner.run(workDir, prompt, ClaudeChatSettings.options.model, indicator, "Claude couldn't apply the code")
                updated = parseReply(reply, original) ?: throw IOException("Claude didn't send back the updated file.")
            }

            override fun onSuccess() {
                val text = updated ?: return
                when {
                    document.text != original -> onError("${file.name} changed while Claude was working; the code wasn't applied.")
                    text == original -> onError("Claude found nothing to change in ${file.name}; the code may already be there.")
                    else -> replace(project, file, document, original, text, editor)
                }
            }

            override fun onThrowable(error: Throwable) {
                if (error is ProcessCanceledException) return
                LOG.warn("Apply code failed", error)
                onError(error.message ?: error.toString())
            }
        })
    }

    private fun replace(
        project: Project,
        file: VirtualFile,
        document: Document,
        original: String,
        text: String,
        editor: Editor?,
    ) {
        val (start, end, replacement) = changedRange(original, text)
        WriteCommandAction.runWriteCommandAction(project, "Apply Code from Claude", null, {
            document.replaceString(start, end, replacement)
        })
        ReviewService.getInstance(project).recordEdit(file.path, original, fromChat = false)
        editor?.takeIf { !it.isDisposed }?.let {
            it.caretModel.moveToOffset(start.coerceAtMost(document.textLength))
            it.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }
}
