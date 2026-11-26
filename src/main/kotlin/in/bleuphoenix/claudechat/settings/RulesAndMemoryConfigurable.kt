package `in`.bleuphoenix.claudechat.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import `in`.bleuphoenix.claudechat.cli.InstructionFiles
import `in`.bleuphoenix.claudechat.cli.RuleFile
import `in`.bleuphoenix.claudechat.cli.RuleKind
import `in`.bleuphoenix.claudechat.cli.RulesAndMemory
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JComponent

/**
 * Settings → Tools → Claude Chat Suite → Rules & Memory: what Claude reads at the start of every chat in this project
 * (CLAUDE.md files, `.claude/rules`, Instruction files) and what it remembered (auto memory), to read, change or add to.
 */
class RulesAndMemoryConfigurable internal constructor(private val project: Project, private val configDir: File) : Configurable {

    constructor(project: Project) : this(project, RulesAndMemory.configDir())

    private val workDir: String? = project.basePath
    private val home = System.getProperty("user.home")

    private val model = ListTableModel<RuleFile>(
        column("Kind") { it.kind.label },
        column("File") { shortPath(it.file) },
        column("About") { file ->
            if (!file.loaded) "Not loaded: Instruction files replace CLAUDE.md · ${file.about}" else file.about
        },
    )
    private val table = TableView(model)
    private val message = JBLabel()

    override fun getDisplayName() = "Rules & Memory"

    override fun createComponent(): JComponent {
        table.setShowGrid(false)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) table.selectedObject?.let(::edit)
            }
        })
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { addRule() }
            .setAddActionName("Add Rule…")
            .setEditAction { table.selectedObject?.let(::edit) }
            .setRemoveAction { table.selectedObject?.let(::remove) }
            .setRemoveActionUpdater { table.selectedObject?.let { it.kind == RuleKind.MEMORY } == true }
            .addExtraAction(object : DumbAwareAction("Open in Editor", "Open the file in the editor", AllIcons.Actions.EditSource) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedObject?.exists == true
                }
                override fun actionPerformed(e: AnActionEvent) {
                    table.selectedObject?.file?.let { openInEditor(project, it, message) }
                }
            })
            .addExtraAction(object : DumbAwareAction("Open Memory Folder", "Show the project's memory folder in the file manager", AllIcons.Actions.MenuOpen) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = workDir?.let { RulesAndMemory.memoryDir(it, configDir).isDirectory } == true
                }
                override fun actionPerformed(e: AnActionEvent) {
                    workDir?.let { RevealFileAction.openDirectory(RulesAndMemory.memoryDir(it, configDir)) }
                }
            })
            .createPanel()
        reload()
        return panel {
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row { cell(message) }
            row {
                comment(
                    "Rules are CLAUDE.md files: yours for all projects, the project's (commit it to share with the team), " +
                        "and those in parent folders. CLAUDE.local.md is only for you. Memory is what Claude saved while " +
                        "working here, one file per fact, listed in MEMORY.md. Chats started after a change use it.",
                )
            }
        }
    }

    override fun isModified() = false

    override fun apply() = Unit

    internal fun reload() {
        val selected = table.selectedObject?.file
        val instructions = workDir?.let { InstructionFiles.resolve(it, InstructionFiles.names(ClaudeChatSettings.options.instructionFiles)) }.orEmpty()
        model.items = workDir?.let { RulesAndMemory.list(it, instructions, configDir) }.orEmpty()
        model.items.indexOfFirst { it.file == selected }.takeIf { it >= 0 }?.let { table.selectionModel.setSelectionInterval(it, it) }
    }

    private fun edit(rule: RuleFile) {
        val initial = if (rule.file.isFile) runCatching { rule.file.readText() }.getOrDefault("") else ""
        val dialog = TextFileDialog(project, "${rule.kind.label}: ${rule.file.name}", shortPath(rule.file), initial)
        if (!dialog.showAndGet() || dialog.text == initial) return
        change("Saved ${rule.file.name}.") {
            rule.file.parentFile?.mkdirs()
            rule.file.writeText(dialog.text)
        }
    }

    private fun addRule() {
        val dir = workDir ?: return
        val targets = listOf(
            File(dir, "CLAUDE.md").takeUnless { File(dir, ".claude/CLAUDE.md").isFile } ?: File(dir, ".claude/CLAUDE.md"),
            File(dir, "CLAUDE.local.md"),
            File(configDir, "CLAUDE.md"),
        )
        val labels = listOf("This project (CLAUDE.md, shared)", "This project, only you (CLAUDE.local.md)", "All your projects (~/.claude/CLAUDE.md)")
        val dialog = AddRuleDialog(project, labels)
        if (!dialog.showAndGet()) return
        val target = targets[dialog.target]
        change("Added to ${shortPath(target)}.") { RulesAndMemory.addRule(target, dialog.rule) }
    }

    /** Deletes a memory file and its line in MEMORY.md. */
    private fun remove(rule: RuleFile) {
        if (rule.kind != RuleKind.MEMORY) return
        val answer = Messages.showYesNoDialog(table, "Forget this memory?\n${rule.about}", "Delete Memory", null)
        if (answer != Messages.YES) return
        change("Deleted ${rule.file.name}.") {
            rule.file.delete()
            val index = File(rule.file.parentFile, "MEMORY.md")
            if (index.isFile) {
                val lines = index.readLines()
                val kept = lines.filterNot { it.contains("(${rule.file.name})") }
                if (kept.size != lines.size) index.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun change(done: String, work: () -> Unit) {
        val error = runCatching(work).exceptionOrNull()
        message.text = error?.let { "Failed: ${it.message}" } ?: done
        message.foreground = if (error != null) JBColor.RED else JBColor.foreground()
        reload()
    }

    private fun shortPath(file: File): String {
        val base = workDir?.let { File(it).absolutePath + File.separator }
        return when {
            base != null && file.path.startsWith(base) -> file.path.removePrefix(base)
            file.path.startsWith(home) -> "~" + file.path.removePrefix(home)
            else -> file.path
        }
    }

    private class AddRuleDialog(project: Project, private val labels: List<String>) : DialogWrapper(project) {
        private lateinit var box: ComboBox<Int>
        private val area = JBTextArea(5, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        val target get() = box.selectedItem as Int
        val rule: String get() = area.text

        init {
            title = "Add Rule"
            setOKButtonText("Add")
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row("Add to:") { box = comboBox(labels.indices.toList(), textListCellRenderer { it?.let(labels::get) }).component }
            row("Rule:") {}
            row { cell(JBScrollPane(area)).align(Align.FILL).comment("e.g. <code>Run the unit tests after changing code.</code>") }
                .resizableRow()
        }

        override fun getPreferredFocusedComponent(): JComponent = area

        override fun doValidate(): ValidationInfo? = if (area.text.isBlank()) ValidationInfo("Enter the rule", area) else null
    }

    private companion object {
        fun column(name: String, value: (RuleFile) -> String) = object : ColumnInfo<RuleFile, String>(name) {
            override fun valueOf(item: RuleFile) = value(item)
        }
    }
}
