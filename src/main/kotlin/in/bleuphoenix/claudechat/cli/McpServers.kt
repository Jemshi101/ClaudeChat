package `in`.bleuphoenix.claudechat.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import java.io.File
import java.nio.charset.StandardCharsets

/** Where an MCP server is configured, as `claude mcp add --scope` takes it. */
enum class McpScope(val cli: String, val label: String) {
    USER("user", "All projects"),
    LOCAL("local", "This project, only me"),
    PROJECT("project", "This project, shared in .mcp.json"),
}

enum class McpTransport(val cli: String, val label: String) {
    HTTP("http", "HTTP"),
    SSE("sse", "SSE"),
    STDIO("stdio", "Command (stdio)"),
}

/**
 * OAuth settings for an HTTP or SSE server (its `oauth` entry), for servers that don't register clients on their own
 * or need particular scopes. The client secret isn't here: the CLI keeps it in its own secure storage.
 */
data class McpOAuth(
    val clientId: String = "",
    val callbackPort: Int? = null,
    val scopes: String = "",
    val authServerMetadataUrl: String = "",
) {
    val isEmpty: Boolean get() = clientId.isBlank() && callbackPort == null && scopes.isBlank() && authServerMetadataUrl.isBlank()

    fun toJson(): JsonObject = JsonObject().apply {
        if (clientId.isNotBlank()) addProperty("clientId", clientId)
        callbackPort?.let { addProperty("callbackPort", it) }
        if (scopes.isNotBlank()) addProperty("scopes", scopes)
        if (authServerMetadataUrl.isNotBlank()) addProperty("authServerMetadataUrl", authServerMetadataUrl)
    }

    companion object {
        fun fromJson(json: JsonObject?): McpOAuth? {
            json ?: return null
            fun str(key: String) = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            return McpOAuth(
                clientId = str("clientId"),
                callbackPort = json.get("callbackPort")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull(),
                scopes = str("scopes"),
                authServerMetadataUrl = str("authServerMetadataUrl"),
            ).takeUnless { it.isEmpty }
        }
    }
}

/**
 * An MCP server as Claude Code's config holds it. An API key kept in the keychain appears in [headers] or [env] as
 * `${CLAUDE_CHAT_MCP_<NAME>}` (see [McpKeys]).
 */
data class McpServer(
    val name: String,
    val scope: McpScope,
    val transport: McpTransport,
    val url: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val oauth: McpOAuth? = null,
) {
    /** Servers that can sign in with OAuth: HTTP and SSE ones. */
    val canSignIn: Boolean get() = transport != McpTransport.STDIO

    val target: String get() = if (transport == McpTransport.STDIO) (listOf(command) + args).joinToString(" ") else url

    /** The JSON `claude mcp add-json` takes. */
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("type", transport.cli)
        if (transport == McpTransport.STDIO) {
            addProperty("command", command)
            add("args", com.google.gson.JsonArray().apply { args.forEach(::add) })
            if (env.isNotEmpty()) add("env", JsonObject().apply { env.forEach(::addProperty) })
        } else {
            addProperty("url", url)
            if (headers.isNotEmpty()) add("headers", JsonObject().apply { headers.forEach(::addProperty) })
            oauth?.takeUnless { it.isEmpty }?.let { add("oauth", it.toJson()) }
        }
    }

    companion object {
        /** A server from a config's `mcpServers` entry; null for kinds this page doesn't edit (WebSocket, SDK). */
        fun fromJson(name: String, scope: McpScope, json: JsonObject): McpServer? {
            val transport = when (json.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                "http" -> McpTransport.HTTP
                "sse" -> McpTransport.SSE
                "stdio" -> McpTransport.STDIO
                null -> if (json.has("command")) McpTransport.STDIO else if (json.has("url")) McpTransport.SSE else return null
                else -> return null
            }
            fun strings(key: String) = json.getAsJsonObject(key)?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
            return McpServer(
                name = name,
                scope = scope,
                transport = transport,
                url = json.get("url")?.asString.orEmpty(),
                command = json.get("command")?.asString.orEmpty(),
                args = json.getAsJsonArray("args")?.map { it.asString }.orEmpty(),
                headers = strings("headers"),
                env = strings("env"),
                oauth = if (transport == McpTransport.STDIO) null else McpOAuth.fromJson(json.getAsJsonObject("oauth")),
            )
        }
    }
}

/** Reads the MCP servers Claude Code is configured with, and changes them with `claude mcp`. */
object McpServers {

    /** `~/.claude.json`, or the one in `$CLAUDE_CONFIG_DIR`, which holds user- and local-scope servers. */
    fun userConfig(): File =
        System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { File(it, ".claude.json") }
            ?: File(System.getProperty("user.home"), ".claude.json")

    /** The servers for [projectDir]: the user's, then this project's private ones, then its shared `.mcp.json`. */
    fun read(projectDir: String?, userConfig: File = userConfig()): List<McpServer> {
        val user = parse(userConfig)
        val local = projectDir?.let { dir ->
            val projects = user?.getAsJsonObject("projects")
            (projects?.getAsJsonObject(dir) ?: projects?.getAsJsonObject(File(dir).canonicalPath))
        }
        val shared = projectDir?.let { parse(File(it, ".mcp.json")) }
        return servers(user, McpScope.USER) + servers(local, McpScope.LOCAL) + servers(shared, McpScope.PROJECT)
    }

    private fun parse(file: File): JsonObject? =
        if (!file.isFile) null else runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()

    private fun servers(config: JsonObject?, scope: McpScope): List<McpServer> =
        config?.getAsJsonObject("mcpServers")?.entrySet().orEmpty()
            .mapNotNull { (name, json) -> json.takeIf { it.isJsonObject }?.let { McpServer.fromJson(name, scope, it.asJsonObject) } }
            .sortedBy { it.name.lowercase() }

    /**
     * Adds [server], replacing [replacing] (the server as it was, when editing). A [clientSecret] goes to the CLI in
     * `MCP_CLIENT_SECRET`, never on the command line. Returns the CLI's error, or null.
     */
    fun save(executable: String, workDir: String?, server: McpServer, replacing: McpServer?, clientSecret: String? = null): String? {
        if (replacing != null) run(executable, workDir, "mcp", "remove", replacing.name, "--scope", replacing.scope.cli)?.let { return it }
        val error = if (clientSecret.isNullOrEmpty()) {
            run(executable, workDir, "mcp", "add-json", "--scope", server.scope.cli, server.name, server.toJson().toString())
        } else {
            run(
                executable, workDir, "mcp", "add-json", "--scope", server.scope.cli, "--client-secret", server.name, server.toJson().toString(),
                env = mapOf(CLIENT_SECRET_VARIABLE to clientSecret),
            )
        }
        // Put the old one back rather than lose it.
        if (error != null && replacing != null) run(executable, workDir, "mcp", "add-json", "--scope", replacing.scope.cli, replacing.name, replacing.toJson().toString())
        return error
    }

    fun remove(executable: String, workDir: String?, server: McpServer): String? =
        run(executable, workDir, "mcp", "remove", server.name, "--scope", server.scope.cli)

    /**
     * Signs in to [server] with `claude mcp login`, which opens the browser and waits for the sign-in to finish (up to
     * [LOGIN_TIMEOUT_MS]). Returns the CLI's error, or null.
     */
    fun login(executable: String, workDir: String?, server: McpServer): String? =
        run(executable, workDir, "mcp", "login", server.name, env = McpKeys.environment(workDir), timeoutMs = LOGIN_TIMEOUT_MS)

    /** Clears the OAuth sign-in stored for [server] with `claude mcp logout`. Returns the CLI's error, or null. */
    fun logout(executable: String, workDir: String?, server: McpServer): String? =
        run(executable, workDir, "mcp", "logout", server.name)

    /** Each server's health as `claude mcp list` reports it ("Connected", "Failed to connect", ...), by name. */
    fun statuses(executable: String, workDir: String?): Map<String, String> {
        val output = runCatching {
            ExecUtil.execAndGetOutput(cli(executable, workDir, "mcp", "list").withEnvironment(McpKeys.environment(workDir)), LIST_TIMEOUT_MS)
        }.getOrNull() ?: return emptyMap()
        return parseStatuses(output.stdout)
    }

    /** `name: target (HTTP) - ✔ Connected` lines from `claude mcp list`. */
    fun parseStatuses(listOutput: String): Map<String, String> =
        listOutput.lines().mapNotNull { STATUS_LINE.matchEntire(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2].trimStart { c -> !c.isLetter() }.trim() }

    private val STATUS_LINE = Regex("""^([^\s:][^:]*): .* - (.+)$""")

    private fun run(
        executable: String,
        workDir: String?,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Int = TIMEOUT_MS,
    ): String? {
        val output = runCatching { ExecUtil.execAndGetOutput(cli(executable, workDir, *args).withEnvironment(env), timeoutMs) }
            .getOrElse { return it.message ?: it.toString() }
        if (output.isTimeout) return "Timed out after ${timeoutMs / 1000} seconds."
        return if (output.exitCode == 0) null else (output.stderr.ifBlank { output.stdout }).trim().ifEmpty { "claude exited with ${output.exitCode}" }
    }

    private fun cli(executable: String, workDir: String?, vararg args: String) =
        GeneralCommandLine(executable, *args)
            .apply { workDir?.let(::withWorkDirectory) }
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)

    private const val TIMEOUT_MS = 30_000

    /** Time to finish signing in in the browser. */
    const val LOGIN_TIMEOUT_MS = 5 * 60_000

    const val CLIENT_SECRET_VARIABLE = "MCP_CLIENT_SECRET"

    /** Listing checks every server's health, which can take a while for one that doesn't answer. */
    private const val LIST_TIMEOUT_MS = 90_000
}

/**
 * MCP API keys kept in the OS keychain (the IDE's password safe) rather than in Claude Code's config. The config
 * holds `${CLAUDE_CHAT_MCP_<NAME>}` where the key goes, and chats start the CLI with that variable set, which the
 * CLI fills in. A terminal `claude` can use the server too by setting the same variable.
 */
object McpKeys {

    /** Where the key goes in a header or variable value, in the "Sent as" text the settings show. */
    const val KEY_MARK = "<key>"

    private const val PREFIX = "CLAUDE_CHAT_MCP_"
    private val REFERENCE = Regex("""\$\{($PREFIX[A-Z0-9_]+)}""")

    fun variable(serverName: String): String = PREFIX + serverName.uppercase().replace(Regex("[^A-Z0-9]"), "_")

    fun reference(serverName: String): String = "\${${variable(serverName)}}"

    /** The keychain variables [server]'s config refers to. */
    fun referencedBy(server: McpServer): Set<String> =
        (server.headers.values + server.env.values).flatMap { value -> REFERENCE.findAll(value).map { it.groupValues[1] } }.toSet()

    /**
     * The variables to start the CLI with: every keychain key the servers for [projectDir] refer to. Reads the
     * keychain, so call off the EDT.
     */
    fun environment(projectDir: String?, lookup: (String) -> String? = ::get, userConfig: File = McpServers.userConfig()): Map<String, String> =
        McpServers.read(projectDir, userConfig).flatMap(::referencedBy).toSet()
            .mapNotNull { variable -> lookup(variable)?.takeIf { it.isNotEmpty() }?.let { variable to it } }
            .toMap()

    fun get(variable: String): String? = PasswordSafe.instance.getPassword(attributes(variable))

    fun set(variable: String, key: String?) = PasswordSafe.instance.setPassword(attributes(variable), key)

    private fun attributes(variable: String) = CredentialAttributes(generateServiceName("Claude Chat", "MCP $variable"))

    /**
     * Splits "Sent as" text into the header (`Authorization: Bearer <key>`) or variable (`API_KEY=<key>`) name and
     * its value with the key's reference in place of [KEY_MARK]. Null when it isn't in that form.
     */
    fun placement(sentAs: String, transport: McpTransport, serverName: String): Pair<String, String>? {
        val separator = if (transport == McpTransport.STDIO) '=' else ':'
        val name = sentAs.substringBefore(separator, "").trim()
        val value = sentAs.substringAfter(separator, "").trim()
        if (name.isEmpty() || KEY_MARK !in value) return null
        return name to value.replace(KEY_MARK, reference(serverName))
    }

    /**
     * For editing [server]: its "Sent as" text and, when the key is written in the config in plain text (as
     * `claude mcp add --header "Authorization: Bearer ..."` leaves it), that key, to be moved to the keychain.
     * Null when it has no key the page recognises.
     */
    fun find(server: McpServer): Found? {
        val stdio = server.transport == McpTransport.STDIO
        val values = if (stdio) server.env else server.headers
        val separator = if (stdio) "=" else ": "
        values.entries.firstOrNull { REFERENCE.containsMatchIn(it.value) }?.let { (name, value) ->
            return Found(name + separator + REFERENCE.replace(value, KEY_MARK), null, name)
        }
        if (stdio) return null
        val (name, value) = server.headers.entries.firstOrNull { (name, _) -> SECRET_HEADER.containsMatchIn(name) } ?: return null
        if ("\${" in value) return null
        val scheme = SCHEME.find(value)?.value.orEmpty()
        return Found("$name: $scheme$KEY_MARK", value.removePrefix(scheme), name)
    }

    class Found(val sentAs: String, val plainKey: String?, val name: String)

    private val SECRET_HEADER = Regex("authorization|api[-_]?key|token|secret", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("""^(Bearer|Token|Basic)\s+""", RegexOption.IGNORE_CASE)
}
