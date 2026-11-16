package `in`.bleuphoenix.claudechat.ide

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import `in`.bleuphoenix.claudechat.ui.ClaudeChatToolWindowFactory

/** An error or warning the IDE shows, on [line] (1-based). */
data class CodeProblem(val line: Int, val message: String, val isError: Boolean)

/**
 * Alt+Enter → Fix with Claude on a line with an error or warning: sends the IDE's messages for that line to the chat,
 * with the lines attached, and asks Claude to fix the cause.
 */
class FixWithClaudeIntention : IntentionAction, HighPriorityAction, DumbAware {

    override fun getText() = "Fix with Claude"

    override fun getFamilyName() = "Fix with Claude"

    override fun startInWriteAction() = false

    // Sending to the chat changes nothing in the file, so there's nothing to preview.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file?.virtualFile?.isInLocalFileSystem == true && problemsAt(project, editor).isNotEmpty()

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        editor ?: return
        val virtualFile = file?.virtualFile ?: editor.virtualFile ?: return
        val problems = problemsAt(project, editor).takeIf { it.isNotEmpty() } ?: return
        val path = IdeBridge(project).displayPath(virtualFile)
        val lines = problems.minOf { it.line }..problems.maxOf { it.line }
        ClaudeChatToolWindowFactory.withChat(project) { chat ->
            chat.ask(prompt(path, problems), listOf(chat.newFileAttachment(path, false, lines)))
        }
    }

    companion object {
        private const val MAX_PROBLEMS = 10

        /** The request for [problems] in the file at [path]. */
        fun prompt(path: String, problems: List<CodeProblem>): String {
            val reported = if (problems.size == 1) {
                val problem = problems.single()
                "The IDE reports ${kind(problem)} in $path at line ${problem.line}: ${problem.message}"
            } else {
                "The IDE reports these problems in $path:\n" +
                    problems.joinToString("\n") { "- Line ${it.line}, ${kind(it)}: ${it.message}" }
            }
            return "$reported\n\nFind the cause, which may be elsewhere in the project, and fix it with the smallest " +
                "change that's right. Don't just silence the ${if (problems.all { !it.isError }) "warning" else "error"}."
        }

        private fun kind(problem: CodeProblem) = if (problem.isError) "an error" else "a warning"

        /** The errors and warnings shown on the caret's line, errors first. */
        fun problemsAt(project: Project, editor: Editor): List<CodeProblem> {
            val document = editor.document
            if (document.textLength == 0) return emptyList()
            val line = document.getLineNumber(editor.caretModel.offset)
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val markup = DocumentMarkupModel.forDocument(document, project, false) ?: return emptyList()
            return markup.allHighlighters.asSequence()
                .filter { it.isValid && it.startOffset <= lineEnd && it.endOffset >= lineStart }
                .mapNotNull { highlighter ->
                    val info = HighlightInfo.fromRangeHighlighter(highlighter) ?: return@mapNotNull null
                    if (info.severity < HighlightSeverity.WARNING) return@mapNotNull null
                    val message = info.description?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val start = highlighter.startOffset.coerceIn(0, document.textLength)
                    CodeProblem(document.getLineNumber(start) + 1, message, info.severity >= HighlightSeverity.ERROR)
                }
                .distinctBy { it.line to it.message }
                .sortedWith(compareByDescending<CodeProblem> { it.isError }.thenBy { it.line })
                .take(MAX_PROBLEMS)
                .toList()
        }
    }
}
