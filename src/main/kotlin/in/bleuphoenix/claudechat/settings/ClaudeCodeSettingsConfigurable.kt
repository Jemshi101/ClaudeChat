package `in`.bleuphoenix.claudechat.settings

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import `in`.bleuphoenix.claudechat.cli.ClaudeSettings
import `in`.bleuphoenix.claudechat.cli.ClaudeSettingsFiles
import `in`.bleuphoenix.claudechat.cli.SettingsScope
import java.io.File
import java.io.IOException
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * Settings → Tools → Claude Chat Suite → Claude Code Settings: the permission rules and environment variables in
 * Claude Code's own settings files, for this project (only me, or shared) and for all projects. Other settings in the
 * files are kept; Open in Editor shows the whole file.
 */
class ClaudeCodeSettingsConfigurable internal constructor(
    private val project: Project?,
    private val projectDir: String?,
    private val home: File,
) : Configurable {

    constructor(project: Project) : this(project, project.basePath, File(System.getProperty("user.home")))

    /** An environment variable row; mutable so the table can edit it. */
    class EnvVar(var name: String, var value: String)

    private val loaded = mutableMapOf<SettingsScope, ClaudeSettings>()
    private val edited = mutableMapOf<SettingsScope, ClaudeSettings>()
    private val errors = mutableMapOf<SettingsScope, String>()
    private var shown = SettingsScope.LOCAL

    private val scopes = ComboBox(SettingsScope.entries.filter { file(it) != null }.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.let { scope -> "${scope.label} (${scope.path})" } }
    }
    private val pathLabel = JBLabel().apply { foreground = JBColor.GRAY }
    private val openLink = ActionLink("Open in Editor") { openInEditor() }
    private val error = JBLabel().apply { foreground = JBColor.RED }
    private val ruleLists = RuleKind.entries.associateWith { CollectionListModel<String>() }
    private val envModel = ListTableModel<EnvVar>(
        envColumn("Name", { it.name }) { row, value -> row.name = value },
        envColumn("Value", { it.value }) { row, value -> row.value = value },
    )
    private val envTable = TableView(envModel)
    private val editors = mutableListOf<JComponent>()

    private enum class RuleKind(val title: String, val hint: String) {
        ALLOW("Allow", "Claude uses these without asking."),
        ASK("Ask", "Claude always asks first, even when an Allow rule matches."),
        DENY("Deny", "Claude can't use these."),
    }

    override fun getDisplayName() = "Claude Code Settings"

    override fun createComponent(): JComponent {
        val tabs = JBTabbedPane()
        RuleKind.entries.forEach { kind -> tabs.addTab(kind.title, rulesPanel(kind)) }
        envTable.setShowGrid(false)
        val envPanel = ToolbarDecorator.createDecorator(envTable)
            .setAddAction {
                envModel.addRow(EnvVar("", ""))
                val row = envModel.rowCount - 1
                envTable.selectionModel.setSelectionInterval(row, row)
                envTable.editCellAt(row, 0)
            }
            .setRemoveAction { stopEditing(); envTable.selectedObject?.let { envModel.removeRow(envModel.indexOf(it)) } }
            .disableUpDownActions()
            .createPanel()
        editors += envTable
        tabs.addTab("Environment", envPanel)

        scopes.selectedItem = shown
        scopes.addActionListener {
            val scope = scopes.selectedItem as? SettingsScope ?: return@addActionListener
            if (scope == shown) return@addActionListener
            capture()
            show(scope)
        }
        show(shown)
        return panel {
            row("Settings file:") { cell(scopes); cell(openLink); cell(pathLabel) }
            row { cell(error) }
            row { cell(tabs).align(Align.FILL) }.resizableRow()
            row {
                comment(
                    "Claude Code's own settings, as <code>/permissions</code> and \"Allow always\" save them. A rule is a " +
                        "tool, optionally with a pattern: <code>Bash(npm run test:*)</code>, <code>Read(./secrets/**)</code>, " +
                        "<code>WebFetch(domain:example.com)</code>, <code>mcp__server__tool</code>. Chats started after Apply " +
                        "use them; other settings in the file are kept.",
                )
            }
        }
    }

    private fun rulesPanel(kind: RuleKind): JComponent {
        val model = ruleLists.getValue(kind)
        val list = JBList(model).apply { selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION }
        editors += list
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { askRule(kind, null)?.let { model.add(it) } }
            .setEditAction {
                val index = list.selectedIndex.takeIf { it >= 0 } ?: return@setEditAction
                askRule(kind, model.getElementAt(index))?.let { model.setElementAt(it, index) }
            }
            .setRemoveAction { list.selectedIndices.sortedDescending().forEach(model::remove) }
            .createPanel()
        return panel {
            row { comment(kind.hint) }
            row { cell(decorated).align(Align.FILL) }.resizableRow()
        }
    }

    private fun askRule(kind: RuleKind, current: String?): String? = Messages.showInputDialog(
        envTable,
        "A tool, optionally with a pattern, e.g. Bash(git diff:*), Edit(./docs/**) or WebFetch(domain:example.com):",
        if (current == null) "Add ${kind.title} Rule" else "Edit ${kind.title} Rule",
        null,
        current,
        object : InputValidator {
            override fun checkInput(inputString: String?) = !inputString.isNullOrBlank()
            override fun canClose(inputString: String?) = checkInput(inputString)
        },
    )?.trim()

    private fun file(scope: SettingsScope) = ClaudeSettingsFiles.file(scope, projectDir, home)

    private fun load(scope: SettingsScope) {
        if (scope in loaded || scope in errors) return
        val file = file(scope) ?: return
        try {
            ClaudeSettingsFiles.read(file).let { loaded[scope] = it; edited[scope] = it }
        } catch (e: IOException) {
            errors[scope] = e.message ?: e.toString()
        }
    }

    private fun show(scope: SettingsScope) {
        shown = scope
        load(scope)
        val file = file(scope)
        pathLabel.text = if (file?.isFile == true) "" else "Not created yet"
        openLink.toolTipText = "${file?.path.orEmpty()}<br>Saves this page's changes and closes Settings"
        error.text = errors[scope]?.let { "$it Fix it in the editor; until then it can't be edited here." }.orEmpty()
        val settings = edited[scope] ?: ClaudeSettings()
        ruleLists.getValue(RuleKind.ALLOW).replaceAll(settings.allow)
        ruleLists.getValue(RuleKind.ASK).replaceAll(settings.ask)
        ruleLists.getValue(RuleKind.DENY).replaceAll(settings.deny)
        envModel.items = settings.env.map { (name, value) -> EnvVar(name, value) }
        editors.forEach { it.isEnabled = scope !in errors }
    }

    /** Takes what's shown into [edited]. */
    private fun capture() {
        if (shown in errors || shown !in edited) return
        stopEditing()
        edited[shown] = ClaudeSettings(
            allow = ruleLists.getValue(RuleKind.ALLOW).items.toList(),
            ask = ruleLists.getValue(RuleKind.ASK).items.toList(),
            deny = ruleLists.getValue(RuleKind.DENY).items.toList(),
            env = envModel.items.map { it.name.trim() to it.value }.filter { it.first.isNotEmpty() }.distinctBy { it.first },
        )
    }

    private fun stopEditing() {
        if (envTable.isEditing) envTable.cellEditor?.stopCellEditing()
    }

    override fun isModified(): Boolean {
        capture()
        return edited.any { (scope, settings) -> loaded[scope] != settings }
    }

    override fun apply() {
        capture()
        for ((scope, settings) in edited) {
            if (loaded[scope] == settings) continue
            val file = file(scope) ?: continue
            ClaudeSettingsFiles.write(file, settings)
            loaded[scope] = settings
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        }
        show(shown)
    }

    override fun reset() {
        loaded.clear()
        edited.clear()
        errors.clear()
        show(shown)
    }

    /** Applies the changes, closes Settings and opens the shown file (created empty if needed) in the editor. */
    private fun openInEditor() {
        val project = project ?: return
        val file = file(shown) ?: return
        if (shown !in errors) apply()
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            file.writeText("{\n}\n")
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return
        DialogWrapper.findInstance(pathLabel)?.close(DialogWrapper.CANCEL_EXIT_CODE)
        OpenFileDescriptor(project, virtualFile).navigate(true)
    }

    // For tests: the shown scope's lists and variables.
    internal fun select(scope: SettingsScope) { scopes.selectedItem = scope }
    internal fun rules(kind: String): CollectionListModel<String> = ruleLists.getValue(RuleKind.valueOf(kind))
    internal val env: ListTableModel<EnvVar> get() = envModel
    internal val errorText: String get() = error.text

    private companion object {
        fun envColumn(name: String, get: (EnvVar) -> String, set: (EnvVar, String) -> Unit) = object : ColumnInfo<EnvVar, String>(name) {
            override fun valueOf(item: EnvVar) = get(item)
            override fun isCellEditable(item: EnvVar) = true
            override fun setValue(item: EnvVar, value: String) = set(item, value)
        }
    }
}
