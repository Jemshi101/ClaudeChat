package `in`.bleuphoenix.claudechat.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Application-wide plugin settings. Changes apply to chats started afterwards. */
@Service(Service.Level.APP)
@State(name = "ClaudeChatSettings", storages = [Storage("claudeChat.xml")])
class ClaudeChatSettings : SimplePersistentStateComponent<ClaudeChatSettings.Options>(Options()) {

    /** A first install: new defaults apply, and nothing older needs carrying over later. */
    override fun noStateLoaded() {
        state.settingsVersion = SETTINGS_VERSION
    }

    override fun loadState(state: Options) {
        super.loadState(state)
        // Before version 2, Cursor command sync was on by default; it's off for new users now. Keep what users had.
        if (state.settingsVersion < 2) state.syncCursorCommands = state.cursorCommands
        state.settingsVersion = SETTINGS_VERSION
    }

    class Options : BaseState() {
        /** Path to the `claude` executable; null means detect it. */
        var cliPath by string()

        /** `--model` value (alias or full name); null means the CLI default. */
        var model by string()

        /** `--effort` level (see [`in`.bleuphoenix.claudechat.cli.Effort]); null means the CLI default. */
        var effort by string()

        /** Permission mode new chats start in (a `--permission-mode` value). */
        var permissionMode by string(DEFAULT_PERMISSION_MODE)

        /**
         * Project guides to give Claude instead of CLAUDE.md, e.g. "agent.md, spec.md", looked up from the project
         * folder up to the repository root (see [`in`.bleuphoenix.claudechat.cli.InstructionFiles]). Null uses CLAUDE.md as usual.
         */
        var instructionFiles by string()

        /** The user's own text added to Claude's system prompt in chats; null for none. */
        var systemPromptAppend by string()

        /** `--max-turns` for chats: the steps Claude takes for one message before stopping; 0 for no limit. */
        var maxTurns by property(0)

        /** Chat text size, in percent of the IDE's (see [`in`.bleuphoenix.claudechat.ui.compose.ChatAppearance]). */
        var chatFontPercent by property(100)

        /** Ask Claude to bold the key facts in replies and plans, for skimming. */
        var emphasisInReplies by property(true)

        /** Ask Claude to end plans with a to-do list, shown as a card in the Plan tab. */
        var todosInPlans by property(true)

        /** Ask Claude to include a Mermaid flowchart in plans. */
        var diagramsInPlans by property(true)

        /** Copy Cursor's commands to Claude Code and Antigravity when a chat starts (see [`in`.bleuphoenix.claudechat.cli.CursorCommands]). */
        var syncCursorCommands by property(false)

        /** [syncCursorCommands] as settings before version 2 stored it, on by default; only read to carry it over. */
        var cursorCommands by property(true)

        /** Which defaults the stored settings were written under (see [loadState]). */
        var settingsVersion by property(0)

        /** Give Claude its task-tracking tools, shown as a to-do list above the message box. */
        var taskList by property(true)

        /** Run shell commands that only read files inside the project without asking (see [`in`.bleuphoenix.claudechat.cli.ReadOnlyCommands]). */
        var autoApproveReads by property(true)

        /** Notify when Claude finishes a turn (see [`in`.bleuphoenix.claudechat.ui.ChatNotifier]). */
        var notifyFinished by property(true)

        /** Notify when Claude asks for permission, asks a question or has a plan ready. */
        var notifyNeedsYou by property(true)

        /** Notify only when the user isn't looking at that chat. */
        var notifyOnlyWhenAway by property(true)

        /** Play the system sound with each notification. */
        var notifySound by property(false)

        /** Sections of the activity strip the user folded (Tasks, Changed files…), folded in every chat. */
        var collapsedActivitySections by list<String>()

        /** The CLI's default tool set and the CLI version it was learned from (see [`in`.bleuphoenix.claudechat.cli.TaskTools]). */
        var builtInTools by string()
        var builtInToolsVersion by string()

        /** The CLI version the last session reported. */
        var lastCliVersion by string()
    }

    companion object {
        const val DEFAULT_PERMISSION_MODE = "manual"
        const val SETTINGS_VERSION = 2

        val options: Options get() = service<ClaudeChatSettings>().state
    }
}
