package `in`.bleuphoenix.claudechat.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import `in`.bleuphoenix.claudechat.cli.CliCatalog
import `in`.bleuphoenix.claudechat.cli.CustomCommand
import `in`.bleuphoenix.claudechat.cli.CustomCommands
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JComponent

/**
 * Settings → Tools → Claude Chat Suite → Commands: the custom slash commands Claude Code finds for this project and for
 * all projects, to add, change or remove. Saved as soon as a dialog closes, so there's nothing to apply.
 */
class CommandsConfigurable internal constructor(private val project: Project, private val userDir: File) : Configurable {

    constructor(project: Project) : this(project, CustomCommands.userDir())

    private val workDir: String? = project.basePath

    private val model = ListTableModel<CustomCommand>(
        column("Command") { "/" + it.name },
        column("For") { it.scope.label },
        column("Description") { it.summary },
    )
    private val table = TableView(model)
    private val message = JBLabel()

    override fun getDisplayName() = "Commands"

    override fun createComponent(): JComponent {
        table.setShowGrid(false)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) table.selectedObject?.let(::edit)
            }
        })
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { add() }
            .setEditAction { table.selectedObject?.let(::edit) }
            .setRemoveAction { table.selectedObject?.let(::remove) }
            .addExtraAction(object : DumbAwareAction("Open in Editor", "Open the command's file in the editor", AllIcons.Actions.EditSource) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedObject?.file != null
                }
                override fun actionPerformed(e: AnActionEvent) {
                    table.selectedObject?.file?.let { openInEditor(project, it, message) }
                }
            })
            .createPanel()
        reload()
        return panel {
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row { cell(message) }
            row {
                comment(
                    "Type / in the chat to run one; what you type after it is passed on. This project's commands are " +
                        "in <code>.claude/commands</code>, yours for all projects in <code>${userDir.path.replace(System.getProperty("user.home"), "~")}</code>. " +
                        "New chats see changes.",
                )
            }
        }
    }

    override fun isModified() = false

    override fun apply() = Unit

    private fun reload() {
        val selected = table.selectedObject?.let { it.name to it.scope }
        model.items = CustomCommands.read(workDir, userDir)
        model.items.indexOfFirst { (it.name to it.scope) == selected }.takeIf { it >= 0 }?.let { table.selectionModel.setSelectionInterval(it, it) }
    }

    private fun taken() = model.items.map { it.name to it.scope }.toSet()

    private fun add() {
        val dialog = CommandDialog(project, null, taken(), hasProject = workDir != null)
        if (dialog.showAndGet()) dialog.result?.let { save(it, null) }
    }

    private fun edit(command: CustomCommand) {
        val dialog = CommandDialog(project, command, taken(), hasProject = workDir != null)
        if (dialog.showAndGet()) dialog.result?.let { save(it, command) }
    }

    private fun save(command: CustomCommand, replacing: CustomCommand?) = change("Saved /${command.name}.") {
        CustomCommands.save(command, replacing, workDir, userDir)
    }

    private fun remove(command: CustomCommand) {
        val answer = Messages.showYesNoDialog(
            table,
            "Delete /${command.name} (${command.scope.label.lowercase()})?\n${command.file?.path.orEmpty()}",
            "Delete Command",
            null,
        )
        if (answer == Messages.YES) change("Deleted /${command.name}.") { CustomCommands.delete(command, workDir, userDir) }
    }

    private fun change(done: String, work: () -> Unit) {
        val error = runCatching(work).exceptionOrNull()
        message.text = error?.let { "Failed: ${it.message}" } ?: done
        message.foreground = if (error != null) JBColor.RED else JBColor.foreground()
        // The / list caches the project's commands; reading them again picks up the change.
        workDir?.let(CliCatalog::forget)
        reload()
    }

    private companion object {
        fun column(name: String, value: (CustomCommand) -> String) = object : ColumnInfo<CustomCommand, String>(name) {
            override fun valueOf(item: CustomCommand) = value(item)
        }
    }
}

/** Opens [file] in the editor; it shows once Settings closes, as [message] says. */
internal fun openInEditor(project: Project, file: File, message: JBLabel) {
    val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    if (virtual == null) {
        message.text = "Couldn't open ${file.path}"
        message.foreground = JBColor.RED
        return
    }
    FileEditorManager.getInstance(project).openFile(virtual, true)
    message.text = "Opened ${file.name} in the editor; close Settings to see it."
    message.foreground = JBColor.foreground()
}
