package `in`.bleuphoenix.claudechat.ui

import `in`.bleuphoenix.claudechat.ide.FileMatch
import `in`.bleuphoenix.claudechat.ide.ProjectFileSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextMessageTest {

    private fun file(path: String, lines: IntRange? = null, dir: Boolean = false) = FileAttachment("k", path, dir, lines)

    @Test
    fun `mentions use the CLI's syntax`() {
        assertEquals("@app/src/Main.kt", ContextMessage.mention(file("app/src/Main.kt")))
        assertEquals("@app/src/Main.kt#L12-20", ContextMessage.mention(file("app/src/Main.kt", 12..20)))
        assertEquals("@Main.kt#L7", ContextMessage.mention(file("Main.kt", 7..7)))
        assertEquals("@\"docs/fruit list.txt\"#L2-3", ContextMessage.mention(file("docs/fruit list.txt", 2..3)))
        assertEquals("@app/src/", ContextMessage.mention(file("app/src", dir = true)))
    }

    @Test
    fun `message carries mentions and a pointer to the open file`() {
        val message = ContextMessage.build(
            "Why does this crash?",
            listOf(file("A.kt"), file("B.kt", 3..4), file("A.kt")),
            ActiveFile("app/Main.kt", 42),
        )
        assertEquals("Why does this crash?\n\nContext: @A.kt @B.kt#L3-4\n\n(Open in my editor: app/Main.kt:42)", message)
        assertEquals("Hi", ContextMessage.build("Hi", emptyList(), null))
    }

    @Test
    fun `pasted and console text goes inside the message and comes back as a chip`() {
        val pasted = TextAttachment("k", "Pasted text", "fun a() {\n\n    b()\n}\n")
        val logcat = TextAttachment("k", "Logcat \"main\"", "E/App: boom")
        val message = ContextMessage.build("Why?", listOf(pasted, file("A.kt"), logcat), null)
        assertEquals(
            "Why?\n\n<attached-text source=\"Pasted text\">\nfun a() {\n\n    b()\n}\n</attached-text>\n\n" +
                "<attached-text source=\"Logcat &quot;main&quot;\">\nE/App: boom\n</attached-text>\n\nContext: @A.kt",
            message,
        )
        var keys = 0
        val (text, attachments) = ContextMessage.split(message) { "k${keys++}" }
        assertEquals("Why?", text)
        assertEquals(listOf("A.kt", "Pasted text · 4 lines", "Logcat \"main\" · 1 line"), attachments.map { it.label })
        assertEquals("fun a() {\n\n    b()\n}", (attachments[1] as TextAttachment).text)
    }

    @Test
    fun `a message can be only an attached text`() {
        assertEquals(
            "<attached-text source=\"Pasted text\">\nx\n</attached-text>",
            ContextMessage.build("", listOf(TextAttachment("k", "Pasted text", "x")), null),
        )
    }

    @Test
    fun `only long pastes become chips`() {
        assertEquals(false, TextAttachment.isLong("a short line"))
        assertEquals(false, TextAttachment.isLong((1..19).joinToString("\n")))
        assertEquals(true, TextAttachment.isLong((1..20).joinToString("\n")))
        assertEquals(true, TextAttachment.isLong("x".repeat(2000)))
    }

    @Test
    fun `labels show the name and lines`() {
        assertEquals("Main.kt:12-20", file("app/Main.kt", 12..20).label)
        assertEquals("src/", file("app/src", dir = true).label)
    }

    @Test
    fun `mention query is the at-word before the cursor`() {
        assertEquals("Mai" to 6, ContextMessage.mentionQuery("Check @Mai", 10))
        assertEquals("" to 0, ContextMessage.mentionQuery("@", 1))
        assertNull("an email address isn't a mention", ContextMessage.mentionQuery("mail me@home", 12))
        assertNull("finished by a space", ContextMessage.mentionQuery("@Main.kt and", 12))
        assertEquals("Ma" to 0, ContextMessage.mentionQuery("@Main.kt", 3))
    }

    @Test
    fun `file search ranks names before paths and fuzzy matches last`() {
        val files = listOf(
            FileMatch("app/src/main/java/com/example/MainActivity.kt", false),
            FileMatch("app/src/main/AndroidManifest.xml", false),
            FileMatch("Main.kt", false),
            FileMatch("docs/maintenance.md", false),
            FileMatch("app/src/main", true),
        )
        assertEquals(
            listOf("Main.kt", "app/src/main", "docs/maintenance.md", "app/src/main/java/com/example/MainActivity.kt", "app/src/main/AndroidManifest.xml"),
            ProjectFileSearch.rank(files, "main", 10).map { it.path },
        )
        assertEquals(listOf("app/src/main/java/com/example/MainActivity.kt"), ProjectFileSearch.rank(files, "mact", 10).map { it.path })
    }

    @Test
    fun `a slash at the start lists commands while the caret is in the first word`() {
        assertEquals("" to 0, ContextMessage.slashQuery("/", 1))
        assertEquals("comp" to 0, ContextMessage.slashQuery("/comp", 5))
        assertEquals("compact" to 0, ContextMessage.slashQuery("/compact keep the plan", 3))
        assertNull(ContextMessage.slashQuery("/compact keep", 13))
        assertNull(ContextMessage.slashQuery("/tmp/out.txt", 5))
        assertNull(ContextMessage.slashQuery("/x", 0))
        // Later in the message: after whitespace, just before the cursor.
        assertEquals("compact" to 4, ContextMessage.slashQuery("see /compact", 12))
        assertEquals("sim" to 9, ContextMessage.slashQuery("Fix it.\n\n/sim", 13))
        assertNull(ContextMessage.slashQuery("see src/main", 12))
        assertNull(ContextMessage.slashQuery("see /tmp/out", 12))
        assertNull(ContextMessage.slashQuery("see /compact now", 16))
        // A message that starts with a command only lists for that first word.
        assertNull(ContextMessage.slashQuery("/compact see /co", 16))
    }
}
