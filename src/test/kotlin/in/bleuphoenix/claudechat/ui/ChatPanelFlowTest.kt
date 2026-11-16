package `in`.bleuphoenix.claudechat.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.review.ReviewService
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.io.File
import java.nio.file.Files

/**
 * The chat panel against a fake CLI (`fake-cli/fake_claude.py`), which records what the panel sends: queued
 * messages, Stop, slash commands and model switches, as the composer triggers them.
 */
class ChatPanelFlowTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private lateinit var panel: ChatPanel
    private var savedCliPath: String? = null
    private var savedModel: String? = null
    private var savedEffort: String? = null

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("fake-claude").toFile().canonicalFile
        val script = File(dir, "fake_claude.py").apply { writeText(File("src/test/resources/fake-cli/fake_claude.py").readText()) }
        val launcher = File(dir, "claude").apply {
            writeText("#!/bin/sh\nexec /usr/bin/python3 -I '${script.path}' \"$@\"\n")
            setExecutable(true)
        }
        val options = ClaudeChatSettings.options
        savedCliPath = options.cliPath
        savedModel = options.model
        savedEffort = options.effort
        options.effort = null
        options.cliPath = launcher.path
        options.model = null
        File(project.basePath!!).mkdirs()
        // The light test project is shared: drop a rejection note another test may have left for the next message.
        ReviewService.getInstance(project).takeRejectionNote()
        panel = ChatPanel(project)
        Disposer.register(testRootDisposable, panel)
        waitFor { panel.state.commands.isNotEmpty() && panel.state.models.isNotEmpty() }
    }

    override fun tearDown() {
        try {
            ClaudeChatSettings.options.cliPath = savedCliPath
            ClaudeChatSettings.options.model = savedModel
            ClaudeChatSettings.options.effort = savedEffort
            dir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test Send now reaches Claude within the running task`() {
        type("WAIT first")
        waitFor { panel.state.busy && sentTexts().contains("WAIT first") }

        panel.suggest("also check the tests")
        panel.sendNow()
        waitFor { sentTexts().any { it.startsWith("also check the tests") } }
        assertTrue("still the same task", panel.state.busy)
        assertTrue(panel.state.queued.isEmpty())
        val note = panel.state.items.filterIsInstance<UserItem>().last()
        assertEquals("also check the tests", note.text)
        assertTrue(note.sentWhileWorking)

        release()
        waitFor { !panel.state.busy }
        val reply = panel.state.items.filterIsInstance<AssistantTextItem>().last().text
        assertTrue(reply, reply.startsWith("done: WAIT first | read: also check the tests"))
        dispatchFor(200)
        assertFalse("one task, no second turn", panel.state.busy)
    }

    fun `test Stop and send interrupts Claude, then sends the message first`() {
        type("WAIT first")
        waitFor { panel.state.busy && sentTexts().contains("WAIT first") }
        type("WAIT later")
        assertEquals(listOf("WAIT later"), panel.state.queued.map { it.text })

        panel.suggest("do this instead")
        panel.stopAndSend()
        waitFor { received().any { it.getAsJsonObject("request")?.get("subtype")?.asString == "interrupt" } }
        waitFor { sentTexts().any { it.startsWith("do this instead") } }
        assertEquals("the queue keeps waiting behind it", listOf("WAIT later"), panel.state.queued.map { it.text })
        waitFor { !panel.state.busy || sentTexts().any { it.startsWith("WAIT later") } }
        assertEquals(listOf("WAIT first", "do this instead"), sentTexts().take(2).map { it.substringBefore("\n") })
    }

    fun `test a queued message can be sent now or with a stop`() {
        type("WAIT first")
        waitFor { panel.state.busy && sentTexts().contains("WAIT first") }
        type("a note")
        val queued = panel.state.queued.single()
        panel.sendQueuedNow(queued, stopFirst = false)
        waitFor { sentTexts().any { it.startsWith("a note") } }
        assertTrue(panel.state.queued.isEmpty())
        assertTrue(panel.state.busy)
        release()
        waitFor { !panel.state.busy }
    }

    fun `test a message sent while Claude works waits, then goes when the turn ends`() {
        type("WAIT first")
        waitFor { sentTexts().contains("WAIT first") }
        assertTrue(panel.state.busy)

        type("second")
        assertEquals(listOf("second"), panel.state.queued.map { it.text })
        assertEquals("", panel.state.input.text.toString())
        dispatchFor(300)
        assertEquals("nothing more is sent while Claude works", listOf("WAIT first"), sentTexts())

        release()
        waitFor { sentTexts().any { it.startsWith("second") } }
        assertTrue(panel.state.queued.isEmpty())
        waitFor { !panel.state.busy }
        assertEquals(listOf("WAIT first", "second"), panel.state.items.filterIsInstance<UserItem>().map { it.text })
    }

    fun `test a right-click request waits while Claude works, then goes with its file`() {
        type("WAIT first")
        waitFor { sentTexts().contains("WAIT first") }

        panel.ask("Explain this file", listOf(panel.newFileAttachment("src/Main.kt", isDirectory = false, lines = null)))
        assertEquals(listOf("Explain this file"), panel.state.queued.map { it.text })

        release()
        waitFor { sentTexts().any { it.startsWith("Explain this file") } }
        assertTrue(sentTexts().last().contains("Context: @src/Main.kt"))
        waitFor { !panel.state.busy }
    }

    fun `test a long paste alone can be sent, inside the message`() {
        panel.state.attachments += panel.newTextAttachment("Pasted text", "line 1\n\nline 3")
        panel.send()
        waitFor { sentTexts().isNotEmpty() }
        assertEquals("<attached-text source=\"Pasted text\">\nline 1\n\nline 3\n</attached-text>", sentTexts().single())
        val sent = panel.state.items.filterIsInstance<UserItem>().single()
        assertEquals("Pasted text · 3 lines", sent.attachments.single().label)
        waitFor { !panel.state.busy }
    }

    fun `test a removed queued message is never sent, and an edited one comes back to the box`() {
        type("WAIT first")
        waitFor { panel.state.busy }
        type("drop me")
        type("edit me")
        panel.unqueue(panel.state.queued.first { it.text == "drop me" }, edit = false)
        panel.unqueue(panel.state.queued.first { it.text == "edit me" }, edit = true)
        assertTrue(panel.state.queued.isEmpty())
        assertEquals("edit me", panel.state.input.text.toString())

        release()
        waitFor { !panel.state.busy }
        dispatchFor(300)
        assertEquals(listOf("WAIT first"), sentTexts())
    }

    fun `test Stop puts queued messages back in the box instead of sending them`() {
        type("WAIT first")
        waitFor { panel.state.busy }
        type("later one")
        type("later two")
        panel.state.input.edit { append("typed") }

        panel.stop()
        assertEquals("later one\n\nlater two\n\ntyped", panel.state.input.text.toString())
        assertTrue(panel.state.queued.isEmpty())
        waitFor { received().any { it.getAsJsonObject("request")?.get("subtype")?.asString == "interrupt" } }
        waitFor { !panel.state.busy }
        dispatchFor(300)
        assertEquals(listOf("WAIT first"), sentTexts())
    }

    fun `test slash commands go to the CLI as typed, without the chat's added context`() {
        panel.attachFile("src/Main.kt", 3..4)
        type("/compact keep the plan")
        waitFor { sentTexts().isNotEmpty() }
        assertEquals("/compact keep the plan @src/Main.kt#L3-4", sentTexts().single())

        // Not a command the CLI offers: an ordinary message (a path, here).
        type("/tmp/out.txt is empty")
        waitFor { sentTexts().size == 2 }
        assertTrue(sentTexts()[1].startsWith("/tmp/out.txt is empty"))
    }

    fun `test a command picked later in the message goes where it was typed`() {
        panel.state.commands = listOf(SlashCommand("simplify-flow", "Simplify", ""))
        panel.state.input.setTextAndPlaceCursorAtEnd("Check the login change.\nKeep the flow. /simp")
        panel.slashChanged("simp", 39)
        panel.pickSlash(panel.state.slash!!.results.single())
        assertEquals("Check the login change.\nKeep the flow. /simplify-flow ", panel.state.input.text.toString())
        assertEquals(TextRange(54), panel.state.input.selection)
        assertNull(panel.state.slash)

        // In the middle, with text after it: no doubled space, and the caret right after the name.
        panel.state.input.edit {
            replace(0, length, "Check /sim the login change")
            selection = TextRange(10)
        }
        panel.slashChanged("sim", 6)
        panel.pickSlash(panel.state.slash!!.results.single())
        assertEquals("Check /simplify-flow the login change", panel.state.input.text.toString())
        assertEquals(TextRange(21), panel.state.input.selection)
    }

    fun `test a plan Claude no longer waits on is built from its file, as in Cursor`() {
        val plan = PlanItem(panel.state.newKey(), "req-1", "Add dark mode", "# Add dark mode\n\n- [ ] Theme", null, JsonObject())
        plan.outcome = "Keep planning · send your feedback below"
        panel.state.items += plan
        assertTrue(plan.buildable)

        panel.buildPlan(plan, autoAcceptEdits = true)
        waitFor { !panel.state.busy && panel.state.items.any { it is AssistantTextItem } }

        assertFalse(plan.buildable)
        assertEquals("acceptEdits", panel.state.mode.id)
        assertEquals("acceptEdits", launches().last().after("--permission-mode"))
        val sent = panel.state.items.filterIsInstance<UserItem>().single()
        assertEquals("Add dark mode\n\nImplement the plan as specified, it is attached for your reference. Do NOT edit the plan file itself.", sent.text)
        val planFile = (sent.attachments.single() as FileAttachment).path
        assertTrue(File(planFile).readText().startsWith("# Add dark mode"))
        assertTrue("the plan file goes with the message", sentTexts().single().contains(File(planFile).name))
    }

    fun `test Ask mode runs the CLI in its normal mode, tells Claude, and declines edits without asking`() {
        panel.selectMode(PermissionMode.CHOICES.first { it.id == "ask" })
        type("ASKEDIT please")
        waitFor { !panel.state.busy && panel.state.items.any { it is AssistantTextItem } }

        assertEquals("manual", launches().last().after("--permission-mode"))
        assertTrue(sentTexts().single().startsWith("(Ask mode: answer my question."))
        assertTrue("no approval card", panel.state.items.none { it is PermissionItem })
        val reply = (panel.state.items.last { it is AssistantTextItem } as AssistantTextItem).text
        assertTrue(reply, reply.startsWith("permission: deny The chat is in Ask mode"))
        assertEquals("ask", panel.state.mode.id)
    }

    fun `test Debug mode explains itself once, then Proceed and Mark as fixed answer the reproduction steps`() {
        panel.selectMode(PermissionMode.CHOICES.first { it.id == DebugMode.ID })
        type("REPRO the clock resets")
        waitFor { !panel.state.busy && panel.state.items.any { it is AssistantTextItem } }

        assertEquals("manual", launches().last().after("--permission-mode"))
        assertTrue(sentTexts().single().startsWith("(Debug mode: find the cause"))
        assertEquals("1. Open Set Clock.\n2. Rotate the phone.", DebugMode.pendingSteps(panel.state))

        // Enter in the empty box proceeds.
        panel.send()
        waitFor { !panel.state.busy && sentTexts().size == 2 }
        assertEquals(DebugMode.PROCEED_MESSAGE, (panel.state.items.last { it is UserItem } as UserItem).text)
        assertTrue(sentTexts()[1], sentTexts()[1].startsWith(DebugMode.REMINDER))
        assertNull("the echo has no steps", DebugMode.pendingSteps(panel.state))

        type("REPRO again")
        waitFor { !panel.state.busy && sentTexts().size == 3 && DebugMode.pendingSteps(panel.state) != null }
        panel.markFixed()
        waitFor { !panel.state.busy && sentTexts().size == 4 }
        assertEquals("manual", panel.state.mode.id)
        assertEquals(DebugMode.MARK_FIXED_MESSAGE, sentTexts()[3])
    }

    fun `test the model picker and a typed slash model both switch with set_model`() {
        type("hello")
        waitFor { !panel.state.busy && sentTexts().size == 1 }

        panel.selectModel("haiku")
        assertEquals("haiku", panel.state.model)
        assertEquals("haiku", ClaudeChatSettings.options.model)
        waitFor { setModels() == listOf("haiku") }

        type("/model default")
        waitFor { setModels() == listOf("haiku", "default") }
        assertNull(panel.state.model)
        assertEquals("a typed /model isn't sent as a message", 1, sentTexts().size)
        assertTrue(panel.state.items.last() is NoticeItem)
    }

    fun `test effort is a launch flag, then switches live with apply_flag_settings`() {
        panel.selectEffort("high")
        assertEquals("high", ClaudeChatSettings.options.effort)
        type("hello")
        waitFor { !panel.state.busy && sentTexts().size == 1 }
        assertEquals("high", launches().last().after("--effort"))

        panel.selectEffort("max")
        panel.selectEffort(null)
        waitFor { effortSettings().size == 2 }
        assertEquals("max", effortSettings()[0].asString)
        assertTrue("default clears the flag setting", effortSettings()[1].isJsonNull)
        assertNull(panel.state.effort)
        assertEquals("one CLI process throughout", 1, launches().count { "-p" in it })
    }

    fun `test the user's system prompt text and turn limit reach the CLI`() {
        val options = ClaudeChatSettings.options
        val saved = options.systemPromptAppend to options.maxTurns
        options.systemPromptAppend = "Answer in British English."
        options.maxTurns = 30
        try {
            type("hello")
            waitFor { !panel.state.busy && sentTexts().size == 1 }
            val launch = launches().last()
            assertEquals("30", launch.after("--max-turns"))
            val prompt = File(launch.after("--append-system-prompt-file")!!).readText()
            assertTrue(prompt, prompt.endsWith("Answer in British English."))
        } finally {
            options.systemPromptAppend = saved.first
            options.maxTurns = saved.second
        }
    }

    fun `test signing in to an MCP server opens its page and the indicator follows`() {
        type("hello")
        waitFor { !panel.state.busy && sentTexts().size == 1 }
        val opened = mutableListOf<String>()
        panel.openInBrowser = { opened += it }

        panel.signInToMcp("docs")
        waitFor { panel.state.mcpServers.isNotEmpty() }
        assertEquals(listOf("https://auth.example.com/authorize?state=1"), opened)
        assertEquals(listOf(McpStatus("docs", "connected")), panel.state.mcpServers)
        val request = received().mapNotNull { it.getAsJsonObject("request") }.first { it.get("subtype").asString == "mcp_authenticate" }
        assertEquals("docs", request.get("serverName").asString)
        assertTrue(panel.state.items.any { it is NoticeItem && it.text.contains("Finish signing in to docs") })
    }

    fun `test slash clear starts the chat view over`() {
        type("hello")
        waitFor { !panel.state.busy && panel.state.items.any { it is AssistantTextItem } }
        type("/clear")
        waitFor { !panel.state.busy && panel.state.items.none { it is AssistantTextItem } }
        assertTrue(panel.state.items.first() is NoticeItem)
    }

    fun `test editing a message continues the CLI session from just before it`() {
        type("first")
        waitFor { !panel.state.busy && panel.state.items.count { it is AssistantTextItem } == 1 }
        type("second")
        waitFor { !panel.state.busy && panel.state.items.count { it is AssistantTextItem } == 2 }
        val lastReply = received().size

        val second = panel.state.items.filterIsInstance<UserItem>().last()
        panel.editMessage(second)
        assertEquals("second", panel.state.input.text.toString())
        panel.state.input.edit { replace(0, length, "second, edited") }
        panel.send()
        waitFor { !panel.state.busy && sentTexts().size == 3 }

        assertEquals(listOf("first", "second, edited"), panel.state.items.filterIsInstance<UserItem>().map { it.text })
        val restart = launches().last()
        assertEquals("a new CLI process continues the session", 2, launches().size)
        assertEquals("fake-session", restart.after("--resume"))
        assertEquals(second.forkPoint, restart.after("--resume-session-at"))
        assertTrue("--fork-session" in restart)
        assertTrue(received().size > lastReply)
    }

    fun `test retrying the first message starts a fresh session`() {
        type("only")
        waitFor { !panel.state.busy && panel.state.items.any { it is AssistantTextItem } }
        panel.retry(panel.state.items.filterIsInstance<UserItem>().single())
        waitFor { !panel.state.busy && sentTexts().size == 2 }
        assertEquals(listOf("only"), panel.state.items.filterIsInstance<UserItem>().map { it.text })
        assertFalse("--resume" in launches().last())
    }

    fun `test signing in from the card sends the failed message again`() {
        File(dir, "signed-out").writeText("")
        type("hola")
        waitFor { panel.state.items.any { it is LoginItem } && !panel.state.busy }
        val login = panel.state.items.filterIsInstance<LoginItem>().single()
        assertEquals("hola", login.retry?.text)
        assertTrue(panel.state.items.none { it is NoticeItem })

        panel.signIn(login, console = false)
        waitFor { login.url != null }
        assertEquals("https://claude.com/cai/oauth/authorize?code=true&state=fake", login.url)
        panel.sendLoginCode(login, "wrong")
        waitFor { login.status == LoginStatus.Failed }
        assertEquals("Login failed: invalid code", login.detail)

        panel.signIn(login, console = false)
        waitFor { login.url != null }
        panel.sendLoginCode(login, "good-code")
        waitFor { panel.state.items.any { it is AssistantTextItem } && !panel.state.busy }

        assertEquals(listOf("hola"), panel.state.items.filterIsInstance<UserItem>().map { it.text })
        assertTrue(panel.state.items.filterIsInstance<NoticeItem>().any { it.text == "Signed in as dev@example.com." })
        assertTrue((panel.state.items.last { it is AssistantTextItem } as AssistantTextItem).text.startsWith("echo: "))
        val chats = launches().filter { "-p" in it }
        assertEquals("the CLI starts again after signing in", 2, chats.size)
        assertFalse("the failed first message starts a fresh session", "--resume" in chats.last())
    }

    fun `test typed slash login shows the sign-in card instead of going to the CLI`() {
        type("/login")
        val login = panel.state.items.single() as LoginItem
        assertNull(login.retry)
        assertEquals(LoginStatus.Ready, login.status)
        assertTrue(sentTexts().isEmpty())
    }

    fun `test Insert at cursor puts code at the caret, replacing the selection`() {
        myFixture.configureByText("Main.kt", "val a = <selection>old</selection>\n")
        panel.insertCode("listOf(1, 2)")
        waitFor { myFixture.editor.document.text == "val a = listOf(1, 2)\n" }
    }

    fun `test a pasted image becomes an image chip`() {
        val image = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB)
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.imageFlavor
            override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any =
                if (isDataFlavorSupported(flavor)) image else throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        })
        assertTrue(panel.pasteAttachments())
        waitFor { panel.state.attachments.any { it is ImageAttachment } }

        type("what is this?")
        waitFor { sentTexts().isNotEmpty() }
        val content = received().first { it.get("type").asString == "user" }.getAsJsonObject("message").getAsJsonArray("content")
        assertEquals("image", content[0].asJsonObject.get("type").asString)
        assertEquals("image/png", content[0].asJsonObject.getAsJsonObject("source").get("media_type").asString)
    }

    fun `test picking a command in the strip opens its card and scrolls to it`() {
        val command = ToolItem("t-cmd", "Bash", "./gradlew test", "{}", null, null, false)
        panel.state.items += command
        panel.showCommand(command)
        assertTrue(command.expanded)
        assertEquals("t-cmd", panel.state.scrollTo)
        panel.toggleActivity()
        assertTrue(panel.state.activityExpanded)
    }

    fun `test Stop on a background task asks the CLI to stop it`() {
        type("hello")
        waitFor { !panel.state.busy && sentTexts().isNotEmpty() }
        val task = BackgroundTask("b123", "local_bash", "sleep 30")
        panel.state.background += task
        panel.stopBackground(task)
        waitFor { received().any { it.getAsJsonObject("request")?.get("subtype")?.asString == "stop_task" } }
        val request = received().last { it.getAsJsonObject("request")?.get("subtype")?.asString == "stop_task" }
        assertEquals("b123", request.getAsJsonObject("request").get("task_id").asString)
    }

    fun `test the changes strip lists this chat's pending files, and accepting one clears it`() {
        val file = projectFile("Strip.kt")
        val other = projectFile("Other.kt")
        // A change from somewhere else (another chat, or Edit with Claude) isn't this chat's.
        ReviewService.getInstance(project).recordEdit(other.path, "val y = 1\n", fromChat = false)
        other.writeText("val y = 2\n")

        type("EDIT ${file.path}")
        waitFor { !panel.state.busy && panel.state.changedFiles.isNotEmpty() }
        assertEquals(listOf("Strip.kt"), panel.state.changedFiles.map { it.name })
        assertEquals(1, panel.state.changedFiles.single().added)

        panel.acceptChanges(panel.state.changedFiles)
        waitFor { panel.state.changedFiles.isEmpty() }
        ReviewService.getInstance(project).state.find(ReviewService.key(other.path))?.let { ReviewService.getInstance(project).acceptFiles(listOf(it)) }
        dispatchFor(100)
    }

    fun `test editing a message after Claude changed files asks, and Undo puts the files back`() {
        val file = projectFile("Undo.kt")
        type("EDIT ${file.path}")
        waitFor { !panel.state.busy && file.readText() == "val x = \"new\"\n" }
        type("thanks")
        waitFor { !panel.state.busy && sentTexts().size == 2 }

        val asked = answerDialogs(Messages.YES)
        panel.editMessage(panel.state.items.filterIsInstance<UserItem>().first())
        panel.state.input.edit { replace(0, length, "never mind") }
        panel.send()
        waitFor { asked.size == 1 && file.readText() == "val x = \"old\"\n" }
        waitFor { !panel.state.busy && sentTexts().size == 3 }
        assertEquals(listOf("never mind"), panel.state.items.filterIsInstance<UserItem>().map { it.text })
    }

    fun `test choosing Keep Changes keeps the files and Cancel keeps the message`() {
        val file = projectFile("Keep.kt")
        type("EDIT ${file.path}")
        waitFor { !panel.state.busy && file.readText() == "val x = \"new\"\n" }
        val first = panel.state.items.filterIsInstance<UserItem>().first()

        val asked = answerDialogs(Messages.CANCEL)
        panel.retry(first)
        waitFor { asked.size == 1 }
        dispatchFor(200)
        assertEquals("Cancel changes nothing", 1, sentTexts().size)
        assertEquals("val x = \"new\"\n", file.readText())

        answerDialogs(Messages.NO, asked)
        panel.retry(first)
        waitFor { asked.size == 2 && sentTexts().size == 2 }
        assertEquals("val x = \"new\"\n", file.readText())
    }

    fun `test Restore checkpoint asks first, then puts the files back`() {
        val file = projectFile("Restore.kt")
        type("EDIT ${file.path}")
        waitFor { !panel.state.busy && file.readText() == "val x = \"new\"\n" }
        val message = panel.state.items.filterIsInstance<UserItem>().single()
        waitFor { message.restorable }

        val asked = answerDialogs(Messages.OK)
        panel.restoreCheckpoint(message)
        waitFor { asked.size == 1 && file.readText() == "val x = \"old\"\n" }
        waitFor { !message.restorable }
        assertTrue((panel.state.items.last() as NoticeItem).text.startsWith("Restored"))

        // Claude hears about it with the next message.
        type("ok")
        waitFor { sentTexts().size == 2 }
        assertTrue(sentTexts()[1].startsWith("(From the IDE: the user restored"))
    }

    /** A file in a folder the IDE may read, holding `val x = "old"`. */
    private fun projectFile(name: String): File {
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        return File(dir, name).apply { writeText("val x = \"old\"\n") }
    }

    /** Answers the next dialogs with [answer]; returns the messages they showed. */
    private fun answerDialogs(answer: Int, asked: MutableList<String> = mutableListOf()): MutableList<String> {
        TestDialogManager.setTestDialog({ message ->
            asked += message
            answer
        }, testRootDisposable)
        return asked
    }

    private fun launches(): List<List<String>> =
        File(dir, "args.jsonl").readLines().filter { it.isNotBlank() }.map { line -> JsonParser.parseString(line).asJsonArray.map { it.asString } }

    private fun List<String>.after(flag: String): String? = getOrNull(indexOf(flag) + 1)?.takeIf { flag in this }

    /** What the user does in the composer: type [text] and press Enter. */
    private fun type(text: String) {
        panel.suggest(text)
        panel.send()
        dispatchFor(50)
    }

    private fun release() = File(dir, "release").writeText("")

    private fun received(): List<JsonObject> =
        File(dir, "received.jsonl").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }
            ?.map { JsonParser.parseString(it).asJsonObject }.orEmpty()

    private fun sentTexts(): List<String> = received().filter { it.get("type").asString == "user" }.map {
        val content = it.getAsJsonObject("message").get("content")
        if (content.isJsonPrimitive) content.asString else content.toString()
    }

    private fun setModels(): List<String> = received()
        .mapNotNull { it.getAsJsonObject("request") }
        .filter { it.get("subtype").asString == "set_model" }
        .map { it.get("model").asString }

    private fun effortSettings() = received()
        .mapNotNull { it.getAsJsonObject("request") }
        .filter { it.get("subtype").asString == "apply_flag_settings" }
        .map { it.getAsJsonObject("settings").get("effortLevel") }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun dispatchFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }
}
