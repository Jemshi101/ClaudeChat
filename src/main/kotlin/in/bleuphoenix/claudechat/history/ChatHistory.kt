package `in`.bleuphoenix.claudechat.history

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/** A past chat: one CLI session transcript. [updated] is the transcript's last-modified time (epoch ms). */
data class SessionInfo(val id: String, val file: File, val title: String, val updated: Long)

/**
 * Past chats for a project, read from the CLI's session transcripts (`~/.claude/projects/<project>/<id>.jsonl`, see
 * docs/PROTOCOL.md). These include sessions started in a terminal in the same folder; any of them can be continued.
 */
object ChatHistory {

    /** How much of a transcript's end is read for its title; titles are rewritten as the chat goes on. */
    private const val TAIL_BYTES = 256 * 1024
    private const val HEAD_LINES = 400

    private val root: File
        get() = System.getenv("CLAUDE_CONFIG_DIR")?.let { File(it, "projects") }
            ?: File(System.getProperty("user.home"), ".claude/projects")

    /** The CLI names a project's folder after its path with every character other than a letter or digit as '-'. */
    fun directoryName(projectPath: String): String = projectPath.replace(Regex("[^A-Za-z0-9]"), "-")

    /** Sessions for [projectPath], newest first; transcripts without a message from the user are left out. */
    fun list(projectPath: String, rootDir: File = root): List<SessionInfo> {
        val dir = File(rootDir, directoryName(projectPath))
        val files = dir.listFiles { file -> file.isFile && file.extension == "jsonl" }.orEmpty()
        return files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            title(file)?.let { SessionInfo(file.nameWithoutExtension, file, it, file.lastModified()) }
        }
    }

    /** The user's name for the chat, else the CLI's generated title, else its first message; null if it has none. */
    fun title(file: File): String? {
        var custom: String? = null
        var generated: String? = null
        tailLines(file).forEach { line ->
            when {
                line.contains("\"custom-title\"") -> parse(line)?.str("customTitle")?.let { custom = it }
                line.contains("\"ai-title\"") -> parse(line)?.str("aiTitle")?.let { generated = it }
            }
        }
        return (custom ?: generated ?: firstPrompt(file))?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Every entry of the transcript, in file order. Call off the EDT; transcripts can be large. */
    fun load(file: File): List<JsonObject> = file.useLines(StandardCharsets.UTF_8) { lines ->
        lines.mapNotNull { line -> if (line.isBlank()) null else parse(line) }.toList()
    }

    /** Renames the chat the way the CLI's /rename does, so the name shows in the terminal too. */
    fun rename(session: SessionInfo, title: String) {
        val entry = JsonObject().apply {
            addProperty("type", "custom-title")
            addProperty("customTitle", title)
            addProperty("sessionId", session.id)
        }
        session.file.appendText(entry.toString() + "\n", StandardCharsets.UTF_8)
    }

    fun delete(session: SessionInfo): Boolean = session.file.delete()

    private fun firstPrompt(file: File): String? = file.useLines(StandardCharsets.UTF_8) { lines ->
        lines.take(HEAD_LINES)
            .filter { it.contains("\"type\":\"user\"") }
            .mapNotNull(::parse)
            .filter { it.get("isSidechain")?.asBoolean != true && it.get("isMeta")?.asBoolean != true }
            .mapNotNull { TranscriptReplay.userText(it) }
            .map { it.lineSequence().first().take(120) }
            .firstOrNull()
    }

    private fun tailLines(file: File): List<String> = RandomAccessFile(file, "r").use { raf ->
        val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        val text = String(bytes, StandardCharsets.UTF_8)
        // A read that starts mid-file starts mid-line; drop that partial line.
        (if (start > 0) text.substringAfter('\n') else text).lines()
    }

    private fun parse(line: String): JsonObject? = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
