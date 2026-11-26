package `in`.bleuphoenix.claudechat.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.google.gson.JsonParser
import `in`.bleuphoenix.claudechat.cli.CliCatalog
import `in`.bleuphoenix.claudechat.cli.Effort
import `in`.bleuphoenix.claudechat.cli.ModelOption
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.history.SessionInfo
import `in`.bleuphoenix.claudechat.ide.FileMatch
import `in`.bleuphoenix.claudechat.review.FileReview
import `in`.bleuphoenix.claudechat.review.Hunk
import `in`.bleuphoenix.claudechat.review.ReviewActions
import `in`.bleuphoenix.claudechat.review.ReviewState
import `in`.bleuphoenix.claudechat.ui.compose.ChatActions
import `in`.bleuphoenix.claudechat.ui.compose.ChatScreen
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File

/**
 * Renders the chat offscreen with Jewel's standalone theme and writes PNGs to build/renders, for reviewing the
 * design without running the IDE. Fails only if rendering throws.
 */
class ChatScreenRenderTest {

    private val outDir = File("build/renders").apply { mkdirs() }

    @Test
    fun `conversation light and dark`() {
        render("conversation-light", sampleConversation(), dark = false)
        render("conversation-dark", sampleConversation(), dark = true)
    }

    @Test
    fun `slash commands, queued messages and the model picker`() {
        val commands = listOf(
            SlashCommand("compact", "Free up context by summarizing the conversation so far", "<optional custom summarization instructions>"),
            SlashCommand("context", "Show current context usage", ""),
            SlashCommand("code-review", "Review the current diff for correctness bugs", "[level]"),
        )
        val models = listOf(
            ModelOption(CliCatalog.DEFAULT, "Default (recommended)", "Opus 5.5 · Best for everyday, complex tasks", "claude-opus-5-5"),
            ModelOption("opus", "Opus 5.5", "For complex work and everyday tasks", "claude-opus-5-5"),
            ModelOption("sonnet", "Sonnet 5.5", "Most efficient for simpler tasks", "claude-sonnet-5-5", Effort.LEVELS),
        )
        val state = sampleConversation().apply {
            this.models = models
            model = "sonnet"
            effort = "xhigh"
            this.commands = commands
            input.edit { append("/co") }
            slash = SlashState("co", CliCatalog.match(commands, "co"), selected = 1)
        }
        render("slash-dark", state, dark = true, heightDp = 640)

        // Commands and file mentions are colored in the message box and in sent messages.
        val tokens = sampleConversation().apply {
            this.commands = commands
            ChatEventReducer(this).addUserMessage("Tidy @src/Main.kt#L3-4 without changing the flow /code-review")
            input.edit { append("Check /tmp/out.txt and @README.md, then /compact") }
        }
        render("tokens-dark", tokens, dark = true, heightDp = 640)
        render("tokens-light", tokens, dark = false, heightDp = 640)

        val busy = sampleConversation().apply {
            this.models = models
            busy = true
            status = "Running Bash…"
            queued += QueuedMessage(newKey(), "Then run the unit tests", emptyList())
            queued += QueuedMessage(newKey(), "And update the README with the new option", listOf(FileAttachment(newKey(), "README.md")))
            input.edit { append("Also check the Kotlin tests") }
        }
        render("queued-dark", busy, dark = true, heightDp = 640)
        render("queued-light", busy, dark = false, heightDp = 640)
    }

    @Test
    fun `compaction divider and the long-chat bar`() {
        val state = sampleConversation()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("go")
        reducer.handle(JsonParser.parseString("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":412000,"post_tokens":38000}}""").asJsonObject)
        reducer.endTurn()
        state.context = ContextUsage(236_000, 1_000_000)
        render("compacted-dark", state, dark = true, heightDp = 640)
        render("compacted-light", state, dark = false, heightDp = 640)
    }

    @Test
    fun `sign-in card`() {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("hola")
        val ready = LoginItem(state.newKey(), retry = state.items.last() as UserItem)
        state.items += ready
        val waiting = LoginItem(state.newKey(), retry = null).apply {
            status = LoginStatus.Waiting
            url = "https://claude.com/cai/oauth/authorize"
        }
        state.items += waiting
        state.items += LoginItem(state.newKey(), retry = null).apply {
            status = LoginStatus.Failed
            detail = "Login failed: invalid code"
        }
        state.items += LoginItem(state.newKey(), retry = null).apply {
            status = LoginStatus.Done
            detail = "Signed in as dev@example.com."
        }
        reducer.endTurn()
        render("login-dark", state, dark = true, heightDp = 900)
        render("login-light", state, dark = false, heightDp = 900)
    }

    @Test
    fun `each mode in its color`() {
        PermissionMode.CHOICES.forEach { choice ->
            listOf(true, false).forEach { dark ->
                render("mode-${choice.id}-${if (dark) "dark" else "light"}", ChatState().apply { mode = choice }, dark = dark, heightDp = 300)
            }
        }
    }

    @Test
    fun `debug mode reproduction steps`() {
        val state = ChatState().apply { mode = PermissionMode.CHOICES.first { it.id == DebugMode.ID } }
        ChatEventReducer(state).addUserMessage("The clock resets to 00:00 after rotating the Set Clock screen")
        state.items += AssistantTextItem(
            "a1",
            "Three hypotheses:\n\n1. The ViewModel is recreated on rotation.\n2. The time is read before the panel answers.\n" +
                "3. The saved state is overwritten.\n\nI added `ClaudeDebug` logging for each.\n\n" +
                "<reproduction_steps>\n1. Open **Set Clock** on the panel.\n2. Change the time, then rotate the phone.\n" +
                "3. Check whether the time resets.\n</reproduction_steps>",
        )
        state.busy = false
        listOf(true, false).forEach { dark ->
            render("debug-steps-${if (dark) "dark" else "light"}", state, dark = dark, heightDp = 640)
        }
    }

    @Test
    fun `empty state`() {
        val state = ChatState().apply {
            shortcutTips = "⌘⇧J focuses this box · ⌥⌘K edits selected code in place"
            mode = PermissionMode.CHOICES.first { it.id == "plan" }
        }
        render("empty-dark", state, dark = true, heightDp = 560)
    }

    @Test
    fun `pending permission`() {
        render("permission-dark", samplePermission(), dark = true, heightDp = 560)
    }

    @Test
    fun `plan card`() {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Plan how to add a --name option to Main.kt")
        javaClass.getResource("/protocol/exit-plan-mode.ndjson")!!.readText().lines().filter { it.isNotBlank() }
            .forEach { reducer.handle(json(it)) }
        render("plan-dark", state, dark = true, heightDp = 640)
        render("plan-light", state, dark = false, heightDp = 640)
        state.items.filterIsInstance<PlanItem>().single().expanded = true
        render("plan-expanded-dark", state, dark = true, heightDp = 900)
        state.items.filterIsInstance<PlanItem>().single().apply {
            expanded = false
            reducer.answeredPlan(this, "Keep planning · send your feedback below", building = false)
        }
        reducer.endTurn()
        render("plan-kept-dark", state, dark = true, heightDp = 640)
    }

    @Test
    fun `plan tab after keep planning`() {
        val plan = javaClass.getResource("/samples/plan-with-diagram.md")!!.readText()
        renderContent("plan-document-buildable-dark", true, widthDp = 760, heightDp = 400) {
            `in`.bleuphoenix.claudechat.ui.compose.PlanDocumentView(plan, `in`.bleuphoenix.claudechat.plan.PlanService.PendingPlan(build = {}, revise = null), onUrlClick = {})
        }
    }

    @Test
    fun `plan action bar at wide and narrow widths`() {
        val pending = `in`.bleuphoenix.claudechat.plan.PlanService.PendingPlan(build = {}, revise = {})
        listOf(900, 480).forEach { width ->
            renderContent("plan-bar-$width-dark", true, widthDp = width, heightDp = 140) {
                `in`.bleuphoenix.claudechat.ui.compose.PlanDocumentView("## Plan\n\nSteps.", pending, onUrlClick = {})
            }
        }
    }

    @Test
    fun `plan to-dos`() {
        val plan = "## Fix\n\nAdd `tools:node=\"remove\"` to the manifest.\n\n## To-dos\n\n" +
            "- [ ] Add tools:node=remove for FOREGROUND_SERVICE and FOREGROUND_SERVICE_LOCATION in the HAP main `AndroidManifest.xml`\n" +
            "- [x] Rebuild demoDebug and confirm the merged manifest has no FOREGROUND_SERVICE* permissions\n" +
            "- [ ] Run QA on build A (master) and build B (master plus manifest change only)\n"
        listOf(false, true).forEach { dark ->
            renderContent("todos-${if (dark) "dark" else "light"}", dark, widthDp = 760, heightDp = 520) {
                `in`.bleuphoenix.claudechat.ui.compose.PlanDocumentView(plan, pending = null, onUrlClick = {}, onEdit = {})
            }
        }
    }

    @Test
    fun `changes strip above the message box`() {
        val review = sampleReview()
        val state = sampleConversation().apply {
            this.review = review
            editedFiles += review.files.map { it.path }
            busy = true
            status = "Running Bash…"
        }
        state.background += BackgroundTask("b1", "local_bash", "./gradlew :app:assembleDemoDebug").apply { outputFile = "/tmp/b1.output" }
        state.background += BackgroundTask("a1", "local_agent", "Explore the site users plugin").apply { activity = "Reading add_user_viewmodel.dart" }
        state.background += BackgroundTask("b2", "local_bash", "adb logcat | grep SecurityException").apply { status = BackgroundStatus.Stopped; endedAt = startedAt + 42_000 }
        state.tasks += TaskEntry("1", "Add the manifest change", null).apply { status = TaskStatus.Completed }
        state.tasks += TaskEntry("2", "Rebuild demoDebug", "Rebuilding demoDebug").apply { status = TaskStatus.InProgress }
        state.tasks += TaskEntry("3", "Run the site users checks", null)
        render("strip-collapsed-dark", state, dark = true, heightDp = 560)
        state.activityExpanded = true
        render("strip-open-dark", state, dark = true, heightDp = 900)
        render("strip-open-light", state, dark = false, heightDp = 900)
    }

    @Test
    fun `chat tabs, each with a close button`() {
        listOf(false, true).forEach { dark ->
            renderContent("tabs-${if (dark) "dark" else "light"}", dark, widthDp = 440, heightDp = 60) {
                `in`.bleuphoenix.claudechat.ui.compose.ChatTabStrip(
                    titles = listOf("Fix the login bug", "PROJ-4542 remove the foreground service permission", "New chat"),
                    selected = 1,
                    onSelect = {},
                    onClose = {},
                )
            }
        }
    }

    /** Renders any plan file for a look: CLAUDECHAT_RENDER_PLAN=<file.md> ./gradlew test --tests '*ChatScreenRenderTest*'. */
    @Test
    fun `plan document from a file`() {
        val path = System.getenv("CLAUDECHAT_RENDER_PLAN") ?: return
        val plan = File(path).readText()
        listOf(false, true).forEach { dark ->
            renderContent("plan-file-${if (dark) "dark" else "light"}", dark, widthDp = 900, heightDp = 2600) {
                `in`.bleuphoenix.claudechat.ui.compose.PlanDocumentView(plan, pending = null, onUrlClick = {})
            }
        }
    }

    @Test
    fun `plan document with a flow diagram`() {
        val plan = javaClass.getResource("/samples/plan-with-diagram.md")!!.readText()
        val pending = `in`.bleuphoenix.claudechat.plan.PlanService.PendingPlan(build = {}, revise = {})
        listOf(false, true).forEach { dark ->
            renderContent("plan-document-${if (dark) "dark" else "light"}", dark, widthDp = 760, heightDp = 1150) {
                `in`.bleuphoenix.claudechat.ui.compose.PlanDocumentView(plan, pending, onUrlClick = {})
            }
        }
    }

    @Test
    fun `left-right diagram in a chat reply`() {
        val state = ChatState()
        state.items += AssistantTextItem(
            state.newKey(),
            "Here's the request flow:\n\n```mermaid\ngraph LR\n  UI[Chat panel] -- JSON lines --> CLI[claude CLI]\n  CLI --> API((Claude API))\n  CLI -.->|permission| UI\n```\n\nThe CLI asks the panel before running tools.",
        )
        render("diagram-chat-dark", state, dark = true, heightDp = 520)
    }

    @Test
    fun `review tab and changes card`() {
        val review = sampleReview()
        listOf(false, true).forEach { dark ->
            renderContent("review-${if (dark) "dark" else "light"}", dark, widthDp = 820, heightDp = 760) {
                `in`.bleuphoenix.claudechat.ui.compose.ReviewDocumentView(review, NoReviewActions)
            }
        }
        renderContent("review-empty-dark", dark = true, widthDp = 820, heightDp = 400) {
            `in`.bleuphoenix.claudechat.ui.compose.ReviewDocumentView(ReviewState(), NoReviewActions)
        }

        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Add a --name option to Main.kt")
        state.items += AssistantTextItem(state.newKey(), "Added the option and a small `Greeter` class.")
        reducer.addChanges(review.files.map { it.path }, review)
        (state.items.first() as UserItem).restorable = true
        render("changes-card-dark", state, dark = true, heightDp = 460)
    }

    @Test
    fun `context chips and the file list`() {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage(
            "Why does the clock screen crash on rotation?",
            listOf(FileAttachment("a1", "app/src/main/java/SetClockActivity.java", lines = 40..72), FileAttachment("a2", "app/src/main/res/layout", isDirectory = true)),
        )
        state.items += AssistantTextItem(state.newKey(), "The activity recreates its fragment without saved state.")
        reducer.endTurn()
        state.activeFile = ActiveFile("app/src/main/java/SetClockActivity.java", null)
        state.attachments += FileAttachment("a3", "app/build.gradle.kts")
        state.attachments += ImageAttachment("a4", "Pasted image", "image/png", "", null)
        state.attachments += TextAttachment("a5", "Logcat", (1..42).joinToString("\n") { "E/Clock: line $it" })
        state.input.edit { append("Also check @Clo") }
        state.mention = MentionState(
            "Clo", 11,
            listOf(
                FileMatch("app/src/main/java/com/example/clock/ClockFragment.kt", false),
                FileMatch("app/src/main/java/com/example/clock/ClockViewModel.kt", false),
                FileMatch("app/src/main/java/com/example/clock", true),
            ),
            selected = 1,
        )
        render("context-dark", state, dark = true, heightDp = 620)
        state.activeFileIncluded = false
        state.mention = null
        render("context-light", state, dark = false, heightDp = 620)
    }

    @Test
    fun `files dragged over the chat`() {
        val state = ChatState()
        ChatEventReducer(state).addUserMessage("Why does the clock screen crash on rotation?")
        state.items += AssistantTextItem(state.newKey(), "The activity recreates its fragment without saved state.")
        state.busy = false
        state.dropping = true
        listOf(true, false).forEach { dark ->
            render("drop-${if (dark) "dark" else "light"}", state, dark = dark, heightDp = 460)
        }
    }

    @Test
    fun `MCP servers under the message box`() {
        val state = ChatState()
        state.mcpServers = listOf(McpStatus("tracker", "connected"))
        render("mcp-connected-dark", state, dark = true, heightDp = 300)
        state.mcpServers = listOf(McpStatus("tracker", "connected"), McpStatus("files", "failed"))
        render("mcp-failed-light", state, dark = false, heightDp = 300)
    }

    @Test
    fun `code blocks with actions and an edited message`() {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("How do I read the --name option?")
        state.items += AssistantTextItem(
            state.newKey(),
            "Read it after the flag:\n\n```kotlin\nval name = args.indexOf(\"--name\")\n    .takeIf { it >= 0 }\n    ?.let { args.getOrNull(it + 1) }\n```\n\nThen fall back to `\"world\"`.",
        )
        reducer.endTurn()
        state.editing = state.items.first() as UserItem
        state.input.edit { append("How do I read the --name and --greeting options?") }
        render("code-edit-dark", state, dark = true, heightDp = 560)
    }

    @Test
    fun `tasks, a running sub-agent and the context meter`() {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Move the Set Clock screen to Flutter")
        state.items += AssistantTextItem(state.newKey(), "I'll look at how the screen is wired first.")
        state.items += ToolItem(state.newKey(), "Agent", "Find the Set Clock entry points", "{}", null, null, false).apply {
            agent = AgentProgress("Explore", activity = "Reading SetClockActivity.java", toolUses = 6, tokens = 18_400)
        }
        listOf(
            Triple("Find the Set Clock entry points", "Finding the Set Clock entry points", TaskStatus.Completed),
            Triple("Add the Flutter route", "Adding the Flutter route", TaskStatus.InProgress),
            Triple("Remove the old activity", null, TaskStatus.Pending),
            Triple("Update the navigation drawer", null, TaskStatus.Pending),
        ).forEachIndexed { i, (subject, active, status) ->
            state.tasks += TaskEntry("${'$'}i", subject, active).apply { this.status = status }
        }
        state.context = ContextUsage(used = 312_000, window = 1_000_000)
        render("agents-dark", state, dark = true, heightDp = 620)
        render("agents-light", state, dark = false, heightDp = 620)
    }

    @Test
    fun `history and recent chats`() {
        val now = System.currentTimeMillis()
        val sessions = listOf(
            SessionInfo("1", File("1"), "Set Clock navigation to Flutter", now - 4 * 60_000),
            SessionInfo("2", File("2"), "Fix Home item hidden under ActionBar", now - 3 * 3_600_000),
            SessionInfo("3", File("3"), "Smart Insight row after re-login", now - 26 * 3_600_000),
            SessionInfo("4", File("4"), "Remove foreground service from the scanner", now - 9 * 86_400_000L),
        )
        val empty = ChatState().apply { recent = sessions }
        render("empty-recent-dark", empty, dark = true, heightDp = 640)
        val history = ChatState().apply { this.history = HistoryState(sessions) }
        render("history-dark", history, dark = true, heightDp = 420)
        render("history-light", history, dark = false, heightDp = 420)
    }

    private fun sampleReview(): ReviewState {
        val review = ReviewState()
        val main = FileReview("/work/app/src/Main.kt", MAIN_BEFORE, created = false)
        main.update(MAIN_AFTER)
        val greeter = FileReview("/work/app/src/Greeter.kt", "", created = true)
        greeter.update("class Greeter(private val name: String) {\n    fun greet() = \"Hello, ${'$'}name!\"\n}\n")
        review.files += main
        review.files += greeter
        return review
    }

    private fun render(name: String, state: ChatState, dark: Boolean, widthDp: Int = 440, heightDp: Int = 900) =
        renderContent(name, dark, widthDp, heightDp) { `in`.bleuphoenix.claudechat.ui.compose.ProvideChatFontScale { ChatScreen(state, NoActions) } }

    @Test
    fun `a command that keeps failing shows once with a count`() {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Run the unit tests")
        repeat(3) {
            state.items += ToolItem(state.newKey(), "Bash", "./gradlew testDebugUnitTest", "{\"command\":\"./gradlew testDebugUnitTest\"}", null, null, false)
                .apply { status = ToolStatus.Failed; result = "Could not resolve all files for configuration" }
        }
        repeat(2) { reducer.addNotice("Claude exited with code 1: API Error: 529 Overloaded", isError = true) }
        render("repeated-failures-dark", state, dark = true, heightDp = 360)
    }

    @Test
    fun `larger chat text`() {
        val compose = `in`.bleuphoenix.claudechat.ui.compose.ChatAppearance
        compose.fontPercent = 150
        try {
            render("font-150-light", sampleConversation(), dark = false, heightDp = 700)
        } finally {
            compose.fontPercent = 100
        }
    }

    private fun renderContent(name: String, dark: Boolean, widthDp: Int, heightDp: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val density = 2f
        val scene = ImageComposeScene((widthDp * density).toInt(), (heightDp * density).toInt(), Density(density)) {
            IntUiTheme(isDark = dark) {
                ProvideMarkdownStyling {
                    `in`.bleuphoenix.claudechat.ui.compose.ProvideModernMarkdown { content() }
                }
            }
        }
        try {
            // Markdown is parsed off the UI thread; render frames until it has landed.
            var time = 0L
            repeat(30) {
                scene.render(time)
                time += 50_000_000L
                Thread.sleep(30)
            }
            val png = scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(outDir, "$name.png").writeBytes(png)
        } finally {
            scene.close()
        }
    }

    private fun sampleConversation(): ChatState {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Where is the Flutter engine re-attached when the activity resumes?")
        state.items += AssistantTextItem(
            state.newKey(),
            """
            It happens in `onResume` of `app/src/main/java/com/example/shop/feature/ShopFlutterActivity.kt:73`.
            The activity only re-attaches when it was **stopped** since the last resume:

            ```kotlin
            if (hostStoppedSinceLastResume) {
                hostStoppedSinceLastResume = false
                engineBindings?.attach()
            }
            ```

            - The native heartbeat is paused first
            - Scanner entry points skip this path
            """.trimIndent(),
        )
        reducer.handle(json("""{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"app/src/main/java/com/example/shop/feature/ShopFlutterActivity.kt"}}]}}"""))
        reducer.handle(json("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"..."}]}}"""))
        reducer.handle(json("""{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"t2","name":"Edit","input":{"file_path":"feature/EngineBindings.kt","old_string":"a","new_string":"b"}}]}}"""))
        reducer.handle(json("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"ok"}]}}"""))
        reducer.handle(json("""{"type":"assistant","message":{"id":"m3","content":[{"type":"tool_use","id":"t3","name":"Bash","input":{"command":"./gradlew :app:testDemoDebugUnitTest"}}]}}"""))
        reducer.handle(json("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t3","content":"FAILED","is_error":true}]}}"""))
        reducer.handle(json("""{"type":"result","subtype":"success","duration_ms":7900,"total_cost_usd":0.1377}"""))
        reducer.addUserMessage("Run the unit tests again")
        reducer.handle(json("""{"type":"assistant","message":{"id":"m4","content":[{"type":"tool_use","id":"t4","name":"Bash","input":{"command":"./gradlew :app:testDemoDebugUnitTest --rerun"}}]}}"""))
        return state
    }

    private fun samplePermission(): ChatState {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Rename the heartbeat flag")
        reducer.handle(json("""{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"feature/ShopFlutterActivity.kt","old_string":"a","new_string":"b"}}]}}"""))
        reducer.handle(
            json(
                """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Edit","display_name":"Edit",
                "input":{"file_path":"feature/ShopFlutterActivity.kt","old_string":"a","new_string":"b"},"description":"feature/ShopFlutterActivity.kt",
                "permission_suggestions":[{"type":"setMode","mode":"acceptEdits","destination":"session"}],"tool_use_id":"t1"}}""",
            ),
        )
        return state
    }

    private companion object {
        val MAIN_BEFORE = """
            |import kotlin.system.exitProcess
            |
            |fun main(args: Array<String>) {
            |    println("Hello, world!")
            |    val verbose = false
            |    if (verbose) println("done")
            |}
            |
            |fun usage() = "usage: app"
            |""".trimMargin()
        val MAIN_AFTER = """
            |import kotlin.system.exitProcess
            |
            |fun main(args: Array<String>) {
            |    val name = args.indexOf("--name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: "world"
            |    println(Greeter(name).greet())
            |    val verbose = false
            |    if (verbose) println("done")
            |}
            |
            |fun usage() = "usage: app [--name NAME]"
            |""".trimMargin()
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    private object NoActions : ChatActions {
        override fun send() = Unit
        override fun stop() = Unit
        override fun selectMode(mode: PermissionMode) = Unit
        override fun answer(item: PermissionItem, allowed: Boolean, always: AlwaysAllowOption?) = Unit
        override fun review(item: PermissionItem) = Unit
        override fun showDiff(item: ToolItem) = Unit
        override fun openFile(path: String) = Unit
        override fun openLink(url: String) = Unit
        override fun suggest(prompt: String) = Unit
        override fun openPlan(item: PlanItem) = Unit
        override fun buildPlan(item: PlanItem, autoAcceptEdits: Boolean) = Unit
        override fun revisePlan(item: PlanItem) = Unit
        override fun openReview() = Unit
        override fun acceptChanges(files: List<FileReview>) = Unit
        override fun rejectChanges(files: List<FileReview>) = Unit
        override fun removeAttachment(attachment: Attachment) = Unit
        override fun copyText(text: String) = Unit
        override fun insertCode(code: String) = Unit
        override fun editMessage(item: UserItem) = Unit
        override fun cancelEdit() = Unit
        override fun retry(item: UserItem) = Unit
        override fun restoreCheckpoint(item: UserItem) = Unit
        override fun openHistory() = Unit
        override fun closeHistory() = Unit
        override fun openSession(session: SessionInfo) = Unit
        override fun renameSession(session: SessionInfo, title: String) = Unit
        override fun deleteSession(session: SessionInfo) = Unit
        override fun includeActiveFile(included: Boolean) = Unit
        override fun mentionChanged(query: String?, start: Int) = Unit
        override fun moveMentionSelection(delta: Int) = Unit
        override fun pickMention(match: FileMatch) = Unit
        override fun pasteAttachments() = false
        override fun slashChanged(query: String?, start: Int) = Unit
        override fun moveSlashSelection(delta: Int) = Unit
        override fun pickSlash(command: SlashCommand) = Unit
        override fun selectModel(value: String) = Unit
        override fun unqueue(message: QueuedMessage, edit: Boolean) = Unit
    }

    private object NoReviewActions : ReviewActions {
        override fun acceptHunk(file: FileReview, hunk: Hunk) = Unit
        override fun rejectHunk(file: FileReview, hunk: Hunk) = Unit
        override fun acceptFiles(files: List<FileReview>) = Unit
        override fun rejectFiles(files: List<FileReview>) = Unit
        override fun openAt(file: FileReview, line: Int) = Unit
    }
}
