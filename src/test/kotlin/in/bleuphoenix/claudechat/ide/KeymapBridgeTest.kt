package `in`.bleuphoenix.claudechat.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.swing.KeyStroke

class KeymapBridgeTest {

    private fun key(text: String) = KeyStroke.getKeyStroke(text)

    @Test
    fun `a Windows-style keymap on a Mac sends the Mac keys`() {
        assertEquals(key("meta C"), KeymapBridge.translate(key("ctrl C"), listOf("\$Copy"), mac = true))
        assertEquals(key("meta V"), KeymapBridge.translate(key("ctrl V"), listOf("\$Paste"), mac = true))
        assertEquals(key("meta A"), KeymapBridge.translate(key("ctrl A"), listOf("\$SelectAll"), mac = true))
        assertEquals(key("shift meta Z"), KeymapBridge.translate(key("ctrl Y"), listOf("Editor Redo", "\$Redo"), mac = true))
        assertEquals(key("meta LEFT"), KeymapBridge.translate(key("HOME"), listOf("EditorLineStart"), mac = true))
        assertEquals(key("shift alt RIGHT"), KeymapBridge.translate(key("shift ctrl RIGHT"), listOf("EditorNextWordWithSelection"), mac = true))
    }

    @Test
    fun `a Mac keymap elsewhere sends that platform's keys`() {
        assertEquals(key("ctrl C"), KeymapBridge.translate(key("meta C"), listOf("\$Copy"), mac = false))
        assertEquals(key("HOME"), KeymapBridge.translate(key("meta LEFT"), listOf("EditorLineStart"), mac = false))
    }

    @Test
    fun `platform keys and keys for other actions pass through`() {
        assertNull(KeymapBridge.translate(key("meta C"), listOf("\$Copy"), mac = true))
        assertNull(KeymapBridge.translate(key("ctrl D"), listOf("EditorDeleteLine"), mac = true))
        assertNull(KeymapBridge.translate(key("ENTER"), emptyList(), mac = true))
    }
}
