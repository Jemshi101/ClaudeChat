package `in`.bleuphoenix.claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeLoginTest {

    // What `claude auth login` 2.1.311 prints without a terminal, with the address as an OSC 8 hyperlink.
    private val url = "https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=y"
    private val printed = "Opening browser to sign in…\nIf the browser didn't open, visit: \u001B]8;;$url\u0007$url\u001B]8;;\u0007\nPaste code here if prompted > "

    @Test
    fun `the sign-in address is read from the CLI's output once it's complete`() {
        assertEquals(url, ClaudeLogin.signInUrl(printed))
        assertNull("still arriving", ClaudeLogin.signInUrl(printed.substring(0, printed.indexOf(url) + 20)))
        assertNull(ClaudeLogin.signInUrl("Opening browser to sign in…\n"))
    }

    @Test
    fun `terminal escapes are dropped from the output`() {
        assertEquals(
            "Opening browser to sign in…\nIf the browser didn't open, visit: $url\nPaste code here if prompted > ",
            ClaudeLogin.plain(printed),
        )
    }
}
