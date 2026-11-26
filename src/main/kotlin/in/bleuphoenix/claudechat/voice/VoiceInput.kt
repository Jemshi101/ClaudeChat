package `in`.bleuphoenix.claudechat.voice

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Robot
import java.awt.event.KeyEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/** How the mic button turns speech into text (Settings → Voice input). */
enum class VoiceEngine(val id: String, val label: String) {
    AUTO("auto", "Auto: local Whisper, else system dictation"),
    WHISPER("whisper", "Local Whisper"),
    SYSTEM("system", "System dictation"),
    ;

    companion object {
        fun of(id: String?): VoiceEngine = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** What a press of the mic button does, or why it can't do anything. */
sealed interface VoicePlan {
    data class Whisper(val executable: File, val model: File) : VoicePlan
    data object SystemDictation : VoicePlan
    data class Unavailable(val reason: String) : VoicePlan
}

object VoiceInput {

    /**
     * Picks the engine for [engine]: Auto uses local Whisper when it's installed with a model, then the system's
     * dictation; a fixed choice says what's missing instead of falling back.
     */
    fun plan(engine: VoiceEngine, whisper: File?, model: File?, systemDictation: Boolean = SystemDictation.available()): VoicePlan {
        val whisperPlan = if (whisper != null && model != null) VoicePlan.Whisper(whisper, model) else null
        val whisperMissing = when {
            whisper == null -> "Local Whisper isn't installed: run brew install whisper-cpp (or set its path in Settings → Claude Chat Suite → Voice Input)."
            else -> "Download a Whisper model in Settings → Claude Chat Suite → Voice Input."
        }
        val systemMissing = "This system has no dictation the plugin can start; install whisper-cpp for local Whisper."
        return when (engine) {
            VoiceEngine.WHISPER -> whisperPlan ?: VoicePlan.Unavailable(whisperMissing)
            VoiceEngine.SYSTEM -> if (systemDictation) VoicePlan.SystemDictation else VoicePlan.Unavailable(systemMissing)
            VoiceEngine.AUTO -> whisperPlan ?: if (systemDictation) VoicePlan.SystemDictation else VoicePlan.Unavailable(whisperMissing)
        }
    }
}

/** whisper.cpp's command-line tool, run on a recording to get its text. Nothing leaves the machine. */
object Whisper {

    private val LOG = logger<Whisper>()

    /** The models offered for download: name, size, and whether it only knows English. */
    data class Model(val name: String, val label: String) {
        val fileName get() = "ggml-$name.bin"
        val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
    }

    val MODELS = listOf(
        Model("base.en", "base.en: English, 142 MB (recommended)"),
        Model("base", "base: any language, 142 MB"),
        Model("small.en", "small.en: English, more accurate, 466 MB"),
        Model("small", "small: any language, more accurate, 466 MB"),
    )

    private val NAMES = listOf("whisper-cli", "whisper-cpp")
    private val SEARCH = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", System.getProperty("user.home") + "/.local/bin")

    /** The executable set in [custom], else whisper-cli on the PATH or where Homebrew puts it. */
    fun findExecutable(custom: String?): File? {
        custom?.takeIf { it.isNotBlank() }?.let { path -> return File(path).takeIf { it.canExecute() } }
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() } + SEARCH
        val exts = if (SystemInfo.isWindows) listOf(".exe", "") else listOf("")
        return NAMES.firstNotNullOfOrNull { name ->
            dirs.firstNotNullOfOrNull { dir -> exts.map { File(dir, name + it) }.firstOrNull { it.isFile && it.canExecute() } }
        }
    }

    /** Where the plugin keeps downloaded models. */
    fun modelDir(): File = File(PathManager.getSystemPath(), "claude-chat/whisper")

    /** The chosen model ([name], e.g. "base.en"), downloaded or found where whisper.cpp installs keep theirs. */
    fun findModel(name: String, dirs: List<File> = modelDirs()): File? =
        dirs.map { File(it, "ggml-$name.bin") }.firstOrNull { it.isFile && it.length() > 1_000_000 }

    fun modelDirs(): List<File> {
        val home = System.getProperty("user.home")
        return listOf(
            modelDir(),
            File("/opt/homebrew/share/whisper-cpp"),
            File("/usr/local/share/whisper-cpp"),
            File(home, "whisper.cpp/models"),
            File(home, ".cache/whisper.cpp"),
        )
    }

    fun command(executable: File, model: File, wav: File): List<String> = listOf(
        executable.path, "-m", model.path, "-f", wav.path,
        "-nt", "-np",
        "-l", if (model.name.contains(".en.")) "en" else "auto",
    )

    /** The spoken text in whisper-cli's output, without markers like [BLANK_AUDIO] or (wind blowing). */
    fun text(output: String): String =
        output.lines()
            .map { it.replace(Regex("\\[[^]]*]|\\([^)]*\\)|\\*[^*]*\\*"), "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Transcribes [wav]; throws with whisper's message when it fails. Blocks; call off the EDT. */
    fun transcribe(executable: File, model: File, wav: File): String {
        val process = ProcessBuilder(command(executable, model, wav)).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Whisper took too long.")
        }
        if (process.exitValue() != 0) {
            LOG.warn("whisper-cli failed (${process.exitValue()}): $errors")
            error(errors.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "whisper-cli failed (${process.exitValue()}).")
        }
        return text(output)
    }
}

/** Records the default microphone as 16 kHz mono, the format Whisper takes. */
class Recorder {

    private val format = AudioFormat(16_000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    /** Peak level of the recording so far, 0 to 1; stays 0 when the system gives silence (no mic permission). */
    @Volatile
    var peak = 0f
        private set

    /** Starts recording; throws when there's no microphone. */
    fun start() {
        val line = AudioSystem.getTargetDataLine(format)
        line.open(format)
        line.start()
        this.line = line
        thread = Thread({
            val chunk = ByteArray(3_200)
            while (line.isOpen) {
                val read = line.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    if (!line.isActive) break else continue
                }
                synchronized(buffer) { buffer.write(chunk, 0, read) }
                var max = 0
                for (i in 0 until read - 1 step 2) {
                    val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                    max = maxOf(max, kotlin.math.abs(sample.toShort().toInt()))
                }
                peak = maxOf(peak, max / 32_768f)
            }
        }, "Claude Chat voice recording").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops and returns the recording as a WAV file, or null when nothing was recorded. */
    fun stop(): File? {
        cancel()
        val bytes = synchronized(buffer) { buffer.toByteArray() }
        if (bytes.size < format.frameSize * 1_600) return null
        val file = File.createTempFile("claude-chat-voice-", ".wav")
        file.deleteOnExit()
        AudioInputStream(ByteArrayInputStream(bytes), format, (bytes.size / format.frameSize).toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
        }
        return file
    }

    fun cancel() {
        line?.let {
            it.stop()
            it.close()
        }
        line = null
        thread?.join(1_000)
        thread = null
    }
}

/** The operating system's own dictation, started into the focused text field. */
object SystemDictation {

    private val LOG = logger<SystemDictation>()

    fun available(): Boolean = SystemInfo.isMac || SystemInfo.isWindows

    /** Starts dictation; false when the system didn't take it (dictation off, or not supported here). */
    fun start(): Boolean = runCatching {
        when {
            SystemInfo.isMac -> {
                var handled = false
                Foundation.executeOnMainThread(true, true) {
                    val app = Foundation.invoke("NSApplication", "sharedApplication")
                    // The selector straight from the Objective-C runtime: Foundation.createSelector's return type
                    // differs between IDE versions.
                    val selector = NativeLibrary.getInstance("objc").getFunction("sel_registerName")
                        .invoke(Pointer::class.java, arrayOf("startDictation:")) as Pointer
                    handled = Foundation.invoke(app, "sendAction:to:from:", selector, ID.NIL, ID.NIL).booleanValue()
                }
                handled
            }
            SystemInfo.isWindows -> {
                // Win+H opens Windows voice typing in the focused field.
                Robot().apply {
                    keyPress(KeyEvent.VK_WINDOWS)
                    keyPress(KeyEvent.VK_H)
                    keyRelease(KeyEvent.VK_H)
                    keyRelease(KeyEvent.VK_WINDOWS)
                }
                true
            }
            else -> false
        }
    }.onFailure { LOG.warn("Couldn't start system dictation", it) }.getOrDefault(false)
}
