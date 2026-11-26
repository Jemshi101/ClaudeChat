package `in`.bleuphoenix.claudechat.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import `in`.bleuphoenix.claudechat.review.ReviewState
import `in`.bleuphoenix.claudechat.ui.compose.compactedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEventReducerTest {

    private val state = ChatState()
    private val turnEnds = mutableListOf<Boolean>()
    private val succeeded = mutableListOf<String>()
    private val plansOpened = mutableListOf<PlanItem>()
    private val toolResults = mutableListOf<JsonObject?>()
    private val tasksCompleted = mutableListOf<String>()
    private val alerts = mutableListOf<ChatAlert>()
    private val reducer = ChatEventReducer(
        state,
        onToolSucceeded = { item, result ->
            succeeded += item.name
            toolResults += result
        },
        onTurnEnd = { turnEnds += it },
        onPlanReady = { plansOpened += it },
        onTaskCompleted = { tasksCompleted += it },
        onAlert = { alerts += it },
    )

    private fun event(json: String) = reducer.handle(JsonParser.parseString(json).asJsonObject)

    private fun toolUse(id: String, name: String, input: String = "{}") =
        event("""{"type":"assistant","message":{"id":"m-$id","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}""")

    private fun toolResult(id: String, isError: Boolean = false) =
        event("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":"out","is_error":$isError}]}}""")

    private fun result(subtype: String = "success") =
        event("""{"type":"result","subtype":"$subtype","duration_ms":1500,"total_cost_usd":0.01}""")

    @Test
    fun `streamed text accumulates and appears on flush`() {
        reducer.addUserMessage("hi")
        event("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m1"}}}""")
        event("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text"}}}""")
        event("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}""")
        event("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}""")
        val text = state.items.filterIsInstance<AssistantTextItem>().single()
        assertEquals("", text.text)
        reducer.flush()
        assertEquals("Hello", text.text)

        // The complete message repeats the streamed text; it must not be added twice.
        event("""{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Hello"}]}}""")
        assertEquals(1, state.items.filterIsInstance<AssistantTextItem>().size)
    }

    @Test
    fun `tool calls get results and statuses`() {
        reducer.addUserMessage("go")
        toolUse("t1", "Edit", """{"file_path":"A.kt","old_string":"a","new_string":"b"}""")
        toolUse("t2", "Bash", """{"command":"false"}""")
        toolResult("t1")
        toolResult("t2", isError = true)
        val (edit, bash) = state.items.filterIsInstance<ToolItem>()
        assertEquals(ToolStatus.Succeeded, edit.status)
        assertEquals("A.kt", edit.filePath)
        assertTrue(edit.diffable)
        assertEquals(ToolStatus.Failed, bash.status)
        assertEquals("false", bash.summary)
        assertEquals(listOf("Edit"), succeeded)

        result()
        assertEquals(listOf(true), turnEnds) // a shell command ran, so the project gets refreshed
        assertFalse(state.busy)
        assertEquals("1.5s · $0.0100", (state.items.last() as TurnMetaItem).text)
    }

    @Test
    fun `events after the turn ended don't make the chat busy again`() {
        reducer.addUserMessage("go")
        toolUse("t1", "Bash")
        reducer.requestStop()
        result("error_during_execution")
        assertFalse(state.busy)
        assertEquals("Stopped.", state.items.filterIsInstance<NoticeItem>().single().text)

        toolResult("t1", isError = true) // the interrupted tool's result arrives late
        assertFalse(state.busy)
    }

    @Test
    fun `failed turn without a stop shows the error`() {
        reducer.addUserMessage("go")
        event("""{"type":"result","subtype":"error_max_budget_usd","is_error":true,"result":"Reached the budget"}""")
        val notice = state.items.filterIsInstance<NoticeItem>().single()
        assertTrue(notice.isError)
        assertEquals("Reached the budget", notice.text)
    }

    @Test
    fun `reaching the turn limit is a pause, not an error`() {
        reducer.addUserMessage("go")
        event("""{"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":11,"duration_ms":900}""")
        val notice = state.items.filterIsInstance<NoticeItem>().single()
        assertFalse(notice.isError)
        assertTrue(notice.text, notice.text.startsWith("Claude stopped after 11 steps, the turn limit in Settings."))
        assertFalse(state.busy)
    }

    @Test
    fun `alerts when Claude finishes, fails or needs permission, not when stopped`() {
        reducer.addUserMessage("go")
        event("""{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"All tests pass now."}]}}""")
        event("""{"type":"result","subtype":"success","duration_ms":42000}""")
        assertEquals(ChatAlert.finished("All tests pass now.", 42.0), alerts.removeLast())

        reducer.addUserMessage("again")
        event("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"rm -rf build"}}}""")
        assertEquals("rm -rf build", alerts.removeLast().also { assertEquals(ChatAlert.Kind.NEEDS_YOU, it.kind) }.body)
        event("""{"type":"result","subtype":"error_during_execution","is_error":true,"result":"API error"}""")
        assertEquals(ChatAlert.failed("API error"), alerts.removeLast())

        reducer.addUserMessage("stop me")
        reducer.requestStop()
        event("""{"type":"result","subtype":"error_during_execution","is_error":true}""")
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `init reports the MCP servers`() {
        event("""{"type":"system","subtype":"init","mcp_servers":[{"name":"tracker","status":"connected","source":"user"},{"name":"files","status":"failed"}]}""")
        assertEquals(listOf(McpStatus("tracker", "connected"), McpStatus("files", "failed")), state.mcpServers)
    }

    @Test
    fun `permission request with suggestions`() {
        reducer.addUserMessage("go")
        event(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash",
            "input":{"command":"mkdir -p out"},"description":"Make dir","decision_reason_type":"rule",
            "permission_suggestions":[{"type":"addRules","behavior":"allow","destination":"localSettings",
            "rules":[{"toolName":"Bash","ruleContent":"mkdir -p out"}]}]}}""",
        )
        val item = state.items.filterIsInstance<PermissionItem>().single()
        assertEquals("Allow Claude to use Bash?", item.title)
        assertEquals("Make dir", item.detail)
        assertEquals("rule", item.reason)
        assertFalse(item.reviewable)
        val option = item.alwaysOptions.single()
        assertEquals("Always allow in project", option.label)
        assertTrue(option.scope, option.scope.contains("Bash(mkdir -p out)") && option.scope.contains("settings.local.json"))
        assertEquals("Waiting for your approval", state.status)

        reducer.answered(item, allowed = true, always = option)
        assertEquals("Allowed · Always allow in project", item.outcome)
        assertEquals("Running Bash…", state.status)
    }

    @Test
    fun `mode changes reported by the CLI update the selector`() {
        event("""{"type":"system","subtype":"status","status":null,"permissionMode":"acceptEdits"}""")
        assertEquals("acceptEdits", state.mode.id)
        event("""{"type":"system","subtype":"status","status":null,"permissionMode":"default"}""")
        assertEquals("manual", state.mode.id)
    }

    @Test
    fun `exit plan mode becomes a plan card that opens the plan file`() {
        reducer.addUserMessage("plan it")
        // Real events captured from the CLI in plan mode.
        javaClass.getResource("/protocol/exit-plan-mode.ndjson")!!.readText().lines().filter { it.isNotBlank() }.forEach(::event)

        assertTrue("no raw ExitPlanMode tool card", state.items.none { it is ToolItem })
        val plan = state.items.filterIsInstance<PlanItem>().single()
        assertEquals("Add --name option to Main.kt", plan.title)
        assertTrue(plan.planFilePath!!.endsWith(".md"))
        assertTrue(plan.planText.contains("## Verification"))
        assertEquals(listOf(plan), plansOpened)
        assertEquals("Plan ready for review", state.status)

        reducer.answeredPlan(plan, "Building", building = true)
        assertEquals("Building", plan.outcome)
        assertEquals("Building…", state.status)
    }

    @Test
    fun `edit results carry the file's text before the change`() {
        reducer.addUserMessage("In Main.kt change hello to hi, and val x = 1 to val x = 2")
        // Captured from the real CLI (acceptEdits mode, Edit and Read only).
        javaClass.getResource("/protocol/edit-with-original.ndjson")!!.readText().lines().filter { it.isNotBlank() }
            .forEach { reducer.handle(JsonParser.parseString(it).asJsonObject) }

        assertEquals(listOf("Read", "Edit", "Edit"), succeeded)
        // The next message would fork from the last event of this turn.
        assertEquals("63a2de95-21fc-4af3-b3be-924057c0cd1d", reducer.lastMessageUuid)
        val originals = toolResults.drop(1).map { it?.get("originalFile")?.asString }
        assertEquals("fun main() {\n    println(\"hello\")\n    val x = 1\n    println(x)\n}\n", originals[0])
        assertEquals("fun main() {\n    println(\"hi\")\n    val x = 1\n    println(x)\n}\n", originals[1])
    }

    @Test
    fun `a changes card added at turn end sits above the timing line`() {
        val review = ReviewState()
        lateinit var withChanges: ChatEventReducer
        withChanges = ChatEventReducer(state, onTurnEnd = { withChanges.addChanges(listOf("/work/A.kt"), review) })
        withChanges.addUserMessage("edit")
        withChanges.handle(JsonParser.parseString("""{"type":"result","subtype":"success","duration_ms":1500}""").asJsonObject)
        assertTrue(state.items[1] is ChangesItem)
        assertTrue(state.items[2] is TurnMetaItem)
    }

    @Test
    fun `not being signed in shows a sign-in card for the message, not the error twice`() {
        reducer.addUserMessage("hola")
        replayFixture("not-logged-in.ndjson")
        val login = state.items.filterIsInstance<LoginItem>().single()
        assertSame(state.items.first(), login.retry)
        assertTrue(state.items.none { it is NoticeItem || it is AssistantTextItem })
        assertFalse(state.busy)
    }

    @Test
    fun `another made-up error shows once`() {
        reducer.addUserMessage("hi")
        event("""{"type":"assistant","error":"rate_limit","message":{"id":"m","model":"<synthetic>","content":[{"type":"text","text":"Limit reached"}]}}""")
        event("""{"type":"result","subtype":"success","is_error":true,"result":"Limit reached","duration_ms":0}""")
        assertEquals(listOf("Limit reached"), state.items.filterIsInstance<NoticeItem>().map { it.text })
        assertTrue(state.items.none { it is AssistantTextItem })
    }

    @Test
    fun `only the latest plan can be built`() {
        reducer.addUserMessage("plan it")
        replayFixture("exit-plan-mode.ndjson")
        val first = state.items.filterIsInstance<PlanItem>().single()
        assertTrue(first.buildable)
        replayFixture("exit-plan-mode.ndjson")
        val (older, latest) = state.items.filterIsInstance<PlanItem>()
        assertFalse(older.buildable)
        assertTrue(latest.buildable)
    }

    @Test
    fun `Ask mode stays Ask when the CLI reports the mode it runs in`() {
        state.mode = PermissionMode.CHOICES.first { it.id == "ask" }
        event("""{"type":"system","subtype":"status","permissionMode":"default"}""")
        assertEquals("ask", state.mode.id)
        event("""{"type":"system","subtype":"status","permissionMode":"plan"}""")
        assertEquals("plan", state.mode.id)
        event("""{"type":"system","subtype":"status","permissionMode":"default"}""")
        assertEquals("manual", state.mode.id)
    }

    private fun replayFixture(name: String) =
        javaClass.getResource("/protocol/$name")!!.readText().lines().filter { it.isNotBlank() }
            .forEach { reducer.handle(JsonParser.parseString(it).asJsonObject) }

    @Test
    fun `a message sent while Claude works that it didn't reach keeps the chat busy for its own turn`() {
        reducer.addUserMessage("first")
        reducer.addMessageWhileWorking("a note")
        assertTrue(state.items.filterIsInstance<UserItem>().last().sentWhileWorking)
        event("""{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"done"}]}}""")
        event("""{"type":"result","subtype":"success","is_error":false,"duration_ms":5}""")
        assertTrue("the CLI runs the note next", state.busy)
        event("""{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"noted"}]}}""")
        event("""{"type":"result","subtype":"success","is_error":false,"duration_ms":5}""")
        assertFalse(state.busy)
    }

    @Test
    fun `a message Claude read at its next step doesn't wait for another turn`() {
        reducer.addUserMessage("first")
        toolUse("t1", "Read", """{"file_path":"a.kt"}""")
        reducer.addMessageWhileWorking("a note")
        event("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"x"}]}}""")
        event("""{"type":"result","subtype":"success","is_error":false,"duration_ms":5}""")
        assertFalse(state.busy)
    }

    @Test
    fun `a background shell command shows as running, finishes, and Claude's follow-up turn shows as busy`() {
        reducer.addUserMessage("Run sleep in the background")
        val lines = javaClass.getResource("/protocol/background-bash.ndjson")!!.readText().lines().filter { it.isNotBlank() }
        // Up to the first turn's result: the command runs in the background, its card isn't an agent's.
        val firstResult = lines.indexOfFirst { it.contains("\"type\":\"result\"") }
        lines.take(firstResult + 1).forEach(::event)
        val task = state.background.single()
        assertEquals(BackgroundStatus.Running, task.status)
        assertTrue(task.isShell)
        assertEquals("Sleep 4 seconds then echo bg-done", task.description)
        assertTrue(task.outputFile!!.endsWith("/tasks/${task.id}.output"))
        val bash = state.items.filterIsInstance<ToolItem>().first { it.name == "Bash" }
        assertEquals(null, bash.agent)
        assertTrue("listed as a background command", bash.background && bash.endedAt != null)
        assertEquals(listOf(bash), state.shellCommands)
        assertFalse(state.busy)

        // It finishes while Claude is idle: a notice, then the CLI's own follow-up turn keeps the chat busy.
        lines.drop(firstResult + 1).takeWhile { !it.contains("\"subtype\":\"init\"") }.forEach(::event)
        assertEquals(BackgroundStatus.Completed, task.status)
        assertTrue(state.items.filterIsInstance<NoticeItem>().any { it.text == "Background command finished: Sleep 4 seconds then echo bg-done" })
        val rest = lines.drop(firstResult + 1).dropWhile { !it.contains("\"subtype\":\"init\"") }
        event(rest.first())
        assertTrue("the follow-up turn", state.busy)
        rest.drop(1).forEach(::event)
        assertFalse(state.busy)
        assertTrue(state.items.filterIsInstance<AssistantTextItem>().last().text.contains("bg-done"))

        // The next message clears finished tasks.
        reducer.addUserMessage("thanks")
        assertTrue(state.background.isEmpty())
    }

    @Test
    fun `a stopped background command shows as stopped and starts no follow-up turn`() {
        reducer.addUserMessage("Run sleep in the background")
        replayFixture("background-stop.ndjson")
        assertEquals(BackgroundStatus.Stopped, state.background.single().status)
        assertTrue(state.items.filterIsInstance<NoticeItem>().any { it.text.startsWith("Background command stopped") })
        assertFalse(state.busy)
    }

    @Test
    fun `task tool calls build the to-do list instead of tool cards`() {
        reducer.addUserMessage("Create a 2-item task list")
        // Captured from the real CLI with --tools TaskCreate,TaskUpdate,TaskList,TaskGet.
        replayFixture("task-tools.ndjson")

        assertEquals(listOf("1" to "Check A.kt", "2" to "Check B.kt"), state.tasks.map { it.id to it.subject })
        assertEquals(listOf(TaskStatus.Completed, TaskStatus.InProgress), state.tasks.map { it.status })
        assertTrue(state.items.none { it is ToolItem })
        assertEquals("completed tasks are reported once, to tick the plan's to-dos", listOf("Check A.kt"), tasksCompleted)
    }

    @Test
    fun `sub-agent calls show the agent's progress and the context meter fills`() {
        reducer.addUserMessage("Use an Explore agent")
        // Captured from the real CLI: an Explore agent reading two files.
        replayFixture("sub-agent.ndjson")

        val agent = state.items.filterIsInstance<ToolItem>().single()
        assertEquals("Agent", agent.name)
        val progress = agent.agent!!
        assertEquals("Explore", progress.type)
        assertEquals(3, progress.toolUses)
        assertTrue(progress.done)
        assertTrue(progress.tokens > 10_000)

        val context = state.context!!
        assertEquals(1_000_000L, context.window)
        assertTrue(context.used > 10_000)
    }

    @Test
    fun `sub-agent output is not shown inline`() {
        reducer.addUserMessage("go")
        event("""{"type":"assistant","parent_tool_use_id":"task1","message":{"id":"x","content":[{"type":"text","text":"inner"}]}}""")
        assertTrue(state.items.none { it is AssistantTextItem })
    }

    @Test
    fun `the CLI's commands and a model chosen with slash model reach the composer`() {
        val lines = javaClass.getResource("/protocol/initialize.ndjson")!!.readText().lines().filter { it.isNotBlank() }
        val response = `in`.bleuphoenix.claudechat.cli.CliCatalog.parseInitialize(lines[0])!!
        state.models = `in`.bleuphoenix.claudechat.cli.CliCatalog.models(response)
        event(lines[1])
        assertTrue(state.commands.any { it.name == "compact" })

        event("""{"type":"system","subtype":"init","model":"claude-opus-5-5","session_id":"s"}""")
        assertEquals(null, state.model) // the default is Opus: nothing changed
        event("""{"type":"system","subtype":"init","model":"claude-haiku-5-5","session_id":"s"}""")
        assertEquals("haiku", state.model)
    }

    @Test
    fun `slash clear starts the view over`() {
        reducer.addUserMessage("hello")
        event("""{"type":"assistant","uuid":"u1","message":{"id":"m1","content":[{"type":"text","text":"Hi"}]}}""")
        result()
        reducer.addUserMessage("/clear")
        event("""{"type":"conversation_reset","session_id":"old"}""")
        result()
        assertEquals(null, reducer.lastMessageUuid)
        assertTrue(state.items.first() is NoticeItem)
        assertTrue(state.items.none { it is UserItem || it is AssistantTextItem })
    }

    @Test
    fun `compaction shows as a divider and shrinks the context`() {
        state.context = ContextUsage(412_000, 1_000_000)
        state.longChatHintDismissed = true
        reducer.addUserMessage("go")
        event("""{"type":"system","subtype":"status","status":"compacting"}""")
        assertEquals("Compacting the conversation…", state.status)
        event("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":412000,"post_tokens":38000,"duration_ms":51000}}""")
        val divider = state.items.filterIsInstance<CompactedItem>().single()
        assertTrue(divider.automatic)
        assertEquals("Context compacted automatically · 412k → 38k tokens", compactedLabel(divider))
        assertEquals(ContextUsage(38_000, 1_000_000), state.context)
        assertFalse(state.longChatHintDismissed)
    }

    @Test
    fun `a typed compact is labelled as such, and a failure shows why`() {
        reducer.addUserMessage("/compact")
        event("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":90500}}""")
        assertEquals("Context compacted · was 90.5k tokens", compactedLabel(state.items.filterIsInstance<CompactedItem>().single()))
        event("""{"type":"system","subtype":"status","status":null,"compact_result":"failed","compact_error":"Not enough messages to compact."}""")
        val notice = state.items.filterIsInstance<NoticeItem>().single()
        assertTrue(notice.isError)
        assertEquals("Couldn't compact the conversation: Not enough messages to compact.", notice.text)
    }

    @Test
    fun `a chat is long from 200k tokens, or half a smaller window`() {
        assertFalse(ContextUsage(199_000, 1_000_000).isLong)
        assertTrue(ContextUsage(200_000, 1_000_000).isLong)
        assertTrue(ContextUsage(100_000, 200_000).isLong)
        assertFalse(ContextUsage(99_000, 200_000).isLong)
        assertTrue(ContextUsage(250_000, null).isLong)
    }
}
