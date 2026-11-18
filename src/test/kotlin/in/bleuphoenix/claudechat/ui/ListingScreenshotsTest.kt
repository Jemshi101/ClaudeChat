package `in`.bleuphoenix.claudechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import `in`.bleuphoenix.claudechat.cli.CliCatalog
import `in`.bleuphoenix.claudechat.cli.Effort
import `in`.bleuphoenix.claudechat.ide.CodeProblem
import `in`.bleuphoenix.claudechat.ide.FixWithClaudeIntention
import `in`.bleuphoenix.claudechat.cli.ModelOption
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.ide.FileMatch
import `in`.bleuphoenix.claudechat.plan.PlanService
import `in`.bleuphoenix.claudechat.review.FileReview
import `in`.bleuphoenix.claudechat.review.Hunk
import `in`.bleuphoenix.claudechat.review.ReviewActions
import `in`.bleuphoenix.claudechat.review.ReviewState
import `in`.bleuphoenix.claudechat.ui.compose.ChatActions
import `in`.bleuphoenix.claudechat.ui.compose.ChatScreen
import `in`.bleuphoenix.claudechat.ui.compose.PlanDocumentView
import `in`.bleuphoenix.claudechat.ui.compose.ProvideModernMarkdown
import `in`.bleuphoenix.claudechat.ui.compose.ReviewDocumentView
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The screenshots for the README and the JetBrains Marketplace listing, drawn offscreen with neutral sample content
 * (a made-up weather app). Written to docs/screenshots only when asked, so ordinary test runs leave them alone:
 *
 *     CLAUDECHAT_SCREENSHOTS=1 ./gradlew test --tests '*ListingScreenshotsTest*'
 */
class ListingScreenshotsTest {

    private val enabled = System.getenv("CLAUDECHAT_SCREENSHOTS") == "1"
    private val outDir = File("docs/screenshots")

    @Test
    fun `chat and the review tab`() = shoot("1-chat-and-review", dark = true) {
        Panels(chat = { ChatScreen(reviewedChat(), NoActions) }) { ReviewDocumentView(sampleReview(), NoReviewActions) }
    }

    @Test
    fun `chat and a plan with its diagram`() = shoot("2-plan", dark = true) {
        Panels(chat = { ChatScreen(planChat(), NoActions) }) {
            PlanDocumentView(PLAN, PlanService.PendingPlan(build = {}, revise = {}), onUrlClick = {})
        }
    }

    @Test
    fun `tasks, changes and a permission prompt`() = shoot("3-tasks-and-permission", dark = true) {
        Panels(chat = { ChatScreen(workingChat(), NoActions) }) { ChatScreen(commandsChat(), NoActions) }
    }

    @Test
    fun `light theme`() = shoot("4-light", dark = false) {
        Panels(chat = { ChatScreen(reviewedChat(), NoActions) }) {
            PlanDocumentView(PLAN, pending = null, onUrlClick = {})
        }
    }

    @Test
    fun `debug mode and fix with claude`() = shoot("5-debug-and-fix", dark = true) {
        Panels(chat = { ChatScreen(debugChat(), NoActions) }) { ChatScreen(fixChat(), NoActions) }
    }

    // ---- Sample chats ----

    private val sampleModels = listOf(
        ModelOption(CliCatalog.DEFAULT, "Default (recommended)", "Best for everyday, complex tasks", "claude-opus-5-5", Effort.LEVELS),
        ModelOption("opus", "Opus", "Most capable for complex work", "claude-opus-5-5", Effort.LEVELS),
        ModelOption("sonnet", "Sonnet", "Most efficient for simpler tasks", "claude-sonnet-5-5", Effort.LEVELS),
    )

    private fun debugChat(): ChatState {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        state.mode = PermissionMode.CHOICES.first { it.id == DebugMode.ID }
        reducer.addUserMessage("After midnight the forecast still shows yesterday's date until I restart the app")
        repeat(3) { failedTool(reducer, "d$it", "Bash", """{"command":"adb logcat -d -s ClaudeDebug"}""", "adb: no devices/emulators found") }
        state.items += AssistantTextItem(
            state.newKey(),
            """
            No device is connected, so I can't read the logs yet. Likely causes:

            1. **`ForecastCache` keeps the forecast across midnight**: its 30-minute lifetime doesn't care about the date
            2. `ForecastScreen` formats "today" once, when the screen is created
            3. The device's time zone changed while the app was running

            I added `ClaudeDebug` logging to `ForecastCache.get` and `ForecastScreen` to tell them apart.

            <reproduction_steps>
            1. Connect a device and open the forecast screen at 23:55
            2. Leave the app open until 00:05
            3. Pull down to refresh and check the date
            </reproduction_steps>
            """.trimIndent(),
        )
        reducer.handle(json("""{"type":"result","subtype":"success","duration_ms":38000,"total_cost_usd":0.18}"""))
        state.models = sampleModels
        state.effort = "high"
        state.context = ContextUsage(used = 64_000, window = 1_000_000)
        return state
    }

    private fun fixChat(): ChatState {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        val path = "src/main/kotlin/weather/ForecastRepository.kt"
        reducer.addUserMessage(
            FixWithClaudeIntention.prompt(path, listOf(CodeProblem(27, "Type mismatch: inferred type is Forecast? but Forecast was expected", true))),
            listOf(FileAttachment("f1", path, lines = 27..27)),
        )
        tool(reducer, "f2", "Read", """{"file_path":"$path"}""")
        state.items += AssistantTextItem(
            state.newKey(),
            """
            `cache.get()` returns null once the cached forecast expires, but `latest()` promises a `Forecast`. Fall back
            to the network instead of forcing it with `!!`:

            ```kotlin $path
            suspend fun latest(): Forecast =
                cache.get() ?: api.forecast().also(cache::put)
            ```

            This also fills the cache after each fetch, which the caching plan needed anyway.
            """.trimIndent(),
        )
        reducer.handle(json("""{"type":"result","subtype":"success","duration_ms":12000,"total_cost_usd":0.05}"""))
        state.models = sampleModels
        state.context = ContextUsage(used = 31_000, window = 1_000_000)
        return state
    }

    private fun reviewedChat(): ChatState {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Show the temperature in the user's unit and add a --units option to the CLI")
        tool(reducer, "t1", "Read", """{"file_path":"src/main/kotlin/weather/Main.kt"}""")
        tool(reducer, "t2", "Grep", """{"pattern":"Units","path":"src"}""")
        state.items += AssistantTextItem(
            state.newKey(),
            """
            Done. `main` now reads **--units** and passes it to a small `Formatter`:

            ```kotlin src/main/kotlin/weather/Main.kt
            val units = Units.parse(args.valueAfter("--units") ?: "metric")
            println(Formatter(units).temperature(reading))
            ```

            - Metric is the default, so nothing changes without the option
            - `Formatter` rounds to one decimal place
            """.trimIndent(),
        )
        tool(reducer, "t3", "Edit", """{"file_path":"src/main/kotlin/weather/Main.kt","old_string":"a","new_string":"b"}""")
        tool(reducer, "t4", "Bash", """{"command":"./gradlew test"}""")
        reducer.handle(json("""{"type":"result","subtype":"success","duration_ms":41000,"total_cost_usd":0.21}"""))
        val review = sampleReview()
        reducer.addChanges(review.files.map { it.path }, review)
        state.review = review
        state.editedFiles += review.files.map { it.path }
        state.context = ContextUsage(used = 86_000, window = 1_000_000)
        state.models = sampleModels
        state.effort = "high"
        return state
    }

    private fun planChat(): ChatState {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Plan offline caching for the forecast screen")
        tool(reducer, "t1", "Agent", """{"description":"Find how forecasts are loaded","prompt":"…"}""")
        state.items += AssistantTextItem(
            state.newKey(),
            "The forecast is fetched on every launch. The plan keeps the last forecast for 30 minutes and shows it " +
                "straight away while a fresh one loads.",
        )
        reducer.handle(json("""{"type":"result","subtype":"success","duration_ms":64000,"total_cost_usd":0.34}"""))
        state.mode = PermissionMode.CHOICES.first { it.id == "plan" }
        return state
    }

    private fun workingChat(): ChatState {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage("Build the caching plan")
        state.items += AssistantTextItem(state.newKey(), "Starting with the cache, then the repository.")
        tool(reducer, "t1", "Write", """{"file_path":"src/main/kotlin/weather/ForecastCache.kt","content":"…"}""")
        reducer.handle(json("""{"type":"assistant","message":{"id":"m9","content":[{"type":"tool_use","id":"t9","name":"Bash","input":{"command":"./gradlew test --tests '*ForecastCacheTest*'","description":"Run the cache tests"}}]}}"""))
        reducer.handle(
            json(
                """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash",
                "input":{"command":"./gradlew test --tests '*ForecastCacheTest*'","description":"Run the cache tests"},"description":"Run the cache tests",
                "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"./gradlew test:*"}],"behavior":"allow","destination":"localSettings"}],"tool_use_id":"t9"}}""",
            ),
        )
        listOf(
            Triple("Add ForecastCache with a 30-minute lifetime", null, TaskStatus.Completed),
            Triple("Run the cache tests", "Running the cache tests", TaskStatus.InProgress),
            Triple("Read from the cache in ForecastRepository", null, TaskStatus.Pending),
            Triple("Show the cached forecast while refreshing", null, TaskStatus.Pending),
        ).forEachIndexed { i, (subject, active, status) -> state.tasks += TaskEntry("$i", subject, active).apply { this.status = status } }
        val review = ReviewState().apply {
            files += FileReview("/work/weather/src/main/kotlin/weather/ForecastCache.kt", "", created = true).apply { update(CACHE) }
        }
        state.review = review
        state.editedFiles += review.files.map { it.path }
        state.activityExpanded = true
        state.busy = true
        state.status = "Waiting for permission…"
        state.context = ContextUsage(used = 142_000, window = 1_000_000)
        return state
    }

    private fun commandsChat(): ChatState {
        val state = ChatState()
        val reducer = ChatEventReducer(state)
        reducer.addUserMessage(
            "Why does the forecast flicker on rotation?",
            listOf(FileAttachment("a1", "src/main/kotlin/weather/ForecastScreen.kt", lines = 40..72)),
        )
        state.items += AssistantTextItem(state.newKey(), "The screen reloads on every configuration change. Keeping the state in the view model fixes it.")
        reducer.endTurn()
        val commands = listOf(
            SlashCommand("compact", "Free up context by summarizing the conversation so far", "<optional instructions>"),
            SlashCommand("context", "Show current context usage", ""),
            SlashCommand("code-review", "Review the current diff for correctness bugs", "[level]"),
        )
        state.commands = commands
        state.models = listOf(
            ModelOption(CliCatalog.DEFAULT, "Default (recommended)", "Best for everyday, complex tasks", null),
            ModelOption("sonnet", "Sonnet", "Most efficient for simpler tasks", null),
        )
        state.activeFile = ActiveFile("src/main/kotlin/weather/ForecastScreen.kt", null)
        state.attachments += FileAttachment("a2", "src/main/kotlin/weather/ForecastViewModel.kt")
        state.input.edit { append("Also check @Fore") }
        state.mention = MentionState(
            "Fore", 11,
            listOf(
                FileMatch("src/main/kotlin/weather/ForecastRepository.kt", false),
                FileMatch("src/main/kotlin/weather/ForecastViewModel.kt", false),
                FileMatch("src/test/kotlin/weather", true),
            ),
            selected = 0,
        )
        return state
    }

    private fun sampleReview(): ReviewState {
        val review = ReviewState()
        review.files += FileReview("/work/weather/src/main/kotlin/weather/Main.kt", MAIN_BEFORE, created = false).apply { update(MAIN_AFTER) }
        review.files += FileReview("/work/weather/src/main/kotlin/weather/Formatter.kt", "", created = true).apply { update(FORMATTER) }
        return review
    }

    private fun tool(reducer: ChatEventReducer, id: String, name: String, input: String) {
        reducer.handle(json("""{"type":"assistant","message":{"id":"m$id","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}"""))
        reducer.handle(json("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":"ok"}]}}"""))
    }

    private fun failedTool(reducer: ChatEventReducer, id: String, name: String, input: String, error: String) {
        reducer.handle(json("""{"type":"assistant","message":{"id":"m$id","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}"""))
        reducer.handle(json("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":"$error","is_error":true}]}}"""))
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    // ---- Drawing ----

    /** The chat panel on the left, as docked in the IDE, and an editor tab or a second chat on the right. */
    @Composable
    private fun Panels(chat: @Composable () -> Unit, right: @Composable () -> Unit) {
        Row {
            Box(Modifier.width(CHAT_WIDTH.dp).fillMaxHeight()) { chat() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x40808080)))
            Box(Modifier.width((WIDTH - CHAT_WIDTH - 1).dp).fillMaxHeight()) { right() }
        }
    }

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        if (!enabled) return
        outDir.mkdirs()
        val density = 1.5f
        val scene = ImageComposeScene((WIDTH * density).toInt(), (HEIGHT * density).toInt(), Density(density)) {
            IntUiTheme(isDark = dark) { ProvideMarkdownStyling { ProvideModernMarkdown { content() } } }
        }
        try {
            // Markdown is parsed off the UI thread; render frames until it has landed.
            var time = 0L
            repeat(40) {
                scene.render(time)
                time += 50_000_000L
                Thread.sleep(30)
            }
            File(outDir, "$name.png").writeBytes(scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val CHAT_WIDTH = 480

        /** Actions that do nothing: these chats are only drawn. */
        val NoActions: ChatActions = Proxy.newProxyInstance(ChatActions::class.java.classLoader, arrayOf(ChatActions::class.java)) { _, method, _ ->
            if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        } as ChatActions

        val NoReviewActions = object : ReviewActions {
            override fun acceptHunk(file: FileReview, hunk: Hunk) = Unit
            override fun rejectHunk(file: FileReview, hunk: Hunk) = Unit
            override fun acceptFiles(files: List<FileReview>) = Unit
            override fun rejectFiles(files: List<FileReview>) = Unit
            override fun openAt(file: FileReview, line: Int) = Unit
        }

        val MAIN_BEFORE = """
            |package weather
            |
            |fun main(args: Array<String>) {
            |    val reading = WeatherClient().current()
            |    println("${'$'}{reading.celsius} °C")
            |}
            |""".trimMargin()

        val MAIN_AFTER = """
            |package weather
            |
            |fun main(args: Array<String>) {
            |    val units = Units.parse(args.valueAfter("--units") ?: "metric")
            |    val reading = WeatherClient().current()
            |    println(Formatter(units).temperature(reading))
            |}
            |""".trimMargin()

        val FORMATTER = """
            |package weather
            |
            |class Formatter(private val units: Units) {
            |    fun temperature(reading: Reading): String = when (units) {
            |        Units.METRIC -> "%.1f °C".format(reading.celsius)
            |        Units.IMPERIAL -> "%.1f °F".format(reading.celsius * 9 / 5 + 32)
            |    }
            |}
            |""".trimMargin()

        val CACHE = """
            |package weather
            |
            |class ForecastCache(private val clock: Clock, private val lifetime: Duration = 30.minutes) {
            |    private var saved: Pair<Instant, Forecast>? = null
            |
            |    fun get(): Forecast? = saved?.takeIf { clock.now() - it.first < lifetime }?.second
            |
            |    fun put(forecast: Forecast) {
            |        saved = clock.now() to forecast
            |    }
            |}
            |""".trimMargin()

        val PLAN = """
            |# Offline caching for the forecast screen
            |
            |## Overview
            |Keep the last forecast for 30 minutes and show it straight away while a fresh one loads, so the screen
            |isn't empty on a slow network.
            |
            |```mermaid
            |flowchart TD
            |    A[Forecast screen opens] --> B{Cached forecast under 30 min?}
            |    B -->|yes| C[Show cached forecast]
            |    B -->|no| D[Show loading state]
            |    C --> E[Fetch in the background]
            |    D --> E
            |    E --> F([Show fresh forecast])
            |    E -.->|offline| C
            |```
            |
            |## Changes
            |1. `ForecastCache.kt`: a new in-memory cache with a 30-minute lifetime.
            |2. `ForecastRepository.kt`: read from the cache first, then refresh it after each fetch.
            |3. `ForecastScreen.kt`: show the cached forecast with an "Updating…" label while refreshing.
            |
            |## To-dos
            |- [ ] Add ForecastCache with a 30-minute lifetime
            |- [ ] Read from the cache in ForecastRepository
            |- [ ] Show the cached forecast while refreshing
            |- [ ] Unit tests for expiry and the offline path
            |""".trimMargin()
    }
}
