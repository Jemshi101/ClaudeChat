package `in`.bleuphoenix.claudechat.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.util.concurrent.atomic.AtomicReference

/** A project file or folder found for an `@` mention. [path] is relative to the project. */
data class FileMatch(val path: String, val isDirectory: Boolean) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val directory: String get() = path.trimEnd('/').substringBeforeLast('/', "")
}

/**
 * Finds project files and folders by name for `@` mentions. The file list (project content, without excluded
 * folders such as build output) is collected once and reused for [CACHE_MS], so each keystroke only filters.
 */
class ProjectFileSearch(private val project: Project) {

    private class Snapshot(val files: List<FileMatch>, val takenAt: Long)

    private val snapshot = AtomicReference<Snapshot?>(null)

    /** Best matches for [query], best first. Call off the EDT. */
    fun search(query: String, limit: Int = 30): List<FileMatch> = rank(files(), query, limit)

    private fun files(): List<FileMatch> {
        snapshot.get()?.takeIf { System.currentTimeMillis() - it.takenAt < CACHE_MS }?.let { return it.files }
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val files = ReadAction.nonBlocking<List<FileMatch>> {
            val found = mutableListOf<FileMatch>()
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                val relative = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
                if (relative.isNotEmpty() && !relative.startsWith(".")) found += FileMatch(relative, file.isDirectory)
                found.size < MAX_FILES
            }
            found
        }.executeSynchronously()
        snapshot.set(Snapshot(files, System.currentTimeMillis()))
        return files
    }

    companion object {
        private const val CACHE_MS = 30_000L
        private const val MAX_FILES = 200_000

        /**
         * Ranks by how well [query] fits: exact name, name prefix, name substring, path substring, then letters of the
         * query in order anywhere in the path. Shorter paths win ties. An empty query lists files, shortest path first.
         */
        fun rank(files: List<FileMatch>, query: String, limit: Int): List<FileMatch> {
            val q = query.lowercase()
            return files.asSequence()
                .mapNotNull { file -> score(file, q)?.let { file to it } }
                .sortedWith(compareBy<Pair<FileMatch, Int>> { it.second }.thenBy { it.first.path.length }.thenBy { it.first.path })
                .take(limit)
                .map { it.first }
                .toList()
        }

        private fun score(file: FileMatch, q: String): Int? {
            if (q.isEmpty()) return if (file.isDirectory) 1 else 0
            val name = file.name.lowercase()
            val path = file.path.lowercase()
            return when {
                name == q || name.substringBeforeLast('.') == q -> 0
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                path.contains(q) -> 3
                isSubsequence(q, name) -> 4
                isSubsequence(q, path) -> 5
                else -> null
            }
        }

        private fun isSubsequence(q: String, text: String): Boolean {
            var i = 0
            for (c in text) if (i < q.length && c == q[i]) i++
            return i == q.length
        }
    }
}
