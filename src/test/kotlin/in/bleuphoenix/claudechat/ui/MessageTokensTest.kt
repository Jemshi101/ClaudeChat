package `in`.bleuphoenix.claudechat.ui

import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.ui.MessageTokens.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTokensTest {

    private val commands = listOf(SlashCommand("simplify-flow", "", ""), SlashCommand("clear", "", "", aliases = listOf("reset")))

    private fun tokens(text: String) = MessageTokens.find(text, commands).map { it.kind to text.substring(it.start, it.end) }

    @Test
    fun `known commands and skills anywhere, after whitespace`() {
        assertEquals(listOf(Kind.Command to "/clear"), tokens("/clear"))
        assertEquals(listOf(Kind.Command to "/simplify-flow"), tokens("Check the login change\n/simplify-flow please"))
        assertEquals(listOf(Kind.Command to "/reset"), tokens("then /reset"))
    }

    @Test
    fun `paths and unknown names are not commands`() {
        assertEquals(emptyList<Any>(), tokens("/tmp/out.txt and src/clear and /unknown and a/clear"))
        assertEquals(emptyList<Any>(), tokens("/simplify-flow/x"))
    }

    @Test
    fun `file mentions, quoted or not, with line ranges`() {
        assertEquals(
            listOf(Kind.File to "@src/Main.kt#L3-4", Kind.Command to "/clear", Kind.File to "@\"My Docs/a b.txt\"#L2"),
            tokens("see @src/Main.kt#L3-4 /clear @\"My Docs/a b.txt\"#L2"),
        )
        assertEquals(listOf(Kind.File to "@README.md"), tokens("(see @README.md), then"))
        assertEquals("an email address isn't a mention", emptyList<Any>(), tokens("mail me@example.com"))
    }
}
