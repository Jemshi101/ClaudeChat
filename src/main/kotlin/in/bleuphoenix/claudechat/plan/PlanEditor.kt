package `in`.bleuphoenix.claudechat.plan

import `in`.bleuphoenix.claudechat.ui.withSwingSizes
import androidx.compose.runtime.mutableStateOf
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import `in`.bleuphoenix.claudechat.ide.IdeBridge
import `in`.bleuphoenix.claudechat.ide.KeymapBridge
import `in`.bleuphoenix.claudechat.ui.compose.PlanDocumentView
import `in`.bleuphoenix.claudechat.ui.compose.ProvideModernMarkdown
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.KeyStroke
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling

/** Adds a rendered "Plan" view, placed before the text editor, to plan files (see [PlanFiles]). */
class PlanEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile) = PlanFiles.isPlan(file)

    override fun acceptRequiresReadAction() = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = PlanEditor(project, file)

    override fun getEditorTypeId() = TYPE_ID

    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    companion object {
        const val TYPE_ID = "claude-chat-plan"
    }
}

/**
 * The plan rendered as a document, Mermaid flowcharts included, with Build actions while the plan can be built.
 * Follows the file's document, so edits in the text tab show up here as they're typed.
 */
class PlanEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(file) }
    private val text = mutableStateOf(document?.text ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrDefault(""))
    private val plans = PlanService.getInstance(project)
    private val ide = IdeBridge(project)

    private val component: JComponent = JewelComposePanel(focusOnClickInside = true) {
        ProvideMarkdownStyling(project) {
            ProvideModernMarkdown {
                PlanDocumentView(
                    markdown = text.value,
                    pending = plans.pendingFor(file.path),
                    onUrlClick = ide::openLink,
                    onEdit = ::edit,
                )
            }
        }
    }.withSwingSizes()

    init {
        KeymapBridge.install(component, this)
        // Cursor's Build Plan key; Ctrl+Enter on macOS too, for Windows-style keymaps.
        val keys = listOfNotNull("meta ENTER".takeIf { SystemInfo.isMac }, "ctrl ENTER").map { KeyboardShortcut(KeyStroke.getKeyStroke(it), null) }
        DumbAwareAction.create { plans.pendingFor(file.path)?.build?.invoke(true) }
            .registerCustomShortcutSet(CustomShortcutSet(*keys.toTypedArray()), component, this)
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                text.value = event.document.text
            }
        }, this)
        LOG.info("Plan editor opened for ${file.name}")
    }

    /**
     * Applies [change] to the plan file's text, as an undoable edit saved right away (Build reads the file). Called from
     * a Compose click handler, so the write goes through invokeLater.
     */
    private fun edit(change: (String) -> String) {
        val document = document ?: return
        ApplicationManager.getApplication().invokeLater({
            val updated = change(document.text)
            if (updated == document.text) return@invokeLater
            WriteCommandAction.runWriteCommandAction(project, "Edit Plan To-dos", null, { document.setText(updated) })
            FileDocumentManager.getInstance().saveDocument(document)
        }, ModalityState.nonModal(), project.disposed)
    }

    override fun getComponent() = component
    override fun getPreferredFocusedComponent() = component
    override fun getName() = "Plan"
    override fun getFile() = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit

    private companion object {
        val LOG = logger<PlanEditor>()
    }
}
