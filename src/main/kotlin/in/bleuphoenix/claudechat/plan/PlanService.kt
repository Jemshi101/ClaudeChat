package `in`.bleuphoenix.claudechat.plan

import androidx.compose.runtime.mutableStateMapOf
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Plans that can be built, by plan file, so a plan's editor tab can offer the same Build (and, while Claude waits for
 * a decision, Keep planning) as its card in the chat. Mutate on the EDT.
 */
@Service(Service.Level.PROJECT)
class PlanService {

    /** [revise] is set while Claude waits for a decision on the plan; otherwise Build starts it from the file. */
    class PendingPlan(val build: (autoAcceptEdits: Boolean) -> Unit, val revise: (() -> Unit)?)

    private val pending = mutableStateMapOf<String, PendingPlan>()

    fun pendingFor(path: String): PendingPlan? = pending[PlanFiles.key(path)]

    fun register(path: String, plan: PendingPlan) {
        pending[PlanFiles.key(path)] = plan
    }

    fun resolve(path: String) {
        pending.remove(PlanFiles.key(path))
    }

    companion object {
        fun getInstance(project: Project): PlanService = project.service()
    }
}

/** Plan files are the markdown files in ~/.claude/plans: the CLI's own, and the readable copies the chat opens. */
object PlanFiles {
    // claudechat.plansDir: set by the build for tests.
    private val directory: String by lazy {
        (System.getProperty("claudechat.plansDir")?.let(::File) ?: File(System.getProperty("user.home"), ".claude/plans")).canonicalPath
    }

    /** The plans-directory file named after [title], new or existing (a chat rewrites its own plan file). */
    fun fileFor(title: String, dir: File = File(directory)): File = File(dir, "${slug(title)}.md")

    fun slug(title: String): String {
        val words = title
            .replace(Regex("^\\s*((implementation|execution)\\s+)?plan\\s*(:|-|–|—)\\s*", RegexOption.IGNORE_CASE), "")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
        val slug = StringBuilder()
        for (word in words) {
            if (slug.isNotEmpty() && slug.length + 1 + word.length > MAX_SLUG) break
            if (slug.isNotEmpty()) slug.append('-')
            slug.append(word.take(MAX_SLUG))
        }
        return slug.toString().ifEmpty { "plan" }
    }

    private const val MAX_SLUG = 60

    fun key(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    fun isPlan(file: VirtualFile): Boolean = isPlan(file.path)

    fun isPlan(path: String): Boolean =
        path.endsWith(".md", ignoreCase = true) && key(path).startsWith("$directory${File.separator}")
}
