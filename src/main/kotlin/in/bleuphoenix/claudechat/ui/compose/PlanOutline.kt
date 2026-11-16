package `in`.bleuphoenix.claudechat.ui.compose

/** A compact view of a markdown plan for the chat card: its opening paragraph and section headings. */
data class PlanOutline(val summary: String, val sections: List<String>) {

    companion object {
        private val INLINE_MARKUP = Regex("""[`*_]|\[([^\]]*)]\([^)]*\)""")

        fun of(plan: String): PlanOutline {
            val lines = plan.lines().map { it.trim() }
            val sections = lines.filter { it.startsWith("## ") }.map { plain(it.removePrefix("## ")) }
            // The first paragraph of prose, skipping headings and list markers.
            val summary = lines
                .dropWhile { it.isEmpty() || it.startsWith("#") }
                .takeWhile { it.isNotEmpty() && !it.startsWith("#") }
                .joinToString(" ") { plain(it.trimStart('-', '*', ' ').replace(Regex("""^\d+\.\s*"""), "")) }
            return PlanOutline(summary, sections)
        }

        private fun plain(text: String) = text.replace(INLINE_MARKUP) { it.groupValues[1] }.trim()
    }
}
