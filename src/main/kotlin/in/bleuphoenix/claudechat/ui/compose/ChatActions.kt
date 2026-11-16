package `in`.bleuphoenix.claudechat.ui.compose

import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.history.SessionInfo
import `in`.bleuphoenix.claudechat.ide.FileMatch
import `in`.bleuphoenix.claudechat.review.FileReview
import `in`.bleuphoenix.claudechat.ui.Attachment
import `in`.bleuphoenix.claudechat.ui.TextAttachment
import `in`.bleuphoenix.claudechat.ui.UserItem
import `in`.bleuphoenix.claudechat.ui.LoginItem
import `in`.bleuphoenix.claudechat.ui.AlwaysAllowOption
import `in`.bleuphoenix.claudechat.ui.PermissionItem
import `in`.bleuphoenix.claudechat.ui.QueuedMessage
import `in`.bleuphoenix.claudechat.ui.PermissionMode
import `in`.bleuphoenix.claudechat.ui.PlanItem
import `in`.bleuphoenix.claudechat.ui.BackgroundTask
import `in`.bleuphoenix.claudechat.ui.ToolItem
import java.awt.datatransfer.Transferable

/** What the chat screen can ask for. Implemented by the chat controller; called on the EDT. */
interface ChatActions {
    /** Sends the typed message; while Claude works, queues it for when Claude finishes. */
    fun send()
    fun stop()

    /** While Claude works: sends the typed message now, for Claude to read at its next step. */
    fun sendNow() = Unit

    /** While Claude works: stops it, then sends the typed message. */
    fun stopAndSend() = Unit
    fun selectMode(mode: PermissionMode)
    fun answer(item: PermissionItem, allowed: Boolean, always: AlwaysAllowOption?)
    fun review(item: PermissionItem)
    fun showDiff(item: ToolItem)
    fun openFile(path: String)
    fun openLink(url: String)

    /** Puts a suggested prompt into the input, ready to edit or send. */
    fun suggest(prompt: String)

    fun openPlan(item: PlanItem)

    /** Approves the plan as currently written in its file; [autoAcceptEdits] switches the chat to accept edits. */
    fun buildPlan(item: PlanItem, autoAcceptEdits: Boolean)

    /** Declines the plan for now; the user's next message is feedback on it. */
    fun revisePlan(item: PlanItem)

    /** Debug mode: the bug was reproduced; Claude reads the logs. Sends the typed message instead, if there is one. */
    fun proceedDebugging() = Unit

    /** Debug mode: the bug is fixed; Claude removes its logging, in Agent mode. */
    fun markFixed() = Unit

    fun copyText(text: String)

    /** Inserts code at the active editor's caret. */
    fun insertCode(code: String)

    /** Has Claude merge a reply's code block into its file (or the active editor's), for review in the editor. */
    fun applyCode(block: MarkdownSegment.Code) {}

    /** Puts [item] back in the message box; sending replaces it, and everything after it, in the conversation. */
    fun editMessage(item: UserItem)
    fun cancelEdit()

    /** Sends the last message again, replacing the reply it got. */
    fun retry(item: UserItem)

    fun removeAttachment(attachment: Attachment)

    /** Puts files Claude changed after [item] back as they were before it. */
    fun restoreCheckpoint(item: UserItem)

    /** Includes or leaves out the active editor's file as context. */
    fun includeActiveFile(included: Boolean)

    /** The `@` mention being typed changed; null when there is none. The controller looks up matching files. */
    fun mentionChanged(query: String?, start: Int)

    fun moveMentionSelection(delta: Int)

    /** Replaces the typed `@query` with a chip for [match]. */
    fun pickMention(match: FileMatch)

    /** The `/query` being typed changed; [start] is the index of its `/`. Null when there is none. */
    fun slashChanged(query: String?, start: Int = 0)

    fun moveSlashSelection(delta: Int)

    /** Replaces the typed `/query` with [command], ready for its arguments. */
    fun pickSlash(command: SlashCommand)

    /** Switches the chat's model ([value] as `--model` takes it); new chats start with it too. */
    fun selectModel(value: String)

    /** Sets how much Claude thinks (an `--effort` level; null for the CLI default), from the next reply. */
    fun selectEffort(level: String?) {}

    /** Takes a queued message out of the queue; [edit] puts it back in the message box. */
    fun unqueue(message: QueuedMessage, edit: Boolean)

    /** Sends a queued message now, without waiting for Claude to finish; [stopFirst] stops Claude first. */
    fun sendQueuedNow(message: QueuedMessage, stopFirst: Boolean) = Unit

    /** Handles a paste that carries images or files; false lets the text field paste text as usual. */
    fun pasteAttachments(): Boolean

    /** Puts a collapsed paste back into the message box (double-click on its chip). */
    fun expandAttachment(attachment: TextAttachment) {}

    /** Whether a drag over the chat carries files or an image it can attach. */
    fun canDrop(transferable: Transferable): Boolean = false

    /** Attaches the files and images dropped on the chat; false when there were none. */
    fun drop(transferable: Transferable): Boolean = false

    /** Opens Settings → Tools → Claude Chat → MCP Servers. */
    fun openMcpSettings() = Unit

    /** Signs in to an MCP server that needs it, in the browser; the chat connects to it afterwards. */
    fun signInToMcp(name: String) = Unit

    /** Tries connecting to an MCP server that failed again. */
    fun reconnectMcp(name: String) = Unit

    fun openHistory()
    fun closeHistory()

    /** Shows a past chat (here if this chat is empty, else in a new chat tab) and continues it. */
    fun openSession(session: SessionInfo)
    fun renameSession(session: SessionInfo, title: String)
    fun deleteSession(session: SessionInfo)

    /** Opens the "Claude Changes" tab. */
    fun openReview()

    /** Scrolls the chat to a command's card and opens it. */
    fun showCommand(item: ToolItem) = Unit

    /** Stops a background shell command or agent. */
    fun stopBackground(task: BackgroundTask) = Unit

    /** Opens a background shell command's output log in the editor. */
    fun openBackgroundOutput(task: BackgroundTask) = Unit

    /** Opens [file] in the editor at its first change, where the change can be accepted or rejected in place. */
    fun openChangedFile(file: FileReview) = Unit
    fun acceptChanges(files: List<FileReview>)
    fun rejectChanges(files: List<FileReview>)

    /** Signs the CLI in from [item]'s card: with a Claude subscription, or [console] for Anthropic Console billing. */
    fun signIn(item: LoginItem, console: Boolean) = Unit

    /** The code the browser showed after signing in, for the CLI. */
    fun sendLoginCode(item: LoginItem, code: String) = Unit
}
