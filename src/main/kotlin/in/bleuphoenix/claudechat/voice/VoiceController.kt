package `in`.bleuphoenix.claudechat.voice

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import `in`.bleuphoenix.claudechat.ui.ChatState
import `in`.bleuphoenix.claudechat.ui.VoicePhase
import java.io.File
import javax.swing.Timer

/**
 * The chat's mic button. With local Whisper it records until pressed again (or [MAX_SECONDS]), transcribes, and
 * puts the text in the message box at the cursor; with system dictation it hands the focused box to the system.
 * Called on the EDT.
 */
class VoiceController(
    private val state: ChatState,
    private val background: (() -> Unit) -> Unit,
    private val onEdt: (() -> Unit) -> Unit,
) {
    private var recorder: Recorder? = null
    private var whisper: VoicePlan.Whisper? = null
    private var limit: Timer? = null

    fun toggle() {
        when (state.voice) {
            VoicePhase.Idle -> start()
            VoicePhase.Recording -> finish()
            VoicePhase.Transcribing -> Unit
        }
    }

    private fun start() {
        state.voiceMessage = null
        val options = ClaudeChatSettings.options
        val plan = VoiceInput.plan(
            VoiceEngine.of(options.voiceInput),
            Whisper.findExecutable(options.whisperPath),
            Whisper.findModel(options.whisperModel ?: Whisper.MODELS.first().name),
        )
        when (plan) {
            is VoicePlan.Unavailable -> state.voiceMessage = plan.reason
            VoicePlan.SystemDictation -> startDictation()
            is VoicePlan.Whisper -> record(plan)
        }
    }

    private fun startDictation() {
        state.focusRequests++
        // The system dictates into the focused field; give the box a moment to take the focus.
        Timer(150) {
            if (!SystemDictation.start()) {
                state.voiceMessage = if (SystemInfo.isMac) {
                    "Couldn't start Dictation. Turn it on in System Settings → Keyboard → Dictation, or press its shortcut " +
                        "(Fn twice, or the mic key) in the message box. For dictation that's always available, install " +
                        "whisper-cpp."
                } else {
                    "Couldn't start the system's dictation. Install whisper-cpp for local Whisper."
                }
            }
        }.apply { isRepeats = false }.start()
    }

    private fun record(plan: VoicePlan.Whisper) {
        val recorder = Recorder()
        try {
            recorder.start()
        } catch (e: Exception) {
            LOG.warn("Couldn't open the microphone", e)
            state.voiceMessage = "Couldn't open the microphone: ${e.message ?: "no input device"}."
            return
        }
        this.recorder = recorder
        whisper = plan
        state.voice = VoicePhase.Recording
        state.voiceStartedAt = System.currentTimeMillis()
        limit = Timer(MAX_SECONDS * 1000) { if (state.voice == VoicePhase.Recording) finish() }.apply {
            isRepeats = false
            start()
        }
    }

    private fun finish() {
        val recorder = recorder ?: return
        val plan = whisper ?: return
        stopTimer()
        this.recorder = null
        state.voice = VoicePhase.Transcribing
        background {
            var wav: File? = null
            val result = runCatching {
                wav = recorder.stop()
                when {
                    wav == null -> Result.failure<String>(IllegalStateException("Nothing was recorded."))
                    recorder.peak < SILENCE -> Result.failure(IllegalStateException(NO_SOUND))
                    else -> Result.success(Whisper.transcribe(plan.executable, plan.model, wav!!))
                }
            }.getOrElse { Result.failure(it) }
            wav?.delete()
            onEdt {
                state.voice = VoicePhase.Idle
                result.onSuccess { text ->
                    if (text.isBlank()) state.voiceMessage = "Didn't catch anything; try again a little closer to the mic."
                    else insert(state.input, text)
                    state.focusRequests++
                }.onFailure {
                    LOG.warn("Voice input failed", it)
                    state.voiceMessage = "Voice input failed: ${it.message}"
                }
            }
        }
    }

    fun cancel() {
        stopTimer()
        recorder?.cancel()
        recorder = null
        if (state.voice == VoicePhase.Recording) state.voice = VoicePhase.Idle
    }

    private fun stopTimer() {
        limit?.stop()
        limit = null
    }

    companion object {
        private val LOG = logger<VoiceController>()

        /** A recording stops on its own after this long. */
        const val MAX_SECONDS = 300

        /** Below this peak level the recording is silence: usually the IDE has no microphone permission. */
        private const val SILENCE = 0.002f

        private val NO_SOUND = if (SystemInfo.isMac) {
            "The recording was silent. Allow Android Studio to use the microphone in System Settings → Privacy & Security " +
                "→ Microphone, then restart it."
        } else {
            "The recording was silent; check the microphone and its input level."
        }

        /** Puts [text] at the cursor (replacing a selection), with a space between it and a word before or after. */
        fun insert(input: TextFieldState, text: String) {
            val current = input.text
            val start = input.selection.min
            val end = input.selection.max
            val before = if (start > 0 && !current[start - 1].isWhitespace()) " " else ""
            val after = if (end < current.length && !current[end].isWhitespace()) " " else ""
            val inserted = before + text.trim() + after
            input.edit {
                replace(start, end, inserted)
                selection = TextRange(start + inserted.length - after.length)
            }
        }
    }
}
