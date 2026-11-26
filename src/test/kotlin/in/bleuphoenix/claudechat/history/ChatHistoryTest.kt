package `in`.bleuphoenix.claudechat.history

import `in`.bleuphoenix.claudechat.ui.AssistantTextItem
import `in`.bleuphoenix.claudechat.ui.ChatState
import `in`.bleuphoenix.claudechat.ui.CompactedItem
import `in`.bleuphoenix.claudechat.ui.FileAttachment
import `in`.bleuphoenix.claudechat.ui.ToolItem
import `in`.bleuphoenix.claudechat.ui.ToolStatus
import `in`.bleuphoenix.claudechat.ui.UserItem
import `in`.bleuphoenix.claudechat.ui.compose.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

class ChatHistoryTest {

    @get:Rule
    val root = TemporaryFolder()

    private val project = "/work/Flutter_Workspace/app"

    private fun transcript(id: String, modified: Long, vararg lines: String): File {
        val dir = File(root.root, ChatHistory.directoryName(project)).apply { mkdirs() }
        return File(dir, "$id.jsonl").apply {
            writeText(lines.joinToString("\n") + "\n")
            setLastModified(modified)
        }
    }

    private fun user(text: String) = """{"type":"user","message":{"role":"user","content":"$text"},"isSidechain":false}"""

    @Test
    fun `project folders are named like the CLI names them`() {
        assertEquals("-Users-me-Developer-Flutter-Workspace-Acme", ChatHistory.directoryName("/Users/me/Developer/Flutter_Workspace/Acme"))
    }

    @Test
    fun `sessions are listed newest first with the best title available`() {
        transcript(
            "old", 1_000_000,
            user("Add a splash screen"),
            """{"type":"ai-title","aiTitle":"Splash screen","sessionId":"old"}""",
            """{"type":"custom-title","customTitle":"Splash v1","sessionId":"old"}""",
            """{"type":"custom-title","customTitle":"Splash screen work","sessionId":"old"}""",
        )
        transcript("new", 3_000_000, user("Why does the clock crash?\\n\\nContext: @Clock.kt"))
        transcript("generated", 2_000_000, user("first"), """{"type":"ai-title","aiTitle":"Clock fixes","sessionId":"generated"}""")
        transcript("empty", 4_000_000, """{"type":"queue-operation","operation":"enqueue"}""")

        val sessions = ChatHistory.list(project, root.root)
        assertEquals(listOf("new", "generated", "old"), sessions.map { it.id })
        assertEquals(listOf("Why does the clock crash?", "Clock fixes", "Splash screen work"), sessions.map { it.title })
    }

    @Test
    fun `rename is stored the way the CLI stores it, and delete removes the transcript`() {
        transcript("s1", 1_000_000, user("Hello"))
        val session = ChatHistory.list(project, root.root).single()
        ChatHistory.rename(session, "Greeting")
        assertEquals("Greeting", ChatHistory.list(project, root.root).single().title)
        assertTrue(session.file.readText().contains("""{"type":"custom-title","customTitle":"Greeting","sessionId":"s1"}"""))

        assertTrue(ChatHistory.delete(session))
        assertTrue(ChatHistory.list(project, root.root).isEmpty())
    }

    @Test
    fun `replaying an edit chat shows the message, tool calls with results, and the reply`() {
        val state = replay("/transcripts/edit-chat.jsonl")
        val user = state.items.first() as UserItem
        assertTrue(user.text.startsWith("In Main.kt, change the greeting text hi to hello"))
        assertFalse("the chat's added context isn't shown", user.text.contains("Open in my editor"))

        val tools = state.items.filterIsInstance<ToolItem>()
        assertEquals(listOf("Bash", "Read", "Edit", "Edit"), tools.map { it.name })
        assertTrue(tools.drop(1).all { it.status == ToolStatus.Succeeded })
        assertTrue(state.items.last() is AssistantTextItem)
        assertFalse(state.busy)
    }

    @Test
    fun `replayed messages get their file chips back`() {
        val state = replay("/transcripts/image-chat.jsonl")
        val users = state.items.filterIsInstance<UserItem>()
        assertEquals(1, users.size)
        assertEquals(
            "What color fills the attached image, and what does the attached Kotlin file print when run without arguments? One short sentence, no tools.",
            users.single().text,
        )
        assertEquals(listOf("Main.kt"), users.single().attachments.map { (it as FileAttachment).path })
        assertTrue((state.items.last() as AssistantTextItem).text.contains("blue"))
    }

    @Test
    fun `a compacted chat replays with a divider, not the summary as a message`() {
        val file = transcript(
            "compacted", 1_000,
            user("Add caching"),
            """{"type":"assistant","message":{"id":"m1","role":"assistant","content":[{"type":"text","text":"Done."}]}}""",
            """{"type":"system","subtype":"compact_boundary","content":"Conversation compacted","compactMetadata":{"trigger":"auto","preTokens":412000}}""",
            """{"type":"user","isCompactSummary":true,"message":{"role":"user","content":"This session is being continued from a previous conversation."}}""",
            user("Now the tests"),
        )
        val state = ChatState().also { TranscriptReplay.replay(ChatHistory.load(file), it) }
        assertEquals(listOf("Add caching", "Now the tests"), state.items.filterIsInstance<UserItem>().map { it.text })
        val divider = state.items.filterIsInstance<CompactedItem>().single()
        assertTrue(divider.automatic)
        assertEquals(412_000L, divider.before)
        assertEquals(state.items.indexOf(divider) + 1, state.items.indexOfFirst { it is UserItem && it.text == "Now the tests" })
    }

    @Test
    fun `times read like a chat list`() {
        val now = Instant.parse("2026-10-24T15:00:00Z")
        fun ago(seconds: Long) = relativeTime(now.minusSeconds(seconds).toEpochMilli(), now, ZoneOffset.UTC)
        assertEquals("just now", ago(20))
        assertEquals("5m ago", ago(300))
        assertEquals("3h ago", ago(3 * 3600))
        assertEquals("Yesterday", ago(20 * 3600))
        assertEquals("Oct 2", ago(22L * 86400))
    }

    private fun replay(resource: String): ChatState {
        val file = File(javaClass.getResource(resource)!!.toURI())
        return ChatState().also { TranscriptReplay.replay(ChatHistory.load(file), it) }
    }
}
