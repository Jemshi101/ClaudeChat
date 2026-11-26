package `in`.bleuphoenix.claudechat.voice

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceInputTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val exe = File("/opt/homebrew/bin/whisper-cli")
    private val model = File("/models/ggml-base.en.bin")

    @Test
    fun `auto uses whisper when it's ready, else system dictation, else says what to install`() {
        assertEquals(VoicePlan.Whisper(exe, model), VoiceInput.plan(VoiceEngine.AUTO, exe, model, systemDictation = true))
        assertEquals(VoicePlan.SystemDictation, VoiceInput.plan(VoiceEngine.AUTO, exe, null, systemDictation = true))
        assertEquals(VoicePlan.SystemDictation, VoiceInput.plan(VoiceEngine.AUTO, null, null, systemDictation = true))
        val none = VoiceInput.plan(VoiceEngine.AUTO, null, null, systemDictation = false) as VoicePlan.Unavailable
        assertTrue(none.reason, none.reason.contains("brew install whisper-cpp"))
    }

    @Test
    fun `a fixed choice doesn't fall back`() {
        val noModel = VoiceInput.plan(VoiceEngine.WHISPER, exe, null, systemDictation = true) as VoicePlan.Unavailable
        assertTrue(noModel.reason, noModel.reason.contains("Download a Whisper model"))
        assertEquals(VoicePlan.SystemDictation, VoiceInput.plan(VoiceEngine.SYSTEM, exe, model, systemDictation = true))
        assertTrue(VoiceInput.plan(VoiceEngine.SYSTEM, exe, model, systemDictation = false) is VoicePlan.Unavailable)
        assertEquals(VoiceEngine.AUTO, VoiceEngine.of("nonsense"))
    }

    @Test
    fun `whisper output loses its markers`() {
        assertEquals(
            "Add a mic button next to send. Use local Whisper.",
            Whisper.text("\n [BLANK_AUDIO]\n Add a mic button next to send.\n (keyboard clicking) Use local Whisper.\n *music*\n"),
        )
        assertEquals("", Whisper.text("[BLANK_AUDIO]\n"))
    }

    @Test
    fun `english models are told the language`() {
        val wav = File("/tmp/a.wav")
        assertEquals(listOf(exe.path, "-m", model.path, "-f", wav.path, "-nt", "-np", "-l", "en"), Whisper.command(exe, model, wav))
        assertEquals("auto", Whisper.command(exe, File("/models/ggml-small.bin"), wav).last())
    }

    @Test
    fun `whisper-cli runs on the recording`() {
        val fake = File(temp.root, "whisper-cli").apply {
            writeText("#!/bin/sh\necho ' [BLANK_AUDIO]'\necho \" Heard \$4\"\n")
            setExecutable(true)
        }
        val wav = temp.newFile("voice.wav")
        assertEquals(fake, Whisper.findExecutable(fake.path))
        assertEquals("Heard ${wav.path}", Whisper.transcribe(fake, model, wav))

        val failing = File(temp.root, "failing").apply {
            writeText("#!/bin/sh\necho 'error: failed to open model' >&2\nexit 3\n")
            setExecutable(true)
        }
        val error = runCatching { Whisper.transcribe(failing, model, wav) }.exceptionOrNull()
        assertEquals("error: failed to open model", error?.message)
    }

    @Test
    fun `models are found by name, partial downloads ignored`() {
        val dir = temp.newFolder("models")
        File(dir, "ggml-base.en.bin").writeBytes(ByteArray(2_000_000))
        File(dir, "ggml-small.bin").writeBytes(ByteArray(10))
        assertEquals(File(dir, "ggml-base.en.bin"), Whisper.findModel("base.en", listOf(temp.newFolder("empty"), dir)))
        assertNull(Whisper.findModel("small", listOf(dir)))
        assertNull(Whisper.findExecutable(File(temp.root, "missing").path))
    }

    @Test
    fun `dictated text goes in at the cursor with spaces around it`() {
        val input = TextFieldState("Fix thebug", TextRange(7))
        VoiceController.insert(input, " login ")
        assertEquals("Fix the login bug", input.text.toString())
        assertEquals(TextRange(13), input.selection)

        val empty = TextFieldState("")
        VoiceController.insert(empty, "Hello")
        assertEquals("Hello", empty.text.toString())

        val selected = TextFieldState("say OLD now", TextRange(4, 7))
        VoiceController.insert(selected, "new")
        assertEquals("say new now", selected.text.toString())
    }
}
