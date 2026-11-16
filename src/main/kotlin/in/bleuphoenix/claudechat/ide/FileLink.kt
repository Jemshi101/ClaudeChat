package `in`.bleuphoenix.claudechat.ide

/** A file reference from a reply, such as `src/Foo.kt:42`, `Foo.kt:10-20` or `src/Foo.kt#L42`. */
data class FileLink(val path: String, val line: Int?) {

    companion object {
        /** Link scheme for file references found in reply text, so they're told apart from web links. */
        const val SCHEME = "claude-file:"

        private val LINE_SUFFIX = Regex("""^(.+?)(?:#L(\d+)(?:-L?\d+)?|:(\d+)(?:[-:]\d+)?)?$""")

        private const val EXTENSIONS =
            "kt|kts|java|xml|gradle|dart|json|md|yaml|yml|properties|pro|cpp|cc|h|c|cmake|txt|sh|py|js|ts|html|css|toml"

        /**
         * Paths ending in a known source extension, optionally with `:line` or `:start-end`.
         * Not preceded by a path character, so the tail of a URL or a longer path isn't matched on its own.
         */
        private val PATH_IN_TEXT = Regex(
            """(?<![\w/.~-])((?:~?/)?(?:[\w.-]+/)*[\w-][\w.-]*\.(?:$EXTENSIONS))(?::(\d+)(?:-\d+)?)?(?![\w/])""",
        )

        /** The last file path named in [text] (`src/Foo.kt`, `Foo.kt:12`), without its line; null when there's none. */
        fun lastPathIn(text: String): String? = PATH_IN_TEXT.findAll(text).lastOrNull()?.groupValues?.get(1)

        fun parse(target: String): FileLink? {
            val cleaned = target.removePrefix(SCHEME).removePrefix("file://").trim()
            if (cleaned.isEmpty()) return null
            val match = LINE_SUFFIX.matchEntire(cleaned) ?: return FileLink(cleaned, null)
            val line = (match.groupValues[2].ifEmpty { match.groupValues[3] }).toIntOrNull()
            return FileLink(match.groupValues[1], line)
        }

        // Inline code spans, markdown links/images, autolinks and bare URLs: never linkified inside.
        private val PROTECTED_SPAN = Regex("""`[^`\n]+`|!?\[[^\]\n]*]\([^)\n]*\)|<[^>\s]+>|https?://\S+""")
        private val FENCE = Regex("""^\s{0,3}(```|~~~)""")

        /**
         * Turns file references in reply markdown into [SCHEME] links. Fenced code blocks, links and URLs are left
         * alone; an inline code span that is exactly a file reference becomes a plain link, so it shows in the link
         * color (code styling would override it).
         */
        fun linkifyMarkdown(markdown: String): String {
            var inFence = false
            return markdown.lines().joinToString("\n") { line ->
                when {
                    FENCE.containsMatchIn(line) -> {
                        inFence = !inFence
                        line
                    }
                    inFence -> line
                    else -> linkifyLine(line)
                }
            }
        }

        /** [text] as literal link text: markdown characters (e.g. the underscores in file names) escaped. */
        private fun escapeLinkText(text: String) = text.replace(MARKDOWN_SPECIAL) { "\\" + it.value }

        private val MARKDOWN_SPECIAL = Regex("""[\\`*_\[\]<>]""")

        private fun linkifyLine(line: String): String {
            val out = StringBuilder(line.length + 32)
            var last = 0
            for (span in PROTECTED_SPAN.findAll(line)) {
                out.append(linkifyText(line.substring(last, span.range.first)))
                val value = span.value
                val code = value.length > 2 && value.startsWith("`")
                val inner = if (code) value.substring(1, value.length - 1) else null
                if (inner != null && PATH_IN_TEXT.matchEntire(inner) != null) {
                    out.append("[").append(escapeLinkText(inner)).append("](").append(SCHEME).append(inner).append(")")
                } else {
                    out.append(value)
                }
                last = span.range.last + 1
            }
            out.append(linkifyText(line.substring(last)))
            return out.toString()
        }

        private fun linkifyText(text: String): String =
            PATH_IN_TEXT.replace(text) { "[${it.value}]($SCHEME${it.value})" }
    }
}
