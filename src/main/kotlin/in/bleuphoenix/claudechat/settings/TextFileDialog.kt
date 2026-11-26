package `in`.bleuphoenix.claudechat.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.VisibleForTesting
import javax.swing.JComponent

/** Shows a rules or memory file's text to read or change; [text] is what to save after OK. */
class TextFileDialog(project: Project?, title: String, private val path: String, initial: String) : DialogWrapper(project) {

    @VisibleForTesting
    val area = JBTextArea(initial, 24, 90).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
        caretPosition = 0
    }

    val text: String get() = area.text

    init {
        this.title = title
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { comment(path) }
        row { cell(JBScrollPane(area)).align(Align.FILL) }.resizableRow()
        row { comment("Saved as Markdown. Chats started after saving use it.") }
    }

    override fun getPreferredFocusedComponent(): JComponent = area
}
