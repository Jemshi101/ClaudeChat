package `in`.bleuphoenix.claudechat.history

import com.google.gson.JsonObject
import `in`.bleuphoenix.claudechat.ui.ChatEventReducer
import `in`.bleuphoenix.claudechat.ui.ChatState
import `in`.bleuphoenix.claudechat.ui.ContextMessage

/**
 * Rebuilds a conversation from a session transcript. Assistant and tool-result entries have the same shape as the
 * live stream-json events, so they go through a [ChatEventReducer] of their own, with no callbacks: replaying an
 * old edit must not refresh, review or checkpoint anything.
 */
object TranscriptReplay {

    /** Fills [state] from [entries]; returns the uuid of the last message, where the conversation continues. */
    fun replay(entries: List<JsonObject>, state: ChatState): String? {
        val reducer = ChatEventReducer(state)
        entries.forEach { entry ->
            if (entry.get("isSidechain")?.asBoolean == true || entry.get("isMeta")?.asBoolean == true) return@forEach
            when (entry.str("type")) {
                "user" -> {
                    val sent = sentText(entry)
                    if (sent != null) {
                        val (text, files) = ContextMessage.split(sent, state::newKey)
                        reducer.endTurn()
                        reducer.addUserMessage(text, files)
                    } else {
                        reducer.handle(entry) // tool results
                    }
                }
                "assistant" -> reducer.handle(entry)
            }
        }
        reducer.flush()
        reducer.endTurn()
        return reducer.lastMessageUuid
    }

    /** What the user typed in a transcript's user entry, without the context the chat adds when sending. */
    fun userText(entry: JsonObject): String? = sentText(entry)?.let { ContextMessage.split(it) { "" }.first }

    /** The text of a user's message as sent; null for tool results, image placeholders and CLI-internal messages. */
    private fun sentText(entry: JsonObject): String? {
        val content = entry.getAsJsonObject("message")?.get("content") ?: return null
        val text = when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> {
                val blocks = content.asJsonArray.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                if (blocks.any { it.str("type") == "tool_result" }) return null
                blocks.filter { it.str("type") == "text" }
                    .mapNotNull { it.str("text") }
                    .filterNot { it.startsWith("[Image: source:") }
                    .joinToString("\n")
            }
            else -> return null
        }
        // Slash commands, command output and interruptions are stored as tagged or bracketed text.
        if (text.startsWith("<") || text.startsWith("[Request interrupted")) return null
        return text.takeIf { ContextMessage.split(it) { "" }.first.isNotBlank() }
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
