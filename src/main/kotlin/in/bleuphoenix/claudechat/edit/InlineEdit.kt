package `in`.bleuphoenix.claudechat.edit

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.bleuphoenix.claudechat.ide.IdeBridge
import `in`.bleuphoenix.claudechat.review.ReviewService
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel

/**
 * Cursor's Cmd+K: an instruction for the selected code (or for code to insert at the caret), answered by Claude in
 * the editor as a change to accept or reject. Bound to Cmd+Alt+K on macOS and Ctrl+Alt+\ elsewhere (Cmd+K is
 * Commit in Android Studio).
 */
class InlineEditAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && editor.document.isWritable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        askForInstruction(editor) { instruction -> InlineEdit.start(project, editor, instruction) }
    }

    /** A one-line box under the caret: Enter sends, Escape or a click elsewhere cancels. */
    private fun askForInstruction(editor: Editor, then: (String) -> Unit) {
        val field = JBTextField(36).apply {
            emptyText.text = if (editor.selectionModel.hasSelection()) "Edit the selection…" else "Generate code here…"
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(8)
            add(field, BorderLayout.CENTER)
            add(JBLabel("Enter to send · Esc to cancel").apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.SOUTH)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, field)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setTitle("Edit with Claude")
            .createPopup()
        field.addActionListener {
            val instruction = field.text.trim()
            if (instruction.isEmpty()) return@addActionListener
            popup.closeOk(null)
            then(instruction)
        }
        popup.showInBestPositionFor(editor)
    }
}

object InlineEdit {

    /**
     * Sends the selection (or the caret position) and [instruction] to Claude, marking the range while it works, then
     * replaces the range with the reply as one undoable command and puts the change up for review in the editor.
     */
    fun start(project: Project, editor: Editor, instruction: String) {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val selection = editor.selectionModel
        val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
        val end = if (selection.hasSelection()) selection.selectionEnd else start
        val before = document.text
        val selected = before.substring(start, end)
        val prompt = InlineEditPrompt.build(IdeBridge(project).displayPath(file), file.extension.orEmpty(), before, start, end, instruction)

        val range = document.createRangeMarker(start, end).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
        }
        val working = editor.markupModel.addRangeHighlighter(
            start, end.coerceAtLeast(start), HighlighterLayer.SELECTION - 1,
            TextAttributes().apply { backgroundColor = WORKING },
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        selection.removeSelection()
        val workDir = project.basePath ?: System.getProperty("user.home")
        LOG.info("Inline edit in ${file.name} (${end - start} chars)")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Claude is editing ${file.name}", true) {
            private var replacement: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val reply = InlineEditRunner.run(workDir, prompt, ClaudeChatSettings.options.model, indicator)
                val code = InlineEditPrompt.parseReply(reply, selected)
                    ?: throw java.io.IOException("Claude didn't suggest a change.")
                replacement = if (start == end) InlineEditPrompt.fitInsertion(code, before, start) else code
            }

            override fun onSuccess() {
                val text = replacement ?: return
                // The user may have typed in the range while Claude worked; don't overwrite that.
                if (!range.isValid || document.getText(range.textRange) != selected) {
                    hint(editor, "The code changed while Claude was working; the edit wasn't applied.")
                    return
                }
                val original = document.text
                WriteCommandAction.runWriteCommandAction(project, "Edit with Claude", null, {
                    document.replaceString(range.startOffset, range.endOffset, text)
                })
                editor.caretModel.moveToOffset(range.startOffset)
                if (file.isInLocalFileSystem) ReviewService.getInstance(project).recordEdit(file.path, original, fromChat = false)
            }

            override fun onThrowable(error: Throwable) {
                if (error is ProcessCanceledException) return
                LOG.warn("Inline edit failed", error)
                hint(editor, error.message ?: error.toString())
            }

            override fun onFinished() {
                editor.markupModel.removeHighlighter(working)
                range.dispose()
            }
        })
    }

    private fun hint(editor: Editor, message: String) = ApplicationManager.getApplication().invokeLater({
        if (!editor.isDisposed) HintManager.getInstance().showErrorHint(editor, message)
    }, ModalityState.nonModal())

    private val LOG = logger<InlineEdit>()
    private val WORKING = JBColor(Color(84, 130, 255, 36), Color(84, 130, 255, 44))
}
