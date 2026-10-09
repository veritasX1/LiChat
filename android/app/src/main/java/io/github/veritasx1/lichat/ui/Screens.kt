package io.github.veritasx1.lichat.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.veritasx1.lichat.core.Account
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* The screens, like Messages on iOS 18: the list with the large title „Nachrichten“, a conversation with blue and grey
 * bubbles, „Neue Nachricht“ with „An:“, the details with members and safety code, the settings, the first sign-in. */

// ---------------------------------------------------------------------------------------------- pieces

fun initials(name: String) = name.split('.', '_', '-', ' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }
    .uppercase().ifEmpty { "?" }

/** A round picture: the person's profile picture (end-to-end) or the initials on grey, like Messages. */
@Composable
fun Avatar(name: String, picture: ByteArray?, size: Dp) {
    val colors = palette()
    val bitmap = remember(picture) { picture?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(Modifier.size(size).clip(CircleShape).background(Brush.verticalGradient(listOf(colors.avatarTop, colors.avatarBottom))),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else BasicText(initials(name), style = style(size.value * 0.4f, 600, Color.White))
    }
}

/** The app's own icon (the white speech bubble on blue), rounded like an iOS app icon. */
@Composable
fun AppIcon(size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.225f))
        .background(Brush.verticalGradient(listOf(Color(0xFF5AC8FA), Color(0xFF0A7AFF))))) {
        Image(androidx.compose.ui.res.painterResource(io.github.veritasx1.lichat.R.drawable.ic_launcher_foreground), contentDescription = "LiChat",
            modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun TextButton(text: String, color: Color, weight: Int = 400, enabled: Boolean = true, onClick: () -> Unit) {
    BasicText(text, style = style(17f, weight, if (enabled) color else palette().tertiary),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp))
}

@Composable
fun IconButton(glyph: Glyph, label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { GlyphIcon(glyph, color, 24.dp) }
}

@Composable
fun LargeTitle(text: String) {
    BasicText(text, style = style(34f, 700, palette().label), maxLines = 1,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp).semantics { heading() })
}

fun whenShort(at: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = at * 1000 }
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    val days = ((today.timeInMillis - then.timeInMillis) / 86_400_000L).toInt() + if (then.before(today)) 1 else 0
    return when {
        days <= 0 -> SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(at * 1000))
        days == 1 -> "Gestern"
        days < 7 -> SimpleDateFormat("EEEE", Locale.GERMANY).format(Date(at * 1000))
        else -> SimpleDateFormat("dd.MM.yy", Locale.GERMANY).format(Date(at * 1000))
    }
}

private fun messages(chat: JSONObject) = chat.getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

// ---------------------------------------------------------------------------------------------- list

@Composable
fun ChatList(account: Account, version: Int, seen: Map<String, Int>, problem: String?, unrestricted: Boolean,
             onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onUnrestrict: () -> Unit,
             notifying: Boolean = true, onNotifications: () -> Unit = {}) {
    val colors = palette()
    var query by remember { mutableStateOf("") }
    val all = remember(version) {
        synchronized(account.lock) {
            account.chats.keys().asSequence().map { cid ->
                val chat = account.chat(cid)
                val list = messages(chat)
                Triple(cid, list.lastOrNull(), list.size)
            }.sortedByDescending { it.second?.optLong("at") ?: 0 }.toList()
        }
    }
    // Search: by name and in the texts, on the phone only (nothing is asked of the server).
    val rows = if (query.isBlank()) all else all.filter { (cid, _, _) ->
        val q = query.trim().lowercase()
        account.title(cid).lowercase().contains(q) || messages(account.chat(cid)).any { io.github.veritasx1.lichat.core.describe(it).lowercase().contains(q) }
    }
    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton("Einstellungen", colors.blue, onClick = onSettings)
            Spacer(Modifier.weight(1f))
            IconButton(Glyph.Compose, "Neue Nachricht", colors.blue, onNew)
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                LargeTitle("Nachrichten")
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(colors.fill).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Search, colors.secondary, 17.dp)
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Suchen" },
                        decorationBox = { inner -> Box { if (query.isEmpty()) BasicText("Suchen", style = style(17f, 400, colors.secondary)); inner() } })
                }
                // Honest about late messages (Michelle Z6): without the exemption Android pauses the connection in Doze.
                if (!notifying) BasicText("Mitteilungen sind aus – neue Nachrichten siehst du nur, wenn du LiChat öffnest. Tippe hier, um sie zu erlauben.",
                    style = style(13f, 400, colors.blue), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onNotifications))
                if (!unrestricted) BasicText("Nachrichten können sich verspäten, solange Android LiChat beim Akku einschränkt. Tippe hier, um das zu ändern.",
                    style = style(13f, 400, colors.blue), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onUnrestrict))
                if (problem != null) BasicText(problem, style = style(13f, 400, colors.red), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (rows.isEmpty()) item {
                BasicText("Noch keine Unterhaltungen", style = style(17f, 600, colors.secondary).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(top = 120.dp))
            }
            items(rows, key = { it.first }) { (cid, last, count) ->
                val title = account.title(cid)
                val others = account.others(cid)
                Row(Modifier.fillMaxWidth().clickable { onOpen(cid) }.padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (count > (seen[cid] ?: 0)) colors.blue else Color.Transparent))
                    Spacer(Modifier.width(6.dp))
                    Avatar(title, if (others.size == 1) account.picture(others[0]) else account.groupPicture(cid), 44.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(title, style = style(17f, 600, colors.label), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            last?.let { BasicText(whenShort(it.optLong("at")), style = style(15f, 400, colors.secondary)) }
                            GlyphIcon(Glyph.Chevron, colors.tertiary, 14.dp, Modifier.padding(start = 4.dp))
                        }
                        val preview = when {
                            last == null -> "Noch keine Nachrichten"
                            last.optBoolean("system") -> last.optString("text")
                            last.optString("from") == account.username -> "Du: " + io.github.veritasx1.lichat.core.describe(last)
                            else -> io.github.veritasx1.lichat.core.describe(last)
                        }
                        BasicText(preview, style = style(15f, 400, colors.secondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.padding(start = 78.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- conversation

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(account: Account, cid: String, version: Int, problem: String?, onBack: () -> Unit, onInfo: () -> Unit,
               onSend: (String, String?) -> Unit, actions: AttachmentActions = AttachmentActions(),
               onReact: (String, String) -> Unit = { _, _ -> }) {
    val colors = palette()
    // Olaf 09.10.: swipe a bubble to the right to answer it, press it long for a reaction (card a7d9d987).
    var replyTo by remember(cid) { mutableStateOf<JSONObject?>(null) }
    var menuFor by remember(cid) { mutableStateOf<JSONObject?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val chat = remember(version, cid) { synchronized(account.lock) { JSONObject(account.chat(cid).toString()) } }
    val title = account.title(cid)
    val others = account.others(cid)
    val list = messages(chat)
    val listState = rememberLazyListState()
    LaunchedEffect(list.size) { if (list.isNotEmpty()) listState.scrollToItem(list.size - 1) }
    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.statusBars)) {
        // Top: back, the person's picture and name in the middle – a tap shows the details (iOS 18 Messages).
        Box(Modifier.fillMaxWidth().background(colors.bar)) {
            Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                .semantics { contentDescription = "Zurück" }.padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Back, colors.blue, 24.dp)
            }
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).clickable(onClick = onInfo).padding(vertical = 6.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(title, if (others.size == 1) account.picture(others[0]) else account.groupPicture(cid), 36.dp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    BasicText(title, style = style(11f, 400, colors.label), maxLines = 1)
                    GlyphIcon(Glyph.Chevron, colors.tertiary, 9.dp, Modifier.padding(start = 2.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
            item { Spacer(Modifier.height(8.dp)) }
            items(list.size) { i ->
                val m = list[i]
                val previous = list.getOrNull(i - 1)
                if (previous == null || m.optLong("at") - previous.optLong("at") > 15 * 60) {
                    BasicText(whenShort(m.optLong("at")).let { if (':' in it) "Heute $it" else it }, style = style(11f, 600, colors.secondary).copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp))
                }
                if (m.optBoolean("system")) {
                    BasicText(m.optString("text"), style = style(11f, 400, colors.secondary).copy(textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    return@items
                }
                val mine = m.optString("from") == account.username
                if (!mine && others.size > 1 && previous?.optString("from") != m.optString("from"))
                    BasicText(m.optString("from"), style = style(11f, 400, colors.secondary), modifier = Modifier.padding(start = 12.dp, top = 6.dp))
                m.optJSONObject("antwort")?.let { quoted ->
                    // The message this answers, small above the bubble; a tap jumps to it.
                    val target = list.indexOfFirst { it.optString("mid") == quoted.optString("mid") }
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        BasicText("↩ ${if (quoted.optString("from") == account.username) "Du" else quoted.optString("from")}: ${quoted.optString("text")}",
                            style = style(13f, 400, colors.secondary), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, colors.fill, RoundedCornerShape(12.dp))
                                .clickable(enabled = target >= 0) { scope.launch { listState.animateScrollToItem(target + 1) } }
                                .padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
                val mid = m.optString("mid")
                var drag by remember(mid) { mutableStateOf(0f) }
                val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
                val gestures = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(drag.toInt(), 0) }
                    .pointerInput(mid) {
                        detectHorizontalDragGestures(onDragEnd = { if (drag > threshold && mid.isNotEmpty()) replyTo = m; drag = 0f },
                            onDragCancel = { drag = 0f }) { _, amount -> drag = (drag + amount).coerceIn(0f, threshold * 1.4f) }
                    }
                    .semantics {
                        if (mid.isNotEmpty()) customActions = listOf(
                            androidx.compose.ui.semantics.CustomAccessibilityAction("Antworten") { replyTo = m; true },
                            androidx.compose.ui.semantics.CustomAccessibilityAction("Reagieren") { menuFor = m; true })
                    }
                m.optJSONObject("anhang")?.let { info ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Box(gestures.combinedClickable(onClick = { actions.open(info) }, onLongClick = { if (mid.isNotEmpty()) menuFor = m })) {
                            AttachmentBubble(info, mine, actions.playing == info.optString("id")) { actions.open(info) }
                        }
                    }
                    Reactions(m, mine)
                    if (mine) TickLine(account.tick(cid, m), m.optLong("at")) { actions.retry(m.optString("mid")) }
                    return@items
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Bottom) {
                    // In a group: the sender's picture beside the bubble, like Messages (card d45b319a).
                    if (!mine && others.size > 1) Box(Modifier.padding(end = 6.dp)) { Avatar(m.optString("from"), account.picture(m.optString("from")), 28.dp) }
                    BasicText(m.optString("text"), style = style(17f, 400, if (mine) Color.White else colors.label),
                        modifier = gestures.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(if (mine) colors.blue else colors.theirs)
                            .combinedClickable(onClick = {}, onLongClick = { if (mid.isNotEmpty()) menuFor = m })
                            .padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Reactions(m, mine)
                if (mine) TickLine(account.tick(cid, m), m.optLong("at")) { actions.retry(m.optString("mid")) }
            }
            chat.optString("removed").takeIf { it.isNotEmpty() }?.let { by ->
                item { BasicText("$by hat dich aus der Unterhaltung entfernt.", style = style(11f, 400, colors.secondary).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) }
            }
        }
        if (problem != null) BasicText(problem, style = style(12f, 400, colors.red), modifier = Modifier.padding(horizontal = 16.dp))
        replyTo?.let { quoted ->
            // Answering: the quote above the input line, ✕ takes it back.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(3.dp, 32.dp).background(colors.blue))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    BasicText("Antwort an ${if (quoted.optString("from") == account.username) "dich" else quoted.optString("from")}", style = style(13f, 600, colors.blue))
                    BasicText(io.github.veritasx1.lichat.core.describe(quoted), style = style(13f, 400, colors.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { replyTo = null }.semantics { contentDescription = "Antwort aufheben" },
                    contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Close, colors.secondary, 16.dp) }
            }
        }
        if (chat.optString("removed").isEmpty()) InputBar({ text -> onSend(text, replyTo?.optString("mid")); replyTo = null }, actions)
        else Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
    menuFor?.let { m ->
        MessageMenu(m, account.username, onReact = { emoji -> onReact(m.optString("mid"), emoji); menuFor = null },
            onReply = { replyTo = m; menuFor = null }, onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.optString("text"))); menuFor = null },
            onClose = { menuFor = null })
    }
}

val TAPBACKS = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")

/** The reactions under a bubble, small at its lower corner (Olaf: „unten rechts“): each emoji once, with a number when
 *  several chose it. */
@Composable
fun Reactions(message: JSONObject, mine: Boolean) {
    val colors = palette()
    val reactions = message.optJSONObject("reaktionen") ?: return
    if (reactions.length() == 0) return
    val counts = reactions.keys().asSequence().map { reactions.getString(it) }.groupingBy { it }.eachCount()
    val who = reactions.keys().asSequence().joinToString(", ") { "$it ${reactions.getString(it)}" }
    Box(Modifier.fillMaxWidth().padding(top = 0.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        BasicText(counts.entries.joinToString(" ") { (emoji, n) -> if (n > 1) "$emoji $n" else emoji }, style = style(13f, 400, colors.label),
            modifier = Modifier.padding(horizontal = 8.dp).offset(y = (-6).dp).clip(RoundedCornerShape(12.dp)).background(colors.cell)
                .border(1.dp, colors.background, RoundedCornerShape(12.dp)).padding(horizontal = 7.dp, vertical = 2.dp)
                .semantics { contentDescription = "Reaktionen: $who" })
    }
}

/** Long press on a bubble: six tapbacks like iOS, then Antworten / Kopieren and who reacted how. */
@Composable
fun MessageMenu(message: JSONObject, me: String, onReact: (String) -> Unit, onReply: () -> Unit, onCopy: () -> Unit, onClose: () -> Unit) {
    val colors = palette()
    val reactions = message.optJSONObject("reaktionen") ?: JSONObject()
    val mine = reactions.optString(me)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).widthIn(max = 340.dp).clickable(enabled = false) {}) {
            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(colors.cell).padding(horizontal = 6.dp, vertical = 4.dp)) {
                TAPBACKS.forEach { emoji ->
                    BasicText(emoji, style = style(26f, 400, colors.label).copy(textAlign = TextAlign.Center),
                        modifier = Modifier.size(46.dp).clip(CircleShape).background(if (emoji == mine) colors.blue.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { onReact(if (emoji == mine) "" else emoji) }.padding(top = 6.dp)
                            .semantics { contentDescription = if (emoji == mine) "Reaktion $emoji zurücknehmen" else "Mit $emoji reagieren" })
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.cell)) {
                BasicText("Antworten", style = style(17f, 400, colors.label), modifier = Modifier.fillMaxWidth().clickable(onClick = onReply).padding(14.dp))
                if (message.optString("text").isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText("Kopieren", style = style(17f, 400, colors.label), modifier = Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(14.dp))
                }
                if (reactions.length() > 0) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText(reactions.keys().asSequence().joinToString("\n") { "${if (it == me) "Du" else it}  ${reactions.getString(it)}" },
                        style = style(15f, 400, colors.secondary), modifier = Modifier.fillMaxWidth().padding(14.dp))
                }
            }
        }
    }
}

/** What the conversation can do with attachments (card 9780f3a0) – the activity does the work (pickers, recorder, files). */
class AttachmentActions(
    val plus: (() -> Unit)? = null,
    val micStart: (() -> Unit)? = null,
    val micStop: (send: Boolean) -> Unit = {},
    val open: (JSONObject) -> Unit = {},
    /** Seconds recorded so far, or null when not recording. */
    val recording: Int? = null,
    /** The voice message playing now (its id). */
    val playing: String? = null,
    /** „Nicht gesendet“ tapped: send that message (its mid) again. */
    val retry: (String) -> Unit = {},
)

/** Under my bubble, grey: the time and ◷ / ✓ / ✓✓ / blue ✓✓ (17 × 11, line 1.6, round ends – Olaf's scheme with Jaques,
 *  card db70d358). Not sent: a red line, a tap sends it again. */
@Composable
fun TickLine(tick: Pair<String, Long>, at: Long, onRetry: () -> Unit) {
    val colors = palette()
    val (state, whenAt) = tick
    val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(at * 1000))
    if (state == "fehler") {
        BasicText("Nicht gesendet – tippen zum Wiederholen", style = style(11f, 400, colors.red).copy(textAlign = TextAlign.End),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onRetry).padding(end = 4.dp, top = 1.dp, bottom = 2.dp))
        return
    }
    val words = when (state) { "wartet" -> "Wird gesendet"; "zugestellt" -> "Zugestellt"; "gelesen" -> "Gelesen"; else -> "Gesendet" }
    val label = "$words " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(whenAt * 1000))
    Row(Modifier.fillMaxWidth().padding(end = 2.dp, top = 1.dp, bottom = 2.dp).semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        BasicText(time, style = style(11f, 400, colors.secondary))
        val color = if (state == "gelesen") colors.blue else colors.secondary
        androidx.compose.foundation.Canvas(Modifier.padding(start = 3.dp).size(17.dp, 11.dp)) {
            val s = size.width / 17f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = (if (state == "wartet") 1.3f else 1.6f) * s,
                cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            fun line(vararg p: Pair<Float, Float>) = drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(p[0].first * s, p[0].second * s); p.drop(1).forEach { lineTo(it.first * s, it.second * s) } }, color, style = stroke)
            when (state) {
                "wartet" -> { drawCircle(color, 4.3f * s, androidx.compose.ui.geometry.Offset(8.5f * s, 5.5f * s), style = stroke)
                              line(8.5f to 3.2f, 8.5f to 5.7f, 10.1f to 6.7f) }
                "gesendet" -> line(4f to 5.8f, 6.6f to 8.4f, 12.6f to 2.4f)
                else -> { line(1.2f to 5.8f, 3.8f to 8.4f, 9.8f to 2.4f); line(7.4f to 8.1f, 7.7f to 8.4f, 13.7f to 2.4f) }
            }
        }
    }
}

/** The input line like iOS 18: ⊕ on the left, the rounded field „LiChat“, inside it 🎤 while empty and the blue ↑ with
 *  text (Michelle b7a191c4: without a visible button it looked as if one could not send). While recording: the time, ✕, ↑. */
@Composable
fun InputBar(onSend: (String) -> Unit, actions: AttachmentActions = AttachmentActions()) {
    val colors = palette()
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom) {
        actions.plus?.let { plus ->
            Box(Modifier.padding(end = 8.dp, bottom = 1.dp).size(34.dp).clip(CircleShape).background(colors.fill)
                .clickable(role = Role.Button, onClick = plus).semantics { contentDescription = "Anhang" }, contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Plus, colors.secondary, 20.dp)
            }
        }
        val seconds = actions.recording
        if (seconds != null) {
            Row(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(18.dp)).background(colors.theirs).padding(start = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button) { actions.micStop(false) }
                    .semantics { contentDescription = "Aufnahme verwerfen" }, contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Close, colors.secondary, 16.dp) }
                Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(colors.red))
                BasicText("Aufnahme  %d:%02d".format(seconds / 60, seconds % 60), style = style(17f, 400, colors.label),
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                Box(Modifier.size(28.dp).clip(CircleShape).background(colors.blue).clickable(role = Role.Button) { actions.micStop(true) }
                    .semantics { contentDescription = "Sprachnachricht senden" }, contentAlignment = Alignment.Center) { GlyphIcon(Glyph.ArrowUp, Color.White, 18.dp) }
            }
            return@Row
        }
        Box(Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(colors.background)
            .border(0.5.dp, colors.separator, RoundedCornerShape(18.dp))) {
            BasicTextField(text, { text = it }, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 40.dp, top = 8.dp, bottom = 8.dp).semantics { contentDescription = "Nachricht" },
                decorationBox = { inner -> Box { if (text.isEmpty()) BasicText("LiChat", style = style(17f, 400, colors.tertiary)); inner() } })
            if (text.isNotBlank()) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(colors.blue)
                .clickable(role = Role.Button) { onSend(text); text = "" }.semantics { contentDescription = "Senden" },
                contentAlignment = Alignment.Center) { GlyphIcon(Glyph.ArrowUp, Color.White, 18.dp) }
            else actions.micStart?.let { mic ->
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClick = mic)
                    .semantics { contentDescription = "Sprachnachricht aufnehmen" }, contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Mic, colors.secondary, 20.dp) }
            }
        }
    }
}

/** A photo or video as its picture (rounded like a bubble), a voice message with ▶ and its length, a file with its name. */
@Composable
fun AttachmentBubble(info: JSONObject, mine: Boolean, playing: Boolean, onOpen: () -> Unit) {
    val colors = palette()
    val kind = info.optString("art")
    val preview = remember(info.optString("id")) {
        info.optString("vorschau").takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val label = when (kind) { "bild" -> "Foto"; "video" -> "Video"; "sprache" -> "Sprachnachricht"; else -> "Datei ${info.optString("name")}" }
    if ((kind == "bild" || kind == "video") && preview != null) {
        val width = 220.dp
        val height = width * (preview.height.toFloat() / preview.width.coerceAtLeast(1)).coerceIn(0.4f, 1.6f)
        Box(Modifier.size(width, height).clip(RoundedCornerShape(18.dp)).clickable(onClick = onOpen).semantics { contentDescription = label },
            contentAlignment = Alignment.Center) {
            Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (kind == "video") Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Play, Color.White, 24.dp)
            }
        }
        return
    }
    val foreground = if (mine) Color.White else colors.label
    Row(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(if (mine) colors.blue else colors.theirs)
        .clickable(onClick = onOpen).semantics { contentDescription = label }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (kind == "sprache") {
            GlyphIcon(if (playing) Glyph.Pause else Glyph.Play, foreground, 20.dp)
            // A quiet wave like Messages: bars of fixed heights (the sound itself is not analysed).
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(6, 12, 18, 10, 16, 8, 14, 20, 12, 6, 10, 16, 8, 12).forEach { h ->
                    Box(Modifier.padding(horizontal = 1.dp).size(3.dp, h.dp).clip(RoundedCornerShape(2.dp)).background(foreground.copy(alpha = 0.8f)))
                }
            }
            val seconds = info.optDouble("dauer", 0.0).toInt()
            BasicText("%d:%02d".format(seconds / 60, seconds % 60), style = style(15f, 400, foreground))
        } else {
            GlyphIcon(Glyph.Document, foreground, 26.dp)
            Column(Modifier.padding(start = 8.dp)) {
                BasicText(info.optString("name", "Datei"), style = style(15f, 600, foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(sizeText(info.optLong("groesse")), style = style(13f, 400, foreground.copy(alpha = 0.8f)))
            }
        }
    }
}

fun sizeText(size: Long): String = when {
    size < 1024 -> "$size Byte"
    size < 1024 * 1024 -> "%.1f KB".format(Locale.GERMANY, size / 1024.0)
    else -> "%.1f MB".format(Locale.GERMANY, size / 1048576.0)
}

/** A photo full-screen inside LiChat (no gallery app gets the plain picture); a tap closes it. */
@Composable
fun PhotoViewer(bytes: ByteArray, onClose: () -> Unit) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose).semantics { contentDescription = "Foto, tippen zum Schließen" },
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** The ⊕ choices, as an action sheet from the bottom with „Abbrechen“ (iOS 18). */
@Composable
fun AttachmentSheet(onPhotos: () -> Unit, onFile: () -> Unit, onCancel: () -> Unit) {
    val colors = palette()
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onCancel), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(10.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.cell)) {
                BasicText("Fotos und Videos", style = style(19f, 400, colors.blue).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onPhotos).padding(vertical = 17.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                BasicText("Datei", style = style(19f, 400, colors.blue).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onFile).padding(vertical = 17.dp))
            }
            Spacer(Modifier.height(8.dp))
            BasicText("Abbrechen", style = style(19f, 600, colors.blue).copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.cell).clickable(onClick = onCancel).padding(vertical = 17.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------- new message

@Composable
fun NewMessage(problem: String?, onCancel: () -> Unit, onSend: (List<String>, String) -> Unit) {
    val colors = palette()
    var to by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            BasicText("Neue Nachricht", style = style(17f, 600, colors.label), modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { TextButton("Abbrechen", colors.blue, onClick = onCancel) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("An:", style = style(17f, 400, colors.secondary))
            BasicTextField(to, { to = it }, singleLine = true, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { contentDescription = "An" },
                decorationBox = { inner -> Box { if (to.isEmpty()) BasicText("Benutzernamen, durch Komma getrennt", style = style(17f, 400, colors.tertiary)); inner() } })
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
        Spacer(Modifier.weight(1f))
        if (problem != null) BasicText(problem, style = style(12f, 400, colors.red), modifier = Modifier.padding(horizontal = 16.dp))
        InputBar(onSend = { text -> onSend(to.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }, text) })
    }
}

// ---------------------------------------------------------------------------------------------- details

@Composable
fun Details(account: Account, cid: String, version: Int, onBack: () -> Unit, onRemove: (String) -> Unit,
            group: GroupActions = GroupActions()) {
    val colors = palette()
    val members = remember(version, cid) { runCatching { account.members(cid) }.getOrDefault(emptyList()) }
    val code = remember(version, cid) { runCatching { account.safetyCode(cid) }.getOrDefault("") }
    val title = account.title(cid)
    val others = account.others(cid)
    val chat = account.chat(cid)
    val isGroup = members.size > 2 || chat.optString("title").isNotEmpty()
    val active = chat.optString("removed").isEmpty()
    var name by remember(cid, version) { mutableStateOf(chat.optString("title")) }
    var adding by remember(cid) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.ime)).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { TextButton("Fertig", colors.blue, 600, onClick = onBack) }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(CircleShape).clickable(enabled = isGroup && active, onClick = group.picture)) {
                Avatar(title, if (others.size == 1 && !isGroup) account.picture(others[0]) else account.groupPicture(cid), 88.dp)
            }
            if (isGroup && active) BasicText("Bild ändern", style = style(15f, 400, colors.blue), modifier = Modifier.padding(top = 6.dp).clickable(onClick = group.picture))
            if (!isGroup || !active) BasicText(title, style = style(22f, 600, colors.label), modifier = Modifier.padding(top = 10.dp, bottom = 20.dp))
        }
        if (isGroup && active) Section("GRUPPENNAME", footer = "Name und Bild sehen nur die Mitglieder – sie stehen nie im Klartext auf dem Server.") {
            BasicTextField(name, { name = it }, singleLine = true, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { group.rename(name) }),
                modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "Gruppenname" },
                decorationBox = { inner -> Box { if (name.isEmpty()) BasicText("Gruppenname", style = style(17f, 400, colors.tertiary)); inner() } })
        }
        Section("${members.size} Personen") {
            members.forEachIndexed { i, member ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(member, account.picture(member), 32.dp)
                    BasicText(if (member == account.username) "$member (ich)" else member, style = style(17f, 400, colors.label),
                        modifier = Modifier.weight(1f).padding(start = 12.dp))
                    // In a group someone can be taken out (test plan S2).
                    if (members.size > 2 && member != account.username && active) TextButton("Entfernen", colors.red) { onRemove(member) }
                }
                Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            }
            if (active) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(colors.fill), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Plus, colors.blue, 18.dp) }
                BasicTextField(adding, { adding = it.lowercase() }, singleLine = true, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        group.add(adding.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }); adding = "" }),
                    modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { contentDescription = "Person hinzufügen" },
                    decorationBox = { inner -> Box { if (adding.isEmpty()) BasicText("Person hinzufügen", style = style(17f, 400, colors.blue)); inner() } })
            }
        }
        if (active) Section(null) {
            SwitchRow("Mitteilungen stummschalten", account.muted(cid), group.mute)
            if (isGroup) {
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
                BasicText("Unterhaltung verlassen", style = style(17f, 400, colors.red),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = group.leave).padding(16.dp))
            }
        }
        if (code.isNotEmpty()) Section("SICHERHEITSCODE", footer = "Vergleicht den Code auf allen Geräten – gleich heißt: niemand liest mit.") {
            BasicText(code.split(" ").chunked(4).joinToString("\n") { it.joinToString("  ") }, style = style(20f, 500, colors.label).copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "Sicherheitscode $code" })
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** What the details of a group can do (card d45b319a) – the activity does the work. */
class GroupActions(
    val rename: (String) -> Unit = {},
    val picture: () -> Unit = {},
    val add: (List<String>) -> Unit = {},
    val mute: (Boolean) -> Unit = {},
    val leave: () -> Unit = {},
)

@Composable
fun Section(header: String?, footer: String? = null, content: @Composable () -> Unit) {
    val colors = palette()
    if (header != null) BasicText(header.uppercase(), style = style(13f, 400, colors.secondary), modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.cell)) { content() }
    if (footer != null) BasicText(footer, style = style(13f, 400, colors.secondary), modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp))
}

// ---------------------------------------------------------------------------------------------- settings

@Composable
fun SettingsScreen(account: Account, preview: Boolean, unrestricted: Boolean, onPreview: (Boolean) -> Unit, onPicture: () -> Unit,
                   onUnrestrict: () -> Unit, onBack: () -> Unit, notifying: Boolean = true, onNotifications: () -> Unit = {},
                   receipts: Boolean = false, onReceipts: (Boolean) -> Unit = {}) {
    val colors = palette()
    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            BasicText("Einstellungen", style = style(17f, 600, colors.label), modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { TextButton("Fertig", colors.blue, 600, onClick = onBack) }
        }
        Section(null) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onPicture).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(account.username, account.picture(account.username), 56.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    BasicText(account.username, style = style(20f, 600, colors.label))
                    BasicText("Profilbild wählen", style = style(15f, 400, colors.blue))
                }
            }
        }
        Section("MITTEILUNGEN", footer = if (notifying) "Aus: Die Mitteilung sagt nur „Neue Nachricht“. An: Absender und Text stehen auch auf dem Sperrbildschirm."
            else "Android zeigt für LiChat keine Mitteilungen – neue Nachrichten siehst du nur, wenn du LiChat öffnest.") {
            Row(Modifier.fillMaxWidth().clickable(enabled = !notifying, onClick = onNotifications).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText("Mitteilungen", style = style(17f, 400, colors.label), modifier = Modifier.weight(1f))
                BasicText(if (notifying) "An" else "Aus", style = style(17f, 400, if (notifying) colors.secondary else colors.blue))
            }
            Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            SwitchRow("Absender und Text zeigen", preview, onPreview)
        }
        Section("NACHRICHTEN", footer = "Wenn eingeschaltet, sehen andere, dass du ihre Nachrichten gelesen hast – und du siehst es bei ihnen " +
            "(blaue Haken). Ausgeschaltet gilt beides nicht. „Zugestellt“ gibt es immer.") {
            SwitchRow("Lesebestätigungen senden", receipts, onReceipts)
        }
        Section("VERBINDUNG", footer = "LiChat hält selbst eine Verbindung zu deinem Server – kein anderer Dienst erfährt, wann du Nachrichten bekommst. " +
            "Solange Android LiChat beim Akku einschränkt, können Nachrichten verspätet kommen.") {
            Row(Modifier.fillMaxWidth().clickable(enabled = !unrestricted, onClick = onUnrestrict).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText("Akku-Einschränkung", style = style(17f, 400, colors.label), modifier = Modifier.weight(1f))
                BasicText(if (unrestricted) "Aus" else "An", style = style(17f, 400, if (unrestricted) colors.secondary else colors.blue))
            }
            Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                BasicText("Server", style = style(17f, 400, colors.label), modifier = Modifier.weight(1f))
                BasicText(account.server.url.removePrefix("https://"), style = style(15f, 400, colors.secondary), maxLines = 1)
            }
        }
    }
}

/** Apple's switch: green when on, 51 × 31 dp (Li-Gestaltung) – only drawn; the row around it is what is tapped. */
@Composable
fun Switch(on: Boolean) {
    val colors = palette()
    Box(Modifier.size(51.dp, 31.dp).clip(CircleShape).background(if (on) colors.green else colors.fill).padding(2.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(27.dp).clip(CircleShape).background(Color.White))
    }
}

/** A settings row with a switch: the whole row toggles, and it is one switch node with its label and state – like iOS
 *  and LiMail (Michelle: before, only the pixels of the switch reacted and it had no checked state). */
@Composable
fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = palette()
    Row(Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = style(17f, 400, colors.label), modifier = Modifier.weight(1f))
        Switch(on)
    }
}

// ---------------------------------------------------------------------------------------------- sign in

@Composable
fun SetupScreen(busy: Boolean, problem: String?, onSignIn: (String, String, String) -> Unit) {
    val colors = palette()
    var server by remember { mutableStateOf("https://") }
    var invite by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.ime))
        .verticalScroll(rememberScrollState()).padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(88.dp)
        BasicText("Bei LiChat anmelden", style = style(28f, 700, colors.label), modifier = Modifier.padding(top = 16.dp))
        BasicText("Mit der Einladung deines Servers. Alles, was du schreibst, ist Ende-zu-Ende verschlüsselt.", style = style(15f, 400, colors.secondary).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp))
        Section(null) {
            listOf(Triple("Server", server) { v: String -> server = v }, Triple("Einladung", invite) { v: String -> invite = v },
                Triple("Benutzername", name) { v: String -> name = v.lowercase() }).forEachIndexed { i, (label, value, set) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(label, style = style(17f, 400, colors.label), modifier = Modifier.width(120.dp))
                    BasicTextField(value, set, singleLine = true, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                        modifier = Modifier.weight(1f).semantics { contentDescription = label })
                }
                if (i < 2) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            }
        }
        if (problem != null) BasicText(problem, style = style(13f, 400, colors.red).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(16.dp))
        val ready = !busy && server.length > 8 && invite.isNotBlank() && name.isNotBlank()
        Box(Modifier.padding(16.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(if (ready) colors.blue else colors.fill)
            .clickable(enabled = ready, role = Role.Button) { onSignIn(server, invite.trim(), name.trim()) }, contentAlignment = Alignment.Center) {
            BasicText(if (busy) "Anmelden …" else "Anmelden", style = style(17f, 600, if (ready) Color.White else colors.tertiary))
        }
    }
}

// ---------------------------------------------------------------------------------------------- explanation

/** Once, before Android asks (Michelle Z6): why LiChat wants to notify and to run without battery limits. */
@Composable
fun Explain(onContinue: () -> Unit, onLater: () -> Unit) {
    val colors = palette()
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(36.dp).clip(RoundedCornerShape(14.dp)).background(colors.cell), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("Nachrichten sofort bekommen", style = style(17f, 600, colors.label).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(top = 18.dp, start = 16.dp, end = 16.dp))
            BasicText("LiChat hält selbst eine Verbindung zu deinem Server, auch wenn die App geschlossen ist. Kein anderer Dienst erfährt davon. " +
                "Dafür fragt Android gleich zweimal: ob LiChat Mitteilungen zeigen darf und ob es vom Akkusparen ausgenommen wird. " +
                "Ohne das kommen Nachrichten erst, wenn du LiChat öffnest.", style = style(13f, 400, colors.label).copy(textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
            BasicText("Weiter", style = style(17f, 600, colors.blue).copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onContinue).padding(12.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
            BasicText("Später", style = style(17f, 400, colors.blue).copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onLater).padding(12.dp))
        }
    }
}
