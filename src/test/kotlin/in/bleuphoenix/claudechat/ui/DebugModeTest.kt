package `in`.bleuphoenix.claudechat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading Debug mode's `<reproduction_steps>` out of Claude's replies. */
class DebugModeTest {

    @Test
    fun `the last finished steps are read and hidden from the reply`() {
        val reply = "Ideas.\n\n<reproduction_steps>\n1. Old\n</reproduction_steps>\nMore.\n<reproduction_steps>\n1. Open it\n2. Rotate\n</reproduction_steps>"
        assertEquals("1. Open it\n2. Rotate", DebugMode.reproductionSteps(reply))
        assertEquals("Ideas.\n\n\nMore.", DebugMode.withoutSteps(reply))
    }

    @Test
    fun `steps still streaming aren't offered yet but are hidden`() {
        val streaming = "Ideas.\n\n<reproduction_steps>\n1. Open"
        assertNull(DebugMode.reproductionSteps(streaming))
        assertEquals("Ideas.", DebugMode.withoutSteps(streaming))
        assertNull(DebugMode.reproductionSteps("No steps here."))
    }

    @Test
    fun `the bar shows only in Debug mode, once Claude has stopped, for its latest reply`() {
        val state = ChatState()
        state.items += UserItem("u1", "bug")
        state.items += AssistantTextItem("a1", "<reproduction_steps>1. Go</reproduction_steps>")
        assertNull("not in Agent mode", DebugMode.pendingSteps(state))
        state.mode = PermissionMode.CHOICES.first { it.id == DebugMode.ID }
        assertEquals("1. Go", DebugMode.pendingSteps(state))
        state.busy = true
        assertNull(DebugMode.pendingSteps(state))
        state.busy = false
        state.items += UserItem("u2", "next")
        assertNull("answered already", DebugMode.pendingSteps(state))
    }
}
