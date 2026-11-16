package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.mermaid.Direction
import `in`.bleuphoenix.claudechat.mermaid.EdgeStyle
import `in`.bleuphoenix.claudechat.mermaid.FlowNode
import `in`.bleuphoenix.claudechat.mermaid.Flowchart
import `in`.bleuphoenix.claudechat.mermaid.FlowchartLayout
import `in`.bleuphoenix.claudechat.mermaid.NodeShape
import `in`.bleuphoenix.claudechat.mermaid.Point
import org.jetbrains.jewel.foundation.theme.JewelTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Draws a parsed Mermaid flowchart. Wider than the space available, it scrolls horizontally. */
@Composable
fun FlowchartView(chart: Flowchart, modifier: Modifier = Modifier) {
    val colors = chatColors()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = JewelTheme.defaultTextStyle.copy(color = colors.text, textAlign = TextAlign.Center)
    val edgeLabelStyle = smallStyle().copy(color = colors.muted, textAlign = TextAlign.Center)

    val diagram = remember(chart, labelStyle, edgeLabelStyle, density) {
        with(density) {
            val maxLabel = 200.dp.roundToPx()
            val labels = chart.nodes.associate { node ->
                node.id to measurer.measure(node.label, labelStyle, constraints = Constraints(maxWidth = maxLabel))
            }
            val edgeLabels = chart.edges.mapNotNull { edge ->
                edge.label?.let { edge to measurer.measure(it, edgeLabelStyle, constraints = Constraints(maxWidth = 140.dp.roundToPx())) }
            }.toMap()
            // Leave room between ranks for edge labels: their width in left-right charts, their height top-down.
            val horizontal = chart.direction == Direction.LeftRight || chart.direction == Direction.RightLeft
            val labelRoom = edgeLabels.values.maxOfOrNull { if (horizontal) it.size.width.toFloat() else it.size.height.toFloat() } ?: 0f
            val padX = 14.dp.toPx()
            val padY = 9.dp.toPx()
            val layout = FlowchartLayout.layout(
                chart,
                sizeOf = { node -> nodeSize(node, labels.getValue(node.id), padX, padY) },
                rankGap = maxOf(if (horizontal) 56.dp.toPx() else 44.dp.toPx(), labelRoom + 36.dp.toPx()),
                nodeGap = 22.dp.toPx(),
                padding = 12.dp.toPx(),
            )
            Diagram(layout, labels, edgeLabels)
        }
    }

    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border.copy(alpha = 0.6f), shape)
            .background(colors.codeFill.copy(alpha = 0.5f))
            .padding(4.dp),
    ) {
        // Shrink wide diagrams to fit the panel, down to 60%; beyond that, scroll.
        val available = constraints.maxWidth.toFloat()
        val fit = if (diagram.layout.width > available) (available / diagram.layout.width).coerceIn(MIN_SCALE, 1f) else 1f
        val width = with(density) { (diagram.layout.width * fit).toDp() }
        val height = with(density) { (diagram.layout.height * fit).toDp() }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(width, height)) {
                scale(fit, fit, pivot = Offset.Zero) { drawDiagram(diagram, chart.direction, colors) }
            }
        }
    }
}

private fun DrawScope.drawDiagram(diagram: Diagram, direction: Direction, colors: ChatColors) {
    val stroke = 1.3.dp.toPx()
    diagram.layout.edges.forEach { routed ->
        if (routed.loop) {
            drawLoop(routed.points, routed.edge.style, routed.edge.arrow, colors.muted, stroke)
        } else {
            drawEdge(routed.points, routed.edge.style, routed.edge.arrow, colors.muted, stroke, direction)
        }
    }
    diagram.layout.nodes.forEach { placed ->
        val accent = placed.node.shape == NodeShape.Diamond
        drawNode(
            placed.node.shape,
            Rect(placed.x, placed.y, placed.x + placed.width, placed.y + placed.height),
            fill = if (accent) colors.brand.copy(alpha = 0.14f) else colors.userBubble,
            border = if (accent) colors.brand.copy(alpha = 0.9f) else colors.border,
            stroke = stroke,
        )
        val label = diagram.labels.getValue(placed.node.id)
        drawText(label, topLeft = Offset(placed.centerX - label.size.width / 2f, placed.centerY - label.size.height / 2f))
    }
    diagram.layout.edges.forEach { routed ->
        val label = diagram.edgeLabels[routed.edge] ?: return@forEach
        val mid = midpoint(routed.points)
        val pad = 4.dp.toPx()
        val box = Rect(
            mid.x - label.size.width / 2f - pad, mid.y - label.size.height / 2f - pad / 2,
            mid.x + label.size.width / 2f + pad, mid.y + label.size.height / 2f + pad / 2,
        )
        drawRoundRect(colors.background, box.topLeft, box.size, CornerRadius(box.height / 2))
        drawText(label, topLeft = Offset(mid.x - label.size.width / 2f, mid.y - label.size.height / 2f))
    }
}

private const val MIN_SCALE = 0.6f

private class Diagram(
    val layout: `in`.bleuphoenix.claudechat.mermaid.FlowLayout,
    val labels: Map<String, TextLayoutResult>,
    val edgeLabels: Map<`in`.bleuphoenix.claudechat.mermaid.FlowEdge, TextLayoutResult>,
)

/** Shapes that cut into their box (diamond, hexagon, circle) are made larger so the label still fits. */
private fun nodeSize(node: FlowNode, label: TextLayoutResult, padX: Float, padY: Float): Pair<Float, Float> {
    val w = label.size.width + 2 * padX
    val h = label.size.height + 2 * padY
    return when (node.shape) {
        NodeShape.Diamond -> w * 1.45f to max(h * 1.6f, w * 0.55f)
        NodeShape.Circle -> max(w, h).let { it to it }
        NodeShape.Hexagon -> w + h * 0.6f to h
        NodeShape.Cylinder -> w to h + padY
        else -> w to h
    }
}

private fun DrawScope.drawNode(shape: NodeShape, box: Rect, fill: Color, border: Color, stroke: Float) {
    val radius = 7.dp.toPx()
    when (shape) {
        NodeShape.Rect, NodeShape.Subroutine -> {
            drawRoundRect(fill, box.topLeft, box.size, CornerRadius(radius))
            drawRoundRect(border, box.topLeft, box.size, CornerRadius(radius), style = Stroke(stroke))
            if (shape == NodeShape.Subroutine) {
                val inset = 7.dp.toPx()
                drawLine(border, Offset(box.left + inset, box.top), Offset(box.left + inset, box.bottom), stroke)
                drawLine(border, Offset(box.right - inset, box.top), Offset(box.right - inset, box.bottom), stroke)
            }
        }
        NodeShape.Round, NodeShape.Stadium -> {
            val r = if (shape == NodeShape.Stadium) box.height / 2 else 14.dp.toPx()
            drawRoundRect(fill, box.topLeft, box.size, CornerRadius(r))
            drawRoundRect(border, box.topLeft, box.size, CornerRadius(r), style = Stroke(stroke))
        }
        NodeShape.Circle -> {
            drawCircle(fill, box.width / 2, box.center)
            drawCircle(border, box.width / 2, box.center, style = Stroke(stroke))
        }
        NodeShape.Diamond -> polygon(
            listOf(Offset(box.center.x, box.top), Offset(box.right, box.center.y), Offset(box.center.x, box.bottom), Offset(box.left, box.center.y)),
            fill, border, stroke,
        )
        NodeShape.Hexagon -> {
            val cut = box.height / 2 * 0.6f
            polygon(
                listOf(
                    Offset(box.left + cut, box.top), Offset(box.right - cut, box.top), Offset(box.right, box.center.y),
                    Offset(box.right - cut, box.bottom), Offset(box.left + cut, box.bottom), Offset(box.left, box.center.y),
                ),
                fill, border, stroke,
            )
        }
        NodeShape.Cylinder -> {
            val lid = 6.dp.toPx()
            drawRoundRect(fill, Offset(box.left, box.top + lid / 2), Size(box.width, box.height - lid), CornerRadius(0f))
            drawOval(fill, Offset(box.left, box.bottom - lid * 1.5f), Size(box.width, lid * 1.5f))
            drawOval(fill, box.topLeft, Size(box.width, lid * 1.5f))
            drawLine(border, Offset(box.left, box.top + lid * 0.75f), Offset(box.left, box.bottom - lid * 0.75f), stroke)
            drawLine(border, Offset(box.right, box.top + lid * 0.75f), Offset(box.right, box.bottom - lid * 0.75f), stroke)
            drawArc(border, 0f, 180f, false, Offset(box.left, box.bottom - lid * 1.5f), Size(box.width, lid * 1.5f), style = Stroke(stroke))
            drawOval(border, box.topLeft, Size(box.width, lid * 1.5f), style = Stroke(stroke))
        }
    }
}

private fun DrawScope.polygon(points: List<Offset>, fill: Color, border: Color, stroke: Float) {
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, fill)
    drawPath(path, border, style = Stroke(stroke, join = StrokeJoin.Round))
}

/** A smooth curve through the routed points, tangent to the flow direction, with an arrowhead at the end. */
private fun DrawScope.drawEdge(points: List<Point>, style: EdgeStyle, arrow: Boolean, color: Color, stroke: Float, direction: Direction) {
    if (points.size < 2) return
    val horizontal = direction == Direction.LeftRight || direction == Direction.RightLeft
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.zipWithNext().forEach { (a, b) ->
            if (horizontal) {
                val dx = (b.x - a.x) / 2
                cubicTo(a.x + dx, a.y, b.x - dx, b.y, b.x, b.y)
            } else {
                val dy = (b.y - a.y) / 2
                cubicTo(a.x, a.y + dy, b.x, b.y - dy, b.x, b.y)
            }
        }
    }
    val width = if (style == EdgeStyle.Thick) stroke * 2.2f else stroke
    val effect = if (style == EdgeStyle.Dotted) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))

    if (arrow) {
        val end = points.last()
        val before = points[points.size - 2]
        // The curve arrives along the flow axis, so the arrow points that way.
        val angle = when {
            horizontal -> if (end.x >= before.x) 0.0 else Math.PI
            else -> if (end.y >= before.y) Math.PI / 2 else -Math.PI / 2
        }
        drawArrowhead(end, angle, color)
    }
}

/** An edge back against the flow: straight runs around the side with rounded corners, the arrow along the last run. */
private fun DrawScope.drawLoop(points: List<Point>, style: EdgeStyle, arrow: Boolean, color: Color, stroke: Float) {
    if (points.size < 2) return
    val radius = 8.dp.toPx()
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.lastIndex) {
            val (prev, corner, next) = Triple(points[i - 1], points[i], points[i + 1])
            val r = minOf(radius, distance(prev, corner) / 2, distance(corner, next) / 2)
            val into = toward(corner, prev, r)
            val out = toward(corner, next, r)
            lineTo(into.x, into.y)
            quadraticTo(corner.x, corner.y, out.x, out.y)
        }
        lineTo(points.last().x, points.last().y)
    }
    val width = if (style == EdgeStyle.Thick) stroke * 2.2f else stroke
    val effect = if (style == EdgeStyle.Dotted) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))
    if (arrow) {
        val end = points.last()
        val before = points[points.size - 2]
        drawArrowhead(end, atan2((end.y - before.y).toDouble(), (end.x - before.x).toDouble()), color)
    }
}

private fun DrawScope.drawArrowhead(end: Point, angle: Double, color: Color) {
    val length = 8.dp.toPx()
    val spread = 0.45
    val head = Path().apply {
        moveTo(end.x, end.y)
        lineTo((end.x - length * cos(angle - spread)).toFloat(), (end.y - length * sin(angle - spread)).toFloat())
        lineTo((end.x - length * cos(angle + spread)).toFloat(), (end.y - length * sin(angle + spread)).toFloat())
        close()
    }
    drawPath(head, color)
}

private fun distance(a: Point, b: Point) = hypot(b.x - a.x, b.y - a.y)

/** The point [length] from [from] toward [to]. */
private fun toward(from: Point, to: Point, length: Float): Point {
    val d = distance(from, to).takeIf { it > 0f } ?: return from
    return Point(from.x + (to.x - from.x) / d * length, from.y + (to.y - from.y) / d * length)
}

private fun midpoint(points: List<Point>): Offset {
    if (points.size % 2 == 1) points[points.size / 2].let { return Offset(it.x, it.y) }
    val a = points[points.size / 2 - 1]
    val b = points[points.size / 2]
    return Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
}
