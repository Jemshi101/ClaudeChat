package `in`.bleuphoenix.claudechat.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import `in`.bleuphoenix.claudechat.cli.CommandScope
import `in`.bleuphoenix.claudechat.cli.CustomCommand
import `in`.bleuphoenix.claudechat.cli.CustomCommands
import org.jetbrains.annotations.VisibleForTesting
import javax.swing.JComponent

/** Adds or edits a custom slash command; [result] is the command to save. */
class CommandDialog(
    project: Project?,
    private val existing: CustomCommand?,
    private val taken: Set<Pair<String, CommandScope>>,
    private val hasProject: Boolean = true,
) : DialogWrapper(project) {

    @VisibleForTesting
    lateinit var nameField: JBTextField
    private lateinit var scopeBox: ComboBox<CommandScope>
    private lateinit var descriptionField: JBTextField
    private lateinit var hintField: JBTextField

    @VisibleForTesting
    val promptArea = JBTextArea(existing?.body.orEmpty(), 16, 72).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
    }

    var result: CustomCommand? = null
        private set

    init {
        title = if (existing == null) "Add Command" else "Edit /${existing.name}"
        init()
    }

    @VisibleForTesting
    fun scope(value: CommandScope) {
        scopeBox.selectedItem = value
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            nameField = textField().align(AlignX.FILL)
                .comment("Typed after / in the chat. Use folder:name to keep commands in a subfolder.").component
            nameField.text = existing?.name.orEmpty()
        }
        row("For:") {
            val scopes = if (hasProject) CommandScope.entries else listOf(CommandScope.USER)
            scopeBox = comboBox(scopes, textListCellRenderer { it?.label }).component
            scopeBox.selectedItem = existing?.scope ?: scopes.first()
            comment("This project's commands are in .claude/commands and can be committed for the team.")
        }
        row("Description:") {
            descriptionField = textField().align(AlignX.FILL).comment("Shown next to the command in the / list.").component
            descriptionField.text = existing?.description.orEmpty()
        }
        row("Arguments hint:") {
            hintField = textField().align(AlignX.FILL).comment("Optional, e.g. <code>[issue number]</code>.").component
            hintField.text = existing?.argumentHint.orEmpty()
        }
        row("Prompt:") {}
        row {
            cell(JBScrollPane(promptArea)).align(Align.FILL)
                .comment(
                    "What Claude gets. <code>\$ARGUMENTS</code> is replaced by what you type after the command " +
                        "(<code>\$1</code>, <code>\$2</code> for single words); <code>@path</code> includes a file.",
                )
        }.resizableRow()
    }

    override fun getPreferredFocusedComponent(): JComponent = if (existing == null) nameField else promptArea

    @VisibleForTesting
    public override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim().removePrefix("/")
        val scope = scopeBox.selectedItem as CommandScope
        return when {
            name.isEmpty() -> ValidationInfo("Enter a name", nameField)
            !CustomCommands.validName(name) -> ValidationInfo("Use letters, digits, - _ . and : between folders", nameField)
            (name to scope) in taken && (existing?.name to existing?.scope) != (name to scope) ->
                ValidationInfo("There's already a /$name for ${scope.label.lowercase()}", nameField)
            promptArea.text.isBlank() -> ValidationInfo("Enter the prompt", promptArea)
            else -> null
        }
    }

    @VisibleForTesting
    public override fun doOKAction() {
        result = CustomCommand(
            name = nameField.text.trim().removePrefix("/"),
            scope = scopeBox.selectedItem as CommandScope,
            description = descriptionField.text.trim(),
            argumentHint = hintField.text.trim(),
            body = promptArea.text,
            file = existing?.file,
            otherFrontmatter = existing?.otherFrontmatter.orEmpty(),
        )
        super.doOKAction()
    }
}
