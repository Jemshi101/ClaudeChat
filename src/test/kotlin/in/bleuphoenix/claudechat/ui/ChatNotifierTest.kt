package `in`.bleuphoenix.claudechat.ui

import com.google.gson.JsonParser
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotifierTest {

    private fun permission(tool: String, input: String) = PermissionItem(
        "k1", "r1", tool, "Allow Claude to use $tool?", "detail", null,
        JsonParser.parseString(input).asJsonObject, emptyList(), reviewable = false,
    )

    @Test
    fun `permission alerts say what Claude wants`() {
        assertEquals(
            ChatAlert(ChatAlert.Kind.NEEDS_YOU, "Claude wants to run a command", "./gradlew test", "k1"),
            ChatAlert.permission(permission("Bash", """{"command":"./gradlew test\necho done"}""")),
        )
        assertEquals("Main.kt", ChatAlert.permission(permission("Edit", """{"file_path":"/src/app/Main.kt"}""")).body)
        assertEquals("Main.kt", ChatAlert.permission(permission("Write", """{"file_path":"C:\\src\\Main.kt"}""")).body)
        ChatAlert.permission(permission("AskUserQuestion", """{"questions":[{"question":"Which flavor?","options":[]}]}""")).let {
            assertEquals("Claude has a question", it.title)
            assertEquals("Which flavor?", it.body)
        }
        ChatAlert.permission(permission("WebFetch", "{}")).let {
            assertEquals("Claude wants to use WebFetch", it.title)
            assertEquals("detail", it.body)
        }
    }

    @Test
    fun `finished alerts show the reply's first line`() {
        assertEquals("Fixed the login crash.", ChatAlert.finished("## Fixed the **login** crash.\n\nDetails…", 12.0).body)
        assertEquals("Done.", ChatAlert.finished(null, 12.0).body)
        val long = "word ".repeat(40)
        assertTrue(ChatAlert.finished(long, 12.0).body.let { it.length <= 121 && it.endsWith("word…") })
    }

    private fun options(onlyAway: Boolean = true, finished: Boolean = true, needsYou: Boolean = true) =
        ClaudeChatSettings.Options().apply {
            notifyOnlyWhenAway = onlyAway
            notifyFinished = finished
            notifyNeedsYou = needsYou
        }

    @Test
    fun `quiet while the user watches the chat`() {
        val needsYou = ChatAlert.permission(permission("Bash", """{"command":"ls"}"""))
        assertFalse(ChatNotifier.shouldShow(needsYou, watching = true, queued = false, options()))
        assertTrue(ChatNotifier.shouldShow(needsYou, watching = false, queued = false, options()))
        assertTrue(ChatNotifier.shouldShow(needsYou, watching = true, queued = false, options(onlyAway = false)))
        assertFalse(ChatNotifier.shouldShow(needsYou, watching = false, queued = false, options(needsYou = false)))
    }

    @Test
    fun `quiet for short turns and when a queued message runs next, not for failures`() {
        assertFalse(ChatNotifier.shouldShow(ChatAlert.finished("ok", 4.0), watching = false, queued = false, options()))
        assertTrue(ChatNotifier.shouldShow(ChatAlert.finished("ok", 30.0), watching = false, queued = false, options()))
        assertFalse(ChatNotifier.shouldShow(ChatAlert.finished("ok", 30.0), watching = false, queued = true, options()))
        assertFalse(ChatNotifier.shouldShow(ChatAlert.finished("ok", 30.0), watching = false, queued = false, options(finished = false)))
        assertTrue(ChatNotifier.shouldShow(ChatAlert.failed("Overloaded"), watching = false, queued = false, options()))
    }
}
