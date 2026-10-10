package io.github.veritasx1.lichat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import io.github.veritasx1.lichat.core.SCREEN_EFFECTS
import io.github.veritasx1.lichat.core.TEXT_EFFECTS
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

/** f012–f014, like Messages: a message's effect plays once when it is new to me (arrives, or I send it) and again on
 *  „Wiedergeben“; text effects run in a loop like on the iPhone. With „Animationen entfernen“ (Android: animation scale 0)
 *  nothing moves – the message just stands there. */
object Effects {
    /** mids whose effect was played already (in this session). */
    val played = androidx.compose.runtime.mutableStateListOf<String>()
    /** The bubble effect playing now: (mid, a counter so a replay starts again). */
    var bubble by mutableStateOf<Pair<String, Int>?>(null)
    /** The screen effect playing now: (kind, counter). */
    var screen by mutableStateOf<Pair<String, Int>?>(null)
    private var count = 0

    fun play(mid: String, kind: String) {
        if (mid !in played) played.add(mid)
        count++
        if (kind in SCREEN_EFFECTS) screen = kind to count else if (kind !in TEXT_EFFECTS) bubble = mid to count
    }
}

@Composable
fun motionAllowed(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f) > 0f
    }
}

/** Knall (thrown in, a shake), Laut (big, then back), Sanft (small, slowly grown) – while [playing] (mid, counter). */
fun Modifier.bubbleEffect(kind: String?, mid: String): Modifier = composed {
    val playing = Effects.bubble?.takeIf { it.first == mid }
    val scale = remember { Animatable(1f) }
    val drop = remember { Animatable(0f) }
    val allowed = motionAllowed()
    LaunchedEffect(playing) {
        if (playing == null || !allowed) return@LaunchedEffect
        when (kind) {
            "knall" -> { scale.snapTo(2.6f); drop.snapTo(-60f)
                kotlinx.coroutines.coroutineScope {
                    launch { scale.animateTo(1f, tween(260, easing = LinearEasing)) }
                    launch { drop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = 900f)) }
                } }
            "laut" -> { scale.animateTo(1.9f, tween(220, easing = FastOutSlowInEasing)); kotlinx.coroutines.delay(350)
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
            "sanft" -> { scale.snapTo(0.55f); kotlinx.coroutines.delay(250); scale.animateTo(1f, tween(1600, easing = FastOutSlowInEasing)) }
        }
        if (Effects.bubble == playing) Effects.bubble = null
    }
    graphicsLayer { scaleX = scale.value; scaleY = scale.value; translationY = drop.value * density }
}

/** Groß, Klein, Schütteln, Nicken, Zittern – the text of the message, in a loop. */
fun Modifier.textEffect(kind: String?): Modifier = composed {
    if (kind !in TEXT_EFFECTS || !motionAllowed()) return@composed this
    val loop = rememberInfiniteTransition(label = "Texteffekt")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(if (kind == "zittern") 240 else 1400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    graphicsLayer {
        val wave = sin(t * 2 * PI).toFloat()
        when (kind) {
            "gross" -> { scaleX = 1f + 0.18f * (wave + 1) / 2; scaleY = scaleX }
            "klein" -> { scaleX = 1f - 0.22f * (wave + 1) / 2; scaleY = scaleX }
            "schuetteln" -> translationX = 7f * density * sin(t * 6 * PI).toFloat() * (if (t < 0.5f) 1f else 0f)
            "nicken" -> translationY = 5f * density * wave
            "zittern" -> { translationX = (Random.nextFloat() - 0.5f) * 3f * density; translationY = (Random.nextFloat() - 0.5f) * 3f * density }
        }
    }
}

private data class Bit(val x: Float, val y: Float, val vx: Float, val vy: Float, val color: Color, val size: Float, val spin: Float, val delay: Float)

/** Konfetti, Ballons, Liebe, Feuerwerk over the conversation for ~3 s, then gone. */
@Composable
fun ScreenEffect(kind: String, key: Int, onDone: () -> Unit) {
    val allowed = motionAllowed()
    val time = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (allowed) time.animateTo(1f, tween(3200, easing = LinearEasing))
        onDone()
    }
    if (!allowed) return
    val colors = listOf(Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFAF52DE), Color(0xFFFF2D55))
    val bits = remember(key, kind) {
        val r = Random(key)
        when (kind) {
            "konfetti" -> List(140) { Bit(r.nextFloat(), -0.1f - r.nextFloat() * 0.4f, (r.nextFloat() - 0.5f) * 0.15f, 0.45f + r.nextFloat() * 0.5f, colors[r.nextInt(colors.size)], 5f + r.nextFloat() * 6f, r.nextFloat() * 720f, 0f) }
            "ballons" -> List(16) { Bit(r.nextFloat(), 1.15f + r.nextFloat() * 0.3f, (r.nextFloat() - 0.5f) * 0.08f, -(0.45f + r.nextFloat() * 0.35f), colors[r.nextInt(colors.size)], 26f + r.nextFloat() * 14f, 0f, r.nextFloat() * 0.25f) }
            "liebe" -> List(26) { Bit(0.5f + (r.nextFloat() - 0.5f) * 0.5f, 0.75f, (r.nextFloat() - 0.5f) * 0.35f, -(0.25f + r.nextFloat() * 0.45f), Color(0xFFFF2D55), 12f + r.nextFloat() * 16f, 0f, r.nextFloat() * 0.4f) }
            else -> List(4) { burst -> Bit(0.2f + r.nextFloat() * 0.6f, 0.15f + r.nextFloat() * 0.35f, 0f, 0f, colors[r.nextInt(colors.size)], 0f, 0f, burst * 0.18f) }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.value
        val w = size.width; val h = size.height
        for (b in bits) {
            val life = ((t - b.delay) / (1f - b.delay)).coerceIn(0f, 1f)
            if (life <= 0f) continue
            val fade = if (life > 0.8f) (1f - life) / 0.2f else 1f
            when (kind) {
                "konfetti" -> {
                    val p = Offset((b.x + b.vx * life * 3) * w, (b.y + b.vy * life * 2.2f) * h)
                    rotate(b.spin * life, p) { drawRect(b.color.copy(alpha = fade), p - Offset(b.size, b.size / 2) * density, Size(b.size * 2, b.size) * density) }
                }
                "ballons" -> {
                    val p = Offset((b.x + b.vx * life + 0.02f * sin(life * 10 + b.x * 6)) * w, (b.y + b.vy * life * 2.4f) * h)
                    val r = b.size * density
                    drawOval(b.color.copy(alpha = 0.9f * fade), p - Offset(r * 0.8f, r), Size(r * 1.6f, r * 2f))
                    drawLine(Color.Gray.copy(alpha = 0.6f * fade), p + Offset(0f, r), p + Offset(0f, r * 3.2f), 1.2f * density)
                }
                "liebe" -> {
                    val p = Offset((b.x + b.vx * life) * w, (b.y + b.vy * life) * h)
                    val s = b.size * density * (0.6f + 0.6f * life)
                    drawPath(heart(p, s), b.color.copy(alpha = fade))
                }
                else -> for (k in 0 until 26) {           // feuerwerk: each burst a ring of sparks falling a little
                    val angle = 2 * PI * k / 26
                    val reach = 0.22f * w * (1 - (1 - life) * (1 - life))
                    val p = Offset(b.x * w + (cos(angle) * reach).toFloat(), b.y * h + (sin(angle) * reach).toFloat() + 0.08f * h * life * life)
                    drawCircle(b.color.copy(alpha = fade), 2.6f * density, p)
                }
            }
        }
    }
}

private fun heart(c: Offset, s: Float) = Path().apply {
    moveTo(c.x, c.y + s * 0.35f)
    cubicTo(c.x - s * 1.1f, c.y - s * 0.3f, c.x - s * 0.45f, c.y - s * 0.95f, c.x, c.y - s * 0.4f)
    cubicTo(c.x + s * 0.45f, c.y - s * 0.95f, c.x + s * 1.1f, c.y - s * 0.3f, c.x, c.y + s * 0.35f)
    close()
}
