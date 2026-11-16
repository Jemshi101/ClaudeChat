package `in`.bleuphoenix.claudechat.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hint shown when the pointer rests on Accept or Reject under a change in the editor. */
class InlineReviewHintTest {

    @Test
    fun `names the action and its shortcut, and says the keys act at the caret`() {
        val accept = InlineReview.hintHtml(InlineReview.HunkBlock.Button.Accept, "⌘Y")
        assertTrue(accept, accept.startsWith("<html>Accept change&nbsp;&nbsp;<b>⌘Y</b>"))
        assertTrue(accept, accept.contains("at the caret"))
        val reject = InlineReview.hintHtml(InlineReview.HunkBlock.Button.Reject, "Ctrl+N")
        assertTrue(reject, reject.startsWith("<html>Reject change&nbsp;&nbsp;<b>Ctrl+N</b>"))
    }

    @Test
    fun `without a shortcut it only names the action`() {
        assertEquals("<html>Accept change</html>", InlineReview.hintHtml(InlineReview.HunkBlock.Button.Accept, ""))
    }

    @Test
    fun `each button points at the action registered for its keys`() {
        assertEquals("ClaudeChat.AcceptChange", InlineReview.HunkBlock.Button.Accept.actionId)
        assertEquals("ClaudeChat.RejectChange", InlineReview.HunkBlock.Button.Reject.actionId)
    }
}
