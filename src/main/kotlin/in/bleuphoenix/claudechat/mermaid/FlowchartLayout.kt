package `in`.bleuphoenix.claudechat.mermaid

/** Where everything goes, in the caller's units (pixels). (x, y) is a node's top-left corner. */
data class FlowLayout(val nodes: List<PlacedNode>, val edges: List<RoutedEdge>, val width: Float, val height: Float)

data class PlacedNode(val node: FlowNode, val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX get() = x + width / 2
    val centerY get() = y + height / 2
}

/**
 * [points] run from the source to the target; the arrowhead goes at the last point. A [loop] goes back against the
 * flow, around the side of the chart, and is drawn as straight runs rather than a curve.
 */
data class RoutedEdge(val edge: FlowEdge, val points: List<Point>, val loop: Boolean = false)

data class Point(val x: Float, val y: Float)

/**
 * Layered ("Sugiyama-style") layout: break cycles, assign ranks by longest path, route long edges through
 * placeholder nodes, order each rank to reduce crossings, then position nodes near their neighbors. Edges that go
 * back against the flow are routed around the side instead, each in its own lane.
 * Left-right charts are laid out top-down with swapped sizes and transposed at the end.
 */
object FlowchartLayout {

    fun layout(
        chart: Flowchart,
        sizeOf: (FlowNode) -> Pair<Float, Float>,
        rankGap: Float,
        nodeGap: Float,
        padding: Float,
    ): FlowLayout {
        val horizontal = chart.direction == Direction.LeftRight || chart.direction == Direction.RightLeft
        val ids = chart.nodes.map { it.id }
        val nodeById = chart.nodes.associateBy { it.id }

        // Sizes along (cross axis, rank axis); left-right charts swap them.
        val size = chart.nodes.associate { node ->
            val (w, h) = sizeOf(node)
            node.id to if (horizontal) h to w else w to h
        }

        // 1. Break cycles: edges that point back to a node on the DFS stack are reversed for layering.
        val adjacency = ids.associateWith { mutableListOf<String>() }
        val edges = chart.edges.filter { it.from != it.to && it.from in nodeById && it.to in nodeById }
        edges.forEach { adjacency.getValue(it.from) += it.to }
        val reversed = HashSet<Pair<String, String>>()
        val state = HashMap<String, Int>() // 1 = on stack, 2 = done
        fun visit(v: String) {
            state[v] = 1
            for (w in adjacency.getValue(v)) {
                when (state[w]) {
                    null -> visit(w)
                    1 -> reversed += v to w
                }
            }
            state[v] = 2
        }
        ids.forEach { if (it !in state) visit(it) }

        data class DagEdge(val from: String, val to: String, val original: FlowEdge, val flipped: Boolean)
        val dagEdges = edges.map { e ->
            if ((e.from to e.to) in reversed) DagEdge(e.to, e.from, e, true) else DagEdge(e.from, e.to, e, false)
        }

        // 2. Ranks: longest path from the sources.
        val rank = HashMap<String, Int>().apply { ids.forEach { put(it, 0) } }
        val incoming = ids.associateWith { id -> dagEdges.count { it.to == id } }.toMutableMap()
        val queue = ArrayDeque(ids.filter { incoming[it] == 0 })
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (e in dagEdges.filter { it.from == v }) {
                rank[e.to] = maxOf(rank.getValue(e.to), rank.getValue(v) + 1)
                incoming[e.to] = incoming.getValue(e.to) - 1
                if (incoming[e.to] == 0) queue += e.to
            }
        }

        // 3. Placeholder nodes so every segment joins adjacent ranks.
        val placeholderSize = 0f to 0f
        val vertexSize = HashMap<String, Pair<Float, Float>>(size)
        val segments = mutableListOf<Pair<String, String>>()
        val chains = HashMap<DagEdge, List<String>>()
        dagEdges.forEachIndexed { index, e ->
            // Back edges take no part in placement; they're routed around the side at the end.
            if (e.flipped) return@forEachIndexed
            val chain = mutableListOf(e.from)
            for (r in rank.getValue(e.from) + 1 until rank.getValue(e.to)) {
                val dummy = "\u0000$index:$r"
                rank[dummy] = r
                vertexSize[dummy] = placeholderSize
                chain += dummy
            }
            chain += e.to
            chain.zipWithNext().forEach { segments += it }
            chains[e] = chain
        }

        // 4. Order within ranks: barycenter sweeps, starting from first-appearance order.
        val maxRank = rank.values.maxOrNull() ?: 0
        val layers = (0..maxRank).map { r -> rank.filterValues { it == r }.keys.sortedBy { order(it, ids) }.toMutableList() }
        val predecessors = segments.groupBy({ it.second }, { it.first })
        val successors = segments.groupBy({ it.first }, { it.second })
        repeat(6) { sweep ->
            val down = sweep % 2 == 0
            val range = if (down) 1..maxRank else (maxRank - 1) downTo 0
            for (r in range) {
                val reference = layers[if (down) r - 1 else r + 1]
                val neighbors = if (down) predecessors else successors
                val current = layers[r].withIndex().associate { it.value to it.index.toFloat() }
                layers[r].sortBy { v ->
                    val ns = neighbors[v].orEmpty().map { reference.indexOf(it) }.filter { it >= 0 }
                    if (ns.isEmpty()) current.getValue(v) else ns.average().toFloat()
                }
            }
        }

        // 5. Cross-axis positions (centers): pack each rank, then pull nodes toward their neighbors' average.
        val center = HashMap<String, Float>()
        fun widthOf(v: String) = vertexSize.getValue(v).first
        fun gapAfter(v: String) = if (v.startsWith("\u0000")) nodeGap / 2 else nodeGap
        layers.forEach { layer ->
            var cursor = 0f
            layer.forEach { v ->
                center[v] = cursor + widthOf(v) / 2
                cursor += widthOf(v) + gapAfter(v)
            }
            val shift = (cursor - nodeGap) / 2
            layer.forEach { center[it] = center.getValue(it) - shift }
        }
        repeat(4) { pass ->
            val down = pass % 2 == 0
            val order = if (down) layers.indices else layers.indices.reversed()
            for (r in order) {
                val layer = layers[r]
                val neighbors = if (down) predecessors else successors
                val desired = layer.map { v ->
                    neighbors[v].orEmpty().mapNotNull { center[it] }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                        ?: center.getValue(v)
                }
                // Keep order and spacing: walk left to right, never closer than the gap.
                val placed = FloatArray(layer.size)
                layer.forEachIndexed { i, v ->
                    val minCenter = if (i == 0) Float.NEGATIVE_INFINITY else {
                        val prev = layer[i - 1]
                        placed[i - 1] + widthOf(prev) / 2 + gapAfter(prev) + widthOf(v) / 2
                    }
                    placed[i] = maxOf(desired[i], minCenter)
                }
                // Re-center the rank on where its nodes wanted to be.
                val drift = (placed.indices.map { placed[it] - desired[it] }.average()).toFloat()
                layer.forEachIndexed { i, v -> center[v] = placed[i] - drift }
            }
        }

        // 6. Rank-axis positions: each rank as thick as its tallest node.
        val rankThickness = layers.map { layer -> layer.maxOfOrNull { vertexSize.getValue(it).second } ?: 0f }
        val rankStart = FloatArray(layers.size)
        for (r in 1 until layers.size) rankStart[r] = rankStart[r - 1] + rankThickness[r - 1] + rankGap

        val minCross = center.entries.minOf { it.value - widthOf(it.key) / 2 }
        fun cross(v: String) = center.getValue(v) - minCross + padding
        fun along(v: String): Float {
            val r = rank.getValue(v)
            return rankStart[r] + rankThickness[r] / 2 + padding
        }

        // Back edges: one lane each beside the nodes they pass, shorter loops nearer the chart.
        val backEdges = dagEdges.filter { it.flipped }.sortedBy { rank.getValue(it.to) - rank.getValue(it.from) }
        val laneCenter = HashMap<DagEdge, Float>()
        val laneGap = nodeGap * 0.7f
        backEdges.forEach { e ->
            val top = rank.getValue(e.from)
            val bottom = rank.getValue(e.to)
            val besideNodes = ids.filter { rank.getValue(it) in top..bottom }.maxOf { center.getValue(it) + widthOf(it) / 2 }
            val besideLanes = laneCenter.filterKeys { other ->
                rank.getValue(other.from) <= bottom && rank.getValue(other.to) >= top
            }.values.maxOrNull()
            laneCenter[e] = maxOf(besideNodes + laneGap, (besideLanes ?: Float.NEGATIVE_INFINITY) + laneGap)
        }

        val crossExtent = maxOf(
            center.entries.maxOf { it.value + widthOf(it.key) / 2 },
            (laneCenter.values.maxOrNull() ?: Float.NEGATIVE_INFINITY) + laneGap / 2,
        ) - minCross + 2 * padding
        val alongExtent = rankStart.last() + rankThickness.last() + 2 * padding
        val flipAlong = chart.direction == Direction.BottomUp || chart.direction == Direction.RightLeft

        fun toPoint(crossValue: Float, alongValue: Float): Point {
            val a = if (flipAlong) alongExtent - alongValue else alongValue
            return if (horizontal) Point(a, crossValue) else Point(crossValue, a)
        }

        val placedNodes = chart.nodes.map { node ->
            val (w, h) = sizeOf(node)
            val c = toPoint(cross(node.id), along(node.id))
            PlacedNode(node, c.x - w / 2, c.y - h / 2, w, h)
        }

        // 7. Edges: leave the source's far side, pass through placeholders, enter the target's near side.
        // Edges joining the same two nodes (either direction) get side-by-side lanes so they don't overlap.
        val lanes = HashMap<DagEdge, Float>()
        dagEdges.filter { !it.flipped }.groupBy { setOf(it.from, it.to) }.values.filter { it.size > 1 }.forEach { group ->
            group.forEachIndexed { i, e -> lanes[e] = (i - (group.size - 1) / 2f) * nodeGap * 0.8f }
        }
        val routed = dagEdges.map { e ->
            laneCenter[e]?.let { lane ->
                // The original edge runs from e.to (further along) back to e.from: out of its side, along the lane,
                // into the target's side.
                val source = e.to
                val target = e.from
                val laneCross = lane - minCross + padding
                val points = listOf(
                    toPoint(cross(source) + widthOf(source) / 2, along(source)),
                    toPoint(laneCross, along(source)),
                    toPoint(laneCross, along(target)),
                    toPoint(cross(target) + widthOf(target) / 2, along(target)),
                )
                return@map RoutedEdge(e.original, points, loop = true)
            }
            val chain = chains.getValue(e)
            val lane = lanes[e] ?: 0f
            val points = chain.mapIndexed { i, v ->
                val half = vertexSize.getValue(v).second / 2
                val offset = when (i) {
                    0 -> half
                    chain.lastIndex -> -half
                    else -> 0f
                }
                toPoint(cross(v) + lane, along(v) + offset)
            }
            RoutedEdge(e.original, if (e.flipped) points.reversed() else points)
        }

        val (width, height) = if (horizontal) alongExtent to crossExtent else crossExtent to alongExtent
        return FlowLayout(placedNodes, routed, width, height)
    }

    private fun order(v: String, ids: List<String>): Int =
        ids.indexOf(v).takeIf { it >= 0 } ?: (ids.size + v.hashCode().mod(1000))
}
