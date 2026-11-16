package `in`.bleuphoenix.claudechat.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import `in`.bleuphoenix.claudechat.cli.McpKeys
import `in`.bleuphoenix.claudechat.cli.McpOAuth
import `in`.bleuphoenix.claudechat.cli.McpScope
import `in`.bleuphoenix.claudechat.cli.McpServer
import `in`.bleuphoenix.claudechat.cli.McpTransport
import org.jetbrains.annotations.VisibleForTesting
import javax.swing.JComponent

/**
 * Adds or edits an MCP server. The API key, if any, is shown masked and saved in the keychain; [result] is the server
 * to save, with the key's reference in its place, and [key] the key typed (null to keep the stored one).
 */
class McpServerDialog(project: Project?, private val existing: McpServer?, private val taken: Set<Pair<String, McpScope>>) : DialogWrapper(project) {

    private val found = existing?.let(McpKeys::find)

    private lateinit var nameField: JBTextField
    private lateinit var typeBox: ComboBox<McpTransport>
    private lateinit var scopeBox: ComboBox<McpScope>
    private lateinit var urlField: JBTextField
    private lateinit var commandField: JBTextField
    private lateinit var argsField: JBTextField
    private lateinit var keyField: JBPasswordField
    private lateinit var sentAsField: JBTextField
    private lateinit var extraArea: JBTextArea
    private lateinit var urlRow: com.intellij.ui.dsl.builder.Row
    private lateinit var oauthGroup: com.intellij.ui.dsl.builder.Row
    private lateinit var clientIdField: JBTextField
    private lateinit var clientSecretField: JBPasswordField
    private lateinit var callbackPortField: JBTextField
    private lateinit var scopesField: JBTextField
    private lateinit var metadataUrlField: JBTextField
    private lateinit var commandRows: List<com.intellij.ui.dsl.builder.Row>

    var result: McpServer? = null
        private set

    /** The key typed; null to keep the one in the keychain. */
    var key: String? = null
        private set

    /** The OAuth client secret typed, for the CLI to keep; null when none was typed. */
    var clientSecret: String? = null
        private set

    init {
        title = if (existing == null) "Add MCP Server" else "Edit MCP Server"
        init()
        updateType()
    }

    /** The dialog's form (dialogs in headless tests have no content pane to find it in). */
    @VisibleForTesting
    lateinit var form: JComponent
        private set

    override fun createCenterPanel(): JComponent = form(panel {
        row("Name:") {
            nameField = textField().align(AlignX.FILL).comment("Claude's tools from it are named mcp__&lt;name&gt;__…").component
            nameField.text = existing?.name.orEmpty()
        }
        row("Type:") {
            typeBox = comboBox(McpTransport.entries, textListCellRenderer { it?.label }).component
            typeBox.selectedItem = existing?.transport ?: McpTransport.HTTP
            typeBox.addActionListener { updateType() }
        }
        urlRow = row("URL:") {
            urlField = textField().align(AlignX.FILL).component
            urlField.emptyText.text = "https://example.com/mcp"
            urlField.text = existing?.url.orEmpty()
        }
        commandRows = listOf(
            row("Command:") {
                commandField = textField().align(AlignX.FILL).component
                commandField.emptyText.text = "npx"
                commandField.text = existing?.command.orEmpty()
            },
            row("Arguments:") {
                argsField = textField().align(AlignX.FILL).comment("Separated by spaces.").component
                argsField.emptyText.text = "-y some-mcp-server"
                argsField.text = existing?.args?.joinToString(" ").orEmpty()
            },
        )
        row("Scope:") {
            scopeBox = comboBox(McpScope.entries, textListCellRenderer { it?.label })
                .comment(".mcp.json servers are shared with the team through the repository; each person approves them once.")
                .component
            scopeBox.selectedItem = existing?.scope ?: McpScope.USER
        }
        group("API Key") {
            row("Key:") {
                keyField = cell(JBPasswordField()).align(AlignX.FILL).component
                found?.plainKey?.let { keyField.text = it }
                keyField.emptyText.text = if (found != null && found.plainKey == null) "Saved in the keychain; leave empty to keep it" else "Optional"
            }
            row("Sent as:") {
                sentAsField = textField().align(AlignX.FILL)
                    .comment("Where the key goes: a header (<code>Authorization: Bearer ${McpKeys.KEY_MARK}</code>) or, for a command, a variable (<code>API_KEY=${McpKeys.KEY_MARK}</code>).")
                    .component
                sentAsField.text = found?.sentAs.orEmpty()
            }
            row {
                comment("Kept in the system keychain, not in Claude Code's config, which holds a variable in its place.")
            }
        }
        oauthGroup = group("OAuth Sign-In") {
            val oauth = existing?.oauth
            row {
                comment(
                    "Most servers that use OAuth need nothing here: sign in from the MCP Servers page or the chat. Fill " +
                        "these in for a server that needs a client registered in advance, or particular scopes.",
                )
            }
            row("Client ID:") {
                clientIdField = textField().align(AlignX.FILL).component
                clientIdField.text = oauth?.clientId.orEmpty()
            }
            row("Client secret:") {
                clientSecretField = cell(JBPasswordField()).align(AlignX.FILL)
                    .comment("Optional. Kept by Claude Code in its own secure storage; type it again to change it.")
                    .component
                clientSecretField.emptyText.text = "Optional"
            }
            row("Scopes:") {
                scopesField = textField().align(AlignX.FILL).comment("Separated by spaces, e.g. <code>read write</code>.").component
                scopesField.text = oauth?.scopes.orEmpty()
            }
            row("Callback port:") {
                callbackPortField = textField().columns(6)
                    .comment("Only for a client registered with a fixed redirect URI on localhost.")
                    .component
                callbackPortField.text = oauth?.callbackPort?.toString().orEmpty()
            }
            row("Metadata URL:") {
                metadataUrlField = textField().align(AlignX.FILL)
                    .comment("The authorization server's metadata (https), when the server doesn't advertise it.")
                    .component
                metadataUrlField.text = oauth?.authServerMetadataUrl.orEmpty()
            }
        }
        group("More Headers or Variables") {
            row {
                extraArea = textArea().rows(3).align(AlignX.FILL)
                    .comment("One per line: <code>Name: value</code> headers, or <code>NAME=value</code> variables for a command. Saved as typed.")
                    .component
                val stdio = existing?.transport == McpTransport.STDIO
                val shown = (if (stdio) existing?.env else existing?.headers).orEmpty().filterKeys { it != found?.name }
                extraArea.text = shown.entries.joinToString("\n") { (k, v) -> if (stdio) "$k=$v" else "$k: $v" }
            }.layout(RowLayout.PARENT_GRID)
        }
    })

    private fun form(panel: JComponent): JComponent = panel.also { form = it }

    private fun updateType() {
        val stdio = typeBox.selectedItem == McpTransport.STDIO
        urlRow.visible(!stdio)
        oauthGroup.visible(!stdio)
        commandRows.forEach { it.visible(stdio) }
        // Offer the usual place for the key when none is set yet.
        val default = if (stdio) "API_KEY=${McpKeys.KEY_MARK}" else "Authorization: Bearer ${McpKeys.KEY_MARK}"
        if (sentAsField.text.isBlank() || sentAsField.text in DEFAULT_PLACES) sentAsField.text = default
    }

    @VisibleForTesting
    public override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        val transport = typeBox.selectedItem as McpTransport
        val scope = scopeBox.selectedItem as McpScope
        if (!NAME.matches(name)) return ValidationInfo("Use letters, digits, - and _.", nameField)
        if ((name to scope) in taken && (name != existing?.name || scope != existing.scope)) {
            return ValidationInfo("There's already a server named $name in ${scope.label.lowercase()}.", nameField)
        }
        if (transport == McpTransport.STDIO) {
            if (commandField.text.isBlank()) return ValidationInfo("Enter the command that starts the server.", commandField)
        } else if (!urlField.text.trim().matches(Regex("https?://\\S+"))) {
            return ValidationInfo("Enter the server's http:// or https:// URL.", urlField)
        }
        val hasKey = keyField.password.isNotEmpty() || (found != null && found.plainKey == null)
        if (hasKey && McpKeys.placement(sentAsField.text, transport, name) == null) {
            val example = if (transport == McpTransport.STDIO) "API_KEY=${McpKeys.KEY_MARK}" else "Authorization: Bearer ${McpKeys.KEY_MARK}"
            return ValidationInfo("Write where the key goes, e.g. $example.", sentAsField)
        }
        if (transport != McpTransport.STDIO) {
            val port = callbackPortField.text.trim()
            if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) {
                return ValidationInfo("Enter a port number from 1 to 65535.", callbackPortField)
            }
            val metadata = metadataUrlField.text.trim()
            if (metadata.isNotEmpty() && !metadata.matches(Regex("https://\\S+"))) {
                return ValidationInfo("Enter an https:// URL.", metadataUrlField)
            }
            if (clientSecretField.password.isNotEmpty() && clientIdField.text.isBlank()) {
                return ValidationInfo("A client secret goes with a client ID.", clientIdField)
            }
        }
        extraLines().firstOrNull { parseExtra(it, transport) == null }?.let {
            return ValidationInfo("Not ${if (transport == McpTransport.STDIO) "NAME=value" else "Name: value"}: $it", extraArea)
        }
        return null
    }

    @VisibleForTesting
    public override fun doOKAction() {
        val name = nameField.text.trim()
        val transport = typeBox.selectedItem as McpTransport
        val typedKey = String(keyField.password)
        val hasKey = typedKey.isNotEmpty() || (found != null && found.plainKey == null)
        val values = extraLines().mapNotNull { parseExtra(it, transport) }.toMap(LinkedHashMap())
        if (hasKey) McpKeys.placement(sentAsField.text, transport, name)?.let { (k, v) -> values[k] = v }
        result = McpServer(
            name = name,
            scope = scopeBox.selectedItem as McpScope,
            transport = transport,
            url = if (transport == McpTransport.STDIO) "" else urlField.text.trim(),
            command = if (transport == McpTransport.STDIO) commandField.text.trim() else "",
            args = if (transport == McpTransport.STDIO) argsField.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } else emptyList(),
            headers = if (transport == McpTransport.STDIO) emptyMap() else values,
            env = if (transport == McpTransport.STDIO) values else emptyMap(),
            oauth = if (transport == McpTransport.STDIO) null else McpOAuth(
                clientId = clientIdField.text.trim(),
                callbackPort = callbackPortField.text.trim().toIntOrNull(),
                scopes = scopesField.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "),
                authServerMetadataUrl = metadataUrlField.text.trim(),
            ).takeUnless { it.isEmpty },
        )
        key = typedKey.ifEmpty { null }
        clientSecret = String(clientSecretField.password).takeIf { it.isNotEmpty() && transport != McpTransport.STDIO }
        super.doOKAction()
    }

    private fun extraLines() = extraArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseExtra(line: String, transport: McpTransport): Pair<String, String>? {
        val separator = if (transport == McpTransport.STDIO) '=' else ':'
        val name = line.substringBefore(separator, "").trim()
        return if (name.isEmpty()) null else name to line.substringAfter(separator).trim()
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_-]+")
        val DEFAULT_PLACES = setOf("API_KEY=${McpKeys.KEY_MARK}", "Authorization: Bearer ${McpKeys.KEY_MARK}")
    }
}
