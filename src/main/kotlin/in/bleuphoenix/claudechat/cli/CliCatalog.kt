package `in`.bleuphoenix.claudechat.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A model the CLI offers (`initialize` → `models`). [value] is what `--model` and `set_model` take; [effortLevels] are
 * the `--effort` levels it supports, empty when it has none.
 */
data class ModelOption(
    val value: String,
    val displayName: String,
    val description: String,
    val resolvedModel: String?,
    val effortLevels: List<String> = emptyList(),
)

/** Effort levels (`--effort`, and `effortLevel` in `apply_flag_settings`): how much Claude thinks before answering. */
object Effort {
    val LEVELS = listOf("low", "medium", "high", "xhigh", "max")

    fun label(level: String?): String = when (level) {
        null -> "Default"
        "xhigh" -> "Extra high"
        else -> level.replaceFirstChar { it.uppercase() }
    }

    fun description(level: String?): String = when (level) {
        null -> "Claude Code's setting (/effort), or the model's default"
        "low" -> "Quickest replies, for simple tasks"
        "medium" -> "Balanced speed and depth"
        "high" -> "Thinks longer, for harder problems"
        "xhigh" -> "Thinks longer still, for complex work"
        "max" -> "Deepest thinking; slowest, and uses the most of your plan"
        else -> ""
    }

    /**
     * The levels to offer with [selectedModel] (a `--model` value, null for the default): the model's own, all of
     * them for a model the CLI didn't list (e.g. a full name typed in settings), none before the models are known.
     */
    fun levelsFor(models: List<ModelOption>, selectedModel: String?): List<String> {
        if (models.isEmpty()) return emptyList()
        val option = models.firstOrNull { it.value == (selectedModel ?: CliCatalog.DEFAULT) } ?: return LEVELS
        return option.effortLevels
    }
}

/** A slash command or skill the CLI accepts as a message (`initialize` → `commands`, `system`/`commands_changed`). */
data class SlashCommand(val name: String, val description: String, val argumentHint: String, val aliases: List<String> = emptyList())

/**
 * The models and slash commands the CLI offers. They're read once per project with an `initialize` request to a
 * short-lived CLI process, which makes no API call and leaves no session behind (see docs/PROTOCOL.md), so the model
 * picker and `/` list work before the first message. Running chats keep the commands current from `commands_changed`.
 */
object CliCatalog {

    class Catalog(val models: List<ModelOption>, val commands: List<SlashCommand>)

    private val byWorkDir = ConcurrentHashMap<String, Catalog>()

    fun cached(workDir: String): Catalog? = byWorkDir[workDir]

    /** Models from any project's catalog read so far (they don't depend on the project); empty if none was read. */
    fun knownModels(): List<ModelOption> = byWorkDir.values.firstOrNull { it.models.isNotEmpty() }?.models.orEmpty()

    /** Drops the project's cached catalog after its commands changed, so the next load asks the CLI again. */
    fun forget(workDir: String) {
        byWorkDir.remove(workDir)
    }

    /** Records commands reported by a running chat, so new chats in the project start with them. */
    fun updateCommands(workDir: String, commands: List<SlashCommand>) {
        byWorkDir.compute(workDir) { _, old -> Catalog(old?.models.orEmpty(), commands) }
    }

    /** Asks the CLI for its catalog. Blocks for a few seconds; call off the EDT. Null if the CLI can't be run. */
    fun load(workDir: String): Catalog? {
        // Cursor commands added since the last load are new commands, so the cached list is out of date.
        if (ClaudeChatSettings.options.syncCursorCommands && CursorCommands.syncAll().isNotEmpty()) byWorkDir.remove(workDir)
        cached(workDir)?.let { return it }
        val executable = ClaudeCli.findExecutable() ?: return null
        val process = runCatching {
            ClaudeCli.commandLine(executable, workDir, null, null, null).createProcess()
        }.getOrElse {
            LOG.warn("Could not start the CLI to read its models and commands", it)
            return null
        }
        try {
            process.outputStream.write((INITIALIZE.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
            val reader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline) {
                val line = reader.readLine() ?: break
                val response = parseInitialize(line) ?: continue
                return Catalog(models(response), commands(response.getAsJsonArray("commands")))
                    .also { byWorkDir[workDir] = it }
            }
            LOG.warn("The CLI didn't answer the initialize request")
            return null
        } finally {
            runCatching { process.outputStream.close() }
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** The `initialize` response in a CLI output [line], or null if the line is something else. */
    fun parseInitialize(line: String): JsonObject? {
        if (!line.contains(REQUEST_ID)) return null
        val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
        val response = event.getAsJsonObject("response") ?: return null
        if (response.str("request_id") != REQUEST_ID || response.str("subtype") != "success") return null
        return response.getAsJsonObject("response")
    }

    fun models(response: JsonObject): List<ModelOption> =
        response.getAsJsonArray("models")?.mapNotNull { element ->
            val model = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val value = model.str("value") ?: return@mapNotNull null
            val efforts = if (model.get("supportsEffort")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                model.getAsJsonArray("supportedEffortLevels")?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
            } else {
                emptyList()
            }
            ModelOption(value, model.str("displayName") ?: value, model.str("description").orEmpty(), model.str("resolvedModel"), efforts)
        }.orEmpty()

    /** Commands from `initialize` or `commands_changed`, leaving out internal ones (named `__…`). */
    fun commands(array: JsonArray?): List<SlashCommand> =
        array?.mapNotNull { element ->
            val command = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = command.str("name")?.takeIf { !it.startsWith("__") } ?: return@mapNotNull null
            SlashCommand(
                name,
                command.str("description").orEmpty(),
                command.str("argumentHint").orEmpty(),
                command.getAsJsonArray("aliases")?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty(),
            )
        }.orEmpty()

    /**
     * Commands matching the `/query` being typed: names (or aliases) starting with it first, then names containing it,
     * each group in the CLI's order.
     */
    fun match(commands: List<SlashCommand>, query: String): List<SlashCommand> {
        if (query.isEmpty()) return commands
        val q = query.lowercase()
        fun names(command: SlashCommand) = (listOf(command.name) + command.aliases).map { it.lowercase() }
        val starting = commands.filter { command -> names(command).any { it.startsWith(q) || it.substringAfter(':').startsWith(q) } }
        val containing = commands.filter { command -> command !in starting && names(command).any { q in it } }
        return starting + containing
    }

    /**
     * How the picker names the model in use: the option chosen ([selected], a `--model` value; null for the CLI default),
     * with "Default" shown as the model it stands for. An unknown value (e.g. a full model name from settings) is shown
     * as is.
     */
    fun label(models: List<ModelOption>, selected: String?): String {
        val option = models.firstOrNull { it.value == (selected ?: DEFAULT) } ?: return selected ?: "Default"
        if (option.value != DEFAULT) return option.displayName
        return models.firstOrNull { it.value != DEFAULT && it.resolvedModel == option.resolvedModel }?.displayName ?: option.displayName
    }

    /** The option for a model id reported by the CLI (e.g. `init.model` after `/model`), preferring an alias over Default. */
    fun optionFor(models: List<ModelOption>, modelId: String): ModelOption? =
        models.firstOrNull { it.value == modelId }
            ?: models.firstOrNull { it.value != DEFAULT && it.resolvedModel == modelId }

    const val DEFAULT = "default"

    private const val REQUEST_ID = "claudechat-initialize"
    private const val TIMEOUT_SECONDS = 20L
    private val LOG = logger<CliCatalog>()

    private val INITIALIZE = JsonObject().apply {
        addProperty("type", "control_request")
        addProperty("request_id", REQUEST_ID)
        add("request", JsonObject().apply { addProperty("subtype", "initialize") })
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
