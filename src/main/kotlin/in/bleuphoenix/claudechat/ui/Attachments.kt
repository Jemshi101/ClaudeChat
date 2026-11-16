package `in`.bleuphoenix.claudechat.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Context added to the next message: shown as chips above the message box, then in the sent message's bubble. */
sealed class Attachment(val key: String) {
    abstract val label: String
}

/**
 * A project file or folder, optionally a line range, sent as an `@` mention that the CLI expands into the file's
 * content (see docs/PROTOCOL.md). [path] is relative to the project when inside it.
 */
class FileAttachment(key: String, val path: String, val isDirectory: Boolean = false, val lines: IntRange? = null) : Attachment(key) {
    override val label: String
        get() {
            val name = path.trimEnd('/').substringAfterLast('/')
            return when {
                lines == null -> if (isDirectory) "$name/" else name
                lines.first == lines.last -> "$name:${lines.first}"
                else -> "$name:${lines.first}-${lines.last}"
            }
        }
}

/** A pasted or dropped image, sent as an image content block. [base64] holds PNG or JPEG bytes. */
class ImageAttachment(
    key: String,
    val name: String,
    val mediaType: String,
    val base64: String,
    val thumbnail: ImageBitmap?,
) : Attachment(key) {
    override val label: String get() = name
}

/**
 * A block of text sent inside the message rather than typed into it: a long paste, collapsed so the message box stays
 * readable, or output from the Run console or Logcat. [source] names it on the chip ("Pasted text", "Logcat").
 */
class TextAttachment(key: String, val source: String, val text: String) : Attachment(key) {
    val lineCount: Int get() = text.lines().size
    override val label: String get() = "$source · $lineCount ${if (lineCount == 1) "line" else "lines"}"

    companion object {
        /** A paste this long becomes a chip instead of filling the message box. */
        fun isLong(text: String): Boolean = text.length >= LONG_CHARS || text.lines().size >= LONG_LINES

        private const val LONG_LINES = 20
        private const val LONG_CHARS = 2000
    }
}

/** The file in the active editor, offered as context until the user removes it. */
data class ActiveFile(val path: String, val line: Int?) {
    val name: String get() = path.substringAfterLast('/')
}

/** Builds the text actually sent to the CLI from what the user typed and the context they attached. */
object ContextMessage {

    fun build(text: String, attachments: List<Attachment>, activeFile: ActiveFile?): String {
        val mentions = attachments.filterIsInstance<FileAttachment>().map(::mention).distinct()
        val parts = mutableListOf(text)
        attachments.filterIsInstance<TextAttachment>().forEach { parts += textBlock(it) }
        if (mentions.isNotEmpty()) parts += CONTEXT_PREFIX + mentions.joinToString(" ")
        // Only a pointer: Claude reads it if it's relevant, rather than every message carrying the whole file.
        activeFile?.let { parts += "(Open in my editor: ${it.path}${it.line?.let { line -> ":$line" }.orEmpty()})" }
        // A message can be only a pasted text.
        return parts.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * Splits a message built by [build] (with any notes the chat put before it) back into the user's own words and
     * the files they attached, for showing a past chat again.
     */
    fun split(sent: String, newKey: () -> String): Pair<String, List<Attachment>> {
        // Text blocks first: they may hold blank lines, which would split them into paragraphs.
        val texts = TEXT_BLOCK.findAll(sent).map { TextAttachment(newKey(), unescape(it.groupValues[1]), it.groupValues[2]) }.toList()
        val paragraphs = TEXT_BLOCK.replace(sent, "").split("\n\n")
        val files = paragraphs.filter { it.startsWith(CONTEXT_PREFIX) }.flatMap { paragraph ->
            MENTION.findAll(paragraph.removePrefix(CONTEXT_PREFIX)).map { match ->
                val path = match.groupValues[1].removeSurrounding("\"").replace("\\\"", "\"")
                val start = match.groupValues[2].toIntOrNull()
                val lines = start?.let { it..(match.groupValues[3].toIntOrNull() ?: it) }
                FileAttachment(newKey(), path.trimEnd('/'), isDirectory = path.endsWith("/"), lines = lines)
            }.toList()
        }
        val text = paragraphs
            .filterNot { it.startsWith(CONTEXT_PREFIX) || it.startsWith("(Open in my editor: ") || it.startsWith("(From the IDE: ") }
            .joinToString("\n\n")
            .trim()
        return text to (files + texts)
    }

    /** A [TextAttachment] as sent: tagged, so a past chat shows it as a chip again rather than in the message. */
    private fun textBlock(attachment: TextAttachment): String =
        "<attached-text source=\"${escape(attachment.source)}\">\n${attachment.text.trimEnd()}\n</attached-text>"

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
    private fun unescape(value: String) = value.replace("&quot;", "\"").replace("&amp;", "&")

    private val TEXT_BLOCK = Regex("""<attached-text source="([^"]*)">\n([\s\S]*?)\n</attached-text>(?:\n\n)?""")

    private const val CONTEXT_PREFIX = "Context: "
    private val MENTION = Regex("""@("(?:[^"\\]|\\.)*"|\S+?)(?:#L(\d+)(?:-(\d+))?)?(?=\s|$)""")

    /** `@path`, quoted when it has spaces, with `#L10-20` for a line range; the CLI's mention syntax. */
    fun mention(file: FileAttachment): String {
        val path = if (file.isDirectory && !file.path.endsWith("/")) "${file.path}/" else file.path
        val quoted = if (path.any { it.isWhitespace() || it == '"' }) "\"${path.replace("\"", "\\\"")}\"" else path
        val range = file.lines?.let { if (it.first == it.last) "#L${it.first}" else "#L${it.first}-${it.last}" }.orEmpty()
        return "@$quoted$range"
    }

    /**
     * The `@query` being typed just before the cursor, if any: an `@` at the start or after whitespace, followed by
     * no whitespace. Returns the query and the index of its `@`.
     */
    fun mentionQuery(text: CharSequence, cursor: Int): Pair<String, Int>? {
        val before = text.subSequence(0, cursor.coerceIn(0, text.length))
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !before[at - 1].isWhitespace()) return null
        val query = before.substring(at + 1)
        if (query.any { it.isWhitespace() }) return null
        return query to at
    }

    /**
     * The command name being typed, for the command list, and the index of its `/`: either the message starts with `/`
     * and the cursor is still in that first word, or (when it doesn't start with one) a `/` after whitespace just
     * before the cursor. A second `/` (a path) means it isn't a command.
     */
    fun slashQuery(text: CharSequence, cursor: Int): Pair<String, Int>? {
        if (text.startsWith("/")) {
            val name = text.drop(1).takeWhile { !it.isWhitespace() }.toString()
            if (cursor < 1 || cursor > name.length + 1 || '/' in name) return null
            return name to 0
        }
        val before = text.subSequence(0, cursor.coerceIn(0, text.length))
        val slash = before.lastIndexOf('/')
        if (slash <= 0 || !before[slash - 1].isWhitespace()) return null
        val name = before.substring(slash + 1)
        if (name.any { it.isWhitespace() }) return null
        return name to slash
    }
}
