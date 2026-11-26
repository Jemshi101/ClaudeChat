package `in`.bleuphoenix.claudechat.ui

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.TimeUnit
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ide.BrowserUtil
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.SequentialTaskExecutor
import `in`.bleuphoenix.claudechat.cli.ClaudeLogin
import `in`.bleuphoenix.claudechat.cli.ClaudeSession
import `in`.bleuphoenix.claudechat.cli.CliCatalog
import `in`.bleuphoenix.claudechat.cli.InstructionFiles
import `in`.bleuphoenix.claudechat.cli.ReadOnlyCommands
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.history.ChatHistory
import `in`.bleuphoenix.claudechat.history.SessionInfo
import `in`.bleuphoenix.claudechat.history.TranscriptReplay
import `in`.bleuphoenix.claudechat.ide.FileMatch
import `in`.bleuphoenix.claudechat.ide.IdeBridge
import `in`.bleuphoenix.claudechat.edit.ApplyCode
import `in`.bleuphoenix.claudechat.ui.compose.MarkdownSegment
import `in`.bleuphoenix.claudechat.ide.KeymapBridge
import `in`.bleuphoenix.claudechat.plan.PlanFiles
import `in`.bleuphoenix.claudechat.plan.PlanService
import `in`.bleuphoenix.claudechat.review.Checkpoints
import `in`.bleuphoenix.claudechat.review.FileReview
import `in`.bleuphoenix.claudechat.review.ReviewService
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import `in`.bleuphoenix.claudechat.ui.compose.ChatActions
import `in`.bleuphoenix.claudechat.ui.compose.ChatScreen
import `in`.bleuphoenix.claudechat.ui.compose.ProvideModernMarkdown
import `in`.bleuphoenix.claudechat.ui.compose.ProvideChatFontScale
import `in`.bleuphoenix.claudechat.ui.compose.ChatAppearance
import `in`.bleuphoenix.claudechat.ui.compose.TaskLists
import `in`.bleuphoenix.claudechat.ui.compose.filesLabel
import org.jetbrains.annotations.VisibleForTesting
import com.intellij.openapi.options.ShowSettingsUtil
import `in`.bleuphoenix.claudechat.settings.McpServersConfigurable
import `in`.bleuphoenix.claudechat.settings.ClaudeChatConfigurable
import `in`.bleuphoenix.claudechat.voice.VoiceController
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import java.awt.datatransfer.Transferable
import java.io.File
import java.util.UUID
import javax.swing.JComponent
import javax.swing.Timer

/**
 * One chat tab: connects the [ClaudeSession], the [ChatState] and the Compose [ChatScreen]. [onTitle] names the tab;
 * [openInNewChat] continues a past chat in another tab when this one is already in use.
 */
class ChatPanel(
    private val project: Project,
    private val onTitle: (String) -> Unit = {},
    /** Whether the user can see this chat now (its tab selected, the tool window open, the IDE focused). */
    private val isWatched: () -> Boolean = { true },
    /** The chat's name, as its tab shows it. */
    private val chatTitle: () -> String = { "" },
    /** Brings this chat forward: the IDE, the tool window and its tab. */
    private val bringForward: () -> Unit = {},
    private val openInNewChat: (SessionInfo) -> Unit = {},
) : Disposable, ChatActions {

    @Volatile
    private var disposed = false

    @VisibleForTesting
    internal val state = ChatState()
    private val workDir = project.basePath ?: System.getProperty("user.home")

    // CLI requests run one at a time, in order (see [toCli]).
    private val cliRequests = SequentialTaskExecutor.createSequentialApplicationPoolExecutor("Claude CLI requests")
    private val ide = IdeBridge(project)
    private val plans = PlanService.getInstance(project)
    private val reviews = ReviewService.getInstance(project)
    private val context = ChatContext(project, state, this)

    // Files Claude changed in the current turn, for the "Files changed" card when it ends.
    private val turnEditedPaths = linkedSetOf<String>()

    // File contents before each message's changes, for "Restore checkpoint"; the message being answered now.
    private val checkpoints = Checkpoints()
    private var currentTurn: UserItem? = null

    // Things Claude should know with the next message (e.g. files restored to a checkpoint).
    private val notesForClaude = mutableListOf<String>()

    /** Debug mode's log file for code that runs on this computer, one per chat; Claude deletes it once it's fixed. */
    private val debugLogFile = File(workDir, ".claude/debug-${UUID.randomUUID().toString().take(6)}.log").path

    /** Whether Claude has been told how Debug mode works since the chat last switched to it. */
    private var debugExplained = false

    // Plans this chat registered with [plans]; removed when decided or when the chat closes.
    private val pendingPlanPaths = mutableSetOf<String>()

    // The readable copy of each plan, by the CLI's plan file; revisions of a plan reuse its copy.
    // The chat's plan file (see planFile) and the plans already written to it.
    private var chatPlanFile: File? = null
    private val writtenPlanRequests = HashSet<String>()

    private val reducer: ChatEventReducer = ChatEventReducer(
        state,
        onToolSucceeded = { item, result -> if (item.name in FILE_EDITING_TOOLS) item.filePath?.let { fileEdited(it, result) } },
        onTurnEnd = { ranShellCommand ->
            if (ranShellCommand) ide.refreshProject()
            devSteps.removeFirstOrNull()?.let(::runDevStep)
            if (turnEditedPaths.isNotEmpty()) {
                reducer.addChanges(turnEditedPaths.toList(), reviews.state)
                turnEditedPaths.clear()
                reviews.openTab(focus = false)
            }
            // After the turn's last items are added.
            ApplicationManager.getApplication().invokeLater(::sendNextQueued, ModalityState.nonModal()) { disposed }
        },
        // Like Cursor, a finished plan opens in an editor tab right away.
        onPlanReady = { openPlan(it) },
        onCommands = { CliCatalog.updateCommands(workDir, it) },
        onTaskCompleted = ::tickPlanTodo,
        onAlert = { notifier.alert(it) },
    )

    private val notifier = ChatNotifier(
        project,
        chatTitle = chatTitle,
        watching = isWatched,
        queued = { state.queued.isNotEmpty() },
        show = { itemKey ->
            bringForward()
            itemKey?.let { state.scrollTo = it }
        },
    )

    private val session: ClaudeSession = ClaudeSession(
        workDir = workDir,
        listener = object : ClaudeSession.Listener {
            override fun onLine(line: String) {
                val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return
                onEdt { if (!approveReadOnly(event) && !declineInAskMode(event)) reducer.handle(event) }
            }

            override fun onExit(exitCode: Int, stderr: String) = onEdt {
                reducer.endTurn()
                if (exitCode != 0) {
                    val details = stderr.trim().takeIf { it.isNotEmpty() }?.let { ":\n$it" }.orEmpty()
                    reducer.addNotice("Claude exited with code $exitCode$details", isError = true)
                }
                sendNextQueued()
            }
        },
    )

    // Streamed text is copied into the UI state a few times per frame-ish, not per token.
    private val flushTimer = Timer(STREAM_FLUSH_MS) { reducer.flush() }

    val component: JComponent = JewelComposePanel(focusOnClickInside = true) {
        ProvideChatFontScale {
            ProvideMarkdownStyling(project) {
                ProvideModernMarkdown { ChatScreen(state, this) }
            }
        }
    }.withSwingSizes()

    val preferredFocusComponent: JComponent get() = component

    init {
        Disposer.register(this, session)
        KeymapBridge.install(component, this)
        state.review = reviews.state

        // Start in the configured mode; the session isn't running yet, so this only sets its launch flag.
        val defaultMode = PermissionMode.fromCli(
            ClaudeChatSettings.options.permissionMode ?: ClaudeChatSettings.DEFAULT_PERMISSION_MODE,
        )
        state.mode = defaultMode
        session.setPermissionMode(defaultMode.cliId)
        state.model = ClaudeChatSettings.options.model
        state.effort = ClaudeChatSettings.options.effort
        ChatAppearance.fontPercent = ClaudeChatSettings.options.chatFontPercent
        loadCatalog()
        state.shortcutTips = shortcutTips()
        background {
            val files = InstructionFiles.resolve(workDir, InstructionFiles.names(ClaudeChatSettings.options.instructionFiles))
            if (files.isNotEmpty()) onEdt { state.instructionsNote = "Instructions: ${files.joinToString { it.name }} (instead of CLAUDE.md)" }
        }

        flushTimer.start()
        loadRecentChats()
        state.focusRequests++
        LOG.info("Chat panel created for ${project.name}")
        ApplicationManager.getApplication().invokeLater(::sendDevAutoPrompt, ModalityState.nonModal(), project.disposed)
    }

    /**
     * Development aid: `runIde -PautoPrompt=<text>` sets [DEV_AUTO_PROMPT], and the first chat sends that text in
     * Plan mode, or in the mode given by `-PautoMode=<id>` ([DEV_AUTO_MODE]), with the files in `-PautoAttach=<paths>`
     * ([DEV_AUTO_ATTACH], separated by the path separator) attached,
     * Plan mode, so the plan flow can be exercised in the sandbox without typing. Unset in normal use.
     */
    private fun sendDevAutoPrompt() {
        val prompt = System.getProperty(DEV_AUTO_PROMPT)?.takeIf { it.isNotBlank() } ?: return
        if (!devAutoPromptSent.compareAndSet(false, true)) return
        LOG.info("Sending development auto-prompt")
        selectMode(PermissionMode.fromCli(System.getProperty(DEV_AUTO_MODE) ?: "plan"))
        System.getProperty(DEV_AUTO_ATTACH)?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.map(::File)?.let(context::addFiles)
        // "type:<text>" only types it, e.g. to look at the "/" or "@" list.
        prompt.removePrefix("type:").takeIf { it != prompt }?.let {
            suggest(it)
            return
        }
        val sendPrompt = {
            suggest(prompt)
            send()
        }
        // -PautoResume: continue the project's most recent chat first.
        if (System.getProperty(DEV_AUTO_RESUME) != null) {
            background {
                val latest = project.basePath?.let(ChatHistory::list)?.firstOrNull()
                onEdt { if (latest != null) load(latest, then = sendPrompt) else sendPrompt() }
            }
        } else {
            sendPrompt()
        }
    }

    // -PautoSteps: further development messages, one per finished turn.
    private val devSteps: MutableList<String> = System.getProperty(DEV_AUTO_STEPS)?.split("||")?.map { it.trim() }?.toMutableList()
        ?: mutableListOf()

    /**
     * A development step: "edit:<text>" edits the last message to <text>; "model:<value>" picks a model and sends the
     * next step; anything else is sent as a message.
     */
    private fun runDevStep(step: String): Unit = ApplicationManager.getApplication().invokeLater({
        LOG.info("Development step: $step")
        step.removePrefix("model:").takeIf { it != step }?.let { model ->
            selectModel(model)
            devSteps.removeFirstOrNull()?.let(::runDevStep)
            return@invokeLater
        }
        val edit = step.removePrefix("edit:").takeIf { it != step }
        if (edit != null) state.items.lastOrNull { it is UserItem }?.let { editMessage(it as UserItem) }
        suggest(edit ?: step)
        send()
        // Handled without a turn (e.g. "/model haiku"): go on to the next step.
        if (!state.busy) devSteps.removeFirstOrNull()?.let(::runDevStep)
    }, ModalityState.nonModal())

    /**
     * Shows Claude's edit in open editors right away and, when the CLI reported the text before the edit, adds the
     * file to the review tab.
     */
    private fun fileEdited(path: String, result: JsonObject?) {
        val baseline = baselineOf(result)
        if (baseline == null || PlanFiles.isPlan(path)) {
            ide.refreshFile(path)
            return
        }
        reviews.recordEdit(path, baseline.text)
        ReviewService.key(path).let { if (it !in state.editedFiles) state.editedFiles += it }
        turnEditedPaths += ReviewService.key(path)
        currentTurn?.let { checkpoints.record(it.key, ReviewService.key(path), baseline.text) }
        updateRestorable()
    }

    private fun updateRestorable() {
        state.items.filterIsInstance<UserItem>().forEach { it.restorable = checkpoints.hasChangesFrom(it.key) }
    }

    // ---- ChatActions ----------------------------------------------------------------------------------------------

    override fun send() {
        if (state.input.text.isBlank() && state.attachments.isEmpty()) {
            // As in Cursor, Enter in an empty box is Proceed once Claude has given reproduction steps.
            if (DebugMode.pendingSteps(state) != null) proceedDebugging()
            return
        }
        val editing = state.editing
        if (editing == null) {
            if (switchModelFromInput() || signInFromInput()) return
            if (state.busy) queueFromInput() else sendFromInput()
            return
        }
        replaceFrom(editing) {
            state.editing = null
            sendFromInput()
        }
    }

    private fun sendFromInput() = takeInput()?.let { (text, attachments) -> sendMessage(text, attachments) }

    override fun sendNow() {
        if (!state.busy || state.editing != null || state.input.text.isBlank()) return send()
        // A slash command only runs as a message of its own, so it waits.
        if (slashCommand(state.input.text.toString()) != null) return send()
        takeInput()?.let { (text, attachments) -> sendWhileWorking(text, attachments) }
    }

    override fun stopAndSend() {
        if (!state.busy || state.editing != null || state.input.text.isBlank()) return send()
        takeInput()?.let { (text, attachments) -> stopThenSend(QueuedMessage(state.newKey(), text, attachments)) }
    }

    override fun sendQueuedNow(message: QueuedMessage, stopFirst: Boolean) {
        if (!state.queued.remove(message)) return
        when {
            !state.busy -> sendMessage(message.text, message.attachments)
            stopFirst -> stopThenSend(message)
            slashCommand(message.text) != null -> state.queued.add(0, message)
            else -> sendWhileWorking(message.text, message.attachments)
        }
    }

    /**
     * Sends a message while Claude works. The CLI hands it to Claude at its next step, within the same turn (or as a
     * turn of its own if Claude finishes first; see ChatEventReducer.addMessageWhileWorking).
     */
    private fun sendWhileWorking(text: String, attachments: List<Attachment>) {
        reducer.addMessageWhileWorking(text, attachments)
        val message = ContextMessage.build(text, attachments, context.activeFileForMessage())
        val images = attachments.filterIsInstance<ImageAttachment>().map { it.mediaType to it.base64 }
        ide.saveAllDocumentsThen { toCli { session.sendUserMessage(message, images) } }
    }

    /** Stops Claude, then sends [message] first once the turn has ended; the rest of the queue follows as usual. */
    private fun stopThenSend(message: QueuedMessage) {
        state.queued.add(0, message)
        reducer.requestStop()
        toCli { session.interrupt() }
    }

    /** While Claude works, a message waits its turn; it can still be removed or edited until it's sent. */
    private fun queueFromInput() = takeInput()?.let { (text, attachments) -> state.queued += QueuedMessage(state.newKey(), text, attachments) }

    /** The typed message and its context, clearing the message box; null if there's neither. */
    private fun takeInput(): Pair<String, List<Attachment>>? {
        val text = state.input.text.toString().trim()
        if (text.isEmpty() && state.attachments.isEmpty()) return null
        val attachments = state.attachments.toList()
        state.input.clearText()
        state.attachments.clear()
        state.mention = null
        state.slash = null
        return text to attachments
    }

    private fun sendNextQueued() {
        if (state.busy || state.editing != null) return
        val next = state.queued.removeFirstOrNull() ?: return
        sendMessage(next.text, next.attachments)
    }

    override fun unqueue(message: QueuedMessage, edit: Boolean) {
        if (!state.queued.remove(message) || !edit) return
        restoreToInput(listOf(message))
    }

    /** Puts [messages] back in the message box, before anything already typed there. */
    private fun restoreToInput(messages: List<QueuedMessage>) {
        val typed = state.input.text.toString().trim()
        state.input.setTextAndPlaceCursorAtEnd((messages.map { it.text } + typed).filter { it.isNotEmpty() }.joinToString("\n\n"))
        state.attachments.addAll(0, messages.flatMap { it.attachments }.filter { new -> state.attachments.none { it.key == new.key } })
        state.focusRequests++
    }

    private fun sendMessage(text: String, attachments: List<Attachment>) {
        notifier.clear()
        if (state.items.isEmpty()) onTitle(text.lineSequence().first())
        reducer.addUserMessage(text, attachments)
        currentTurn = (state.items.last() as UserItem).also { checkpoints.startTurn(it.key) }
        if (slashCommand(text) != null) {
            // The CLI runs it only when the message starts with it, so nothing goes before it, and attached files
            // become its arguments.
            val mentions = attachments.filterIsInstance<FileAttachment>().joinToString(" ", transform = ContextMessage::mention)
            val command = listOf(text, mentions).filter { it.isNotEmpty() }.joinToString(" ")
            ide.saveAllDocumentsThen { toCli { session.sendUserMessage(command) } }
            return
        }
        // Rejected or restored changes are mentioned so Claude doesn't assume the files still hold what it wrote.
        val withContext = ContextMessage.build(text, attachments, context.activeFileForMessage())
        val notes = listOfNotNull(reviews.takeRejectionNote(), modeNote()) + notesForClaude
        notesForClaude.clear()
        val message = (notes + withContext).joinToString("\n\n")
        val images = attachments.filterIsInstance<ImageAttachment>().map { it.mediaType to it.base64 }
        // Send after open editors are saved, so Claude reads the user's latest edits.
        ide.saveAllDocumentsThen { toCli { session.sendUserMessage(message, images) } }
    }

    /** What a message in Ask or Debug mode tells Claude about the mode. */
    private fun modeNote(): String? = when (state.mode.id) {
        ASK -> ASK_MODE_NOTE
        DebugMode.ID -> if (debugExplained) DebugMode.REMINDER else DebugMode.note(debugLogFile).also { debugExplained = true }
        else -> null
    }

    override fun proceedDebugging() {
        if (DebugMode.pendingSteps(state) == null) return
        val typed = takeInput()
        sendMessage(typed?.first ?: DebugMode.PROCEED_MESSAGE, typed?.second.orEmpty())
    }

    /** Has Claude remove its logging, in Agent mode as in Cursor. */
    override fun markFixed() {
        if (DebugMode.pendingSteps(state) == null) return
        val typed = takeInput()
        selectMode(PermissionMode.CHOICES.first())
        sendMessage(listOfNotNull(DebugMode.MARK_FIXED_MESSAGE, typed?.first).joinToString(" "), typed?.second.orEmpty())
    }

    /** Adds a file, or lines of it, to the next message's context (Ask Claude About Selection). */
    fun attachFile(path: String, lines: IntRange?, isDirectory: Boolean = false) =
        context.attach(FileAttachment(state.newKey(), path, isDirectory, lines))

    override fun removeAttachment(attachment: Attachment) = context.remove(attachment)

    override fun expandAttachment(attachment: TextAttachment) = context.expand(attachment)

    /** Adds output from the Run console or Logcat (or other text) to the next message's context, as a chip. */
    fun attachText(source: String, text: String) = context.attachText(source, text)

    /**
     * Sends [text] with [attachments], as from the right-click menus: right away, or queued while Claude works (or
     * while a message is being edited), like a message typed then.
     */
    fun ask(text: String, attachments: List<Attachment>) {
        if (state.busy || state.editing != null) state.queued += QueuedMessage(state.newKey(), text, attachments)
        else sendMessage(text, attachments)
    }

    /** A fresh copy of a file attachment with this chat's own key, for context coming from outside the chat. */
    fun newFileAttachment(path: String, isDirectory: Boolean, lines: IntRange?) = FileAttachment(state.newKey(), path, isDirectory, lines)

    fun newTextAttachment(source: String, text: String) = TextAttachment(state.newKey(), source, text)

    fun focusInput() {
        state.focusRequests++
    }

    override fun compact() = ask(COMPACT, emptyList())

    override fun compactWithFocus() {
        val typed = state.input.text.toString().trim()
        val focus = typed.removePrefix(COMPACT).trim()
        state.input.setTextAndPlaceCursorAtEnd("$COMPACT $focus".trimEnd() + " ")
        state.focusRequests++
    }

    override fun newChat() = ClaudeChatToolWindowFactory.withChat(project, newChat = true) { it.focusInput() }

    // ---- Voice input ----------------------------------------------------------------------------------------------

    private val voice by lazy { VoiceController(state, ::background, ::onEdt) }

    override fun toggleVoice() = voice.toggle()

    override fun cancelVoice() = voice.cancel()

    override fun openVoiceSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeChatConfigurable::class.java)

    // ---- Message actions ------------------------------------------------------------------------------------------

    override fun copyText(text: String) = ide.copyToClipboard(text)

    override fun insertCode(code: String) = ide.insertAtCaret(code) {
        reducer.addNotice("Open a file in the editor to insert code there.", isError = false)
    }

    override fun applyCode(block: MarkdownSegment.Code) = ApplyCode.start(project, block.code, block.language, block.path) { message ->
        reducer.addNotice(message, isError = true)
    }

    override fun editMessage(item: UserItem) {
        if (!canRewrite(item)) return
        state.editing = item
        state.input.setTextAndPlaceCursorAtEnd(item.text)
        state.attachments.clear()
        state.attachments += item.attachments
        state.focusRequests++
    }

    override fun cancelEdit() {
        state.editing = null
        state.input.clearText()
        state.attachments.clear()
    }

    override fun retry(item: UserItem) {
        if (!canRewrite(item)) return
        replaceFrom(item) { sendMessage(item.text, item.attachments) }
    }

    private fun canRewrite(item: UserItem): Boolean {
        if (state.busy) {
            reducer.addNotice("Wait for Claude to finish, or stop it, first.", isError = false)
            return false
        }
        return item in state.items
    }

    /**
     * Removes [item] and everything after it, from the view and from the CLI session (continued from a copy that
     * ends just before it), then runs [then]. If Claude changed files since, asks whether to undo those changes too.
     */
    private fun replaceFrom(item: UserItem, then: () -> Unit) {
        val files = checkpoints.restoreTo(item.key)
        val proceed = {
            val index = state.items.indexOf(item)
            if (index >= 0) state.items.subList(index, state.items.size).clear()
            checkpoints.dropFrom(item.key)
            updateRestorable()
            reducer.continueAfter(item.forkPoint)
            toCli {
                session.forkAt(item.forkPoint)
                onEdt(then)
            }
        }
        if (files.isEmpty()) {
            proceed()
            return
        }
        // A dialog from a Compose click or key handler needs invokeLater (see IdeBridge).
        ApplicationManager.getApplication().invokeLater({
            val names = files.keys.joinToString("\n") { "  " + it.removePrefix("${project.basePath}/") }
            val answer = Messages.showYesNoCancelDialog(
                project,
                "Claude changed these files after this message. Undo those changes too?\n\n$names",
                "Replace Message",
                "Undo Changes",
                "Keep Changes",
                "Cancel",
                Messages.getQuestionIcon(),
            )
            when (answer) {
                Messages.YES -> reviews.restore(files) { proceed() }
                Messages.NO -> proceed()
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    // ---- History --------------------------------------------------------------------------------------------------

    private fun loadRecentChats() = background {
        val sessions = project.basePath?.let(ChatHistory::list).orEmpty()
        onEdt { state.recent = sessions }
    }

    override fun openHistory() = background {
        val sessions = project.basePath?.let(ChatHistory::list).orEmpty()
        onEdt {
            val open = state.history
            if (open != null) open.sessions = sessions else state.history = HistoryState(sessions)
        }
    }

    override fun closeHistory() {
        state.history = null
        state.focusRequests++
    }

    override fun openSession(session: SessionInfo) {
        state.history = null
        if (session.id == this.session.sessionId) return
        // A chat that has started can't switch sessions; the past chat gets its own tab.
        if (state.items.isNotEmpty() || this.session.sessionId != null) {
            openInNewChat(session)
            return
        }
        load(session)
    }

    /** Shows a past chat's transcript and continues it with `--resume` from the next message. */
    fun load(session: SessionInfo, then: () -> Unit = {}) {
        onTitle(session.title)
        background {
            val entries = ChatHistory.load(session.file)
            onEdt {
                reducer.continueAfter(TranscriptReplay.replay(entries, state))
                this.session.resume(session.id)
                state.focusRequests++
                LOG.info("Reopened chat ${session.id} (${state.items.size} items)")
                then()
            }
        }
    }

    override fun renameSession(session: SessionInfo, title: String) = background {
        ChatHistory.rename(session, title)
        if (session.id == this.session.sessionId) onEdt { onTitle(title) }
        openHistory()
        loadRecentChats()
    }

    override fun deleteSession(session: SessionInfo) {
        // A dialog from a Compose click handler needs invokeLater (see IdeBridge).
        ApplicationManager.getApplication().invokeLater({
            if (session.id == this.session.sessionId) {
                Messages.showInfoMessage(project, "This chat is open here. Close its tab first.", "Delete Chat")
                return@invokeLater
            }
            val answer = Messages.showOkCancelDialog(
                project,
                "Delete \u201c${session.title}\u201d? Its transcript is removed and it can't be continued.",
                "Delete Chat",
                "Delete",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.OK) return@invokeLater
            background {
                ChatHistory.delete(session)
                openHistory()
                loadRecentChats()
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    override fun restoreCheckpoint(item: UserItem) {
        if (state.busy) {
            reducer.addNotice("Wait for Claude to finish, or stop it, before restoring a checkpoint.", isError = false)
            return
        }
        val files = checkpoints.restoreTo(item.key)
        if (files.isEmpty()) return
        // A dialog from a Compose click handler needs invokeLater (see IdeBridge).
        ApplicationManager.getApplication().invokeLater({
            val names = files.keys.joinToString("\n") { "  " + it.removePrefix("${project.basePath}/") }
            val answer = Messages.showOkCancelDialog(
                project,
                "Put these files back as they were before this message?\n\n$names\n\n" +
                    "Changes made by shell commands aren't restored. Each file's restore can be undone in its editor.",
                "Restore Checkpoint",
                "Restore",
                "Cancel",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.OK) return@invokeLater
            reviews.restore(files) {
                checkpoints.dropFrom(item.key)
                updateRestorable()
                val label = filesLabel(files.size)
                reducer.addNotice("Restored $label to before \u201c${item.text.lineSequence().first().take(60)}\u201d.", isError = false)
                notesForClaude += "(From the IDE: the user restored $label to how they were before their message " +
                    "\u201c${item.text.take(200)}\u201d, undoing your later changes to: " +
                    files.keys.joinToString(", ") { it.removePrefix("${project.basePath}/") } +
                    ". Read them again before editing them.)"
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    override fun includeActiveFile(included: Boolean) {
        state.activeFileIncluded = included
    }

    override fun mentionChanged(query: String?, start: Int) = context.mentionChanged(query, start)

    override fun moveMentionSelection(delta: Int) = context.moveMentionSelection(delta)

    override fun pickMention(match: FileMatch) = context.pickMention(match)

    override fun pasteAttachments(): Boolean = context.paste()

    override fun canDrop(transferable: Transferable): Boolean = context.canDrop(transferable)

    override fun drop(transferable: Transferable): Boolean = context.add(transferable)

    override fun openMcpSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, McpServersConfigurable::class.java)

    override fun signInToMcp(name: String) = toCli {
        session.request("mcp_authenticate", { addProperty("serverName", name) }) { response ->
            onEdt {
                val error = controlError(response)
                if (error != null) {
                    reducer.addNotice("Couldn't sign in to $name: $error", isError = true)
                    return@onEdt
                }
                val url = response.getAsJsonObject("response")?.get("authUrl")?.takeIf { it.isJsonPrimitive }?.asString
                if (url != null) {
                    openInBrowser(url)
                    reducer.addNotice("Finish signing in to $name in your browser; the chat connects to it afterwards.", isError = false)
                }
                watchMcp(name, MCP_WATCH_TRIES)
            }
        }
    }

    override fun reconnectMcp(name: String) = toCli {
        session.request("mcp_reconnect", { addProperty("serverName", name) }) { response ->
            onEdt {
                controlError(response)?.let { reducer.addNotice("Couldn't reconnect $name: $it", isError = true) }
                refreshMcp()
            }
        }
    }

    /** Opens MCP sign-in pages; tests replace it. */
    internal var openInBrowser: (String) -> Unit = { BrowserUtil.browse(it) }

    /** The error in a control [response], or null when it succeeded. */
    private fun controlError(response: JsonObject): String? =
        if (response.get("subtype")?.asString == "error") response.get("error")?.takeIf { it.isJsonPrimitive }?.asString ?: "unknown error" else null

    /** Updates the MCP indicator from `mcp_status`, then calls [then] with the servers. */
    private fun refreshMcp(then: (List<McpStatus>) -> Unit = {}) = toCli {
        session.request("mcp_status") { response ->
            val servers = response.getAsJsonObject("response")?.getAsJsonArray("mcpServers")?.let(McpStatus::list) ?: return@request
            onEdt {
                state.mcpServers = servers
                then(servers)
            }
        }
    }

    /** Checks [name] every few seconds while sign-in may still be finishing in the browser. */
    private fun watchMcp(name: String, triesLeft: Int) {
        refreshMcp { servers ->
            val status = servers.firstOrNull { it.name == name }
            if (triesLeft <= 1 || disposed || status == null || !(status.needsAuth || status.status == "pending")) return@refreshMcp
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ onEdt { watchMcp(name, triesLeft - 1) } }, MCP_WATCH_SECONDS, TimeUnit.SECONDS)
        }
    }

    override fun stop() {
        // Stopping stops everything: queued messages go back to the message box instead of being sent.
        if (state.queued.isNotEmpty()) {
            restoreToInput(state.queued.toList())
            state.queued.clear()
        }
        reducer.requestStop()
        toCli { session.interrupt() }
    }

    /** "⌘⇧J focuses this box · ⌥⌘K edits selected code", from the keymap as the user has it. */
    private fun shortcutTips(): String = listOfNotNull(
        KeymapUtil.getFirstKeyboardShortcutText("ClaudeChat.FocusChat").takeIf { it.isNotEmpty() }?.let { "$it focuses this box" },
        KeymapUtil.getFirstKeyboardShortcutText("ClaudeChat.InlineEdit").takeIf { it.isNotEmpty() }?.let { "$it edits selected code in place" },
        KeymapUtil.getFirstKeyboardShortcutText("ClaudeChat.ToggleActivity").takeIf { it.isNotEmpty() }?.let { "$it shows activity" },
    ).joinToString(" · ")

    // ---- Slash commands and models ----------------------------------------------------------------------------------

    /** The command [text] starts with, if it names one the CLI offers (so a path like /tmp/x isn't taken for one). */
    private fun slashCommand(text: String): SlashCommand? {
        if (!text.startsWith("/")) return null
        val name = text.drop(1).takeWhile { !it.isWhitespace() }
        return state.commands.firstOrNull { it.name == name || name in it.aliases }
    }

    /** Reads the CLI's models and commands for the picker and the `/` list (cached per project). */
    private fun loadCatalog() {
        CliCatalog.cached(workDir)?.let(::applyCatalog) ?: background { CliCatalog.load(workDir)?.let { onEdt { applyCatalog(it) } } }
    }

    private fun applyCatalog(catalog: CliCatalog.Catalog) {
        LOG.info("CLI offers ${catalog.models.size} models and ${catalog.commands.size} commands")
        state.models = catalog.models
        // A running chat may already have newer commands from the CLI.
        if (state.commands.isEmpty()) state.commands = catalog.commands
        // A "/" typed before the commands arrived lists them now.
        if (state.slash == null) ContextMessage.slashQuery(state.input.text, state.input.selection.end)?.let { slashChanged(it.first, it.second) }
    }

    override fun slashChanged(query: String?, start: Int) {
        state.slash = query?.let { SlashState(it, CliCatalog.match(state.commands, it), start = start) }?.takeIf { it.results.isNotEmpty() }
    }

    override fun moveSlashSelection(delta: Int) {
        val slash = state.slash ?: return
        state.slash = SlashState(slash.query, slash.results, (slash.selected + delta).mod(slash.results.size), slash.start)
    }

    override fun pickSlash(command: SlashCommand) {
        val slash = state.slash
        if (slash != null && slash.start > 0) {
            // Later in the message it stays where it was typed: the CLI doesn't run it, but Claude reads it there and
            // invokes the skill itself.
            val end = (slash.start + 1 + slash.query.length).coerceAtMost(state.input.text.length)
            val spaced = state.input.text.getOrNull(end)?.isWhitespace() == true
            state.input.edit {
                replace(slash.start, end, if (spaced) "/${command.name}" else "/${command.name} ")
                selection = TextRange(slash.start + command.name.length + 2)
            }
            state.slash = null
            state.focusRequests++
            return
        }
        val text = state.input.text.toString()
        val rest = text.drop(1).dropWhile { !it.isWhitespace() }.trimStart()
        state.input.setTextAndPlaceCursorAtEnd("/${command.name} $rest")
        state.slash = null
        state.focusRequests++
    }

    /**
     * `/model <name>` typed in the chat goes through the picker's `set_model` instead of the CLI's command, which is
     * ignored after a `set_model` when it names the model the chat was launched with (see docs/PROTOCOL.md).
     */
    private fun switchModelFromInput(): Boolean {
        val name = MODEL_COMMAND.matchEntire(state.input.text.trim())?.groupValues?.get(1) ?: return false
        val option = state.models.firstOrNull { it.value.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
            ?: return false
        takeInput()
        selectModel(option.value)
        reducer.addNotice("Model: ${CliCatalog.label(state.models, state.model)}", isError = false)
        return true
    }

    // ---- Signing in ------------------------------------------------------------------------------------------------

    private var login: ClaudeLogin? = null

    /** `/login`: the CLI can't run it in a chat, so the chat shows its own sign-in card. */
    private fun signInFromInput(): Boolean {
        if (!state.input.text.trim().toString().equals("/login", ignoreCase = true)) return false
        takeInput()
        state.items += LoginItem(state.newKey(), retry = null)
        return true
    }

    override fun signIn(item: LoginItem, console: Boolean) {
        login?.let(Disposer::dispose)
        item.status = LoginStatus.Waiting
        item.url = null
        item.detail = null
        lateinit var runner: ClaudeLogin
        runner = ClaudeLogin(
            workDir,
            onUrl = { url -> onEdt { item.url = url } },
            onExit = { exitCode, output ->
                val account = if (exitCode == 0) ClaudeLogin.account(workDir) else null
                onEdt { signedIn(item, runner, account, exitCode, output) }
            },
        )
        login = runner
        Disposer.register(this, runner)
        background {
            runCatching { runner.start(console) }.onFailure { error ->
                onEdt {
                    item.status = LoginStatus.Failed
                    item.detail = error.message
                }
            }
        }
    }

    override fun sendLoginCode(item: LoginItem, code: String) {
        val runner = login ?: return
        if (code.isNotBlank()) background { runner.sendCode(code) }
    }

    /** Ends [item]'s sign-in; once signed in, the CLI starts again (it reads the sign-in when it starts) and the message that failed goes again. */
    private fun signedIn(item: LoginItem, runner: ClaudeLogin, account: String?, exitCode: Int, output: String) {
        if (login === runner) login = null
        Disposer.dispose(runner)
        if (account == null) {
            item.status = LoginStatus.Failed
            item.detail = output.lines().lastOrNull { it.isNotBlank() && !it.contains("https://") }
                ?: "Sign-in didn't finish (exit code $exitCode)."
            return
        }
        item.status = LoginStatus.Done
        item.detail = "Signed in as $account."
        LOG.info("Signed in to Claude")
        val retry = item.retry?.takeIf { it in state.items && !state.busy }
        if (retry == null) {
            toCli { session.restart() }
            return
        }
        replaceFrom(retry) {
            reducer.addNotice("Signed in as $account.", isError = false)
            sendMessage(retry.text, retry.attachments)
        }
    }

    /**
     * A shell command that only reads files inside the project runs without asking, as Read and Grep do (see
     * [ReadOnlyCommands]). Returns whether [event] was such a request.
     */
    private fun approveReadOnly(event: JsonObject): Boolean {
        if (!ClaudeChatSettings.options.autoApproveReads || event.get("type")?.asString != "control_request") return false
        val request = event.getAsJsonObject("request") ?: return false
        if (request.get("subtype")?.asString != "can_use_tool") return false
        val tool = request.get("tool_name")?.asString ?: return false
        val input = request.getAsJsonObject("input") ?: return false
        fun field(name: String) = input.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        val what = when (tool) {
            "Bash" -> field("command")?.takeIf { ReadOnlyCommands.isReadOnlyInside(it, File(workDir)) }
            // Claude's file tools ask for files outside the project, e.g. a design it unzipped into /tmp.
            in READ_TOOLS -> {
                val paths = listOfNotNull(field("file_path"), field("notebook_path"), field("path"), field("pattern")?.takeIf { tool == "Glob" && isAbsolutePattern(it) })
                paths.takeIf { it.isNotEmpty() && it.all { p -> ReadOnlyCommands.isReadableInside(p, File(workDir)) } }?.joinToString()
            }
            else -> null
        } ?: return false
        val requestId = event.get("request_id")?.asString ?: return false
        LOG.info("Read-only $tool inside the project, allowed: ${what.lineSequence().first().take(200)}")
        toCli { session.answerPermission(requestId, true, input) }
        return true
    }

    private fun isAbsolutePattern(pattern: String) =
        pattern.startsWith("/") || pattern.startsWith("~") || pattern.startsWith("\\") || (pattern.length > 1 && pattern[1] == ':') ||
            pattern.split('/', '\\').any { it == ".." }

    /**
     * Ask mode: Claude may read and search, but a request to edit a file or run a command that needs approval is
     * declined without asking. Returns whether [event] was such a request.
     */
    private fun declineInAskMode(event: JsonObject): Boolean {
        if (state.mode.id != ASK || event.get("type")?.asString != "control_request") return false
        val request = event.getAsJsonObject("request") ?: return false
        if (request.get("subtype")?.asString != "can_use_tool") return false
        val tool = request.get("tool_name")?.asString ?: return false
        if (tool !in ASK_MODE_DECLINED) return false
        val requestId = event.get("request_id")?.asString ?: return false
        LOG.info("Ask mode: declined $tool")
        toCli { session.answerPermission(requestId, false, request.getAsJsonObject("input"), denyMessage = ASK_MODE_DENIAL) }
        return true
    }

    override fun selectModel(value: String) {
        state.model = value.takeUnless { it == CliCatalog.DEFAULT }
        // Like Cursor, the choice sticks: new chats start with it.
        ClaudeChatSettings.options.model = state.model
        toCli { session.setModel(value) }
    }

    override fun selectEffort(level: String?) {
        state.effort = level
        // Sticks like the model: new chats start with it.
        ClaudeChatSettings.options.effort = level
        toCli { session.setEffort(level) }
    }

    override fun selectMode(mode: PermissionMode) {
        if (mode.id != DebugMode.ID) debugExplained = false
        state.mode = mode
        toCli { session.setPermissionMode(mode.cliId) }
    }

    override fun answer(item: PermissionItem, allowed: Boolean, always: AlwaysAllowOption?) {
        if (item.outcome != null) return
        notifier.clear()
        reducer.answered(item, allowed, always)
        val updatedPermissions = always?.let { JsonArray().apply { add(it.suggestion) } }
        toCli { session.answerPermission(item.requestId, allowed, item.input, updatedPermissions) }
    }

    override fun review(item: PermissionItem) {
        item.input?.let { ide.showDiff(item.toolName, it) }
    }

    override fun showDiff(item: ToolItem) {
        item.input?.let { ide.showDiff(item.name, it) }
    }

    override fun openFile(path: String) = ide.openFile(path)

    override fun openLink(url: String) = ide.openLink(url)

    override fun suggest(prompt: String) {
        state.input.setTextAndPlaceCursorAtEnd(prompt)
        state.focusRequests++
    }

    override fun openPlan(item: PlanItem) {
        LOG.info("Opening plan \"${item.title}\"")
        background {
            val path = planFile(item).path
            onEdt {
                item.openedPath = path
                offerPlan(item)
                ide.openPlanTab(path) { reducer.addNotice(it, isError = true) }
            }
        }
    }

    /** The plan's tab offers what its card does: Build while the plan can be built, Keep planning while Claude waits. */
    private fun offerPlan(item: PlanItem) {
        val path = item.openedPath ?: return
        if (!item.buildable) {
            resolvePlan(item)
            return
        }
        val waiting = item.outcome == null
        plans.register(path, PlanService.PendingPlan(build = { buildPlan(item, it) }, revise = if (waiting) ({ revisePlan(item) }) else null))
        pendingPlanPaths += path
    }

    override fun buildPlan(item: PlanItem, autoAcceptEdits: Boolean) {
        if (!item.buildable) return
        notifier.clear()
        val waiting = item.outcome == null
        item.built = true
        reducer.answeredPlan(
            item,
            if (autoAcceptEdits) "Building · edits are accepted automatically" else "Building · asking before each edit",
            building = waiting,
        )
        resolvePlan(item)
        if (!waiting) return buildFromFile(item, autoAcceptEdits)
        background {
            ide.saveAndReadFile(planFile(item).path) { edited ->
                // Send the plan as it is now in the editor tab, including the user's edits.
                val input = item.input.deepCopy().apply { addProperty("plan", edited ?: item.planText) }
                val switchMode = if (autoAcceptEdits) JsonArray().apply { add(ACCEPT_EDITS_FOR_SESSION) } else null
                toCli { session.answerPermission(item.requestId, true, input, switchMode) }
            }
        }
    }

    /**
     * Builds a plan Claude no longer waits on (after Keep planning, or revised without presenting it again), as Cursor
     * does: leave plan mode and send a message asking Claude to implement the plan file, attached.
     */
    private fun buildFromFile(item: PlanItem, autoAcceptEdits: Boolean) {
        selectMode(PermissionMode.fromCli(if (autoAcceptEdits) "acceptEdits" else "default"))
        background {
            val path = planFile(item).path
            ide.saveAndReadFile(path) {
                val text = "${item.title}\n\n$BUILD_PLAN_MESSAGE"
                val attachments = listOf(FileAttachment(state.newKey(), path))
                if (state.busy) state.queued += QueuedMessage(state.newKey(), text, attachments) else sendMessage(text, attachments)
            }
        }
    }

    override fun revisePlan(item: PlanItem) {
        if (item.outcome != null) return
        notifier.clear()
        reducer.answeredPlan(item, "Keep planning · send your feedback below", building = false)
        // Still buildable, from its file.
        offerPlan(item)
        state.focusRequests++
        toCli { session.answerPermission(item.requestId, false, item.input, denyMessage = REVISE_PLAN_MESSAGE) }
    }

    override fun openReview() = reviews.openTab(focus = true)

    override fun showCommand(item: ToolItem) {
        item.expanded = true
        state.scrollTo = item.key
    }

    /** Opens or closes the activity strip (Claude Chat: Toggle Activity). */
    fun toggleActivity() {
        state.activityExpanded = !state.activityExpanded
    }

    override fun stopBackground(task: BackgroundTask) {
        if (task.status == BackgroundStatus.Running) toCli { session.stopTask(task.id) }
    }

    override fun openBackgroundOutput(task: BackgroundTask) {
        task.outputFile?.let(ide::openFile)
    }

    override fun openChangedFile(file: FileReview) = reviews.openAt(file, file.hunks.firstOrNull()?.currentStart ?: 0)

    override fun acceptChanges(files: List<FileReview>) = reviews.acceptFiles(files)

    override fun rejectChanges(files: List<FileReview>) = reviews.rejectFiles(files)

    private fun resolvePlan(item: PlanItem) {
        val path = synchronized(writtenPlanRequests) { chatPlanFile }?.path ?: return
        plans.resolve(path)
        pendingPlanPaths -= path
    }

    /**
     * The chat's plan file: one per chat, named after its first plan (see [PlanFiles.fileFor]), so a revised plan
     * replaces the text instead of adding another file, also when the chat is reopened later. Each plan is written once,
     * when first opened, and only while it's the latest, so reopening an older card doesn't undo a newer plan or the
     * user's edits. Call off the EDT; it writes the file.
     */
    private fun planFile(item: PlanItem): File = synchronized(writtenPlanRequests) {
        val plans = state.items.filterIsInstance<PlanItem>()
        val file = chatPlanFile ?: PlanFiles.fileFor((plans.firstOrNull() ?: item).title).also { chatPlanFile = it }
        if (item == plans.lastOrNull() && writtenPlanRequests.add(item.requestId)) {
            file.parentFile.mkdirs()
            file.writeText(item.planText)
        }
        file
    }


    /** Ticks the plan to-do that a task Claude completed stands for, in the chat's plan file and its open tab. */
    private fun tickPlanTodo(subject: String) {
        // A reopened chat knows its plan file once a card is opened; until then it's named after the first plan.
        val file = synchronized(writtenPlanRequests) { chatPlanFile }
            ?: state.items.filterIsInstance<PlanItem>().firstOrNull()?.let { PlanFiles.fileFor(it.title) }?.takeIf { it.isFile }
            ?: return
        ide.editFile(file.path, "Tick Plan To-do") { text ->
            TaskLists.match(TaskLists.tasks(text), subject)?.let { TaskLists.complete(text, it) } ?: text
        }
    }

    // ---- Threading ------------------------------------------------------------------------------------------------

    /** Runs slow work (files, history, CLI lookups) on a pooled thread and reports failures in the conversation. */
    private fun background(action: () -> Unit) = runReporting({ ApplicationManager.getApplication().executeOnPooledThread(it) }, action)

    /**
     * Talks to the CLI off the EDT, one request at a time in the order asked, so e.g. a model switch reaches the CLI
     * before the message sent right after it.
     */
    private fun toCli(action: () -> Unit) = runReporting({ cliRequests.execute(it) }, action)

    private fun runReporting(executor: (Runnable) -> Unit, action: () -> Unit) {
        executor {
            try {
                action()
            } catch (e: Exception) {
                LOG.warn("Claude CLI request failed", e)
                onEdt {
                    reducer.endTurn()
                    reducer.addNotice(e.message ?: e.toString(), isError = true)
                }
            }
        }
    }

    private fun onEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any()) { disposed }

    override fun dispose() {
        disposed = true
        voice.cancel()
        notifier.clear()
        flushTimer.stop()
        pendingPlanPaths.forEach(plans::resolve)
    }

    private companion object {
        /** Sign-in in the browser is watched for 5 minutes. */
        const val MCP_WATCH_SECONDS = 3L
        const val MCP_WATCH_TRIES = 100
        val LOG = logger<ChatPanel>()
        const val STREAM_FLUSH_MS = 50
        val READ_TOOLS = setOf("Read", "Glob", "Grep", "LS", "NotebookRead")
        const val DEV_AUTO_PROMPT = "claudechat.dev.autoPrompt"
        const val DEV_AUTO_MODE = "claudechat.dev.autoMode"
        const val DEV_AUTO_ATTACH = "claudechat.dev.autoAttach"
        const val DEV_AUTO_RESUME = "claudechat.dev.autoResume"
        const val DEV_AUTO_STEPS = "claudechat.dev.autoSteps"
        val devAutoPromptSent = java.util.concurrent.atomic.AtomicBoolean(false)
        val FILE_EDITING_TOOLS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")
        val MODEL_COMMAND = Regex("""/model\s+(\S+)""")

        /** A file's text before an edit; [text] is null when the edit created the file. */
        class Baseline(val text: String?)

        /** The baseline from an edit's `tool_use_result`, or null if the CLI didn't report one (e.g. NotebookEdit). */
        fun baselineOf(result: JsonObject?): Baseline? {
            if (result == null) return null
            if (result.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "create") return Baseline(null)
            return result.get("originalFile")?.takeIf { it.isJsonPrimitive }?.asString?.let(::Baseline)
        }

        const val REVISE_PLAN_MESSAGE =
            "The user wants to revise the plan before building. Stop and wait for their feedback; " +
                "don't present the plan again until they reply."

        const val ASK = "ask"

        const val COMPACT = "/compact"

        /** Tools that change files or run commands: declined in Ask mode when the CLI asks to run them. */
        val ASK_MODE_DECLINED = setOf("Edit", "MultiEdit", "Write", "NotebookEdit", "Bash", "PowerShell")

        /** Sent with each message in Ask mode. */
        const val ASK_MODE_NOTE =
            "(Ask mode: answer my question. You may read and search the code, but don't edit files or run commands " +
                "that change anything. If changes are needed, describe them.)"

        const val ASK_MODE_DENIAL =
            "The chat is in Ask mode, which is read-only: don't edit files or run commands. Answer from what you can " +
                "read, and describe any changes instead of making them."

        /** Cursor's request when a plan is built from its file. */
        const val BUILD_PLAN_MESSAGE =
            "Implement the plan as specified, it is attached for your reference. Do NOT edit the plan file itself."

        /** Permission update that switches the session to accept edits, sent with "Build". */
        val ACCEPT_EDITS_FOR_SESSION = JsonObject().apply {
            addProperty("type", "setMode")
            addProperty("mode", "acceptEdits")
            addProperty("destination", "session")
        }
    }
}
