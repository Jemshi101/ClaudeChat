package `in`.bleuphoenix.claudechat.ui

import `in`.bleuphoenix.claudechat.cli.SlashCommand

/**
 * The parts of a message the chat colors: `/command`s the CLI offers (commands and skills) and `@file` mentions, each
 * at the start of the message or after whitespace. A `/name` that isn't a known command (e.g. a path) is left alone.
 */
object MessageTokens {

    enum class Kind { Command, File }

    data class Token(val kind: Kind, val start: Int, val end: Int)

    fun find(text: CharSequence, commands: Collection<SlashCommand>): List<Token> {
        val names = commands.flatMapTo(HashSet()) { listOf(it.name) + it.aliases }
        val commandTokens = COMMAND.findAll(text)
            .filter { it.groupValues[1] in names }
            .map { Token(Kind.Command, it.range.first, it.range.last + 1) }
        // Punctuation after a mention ("see @README.md, then") isn't part of the path.
        val fileTokens = FILE.findAll(text).map { Token(Kind.File, it.range.first, it.range.first + it.value.trimEnd(*TRAILING).length) }
        return (commandTokens + fileTokens).sortedBy { it.start }.toList()
    }

    // A command ends at whitespace; another "/" makes it a path. Mentions may be quoted (see ContextMessage.mention).
    private val COMMAND = Regex("""(?<=^|\s)/([^\s/]+)(?=\s|$)""")
    private val TRAILING = charArrayOf(',', '.', ';', ':', '!', '?', ')', ']')
    private val FILE = Regex("""(?<=^|\s)@("[^"\n]+"\S*|[^\s"]+)""")
}
