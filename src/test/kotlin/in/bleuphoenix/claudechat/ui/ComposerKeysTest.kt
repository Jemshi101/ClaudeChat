package `in`.bleuphoenix.claudechat.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import `in`.bleuphoenix.claudechat.cli.CliCatalog
import `in`.bleuphoenix.claudechat.cli.SlashCommand
import `in`.bleuphoenix.claudechat.ui.compose.ChatActions
import `in`.bleuphoenix.claudechat.ui.compose.Composer
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Canvas
import java.lang.reflect.Proxy
import java.awt.event.KeyEvent as AwtKeyEvent

/** The message box's keyboard handling, driven with real key events in an offscreen Compose scene. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ComposerKeysTest {

    private val state = ChatState()
    private val calls = mutableListOf<String>()
    private val source = Canvas()
    private lateinit var scene: ImageComposeScene
    private var time = 0L

    private val commands = listOf(
        SlashCommand("compact", "Summarize", "<instructions>"),
        SlashCommand("context", "Show context usage", ""),
        SlashCommand("clear", "Start over", ""),
    )

    /** Records each call as "name(args)"; the slash list behaves like the real controller's. */
    private val actions = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ChatActions::class.java)) { _, method, args ->
        val arguments = args.orEmpty().map { (it as? SlashCommand)?.name ?: it }
        calls += "${method.name}(${arguments.joinToString()})"
        when (method.name) {
            "slashChanged" -> state.slash = (args[0] as String?)?.let { SlashState(it, CliCatalog.match(commands, it), start = args[1] as Int) }
            "moveSlashSelection" -> state.slash = state.slash?.let { SlashState(it.query, it.results, (it.selected + args[0] as Int).mod(it.results.size), it.start) }
        }
        if (method.returnType == Boolean::class.javaPrimitiveType) false else null
    } as ChatActions

    @Before
    fun setUp() {
        state.commands = commands
        state.focusRequests++
        scene = ImageComposeScene(800, 600, Density(1f)) { IntUiTheme(isDark = true) { Composer(state, actions) } }
        frames()
    }

    @After
    fun tearDown() = scene.close()

    @Test
    fun `typing a slash lists commands, arrows choose and Tab completes`() {
        type("/co")
        assertTrue(calls.contains("slashChanged(co, 0)"))
        assertEquals(listOf("compact", "context"), state.slash!!.results.map { it.name })

        press(AwtKeyEvent.VK_DOWN)
        assertEquals(1, state.slash!!.selected)
        press(AwtKeyEvent.VK_TAB)
        assertTrue(calls.contains("pickSlash(context)"))
        assertTrue("Tab doesn't send", calls.none { it.startsWith("send") })
    }

    @Test
    fun `Enter on a fully typed command sends it, Enter on a partial one completes it`() {
        type("/com")
        press(AwtKeyEvent.VK_ENTER)
        assertTrue(calls.contains("pickSlash(compact)"))
        assertTrue(calls.none { it.startsWith("send") })

        state.input.edit { replace(0, length, "") }
        frames()
        type("/clear")
        press(AwtKeyEvent.VK_ENTER)
        assertEquals("send()", calls.last())
    }

    @Test
    fun `a slash later in the message lists commands too, and Enter picks rather than sends`() {
        type("Fix the login bug /compact")
        assertTrue(calls.contains("slashChanged(compact, 18)"))
        press(AwtKeyEvent.VK_ENTER)
        assertTrue(calls.contains("pickSlash(compact)"))
        assertTrue(calls.none { it.startsWith("send") })
    }

    @Test
    fun `Escape hides the command list until the command word is gone`() {
        type("/co")
        press(AwtKeyEvent.VK_ESCAPE)
        assertNull(state.slash)
        type("n")
        assertNull(state.slash)
        assertEquals("slashChanged(null, 0)", calls.last { it.startsWith("slashChanged") })
    }

    @Test
    fun `Enter sends, Shift+Enter adds a line, Shift+Tab switches mode`() {
        type("hello")
        press(AwtKeyEvent.VK_ENTER, AwtKeyEvent.SHIFT_DOWN_MASK)
        assertTrue(calls.none { it.startsWith("send") })
        assertEquals("hello\n", state.input.text.toString())

        press(AwtKeyEvent.VK_TAB, AwtKeyEvent.SHIFT_DOWN_MASK)
        assertTrue(calls.any { it.startsWith("selectMode") })

        press(AwtKeyEvent.VK_ENTER)
        assertEquals("send()", calls.last())
    }

    @Test
    fun `an at sign looks up files`() {
        type("see @Mai")
        assertTrue(calls.contains("mentionChanged(Mai, 4)"))
        assertNull("not a command", state.slash)
    }

    private fun type(text: String) = text.forEach { char ->
        send(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, char, 0)
        frames()
    }

    private fun press(keyCode: Int, modifiers: Int = 0) {
        val char = when (keyCode) {
            AwtKeyEvent.VK_ENTER -> '\n'
            AwtKeyEvent.VK_TAB -> '\t'
            else -> AwtKeyEvent.CHAR_UNDEFINED
        }
        send(AwtKeyEvent.KEY_PRESSED, keyCode, char, modifiers)
        send(AwtKeyEvent.KEY_RELEASED, keyCode, char, modifiers)
        frames()
    }

    private fun send(id: Int, keyCode: Int, char: Char, modifiers: Int) {
        val awt = AwtKeyEvent(source, id, System.currentTimeMillis(), modifiers, keyCode, char)
        val type = when (id) {
            AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
            AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
            else -> KeyEventType.Unknown
        }
        scene.sendKeyEvent(
            KeyEvent(
                key = KEYS[keyCode] ?: Key.Unknown,
                type = type,
                codePoint = if (char == AwtKeyEvent.CHAR_UNDEFINED) 0 else char.code,
                isCtrlPressed = modifiers and AwtKeyEvent.CTRL_DOWN_MASK != 0,
                isMetaPressed = modifiers and AwtKeyEvent.META_DOWN_MASK != 0,
                isAltPressed = false,
                isShiftPressed = modifiers and AwtKeyEvent.SHIFT_DOWN_MASK != 0,
                nativeEvent = awt,
            ),
        )
    }

    private companion object {
        val KEYS = mapOf(
            AwtKeyEvent.VK_ENTER to Key.Enter,
            AwtKeyEvent.VK_TAB to Key.Tab,
            AwtKeyEvent.VK_ESCAPE to Key.Escape,
            AwtKeyEvent.VK_DOWN to Key.DirectionDown,
            AwtKeyEvent.VK_UP to Key.DirectionUp,
        )
    }

    /** Lets effects (focus, the slash and mention watchers) run. */
    private fun frames() = repeat(5) {
        scene.render(time)
        time += 16_000_000L
        Thread.sleep(5)
    }
}
