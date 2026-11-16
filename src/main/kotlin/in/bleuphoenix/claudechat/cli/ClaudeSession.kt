package `in`.bleuphoenix.claudechat.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread

/**
 * One chat backed by a long-running `claude` process. The process starts on the first message and is
 * restarted with `--resume` if it exits, so the conversation continues.
 */
class ClaudeSession(private val workDir: String, private val listener: Listener) : Disposable {

    interface Listener {
        /** A raw stream-json line from the CLI. Called on the reader thread. */
        fun onLine(line: String)

        /** The CLI process exited. Called on the reader thread. */
        fun onExit(exitCode: Int, stderr: String)
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    var sessionId: String? = null
        private set

    // The process being replaced by [forkAt]: its exit is expected and not reported.
    @Volatile
    private var retired: Process? = null

    // Set by [forkAt]; used for the next start only.
    @Volatile
    private var forkAtMessage: String? = null

    /**
     * Continues from a copy of the conversation that ends at message [uuid] (null: from the start), dropping what
     * came after it, e.g. to edit an earlier message. Takes effect with the next message, in a new CLI process.
     */
    @Synchronized
    fun forkAt(uuid: String?) {
        restart()
        if (uuid == null) sessionId = null else forkAtMessage = uuid
    }

    /** Starts the CLI again with the next message, continuing the same session (e.g. after signing in). */
    @Synchronized
    fun restart() {
        process?.let { current ->
            retired = current
            runCatching { current.outputStream.close() }
            current.destroy()
        }
        process = null
    }

    /** Continues the CLI session [id] (a past chat) when the process starts. */
    fun resume(id: String) {
        check(process?.isAlive != true) { "The chat has already started" }
        sessionId = id
    }

    @Volatile
    private var permissionMode: String? = null

    /** `--model` value for this chat; null for the CLI default. */
    @Volatile
    private var model: String? = ClaudeChatSettings.options.model

    /** `--effort` level for this chat; null for the CLI default. */
    @Volatile
    private var effort: String? = ClaudeChatSettings.options.effort

    @Volatile
    private var disposed = false

    /** Sends a message; [images] (media type to base64 data) go first as image blocks, as the API recommends. */
    fun sendUserMessage(text: String, images: List<Pair<String, String>> = emptyList()) {
        write(JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply {
                addProperty("role", "user")
                if (images.isEmpty()) {
                    addProperty("content", text)
                } else {
                    add("content", JsonArray().apply {
                        images.forEach { (mediaType, data) ->
                            add(JsonObject().apply {
                                addProperty("type", "image")
                                add("source", JsonObject().apply {
                                    addProperty("type", "base64")
                                    addProperty("media_type", mediaType)
                                    addProperty("data", data)
                                })
                            })
                        }
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", text)
                        })
                    })
                }
            })
        })
    }

    /**
     * [updatedPermissions] are permission updates to apply with an allow ("Allow always", or switching mode when a
     * plan is approved). [denyMessage] is what Claude is told on a deny.
     */
    fun answerPermission(
        requestId: String,
        allow: Boolean,
        input: JsonObject?,
        updatedPermissions: JsonArray? = null,
        denyMessage: String = "The user denied this action.",
    ) {
        val decision = JsonObject().apply {
            if (allow) {
                addProperty("behavior", "allow")
                add("updatedInput", input ?: JsonObject())
                if (updatedPermissions != null) add("updatedPermissions", updatedPermissions)
            } else {
                addProperty("behavior", "deny")
                addProperty("message", denyMessage)
            }
        }
        write(JsonObject().apply {
            addProperty("type", "control_response")
            add("response", JsonObject().apply {
                addProperty("subtype", "success")
                addProperty("request_id", requestId)
                add("response", decision)
            })
        })
    }

    /**
     * Changes the permission mode (CLI `--permission-mode` values). A running session switches immediately;
     * otherwise the mode applies when the process starts.
     */
    fun setPermissionMode(mode: String) {
        permissionMode = mode
        if (process?.isAlive != true) return
        write(JsonObject().apply {
            addProperty("type", "control_request")
            addProperty("request_id", UUID.randomUUID().toString())
            add("request", JsonObject().apply {
                addProperty("subtype", "set_permission_mode")
                addProperty("mode", mode)
            })
        })
    }

    /**
     * Switches the model ([value] as `--model` takes it; [CliCatalog.DEFAULT] for the CLI default). A running session
     * switches from the next reply; otherwise it applies when the process starts.
     */
    fun setModel(value: String) {
        model = value.takeUnless { it == CliCatalog.DEFAULT }
        LOG.info("Model set to $value" + if (process?.isAlive == true) "" else " (applies when the chat starts)")
        if (process?.isAlive != true) return
        write(JsonObject().apply {
            addProperty("type", "control_request")
            addProperty("request_id", UUID.randomUUID().toString())
            add("request", JsonObject().apply {
                addProperty("subtype", "set_model")
                addProperty("model", value)
            })
        })
    }

    /**
     * Sets the effort level (null for the CLI default). A running session applies it from the next reply through
     * `apply_flag_settings`, which also takes "max"; otherwise it applies when the process starts.
     */
    fun setEffort(level: String?) {
        effort = level
        LOG.info("Effort set to ${level ?: "default"}" + if (process?.isAlive == true) "" else " (applies when the chat starts)")
        if (process?.isAlive != true) return
        write(JsonObject().apply {
            addProperty("type", "control_request")
            addProperty("request_id", UUID.randomUUID().toString())
            add("request", JsonObject().apply {
                addProperty("subtype", "apply_flag_settings")
                add("settings", JsonObject().apply { add("effortLevel", level?.let(::JsonPrimitive) ?: JsonNull.INSTANCE) })
            })
        })
    }

    /** Callbacks for control requests waiting for their `control_response`, by request id. */
    private val awaiting = java.util.concurrent.ConcurrentHashMap<String, (JsonObject) -> Unit>()

    /**
     * Sends a control request and calls [onResponse] (on the CLI's reader thread) with its `response` object:
     * `subtype` "success" with the answer in `response`, or "error" with `error`. Without a running process it's
     * answered at once with an error.
     */
    fun request(subtype: String, fill: JsonObject.() -> Unit = {}, onResponse: (JsonObject) -> Unit) {
        if (process?.isAlive != true) {
            onResponse(JsonObject().apply { addProperty("subtype", "error"); addProperty("error", "The chat isn't running.") })
            return
        }
        val id = UUID.randomUUID().toString()
        awaiting[id] = onResponse
        write(JsonObject().apply {
            addProperty("type", "control_request")
            addProperty("request_id", id)
            add("request", JsonObject().apply { addProperty("subtype", subtype); fill() })
        })
    }

    /** Hands a `control_response` [line] to the request waiting for it. */
    private fun answerRequest(line: String) {
        if (awaiting.isEmpty() || !line.contains("\"control_response\"")) return
        val response = runCatching { JsonParser.parseString(line).asJsonObject.getAsJsonObject("response") }.getOrNull() ?: return
        val id = response.get("request_id")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        awaiting.remove(id)?.let { callback -> runCatching { callback(response) }.onFailure { LOG.warn("Control response handler failed", it) } }
    }

    /** Stops the current turn; the session stays open. Does nothing when no process is running. */
    fun interrupt() {
        if (process?.isAlive != true) return
        write(JsonObject().apply {
            addProperty("type", "control_request")
            addProperty("request_id", UUID.randomUUID().toString())
            add("request", JsonObject().apply { addProperty("subtype", "interrupt") })
        })
    }

    /** Stops a background task (a shell command or agent run in the background); see docs/PROTOCOL.md. */
    fun stopTask(taskId: String) {
        if (process?.isAlive != true) return
        write(JsonObject().apply {
            addProperty("type", "control_request")
            addProperty("request_id", UUID.randomUUID().toString())
            add("request", JsonObject().apply {
                addProperty("subtype", "stop_task")
                addProperty("task_id", taskId)
            })
        })
    }

    @Synchronized
    private fun write(message: JsonObject) {
        val stdin = ensureStarted().outputStream
        stdin.write((message.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        stdin.flush()
    }

    private fun ensureStarted(): Process {
        check(!disposed) { "Chat is closed" }
        process?.takeIf { it.isAlive }?.let { return it }

        val executable = ClaudeCli.findExecutable()
            ?: throw IOException(
                "Could not find the claude CLI. Install Claude Code, or set its path in Settings → Tools → Claude Chat.",
            )
        val options = ClaudeChatSettings.options
        val prompt = listOfNotNull(
            ClaudeCli.EMPHASIS_PROMPT.takeIf { options.emphasisInReplies },
            ClaudeCli.PLAN_DIAGRAM_PROMPT.takeIf { options.diagramsInPlans },
            ClaudeCli.PLAN_TODOS_PROMPT.takeIf { options.todosInPlans },
            options.systemPromptAppend?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString("\n\n").ifEmpty { null }
        val fork = forkAtMessage
        val tools = TaskTools.toolsArgument(options.taskList, options.builtInTools, options.builtInToolsVersion, options.lastCliVersion)
        val commandLine = ClaudeCli.commandLine(executable, workDir, sessionId, permissionMode, model, null, fork, tools, effort, options.maxTurns)
        val instructions = InstructionFiles.apply(commandLine, workDir, options.instructionFiles, prompt)
        if (instructions.isNotEmpty()) LOG.info("Instructions instead of CLAUDE.md: ${instructions.joinToString { it.path }}")
        // API keys for MCP servers, kept in the keychain; the config refers to them by variable (see McpKeys).
        runCatching { commandLine.withEnvironment(McpKeys.environment(workDir)) }.onFailure { LOG.warn("Couldn't read MCP keys", it) }
        if (options.syncCursorCommands) CursorCommands.syncAll().forEach { LOG.info("Copied a Cursor command to $it") }
        val started = commandLine.createProcess()
        process = started
        forkAtMessage = null

        val stderr = StringBuilder()
        val stderrReader = thread(name = "claude-stderr", isDaemon = true) {
            started.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                synchronized(stderr) {
                    stderr.appendLine(line)
                    if (stderr.length > MAX_STDERR) stderr.delete(0, stderr.length - MAX_STDERR)
                }
            }
        }
        thread(name = "claude-stdout", isDaemon = true) {
            started.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                captureSessionId(line, learnTools = tools == null)
                answerRequest(line)
                listener.onLine(line)
            }
            val exitCode = started.waitFor()
            // Requests to this process won't be answered now.
            awaiting.clear()
            stderrReader.join(STDERR_JOIN_MS)
            if (!disposed && retired !== started) listener.onExit(exitCode, synchronized(stderr) { stderr.toString() })
        }
        return started
    }

    /** Reads the session id from `init`, and the CLI's version and (when started with defaults) its tool set. */
    private fun captureSessionId(line: String, learnTools: Boolean) {
        if (!line.startsWith("{\"type\":\"system\",\"subtype\":\"init\"")) return
        val init = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return
        init.get("session_id")?.asString?.let { sessionId = it }
        val version = init.get("claude_code_version")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val options = ClaudeChatSettings.options
        options.lastCliVersion = version
        if (learnTools) {
            val tools = init.getAsJsonArray("tools")?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString } ?: return
            options.builtInTools = TaskTools.builtIn(tools)
            options.builtInToolsVersion = version
        }
    }

    override fun dispose() {
        disposed = true
        process?.let { p ->
            runCatching { p.outputStream.close() }
            p.destroy()
        }
        process = null
    }

    private companion object {
        val LOG = logger<ClaudeSession>()
        const val MAX_STDERR = 4000
        const val STDERR_JOIN_MS = 1000L
    }
}
