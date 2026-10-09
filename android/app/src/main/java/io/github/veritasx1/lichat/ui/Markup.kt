package io.github.veritasx1.lichat.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.veritasx1.lichat.core.Pictures
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** „Markieren“ before a photo leaves (4fb76dfd), like Apple's Markup: crop, rotate, pen, marker, text, shapes and arrows,
 *  emoji – undo for everything. Only here on the phone, no AI; the result is a new picture, so no metadata comes along.
 *  Everything is kept in the picture's own pixels, so the screen and the sent picture match exactly. */
sealed class Mark {
    data class Stroke(val points: List<Offset>, val color: Int, val width: Float, val marker: Boolean) : Mark()
    data class Shape(val kind: Tool, val a: Offset, val b: Offset, val color: Int, val width: Float) : Mark()
    data class Label(val text: String, val at: Offset, val color: Int, val size: Float) : Mark()
    data class Sticker(val emoji: String, val at: Offset, val size: Float) : Mark()
}

enum class Tool(val title: String) {
    Pen("Stift"), Marker("Marker"), Text("Text"), Rect("▢"), Oval("◯"), Arrow("↗"), Emoji("😀"), Crop("Zuschneiden")
}

val MARKUP_COLORS = listOf(0xFF000000, 0xFFFFFFFF, 0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF007AFF).map { it.toInt() }

/** Draws the marks onto an Android canvas: picture pixels × [scale], moved by ([dx], [dy]) – for the screen and the file. */
fun paintMarks(canvas: android.graphics.Canvas, marks: List<Mark>, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f) {
    fun x(p: Offset) = dx + p.x * scale
    fun y(p: Offset) = dy + p.y * scale
    for (mark in marks) when (mark) {
        is Mark.Stroke -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = mark.color; alpha = if (mark.marker) 90 else 255; strokeWidth = mark.width * scale
                if (mark.marker) strokeCap = Paint.Cap.SQUARE
            }
            val path = Path()
            mark.points.firstOrNull()?.let { path.moveTo(x(it), y(it)) }
            mark.points.drop(1).forEach { path.lineTo(x(it), y(it)) }
            if (mark.points.size == 1) path.lineTo(x(mark.points[0]) + 0.1f, y(mark.points[0]))
            canvas.drawPath(path, paint)
        }
        is Mark.Shape -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = mark.color; strokeWidth = mark.width * scale
            }
            val box = RectF(minOf(x(mark.a), x(mark.b)), minOf(y(mark.a), y(mark.b)), maxOf(x(mark.a), x(mark.b)), maxOf(y(mark.a), y(mark.b)))
            when (mark.kind) {
                Tool.Rect -> canvas.drawRect(box, paint)
                Tool.Oval -> canvas.drawOval(box, paint)
                else -> {
                    canvas.drawLine(x(mark.a), y(mark.a), x(mark.b), y(mark.b), paint)
                    val angle = atan2(y(mark.b) - y(mark.a), x(mark.b) - x(mark.a))
                    val head = mark.width * scale * 4
                    for (side in listOf(-0.5f, 0.5f)) canvas.drawLine(x(mark.b), y(mark.b),
                        x(mark.b) - head * cos(angle + side), y(mark.b) - head * sin(angle + side), paint)
                }
            }
        }
        is Mark.Label -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mark.color; textSize = mark.size * scale; typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                setShadowLayer(mark.size * scale / 12, 0f, 0f, if (mark.color == MARKUP_COLORS[1]) 0x99000000.toInt() else 0x66FFFFFF)
            }
            canvas.drawText(mark.text, x(mark.at), y(mark.at), paint)
        }
        is Mark.Sticker -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = mark.size * scale; textAlign = Paint.Align.CENTER }
            canvas.drawText(mark.emoji, x(mark.at), y(mark.at) + mark.size * scale / 3, paint)
        }
    }
}

/** The picture with its marks, cropped – ready to be sent as a photo (no metadata: a new bitmap). */
fun flatten(picture: Bitmap, marks: List<Mark>, crop: RectF? = null): Bitmap {
    val out = picture.copy(Bitmap.Config.ARGB_8888, true)
    paintMarks(android.graphics.Canvas(out), marks)
    val area = crop ?: return out
    val left = area.left.toInt().coerceIn(0, out.width - 1); val top = area.top.toInt().coerceIn(0, out.height - 1)
    return Bitmap.createBitmap(out, left, top, (area.width().toInt()).coerceIn(1, out.width - left), (area.height().toInt()).coerceIn(1, out.height - top))
}

fun rotated(picture: Bitmap): Bitmap =
    Bitmap.createBitmap(picture, 0, 0, picture.width, picture.height, android.graphics.Matrix().apply { postRotate(90f) }, true)

@Composable
fun MarkupEditor(bytes: ByteArray, onCancel: () -> Unit, onDone: (ByteArray) -> Unit) {
    val colors = palette()
    var picture by remember(bytes) { mutableStateOf(runCatching { Pictures.forMarkup(bytes) }.getOrNull()) }
    val marks = remember(bytes) { mutableStateListOf<Mark>() }
    // Undo: a mark is taken back; rotating and cropping keep the picture before (at most 8 of them, 16 MB each).
    val undo = remember(bytes) { mutableStateListOf<Pair<Bitmap, List<Mark>>?>() }
    var tool by remember { mutableStateOf(Tool.Pen) }
    var color by remember { mutableIntStateOf(MARKUP_COLORS[2]) }
    var drawing by remember { mutableStateOf<Mark?>(null) }
    var crop by remember(bytes) { mutableStateOf<RectF?>(null) }
    var typingAt by remember { mutableStateOf<Offset?>(null) }
    var typed by remember { mutableStateOf("") }
    var stickerAt by remember { mutableStateOf<Offset?>(null) }
    var moving by remember { mutableStateOf<Int?>(null) }
    androidx.activity.compose.BackHandler(onBack = onCancel)
    val image = picture ?: run {
        androidx.compose.runtime.LaunchedEffect(Unit) { onCancel() }       // not a picture LiChat can read
        return
    }
    val unit = minOf(image.width, image.height) / 160f              // line width: the same look on any picture size

    // Where the picture sits in the area (scale, dx, dy) – worked out from the area's size, so a touch before the first
    // frame is drawn lands in the right place too.
    fun frameIn(width: Float, height: Float): Triple<Float, Float, Float> {
        val scale = minOf(width / image.width, height / image.height)
        return Triple(scale, (width - image.width * scale) / 2, (height - image.height * scale) / 2)
    }
    fun push(mark: Mark) { marks.add(mark); undo.add(null) }
    fun applyCrop() {
        val area = crop ?: return
        crop = null
        if (area.width() >= image.width - 1 && area.height() >= image.height - 1) return
        undo.add(image to marks.toList()); while (undo.count { it != null } > 8) undo.removeAt(undo.indexOfFirst { it != null })
        picture = flatten(image, marks, area); marks.clear()
    }
    fun choose(next: Tool) {
        if (tool == Tool.Crop && next != Tool.Crop) applyCrop()
        tool = next
        if (next == Tool.Crop) crop = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
    }

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton("Abbrechen", Color.White, onClick = onCancel)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton("Rückgängig", if (undo.isNotEmpty()) Color.White else Color(0x66FFFFFF)) {
                    val last = undo.removeLastOrNull() ?: return@TextButton
                    if (last == null) marks.removeLastOrNull() else { picture = last.first; marks.clear(); marks.addAll(last.second) }
                    crop = null; if (tool == Tool.Crop) tool = Tool.Pen
                }
            }
            TextButton("Fertig", colors.blue, 600) {
                val area = if (tool == Tool.Crop) crop else null
                onDone(Pictures.encode(flatten(image, marks, area)))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Foto zum Markieren" }
                .pointerInput(tool, image) {
                    detectTapGestures { at ->
                        val frame = frameIn(size.width.toFloat(), size.height.toFloat())
                        val p = Offset((at.x - frame.second) / frame.first, (at.y - frame.third) / frame.first)
                        when (tool) {
                            Tool.Text -> { typingAt = p; typed = "" }
                            Tool.Emoji -> stickerAt = p
                            else -> {}
                        }
                    }
                }
                .pointerInput(tool, image, color) {
                    val frame = frameIn(size.width.toFloat(), size.height.toFloat())
                    fun toPicture(p: Offset) = Offset((p.x - frame.second) / frame.first, (p.y - frame.third) / frame.first)
                    detectDragGestures(
                        onDragStart = { at ->
                            val p = toPicture(at)
                            moving = null
                            when (tool) {
                                Tool.Pen, Tool.Marker -> drawing = Mark.Stroke(listOf(p), color, if (tool == Tool.Marker) unit * 6 else unit * 1.6f, tool == Tool.Marker)
                                Tool.Rect, Tool.Oval, Tool.Arrow -> drawing = Mark.Shape(tool, p, p, color, unit)
                                Tool.Text, Tool.Emoji -> moving = marks.indexOfLast { m ->
                                    (m is Mark.Label && hypot(m.at.x - p.x, m.at.y - p.y) < m.size * 1.5f) ||
                                        (m is Mark.Sticker && hypot(m.at.x - p.x, m.at.y - p.y) < m.size)
                                }.takeIf { it >= 0 }
                                Tool.Crop -> {}
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val p = toPicture(change.position)
                            val step = Offset(amount.x / frame.first, amount.y / frame.first)
                            when (val d = drawing) {
                                is Mark.Stroke -> drawing = d.copy(points = d.points + p)
                                is Mark.Shape -> drawing = d.copy(b = p)
                                else -> {}
                            }
                            moving?.let { i ->
                                marks[i] = when (val m = marks[i]) {
                                    is Mark.Label -> m.copy(at = m.at + step)
                                    is Mark.Sticker -> m.copy(at = m.at + step)
                                    else -> m
                                }
                            }
                            if (tool == Tool.Crop) crop?.let { area ->
                                // the nearest corner follows the finger; the frame never leaves the picture
                                val corners = listOf(area.left to area.top, area.right to area.top, area.left to area.bottom, area.right to area.bottom)
                                val near = corners.indices.minBy { hypot(corners[it].first - p.x, corners[it].second - p.y) }
                                val next = RectF(area)
                                val minimum = image.width / 10f
                                when (near) {
                                    0 -> { next.left = p.x.coerceIn(0f, area.right - minimum); next.top = p.y.coerceIn(0f, area.bottom - minimum) }
                                    1 -> { next.right = p.x.coerceIn(area.left + minimum, image.width.toFloat()); next.top = p.y.coerceIn(0f, area.bottom - minimum) }
                                    2 -> { next.left = p.x.coerceIn(0f, area.right - minimum); next.bottom = p.y.coerceIn(area.top + minimum, image.height.toFloat()) }
                                    else -> { next.right = p.x.coerceIn(area.left + minimum, image.width.toFloat()); next.bottom = p.y.coerceIn(area.top + minimum, image.height.toFloat()) }
                                }
                                crop = next
                            }
                        },
                        onDragEnd = { drawing?.let { push(it) }; drawing = null; moving = null },
                        onDragCancel = { drawing = null; moving = null })
                }) {
                val (scale, dx, dy) = frameIn(size.width, size.height)
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.drawBitmap(image, null, RectF(dx, dy, dx + image.width * scale, dy + image.height * scale), Paint(Paint.FILTER_BITMAP_FLAG))
                    paintMarks(native, marks + listOfNotNull(drawing), scale, dx, dy)
                    crop?.let { area ->
                        val shade = Paint().apply { this.color = 0x99000000.toInt() }
                        val l = dx + area.left * scale; val t = dy + area.top * scale; val r = dx + area.right * scale; val b = dy + area.bottom * scale
                        native.drawRect(dx, dy, dx + image.width * scale, t, shade)
                        native.drawRect(dx, b, dx + image.width * scale, dy + image.height * scale, shade)
                        native.drawRect(dx, t, l, b, shade)
                        native.drawRect(r, t, dx + image.width * scale, b, shade)
                        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.dp.toPx() }
                        native.drawRect(l, t, r, b, edge)
                        val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 5.dp.toPx() }
                        val c = 18.dp.toPx()
                        for ((x, y, sx, sy) in listOf(listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(l, b, 1f, -1f), listOf(r, b, -1f, -1f))) {
                            native.drawLine(x, y, x + c * sx, y, corner); native.drawLine(x, y, x, y + c * sy, corner)
                        }
                    }
                }
            }
        }
        // Colours (not for emoji and crop), then the tools
        if (tool != Tool.Emoji && tool != Tool.Crop) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
            MARKUP_COLORS.forEach { c ->
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(if (c == color) 3.dp else 1.dp, if (c == color) colors.blue else Color(0x66FFFFFF), CircleShape)
                    .clickable { color = c }.semantics { contentDescription = "Farbe" })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool.entries.forEach { t ->
                BasicText(t.title, style = style(15f, 600, if (t == tool) Color.Black else Color.White), modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (t == tool) Color.White else Color(0x33FFFFFF)).clickable { choose(t) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            BasicText("Drehen", style = style(15f, 600, Color.White), modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x33FFFFFF))
                .clickable {
                    applyCrop()
                    undo.add(image to marks.toList())
                    picture = rotated(flatten(image, marks)); marks.clear()
                    if (tool == Tool.Crop) tool = Tool.Pen
                }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
    typingAt?.let { at ->
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp).clip(RoundedCornerShape(14.dp)).background(colors.cell).padding(16.dp)) {
                BasicTextField(typed, { typed = it }, singleLine = true, textStyle = style(20f, 600, colors.label), cursorBrush = SolidColor(colors.blue),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Text" },
                    decorationBox = { inner -> Box { if (typed.isEmpty()) BasicText("Text", style = style(20f, 400, colors.tertiary)); inner() } })
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton("Abbrechen", colors.blue) { typingAt = null }
                    TextButton("Hinzufügen", colors.blue, 600) {
                        if (typed.isNotBlank()) push(Mark.Label(typed.trim(), at, color, unit * 9))
                        typingAt = null
                    }
                }
            }
        }
    }
    stickerAt?.let { at ->
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { stickerAt = null }, contentAlignment = Alignment.BottomCenter) {
            EmojiPicker(onPick = { emoji -> push(Mark.Sticker(emoji, at, unit * 22)); stickerAt = null }, modifier = Modifier.fillMaxWidth().height(320.dp))
        }
    }
}
