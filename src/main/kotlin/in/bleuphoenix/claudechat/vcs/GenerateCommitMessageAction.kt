package `in`.bleuphoenix.claudechat.vcs

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.VfsUtilCore
import `in`.bleuphoenix.claudechat.edit.InlineEditRunner
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * The Claude button on the commit message box: Claude writes a message for the changes ticked for commit, with a
 * one-shot `claude -p` that has no tools, and it replaces the box's text. A draft in the box guides the message.
 */
class GenerateCommitMessageAction : DumbAwareAction() {

    private val running = ConcurrentHashMap.newKeySet<Project>()

    // The commit panel's included changes and message are UI state.
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val available = project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        e.presentation.isVisible = available
        e.presentation.isEnabled = available && project !in running
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val workflow = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val changes = workflow?.getIncludedChanges()
            ?: e.getData(VcsDataKeys.CHANGES)?.toList()
            ?: ChangeListManager.getInstance(project).defaultChangeList.changes.toList()
        val unversioned = workflow?.getIncludedUnversionedFiles().orEmpty()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            notify(project, "Tick the changes to commit first; Claude writes the message from them.", NotificationType.INFORMATION)
            return
        }
        val draft = workflow?.commitMessageUi?.text ?: e.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)?.text
        generate(project, messageControl, changes, unversioned, draft)
    }

    private fun generate(project: Project, control: CommitMessageI, changes: List<Change>, unversioned: List<FilePath>, draft: String?) {
        val base = project.basePath ?: System.getProperty("user.home")
        running += project
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Claude is writing a commit message", true) {
            private var message: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val files = changes.mapNotNull { fileChange(base, it) } + unversioned.map { newFile(base, it) }
                val prompt = CommitMessagePrompt.build(files, draft, recentSubjects(base))
                LOG.info("Commit message for ${files.size} files (${prompt.length} chars)")
                val reply = InlineEditRunner.run(base, prompt, ClaudeChatSettings.options.model, indicator, "Claude couldn't write a commit message")
                message = CommitMessagePrompt.clean(reply).takeIf { it.isNotEmpty() }
                    ?: throw java.io.IOException("Claude didn't write a commit message.")
            }

            override fun onSuccess() {
                message?.let(control::setCommitMessage)
            }

            override fun onThrowable(error: Throwable) {
                if (error is ProcessCanceledException) return
                LOG.warn("Commit message failed", error)
                notify(project, error.message ?: error.toString(), NotificationType.ERROR)
            }

            override fun onFinished() {
                running -= project
            }
        })
    }

    companion object {
        private val LOG = logger<GenerateCommitMessageAction>()

        /** Files bigger than this are described, not diffed. */
        private const val MAX_FILE_BYTES = 512 * 1024

        private fun relative(base: String, path: FilePath) = FileUtil.getRelativePath(base, path.path, '/') ?: path.path

        private fun fileChange(base: String, change: Change): FileChange? {
            val before = change.beforeRevision
            val after = change.afterRevision
            val path = (after ?: before)?.file ?: return null
            val oldPath = before?.file?.takeIf { after != null && it.path != after.file.path }?.let { relative(base, it) }
            val binary = path.fileType.isBinary
            return FileChange(
                path = relative(base, path),
                before = if (before == null) null else if (binary) "" else content(before),
                after = if (after == null) null else if (binary) "" else content(after),
                oldPath = oldPath,
                binary = binary,
            )
        }

        private fun content(revision: ContentRevision): String =
            runCatching { revision.content }.getOrNull()
                ?.let { if (it.length > MAX_FILE_BYTES) "(Too large to diff)" else it } ?: "(Content not available)"

        private fun newFile(base: String, path: FilePath): FileChange {
            val file = path.virtualFile
            val binary = path.fileType.isBinary || (file != null && file.length > MAX_FILE_BYTES)
            val text = if (binary || file == null) "" else runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
            return FileChange(relative(base, path), before = null, after = text, binary = binary)
        }

        /** The last commit subjects in the project's git repo, to match their style; empty without git. */
        private fun recentSubjects(base: String): List<String> = runCatching {
            val command = GeneralCommandLine("git", "log", "-n", "10", "--no-merges", "--format=%s")
                .withWorkDirectory(base)
                .withCharset(StandardCharsets.UTF_8)
            val output = ExecUtil.execAndGetOutput(command, 5_000)
            if (output.exitCode != 0) emptyList() else output.stdoutLines.map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

        private fun notify(project: Project, message: String, type: NotificationType) {
            NotificationGroupManager.getInstance().getNotificationGroup("Claude Chat: Finished")
                .createNotification("Commit message", message, type)
                .notify(project)
        }
    }
}
