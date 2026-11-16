package `in`.bleuphoenix.claudechat.ide

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.SystemInfo
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Makes the IDE's keymap work in the Compose views: Compose text fields and selections only know the platform's own
 * keys (⌘C, ⌥←, … on macOS), while the IDE may use another keymap (e.g. the Windows one on a Mac, with Ctrl+C,
 * Home/End and Ctrl+←). A key pressed in [component] that the active keymap binds to an editing action is sent on as
 * the platform's key for that action.
 */
object KeymapBridge {

    /** Editing actions and how to ask Compose for them, by name of the platform key. */
    private enum class Native(val mac: String, val other: String) {
        COPY("meta C", "ctrl C"),
        CUT("meta X", "ctrl X"),
        PASTE("meta V", "ctrl V"),
        SELECT_ALL("meta A", "ctrl A"),
        UNDO("meta Z", "ctrl Z"),
        REDO("shift meta Z", "shift ctrl Z"),
        LINE_START("meta LEFT", "HOME"),
        LINE_END("meta RIGHT", "END"),
        LINE_START_SELECT("shift meta LEFT", "shift HOME"),
        LINE_END_SELECT("shift meta RIGHT", "shift END"),
        TEXT_START("meta UP", "ctrl HOME"),
        TEXT_END("meta DOWN", "ctrl END"),
        TEXT_START_SELECT("shift meta UP", "shift ctrl HOME"),
        TEXT_END_SELECT("shift meta DOWN", "shift ctrl END"),
        WORD_LEFT("alt LEFT", "ctrl LEFT"),
        WORD_RIGHT("alt RIGHT", "ctrl RIGHT"),
        WORD_LEFT_SELECT("shift alt LEFT", "shift ctrl LEFT"),
        WORD_RIGHT_SELECT("shift alt RIGHT", "shift ctrl RIGHT"),
        DELETE_WORD_BACK("alt BACK_SPACE", "ctrl BACK_SPACE"),
        DELETE_WORD_FORWARD("alt DELETE", "ctrl DELETE");

        fun stroke(mac: Boolean): KeyStroke = KeyStroke.getKeyStroke(if (mac) this.mac else other)
    }

    private val ACTIONS = mapOf(
        "\$Copy" to Native.COPY,
        "\$Cut" to Native.CUT,
        "\$Paste" to Native.PASTE,
        "\$SelectAll" to Native.SELECT_ALL,
        "\$Undo" to Native.UNDO,
        "\$Redo" to Native.REDO,
        "EditorLineStart" to Native.LINE_START,
        "EditorLineEnd" to Native.LINE_END,
        "EditorLineStartWithSelection" to Native.LINE_START_SELECT,
        "EditorLineEndWithSelection" to Native.LINE_END_SELECT,
        "EditorTextStart" to Native.TEXT_START,
        "EditorTextEnd" to Native.TEXT_END,
        "EditorTextStartWithSelection" to Native.TEXT_START_SELECT,
        "EditorTextEndWithSelection" to Native.TEXT_END_SELECT,
        "EditorPreviousWord" to Native.WORD_LEFT,
        "EditorNextWord" to Native.WORD_RIGHT,
        "EditorPreviousWordWithSelection" to Native.WORD_LEFT_SELECT,
        "EditorNextWordWithSelection" to Native.WORD_RIGHT_SELECT,
        "EditorDeleteToWordStart" to Native.DELETE_WORD_BACK,
        "EditorDeleteToWordEnd" to Native.DELETE_WORD_FORWARD,
    )

    /**
     * The platform key to send for [stroke], given the actions the keymap binds it to; null when it's no editing
     * action or already the platform's key.
     */
    fun translate(stroke: KeyStroke, actionIds: List<String>, mac: Boolean = SystemInfo.isMac): KeyStroke? {
        val native = actionIds.firstNotNullOfOrNull { ACTIONS[it] }?.stroke(mac) ?: return null
        return native.takeIf { it != stroke }
    }

    fun install(component: Component, parent: Disposable) {
        var forwarding = false
        IdeEventQueue.getInstance().addDispatcher({ event ->
            if (forwarding || event !is KeyEvent || event.id == KeyEvent.KEY_RELEASED) return@addDispatcher false
            val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return@addDispatcher false
            if (!SwingUtilities.isDescendingFrom(focused, component)) return@addDispatcher false
            if (event.id == KeyEvent.KEY_TYPED) {
                // The character typed with a translated key (Ctrl+C's \u0003) must not reach the text field.
                return@addDispatcher event.keyChar.code < 32 && event.isControlDown && pendingTyped.remove(focused)
            }
            val stroke = KeyStroke.getKeyStrokeForEvent(event)
            val ids = KeymapManager.getInstance().activeKeymap.getActionIds(KeyboardShortcut(stroke, null)).toList()
            val native = translate(stroke, ids) ?: return@addDispatcher false
            forwarding = true
            try {
                for (id in intArrayOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
                    focused.dispatchEvent(
                        KeyEvent(focused, id, event.`when`, native.modifiers and MODIFIER_MASK, native.keyCode, KeyEvent.CHAR_UNDEFINED),
                    )
                }
            } finally {
                forwarding = false
            }
            if (event.isControlDown) pendingTyped += focused
            event.consume()
            true
        }, parent)
    }

    private val pendingTyped = HashSet<Component>()

    private const val MODIFIER_MASK =
        InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK or InputEvent.ALT_DOWN_MASK
}
