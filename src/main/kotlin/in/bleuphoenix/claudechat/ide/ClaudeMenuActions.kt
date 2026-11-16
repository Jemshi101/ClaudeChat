package `in`.bleuphoenix.claudechat.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import `in`.bleuphoenix.claudechat.ui.ClaudeChatToolWindowFactory

/** What a right-click action is about: lines of a file, a whole file, or a folder, as the chat attaches it. */
data class ClaudeTarget(val path: String, val isDirectory: Boolean = false, val lines: IntRange? = null)

/** The requests in the Claude right-click menu, each phrased for what it's about (see [prompt]). */
enum class ClaudeTask(val template: String) {
    Explain(
        "Explain {subject}: what it does, how it fits into the project, and anything surprising or easy to get " +
            "wrong. Don't change any files.",
    ),
    FindBugs(
        "Look for bugs in {subject}: logic errors, unhandled edge cases, null and error handling, threading and " +
            "leaked resources. List each one with why it's a bug, then fix them.",
    ),
    Refactor(
        "Refactor {subject} to be easier to read and maintain, without changing what it does. Follow the " +
            "project's existing conventions, then sum up the main changes.",
    ),
    WriteTests(
        "Write unit tests for {subject}, using the project's existing test framework, style and folders. Cover " +
            "the main behavior and edge cases, then run them if you can.",
    );

    /** The request for [targets]: the selected code, this file, these files, or the code in a folder. */
    fun prompt(targets: List<ClaudeTarget>): String {
        val subject = when {
            targets.any { it.isDirectory } -> if (targets.size == 1) "the code in this folder" else "the code in these folders and files"
            targets.size > 1 -> "these files"
            targets.single().lines != null -> "the selected code"
            else -> "this file"
        }
        return template.replace("{subject}", subject)
    }

    companion object {
        /**
         * What the event is about: the editor's selected lines or its file, or the files and folders selected in the
         * project tree.
         */
        fun targets(e: AnActionEvent): List<ClaudeTarget> {
            val project = e.project ?: return emptyList()
            val ide = IdeBridge(project)
            val editor = e.getData(CommonDataKeys.EDITOR)
            if (editor != null) {
                AskClaudeAboutSelectionAction.selectedLines(e)?.let { (path, lines) -> return listOf(ClaudeTarget(path, lines = lines)) }
                val file = editor.virtualFile ?: return emptyList()
                return listOf(ClaudeTarget(ide.displayPath(file)))
            }
            val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return emptyList()
            return files.filter { it.isInLocalFileSystem }.map { ClaudeTarget(ide.displayPath(it), it.isDirectory) }
        }
    }
}

/** Sends one of [ClaudeTask]'s requests about the selection, file or folder to the selected chat. */
abstract class ClaudeTaskAction(private val task: ClaudeTask) : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && ClaudeTask.targets(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val targets = ClaudeTask.targets(e).takeIf { it.isNotEmpty() } ?: return
        ClaudeChatToolWindowFactory.withChat(project) { chat ->
            chat.ask(task.prompt(targets), targets.map { chat.newFileAttachment(it.path, it.isDirectory, it.lines) })
        }
    }
}

class ExplainWithClaudeAction : ClaudeTaskAction(ClaudeTask.Explain)

class FindBugsWithClaudeAction : ClaudeTaskAction(ClaudeTask.FindBugs)

class RefactorWithClaudeAction : ClaudeTaskAction(ClaudeTask.Refactor)

class WriteTestsWithClaudeAction : ClaudeTaskAction(ClaudeTask.WriteTests)

/**
 * Starts a chat with the selected files or folders (or the editor's selection) already attached, ready for a
 * question. The chat still runs in the project folder, so reviewing, permissions and history work as usual.
 */
class NewChatAboutThisAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && ClaudeTask.targets(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val targets = ClaudeTask.targets(e).takeIf { it.isNotEmpty() } ?: return
        ClaudeChatToolWindowFactory.withChat(project, newChat = true) { chat ->
            targets.forEach { chat.attachFile(it.path, it.lines, it.isDirectory) }
            chat.focusInput()
        }
    }
}

/**
 * Output from the Run/Debug console or Logcat: the selected text, or the last [MAX_LINES] lines (at most
 * [MAX_CHARS] characters) when nothing is selected.
 */
object ConsoleOutput {
    const val MAX_LINES = 300
    const val MAX_CHARS = 30_000

    const val EXPLAIN_PROMPT =
        "Explain what this output shows. If it has an error, crash or failing test, find the cause in the project's " +
            "code and fix it."

    fun of(editor: Editor): String? {
        val selected = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
        return (selected ?: tail(editor.document.text)).trimEnd().takeIf { it.isNotBlank() }
    }

    fun tail(text: String): String {
        val lines = text.trimEnd().lines()
        return lines.takeLast(MAX_LINES).joinToString("\n").takeLast(MAX_CHARS)
    }
}

/**
 * In the Run/Debug console or Logcat: sends its output to the chat, asking Claude to explain it and fix what it
 * shows ([explain]), or only attaches it so the user can ask their own question.
 */
abstract class ConsoleOutputAction(private val source: String, private val explain: Boolean) : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && editor.document.textLength > 0
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = e.getData(CommonDataKeys.EDITOR)?.let(ConsoleOutput::of) ?: return
        ClaudeChatToolWindowFactory.withChat(project) { chat ->
            if (explain) chat.ask(ConsoleOutput.EXPLAIN_PROMPT, listOf(chat.newTextAttachment(source, text)))
            else chat.attachText(source, text)
        }
    }
}

class ExplainConsoleOutputAction : ConsoleOutputAction("Console output", explain = true)

class AddConsoleOutputAction : ConsoleOutputAction("Console output", explain = false)

class ExplainLogcatAction : ConsoleOutputAction("Logcat", explain = true)

class AddLogcatAction : ConsoleOutputAction("Logcat", explain = false)
