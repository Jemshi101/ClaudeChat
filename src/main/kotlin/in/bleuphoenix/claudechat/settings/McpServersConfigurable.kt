package `in`.bleuphoenix.claudechat.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import `in`.bleuphoenix.claudechat.cli.ClaudeCli
import `in`.bleuphoenix.claudechat.cli.McpKeys
import `in`.bleuphoenix.claudechat.cli.McpServer
import `in`.bleuphoenix.claudechat.cli.McpServers
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Settings → Tools → Claude Chat → MCP Servers: the servers Claude Code is configured with, for this project and
 * all projects, as in Cursor's MCP settings. Changes go through `claude mcp` right away, so there's nothing to apply.
 */
class McpServersConfigurable(private val project: Project) : Configurable {

    private val workDir: String? = project.basePath
    private val statuses = mutableMapOf<String, String>()
    private var checking = false

    /** A change, sign-in or sign-out is running. */
    private var busy = false

    private val model = ListTableModel<McpServer>(
        column("Name") { it.name },
        column("Scope") { it.scope.label },
        column("Type") { it.transport.label },
        column("URL or command") { it.target },
        object : ColumnInfo<McpServer, String>("Status") {
            override fun valueOf(item: McpServer) = statuses[item.name] ?: if (checking) "Checking…" else ""
            override fun getRenderer(item: McpServer): TableCellRenderer = StatusRenderer
        },
    )
    private val table = TableView(model)
    private val message = JBLabel()

    override fun getDisplayName() = "MCP Servers"

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
            .addExtraAction(object : DumbAwareAction("Check Status", "Connect to each server and report whether it works", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = checkStatus()
            })
            .addExtraAction(object : DumbAwareAction("Sign In", "Sign in to the selected server with OAuth, in the browser", AllIcons.Actions.Execute) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !busy && table.selectedObject?.canSignIn == true
                }
                override fun actionPerformed(e: AnActionEvent) {
                    table.selectedObject?.let(::signIn)
                }
            })
            .addExtraAction(object : DumbAwareAction("Sign Out", "Forget the selected server's OAuth sign-in", AllIcons.Actions.Exit) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !busy && table.selectedObject?.canSignIn == true
                }
                override fun actionPerformed(e: AnActionEvent) {
                    table.selectedObject?.let(::signOut)
                }
            })
            .createPanel()
        reload()
        checkStatus()
        return panel {
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row { cell(message) }
            row {
                comment(
                    "Saved with the claude CLI as soon as you change them; chats started afterwards use them. API keys are " +
                        "kept in the system keychain and the config holds <code>\${${McpKeys.variable("NAME")}}</code> " +
                        "instead. To use such a server from claude in a terminal, set that variable there.",
                )
            }
        }
    }

    override fun isModified() = false

    override fun apply() = Unit

    private fun reload() {
        val selected = table.selectedObject?.let { it.name to it.scope }
        model.items = McpServers.read(workDir)
        model.items.indexOfFirst { (it.name to it.scope) == selected }.takeIf { it >= 0 }?.let { table.selectionModel.setSelectionInterval(it, it) }
    }

    private fun taken() = model.items.map { it.name to it.scope }.toSet()

    private fun add() {
        val dialog = McpServerDialog(project, null, taken())
        if (dialog.showAndGet()) dialog.result?.let { save(it, dialog.key, null, dialog.clientSecret) }
    }

    private fun edit(server: McpServer) {
        val dialog = McpServerDialog(project, server, taken())
        if (dialog.showAndGet()) dialog.result?.let { save(it, dialog.key, server, dialog.clientSecret) }
    }

    private fun signIn(server: McpServer) =
        inBackground("Finish signing in to ${server.name} in your browser…") { exe -> McpServers.login(exe, workDir, server) }

    private fun signOut(server: McpServer) =
        inBackground("Signing out of ${server.name}…") { exe -> McpServers.logout(exe, workDir, server) }

    private fun remove(server: McpServer) {
        val answer = Messages.showYesNoDialog(table, "Remove ${server.name} (${server.scope.label.lowercase()})?", "Remove MCP Server", null)
        if (answer != Messages.YES) return
        val before = model.items.toList()
        inBackground("Removing ${server.name}…") { exe ->
            McpServers.remove(exe, workDir, server).also { if (it == null) forgetUnusedKeys(before) }
        }
    }

    private fun save(server: McpServer, key: String?, replacing: McpServer?, clientSecret: String?) {
        val before = model.items.toList()
        inBackground("Saving ${server.name}…") { exe -> saveNow(exe, server, key, replacing, before, clientSecret) }
    }

    private fun saveNow(exe: String, server: McpServer, key: String?, replacing: McpServer?, before: List<McpServer>, clientSecret: String?): String? {
        val variable = McpKeys.variable(server.name)
        if (variable in McpKeys.referencedBy(server)) {
            // A new key, or the one already saved (under the old name, after a rename).
            val value = key ?: McpKeys.get(variable) ?: replacing?.let { McpKeys.get(McpKeys.variable(it.name)) }
            if (value != null) McpKeys.set(variable, value)
        }
        return McpServers.save(exe, workDir, server, replacing, clientSecret).also { if (it == null) forgetUnusedKeys(before) }
    }

    /** Takes keys out of the keychain once no server refers to them. */
    private fun forgetUnusedKeys(before: List<McpServer>) {
        val used = McpServers.read(workDir).flatMap(McpKeys::referencedBy).toSet()
        val dropped = before.flatMap(McpKeys::referencedBy).toSet() - used
        dropped.forEach { McpKeys.set(it, null) }
    }

    private fun inBackground(label: String, work: (String) -> String?) {
        busy = true
        message.foreground = JBColor.foreground()
        message.text = label
        ApplicationManager.getApplication().executeOnPooledThread {
            val exe = ClaudeCli.findExecutable()
            val error = if (exe == null) "Couldn't find claude; set its path on the Claude Chat page." else work(exe)
            onEdt {
                busy = false
                message.text = error?.let { "Failed: $it" }.orEmpty()
                message.foreground = if (error != null) JBColor.RED else JBColor.foreground()
                reload()
                if (error == null) checkStatus()
            }
        }
    }

    private fun checkStatus() {
        if (checking) return
        checking = true
        statuses.clear()
        model.fireTableDataChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = ClaudeCli.findExecutable()?.let { McpServers.statuses(it, workDir) }.orEmpty()
            onEdt {
                checking = false
                statuses.putAll(found)
                model.fireTableDataChanged()
            }
        }
    }

    // The settings dialog is modal, so updates must be allowed to run while it's open.
    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    private object StatusRenderer : DefaultTableCellRenderer() {
        private fun readResolve(): Any = StatusRenderer

        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column)
            val text = value as? String ?: ""
            if (!selected) {
                foreground = when {
                    text.startsWith("Connected") -> JBColor(0x2E7D32, 0x6BC46D)
                    text.startsWith("Failed") -> JBColor.RED
                    text.isEmpty() || text == "Checking…" -> JBColor.GRAY
                    else -> JBColor(0xA15C00, 0xE5A84B)
                }
            }
            return this
        }
    }

    private companion object {
        fun column(name: String, value: (McpServer) -> String) = object : ColumnInfo<McpServer, String>(name) {
            override fun valueOf(item: McpServer) = value(item)
        }
    }
}
