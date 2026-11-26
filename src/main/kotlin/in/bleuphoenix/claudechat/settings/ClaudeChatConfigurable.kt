package `in`.bleuphoenix.claudechat.settings

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import `in`.bleuphoenix.claudechat.ui.compose.ChatAppearance
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.openapi.keymap.KeymapUtil
import `in`.bleuphoenix.claudechat.cli.ClaudeCli
import `in`.bleuphoenix.claudechat.cli.CliCatalog
import `in`.bleuphoenix.claudechat.cli.Effort
import `in`.bleuphoenix.claudechat.ui.ChatNotifier
import `in`.bleuphoenix.claudechat.ui.PermissionMode

/** Settings → Tools → Claude Chat. */
class ClaudeChatConfigurable : BoundConfigurable("Claude Chat Suite") {

    private val options = ClaudeChatSettings.options
    private lateinit var pathField: TextFieldWithBrowseButton
    private lateinit var testResult: JBLabel

    override fun createPanel(): DialogPanel = panel {
        group("Claude CLI") {
            row("Path:") {
                pathField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select the claude Executable"),
                )
                    .bindText({ options.cliPath.orEmpty() }, { options.cliPath = it.trim().ifEmpty { null } })
                    .align(AlignX.FILL)
                    .comment(
                        if (SystemInfo.isWindows) "Leave empty to find it automatically in %USERPROFILE%\\.local\\bin, npm's global folder, PATH or the Claude desktop app."
                        else "Leave empty to find it automatically in ~/.local/bin, Homebrew or PATH.",
                    )
                    .component
                // Empty, the field shows the claude that would be used, or where the installer puts it.
                (pathField.textField as? JBTextField)?.emptyText?.text =
                    ClaudeCli.findExecutable(null) ?: ClaudeCli.defaultLocations().first()
            }
            row {
                button("Test") { testCli() }
                testResult = cell(JBLabel()).component
            }
        }
        group("New Chats") {
            row("Model:") {
                // The CLI's own list once a chat has read it; any alias or full model name can be typed too.
                val models = CliCatalog.knownModels()
                val names = models.associate { it.value to it.displayName }
                comboBox(listOf(CliCatalog.DEFAULT) + models.map { it.value }.filter { it != CliCatalog.DEFAULT }, textListCellRenderer { value ->
                    value?.let { v -> names[v]?.let { "$it ($v)" } ?: v }
                })
                    .applyToComponent { isEditable = true }
                    .bindItem(
                        { options.model ?: CliCatalog.DEFAULT },
                        { options.model = it?.trim()?.takeUnless { value -> value.isEmpty() || value == CliCatalog.DEFAULT } },
                    )
                    .comment(
                        "Also set from the model picker under the message box, which switches the open chat too. " +
                            "An alias such as opus or haiku, or a full model name.",
                    )
            }
            row("Effort:") {
                comboBox(listOf<String?>(null) + Effort.LEVELS, textListCellRenderer { Effort.label(it) })
                    .bindItem({ options.effort }, { options.effort = it })
                    .comment(
                        "How much Claude thinks before answering: higher handles harder problems but is slower and uses " +
                            "more of your plan. Also set from the picker under the message box. Models offer the levels " +
                            "they support.",
                    )
            }
            row("Permission mode:") {
                comboBox(PermissionMode.CHOICES, textListCellRenderer { it?.label })
                    .bindItem(
                        { PermissionMode.fromCli(options.permissionMode ?: ClaudeChatSettings.DEFAULT_PERMISSION_MODE) },
                        { options.permissionMode = it?.id },
                    )
                    .comment("Can still be changed per chat from the selector under the message box, or with Shift+Tab.")
            }
            row("Instruction files:") {
                textField()
                    .bindText({ options.instructionFiles.orEmpty() }, { options.instructionFiles = it.trim().ifEmpty { null } })
                    .align(AlignX.FILL)
                    .comment(
                        "Project guides Claude gets instead of CLAUDE.md, e.g. <code>agent.md, spec.md</code>. Each is looked " +
                            "up in the project folder, then its parents up to the repository root. When any is found, no " +
                            "CLAUDE.md is loaded (the project's or its parents'). Empty uses CLAUDE.md as usual.",
                    )
            }
            row("Add to system prompt:") {
                textArea()
                    .rows(3)
                    .align(AlignX.FILL)
                    .bindText({ options.systemPromptAppend.orEmpty() }, { options.systemPromptAppend = it.trim().ifEmpty { null } })
                    .comment(
                        "Your own instructions for every chat, e.g. <code>Answer in British English.</code> or " +
                            "<code>Run the unit tests after changing code.</code> For project rules, CLAUDE.md is better.",
                    )
            }
            row("Turn limit:") {
                intTextField(0..1000)
                    .columns(6)
                    .bindIntText({ options.maxTurns }, { options.maxTurns = it })
                    .comment(
                        "The most steps Claude takes for one message (each reply, with the tools it runs) before it stops " +
                            "and waits for you. 0 for no limit.",
                    )
            }
            row("Compact the conversation at:") {
                comboBox(COMPACT_WINDOWS, textListCellRenderer { it?.let(::compactWindowLabel) })
                    .bindItem({ options.autoCompactWindow.takeIf { it in COMPACT_WINDOWS } ?: 0 }, { options.autoCompactWindow = it ?: 0 })
                    .comment(
                        "When Claude Code replaces a long chat with a summary. Auto lets it choose for the model, often " +
                            "200k–600k tokens; a fixed size compacts sooner but more often. Chats started after Apply use " +
                            "it. Any chat can also be compacted from the context ring under the message box.",
                    )
            }
            row {
                checkBox("Bold the key points in replies and plans")
                    .bindSelected({ options.emphasisInReplies }, { options.emphasisInReplies = it })
                    .comment("Claude puts conclusions, files to change and risks in bold, so they're easy to skim.")
            }
            row {
                checkBox("End plans with a to-do list")
                    .bindSelected({ options.todosInPlans }, { options.todosInPlans = it })
                    .comment("The Plan tab shows it as a to-do card: tick steps off or add new ones.")
            }
            row {
                checkBox("Include a flow diagram in plans")
                    .bindSelected({ options.diagramsInPlans }, { options.diagramsInPlans = it })
                    .comment("Claude adds a Mermaid flowchart to each plan; the Plan tab draws it.")
            }
            row {
                checkBox("Run read-only commands inside the project without asking")
                    .bindSelected({ options.autoApproveReads }, { options.autoApproveReads = it })
                    .comment(
                        "Commands like <code>grep</code>, <code>sed -n</code>, <code>cat</code>, <code>ls</code>, " +
                            "<code>find</code> and <code>git log</code>, when every file they read is in the project. " +
                            "Reading elsewhere, writing, or running other programs still asks.",
                    )
            }
            row {
                checkBox("Task list")
                    .bindSelected({ options.taskList }, { options.taskList = it })
                    .comment("Lets Claude keep a to-do list for longer tasks, shown above the message box.")
            }
            row {
                checkBox("Use Cursor's commands")
                    .bindSelected({ options.syncCursorCommands }, { options.syncCursorCommands = it })
                    .comment(
                        "Commands added in Cursor (<code>~/.cursor/commands</code>) are copied to Claude Code's, so they're " +
                            "under / here and in claude, and to Antigravity's global workflows when it's installed.",
                    )
            }
            row { comment("Changes apply to chats started after saving.") }
        }
        group("Appearance") {
            row("Chat text size:") {
                comboBox(ChatAppearance.FONT_PERCENTS, textListCellRenderer { it?.let { percent -> "$percent%" } })
                    .bindItem(
                        { options.chatFontPercent.takeIf { it in ChatAppearance.FONT_PERCENTS } ?: 100 },
                        { percent -> (percent ?: 100).let { options.chatFontPercent = it; ChatAppearance.fontPercent = it } },
                    )
                    .comment("Of the IDE's text size, for messages, code and the message box. Open chats change at once.")
            }
        }
        group("Notifications") {
            row {
                checkBox("When Claude finishes")
                    .bindSelected({ options.notifyFinished }, { options.notifyFinished = it })
                    .comment("Not for turns under ${ChatNotifier.MIN_SECONDS.toInt()} seconds, or when a queued message runs next.")
            }
            row {
                checkBox("When Claude needs permission, asks a question, or has a plan ready")
                    .bindSelected({ options.notifyNeedsYou }, { options.notifyNeedsYou = it })
            }
            row {
                checkBox("Only when I'm not looking at that chat")
                    .bindSelected({ options.notifyOnlyWhenAway }, { options.notifyOnlyWhenAway = it })
                    .comment("Android Studio is in the background, the Claude window is closed, or another chat tab is open.")
            }
            row {
                checkBox("Play a sound")
                    .bindSelected({ options.notifySound }, { options.notifySound = it })
            }
            row {
                comment(
                    "In the background, Android Studio shows them in the system's notification center. Settings → " +
                        "Appearance &amp; Behavior → Notifications sets how each kind is shown.",
                )
            }
        }
        group("Shortcuts") {
            SHORTCUTS.forEach { (actionId, label) ->
                row("$label:") {
                    label(KeymapUtil.getFirstKeyboardShortcutText(actionId).ifEmpty { "none" })
                }
            }
            row { comment("Change them in Settings → Keymap (search for \"Claude\").") }
        }
    }

    /** Runs `claude --version` with the path as currently typed, so it can be checked before saving. */
    private fun testCli() {
        val typed = pathField.text.trim().ifEmpty { null }
        testResult.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = try {
                val executable = ClaudeCli.findExecutable(typed)
                if (executable == null) {
                    if (typed != null) "Not found or not executable: $typed" else "Couldn't find claude automatically."
                } else {
                    val output = ExecUtil.execAndGetOutput(
                        GeneralCommandLine(executable, "--version")
                            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE),
                        TEST_TIMEOUT_MS,
                    )
                    if (output.exitCode == 0) "Found $executable: ${output.stdout.trim()}"
                    else "$executable exited with ${output.exitCode}: ${output.stderr.trim()}"
                }
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            // The settings dialog is modal, so the update must be allowed to run while it's open.
            ApplicationManager.getApplication().invokeLater({ testResult.text = message }, ModalityState.any())
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000
        val SHORTCUTS = listOf(
            "ClaudeChat.FocusChat" to "Focus Claude Chat",
            "ClaudeChat.InlineEdit" to "Edit with Claude",
            "ClaudeChat.ToggleActivity" to "Toggle Claude Chat Activity",
        )

        /** Auto-compact windows offered, in tokens; 0 is the CLI's own choice. */
        val COMPACT_WINDOWS = listOf(0, 200_000, 300_000, 400_000, 500_000)

        fun compactWindowLabel(tokens: Int) = if (tokens == 0) "Auto (recommended)" else "${tokens / 1000}k tokens"
    }
}
