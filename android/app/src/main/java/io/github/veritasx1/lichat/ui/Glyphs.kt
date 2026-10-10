package io.github.veritasx1.lichat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** LiChat's symbols – the same drawings as LiMail (Li-Gestaltung), drawn in the manner of SF Symbols (regular weight, round ends) – no Apple artwork. */
enum class Glyph { Back, Chevron, Search, Compose, Filter, FilterOn, Inbox, Inboxes, Sent, Drafts, Trash, Archive, Junk, Folder, Flag, Star, Reply, ReplyAll, Forward, Move, Paperclip, More, Envelope, ArrowUp, Shield, Close, Check, Photo, Calendar, Clock, Handle, Person, Cart, Megaphone, Tag, Gear, BellOff, Lock, Seal, Share, Bell, Plus, Mic, Play, Pause, Stop, Document, Camera, QrCode, Keypad }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val s = size.minDimension / 24f
    val stroke = Stroke(width = 1.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun path(vararg points: Pair<Float, Float>, close: Boolean = false) = Path().apply {
        moveTo(points[0].first * s, points[0].second * s)
        points.drop(1).forEach { lineTo(it.first * s, it.second * s) }
        if (close) close()
    }
    fun line(vararg points: Pair<Float, Float>) = drawPath(path(*points), color, style = stroke)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(color, Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r * s), style = stroke)
    when (glyph) {
        Glyph.Back -> drawPath(path(15f to 4.5f, 7.5f to 12f, 15f to 19.5f), color, style = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Glyph.Chevron -> drawPath(path(9f to 5f, 16f to 12f, 9f to 19f), color, style = Stroke(2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Glyph.Search -> {
            drawCircle(color, 7f * s, Offset(10.5f * s, 10.5f * s), style = stroke)
            drawPath(path(15.6f to 15.6f, 21f to 21f), color, style = Stroke(2.4f * s, cap = StrokeCap.Round))
        }
        Glyph.Compose -> {  // square and pencil
            line(13f to 4.5f, 6f to 4.5f, 3.5f to 7f, 3.5f to 18f, 6f to 20.5f, 17f to 20.5f, 19.5f to 18f, 19.5f to 11f)
            line(10f to 14f, 10.5f to 11f, 18.5f to 3f, 21f to 5.5f, 13f to 13.5f, 10f to 14f)
        }
        Glyph.FilterOn -> {  // the filter in use, like SF line.3.horizontal.decrease.circle.fill: a full circle, white lines
            drawCircle(color, 10.5f * s, Offset(12f * s, 12f * s))
            val white = Color.White
            listOf((7f to 9f) to (17f to 9f), (8.8f to 12.5f) to (15.2f to 12.5f), (10.6f to 16f) to (13.4f to 16f)).forEach { (a, b) ->
                drawPath(path(a, b), white, style = stroke)
            }
        }
        Glyph.Filter -> {  // circle with three shrinking lines
            drawCircle(color, 9.5f * s, Offset(12f * s, 12f * s), style = stroke)
            line(7f to 9f, 17f to 9f); line(8.8f to 12.5f, 15.2f to 12.5f); line(10.6f to 16f, 13.4f to 16f)
        }
        Glyph.Inbox -> {
            line(3.5f to 13f, 6f to 5f, 18f to 5f, 20.5f to 13f, 20.5f to 19f, 3.5f to 19f, 3.5f to 13f)
            line(3.5f to 13f, 8.5f to 13f, 9.5f to 15.5f, 14.5f to 15.5f, 15.5f to 13f, 20.5f to 13f)
        }
        Glyph.Inboxes -> {  // two trays stacked
            line(5.5f to 8f, 7f to 3.5f, 17f to 3.5f, 18.5f to 8f)
            line(3.5f to 14f, 6f to 8f, 18f to 8f, 20.5f to 14f, 20.5f to 20f, 3.5f to 20f, 3.5f to 14f)
            line(3.5f to 14f, 8.5f to 14f, 9.5f to 16.5f, 14.5f to 16.5f, 15.5f to 14f, 20.5f to 14f)
        }
        Glyph.Sent -> {  // paper plane
            line(3f to 11f, 21f to 3.5f, 14f to 21f, 11f to 13.5f, 3f to 11f)
            line(11f to 13.5f, 21f to 3.5f)
        }
        Glyph.Drafts -> {  // a page with a folded corner
            line(14f to 3f, 6f to 3f, 6f to 21f, 18.5f to 21f, 18.5f to 7.5f, 14f to 3f, 14f to 7.5f, 18.5f to 7.5f)
            line(9f to 12f, 15.5f to 12f); line(9f to 15.5f, 15.5f to 15.5f)
        }
        Glyph.Trash -> {
            line(4f to 6.5f, 20f to 6.5f); line(9.5f to 6.5f, 9.5f to 4f, 14.5f to 4f, 14.5f to 6.5f)
            line(6f to 6.5f, 7f to 20.5f, 17f to 20.5f, 18f to 6.5f)
            line(10f to 10f, 10.3f to 17f); line(14f to 10f, 13.7f to 17f)
        }
        Glyph.Archive -> {
            box(3f, 4f, 18f, 5f, 1.5f)
            line(4.5f to 9f, 4.5f to 20f, 19.5f to 20f, 19.5f to 9f)
            line(9.5f to 13f, 14.5f to 13f)
        }
        Glyph.Junk -> {  // Werbung (Olaf 07.10., card 269e71be): an open envelope, the letter showing a „prohibited“ sign – as on Ubuntu
            line(5.4f to 11.1f, 5.4f to 2.4f, 18.6f to 2.4f, 18.6f to 11.1f)
            line(2.25f to 9.9f, 2.25f to 21.6f, 21.75f to 21.6f, 21.75f to 9.9f)
            line(2.4f to 10.8f, 12f to 16.8f, 21.6f to 10.8f)
            drawCircle(color, 3.5f * s, Offset(12f * s, 7.35f * s), style = Stroke(1.7f * s))
            drawLine(color, Offset(9.7f * s, 9.65f * s), Offset(14.3f * s, 5.05f * s), 1.7f * s, cap = StrokeCap.Round)
        }
        Glyph.Folder -> line(3f to 6f, 3f to 19.5f, 21f to 19.5f, 21f to 8f, 11.5f to 8f, 9.5f to 5f, 3f to 5f, 3f to 6f)
        Glyph.Flag -> {
            line(5f to 21f, 5f to 3.5f)
            drawPath(path(5f to 4f, 9f to 3f, 13f to 5f, 19f to 4f, 19f to 13f, 13f to 14f, 9f to 12f, 5f to 13f, close = true), color)
        }
        Glyph.Star -> {
            val points = (0 until 10).map { i ->
                val r = if (i % 2 == 0) 9.5f else 4f
                val a = Math.toRadians((-90 + i * 36).toDouble())
                (12f + r * Math.cos(a).toFloat()) to (12.5f + r * Math.sin(a).toFloat())
            }
            drawPath(path(*points.toTypedArray(), close = true), color)
        }
        Glyph.Shield -> line(12f to 3f, 19.5f to 6f, 19.5f to 11.5f, 17.5f to 16f, 12f to 21f, 6.5f to 16f, 4.5f to 11.5f, 4.5f to 6f, 12f to 3f)  // privacy
        Glyph.Photo -> {  // a picture: frame, sun, hill
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(3f * s, 5f * s), androidx.compose.ui.geometry.Size(18f * s, 14f * s),
                androidx.compose.ui.geometry.CornerRadius(2.5f * s), style = stroke)
            drawCircle(color, 1.8f * s, androidx.compose.ui.geometry.Offset(8.5f * s, 9.5f * s))
            line(4f to 17f, 9.5f to 12.5f, 13f to 15.5f, 16f to 12.5f, 20f to 16.5f)
        }
        Glyph.Calendar -> {
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(3.5f * s, 5f * s), androidx.compose.ui.geometry.Size(17f * s, 15f * s),
                androidx.compose.ui.geometry.CornerRadius(2.5f * s), style = stroke)
            line(3.5f to 9.5f, 20.5f to 9.5f); line(8f to 3f, 8f to 6.5f); line(16f to 3f, 16f to 6.5f)
        }
        // Kategorien like iOS (card 152271eb): own drawings in the manner of SF Symbols.
        Glyph.Camera -> {  // a camera body with its lens (89aae55d: scan a code)
            line(3f to 8.5f, 3f to 18.5f, 21f to 18.5f, 21f to 8.5f, 16.5f to 8.5f, 15f to 6f, 9f to 6f, 7.5f to 8.5f, 3f to 8.5f)
            drawCircle(color, 3.4f * s, Offset(12f * s, 13f * s), style = stroke)
        }
        Glyph.QrCode -> {  // three finder squares and a few modules
            box(3.5f, 3.5f, 6.5f, 6.5f, 1f); box(14f, 3.5f, 6.5f, 6.5f, 1f); box(3.5f, 14f, 6.5f, 6.5f, 1f)
            for ((x, y) in listOf(14f to 14f, 18f to 14f, 16f to 17f, 14f to 19.5f, 19f to 19.5f))
                drawRect(color, Offset(x * s, y * s), Size(1.8f * s, 1.8f * s))
        }
        Glyph.Keypad -> {  // nine dots: type a PIN
            for (row in 0..2) for (col in 0..2) drawCircle(color, 1.6f * s, Offset((6f + col * 6f) * s, (6f + row * 6f) * s))
        }
        Glyph.Person -> {
            drawCircle(color, 3.6f * s, Offset(12f * s, 8f * s), style = stroke)
            drawPath(Path().apply { moveTo(4.5f * s, 20.5f * s); cubicTo(5f * s, 14.5f * s, 19f * s, 14.5f * s, 19.5f * s, 20.5f * s) }, color, style = stroke)
        }
        Glyph.Cart -> {
            line(2.5f to 4f, 5f to 4f, 7.6f to 15f, 18f to 15f, 20.3f to 7.5f, 6f to 7.5f)
            drawCircle(color, 1.4f * s, Offset(9.2f * s, 19f * s)); drawCircle(color, 1.4f * s, Offset(16.6f * s, 19f * s))
        }
        Glyph.Megaphone -> {
            line(7f to 9f, 17.5f to 4.5f, 17.5f to 19.5f, 7f to 15f, 7f to 9f)
            box(3.2f, 9f, 3.8f, 6f, 1.2f)
            line(8f to 15.3f, 9.6f to 20.3f)
            line(20.3f to 10f, 20.3f to 14f)
        }
        Glyph.Tag -> {
            line(12.5f to 3.5f, 20.5f to 3.5f, 20.5f to 11.5f, 11.5f to 20.5f, 3.5f to 12.5f, 12.5f to 3.5f)
            drawCircle(color, 1.5f * s, Offset(16.5f * s, 7.5f * s))
        }
        Glyph.Clock -> {  // a clock face: Erinnern (Apple: clock)
            drawCircle(color, 8.5f * s, androidx.compose.ui.geometry.Offset(12f * s, 12f * s), style = stroke)
            line(12f to 7f, 12f to 12f, 15.5f to 14f)
        }
        Glyph.BellOff -> {  // a bell, struck through: muted
            line(6f to 16.5f, 6f to 11f); drawArc(color, 180f, 180f, false, androidx.compose.ui.geometry.Offset(6f * s, 5f * s),
                androidx.compose.ui.geometry.Size(12f * s, 12f * s), style = stroke)
            line(18f to 11f, 18f to 16.5f); line(4.5f to 16.5f, 19.5f to 16.5f); line(10.5f to 19f, 13.5f to 19f); line(4f to 4f, 20f to 20f)
        }
        Glyph.Bell -> {  // a bell: „Benachrichtige mich“
            line(6f to 16.5f, 6f to 11f); drawArc(color, 180f, 180f, false, androidx.compose.ui.geometry.Offset(6f * s, 5f * s),
                androidx.compose.ui.geometry.Size(12f * s, 12f * s), style = stroke)
            line(18f to 11f, 18f to 16.5f); line(4.5f to 16.5f, 19.5f to 16.5f); line(10.5f to 19f, 13.5f to 19f)
        }
        Glyph.Lock -> {  // a padlock: encrypted
            box(5f, 10.5f, 14f, 10f, 2.2f)
            drawArc(color, 180f, 180f, false, androidx.compose.ui.geometry.Offset(8f * s, 4f * s), androidx.compose.ui.geometry.Size(8f * s, 9f * s), style = stroke)
            line(8f to 8.5f, 8f to 10.5f); line(16f to 8.5f, 16f to 10.5f); line(12f to 14.5f, 12f to 16.5f)
        }
        Glyph.Seal -> {  // a rosette with a check mark: signed
            drawCircle(color, 6.8f * s, androidx.compose.ui.geometry.Offset(12f * s, 10f * s), style = stroke)
            line(9f to 10.2f, 11.2f to 12.4f, 15.2f to 8.2f)
            line(8.6f to 15.8f, 7.4f to 21f, 12f to 19f, 16.6f to 21f, 15.4f to 15.8f)
        }
        Glyph.Handle -> { line(5f to 8.5f, 19f to 8.5f); line(5f to 12f, 19f to 12f); line(5f to 15.5f, 19f to 15.5f) }  // drag to reorder
        Glyph.Gear -> {
            drawCircle(color, 3f * s, androidx.compose.ui.geometry.Offset(12f * s, 12f * s), style = stroke)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0); val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                line(12f + 5.5f * c to 12f + 5.5f * sn, 12f + 8.5f * c to 12f + 8.5f * sn)
            }
            drawCircle(color, 6f * s, androidx.compose.ui.geometry.Offset(12f * s, 12f * s), style = stroke)
        }
        Glyph.Check -> line(5f to 12.5f, 10f to 17.5f, 19f to 6.5f)
        Glyph.Close -> { line(6.5f to 6.5f, 17.5f to 17.5f); line(17.5f to 6.5f, 6.5f to 17.5f) }
        Glyph.Reply -> {
            line(9.5f to 5f, 3.5f to 11f, 9.5f to 17f)
            drawPath(Path().apply {
                moveTo(3.5f * s, 11f * s); lineTo(13f * s, 11f * s)
                cubicTo(18f * s, 11f * s, 20.5f * s, 14f * s, 20.5f * s, 19f * s)
            }, color, style = stroke)
        }
        Glyph.ReplyAll -> {  // two arrow heads: answer everyone
            line(12f to 5f, 6f to 11f, 12f to 17f)
            line(7.5f to 5f, 1.5f to 11f, 7.5f to 17f)
            drawPath(Path().apply {
                moveTo(6f * s, 11f * s); lineTo(14f * s, 11f * s)
                cubicTo(19f * s, 11f * s, 21.5f * s, 14f * s, 21.5f * s, 19f * s)
            }, color, style = stroke)
        }
        Glyph.Forward -> {  // the reply arrow, pointing on
            line(14.5f to 5f, 20.5f to 11f, 14.5f to 17f)
            drawPath(Path().apply {
                moveTo(20.5f * s, 11f * s); lineTo(11f * s, 11f * s)
                cubicTo(6f * s, 11f * s, 3.5f * s, 14f * s, 3.5f * s, 19f * s)
            }, color, style = stroke)
        }
        Glyph.Move -> {  // folder with an arrow
            line(3f to 6f, 3f to 19.5f, 21f to 19.5f, 21f to 8f, 11.5f to 8f, 9.5f to 5f, 3f to 5f, 3f to 6f)
            line(8f to 14f, 15.5f to 14f); line(12.5f to 11f, 15.5f to 14f, 12.5f to 17f)
        }
        Glyph.Paperclip -> drawPath(Path().apply {
            moveTo(15.5f * s, 7f * s); lineTo(8.5f * s, 14f * s)
            cubicTo(7f * s, 15.5f * s, 8.5f * s, 18f * s, 10.5f * s, 16.5f * s); lineTo(18f * s, 9f * s)
            cubicTo(21f * s, 6f * s, 17f * s, 1.5f * s, 14f * s, 4.5f * s); lineTo(6f * s, 12.5f * s)
            cubicTo(1.5f * s, 17f * s, 7.5f * s, 23f * s, 12f * s, 18.5f * s); lineTo(17.5f * s, 13f * s)
        }, color, style = stroke)
        Glyph.More -> {
            drawCircle(color, 9.5f * s, Offset(12f * s, 12f * s), style = stroke)
            for (x in listOf(7.8f, 12f, 16.2f)) drawCircle(color, 1.3f * s, Offset(x * s, 12f * s))
        }
        Glyph.Share -> {  // Apple's share: an open box, an arrow out of it
            line(8.5f to 9.5f, 6f to 9.5f, 6f to 21f, 18f to 21f, 18f to 9.5f, 15.5f to 9.5f)
            line(12f to 14f, 12f to 2.5f); line(8f to 6.5f, 12f to 2.5f, 16f to 6.5f)
        }
        Glyph.Plus -> { line(12f to 5f, 12f to 19f); line(5f to 12f, 19f to 12f) }
        Glyph.Mic -> {  // microphone: capsule, cup, stand
            box(9f, 3f, 6f, 11f, 3f)
            line(5.5f to 11f, 5.5f to 12f); line(18.5f to 11f, 18.5f to 12f)
            drawArc(color, 0f, 180f, false, Offset(5.5f * s, 5.5f * s), Size(13f * s, 13f * s), style = stroke)
            line(12f to 18.5f, 12f to 21f)
        }
        Glyph.Play -> drawPath(path(7.5f to 4.5f, 19f to 12f, 7.5f to 19.5f, close = true), color)
        Glyph.Pause -> { drawRoundRect(color, Offset(6.5f * s, 5f * s), Size(4f * s, 14f * s), CornerRadius(1f * s))
                         drawRoundRect(color, Offset(13.5f * s, 5f * s), Size(4f * s, 14f * s), CornerRadius(1f * s)) }
        Glyph.Stop -> drawRoundRect(color, Offset(6f * s, 6f * s), Size(12f * s, 12f * s), CornerRadius(2.5f * s))
        Glyph.Document -> {  // a page with a folded corner
            line(14f to 3f, 6.5f to 3f, 5f to 4.5f, 5f to 19.5f, 6.5f to 21f, 17.5f to 21f, 19f to 19.5f, 19f to 8f, 14f to 3f)
            line(14f to 3f, 14f to 8f, 19f to 8f)
        }
        Glyph.ArrowUp -> {  // the send arrow
            val bold = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(path(12f to 19f, 12f to 5.5f), color, style = bold)
            drawPath(path(6f to 11f, 12f to 5f, 18f to 11f), color, style = bold)
        }
        Glyph.Envelope -> {
            box(2.5f, 5f, 19f, 14f, 2.5f)
            line(3.5f to 6.5f, 12f to 13f, 20.5f to 6.5f)
        }
    }
}
