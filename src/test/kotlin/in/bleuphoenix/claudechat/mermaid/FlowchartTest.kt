package `in`.bleuphoenix.claudechat.mermaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowchartTest {

    private fun parse(source: String) = FlowchartParser.parse(source.trimIndent())!!

    private fun edgesOf(chart: Flowchart) = chart.edges.map { "${it.from}->${it.to}" + (it.label?.let { l -> "[$l]" } ?: "") }

    @Test
    fun `shapes and labels`() {
        val chart = parse(
            """
            flowchart TD
              A[Start] --> B(Round) --> C{Valid?}
              C -->|yes| D([Done])
              C -->|no| E((Retry))
              F{{Hex}} --- G[["Sub [x]"]]
              H[(Db)]
            """,
        )
        assertEquals(Direction.TopDown, chart.direction)
        val shapes = chart.nodes.associate { it.id to it.shape }
        assertEquals(NodeShape.Rect, shapes["A"])
        assertEquals(NodeShape.Round, shapes["B"])
        assertEquals(NodeShape.Diamond, shapes["C"])
        assertEquals(NodeShape.Stadium, shapes["D"])
        assertEquals(NodeShape.Circle, shapes["E"])
        assertEquals(NodeShape.Hexagon, shapes["F"])
        assertEquals(NodeShape.Subroutine, shapes["G"])
        assertEquals(NodeShape.Cylinder, shapes["H"])
        assertEquals("Sub [x]", chart.nodes.first { it.id == "G" }.label)
        assertEquals(listOf("A->B", "B->C", "C->D[yes]", "C->E[no]", "F->G"), edgesOf(chart))
        assertEquals(false, chart.edges.last().arrow)
    }

    @Test
    fun `text on links, styles, groups and semicolons`() {
        val chart = parse(
            """
            graph LR
              A -- check --> B; B -.-> C
              B == ship it ==> D
              C & D --> E
              A -. maybe .-> E
            """,
        )
        assertEquals(Direction.LeftRight, chart.direction)
        assertEquals(listOf("A->B[check]", "B->C", "B->D[ship it]", "C->E", "D->E", "A->E[maybe]"), edgesOf(chart))
        assertEquals(EdgeStyle.Dotted, chart.edges[1].style)
        assertEquals(EdgeStyle.Thick, chart.edges[2].style)
    }

    @Test
    fun `later definitions name earlier references, styling and subgraphs are skipped`() {
        val chart = parse(
            """
            flowchart TB
              %% comment
              classDef hot fill:#f00
              subgraph Build
                A --> B
              end
              A[Compile<br/>sources]:::hot
              style A fill:#fff
            """,
        )
        assertEquals("Compile\nsources", chart.nodes.first { it.id == "A" }.label)
        assertEquals(listOf("A->B"), edgesOf(chart))
    }

    @Test
    fun `other diagram types are not parsed`() {
        assertNull(FlowchartParser.parse("sequenceDiagram\n  A->>B: hi"))
        assertNull(FlowchartParser.parse(""))
    }

    private fun layout(source: String) = FlowchartLayout.layout(parse(source), { 80f to 30f }, rankGap = 40f, nodeGap = 20f, padding = 8f)

    @Test
    fun `top-down layout puts ranks below each other without overlaps`() {
        val result = layout(
            """
            flowchart TD
              A --> B & C & D
              B --> E
              C --> E
              D --> E
            """,
        )
        val byId = result.nodes.associateBy { it.node.id }
        assertTrue(byId.getValue("A").y < byId.getValue("B").y)
        assertTrue(byId.getValue("B").y < byId.getValue("E").y)
        val middle = listOf("B", "C", "D").map { byId.getValue(it) }.sortedBy { it.x }
        middle.zipWithNext().forEach { (left, right) -> assertTrue("overlap", left.x + left.width <= right.x + 0.01f) }
        assertTrue(result.nodes.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= result.width + 0.01f && it.y + it.height <= result.height + 0.01f })
    }

    @Test
    fun `left-right layout flows along x`() {
        val result = layout("flowchart LR\n A --> B --> C")
        val xs = result.nodes.sortedBy { it.node.id }.map { it.x }
        assertTrue(xs[0] < xs[1] && xs[1] < xs[2])
    }

    @Test
    fun `cycles are laid out and long edges get bends`() {
        val result = layout(
            """
            flowchart TD
              A --> B --> C --> D
              D --> A
              A --> D
            """,
        )
        assertEquals(5, result.edges.size)
        val back = result.edges.first { it.edge.from == "D" && it.edge.to == "A" }
        // Routed from D to A, so the arrowhead ends at A.
        assertTrue(back.points.first().y > back.points.last().y)
        val long = result.edges.first { it.edge.from == "A" && it.edge.to == "D" }
        assertTrue("long edges pass through intermediate points", long.points.size > 2)
    }

    @Test
    fun `edges back against the flow go around the side, clear of every node`() {
        val result = layout(
            """
            flowchart TD
              A[Add] --> B[Build] --> C{Clean?}
              C -- no --> A
              C -- yes --> D[Ship]
              D --> E{Hang?}
              E -- yes --> B
            """,
        )
        val loops = result.edges.filter { it.loop }
        assertEquals(listOf("C" to "A", "E" to "B"), loops.map { it.edge.from to it.edge.to }.sortedBy { it.first })
        val nodeRight = result.nodes.maxOf { it.x + it.width }
        loops.forEach { loop ->
            val (start, out, back, end) = loop.points
            val source = result.nodes.single { it.node.id == loop.edge.from }
            val target = result.nodes.single { it.node.id == loop.edge.to }
            // Out of the source's side, up a lane right of all nodes, into the target's side.
            assertEquals(source.x + source.width, start.x, 0.01f)
            assertEquals(target.x + target.width, end.x, 0.01f)
            assertEquals(out.x, back.x, 0.01f)
            assertTrue(out.x > nodeRight)
            assertTrue(back.y < out.y)
            assertTrue("the chart is wide enough for the lane", result.width >= out.x)
        }
        // Overlapping loops get separate lanes.
        assertTrue(loops[0].points[1].x != loops[1].points[1].x)
    }
}
