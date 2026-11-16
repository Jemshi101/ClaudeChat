package `in`.bleuphoenix.claudechat.review

import `in`.bleuphoenix.claudechat.ui.withSwingSizes
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import `in`.bleuphoenix.claudechat.ide.KeymapBridge
import `in`.bleuphoenix.claudechat.ui.compose.ReviewDocumentView
import org.jetbrains.jewel.bridge.JewelComposePanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** The in-memory file behind the "Claude Changes" tab; it has no content of its own. */
class ReviewVirtualFile : LightVirtualFile("Claude Changes") {
    init {
        isWritable = false
    }
}

class ReviewEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile) = file is ReviewVirtualFile

    override fun acceptRequiresReadAction() = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ReviewEditor(project, file)

    override fun getEditorTypeId() = "claude-chat-review"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Every pending change Claude made, file by file, with Accept and Reject on each hunk. */
class ReviewEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val reviews = ReviewService.getInstance(project)

    private val component: JComponent = JewelComposePanel(focusOnClickInside = true) {
        ReviewDocumentView(reviews.state, reviews)
    }.withSwingSizes()

    init {
        KeymapBridge.install(component, this)
        LOG.info("Review tab opened with ${reviews.state.pending.size} file(s) pending")
    }

    override fun getComponent() = component
    override fun getPreferredFocusedComponent() = component
    override fun getName() = "Changes"
    override fun getFile() = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit

    private companion object {
        val LOG = logger<ReviewEditor>()
    }
}
