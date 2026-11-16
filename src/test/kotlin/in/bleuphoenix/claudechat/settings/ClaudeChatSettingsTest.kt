package `in`.bleuphoenix.claudechat.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeChatSettingsTest {

    private fun loaded(setUp: ClaudeChatSettings.Options.() -> Unit) =
        ClaudeChatSettings().apply { loadState(ClaudeChatSettings.Options().apply(setUp)) }.state

    @Test
    fun `new installs start with Cursor command sync off`() {
        val settings = ClaudeChatSettings().apply { noStateLoaded() }
        assertFalse(settings.state.syncCursorCommands)
        assertEquals(ClaudeChatSettings.SETTINGS_VERSION, settings.state.settingsVersion)
    }

    @Test
    fun `users from before keep what they had`() {
        // Never touched: on, the old default.
        assertTrue(loaded { }.syncCursorCommands)
        // Turned off under the old default.
        assertFalse(loaded { cursorCommands = false }.syncCursorCommands)
    }

    @Test
    fun `settings saved under the new defaults aren't carried over again`() {
        val state = loaded { settingsVersion = 2 }
        assertFalse(state.syncCursorCommands)
        assertTrue(loaded { settingsVersion = 2; syncCursorCommands = true }.syncCursorCommands)
    }
}
