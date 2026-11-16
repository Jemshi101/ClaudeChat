package `in`.bleuphoenix.claudechat.ui

import com.google.gson.JsonObject
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AppIcon
import `in`.bleuphoenix.claudechat.settings.ClaudeChatSettings
import java.awt.Toolkit

/**
 * Something in a chat worth telling the user about while they look elsewhere: Claude [finished][Kind.FINISHED] its
 * turn, or [needs them][Kind.NEEDS_YOU] (a permission, a question, a plan to review). [itemKey] is the chat item to
 * scroll to.
 */
data class ChatAlert(
    val kind: Kind,
    val title: String,
    val body: String,
    val itemKey: String? = null,
    val seconds: Double? = null,
    val failed: Boolean = false,
) {
    enum class Kind { FINISHED, NEEDS_YOU }

    companion object {
        fun permission(item: PermissionItem): ChatAlert {
            val input = item.input
            fun field(name: String) = input?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            val (title, body) = when (item.toolName) {
                "Bash" -> "Claude wants to run a command" to (field("command")?.let(::firstLine) ?: item.detail)
                "Edit", "MultiEdit", "Write", "NotebookEdit" ->
                    "Claude wants to edit a file" to ((field("file_path") ?: field("notebook_path"))?.let(::fileName) ?: item.detail)
                "AskUserQuestion" -> "Claude has a question" to (question(input) ?: item.detail)
                else -> "Claude wants to use ${item.toolName}" to item.detail
            }
            return ChatAlert(Kind.NEEDS_YOU, title, body, item.key)
        }

        fun plan(item: PlanItem) = ChatAlert(Kind.NEEDS_YOU, "Plan ready for review", item.title, item.key)

        fun finished(reply: String?, seconds: Double?) =
            ChatAlert(Kind.FINISHED, "Claude finished", reply?.let(::summary).orEmpty().ifEmpty { "Done." }, seconds = seconds)

        fun failed(error: String) = ChatAlert(Kind.FINISHED, "Claude stopped with an error", firstLine(error), failed = true)

        /** The first question of an AskUserQuestion call. */
        private fun question(input: JsonObject?): String? = input?.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("question")?.takeIf { it.isJsonPrimitive }?.asString

        private fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

        /** A reply's first line of prose, without markdown marks. */
        private fun summary(reply: String): String = reply.lineSequence()
            .map { it.trim().trimStart('#', '>', '-', '*', ' ').replace("**", "").replace("`", "").trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("|") }
            ?.let(::firstLine).orEmpty()

        private fun firstLine(text: String): String = text.trim().lineSequence().first().let {
            if (it.length <= MAX_LENGTH) it else it.take(MAX_LENGTH).substringBeforeLast(' ') + "…"
        }

        private const val MAX_LENGTH = 120
    }
}

/**
 * A chat's desktop notifications, as in Cursor: when Claude finishes or needs the user, and the user isn't looking at
 * that chat. They're IDE notifications, which the IDE also shows in the system's notification center while it's in the
 * background; clicking one opens the chat at the item. A notification is taken back once the user answers or sends a
 * message, so stale permission prompts don't pile up.
 */
class ChatNotifier(
    private val project: Project,
    private val chatTitle: () -> String,
    /** Whether the user can see this chat right now: the IDE is focused, the tool window open and this tab selected. */
    private val watching: () -> Boolean,
    /** Whether messages are queued, which run as soon as this turn ends. */
    private val queued: () -> Boolean,
    /** Brings the chat forward and scrolls to the item. */
    private val show: (itemKey: String?) -> Unit,
) {
    private val shown = mutableListOf<Notification>()

    fun alert(alert: ChatAlert) {
        val options = ClaudeChatSettings.options
        if (!shouldShow(alert, watching(), queued(), options)) return
        val group = if (alert.kind == ChatAlert.Kind.NEEDS_YOU) NEEDS_YOU_GROUP else FINISHED_GROUP
        val type = if (alert.failed) NotificationType.ERROR else NotificationType.INFORMATION
        val notification = Notification(group, alert.title, StringUtil.escapeXmlEntities(alert.body), type)
            .setSubtitle(chatTitle())
            .addAction(NotificationAction.createSimpleExpiring("Open Chat") { show(alert.itemKey) })
            .whenExpired { shown.removeIf { it.isExpired } }
        shown += notification
        notification.notify(project)
        val away = !ApplicationManager.getApplication().isActive
        // Claude waits until the user answers: bounce the Dock icon, flash the taskbar button.
        if (alert.kind == ChatAlert.Kind.NEEDS_YOU && away) AppIcon.getInstance().requestAttention(project, false)
        if (options.notifySound) Toolkit.getDefaultToolkit().beep()
    }

    /** Takes back this chat's notifications: the user answered, or is back in the chat. */
    fun clear() {
        shown.toList().forEach { it.expire() }
        shown.clear()
    }

    companion object {
        const val NEEDS_YOU_GROUP = "Claude Chat: Needs You"
        const val FINISHED_GROUP = "Claude Chat: Finished"

        /** Turns shorter than this end without a notification: the user was most likely waiting for them. */
        const val MIN_SECONDS = 10.0

        fun shouldShow(alert: ChatAlert, watching: Boolean, queued: Boolean, options: ClaudeChatSettings.Options): Boolean {
            if (options.notifyOnlyWhenAway && watching) return false
            return when (alert.kind) {
                ChatAlert.Kind.NEEDS_YOU -> options.notifyNeedsYou
                // A queued message starts the next turn right away; a failure is worth knowing however quick.
                ChatAlert.Kind.FINISHED -> options.notifyFinished && !queued && (alert.failed || (alert.seconds ?: 0.0) >= MIN_SECONDS)
            }
        }
    }
}
