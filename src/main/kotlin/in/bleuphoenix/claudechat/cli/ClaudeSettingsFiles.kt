package `in`.bleuphoenix.claudechat.cli

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Claude Code's settings files, from the most specific to the most general. */
enum class SettingsScope(val label: String, val path: String) {
    LOCAL("This project, only me", ".claude/settings.local.json"),
    PROJECT("This project, shared", ".claude/settings.json"),
    USER("All projects", "~/.claude/settings.json"),
}

/** The parts of a settings file the settings page edits: permission rules and environment variables. */
data class ClaudeSettings(
    val allow: List<String> = emptyList(),
    val ask: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val env: List<Pair<String, String>> = emptyList(),
)

/**
 * Reads and writes the permission rules (`permissions.allow` / `ask` / `deny`) and `env` of Claude Code's settings
 * files, keeping every other key as it is.
 */
object ClaudeSettingsFiles {
    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** The file for [scope]; null for a project scope without a project folder. */
    fun file(scope: SettingsScope, projectDir: String?, home: File = File(System.getProperty("user.home"))): File? = when (scope) {
        SettingsScope.USER -> File(home, ".claude/settings.json")
        else -> projectDir?.let { File(it, scope.path) }
    }

    /** The file's settings; empty when it doesn't exist. Throws IOException when it isn't a JSON object. */
    fun read(file: File): ClaudeSettings {
        val json = parse(file) ?: return ClaudeSettings()
        val permissions = json.get("permissions")?.takeIf { it.isJsonObject }?.asJsonObject
        fun rules(key: String) = permissions?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
        val env = json.get("env")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
            ?.map { (name, value) -> name to if (value.isJsonPrimitive) value.asString else value.toString() }.orEmpty()
        return ClaudeSettings(rules("allow"), rules("ask"), rules("deny"), env)
    }

    /** Writes [settings] into the file, creating it and its folder if needed. Other keys are kept. */
    fun write(file: File, settings: ClaudeSettings) {
        val json = parse(file) ?: JsonObject()
        val permissions = json.get("permissions")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        fun putRules(key: String, rules: List<String>) {
            if (rules.isEmpty()) permissions.remove(key)
            else permissions.add(key, JsonArray().apply { rules.forEach(::add) })
        }
        putRules("allow", settings.allow)
        putRules("ask", settings.ask)
        putRules("deny", settings.deny)
        if (permissions.size() == 0) json.remove("permissions") else json.add("permissions", permissions)
        if (settings.env.isEmpty()) json.remove("env")
        else json.add("env", JsonObject().apply { settings.env.forEach { (name, value) -> addProperty(name, value) } })
        file.parentFile?.mkdirs()
        file.writeText(GSON.toJson(json) + "\n", StandardCharsets.UTF_8)
    }

    private fun parse(file: File): JsonObject? {
        if (!file.isFile) return null
        val text = file.readText(StandardCharsets.UTF_8)
        if (text.isBlank()) return JsonObject()
        val element = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw IOException("${file.name} isn't valid JSON: ${e.cause?.message ?: e.message}", e)
        }
        if (!element.isJsonObject) throw IOException("${file.name} doesn't hold a JSON object.")
        return element.asJsonObject
    }
}
