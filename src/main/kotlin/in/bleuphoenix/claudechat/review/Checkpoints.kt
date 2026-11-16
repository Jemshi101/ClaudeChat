package `in`.bleuphoenix.claudechat.review

/**
 * What each message's turn changed, so the files can be put back to how they were before any message ("Restore
 * checkpoint"). For each turn it keeps, per file, the text before the turn's first edit; null means the turn
 * created the file. Turns are kept in the order they were sent.
 */
class Checkpoints {

    private class FileBefore(val text: String?)

    private val turns = LinkedHashMap<String, LinkedHashMap<String, FileBefore>>()

    fun startTurn(key: String) {
        turns.getOrPut(key) { LinkedHashMap() }
    }

    /** Records that [path] was changed in turn [key]; only the first change per turn matters. */
    fun record(key: String, path: String, before: String?) {
        turns.getOrPut(key) { LinkedHashMap() }.putIfAbsent(path, FileBefore(before))
    }

    /** Whether anything changed in turn [key] or later, i.e. restoring to before it would change files. */
    fun hasChangesFrom(key: String): Boolean = from(key).any { it.isNotEmpty() }

    /**
     * Each file changed in turn [key] or later, with its text before the earliest of those changes (null: the file
     * didn't exist and should be deleted).
     */
    fun restoreTo(key: String): Map<String, String?> {
        val result = LinkedHashMap<String, String?>()
        from(key).forEach { turn -> turn.forEach { (path, before) -> if (path !in result) result[path] = before.text } }
        return result
    }

    /** Forgets turn [key] and later ones once they've been undone, so they aren't restored twice. */
    fun dropFrom(key: String) {
        val keys = turns.keys.toList()
        val index = keys.indexOf(key)
        if (index < 0) return
        keys.drop(index).forEach { turns[it]?.clear() }
    }

    private fun from(key: String): List<Map<String, FileBefore>> {
        val keys = turns.keys.toList()
        val index = keys.indexOf(key)
        return if (index < 0) emptyList() else keys.drop(index).map { turns.getValue(it) }
    }
}
