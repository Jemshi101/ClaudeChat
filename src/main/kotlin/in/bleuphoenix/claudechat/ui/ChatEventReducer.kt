package `in`.bleuphoenix.claudechat.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import `in`.bleuphoenix.claudechat.cli.CliCatalog
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.cli.TaskTools
import `in`.bleuphoenix.claudechat.review.ReviewState

/**
 * Turns CLI stream-json events (see docs/PROTOCOL.md) into [ChatState] changes. Call on the EDT.
 *
 * [onTurnEnd] runs when a turn finishes; [onToolSucceeded] runs for each successful tool call with the CLI's
 * `tool_use_result` (for edits it includes the file's text before the change), so the caller can refresh and track
 * files the tool changed.
 */
class ChatEventReducer(
    private val state: ChatState,
    private val onToolSucceeded: (ToolItem, JsonObject?) -> Unit = { _, _ -> },
    private val onTurnEnd: (ranShellCommand: Boolean) -> Unit = {},
    /** A plan is waiting for the user's decision; the caller opens it in an editor tab. */
    private val onPlanReady: (PlanItem) -> Unit = {},
    /** The CLI reported its slash commands (they change as skills and plugins load). */
    private val onCommands: (List<SlashCommand>) -> Unit = {},
    /** Claude marked one of its tasks completed; the caller ticks the plan's matching to-do. */
    private val onTaskCompleted: (subject: String) -> Unit = {},
    /** Claude finished its turn or needs the user; the caller may notify them (see [ChatNotifier]). */
    private val onAlert: (ChatAlert) -> Unit = {},
) {

    // A turn runs from the user's message to its `result`. Events arriving outside a turn
    // (e.g. an interrupted tool's result) must not make the chat look busy again.
    private var turnActive = false
    private var stopRequested = false
    private var ranShellCommand = false

    /** The last conversation message the CLI reported (its transcript uuid): where a new message would fork from. */
    var lastMessageUuid: String? = null
        private set

    /** Continues after a replayed transcript whose last message was [uuid]. */
    fun continueAfter(uuid: String?) {
        lastMessageUuid = uuid
    }

    private var currentMessageId: String? = null
    private val streamedBlocks = HashMap<String, StreamedText>() // "<message id>:<block index>"
    private val streamedMessages = HashSet<String>()
    private val toolItems = HashMap<String, ToolItem>() // tool_use id

    // Task-tool calls waiting for their results (tool_use id to name and input); they update the to-do list.
    private val taskCalls = HashMap<String, Pair<String, JsonObject>>()

    // The session's main model, for picking its context window out of the usage report.
    private var model: String? = null

    private class StreamedText(val item: AssistantTextItem) {
        val text = StringBuilder()
    }

    fun addUserMessage(text: String, attachments: List<Attachment> = emptyList()) {
        // Finished background work has been seen by now; what's still running stays.
        state.background.removeAll { it.status != BackgroundStatus.Running }
        turnActive = true
        stopRequested = false
        state.items += UserItem(state.newKey(), text, attachments, forkPoint = lastMessageUuid)
        busy("Starting…")
    }

    /**
     * A message sent while Claude works: shown now, read by Claude at its next step (after a tool result) within the
     * turn. If the turn ends before another tool result, the CLI runs it as a turn of its own, so the chat stays busy
     * for that one (see [onResult]).
     */
    fun addMessageWhileWorking(text: String, attachments: List<Attachment> = emptyList()) {
        state.items += UserItem(state.newKey(), text, attachments, forkPoint = lastMessageUuid, sentWhileWorking = true)
        unreadMessages = true
    }

    // A message sent while Claude works, not yet read: no tool result has come since.
    private var unreadMessages = false

    fun requestStop() {
        stopRequested = true
        busy("Stopping…")
    }

    fun addNotice(text: String, isError: Boolean) {
        state.items += NoticeItem(state.newKey(), text, isError)
    }

    /** Ends the turn without a `result`, e.g. when the CLI process exits or a request fails. */
    fun endTurn() {
        // The streamed text is final now; keeping a second copy of every reply would double a long chat's memory.
        flush()
        streamedBlocks.clear()
        turnActive = false
        state.busy = false
        state.status = ""
    }

    /** Streamed text accumulates here and is copied into the state by [flush], so markdown isn't re-parsed per token. */
    fun flush() {
        streamedBlocks.values.forEach { block ->
            if (block.item.text.length != block.text.length) block.item.text = block.text.toString()
        }
    }

    fun handle(event: JsonObject) {
        // Sub-agent output is summarised by its Task tool call instead of shown inline.
        if (event.has("parent_tool_use_id") && !event.get("parent_tool_use_id").isJsonNull) return
        if (event.str("type") == "assistant" || event.str("type") == "user") event.str("uuid")?.let { lastMessageUuid = it }
        when (event.str("type")) {
            "system" -> onSystem(event)
            "stream_event" -> event.obj("event")?.let(::onStreamEvent)
            "assistant" -> event.str("error")?.let { onAssistantError(it, event.obj("message")) }
                ?: event.obj("message")?.let(::onAssistant)
            "user" -> event.obj("message")?.let { onToolResults(it, event.obj("tool_use_result")) }
            "control_request" -> event.obj("request")
                ?.takeIf { it.str("subtype") == "can_use_tool" }
                ?.let { onPermissionRequest(event.str("request_id") ?: return, it) }
            "result" -> onResult(event)
            "conversation_reset" -> onConversationReset()
        }
    }

    /** `/clear` started a new session with empty context: the view starts over too. */
    private fun onConversationReset() {
        flush()
        state.items.clear()
        state.tasks.clear()
        state.context = null
        streamedBlocks.clear()
        streamedMessages.clear()
        toolItems.clear()
        taskCalls.clear()
        lastMessageUuid = null
        addNotice("Started a new conversation. The previous one is in chat history.", isError = false)
    }

    private fun busy(label: String) {
        if (!turnActive) return
        state.busy = true
        state.status = label
    }

    private fun onSystem(event: JsonObject) {
        // `init` and `status` events report the current mode, including changes from "Allow always".
        event.str("permissionMode")?.let { state.mode = PermissionMode.reported(state.mode, it) }
        when (event.str("subtype")) {
            "init" -> {
                event.str("model")?.let(::onModel)
                event.getAsJsonArray("mcp_servers")?.let { state.mcpServers = McpStatus.list(it) }
                // A background task finished while Claude was idle: the CLI starts a turn of its own so Claude can
                // react to it.
                if (!turnActive && followUpExpected) {
                    followUpExpected = false
                    turnActive = true
                    stopRequested = false
                    busy("Looking at the finished background task…")
                }
            }
            "commands_changed" -> CliCatalog.commands(event.getAsJsonArray("commands")).let { commands ->
                state.commands = commands
                onCommands(commands)
            }
            "task_started", "task_progress", "task_updated", "task_notification" -> {
                onBackgroundEvent(event)
                onAgentEvent(event)
            }
            "background_tasks_changed" -> onBackgroundList(event)
            "status" -> if (event.str("status") == "requesting") busy("Thinking…")
            "permission_denied" -> addNotice(event.str("message") ?: "Permission denied", isError = true)
        }
    }

    /** The model each message runs on; follows a `/model` typed in the chat too. */
    private fun onModel(id: String) {
        model = id
        val chosen = state.models.firstOrNull { it.value == (state.model ?: CliCatalog.DEFAULT) }
        if (chosen?.resolvedModel == id || chosen?.value == id) return
        CliCatalog.optionFor(state.models, id)?.let { state.model = it.value }
    }

    private fun onStreamEvent(event: JsonObject) {
        when (event.str("type")) {
            "message_start" -> currentMessageId = event.obj("message")?.str("id")
            "content_block_start" -> {
                val block = event.obj("content_block") ?: return
                when (block.str("type")) {
                    "text" -> {
                        val item = AssistantTextItem(state.newKey())
                        streamedBlocks[blockKey(event)] = StreamedText(item)
                        currentMessageId?.let(streamedMessages::add)
                        state.items += item
                        busy("Writing…")
                    }
                    "tool_use" -> busy("Preparing ${block.str("name")}…")
                }
            }
            "content_block_delta" -> {
                val delta = event.obj("delta") ?: return
                if (delta.str("type") != "text_delta") return
                streamedBlocks[blockKey(event)]?.text?.append(delta.str("text").orEmpty())
            }
        }
    }

    private fun blockKey(event: JsonObject) = "$currentMessageId:${event.get("index")?.asInt}"

    /**
     * A message the CLI made up for a failed request ("Not logged in · Please run /login"), which the turn's result
     * repeats. Not being signed in gets a card that signs in; anything else shows as an error.
     */
    private fun onAssistantError(error: String, message: JsonObject?) {
        flush()
        val text = message?.getAsJsonArray("content")
            ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { b -> b.str("type") == "text" }?.str("text") }
            ?.joinToString("\n")
            .orEmpty()
        shownError = text
        if (error == AUTH_FAILED) {
            state.items += LoginItem(state.newKey(), retry = state.items.lastOrNull { it is UserItem } as UserItem?)
        } else {
            addNotice(text.ifEmpty { error }, isError = true)
        }
    }

    // The error a made-up message already showed this turn, so the result doesn't show it again.
    private var shownError: String? = null

    private fun onAssistant(message: JsonObject) {
        val messageId = message.str("id")
        message.obj("usage")?.let { usage ->
            val used = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens")
                .sumOf { usage.get(it)?.takeIf { v -> v.isJsonPrimitive }?.asLong ?: 0L }
            if (used > 0) state.context = ContextUsage(used, state.context?.window)
        }
        message.getAsJsonArray("content")?.forEach { element ->
            val block = element.asJsonObject
            when (block.str("type")) {
                "text" -> if (messageId !in streamedMessages) {
                    state.items += AssistantTextItem(state.newKey(), block.str("text").orEmpty())
                }
                // Task-tool calls only update the to-do list above the message box.
                "tool_use" -> if (block.str("name") in TASK_TOOLS) {
                    block.str("id")?.let { taskCalls[it] = block.str("name")!! to (block.obj("input") ?: JsonObject()) }
                    busy("Updating tasks…")
                } else if (block.str("name") != PLAN_TOOL && block.str("name") !in HIDDEN_TOOLS) {
                    // Shown as a plan card when its permission request arrives, not as a tool call.
                    flush()
                    val name = block.str("name") ?: "Tool"
                    val input = block.obj("input")
                    val item = ToolItem(
                        key = state.newKey(),
                        name = name,
                        summary = toolSummary(input),
                        inputJson = PRETTY.toJson(input),
                        input = input,
                        filePath = input?.str("file_path") ?: input?.str("notebook_path"),
                        diffable = name in DIFFABLE_AFTER && input != null,
                    )
                    block.str("id")?.let { toolItems[it] = item }
                    if (name in AGENT_TOOLS) item.agent = AgentProgress(type = input?.str("subagent_type"))
                    if (name == "Bash") ranShellCommand = true
                    state.items += item
                    busy("Running $name…")
                }
            }
        }
    }

    private fun onToolResults(message: JsonObject, toolUseResult: JsonObject?) {
        val content = message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        content.forEach { element ->
            val part = element.asJsonObject
            if (part.str("type") != "tool_result") return@forEach
            // Claude's next step, which reads any message sent while it worked.
            unreadMessages = false
            val isError = part.get("is_error")?.asBoolean == true
            taskCalls.remove(part.str("tool_use_id"))?.let { (name, input) ->
                if (!isError) updateTasks(name, input, toolUseResult)
                return@forEach
            }
            val item = toolItems[part.str("tool_use_id")] ?: return@forEach
            item.result = resultText(part.get("content")).ifEmpty { "(no output)" }
            item.endedAt = System.currentTimeMillis()
            // A background shell command: its result names the file its output goes to.
            toolUseResult?.str("backgroundTaskId")?.let { id ->
                item.background = true
                OUTPUT_FILE.find(item.result.orEmpty())?.let { match ->
                    state.background.firstOrNull { it.id == id }?.outputFile = match.groupValues[1].trimEnd('.')
                }
            }
            item.status = if (isError) ToolStatus.Failed else ToolStatus.Succeeded
            item.agent = item.agent?.copy(done = true)
            if (!isError) onToolSucceeded(item, toolUseResult)
        }
        busy("Thinking…")
    }

    /** Applies a task tool's call to the to-do list (see docs/PROTOCOL.md for the shapes). */
    private fun updateTasks(name: String, input: JsonObject, result: JsonObject?) {
        when (name) {
            "TaskCreate" -> {
                val id = result?.obj("task")?.str("id") ?: return
                if (state.tasks.none { it.id == id }) {
                    state.tasks += TaskEntry(id, input.str("subject").orEmpty(), input.str("activeForm"))
                }
            }
            "TaskUpdate" -> {
                val task = state.tasks.firstOrNull { it.id == input.str("taskId") } ?: return
                input.str("subject")?.let { task.subject = it }
                input.str("activeForm")?.let { task.activeForm = it }
                when (input.str("status")) {
                    "pending" -> task.status = TaskStatus.Pending
                    "in_progress" -> task.status = TaskStatus.InProgress
                    "completed" -> if (task.status != TaskStatus.Completed) {
                        task.status = TaskStatus.Completed
                        onTaskCompleted(task.subject)
                    }
                    "deleted" -> state.tasks.remove(task)
                }
            }
        }
    }

    /** Sub-agent progress: the CLI reports what the agent is doing and its usage so far. */
    private fun onAgentEvent(event: JsonObject) {
        val item = toolItems[event.str("tool_use_id")] ?: return
        // A background shell command reports through the same events; only agents show progress on their card.
        if (item.agent == null && item.name !in AGENT_TOOLS) return
        val progress = item.agent ?: AgentProgress(type = event.str("subagent_type"))
        val usage = event.obj("usage")
        item.agent = progress.copy(
            activity = event.str("description")?.takeIf { event.str("subtype") == "task_progress" } ?: progress.activity,
            toolUses = usage?.get("tool_uses")?.asInt ?: progress.toolUses,
            tokens = usage?.get("total_tokens")?.asLong ?: progress.tokens,
            done = progress.done || event.str("subtype") == "task_notification" ||
                event.obj("patch")?.str("status") == "completed",
        )
    }

    // A background task finished outside a turn, so the CLI's next init starts a turn of its own.
    private var followUpExpected = false

    /** Background shell commands and agents: started, progress, finished (see docs/PROTOCOL.md). */
    private fun onBackgroundEvent(event: JsonObject) {
        val id = event.str("task_id") ?: return
        val existing = state.background.firstOrNull { it.id == id }
        when (event.str("subtype")) {
            "task_started" -> if (existing == null && event.get("is_backgrounded")?.asBoolean == true) {
                state.background += BackgroundTask(id, event.str("task_type") ?: "", event.str("description").orEmpty())
            }
            "task_progress" -> existing?.activity = event.str("description")
            "task_updated" -> event.obj("patch")?.str("status")?.let { status -> existing?.finish(status) }
            "task_notification" -> {
                existing ?: return
                event.str("output_file")?.let { existing.outputFile = it }
                val status = event.str("status").orEmpty()
                existing.finish(status)
                val verb = when (existing.status) {
                    BackgroundStatus.Stopped -> "stopped"
                    BackgroundStatus.Failed -> "failed"
                    else -> "finished"
                }
                addNotice("Background ${if (existing.isShell) "command" else "task"} $verb: ${existing.description}", isError = existing.status == BackgroundStatus.Failed)
                if (!turnActive && existing.status != BackgroundStatus.Stopped) followUpExpected = true
            }
        }
    }

    /** The CLI's full list of running background tasks: adds any missed, and finishes the ones gone from it. */
    private fun onBackgroundList(event: JsonObject) {
        val running = event.get("tasks")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject } ?: return
        running.forEach { task ->
            val id = task.str("task_id") ?: return@forEach
            if (state.background.none { it.id == id }) {
                state.background += BackgroundTask(id, task.str("task_type") ?: "", task.str("description").orEmpty())
            }
        }
        val ids = running.mapNotNull { it.str("task_id") }.toSet()
        state.background.filter { it.status == BackgroundStatus.Running && it.id !in ids }.forEach { it.finish("completed") }
    }

    private fun BackgroundTask.finish(status: String) {
        if (this.status != BackgroundStatus.Running) return
        this.status = when (status) {
            "killed", "stopped" -> BackgroundStatus.Stopped
            "failed", "error" -> BackgroundStatus.Failed
            else -> BackgroundStatus.Completed
        }
        endedAt = System.currentTimeMillis()
    }

    private fun onPermissionRequest(requestId: String, request: JsonObject) {
        val toolName = request.str("tool_name") ?: "a tool"
        val input = request.obj("input")
        if (toolName == PLAN_TOOL && input != null) {
            onPlanRequest(requestId, input)
            return
        }
        val alwaysOptions = request.get("permission_suggestions")
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.let(::alwaysAllowOption) }
            .orEmpty()
        val item = PermissionItem(
            key = state.newKey(),
            requestId = requestId,
            toolName = toolName,
            title = "Allow Claude to use ${request.str("display_name") ?: toolName}?",
            detail = request.str("description") ?: toolSummary(input),
            // Why the CLI asked (e.g. no matching rule, path outside the project): explains repeat prompts.
            reason = request.str("decision_reason_type"),
            input = input,
            alwaysOptions = alwaysOptions,
            reviewable = toolName in REVIEWABLE_BEFORE && input != null,
        )
        state.items += item
        busy("Waiting for your approval")
        onAlert(ChatAlert.permission(item))
    }

    private fun onPlanRequest(requestId: String, input: JsonObject) {
        flush()
        val plan = input.str("plan").orEmpty()
        state.items.filterIsInstance<PlanItem>().forEach { it.superseded = true }
        val item = PlanItem(
            key = state.newKey(),
            requestId = requestId,
            title = planTitle(plan),
            planText = plan,
            planFilePath = input.str("planFilePath"),
            input = input,
        )
        state.items += item
        busy("Plan ready for review")
        onPlanReady(item)
        onAlert(ChatAlert.plan(item))
    }

    /** Records the user's decision on a plan; the caller sends it to the CLI. */
    fun answeredPlan(item: PlanItem, outcome: String, building: Boolean) {
        item.outcome = outcome
        busy(if (building) "Building…" else "Waiting for your feedback")
    }

    /** Records the user's answer on [item]; the caller sends it to the CLI. */
    fun answered(item: PermissionItem, allowed: Boolean, always: AlwaysAllowOption?) {
        item.outcome = when {
            always != null -> "Allowed · ${always.label}"
            allowed -> "Allowed"
            else -> "Denied"
        }
        busy(if (allowed) "Running ${item.toolName}…" else "Thinking…")
    }

    /** Adds a card listing the files Claude changed in the turn that just ended. */
    fun addChanges(paths: List<String>, review: ReviewState) {
        state.items += ChangesItem(state.newKey(), paths, review)
    }

    private fun onResult(result: JsonObject) {
        flush()
        endTurn()

        // Reaching the turn limit set in Settings is a pause, not a failure.
        val turnLimit = result.str("subtype") == "error_max_turns"
        val failed = !turnLimit && (result.get("is_error")?.asBoolean == true || result.str("subtype") != "success")
        if (turnLimit) {
            val turns = result.get("num_turns")?.takeIf { it.isJsonPrimitive }?.asString
            addNotice(
                "Claude stopped after ${turns?.let { "$it steps" } ?: "the most steps allowed"}, the turn limit in Settings. " +
                    "Send a message, such as \"continue\", to let it go on.",
                isError = false,
            )
        }
        val alert = when {
            failed && !stopRequested ->
                ChatAlert.failed(result.str("result") ?: shownError ?: "Turn ended: ${result.str("subtype")}")
            // A message sent during the turn that Claude didn't reach runs next: the work isn't finished.
            failed || unreadMessages -> null
            else -> ChatAlert.finished(
                state.items.lastOrNull { it is AssistantTextItem || it is UserItem }?.let { (it as? AssistantTextItem)?.text },
                result.get("duration_ms")?.asLong?.let { it / 1000.0 },
            )
        }
        when {
            // An interrupted turn ends with subtype error_during_execution.
            failed && stopRequested -> addNotice("Stopped.", isError = false)
            failed && shownError != null && result.str("result") in listOf(null, shownError) -> Unit
            failed -> addNotice(result.str("result") ?: "Turn ended: ${result.str("subtype")}", isError = true)
        }
        stopRequested = false
        shownError = null

        val shell = ranShellCommand
        ranShellCommand = false
        // A message sent while Claude worked that it didn't reach runs as the next turn: stay busy for it, before the
        // caller's turn-end handling (which would send the next queued message).
        if (unreadMessages && !failed) {
            unreadMessages = false
            turnActive = true
            busy("Reading your message…")
        }
        // Before the turn's timing line, so a changes card added by the caller sits above it.
        onTurnEnd(shell)
        alert?.let(onAlert)

        val parts = mutableListOf<String>()
        (result.get("duration_ms") ?: result.get("duration_api_ms"))?.asLong?.let { parts += "%.1fs".format(it / 1000.0) }
        result.get("total_cost_usd")?.asDouble?.let { parts += "$%.4f".format(it) }
        if (parts.isNotEmpty()) state.items += TurnMetaItem(state.newKey(), parts.joinToString(" · "))

        // The context window comes with the usage report; prefer the session's own model.
        result.obj("modelUsage")?.let { usage ->
            val windows = usage.entrySet().mapNotNull { (name, value) ->
                value.takeIf { it.isJsonObject }?.asJsonObject?.get("contextWindow")?.asLong?.let { name to it }
            }
            val window = windows.firstOrNull { it.first == model }?.second ?: windows.maxOfOrNull { it.second }
            if (window != null) state.context = ContextUsage(state.context?.used ?: 0, window)
        }
    }

    private companion object {
        val PRETTY = GsonBuilder().setPrettyPrinting().create()

        /** The tool Claude calls to present a plan and leave plan mode. */
        const val PLAN_TOOL = "ExitPlanMode"

        // The `error` of the message the CLI sends when it isn't signed in.
        const val AUTH_FAILED = "authentication_failed"

        /** "Output is being written to: <path>" in a background shell command's result. */
        private val OUTPUT_FILE = Regex("""Output is being written to: (\S+)""")

        val TASK_TOOLS = TaskTools.NAMES.toSet()

        /** Claude's own plumbing (loading deferred tools), not worth a card. */
        val HIDDEN_TOOLS = setOf("ToolSearch")

        /** Sub-agent tools: called Agent in current CLIs, Task in older ones. */
        val AGENT_TOOLS = setOf("Agent", "Task")

        /** The plan's first heading, or its first line. */
        fun planTitle(plan: String): String {
            val lines = plan.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val heading = lines.firstOrNull { it.startsWith("#") }?.trimStart('#')?.trim()
            return (heading ?: lines.firstOrNull() ?: "Plan").replace("`", "").take(120)
        }

        // Write isn't offered after the fact: once written, the file already equals the new content.
        val DIFFABLE_AFTER = setOf("Edit", "MultiEdit")
        val REVIEWABLE_BEFORE = setOf("Edit", "MultiEdit", "Write")

        fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
        fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        fun toolSummary(input: JsonObject?): String {
            if (input == null) return ""
            return listOf("command", "file_path", "notebook_path", "pattern", "url", "query", "description", "skill")
                .firstNotNullOfOrNull { input.str(it) }
                .orEmpty()
        }

        /**
         * Turns a CLI permission suggestion into a button whose label says where it applies (several can appear on
         * one prompt) and a one-line description of what it allows.
         */
        fun alwaysAllowOption(suggestion: JsonObject): AlwaysAllowOption? {
            val destination = suggestion.str("destination")
            return when (suggestion.str("type")) {
                "setMode" -> when (val mode = suggestion.str("mode")) {
                    "acceptEdits" -> AlwaysAllowOption("Allow all edits", "File edits in this chat no longer ask.", suggestion)
                    null -> null
                    else -> AlwaysAllowOption("Switch to ${PermissionMode.fromCli(mode).label}", "Changes this chat's mode.", suggestion)
                }
                "addRules" -> {
                    if (suggestion.str("behavior") != "allow") return null
                    val rules = suggestion.getAsJsonArray("rules")
                        ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                        ?.joinToString(", ") { rule ->
                            val tool = rule.str("toolName").orEmpty()
                            rule.str("ruleContent")?.let { "$tool($it)" } ?: tool
                        }
                        .orEmpty()
                    val (label, where) = when (destination) {
                        "localSettings" -> "Always allow in project" to "saved in .claude/settings.local.json"
                        "projectSettings" -> "Always allow for team" to "saved in .claude/settings.json, shared"
                        "userSettings" -> "Always allow everywhere" to "saved in ~/.claude/settings.json"
                        else -> "Allow for this chat" to "until this chat closes"
                    }
                    AlwaysAllowOption(label, "$rules · $where", suggestion)
                }
                else -> null
            }
        }

        fun resultText(content: JsonElement?): String = when {
            content == null || content.isJsonNull -> ""
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                .filter { it.str("type") == "text" }
                .joinToString("\n") { it.str("text").orEmpty() }
            else -> content.toString()
        }
    }
}
