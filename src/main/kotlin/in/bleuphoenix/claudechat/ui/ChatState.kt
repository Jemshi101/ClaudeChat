package `in`.bleuphoenix.claudechat.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonObject
import `in`.bleuphoenix.claudechat.cli.ModelOption
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.history.SessionInfo
import `in`.bleuphoenix.claudechat.review.ReviewState
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import `in`.bleuphoenix.claudechat.ide.FileMatch

/** Everything the chat screen shows. Mutated on the EDT; Compose recomposes from it. */
class ChatState {
    val items = mutableStateListOf<ChatItem>()
    val input = TextFieldState()

    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var mode by mutableStateOf(PermissionMode.CHOICES.first())

    /** Incremented to ask the composer to take focus. */
    var focusRequests by mutableIntStateOf(0)

    /** Context for the next message, shown as chips above the message box. */
    val attachments = mutableStateListOf<Attachment>()

    /** The MCP servers this chat's Claude started with and how each is doing, from the session's `init` event. */
    var mcpServers by mutableStateOf<List<McpStatus>>(emptyList())

    /** Files or an image are being dragged over the chat; it shows where to drop them. */
    var dropping by mutableStateOf(false)

    /** The active editor's file, offered as context unless the user removed it ([activeFileIncluded]). */
    var activeFile by mutableStateOf<ActiveFile?>(null)
    var activeFileIncluded by mutableStateOf(true)

    /** File suggestions for the `@` mention being typed, or null when none is being typed. */
    var mention by mutableStateOf<MentionState?>(null)

    /** The `/` command being typed at the start of the message, or null when none is. */
    var slash by mutableStateOf<SlashState?>(null)

    /** The project's changes under review (shared with the Review tab); set by the chat controller. */
    var review: ReviewState? = null

    /** Files this chat's Claude edited (review keys), so the changes strip lists this chat's changes only. */
    val editedFiles = mutableStateListOf<String>()

    /** This chat's changes still waiting for review, in the order Claude first made them. */
    val changedFiles: List<`in`.bleuphoenix.claudechat.review.FileReview>
        get() = review?.pending?.filter { it.path in editedFiles }.orEmpty()

    /** Background work (shell commands and agents run in the background): running, or finished since the last message. */
    val background = mutableStateListOf<BackgroundTask>()

    /** The chat item to scroll to (e.g. a command picked in the activity strip), or null. */
    var scrollTo by mutableStateOf<String?>(null)

    /** This chat's terminal commands, the latest first, for the activity strip. */
    val shellCommands: List<ToolItem>
        get() = items.asReversed().filterIsInstance<ToolItem>().filter { it.name == "Bash" }.take(MAX_COMMANDS)

    /** Whether the activity strip above the message box is open. */
    var activityExpanded by mutableStateOf(false)

    /** The strip's folded sections, by title; the same in every chat and kept across restarts (see [toggleSection]). */
    val collapsedSections = mutableStateListOf<String>().apply {
        addAll(runCatching { ClaudeChatSettings.options.collapsedActivitySections }.getOrDefault(emptyList()))
    }

    /** Folds or unfolds the strip's [title] section, and remembers that for new chats. */
    fun toggleSection(title: String) {
        if (!collapsedSections.remove(title)) collapsedSections += title
        // No settings service in plain unit tests.
        runCatching { ClaudeChatSettings.options.collapsedActivitySections = collapsedSections.toMutableList() }
    }

    /** Slash commands and skills the CLI accepts, and the models it offers (see [`in`.bleuphoenix.claudechat.cli.CliCatalog]). */
    var commands by mutableStateOf<List<SlashCommand>>(emptyList())
    var models by mutableStateOf<List<ModelOption>>(emptyList())

    /** The chosen model (a `--model` value); null for the CLI default. */
    var model by mutableStateOf<String?>(null)

    /** The chosen effort level (an `--effort` value); null for the CLI default. */
    var effort by mutableStateOf<String?>(null)

    /** Messages sent while Claude was busy, sent in order when it finishes; removable until then. */
    val queued = mutableStateListOf<QueuedMessage>()

    /** Claude's to-do list (its task tools), shown above the message box while any item is open. */
    val tasks = mutableStateListOf<TaskEntry>()

    /** How full the context window is, from the latest reply; null until the first one. */
    var context by mutableStateOf<ContextUsage?>(null)

    /** The long-chat bar was closed; it comes back after the next compaction, once the chat is long again. */
    var longChatHintDismissed by mutableStateOf(false)

    /** What the mic button is doing. */
    var voice by mutableStateOf(VoicePhase.Idle)

    /** When the recording started, for its timer. */
    var voiceStartedAt by mutableStateOf(0L)

    /** Why voice input didn't work, or how to get it working; shown above the message box until closed. */
    var voiceMessage by mutableStateOf<String?>(null)

    /** Which project guides Claude gets instead of CLAUDE.md, for the welcome screen; empty when CLAUDE.md is used. */
    var instructionsNote by mutableStateOf("")

    /** The plugin's editor shortcuts as currently bound, for the welcome screen; empty when unbound. */
    var shortcutTips by mutableStateOf("")

    /** The most recent past chats, offered on the welcome screen. */
    var recent by mutableStateOf<List<SessionInfo>>(emptyList())

    /** The message being edited in the composer; sending replaces it and everything after it. */
    var editing by mutableStateOf<UserItem?>(null)

    /** The chat history screen while it's open. */
    var history by mutableStateOf<HistoryState?>(null)

    private var nextKey = 0
    fun newKey() = "item-${nextKey++}"
}

/** One entry in the conversation. [key] is stable, so the list can animate insertions. */
sealed class ChatItem(val key: String)

/** The chat history screen: past chats, a search box, and the chat being renamed (by session id), if any. */
class HistoryState(sessions: List<SessionInfo>) {
    var sessions by mutableStateOf(sessions)
    val query = TextFieldState()
    var renaming by mutableStateOf<String?>(null)
}

/** The `@` mention being typed: [query] starts after the `@` at [start] in the input. */
class MentionState(val query: String, val start: Int, val results: List<FileMatch>, val selected: Int = 0)

/**
 * The `/query` being typed and the commands matching it. [start] is the index of its `/`: 0 at the start of the
 * message, more when it's typed later on.
 */
class SlashState(val query: String, val results: List<SlashCommand>, val selected: Int = 0, val start: Int = 0)

/** A message waiting for Claude to finish the current turn. */
/** An MCP server's state in a chat: "connected", "failed", "needs-auth" or "pending", as the CLI reports it. */
data class McpStatus(val name: String, val status: String) {
    val connected: Boolean get() = status == "connected"
    val failed: Boolean get() = status == "failed"
    val needsAuth: Boolean get() = status == "needs-auth"

    companion object {
        /** The servers in `init`'s `mcp_servers` or `mcp_status`'s `mcpServers`. */
        fun list(servers: com.google.gson.JsonArray): List<McpStatus> = servers
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .mapNotNull { server ->
                val name = server.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                McpStatus(name, server.get("status")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
            }
    }
}

class QueuedMessage(val key: String, val text: String, val attachments: List<Attachment>)

/**
 * A message from the user. [forkPoint] is the last message before it in the CLI's transcript (null for the first),
 * so the conversation can be continued from just before it to edit or retry it.
 */
class UserItem(
    key: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val forkPoint: String? = null,
    /** Sent while Claude was working, for Claude to read at its next step (see ChatPanel.sendNow). */
    val sentWhileWorking: Boolean = false,
) : ChatItem(key) {
    /** Whether Claude changed files after this message, so "Restore checkpoint" would put something back. */
    var restorable by mutableStateOf(false)
}

class AssistantTextItem(key: String, text: String = "") : ChatItem(key) {
    var text by mutableStateOf(text)
}

class ToolItem(
    key: String,
    val name: String,
    val summary: String,
    val inputJson: String,
    val input: JsonObject?,
    /** A file the summary refers to, opened when the summary is clicked. */
    val filePath: String?,
    /** Whether a "Diff" action makes sense after the call ran. */
    val diffable: Boolean,
) : ChatItem(key) {
    var status by mutableStateOf(ToolStatus.Running)
    var result by mutableStateOf<String?>(null)

    /** Set for sub-agent calls (the Agent tool). */
    var agent by mutableStateOf<AgentProgress?>(null)

    /** Whether the card shows its input and output; the activity strip opens it when jumping to a command. */
    var expanded by mutableStateOf(false)

    /** When the call started and got its result, for the command list's durations. */
    val startedAt: Long = System.currentTimeMillis()
    var endedAt by mutableStateOf<Long?>(null)

    /** A shell command Claude ran in the background: its result came at once, the command runs on. */
    var background by mutableStateOf(false)
}

enum class ToolStatus { Running, Succeeded, Failed }

/** The mic button with local Whisper: off, listening, or turning the recording into text. */
enum class VoicePhase { Idle, Recording, Transcribing }

/** A sub-agent's progress, from the CLI's task events: what it's doing and how much it has done. */
data class AgentProgress(
    val type: String?,
    val activity: String? = null,
    val toolUses: Int = 0,
    val tokens: Long = 0,
    val done: Boolean = false,
)

/** One item of Claude's to-do list. [activeForm] is how it reads while in progress ("Reading the config"). */
class TaskEntry(val id: String, subject: String, activeForm: String?) {
    var subject by mutableStateOf(subject)
    var activeForm by mutableStateOf(activeForm)
    var status by mutableStateOf(TaskStatus.Pending)
}

/** How many recent commands the activity strip lists. */
const val MAX_COMMANDS = 8

enum class TaskStatus { Pending, InProgress, Completed }

enum class BackgroundStatus { Running, Completed, Failed, Stopped }

/**
 * A shell command or agent Claude runs in the background, from the CLI's task events (see docs/PROTOCOL.md).
 * [kind] is the CLI's task type, e.g. `local_bash`; [outputFile] is where a shell's output goes.
 */
class BackgroundTask(val id: String, val kind: String, val description: String, val startedAt: Long = System.currentTimeMillis()) {
    var status by mutableStateOf(BackgroundStatus.Running)
    var activity by mutableStateOf<String?>(null)
    var outputFile by mutableStateOf<String?>(null)
    var endedAt by mutableStateOf<Long?>(null)

    val isShell: Boolean get() = kind == "local_bash"
}

/** Tokens in the conversation so far, and the model's context window when known. */
data class ContextUsage(val used: Long, val window: Long?) {
    val fraction: Float? get() = window?.takeIf { it > 0 }?.let { (used.toDouble() / it).toFloat().coerceIn(0f, 1f) }

    /** Long enough that compacting or a new chat would help: [LONG_CHAT_TOKENS], or half a smaller window. */
    val isLong: Boolean get() = used >= minOf(LONG_CHAT_TOKENS, (window ?: Long.MAX_VALUE) / 2)

    companion object {
        const val LONG_CHAT_TOKENS = 200_000L
    }
}

class PermissionItem(
    key: String,
    val requestId: String,
    val toolName: String,
    val title: String,
    val detail: String,
    val reason: String?,
    val input: JsonObject?,
    val alwaysOptions: List<AlwaysAllowOption>,
    /** Whether "Review changes" can show a diff before the call runs. */
    val reviewable: Boolean,
) : ChatItem(key) {
    /** Null while waiting for the user; afterwards a short description of the answer. */
    var outcome by mutableStateOf<String?>(null)
}

/**
 * An "Allow always" choice built from one CLI permission suggestion.
 * [scope] says what it saves and where, so the user knows what they're agreeing to.
 */
class AlwaysAllowOption(val label: String, val scope: String, val suggestion: JsonObject)

/**
 * A plan Claude proposes when leaving plan mode (its ExitPlanMode call). The plan is a markdown file
 * ([planFilePath], written by the CLI) that opens in an editor tab; building sends the file's current text, so the
 * user's edits are what Claude implements.
 */
class PlanItem(
    key: String,
    val requestId: String,
    val title: String,
    val planText: String,
    val planFilePath: String?,
    val input: JsonObject,
) : ChatItem(key) {
    /** Null while waiting for the user; afterwards a short description of the decision. */
    var outcome by mutableStateOf<String?>(null)

    /** Whether the card shows the whole plan rather than its summary. */
    var expanded by mutableStateOf(false)

    /** The file the plan's tab shows, named after the plan; set once it's written. */
    var openedPath by mutableStateOf<String?>(null)

    /** Build was chosen for this plan. */
    var built by mutableStateOf(false)

    /** A newer plan came after this one in the chat; only the latest can be built. */
    var superseded by mutableStateOf(false)

    /** Build is offered: while Claude waits for a decision, and afterwards (as in Cursor) until it's built. */
    val buildable: Boolean get() = !built && !superseded
}

/**
 * The files Claude changed in one turn, as [paths]. Their pending hunks and counts come live from [review], so the
 * card updates as changes are accepted or rejected in the review tab.
 */
class ChangesItem(key: String, val paths: List<String>, val review: ReviewState) : ChatItem(key)

class NoticeItem(key: String, val text: String, val isError: Boolean) : ChatItem(key)

/**
 * The CLI compacted the conversation here: Claude now works from a summary of what came before. [automatic] when the
 * CLI did it on its own (near its auto-compact window, or while idle) rather than for a typed `/compact`; [after] is
 * null when the CLI didn't report it.
 */
class CompactedItem(key: String, val automatic: Boolean, val before: Long, val after: Long?) : ChatItem(key)

/**
 * The CLI isn't signed in (or the user typed /login): a card that signs it in with `claude auth login`. [retry] is
 * the message that failed for it, sent again once signed in.
 */
class LoginItem(key: String, val retry: UserItem?) : ChatItem(key) {
    var status by mutableStateOf(LoginStatus.Ready)

    /** The sign-in page, once the CLI has printed it. */
    var url by mutableStateOf<String?>(null)

    /** Why signing in failed, or who is signed in. */
    var detail by mutableStateOf<String?>(null)
}

enum class LoginStatus { Ready, Waiting, Failed, Done }

class TurnMetaItem(key: String, val text: String) : ChatItem(key)
