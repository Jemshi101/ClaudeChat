package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Small vector glyphs drawn directly, so they scale crisply and take any color.

@Composable
fun ArrowUpGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(14.dp)) {
    val stroke = Stroke(width = size.minDimension * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path().apply {
        moveTo(size.width * 0.5f, size.height * 0.85f)
        lineTo(size.width * 0.5f, size.height * 0.15f)
        moveTo(size.width * 0.18f, size.height * 0.45f)
        lineTo(size.width * 0.5f, size.height * 0.15f)
        lineTo(size.width * 0.82f, size.height * 0.45f)
    }
    drawPath(path, color, style = stroke)
}

@Composable
fun ArrowDownGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(14.dp)) {
    val stroke = Stroke(width = size.minDimension * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path().apply {
        moveTo(size.width * 0.5f, size.height * 0.15f)
        lineTo(size.width * 0.5f, size.height * 0.85f)
        moveTo(size.width * 0.18f, size.height * 0.55f)
        lineTo(size.width * 0.5f, size.height * 0.85f)
        lineTo(size.width * 0.82f, size.height * 0.55f)
    }
    drawPath(path, color, style = stroke)
}

@Composable
fun StopGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(10.dp)) {
    drawRoundRect(color, cornerRadius = CornerRadius(size.minDimension * 0.22f))
}

@Composable
fun CheckGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(12.dp)) {
    val path = Path().apply {
        moveTo(size.width * 0.15f, size.height * 0.52f)
        lineTo(size.width * 0.42f, size.height * 0.78f)
        lineTo(size.width * 0.87f, size.height * 0.24f)
    }
    drawPath(path, color, style = Stroke(size.minDimension * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
fun CrossGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(12.dp)) {
    val stroke = size.minDimension * 0.15f
    drawLine(color, Offset(size.width * 0.22f, size.height * 0.22f), Offset(size.width * 0.78f, size.height * 0.78f), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.78f, size.height * 0.22f), Offset(size.width * 0.22f, size.height * 0.78f), stroke, StrokeCap.Round)
}

/** Right-pointing chevron; rotate it 90° for "expanded". */
@Composable
fun ChevronGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(10.dp)) {
    val path = Path().apply {
        moveTo(size.width * 0.35f, size.height * 0.18f)
        lineTo(size.width * 0.68f, size.height * 0.5f)
        lineTo(size.width * 0.35f, size.height * 0.82f)
    }
    drawPath(path, color, style = Stroke(size.minDimension * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Four-pointed spark, the mark used for Claude in the empty state. */
fun DrawScope.drawSpark(color: Color, center: Offset, radius: Float) {
    val waist = radius * 0.22f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + waist, center.y - waist, center.x + radius, center.y)
        quadraticTo(center.x + waist, center.y + waist, center.x, center.y + radius)
        quadraticTo(center.x - waist, center.y + waist, center.x - radius, center.y)
        quadraticTo(center.x - waist, center.y - waist, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}

@Composable
fun BrandMark(brand: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(brand), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawSpark(Color.White, Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension * 0.3f)
        }
    }
}

/** A page with a folded corner and text lines: marks a plan document. */
@Composable
fun DocumentGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(14.dp)) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(size.minDimension * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val page = Path().apply {
        moveTo(w * 0.22f, h * 0.08f)
        lineTo(w * 0.6f, h * 0.08f)
        lineTo(w * 0.8f, h * 0.28f)
        lineTo(w * 0.8f, h * 0.92f)
        lineTo(w * 0.22f, h * 0.92f)
        close()
        moveTo(w * 0.6f, h * 0.08f)
        lineTo(w * 0.6f, h * 0.28f)
        lineTo(w * 0.8f, h * 0.28f)
    }
    drawPath(page, color, style = stroke)
    listOf(0.48f, 0.62f, 0.76f).forEach { y ->
        drawLine(color, Offset(w * 0.34f, h * y), Offset(w * 0.68f, h * y), stroke.width, StrokeCap.Round)
    }
}

/** A folder with a tab: marks a folder in context chips and the file list. */
@Composable
fun FolderGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(14.dp)) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(size.minDimension * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val folder = Path().apply {
        moveTo(w * 0.1f, h * 0.22f)
        lineTo(w * 0.4f, h * 0.22f)
        lineTo(w * 0.5f, h * 0.34f)
        lineTo(w * 0.9f, h * 0.34f)
        lineTo(w * 0.9f, h * 0.82f)
        lineTo(w * 0.1f, h * 0.82f)
        close()
    }
    drawPath(folder, color, style = stroke)
}
