package `in`.bleuphoenix.claudechat.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Decides whether a shell command Claude asks to run only reads, and only inside the project, so it can run without
 * asking: `cd`, `grep`, `sed -n`, `cat`, `ls`, `find`, read-only `git`, `dart analyze`, Python scripts that only read,
 * pipes, `&&`, `for` loops, heredocs and plain variables. Every path it names must resolve inside the project (`..`,
 * `~` and absolute paths included, symlinks followed), or in Claude's own plans and saved tool output. The one write it
 * allows is a copy into the temp folder (`cp file /tmp/x`, `git show HEAD:file > /tmp/x`), which it can then read back.
 *
 * It errs on the side of asking: anything it doesn't fully understand (another program, a redirect to a file, `$(...)`
 * whose output isn't a list of project files, a variable it can't resolve) is not read-only.
 */
object ReadOnlyCommands {

    fun isReadOnlyInside(
        command: String,
        root: File,
        home: File = File(System.getProperty("user.home")),
        temps: List<File> = defaultTemps(),
    ): Boolean {
        if (command.length > MAX_LENGTH) return false
        return try {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            val tempPaths = temps.map { it.toPath().toAbsolutePath().normalize() }.flatMap { listOf(it, real(it)) }.distinct()
            Checker(rootPath, real(rootPath), home.toPath(), tempPaths).check(Tokenizer(command).commands())
        } catch (_: Unsupported) {
            false
        } catch (_: InvalidPathException) {
            false
        }
    }

    /**
     * Whether Claude's own file tools (Read, Glob, Grep…) may read [path] without asking: the same places as commands,
     * the project, Claude's plans and saved output, and the temp folder. Globs are judged by the folder before them.
     */
    fun isReadableInside(
        path: String,
        root: File,
        home: File = File(System.getProperty("user.home")),
        temps: List<File> = defaultTemps(),
    ): Boolean {
        if (path.isBlank() || path.startsWith("-") || path.contains('\u0000')) return false
        return try {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            val tempPaths = temps.map { it.toPath().toAbsolutePath().normalize() }.flatMap { listOf(it, real(it)) }.distinct()
            Checker(rootPath, real(rootPath), home.toPath(), tempPaths).pathOk(path.replace('\\', '/').let(::windowsDrive))
        } catch (_: Unsupported) {
            false
        } catch (_: InvalidPathException) {
            false
        }
    }

    /** C:/x as Git Bash writes it, /c/x, so both spellings resolve the same way. */
    private fun windowsDrive(path: String): String =
        if (path.length > 2 && path[1] == ':' && path[0].isLetter()) "/${path[0].lowercaseChar()}${path.substring(2)}" else path

    private const val MAX_LENGTH = 20_000

    /** The temp folders a copy may go to: /tmp, and the one the system gives this user (`$TMPDIR` on a Mac). */
    private fun defaultTemps(): List<File> = listOfNotNull(
        File("/tmp").takeIf { File.separatorChar == '/' },
        System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }?.let(::File),
        System.getProperty("java.io.tmpdir")?.let(::File),
    ).filter { it.isDirectory }

    private class Unsupported : RuntimeException(null, null, false, false)

    private fun fail(): Nothing = throw Unsupported()

    private fun real(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path)

    // ---- Tokens ----

    private sealed interface Part
    private data class Lit(val text: String) : Part
    private data class Var(val name: String) : Part
    private data class Sub(val command: String) : Part

    private class Word(val parts: List<Part>)
    private class Redirect(val op: String, val target: Word?, val fdTarget: String?, val heredoc: Heredoc? = null)

    /** `<<EOF` … `EOF`: [body] is filled in once the line with the command ends. */
    private class Heredoc(val delimiter: String, val quoted: Boolean, val stripTabs: Boolean) {
        var body: String? = null
    }

    /** A simple command and the operator after it (`;`, `&&`, `||`, `|`, or empty at the end). */
    private class Command(val words: List<Word>, val redirects: List<Redirect>, val then: String)

    /** Splits a command line into simple commands, the way the shell reads it; throws on syntax it doesn't follow. */
    private class Tokenizer(private val text: String) {
        private var i = 0
        private val pendingHeredocs = mutableListOf<Heredoc>()

        fun commands(): List<Command> {
            val commands = mutableListOf<Command>()
            var words = mutableListOf<Word>()
            var redirects = mutableListOf<Redirect>()
            fun end(op: String) {
                if (words.isEmpty() && redirects.isEmpty()) {
                    // Empty lines and a trailing `;` are fine; `a && && b` isn't.
                    if (op != ";" && op != "") fail()
                } else {
                    commands += Command(words, redirects, op)
                }
                words = mutableListOf()
                redirects = mutableListOf()
            }
            while (true) {
                skipBlanks()
                if (i >= text.length) break
                val c = text[i]
                when {
                    c == '#' -> while (i < text.length && text[i] != '\n') i++
                    c == '\n' || c == ';' -> {
                        if (text.startsWith(";;", i)) fail()
                        i++
                        if (c == '\n') readHeredocs()
                        end(";")
                    }
                    text.startsWith("&&", i) || text.startsWith("||", i) -> {
                        end(text.substring(i, i + 2))
                        i += 2
                    }
                    text.startsWith("|&", i) -> fail()
                    c == '|' -> {
                        i++
                        end("|")
                    }
                    text.startsWith("&>", i) -> {
                        i += 2
                        if (i < text.length && text[i] == '>') i++
                        redirects += Redirect(">", word() ?: fail(), null)
                    }
                    c == '&' || c == '(' || c == ')' || c == '`' -> fail()
                    c == '>' || c == '<' -> redirects += redirect()
                    else -> {
                        val start = i
                        val w = word() ?: fail()
                        // `2>/dev/null`: digits right before a redirect are its file descriptor.
                        if (i < text.length && (text[i] == '>' || text[i] == '<') && text.substring(start, i).all(Char::isDigit)) {
                            redirects += redirect()
                        } else {
                            words += w
                        }
                    }
                }
            }
            if (pendingHeredocs.isNotEmpty()) fail()
            end("")
            return commands
        }

        /** Reads the bodies of the heredocs started on the line that just ended. */
        private fun readHeredocs() {
            for (heredoc in pendingHeredocs) {
                val body = StringBuilder()
                while (true) {
                    if (i >= text.length) fail()
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    val line = text.substring(i, end)
                    i = minOf(end + 1, text.length)
                    if ((if (heredoc.stripTabs) line.trimStart('\t') else line) == heredoc.delimiter) break
                    body.append(line).append('\n')
                }
                heredoc.body = body.toString()
            }
            pendingHeredocs.clear()
        }

        private fun skipBlanks() {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text.startsWith("\\\n", i))) i += if (text[i] == '\\') 2 else 1
        }

        private fun redirect(): Redirect {
            val op = when {
                text.startsWith(">>", i) -> ">>"
                text.startsWith(">&", i) -> ">&"
                text.startsWith(">|", i) -> ">|"
                text.startsWith("<<<", i) -> fail()
                text.startsWith("<<", i) -> return heredoc()
                text.startsWith("<&", i) -> fail()
                else -> text[i].toString()
            }
            i += op.length
            if (op == ">&") {
                val start = i
                while (i < text.length && (text[i].isDigit() || text[i] == '-')) i++
                if (i == start) fail()
                return Redirect(op, null, text.substring(start, i))
            }
            skipBlanks()
            return Redirect(op, word() ?: fail(), null)
        }

        private fun heredoc(): Redirect {
            i += 2
            val stripTabs = i < text.length && text[i] == '-'
            if (stripTabs) i++
            skipBlanks()
            val quote = text.getOrNull(i)?.takeIf { it == '\'' || it == '"' }
            val delimiter = if (quote != null) {
                val close = text.indexOf(quote, i + 1).takeIf { it >= 0 } ?: fail()
                text.substring(i + 1, close).also { i = close + 1 }
            } else {
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                text.substring(start, i)
            }
            if (delimiter.isEmpty()) fail()
            val heredoc = Heredoc(delimiter, quote != null, stripTabs)
            pendingHeredocs += heredoc
            return Redirect("<<", null, null, heredoc)
        }

        /** Reads one word: quotes, escapes, `$NAME`, `${NAME}` and `$(...)`. Null if there's none here. */
        private fun word(): Word? {
            val parts = mutableListOf<Part>()
            val literal = StringBuilder()
            fun flush() {
                if (literal.isNotEmpty()) parts += Lit(literal.toString())
                literal.clear()
            }
            var any = false
            while (i < text.length) {
                val c = text[i]
                if (c in " \t\n;&|<>()`") break
                any = true
                when (c) {
                    '\\' -> {
                        if (i + 1 >= text.length) fail()
                        if (text[i + 1] != '\n') literal.append(text[i + 1])
                        i += 2
                    }
                    '\'' -> {
                        val close = text.indexOf('\'', i + 1).takeIf { it >= 0 } ?: fail()
                        literal.append(text, i + 1, close)
                        i = close + 1
                    }
                    '"' -> {
                        i++
                        while (true) {
                            if (i >= text.length) fail()
                            val d = text[i]
                            when {
                                d == '"' -> { i++; break }
                                d == '`' -> fail()
                                d == '\\' && i + 1 < text.length && text[i + 1] in "\$`\"\\\n" -> {
                                    if (text[i + 1] != '\n') literal.append(text[i + 1])
                                    i += 2
                                }
                                d == '$' -> { flush(); parts += dollar() }
                                else -> { literal.append(d); i++ }
                            }
                        }
                        // `""` is an empty word, not nothing.
                        if (parts.isEmpty() && literal.isEmpty()) parts += Lit("")
                    }
                    '$' -> { flush(); parts += dollar() }
                    else -> { literal.append(c); i++ }
                }
            }
            flush()
            return if (any) Word(parts) else null
        }

        private fun dollar(): Part {
            i++ // $
            if (i >= text.length) return Lit("$")
            val c = text[i]
            return when {
                text.startsWith("((", i) -> fail()
                c == '(' -> {
                    val close = closingParen(i)
                    Sub(text.substring(i + 1, close)).also { i = close + 1 }
                }
                c == '{' -> {
                    val close = text.indexOf('}', i).takeIf { it >= 0 } ?: fail()
                    val name = text.substring(i + 1, close)
                    if (!NAME.matches(name)) fail() // ${x:-y}, ${#x}, ${x/a/b}…
                    Var(name).also { i = close + 1 }
                }
                c == '_' || c.isLetter() -> {
                    val start = i
                    while (i < text.length && (text[i] == '_' || text[i].isLetterOrDigit())) i++
                    Var(text.substring(start, i))
                }
                c == '"' || c == ' ' -> Lit("$")
                // The last exit status: a number, fine to print.
                c == '?' -> Var("?").also { i++ }
                else -> fail() // $1, $@, $'…'
            }
        }

        /** The `)` closing the `(` at [open], skipping quoted text and nested parentheses. */
        private fun closingParen(open: Int): Int {
            var depth = 0
            var j = open
            while (j < text.length) {
                when (text[j]) {
                    '\\' -> j++
                    '\'' -> j = text.indexOf('\'', j + 1).takeIf { it >= 0 } ?: fail()
                    '"' -> {
                        j++
                        while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1
                    }
                    '(' -> depth++
                    ')' -> if (--depth == 0) return j
                }
                j++
            }
            fail()
        }
    }

    // ---- Checking ----

    /** What `$(...)` expands to: paths a lister found in the project, or text that could be anything. */
    private const val LISTED = "\u0000listed"
    private const val UNKNOWN = "\u0000unknown"

    private class Checker(
        private val root: Path,
        private val realRoot: Path,
        private val home: Path,
        private val temps: List<Path>,
        private val vars: MutableMap<String, List<String>> = mutableMapOf(),
        private var cwd: Path = root,
    ) {
        /** Commands seen so far in the pipeline being read. */
        private val pipeline = mutableListOf<List<String>>()
        private val loops = ArrayDeque<String>()

        fun check(commands: List<Command>): Boolean {
            var expectDo = false
            for (command in commands) {
                var words = command.words
                val first = words.firstOrNull()?.let(::literal)
                when {
                    expectDo -> {
                        if (first != "do") fail()
                        expectDo = false
                        words = words.drop(1)
                    }
                    first == "for" -> {
                        startLoop(words)
                        if (command.redirects.isNotEmpty() || command.then != ";") fail()
                        expectDo = true
                        continue
                    }
                    first == "done" && words.size == 1 -> {
                        vars.remove(loops.removeLastOrNull() ?: fail())
                        checkRedirects(command.redirects)
                        endOfCommand(command.then, null)
                        continue
                    }
                    first in KEYWORDS -> fail()
                }
                checkRedirects(command.redirects)
                val stdin = command.redirects.lastOrNull { it.heredoc != null }?.heredoc?.body
                val expanded = runCommand(words, command.then, stdin)
                endOfCommand(command.then, expanded)
            }
            return !expectDo && loops.isEmpty()
        }

        /** The last pipeline read, each command's words expanded: what `$(...)` would print. */
        var lastPipeline: List<List<String>> = emptyList()
            private set

        private fun endOfCommand(then: String, words: List<String>?) {
            if (then == "|") {
                words?.let { pipeline += it }
            } else {
                lastPipeline = pipeline + listOfNotNull(words)
                pipeline.clear()
            }
        }

        /** `for NAME in values…`: the body is checked with NAME standing for each value. */
        private fun startLoop(words: List<Word>) {
            if (words.size < 3) fail()
            val name = literal(words[1]) ?: fail()
            if (!NAME.matches(name) || literal(words[2]) != "in" || !assignable(name)) fail()
            val values = words.drop(3).flatMap(::expand)
            if (values.size > MAX_VALUES) fail()
            values.forEach { if (!pathOk(it)) fail() }
            vars[name] = values
            loops.addLast(name)
        }

        /** Checks one simple command; returns its words, expanded with each variable's first value. */
        private fun runCommand(words: List<Word>, then: String, stdin: String?): List<String>? {
            if (words.isEmpty()) return null
            val assignments = words.takeWhile { w -> literalPrefixName(w) != null }
            val rest = words.drop(assignments.size)
            if (rest.isEmpty()) {
                // NAME=value on its own: a shell variable for the commands after it.
                assignments.forEach { assign(it, exported = false) }
                return null
            }
            // NAME=value before a command goes into its environment: only a few harmless ones.
            assignments.forEach { w -> if (literalPrefixName(w) !in SAFE_ENV) fail() }

            val name = literal(rest[0]) ?: fail()
            val args = rest.drop(1)
            when (name) {
                "cd" -> {
                    if (args.size != 1 || then == "|") fail()
                    val target = expand(args[0]).singleOrNull() ?: fail()
                    if (target == "-" || target.startsWith("\u0000")) fail()
                    val path = resolve(target)
                    if (!inside(path)) fail()
                    cwd = path
                    return null
                }
                "export" -> {
                    args.forEach { assign(it, exported = true) }
                    return null
                }
            }
            val values = args.map(::expand)
            checkProgram(name, values, stdin)
            return listOf(name) + values.map { it.first() }
        }

        private fun assign(word: Word, exported: Boolean) {
            val name = literalPrefixName(word) ?: fail()
            if (!assignable(name) || (exported && name !in SAFE_ENV)) fail()
            val value = expand(word).map { it.substringAfter('=') }
            if (value.size != 1) fail()
            vars[name] = value
        }

        private fun assignable(name: String): Boolean =
            name !in DANGEROUS_VARS && !name.startsWith("GIT_") && !name.startsWith("LD_") && !name.startsWith("DYLD_") &&
                (name in vars || System.getenv(name) == null)

        /** The program and its arguments: only readers, used only to read, on paths inside the project. */
        private fun checkProgram(name: String, args: List<List<String>>, stdin: String? = null) {
            when (name) {
                "git" -> return checkGit(args)
                "xargs" -> return checkXargs(args)
                "python3", "python" -> return checkPython(args, stdin)
                // timeout [-k 5] [-s SIGNAL] 300 command…: the command decides.
                "timeout" -> {
                    var index = 0
                    while (index < args.size && args[index].first().startsWith("-")) index += if (args[index].first() in setOf("-k", "-s")) 2 else 1
                    if (args.getOrNull(index)?.first()?.firstOrNull()?.isDigit() != true) fail()
                    val command = args.drop(index + 1)
                    val program = command.firstOrNull()?.singleOrNull() ?: fail()
                    return checkProgram(program, command.drop(1), stdin)
                }
                "dart", "flutter" -> return checkDart(name, args)
                "cp" -> return checkCopy(args)
                "textutil" -> {
                    if (args.none { it.first() == "-stdout" } || args.any { it.first() == "-output" }) fail()
                    return args.forEach { values -> values.forEach { if (!pathOk(it)) fail() } }
                }
            }
            val spec = SPECS[name] ?: fail()
            checkArgs(spec, args)
        }

        private fun checkArgs(spec: Spec, args: List<List<String>>) {
            // echo and printf only print what they're given.
            if (spec.printsOnly) return
            val parsed = parse(spec, args.map { it.first() })
            parsed.forEachIndexed { index, kind ->
                val values = args[index]
                when (kind) {
                    // A search pattern can't do anything, whatever text $(...) put in it; a sed or awk script can.
                    Kind.SCRIPT -> values.forEach { if (!spec.patternOnly && (it.contains('\u0000') || !spec.scriptOk(it))) fail() }
                    Kind.EXEMPT -> Unit
                    Kind.PATH -> values.forEach { if (!pathOk(it)) fail() }
                }
            }
        }

        /** `xargs` runs a reader on the file names piped to it, which must come from listing project files. */
        private fun checkXargs(args: List<List<String>>) {
            if (!listsPaths(pipeline)) fail()
            var index = 0
            while (index < args.size) {
                val arg = args[index].first()
                when {
                    arg in XARGS_FLAGS -> index++
                    arg in XARGS_OPTIONS -> index += 2
                    XARGS_OPTIONS.any { arg.startsWith(it) && arg.length > it.length } -> index++
                    arg.startsWith("-") -> fail()
                    else -> break
                }
            }
            val command = args.drop(index)
            if (command.isEmpty()) fail()
            val name = command[0].first()
            if (name == "xargs" || name.contains('\u0000')) fail()
            checkProgram(name, command.drop(1))
        }

        /** `python3 - <<EOF`, `python3 -c '…'` or `python3 script.py`, when the code only reads (see [pythonReadsOnly]). */
        private fun checkPython(args: List<List<String>>, stdin: String?) {
            var index = 0
            var code: String? = null
            while (index < args.size && code == null) {
                val arg = args[index].singleOrNull() ?: fail()
                when {
                    arg in PYTHON_FLAGS -> index++
                    arg == "-c" -> { code = args.getOrNull(index + 1)?.singleOrNull() ?: fail(); index += 2 }
                    arg == "-" -> { code = stdin ?: fail(); index++ }
                    arg.startsWith("-") -> fail() // -m runs a module, -i is interactive…
                    else -> {
                        if (!pathOk(arg)) fail()
                        val file = resolve(arg).toFile()
                        if (!file.isFile || file.length() > MAX_SCRIPT) fail()
                        code = file.readText()
                        index++
                    }
                }
            }
            if (code == null || code.contains('\u0000') || !pythonReadsOnly(code, ::pathOk)) fail()
            args.drop(index).forEach { values -> values.forEach { if (!pathOk(it)) fail() } }
        }

        /** `cp [-rpf…] files… /tmp/x`: copies of project files into the temp folder. */
        private fun checkCopy(args: List<List<String>>) {
            val operands = mutableListOf<List<String>>()
            var endOfOptions = false
            for (values in args) {
                val arg = values.singleOrNull() ?: fail()
                when {
                    endOfOptions || !arg.startsWith("-") -> operands += values
                    arg == "--" -> endOfOptions = true
                    arg.startsWith("--") -> if (arg.substringBefore('=') !in CP_LONG_FLAGS) fail()
                    else -> if (arg.length < 2 || arg.drop(1).any { it !in CP_FLAGS }) fail()
                }
            }
            if (operands.size < 2) fail()
            val target = operands.last().single()
            val sources = operands.dropLast(1).map { it.single() }
            sources.forEach { if (!pathOk(it)) fail() }
            if (!writableTemp(target)) fail()
            // Into a folder: each copy lands at folder/name, which must not be a link out of it either.
            val folder = resolve(target)
            if (Files.isDirectory(folder)) sources.forEach { source ->
                if (source.startsWith("\u0000")) return@forEach // Names a lister found: they land in the folder.
                if (!writableTemp(folder.resolve(source.trimEnd('/').substringAfterLast('/')).toString())) fail()
            }
        }

        /** `dart analyze`, `flutter analyze` and `dart format --output=none`: they report, changing nothing. */
        private fun checkDart(name: String, args: List<List<String>>) {
            val values = args.map { it.first() }
            when (values.firstOrNull()) {
                "analyze" -> if (values.any { it.startsWith("--write") || it == "--watch" }) fail()
                "format" -> if (name != "dart" || values.none { it == "--output=none" || it == "-onone" } &&
                    values.zipWithNext().none { (a, b) -> (a == "-o" || a == "--output") && b == "none" }
                ) fail()
                else -> fail()
            }
            args.drop(1).forEach { v -> v.forEach { if (!it.startsWith("-") && it != "none" && !pathOk(it)) fail() } }
        }

        private fun checkGit(args: List<List<String>>) {
            var index = 0
            // Options before the subcommand.
            while (index < args.size && args[index].first().startsWith("-")) {
                val arg = args[index].first()
                when {
                    arg in GIT_GLOBAL_FLAGS -> index++
                    arg == "-C" -> {
                        val dirs = args.getOrNull(index + 1) ?: fail()
                        dirs.forEach { if (!pathOk(it)) fail() }
                        index += 2
                    }
                    else -> fail() // -c sets config (pagers, aliases…), --exec-path…
                }
            }
            val sub = args.getOrNull(index)?.first() ?: fail()
            val rest = args.drop(index + 1)
            val values = rest.map { it.first() }
            val options = values.filter { it.startsWith("-") }.map { it.substringBefore('=') }
            val positionals = values.filterNot { it.startsWith("-") }
            when (sub) {
                in GIT_READERS -> Unit
                "branch" -> if (options.any { it !in GIT_BRANCH_LIST } ||
                    (positionals.isNotEmpty() && options.none { it in GIT_BRANCH_FILTERS })
                ) fail()
                "tag" -> if (options.any { it !in setOf("-l", "--list", "-n") } || (positionals.isNotEmpty() && options.none { it == "-l" || it == "--list" })) fail()
                "remote" -> if (options.any { it != "-v" && it != "--verbose" } || positionals.firstOrNull() !in setOf(null, "show", "get-url")) fail()
                "stash" -> if (positionals.firstOrNull() !in setOf("list", "show")) fail()
                "config" -> if (options.none { it in GIT_CONFIG_READS } || options.any { it in GIT_CONFIG_WRITES }) fail()
                "reflog" -> if (positionals.firstOrNull() in setOf("expire", "delete")) fail()
                "worktree" -> if (positionals.firstOrNull() != "list") fail()
                else -> fail()
            }
            rest.forEach { values ->
                val arg = values.first()
                if (GIT_FORBIDDEN.any { arg == it || arg.startsWith("$it=") }) fail()
                // Values of --grep=, --format= and the like are text, not paths.
                if (arg.startsWith("--") && arg.contains('=')) return@forEach
                values.forEach { if (!pathOk(it)) fail() }
            }
        }

        private fun checkRedirects(redirects: List<Redirect>) {
            redirects.forEach { r ->
                when (r.op) {
                    ">&" -> Unit // 2>&1
                    // Text given as input; unquoted, the shell would expand $ and ` in it first.
                    "<<" -> r.heredoc?.let { h -> if (h.body == null || (!h.quoted && h.body!!.any { it == '$' || it == '`' })) fail() }
                    "<" -> expand(r.target ?: fail()).forEach { if (!pathOk(it)) fail() }
                    // > /dev/null, or a copy of the output into the temp folder.
                    ">", ">>", ">|" -> expand(r.target ?: fail()).singleOrNull()?.takeIf { it == "/dev/null" || writableTemp(it) } ?: fail()
                    else -> fail()
                }
            }
        }

        // ---- Words ----

        private fun literal(word: Word): String? = word.parts.singleOrNull()?.let { it as? Lit }?.text

        /** NAME when [word] is `NAME=…` (the name unquoted), else null. */
        private fun literalPrefixName(word: Word): String? {
            val first = word.parts.firstOrNull() as? Lit ?: return null
            val name = first.text.substringBefore('=', "")
            return name.takeIf { first.text.contains('=') && NAME.matches(it) }
        }

        /** Every value [word] can take. `$(...)` becomes [LISTED] or [UNKNOWN]. */
        private fun expand(word: Word): List<String> {
            var results = listOf("")
            for (part in word.parts) {
                val options: List<String> = when (part) {
                    is Lit -> listOf(part.text)
                    is Var -> vars[part.name] ?: when (part.name) {
                        "HOME" -> listOf(home.toString())
                        "?" -> listOf(UNKNOWN)
                        "PWD" -> listOf(cwd.toString())
                        else -> fail()
                    }
                    is Sub -> listOf(substitution(part.command))
                }
                results = results.flatMap { prefix -> options.map { prefix + it } }
                if (results.size > MAX_VALUES) fail()
            }
            return results
        }

        /** Checks the command inside `$(...)` (in a subshell: its `cd` and variables stay there). */
        private fun substitution(command: String): String {
            val inner = Tokenizer(command).commands()
            val checker = Checker(root, realRoot, home, temps, vars.toMutableMap(), cwd)
            if (!checker.check(inner)) fail()
            return if (listsPaths(checker.lastPipeline)) LISTED else UNKNOWN
        }

        // ---- Paths ----

        private fun resolve(value: String): Path {
            // Git Bash on Windows spells D:\x as /d/x.
            val drive = if (File.separatorChar == '\\') WINDOWS_DRIVE.matchEntire(value) else null
            if (drive != null) return Paths.get("${drive.groupValues[1]}:/${drive.groupValues[2]}").normalize()
            // ...and its /tmp is the user's temp folder.
            if (File.separatorChar == '\\' && (value == "/tmp" || value.startsWith("/tmp/")) && temps.isNotEmpty()) {
                return temps.first().resolve(value.removePrefix("/tmp").removePrefix("/")).normalize()
            }
            val path = when {
                value == "~" || value.startsWith("~/") -> home.resolve(value.removePrefix("~").removePrefix("/"))
                value.startsWith("~") -> fail() // ~user
                else -> cwd.resolve(value)
            }
            return path.normalize()
        }

        /** Whether [path], symlinks followed, is in a temp folder. */
        private fun inTemp(path: Path): Boolean {
            val normalized = path.normalize()
            val resolved = if (Files.exists(normalized)) real(normalized) else normalized
            return temps.any { resolved.startsWith(it) && resolved != it }
        }

        /**
         * Whether a copy may be written at [value]: a path in a temp folder whose folder, and the file itself if it's
         * there already, really are in it. Anyone can put a symlink in /tmp; writing through one could land anywhere.
         */
        private fun writableTemp(value: String): Boolean {
            if (value.contains('\u0000') || value.isEmpty() || value.startsWith("-")) return false
            if (value.any { it in "*?[{" }) return false
            val path = resolve(value)
            if (Files.isSymbolicLink(path)) return false
            val parent = path.parent ?: return false
            if (!Files.isDirectory(parent) || !temps.any { real(parent).startsWith(it) }) return false
            return inTemp(path) || Files.isDirectory(path) && temps.any { real(path) == it }
        }

        private fun inside(path: Path): Boolean {
            val normalized = path.normalize()
            val resolved = if (Files.exists(normalized)) real(normalized) else normalized
            if (resolved.startsWith(realRoot) || (!Files.exists(normalized) && normalized.startsWith(root))) return true
            return claudesOwn(resolved)
        }

        /** Claude's plans and the tool output it saved for itself: it reads them back, and they hold nothing else. */
        private fun claudesOwn(path: Path): Boolean = listOf(home.resolve(".claude"), real(home.resolve(".claude"))).any { claude ->
            val projects = claude.resolve("projects")
            path.startsWith(claude.resolve("plans")) ||
                (path.startsWith(projects) && projects.relativize(path).any { it.toString() == "tool-results" })
        }

        /** Whether [value], if it names a file, names one inside the project. */
        fun pathOk(value: String): Boolean {
            if (value == LISTED || value.startsWith(LISTED)) return true
            if (value.contains(UNKNOWN)) return false
            if (value.contains('\u0000')) return false
            // --option=value: the value may be a path.
            val text = if (value.startsWith("-")) value.substringAfter('=', "") else value
            if (text.isEmpty() || text == "-") return true
            val pathLike = text.startsWith("/") || text.startsWith("~") || text.split('/').any { it == ".." }
            // Up to the first wildcard; what's before it decides the folder.
            val wild = text.indexOfFirst { it in "*?[{" }
            val fixed = if (wild < 0) text else text.substring(0, wild).substringBeforeLast('/', "")
            if (text == "/dev/null") return true
            if (pathLike) return resolve(fixed.ifEmpty { if (text.startsWith("/")) "/" else "." }).let { inside(it) || inTemp(it) }
            // A relative name: only a symlink could lead out.
            val path = resolve(fixed.ifEmpty { "." })
            return !Files.exists(path) || inside(path)
        }

        /** Whether a pipeline's output is file names in the project: a lister, then filters that only read their input. */
        private fun listsPaths(commands: List<List<String>>): Boolean {
            val first = commands.firstOrNull() ?: return false
            val lister = when (first.firstOrNull()) {
                "find" -> first.none { it == "-L" || it == "-follow" }
                "grep", "egrep", "fgrep" -> first.any { it == "--files-with-matches" || it == "--files-without-match" || LIST_FLAGS.matches(it) }
                "rg" -> first.any { it == "-l" || it == "--files" || it == "--files-with-matches" }
                "git" -> first.getOrNull(1) == "ls-files" || (first.getOrNull(1) in setOf("diff", "grep") && first.any { it == "--name-only" || it == "-l" })
                else -> false
            }
            if (!lister) return false
            return commands.drop(1).all { filter ->
                when (filter.firstOrNull()) {
                    "head", "tail", "sort", "uniq" -> filter.drop(1).all { it.startsWith("-") || it.all(Char::isDigit) }
                    "grep", "egrep", "fgrep" -> filter.drop(1).count { !it.startsWith("-") } <= 1
                    else -> false
                }
            }
        }
    }

    // ---- Programs ----

    private enum class Kind { PATH, EXEMPT, SCRIPT }

    /**
     * How a reader's arguments are read. [options] maps an option to what each value it takes is (a path to check, or
     * text). [scriptFirst]: the first operand is a pattern or script ([scriptOk] decides whether a script only reads),
     * unless one of [scriptOptions] gave it.
     */
    private class Spec(
        val options: Map<String, List<Kind>> = emptyMap(),
        val scriptFirst: Boolean = false,
        val scriptOptions: Set<String> = emptySet(),
        val forbidden: Set<String> = emptySet(),
        val forbiddenShort: Set<Char> = emptySet(),
        val singleDashLong: Boolean = false,
        val maxOperands: Int = Int.MAX_VALUE,
        val scriptOk: (String) -> Boolean = { true },
        val printsOnly: Boolean = false,
        val patternOnly: Boolean = false,
    )

    private fun parse(spec: Spec, args: List<String>): List<Kind> {
        val kinds = MutableList(args.size) { Kind.PATH }
        var operands = 0
        var scriptGiven = false
        var index = 0
        var endOfOptions = false
        while (index < args.size) {
            val arg = args[index]
            fun take(name: String, inline: String?) {
                val values = spec.options.getValue(name)
                val kind = values.first()
                if (name in spec.scriptOptions) scriptGiven = true
                if (inline != null) {
                    // -e'pattern', --regexp=pattern: the value is in the same argument.
                    if (kind == Kind.SCRIPT && (inline.contains('\u0000') || !spec.scriptOk(inline))) fail()
                    kinds[index] = if (kind == Kind.PATH) Kind.PATH else Kind.EXEMPT
                    index++
                    return
                }
                values.forEachIndexed { n, k -> if (index + 1 + n < args.size) kinds[index + 1 + n] = k }
                kinds[index] = Kind.EXEMPT
                index += 1 + values.size
            }
            when {
                endOfOptions || arg == "-" || !arg.startsWith("-") || arg.startsWith("\u0000") -> {
                    if (spec.scriptFirst && !scriptGiven) {
                        kinds[index] = Kind.SCRIPT
                        scriptGiven = true
                    } else {
                        operands++
                    }
                    if (operands > spec.maxOperands) fail()
                    index++
                }
                arg == "--" -> { endOfOptions = true; kinds[index] = Kind.EXEMPT; index++ }
                arg.startsWith("--") || spec.singleDashLong -> {
                    val name = arg.substringBefore('=')
                    if (name in spec.forbidden) fail()
                    if (name in spec.options) take(name, arg.substringAfter('=', "").takeIf { arg.contains('=') }) else index++
                }
                else -> {
                    // -abc: flags, the last of which may take a value (-A3, -e pattern).
                    var consumed = false
                    for ((n, c) in arg.drop(1).withIndex()) {
                        if (c in spec.forbiddenShort) fail()
                        if ("-$c" in spec.forbidden) fail()
                        if ("-$c" in spec.options) {
                            val inline = arg.substring(n + 2).takeIf { it.isNotEmpty() }
                            take("-$c", inline)
                            consumed = true
                            break
                        }
                    }
                    if (!consumed) { kinds[index] = Kind.EXEMPT; index++ }
                }
            }
        }
        return kinds
    }

    private val PATH_VALUE = listOf(Kind.PATH)
    private val TEXT_VALUE = listOf(Kind.EXEMPT)

    private fun opts(text: Collection<String> = emptyList(), paths: Collection<String> = emptyList()) =
        text.associateWith { TEXT_VALUE } + paths.associateWith { PATH_VALUE }

    /** `sed` scripts that only print: addresses, `p`, `d`, `q`, `=`, `n` (no `s`, `w`, `e`, `r`…). */
    private fun sedOk(script: String): Boolean = SedScript(script).readsOnly()

    /**
     * Reads a `sed` script command by command. Without `-i` (checked separately) sed only changes what it prints, so
     * any script is fine unless it writes (`w`, `W`, the `s///w` flag), reads other files (`r`, `R`) or runs a command
     * (`e`, the `s///e` flag). Anything it can't follow counts as not read-only.
     */
    private class SedScript(private val s: String) {
        private var i = 0

        fun readsOnly(): Boolean {
            while (true) {
                while (i < s.length && s[i] in " \t\n;") i++
                if (i >= s.length) return true
                if (!address()) return false
                blanks()
                if (i < s.length && s[i] == ',') {
                    i++
                    blanks()
                    if (!address()) return false
                }
                blanks()
                if (i < s.length && s[i] == '!') { i++; blanks() }
                if (i >= s.length) return false
                val command = s[i++]
                when (command) {
                    's' -> {
                        val delimiter = s.getOrNull(i) ?: return false
                        if (delimiter == '\n' || delimiter == '\\') return false
                        i++
                        if (!until(delimiter) || !until(delimiter)) return false
                        while (i < s.length && (s[i].isLetterOrDigit())) {
                            if (s[i] !in "gpiImM0123456789") return false // w file, e
                            i++
                        }
                    }
                    'y' -> {
                        val delimiter = s.getOrNull(i) ?: return false
                        i++
                        if (!until(delimiter) || !until(delimiter)) return false
                    }
                    '{', '}', '=', 'p', 'P', 'd', 'D', 'n', 'N', 'g', 'G', 'h', 'H', 'x', 'z', 'F' -> Unit
                    'q', 'Q', 'l', 'L' -> while (i < s.length && (s[i].isDigit() || s[i] == ' ')) i++
                    // Labels and the text a, i and c print run to the end of the line (b and t also to a ;).
                    'b', 't', 'T', ':' -> while (i < s.length && s[i] != '\n' && (command == ':' || s[i] != ';')) i++
                    'a', 'i', 'c' -> while (i < s.length && s[i] != '\n') i++
                    else -> return false // w, W, r, R, e, v…
                }
            }
        }

        private fun blanks() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        }

        /** An optional address: a line number (with ~step or +count), $, or /regex/ or \cregexc with I/M. */
        private fun address(): Boolean {
            if (i >= s.length) return true
            when {
                s[i].isDigit() || s[i] == '+' || s[i] == '~' -> while (i < s.length && (s[i].isDigit() || s[i] in "+~")) i++
                s[i] == '$' -> i++
                s[i] == '/' -> { i++; if (!until('/')) return false }
                s[i] == '\\' -> {
                    val delimiter = s.getOrNull(i + 1) ?: return false
                    i += 2
                    if (!until(delimiter)) return false
                }
                else -> return true
            }
            while (i < s.length && s[i] in "IM") i++
            return true
        }

        /** Skips to just after the next unescaped [delimiter]; false if there's none. */
        private fun until(delimiter: Char): Boolean {
            while (i < s.length) {
                val c = s[i]
                if (c == '\\') { i += 2; continue }
                i++
                if (c == delimiter) return true
            }
            return false
        }
    }

    /** `awk` programs that can't write, read other files or run commands. */
    private fun awkOk(program: String): Boolean =
        listOf(">", "<", "|", "system", "getline", "close", "fflush", "ENVIRON").none { program.contains(it) }

    /** cp flags that only change how it copies; not -l/-s (links), -t/-T (another target), -x… */
    private val CP_FLAGS = "rRpafvnLPHXc".toSet()
    private val CP_LONG_FLAGS = setOf("--recursive", "--preserve", "--force", "--verbose", "--no-clobber", "--archive", "--dereference", "--no-dereference")

    private val GREP = Spec(
        options = opts(
            text = listOf("-e", "--regexp", "-A", "-B", "-C", "-m", "--max-count", "-d", "-D", "--include", "--exclude", "--exclude-dir", "--color", "--colour", "--label", "--context", "--after-context", "--before-context"),
            paths = listOf("-f", "--file"),
        ),
        scriptFirst = true,
        scriptOptions = setOf("-e", "--regexp", "-f", "--file"),
        patternOnly = true,
    )

    private val SPECS: Map<String, Spec> = buildMap {
        val plain = Spec()
        listOf(
            "ls", "cat", "head", "tail", "wc", "stat", "pwd", "true", "false", ":", "test", "[", "nl",
            "cut", "tr", "column", "strings", "od", "hexdump", "cmp", "comm", "diff", "basename", "dirname", "realpath",
            "readlink", "which", "type", "date", "zipinfo",
        ).forEach { put(it, plain) }
        put("echo", Spec(printsOnly = true))
        put("printf", Spec(printsOnly = true))
        put("wc", Spec(forbidden = setOf("--files0-from")))
        put("du", Spec(forbidden = setOf("--files0-from")))
        put("file", Spec(forbidden = setOf("-f", "--files-from", "-m", "--magic-file")))
        put("sort", Spec(forbidden = setOf("-o", "--output", "-T", "--temporary-directory", "--compress-program", "--files0-from")))
        put("uniq", Spec(maxOperands = 1))
        put("tree", Spec(forbidden = setOf("-o", "--fromfile")))
        put("xxd", Spec(forbiddenShort = setOf('r'), maxOperands = 1))
        listOf("md5", "md5sum", "shasum", "sha1sum", "sha256sum").forEach { put(it, Spec(forbidden = setOf("-c", "--check"))) }
        listOf("grep", "egrep", "fgrep").forEach { put(it, GREP) }
        put(
            "rg",
            Spec(
                options = opts(
                    text = listOf("-e", "--regexp", "-g", "--glob", "--iglob", "-t", "--type", "-T", "--type-not", "-A", "-B", "-C", "-m", "--max-count", "-M", "--max-columns", "-j", "--threads", "--color", "--colors", "--sort", "--sortr", "--max-depth", "-d"),
                    paths = listOf("-f", "--file", "--ignore-file"),
                ),
                scriptFirst = true,
                scriptOptions = setOf("-e", "--regexp", "-f", "--file"),
                patternOnly = true,
                forbidden = setOf("--pre", "--pre-glob", "--type-add", "--search-zip", "-z"),
            ),
        )
        put(
            "sed",
            Spec(
                options = opts(text = listOf("-l", "--line-length")) + listOf("-e", "--expression").associateWith { listOf(Kind.SCRIPT) },
                scriptFirst = true,
                scriptOptions = setOf("-e", "--expression"),
                forbidden = setOf("--in-place", "-f", "--file"),
                forbiddenShort = setOf('i', 'I'),
                scriptOk = ::sedOk,
            ),
        )
        listOf("awk", "gawk").forEach {
            put(
                it,
                Spec(
                    options = opts(text = listOf("-F", "-v", "--field-separator", "--assign")),
                    scriptFirst = true,
                    forbidden = setOf("-f", "--file", "-i", "--include", "-l", "--load", "-E", "--exec", "-o", "--pretty-print", "-p", "--profile", "-d", "--dump-variables"),
                    scriptOk = ::awkOk,
                ),
            )
        }
        put(
            "find",
            Spec(
                options = opts(
                    text = listOf(
                        "-name", "-iname", "-path", "-ipath", "-wholename", "-iwholename", "-regex", "-iregex", "-regextype",
                        "-type", "-xtype", "-maxdepth", "-mindepth", "-size", "-mtime", "-mmin", "-atime", "-amin",
                        "-ctime", "-cmin", "-perm", "-user", "-group", "-printf", "-newermt", "-links", "-inum",
                    ),
                    paths = listOf("-newer", "-samefile"),
                ),
                singleDashLong = true,
                forbidden = setOf("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls", "-L", "-follow"),
            ),
        )
        put(
            "jq",
            Spec(
                options = opts(text = listOf("--indent", "--tab")) + mapOf(
                    "--arg" to listOf(Kind.EXEMPT, Kind.EXEMPT),
                    "--argjson" to listOf(Kind.EXEMPT, Kind.EXEMPT),
                    "--slurpfile" to listOf(Kind.EXEMPT, Kind.PATH),
                    "--rawfile" to listOf(Kind.EXEMPT, Kind.PATH),
                ),
                scriptFirst = true,
                forbidden = setOf("-f", "--from-file", "-L"),
            ),
        )
    }

    /**
     * Whether Python [code] only reads: no writing, deleting, running programs, network, or the indirection that could
     * hide them (eval, getattr, __import__…), files opened only for reading, and every path it spells out passing
     * [pathOk]. A pattern check, so it asks when unsure.
     */
    private fun pythonReadsOnly(code: String, pathOk: (String) -> Boolean): Boolean {
        val stripped = code.replace("sys.stdout.write(", "print(").replace("sys.stderr.write(", "print(")
        if (PYTHON_FORBIDDEN.any { stripped.contains(it) }) return false
        if (PYTHON_ROOT_WALK.containsMatchIn(code)) return false
        if (!opensOnlyToRead(code)) return false
        return PYTHON_STRING.findAll(code).all { match ->
            val text = match.groupValues[2].ifEmpty { match.groupValues[3] }
            text.length < 2 || !(text.startsWith("/") || text.startsWith("~") || text.split('/').any { it == ".." }) ||
                !rootFolderExists(text) || pathOk(text)
        }
    }

    /** "/api/v1" in code is usually not a path: only text under a folder that exists at the top of the disk counts. */
    private fun rootFolderExists(text: String): Boolean {
        if (!text.startsWith("/")) return true
        val first = text.drop(1).substringBefore('/')
        return first.isEmpty() || runCatching { Files.exists(Paths.get("/", first)) }.getOrDefault(true)
    }

    /** Every `open(…)` and `.open(…)` reads: no mode, or a literal one of r, b, t. */
    private fun opensOnlyToRead(code: String): Boolean {
        var from = 0
        while (true) {
            val at = OPEN_CALL.find(code, from) ?: return true
            val start = at.range.last + 1
            var depth = 1
            var j = start
            var quote: Char? = null
            val args = mutableListOf(StringBuilder())
            while (j < code.length && depth > 0) {
                val c = code[j]
                when {
                    quote != null -> { if (c == '\\') { args.last().append(c); j++ } else if (c == quote) quote = null }
                    c == '\'' || c == '"' -> quote = c
                    c == '(' || c == '[' || c == '{' -> depth++
                    c == ')' || c == ']' || c == '}' -> depth--
                    c == ',' && depth == 1 -> { args += StringBuilder(); j++; continue }
                }
                if (depth > 0 && j < code.length) args.last().append(code[j])
                j++
            }
            if (depth > 0) return false
            val method = at.value.startsWith(".")
            val list = args.map { it.toString().trim() }.filter { it.isNotEmpty() }
            val positional = list.filterNot { KWARG.matches(it) }
            val mode = list.firstOrNull { it.startsWith("mode") }?.substringAfter('=')?.trim()
                ?: positional.getOrNull(if (method) 0 else 1)
            if (mode != null && !READ_MODE.matches(mode)) return false
            if (list.any { KWARG.matches(it) && it.substringBefore('=').trim() !in setOf("mode", "encoding", "errors", "newline", "buffering") }) return false
            from = j
        }
    }

    private const val MAX_SCRIPT = 200_000L
    private val PYTHON_FLAGS = setOf("-I", "-u", "-B", "-E", "-s", "-S", "-q", "-O", "-OO", "-P")
    private val PYTHON_FORBIDDEN = listOf(
        "subprocess", "os.system", "os.popen", "os.exec", "os.spawn", "os.fork", "os.kill", "os.remove", "os.unlink",
        "os.rename", "os.replace", "os.makedirs", "os.mkdir", "os.rmdir", "os.removedirs", "os.chmod", "os.chown",
        "os.link", "os.symlink", "os.truncate", "os.chdir", "os.environ", "os.getenv", "os.putenv", "os.open",
        "os.write", "os.utime", "os.path.expanduser", "expandvars", "shutil", "socket", "urllib", "http.", "requests",
        "ftplib", "smtplib", "telnetlib", "ctypes", "cffi", "pickle", "marshal", "shelve", "dbm", "sqlite3", "tempfile",
        "multiprocessing", "asyncio", "pty", "webbrowser", "importlib", "__import__", "__builtins__", "__class__",
        "__subclasses__", "__globals__", "__dict__", "eval(", "exec(", "compile(", "getattr(", "setattr(", "delattr(",
        "globals(", "locals(", "vars(", "breakpoint(", "input(", "chr(", "fromhex", "base64", "codecs", "zipfile",
        "tarfile", "gzip", "bz2", "lzma", "zlib", ".write(", ".writelines(", ".write_text(", ".write_bytes(", ".unlink(",
        ".rmdir(", ".mkdir(", ".rename(", ".touch(", ".chmod(", ".symlink_to(", ".hardlink_to(", ".expanduser(",
        ".home(", "Path.home", "os.walk(\"/\")", "sys.modules", "atexit", "signal",
    )
    private val PYTHON_ROOT_WALK = Regex("""(walk|listdir|scandir|glob|iglob|Path|open|iterdir|rglob)\(\s*[rbf]?['"]/['"]""")
    private val PYTHON_STRING = Regex("""([rbfuRBFU]{0,2})(?:'((?:\\.|[^'\\\n])*)'|"((?:\\.|[^"\\\n])*)")""")
    private val OPEN_CALL = Regex("""(?<![A-Za-z0-9_])open\(|\.open\(""")
    private val KWARG = Regex("""[A-Za-z_][A-Za-z0-9_]*\s*=[^=].*""", RegexOption.DOT_MATCHES_ALL)
    private val READ_MODE = Regex("""[rbf]?['"][rbt]*['"]""")

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val WINDOWS_DRIVE = Regex("/([A-Za-z])(?:/(.*))?")
    private val LIST_FLAGS = Regex("-[rRisnwxEFIHh]*[lL][rRisnwxEFIHh]*")
    private const val MAX_VALUES = 64
    private val KEYWORDS = setOf("do", "done", "if", "then", "else", "elif", "fi", "while", "until", "case", "esac", "select", "function", "{", "}", "[[", "]]", "!", "time", "coproc")
    private val SAFE_ENV = setOf("LC_ALL", "LANG", "LC_CTYPE", "LC_COLLATE", "TZ", "NO_COLOR", "COLUMNS", "TERM")
    private val DANGEROUS_VARS = setOf(
        "PATH", "IFS", "CDPATH", "BASH_ENV", "ENV", "PAGER", "MANPAGER", "EDITOR", "VISUAL", "HOME", "PS4",
        "PROMPT_COMMAND", "SHELLOPTS", "BASHOPTS", "GLOBIGNORE", "LESSOPEN", "LESSCLOSE", "OLDPWD", "PWD",
    )
    private val XARGS_FLAGS = setOf("-0", "-r", "-t", "--null", "--no-run-if-empty", "--verbose")
    private val XARGS_OPTIONS = setOf("-n", "-L", "-I", "-d", "-P", "-s")
    private val GIT_GLOBAL_FLAGS = setOf("--no-pager", "-P", "--no-optional-locks", "--literal-pathspecs", "--no-replace-objects")
    private val GIT_READERS = setOf(
        "status", "log", "diff", "show", "blame", "annotate", "rev-parse", "ls-files", "ls-tree", "grep", "describe",
        "shortlog", "cat-file", "merge-base", "rev-list", "name-rev", "for-each-ref", "show-ref", "count-objects",
        "whatchanged", "show-branch", "check-ignore", "check-attr", "cherry", "range-diff",
    )
    private val GIT_FORBIDDEN = setOf("--output", "--ext-diff", "-O", "--open-files-in-pager", "--textconv", "--exec", "--upload-pack", "--receive-pack")
    private val GIT_BRANCH_LIST = setOf("-a", "-r", "-v", "-vv", "--all", "--remotes", "--list", "-l", "--show-current", "--no-color", "--color", "--contains", "--no-contains", "--merged", "--no-merged", "--points-at", "--sort", "--format")
    private val GIT_BRANCH_FILTERS = setOf("--list", "-l", "--contains", "--no-contains", "--merged", "--no-merged", "--points-at")
    private val GIT_CONFIG_READS = setOf("--get", "--get-all", "--get-regexp", "--list", "-l")
    private val GIT_CONFIG_WRITES = setOf("--add", "--unset", "--unset-all", "--replace-all", "--rename-section", "--remove-section", "-e", "--edit")
}
