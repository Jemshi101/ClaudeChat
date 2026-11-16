package `in`.bleuphoenix.claudechat.mermaid

/** A Mermaid flowchart: the subset plans use (nodes with shapes, labeled edges, a direction). */
data class Flowchart(val direction: Direction, val nodes: List<FlowNode>, val edges: List<FlowEdge>)

enum class Direction { TopDown, BottomUp, LeftRight, RightLeft }

enum class NodeShape { Rect, Round, Stadium, Circle, Diamond, Hexagon, Subroutine, Cylinder }

data class FlowNode(val id: String, val label: String, val shape: NodeShape)

enum class EdgeStyle { Solid, Dotted, Thick }

data class FlowEdge(val from: String, val to: String, val label: String?, val style: EdgeStyle, val arrow: Boolean)

/**
 * Parses Mermaid `flowchart`/`graph` source. Returns null for other diagram types or unreadable input, so callers can
 * fall back to showing the source. Styling statements are ignored and subgraphs are flattened.
 */
object FlowchartParser {

    private val HEADER = Regex("""^(?:flowchart|graph)(?:\s+(TD|TB|BT|LR|RL))?\s*$""", RegexOption.IGNORE_CASE)
    private val IGNORED = Regex("""^(?:classDef|class|style|linkStyle|click|subgraph|end|direction)\b""")
    private val ID = Regex("""[\p{L}\p{N}_]+""")
    private val CLASS_SUFFIX = Regex(""":::[\w-]+""")

    // A link: optional start arrow, the line (--, ---, -.-, ==, ~~~), an optional end (>, x, o), an optional |label|.
    private val LINK = Regex("""\s*<?(-{2,}|-\.+-|={2,}|~~~)(>|[xo](?=\s))?\s*(?:\|([^|]*)\|)?\s*""")

    // Labels written inside the link ("A -- text --> B") are rewritten to the |text| form first.
    private val TEXT_ON_LINK = listOf(
        Regex("""\s--\s+([^-|>][^|]*?)\s+(-{2,}>?)""") to { m: MatchResult -> " ${m.groupValues[2].ifEmpty { "-->" }}|${m.groupValues[1]}| " },
        Regex("""\s-\.\s+(.+?)\s+\.->""") to { m: MatchResult -> " -.->|${m.groupValues[1]}| " },
        Regex("""\s==\s+(.+?)\s+==>""") to { m: MatchResult -> " ==>|${m.groupValues[1]}| " },
    )

    /** Opening and closing delimiters of node shapes, longest first so `((` wins over `(`. */
    private val SHAPES = listOf(
        Triple("((", "))", NodeShape.Circle),
        Triple("([", "])", NodeShape.Stadium),
        Triple("[[", "]]", NodeShape.Subroutine),
        Triple("[(", ")]", NodeShape.Cylinder),
        Triple("{{", "}}", NodeShape.Hexagon),
        Triple("[/", "/]", NodeShape.Rect),
        Triple("[\\", "\\]", NodeShape.Rect),
        Triple("[", "]", NodeShape.Rect),
        Triple("(", ")", NodeShape.Round),
        Triple("{", "}", NodeShape.Diamond),
        Triple(">", "]", NodeShape.Rect),
    )

    fun parse(source: String): Flowchart? {
        val statements = source.lines()
            .map { it.substringBefore("%%").trim() }
            .flatMap { splitStatements(it) }
            .filter { it.isNotEmpty() }
        if (statements.isEmpty()) return null
        val header = HEADER.matchEntire(statements.first()) ?: return null
        val direction = when (header.groupValues[1].uppercase()) {
            "LR" -> Direction.LeftRight
            "RL" -> Direction.RightLeft
            "BT" -> Direction.BottomUp
            else -> Direction.TopDown
        }

        val builder = Builder()
        for (statement in statements.drop(1)) {
            if (IGNORED.containsMatchIn(statement)) continue
            runCatching { parseStatement(statement, builder) }
        }
        if (builder.nodes.isEmpty()) return null
        return Flowchart(direction, builder.nodes.values.toList(), builder.edges)
    }

    private class Builder {
        val nodes = LinkedHashMap<String, FlowNode>()
        val edges = mutableListOf<FlowEdge>()
        val defined = HashSet<String>()

        fun node(id: String, label: String?, shape: NodeShape?) {
            if (label != null && shape != null) {
                nodes[id] = FlowNode(id, label, shape)
                defined += id
            } else if (id !in nodes) {
                nodes[id] = FlowNode(id, id, NodeShape.Rect)
            }
        }
    }

    /** Splits on `;` outside quotes and brackets. */
    private fun splitStatements(line: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quoted = false
        val current = StringBuilder()
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                !quoted && c in "[({" -> depth++
                !quoted && c in "])}" -> depth = (depth - 1).coerceAtLeast(0)
            }
            if (c == ';' && !quoted && depth == 0) {
                out += current.toString().trim()
                current.clear()
            } else {
                current.append(c)
            }
        }
        out += current.toString().trim()
        return out
    }

    private fun parseStatement(raw: String, builder: Builder) {
        var text = " $raw"
        TEXT_ON_LINK.forEach { (regex, rewrite) -> text = regex.replace(text, rewrite) }
        text = text.trim()

        var index = 0
        var previous = parseNodeGroup(text, index, builder) ?: return
        index = previous.second
        while (index < text.length) {
            val link = LINK.matchAt(text, index) ?: break
            val nextGroup = parseNodeGroup(text, link.range.last + 1, builder) ?: break
            val line = link.groupValues[1]
            val style = when {
                line.startsWith("=") -> EdgeStyle.Thick
                line.contains('.') -> EdgeStyle.Dotted
                else -> EdgeStyle.Solid
            }
            val label = link.groupValues[3].trim().takeIf { it.isNotEmpty() }?.let(::cleanLabel)
            val arrow = link.groupValues[2] == ">"
            if (line != "~~~") {
                for (from in previous.first) for (to in nextGroup.first) {
                    builder.edges += FlowEdge(from, to, label, style, arrow)
                }
            }
            previous = nextGroup
            index = nextGroup.second
        }
    }

    /** `A & B[label] & C` → the ids and the index after the group. */
    private fun parseNodeGroup(text: String, start: Int, builder: Builder): Pair<List<String>, Int>? {
        val ids = mutableListOf<String>()
        var index = start
        while (true) {
            while (index < text.length && text[index].isWhitespace()) index++
            val (id, end) = parseNode(text, index, builder) ?: return if (ids.isEmpty()) null else ids to index
            ids += id
            index = end
            var lookahead = index
            while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
            if (lookahead < text.length && text[lookahead] == '&') index = lookahead + 1 else return ids to index
        }
    }

    private fun parseNode(text: String, start: Int, builder: Builder): Pair<String, Int>? {
        val id = ID.matchAt(text, start) ?: return null
        var index = id.range.last + 1
        for ((open, close, shape) in SHAPES) {
            if (!text.startsWith(open, index)) continue
            val contentStart = index + open.length
            val closeAt = if (text.startsWith("\"", contentStart)) {
                val endQuote = text.indexOf('"', contentStart + 1)
                if (endQuote < 0) -1 else text.indexOf(close, endQuote + 1)
            } else {
                text.indexOf(close, contentStart)
            }
            if (closeAt < 0) continue
            builder.node(id.value, cleanLabel(text.substring(contentStart, closeAt)), shape)
            index = closeAt + close.length
            CLASS_SUFFIX.matchAt(text, index)?.let { index = it.range.last + 1 }
            return id.value to index
        }
        builder.node(id.value, null, null)
        CLASS_SUFFIX.matchAt(text, index)?.let { index = it.range.last + 1 }
        return id.value to index
    }

    private fun cleanLabel(raw: String): String = raw.trim()
        .removeSurrounding("\"")
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace("#quot;", "\"")
        .replace("`", "")
        .trim()
}
