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
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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

/** The messages shown – without those in „Zuletzt gelöscht“ (a0ceee6e). */
private fun messages(chat: JSONObject) = chat.getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    .filter { !it.has("geloescht") }

// ---------------------------------------------------------------------------------------------- list

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatList(account: Account, version: Int, seen: Map<String, Int>, problem: String?, unrestricted: Boolean,
             onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onUnrestrict: () -> Unit,
             notifying: Boolean = true, onNotifications: () -> Unit = {}, onAction: (String, String) -> Unit = { _, _ -> }) {
    val colors = palette()
    var query by remember { mutableStateOf("") }
    // Filter like Messages (1f800b4f, a0ceee6e): Alle / Bekannte / Unbekannte / Ungelesen / Zuletzt gelöscht.
    var filter by remember { mutableStateOf("alle") }
    var choosingFilter by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    val pinned = remember(version, filter) { if (filter == "alle") synchronized(account.lock) { account.pinned() } else emptyList() }
    val all = remember(version, filter) {
        synchronized(account.lock) {
            account.filtered(filter).filter { it !in pinned }.asSequence().map { cid ->   // without my devices' own group and the pinned
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
            TextButton("Filter", colors.blue) { choosingFilter = true }
            IconButton(Glyph.Compose, "Neue Nachricht", colors.blue, onNew)
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                LargeTitle(FILTERS.first { it.first == filter }.second.let { if (filter == "alle") "Nachrichten" else it })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(colors.fill).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Search, colors.secondary, 17.dp)
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(query, { query = it }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrectEnabled = false), textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
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
            if (pinned.isNotEmpty() && query.isBlank()) item {
                // Pinned as big round pictures, three in a row (206118bf).
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    pinned.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { cid ->
                                val title = account.title(cid)
                                val others = account.others(cid)
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(onClick = { onOpen(cid) }, onLongClick = { rowMenu = cid }).padding(vertical = 8.dp)
                                    .semantics { contentDescription = "$title, angeheftet" + if (account.unread(cid) > 0) ", ungelesen" else "" },
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        Avatar(title, if (others.size == 1) account.picture(others[0]) else account.groupPicture(cid), 72.dp)
                                        if (account.unread(cid) > 0) Box(Modifier.align(Alignment.TopStart).size(14.dp).clip(CircleShape).background(colors.blue))
                                    }
                                    BasicText(title, style = style(13f, 400, colors.label).copy(textAlign = TextAlign.Center), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (rows.isEmpty() && pinned.isEmpty()) item {
                BasicText("Noch keine Unterhaltungen", style = style(17f, 600, colors.secondary).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(top = 120.dp))
            }
            items(rows, key = { it.first }) { (cid, last, count) ->
                val title = account.title(cid)
                val others = account.others(cid)
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (filter == "geloescht") rowMenu = cid else onOpen(cid) },
                    onLongClick = { rowMenu = cid }).padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (account.unread(cid) > 0) colors.blue else Color.Transparent))
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
    if (choosingFilter) ChoiceSheet(FILTERS.map { it.second }, FILTERS.indexOfFirst { it.first == filter },
        onChoose = { filter = FILTERS[it].first; choosingFilter = false }, onCancel = { choosingFilter = false })
    rowMenu?.let { cid ->
        // Long press on a conversation, like iOS: anheften, (un)gelesen, Hinweise, löschen – in „Zuletzt gelöscht“ wiederherstellen.
        val chat = account.chat(cid)
        val actions = if (filter == "geloescht") listOf("wiederherstellen" to "Wiederherstellen", "endgueltig" to "Endgültig löschen")
            else listOf((if (chat.has("angeheftet")) "loesen" to "Nicht mehr anheften" else "anheften" to "Anheften"),
                (if (account.unread(cid) > 0) "gelesen" to "Als gelesen markieren" else "ungelesen" to "Als ungelesen markieren"),
                (if (chat.optBoolean("stumm")) "laut" to "Hinweise einblenden" else "stumm" to "Hinweise ausblenden"),
                "loeschen" to "Löschen")
        ChoiceSheet(actions.map { it.second }, -1, destructive = actions.indexOfFirst { it.first in setOf("loeschen", "endgueltig") },
            onChoose = { onAction(cid, actions[it].first); rowMenu = null }, onCancel = { rowMenu = null })
    }
}

val FILTERS = listOf("alle" to "Alle Nachrichten", "bekannt" to "Bekannte Absender", "unbekannt" to "Unbekannte Absender",
    "ungelesen" to "Ungelesene Nachrichten", "geloescht" to "Zuletzt gelöscht")

/** An iOS action sheet: choices from the bottom, „Abbrechen“ apart; the chosen one with a check. */
@Composable
fun ChoiceSheet(choices: List<String>, chosen: Int, destructive: Int = -1, onChoose: (Int) -> Unit, onCancel: () -> Unit) {
    val colors = palette()
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onCancel), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(8.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.cell)) {
                choices.forEachIndexed { i, label ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    Row(Modifier.fillMaxWidth().clickable { onChoose(i) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(label, style = style(17f, 400, if (i == destructive) colors.red else colors.blue).copy(textAlign = TextAlign.Center), modifier = Modifier.weight(1f))
                        if (i == chosen) BasicText("✓", style = style(17f, 600, colors.blue))
                    }
                }
            }
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.cell).clickable(onClick = onCancel).padding(16.dp),
                contentAlignment = Alignment.Center) { BasicText("Abbrechen", style = style(17f, 600, colors.blue)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- conversation

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(account: Account, cid: String, version: Int, problem: String?, onBack: () -> Unit, onInfo: () -> Unit,
               onSend: (String, String?) -> Unit, actions: AttachmentActions = AttachmentActions(),
               onReact: (String, String) -> Unit = { _, _ -> }, onEdit: (String, String) -> Unit = { _, _ -> },
               onUnsend: (String) -> Unit = {}, onDelete: (String) -> Unit = {}) {
    val colors = palette()
    // Olaf 09.10.: swipe a bubble to the right to answer it, press it long for a reaction (card a7d9d987).
    var replyTo by remember(cid) { mutableStateOf<JSONObject?>(null) }
    var menuFor by remember(cid) { mutableStateOf<JSONObject?>(null) }
    // Editing one of my messages (card 4ceca163): its text in the input line, „Nachricht bearbeiten“ above it.
    var editing by remember(cid) { mutableStateOf<JSONObject?>(null) }
    var versionsOf by remember(cid) { mutableStateOf<String?>(null) }
    // Where each bubble is on screen: its menu opens right there, the bubble lifted above the dimmed transcript.
    val bounds = remember(cid) { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val chat = remember(version, cid) { synchronized(account.lock) { JSONObject(account.chat(cid).toString()) } }
    val title = account.title(cid)
    val others = account.others(cid)
    // A group: more than two, or a chat with a name (Michelle: „Gruppentest“ of two had no suggestions, no sender line).
    val isGroup = others.size > 1 || chat.optString("title").isNotEmpty()
    val list = messages(chat)
    // Newest at the bottom and the list anchored there (reverseLayout): the keyboard, a growing input line and a long
    // last bubble never hide the end (card 0c01f599). Item 0 is the newest message.
    val listState = rememberLazyListState()
    val atEnd by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    var unseen by remember(cid) { mutableStateOf(0) }
    // „↑ Zur ersten ungelesenen“ (300129f8): worked out when the chat opens, before it counts as read.
    val firstUnread = remember(cid) { account.firstUnread(cid) }
    val unreadCount = remember(cid) { account.unread(cid) }
    var firstDone by remember(cid) { mutableStateOf(false) }
    var known by remember(cid) { mutableStateOf(list.size) }
    LaunchedEffect(list.size) {
        // A new message: follow it when I am at the end or wrote it myself, otherwise offer „↓ Neue Nachricht“ (card be8f93c3).
        val added = list.size - known
        known = list.size
        if (added <= 0) return@LaunchedEffect
        val fromMe = list.lastOrNull()?.optString("from") == account.username
        if (fromMe || listState.firstVisibleItemIndex <= added) { unseen = 0; listState.animateScrollToItem(0) } else unseen += added
    }
    LaunchedEffect(atEnd) { if (atEnd) unseen = 0 }
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
        Box(Modifier.weight(1f)) {
        // „•••“ while someone types – it fades a few seconds after the last sign (card 884f7c8a).
        var clock by remember { mutableStateOf(0L) }
        val typing = remember(version, cid, clock) { account.typingNow(cid) }
        LaunchedEffect(typing, clock, version) { if (typing.isNotEmpty()) { kotlinx.coroutines.delay(io.github.veritasx1.lichat.core.TYPING_FRESH_MS + 100); clock++ } }
        LaunchedEffect(typing.isNotEmpty()) { if (typing.isNotEmpty() && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, reverseLayout = true) {
            if (typing.isNotEmpty() && chat.optString("removed").isEmpty()) item(key = "tippt") {
                val who = typing.joinToString(", ") { account.display(it) }
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).semantics(mergeDescendants = true) { contentDescription = "$who tippt" }) {
                    if (isGroup) BasicText(who, style = style(11f, 400, colors.secondary), modifier = Modifier.padding(start = 12.dp, top = 6.dp))
                    TypingBubble()
                }
            }
            chat.optString("removed").takeIf { it.isNotEmpty() }?.let { by ->
                item { BasicText("$by hat dich aus der Unterhaltung entfernt.", style = style(11f, 400, colors.secondary).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) }
            }
            // One Column per item: with reverseLayout the parts of an item would otherwise stack upside down too.
            items(list.size, key = { list[list.size - 1 - it].optString("mid").ifEmpty { "n${list.size - 1 - it}" } }) { row -> Column {
                val i = list.size - 1 - row
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
                if (m.optBoolean("zurueckgerufen")) {
                    // Called back (iOS): a grey line where the bubble was – for everyone.
                    BasicText(if (mine) "Du hast eine Nachricht widerrufen." else "${account.display(m.optString("from"))} hat eine Nachricht widerrufen.",
                        style = style(13f, 400, colors.secondary).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            textAlign = if (mine) TextAlign.End else TextAlign.Start),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
                    return@Column
                }
                if (!mine && isGroup && previous?.optString("from") != m.optString("from"))
                    BasicText(account.display(m.optString("from")), style = style(11f, 400, colors.secondary), modifier = Modifier.padding(start = 12.dp, top = 6.dp))
                m.optJSONObject("antwort")?.let { quoted ->
                    // The message this answers, small above the bubble; a tap jumps to it.
                    val target = list.indexOfFirst { it.optString("mid") == quoted.optString("mid") }
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        BasicText("↩ ${if (quoted.optString("from") == account.username) "Du" else account.display(quoted.optString("from"))}: ${if (quoted.optBoolean("zurueckgerufen")) "Nachricht widerrufen" else quoted.optString("text")}",
                            style = style(13f, 400, colors.secondary), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, colors.fill, RoundedCornerShape(12.dp))
                                .clickable(enabled = target >= 0) { scope.launch { listState.animateScrollToItem(list.size - 1 - target + (if (chat.optString("removed").isEmpty()) 0 else 1) + (if (typing.isEmpty()) 0 else 1)) } }
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
                val info = m.optJSONObject("anhang")
                val versions = m.optJSONArray("bearbeitet")
                if (versions != null && versionsOf == mid) {
                    // The earlier versions, small and grey above the bubble (iOS: tap „Bearbeitet“).
                    for (k in 0 until versions.length()) {
                        BasicText(versions.getJSONObject(k).optString("text"), style = style(13f, 400, colors.secondary).copy(textAlign = if (mine) TextAlign.End else TextAlign.Start),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Bottom) {
                    // In a group: the sender's picture beside the bubble, like Messages (card d45b319a).
                    if (!mine && isGroup && info == null) Box(Modifier.padding(end = 6.dp)) { Avatar(m.optString("from"), account.picture(m.optString("from")), 28.dp) }
                    // While its menu is open the bubble is shown lifted – the original is hidden (Michelle 20eb5cc5).
                    Reacted(m, account, gestures.alpha(if (menuFor?.optString("mid") == mid && mid.isNotEmpty()) 0f else 1f)) {
                        Bubble(m, mine, Modifier.onGloballyPositioned { if (mid.isNotEmpty()) bounds[mid] = it.boundsInWindow() },
                            Modifier.combinedClickable(onClick = { info?.let { actions.open(it) } }, onLongClick = { if (mid.isNotEmpty()) menuFor = m }),
                            playing = info != null && actions.playing == info.optString("id"), open = actions.open, load = actions.load)
                    }
                }
                if (versions != null) {
                    BasicText(if (versionsOf == mid) "Frühere Versionen ausblenden" else "Bearbeitet", style = style(11f, 600, colors.blue).copy(textAlign = if (mine) TextAlign.End else TextAlign.Start),
                        modifier = Modifier.fillMaxWidth().clickable { versionsOf = if (versionsOf == mid) null else mid }.padding(horizontal = 12.dp, vertical = 2.dp))
                }
                if (mine) TickLine(account.tick(cid, m), m.optLong("at")) { actions.retry(m.optString("mid")) }
            } }
            item { Spacer(Modifier.height(8.dp)) }
        }
        val firstVisible = firstUnread != null && listState.layoutInfo.visibleItemsInfo.any { it.key == firstUnread }
        if (firstUnread != null && !firstDone && !firstVisible && unreadCount > 0) {
            BasicText(if (unreadCount == 1) "↑ 1 ungelesene Nachricht" else "↑ $unreadCount ungelesene Nachrichten", style = style(15f, 600, Color.White),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).background(colors.blue)
                    .clickable {
                        firstDone = true
                        val i = list.indexOfFirst { it.optString("mid") == firstUnread }
                        val row = list.size - 1 - i + (if (chat.optString("removed").isEmpty()) 0 else 1) + (if (typing.isEmpty()) 0 else 1)
                        if (i >= 0) scope.launch { listState.animateScrollToItem(row) }
                    }.padding(horizontal = 14.dp, vertical = 7.dp))
        }
        if (unseen > 0) {
            BasicText(if (unseen == 1) "↓ Neue Nachricht" else "↓ $unseen neue Nachrichten", style = style(15f, 600, Color.White),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp)).background(colors.blue)
                    .clickable { unseen = 0; scope.launch { listState.animateScrollToItem(0) } }.padding(horizontal = 14.dp, vertical = 7.dp))
        }
        }
        if (problem != null) BasicText(problem, style = style(12f, 400, colors.red), modifier = Modifier.padding(horizontal = 16.dp))
        replyTo?.let { quoted ->
            // Answering: the quote above the input line, ✕ takes it back.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(3.dp, 32.dp).background(colors.blue))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    BasicText("Antwort an ${if (quoted.optString("from") == account.username) "dich" else account.display(quoted.optString("from"))}", style = style(13f, 600, colors.blue))
                    BasicText(io.github.veritasx1.lichat.core.describe(quoted), style = style(13f, 400, colors.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { replyTo = null }.semantics { contentDescription = "Antwort aufheben" },
                    contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Close, colors.secondary, 16.dp) }
            }
        }
        editing?.let { message ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(3.dp, 32.dp).background(colors.blue))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    BasicText("Nachricht bearbeiten", style = style(13f, 600, colors.blue))
                    BasicText(message.optString("text"), style = style(13f, 400, colors.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { editing = null }.semantics { contentDescription = "Bearbeiten abbrechen" },
                    contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Close, colors.secondary, 16.dp) }
            }
        }
        if (chat.optString("removed").isEmpty()) InputBar({ text ->
            val edited = editing
            if (edited != null) { onEdit(edited.optString("mid"), text); editing = null }
            else { onSend(text, replyTo?.optString("mid")); replyTo = null }
        }, actions, preset = editing?.optString("text"),
            mentions = if (isGroup) ({ typed -> account.mentionCandidates(cid, typed).map { it to account.display(it) } }) else null)
        else Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
    menuFor?.let { m ->
        val mine = m.optString("from") == account.username
        MessageMenu(m, account, mine, bounds[m.optString("mid")], { Reacted(m, account) { Bubble(m, mine, maxLines = 6) } }, onReact = { emoji -> onReact(m.optString("mid"), emoji); menuFor = null },
            onReply = { replyTo = m; menuFor = null }, onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.optString("text"))); menuFor = null },
            onClose = { menuFor = null },
            onEdit = if (account.canEdit(m)) ({ replyTo = null; editing = m; menuFor = null }) else null,
            onDelete = { onDelete(m.optString("mid")); menuFor = null },
            onUnsend = if (account.canUnsend(m)) ({ onUnsend(m.optString("mid")); menuFor = null }) else null)
    }
}

val TAPBACKS = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")

/** Words for the tapbacks (Michelle a7d9d987: the accessibility tree says what they are). */
val TAPBACK_WORDS = mapOf("❤️" to "Herz", "👍" to "Daumen hoch", "👎" to "Daumen runter", "😂" to "Haha", "‼️" to "Ausrufezeichen", "❓" to "Fragezeichen")
fun tapbackWord(emoji: String) = TAPBACK_WORDS[emoji] ?: emoji

/** The first emoji in what was typed, whole – 👍🏽, 👩‍👩‍👧, 🇫🇷, 1️⃣ stay one – or null for letters and digits. Put
 *  together by hand (skin tones, ZWJ, flags, keycaps, variation selectors), the same on every Android and in tests. */
fun firstEmoji(text: String): String? {
    val cps = text.codePoints().toArray()
    fun joins(cp: Int) = cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
    fun regional(cp: Int) = cp in 0x1F1E6..0x1F1FF
    var i = 0
    while (i < cps.size) {
        val cp = cps[i]
        val keycap = (cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A) && cps.getOrNull(i + 1) in setOf(0xFE0F, 0x20E3)
        if (keycap || (!Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp) && cp > 0x2000)) {
            var end = i + 1
            if (regional(cp) && cps.getOrNull(end)?.let { regional(it) } == true) end++
            while (end < cps.size) {
                when {
                    joins(cps[end]) -> end++
                    cps[end] == 0x200D && end + 1 < cps.size -> end += 2
                    else -> break
                }
            }
            return String(cps, i, end - i).take(16)
        }
        i++
    }
    return null
}

/** A bubble as it is: text, or the attachment. The same in the transcript and lifted above it while its menu is open. */
@Composable
fun Bubble(m: JSONObject, mine: Boolean, modifier: Modifier = Modifier, interact: Modifier = Modifier, playing: Boolean = false,
           open: (JSONObject) -> Unit = {}, load: ((JSONObject) -> ByteArray)? = null, maxLines: Int = Int.MAX_VALUE) {
    val colors = palette()
    val info = m.optJSONObject("anhang")
    if (info != null) Box(modifier.then(interact)) { AttachmentBubble(info, mine, playing, load) { open(info) } }
    else BasicText(withMentions(m.optString("text")), style = style(17f, 400, if (mine) Color.White else colors.label),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(if (mine) colors.blue else colors.theirs)
            .then(interact).padding(horizontal = 12.dp, vertical = 7.dp))
}

/** „@name“ bold in a bubble (card d54175ed). */
fun withMentions(text: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    var last = 0
    for (match in io.github.veritasx1.lichat.core.MENTION.findAll(text)) {
        append(text.substring(last, match.range.first))
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        append(match.value)
        pop()
        last = match.range.last + 1
    }
    append(text.substring(last))
}

/** The bubble with its reactions small at its lower right corner (Olaf: „unten rechts“ – for every bubble, Michelle a7d9d987). */
@Composable
fun Reacted(message: JSONObject, account: Account, modifier: Modifier = Modifier, bubble: @Composable () -> Unit) {
    val reactions = message.optJSONObject("reaktionen")
    val has = reactions != null && reactions.length() > 0
    Box(modifier.padding(bottom = if (has) 14.dp else 0.dp)) {
        bubble()
        if (has) ReactionBadge(reactions!!, account, Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 14.dp))
    }
}

/** Each emoji once, with a number when several chose it. */
@Composable
fun ReactionBadge(reactions: JSONObject, account: Account, modifier: Modifier = Modifier) {
    val colors = palette()
    val counts = reactions.keys().asSequence().map { reactions.getString(it) }.groupingBy { it }.eachCount()
    val who = reactions.keys().asSequence().joinToString(", ") {
        "${if (it == account.username) "Du" else account.display(it)}: ${tapbackWord(reactions.getString(it))}"
    }
    BasicText(counts.entries.joinToString(" ") { (emoji, n) -> if (n > 1) "$emoji $n" else emoji }, style = style(13f, 400, colors.label),
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(colors.cell)
            .border(1.dp, colors.background, RoundedCornerShape(12.dp)).padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics { contentDescription = "Reaktionen – $who" })
}

/** Long press on a bubble, like iOS: the transcript dims, the bubble stays where it was and is lifted above it, the six
 *  tapbacks right above it and Antworten / Kopieren / who reacted right below – on the bubble's side (Michelle a7d9d987). */
@Composable
fun MessageMenu(message: JSONObject, account: Account, mine: Boolean, at: androidx.compose.ui.geometry.Rect?, bubble: @Composable () -> Unit,
                onReact: (String) -> Unit, onReply: () -> Unit, onCopy: () -> Unit, onClose: () -> Unit,
                onEdit: (() -> Unit)? = null, onUnsend: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val colors = palette()
    val me = account.username
    val reactions = message.optJSONObject("reaktionen") ?: JSONObject()
    val chosen = reactions.optString(me)
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var picking by remember { mutableStateOf(false) }
    // Darker than before (Michelle 20eb5cc5): the lifted bubble stands out clearly.
    Box(Modifier.fillMaxSize().background(Color(0x99000000))
        .clickable(onClick = onClose).semantics { contentDescription = "Menü schließen" }) {
        if (picking) {
            // „+“ (Olaf 20eb5cc5): our own emoji picker at the bottom – categories, recently used, search.
            EmojiPicker({ onReact(it) }, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars).padding(8.dp))
            return@Box
        }
        androidx.compose.ui.layout.Layout(content = {
            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(colors.cell).clickable(enabled = false) {}.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val shown = if (chosen.isNotEmpty() && chosen !in TAPBACKS) TAPBACKS + chosen else TAPBACKS
                shown.forEach { emoji ->
                    BasicText(emoji, style = style(26f, 400, colors.label).copy(textAlign = TextAlign.Center),
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(if (emoji == chosen) colors.blue.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { onReact(if (emoji == chosen) "" else emoji) }.padding(top = 5.dp)
                            .semantics { contentDescription = if (emoji == chosen) "Reaktion ${tapbackWord(emoji)} zurücknehmen" else "Mit ${tapbackWord(emoji)} reagieren" })
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(colors.fill).clickable { picking = true }
                    .semantics { contentDescription = "Anderes Emoji" }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Plus, colors.secondary, 18.dp)
                }
                        }
            Box { bubble() }
            Column(Modifier.widthIn(min = 200.dp, max = 260.dp).clip(RoundedCornerShape(14.dp)).background(colors.cell).clickable(enabled = false) {}) {
                BasicText("Antworten", style = style(17f, 400, colors.label), modifier = Modifier.fillMaxWidth().clickable(onClick = onReply).padding(14.dp))
                if (message.optString("text").isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText("Kopieren", style = style(17f, 400, colors.label), modifier = Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(14.dp))
                }
                onEdit?.let { edit ->
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText("Bearbeiten", style = style(17f, 400, colors.label), modifier = Modifier.fillMaxWidth().clickable(onClick = edit).padding(14.dp))
                }
                onDelete?.let { delete ->
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText("Löschen", style = style(17f, 400, colors.red), modifier = Modifier.fillMaxWidth().clickable(onClick = delete).padding(14.dp))
                }
                onUnsend?.let { unsend ->
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText("Senden widerrufen", style = style(17f, 400, colors.red), modifier = Modifier.fillMaxWidth().clickable(onClick = unsend).padding(14.dp))
                }
                if (reactions.length() > 0) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText(reactions.keys().asSequence().joinToString("\n") { "${if (it == me) "Du" else account.display(it)}  ${reactions.getString(it)}" },
                        style = style(15f, 400, colors.secondary), modifier = Modifier.fillMaxWidth().padding(14.dp))
                }
            }
        }, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).onGloballyPositioned { origin = it.positionInWindow() }) { parts, limits ->
            val loose = limits.copy(minWidth = 0, minHeight = 0)
            val margin = 12.dp.roundToPx()
            val gap = 8.dp.roundToPx()
            val bar = parts[0].measure(loose)
            val menu = parts[2].measure(loose)
            val room = (limits.maxHeight - bar.height - menu.height - 2 * gap - 2 * margin).coerceAtLeast(48.dp.roundToPx())
            val lifted = parts[1].measure(loose.copy(maxHeight = room))
            layout(limits.maxWidth, limits.maxHeight) {
                // Where the bubble is in the transcript, in this layout's coordinates (without one: in the middle).
                val place = at?.translate(-origin.x, -origin.y)
                val left = (place?.left?.toInt() ?: if (mine) limits.maxWidth - lifted.width - margin else margin)
                    .coerceIn(0, (limits.maxWidth - lifted.width).coerceAtLeast(0))
                val lowest = (limits.maxHeight - margin - menu.height - gap - lifted.height)
                val highest = margin + bar.height + gap
                val wanted = place?.top?.toInt() ?: ((limits.maxHeight - lifted.height) / 2)
                val top = wanted.coerceIn(highest, lowest.coerceAtLeast(highest))
                fun side(width: Int) = (if (mine) left + lifted.width - width else left).coerceIn(margin, (limits.maxWidth - width - margin).coerceAtLeast(margin))
                bar.place(side(bar.width), top - gap - bar.height)
                lifted.place(left, top)
                menu.place(side(menu.width), top + lifted.height + gap)
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
    /** Something was typed (with the switch on the others see „…“, card 884f7c8a). */
    val typing: () -> Unit = {},
    /** The attachment's bytes (downloaded once, kept sealed on the phone) – for photos sharp in the bubble (ed0686a1). */
    val load: ((JSONObject) -> ByteArray)? = null,
)

/** Photos in the bubbles at display size (Olaf ed0686a1: the 320 px preview blown up was blurry): decoded once, kept
 *  here while the app runs (24 photos), the small preview shows until then. */
object FullImages {
    val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24)
    const val PIXELS = 900

    fun decode(bytes: ByteArray, target: Int = PIXELS): androidx.compose.ui.graphics.ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

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
fun InputBar(onSend: (String) -> Unit, actions: AttachmentActions = AttachmentActions(), preset: String? = null,
             mentions: ((String) -> List<Pair<String, String>>)? = null) {
    val colors = palette()
    // The text with its cursor: after „Bearbeiten“ or a suggested name the cursor stands at the end (Michelle 4ceca163).
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    val text = field.text
    fun setText(value: String) { field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length)) }
    // „@…“ at the end in a group: the members that fit as a row above the input, a tap writes „@name “ (card d54175ed).
    val typedMention = mentions?.let { Regex("(?:^|\\s)@([\\w.-]*)$").find(text) }
    val suggestions = typedMention?.let { mentions(it.groupValues[1]) }.orEmpty()
    if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 6.dp)) {
        suggestions.forEach { (user, name) ->
            BasicText(name, style = style(15f, 600, colors.blue), modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(16.dp))
                .background(colors.fill).clickable {
                    val start = text.lastIndexOf('@')
                    setText(text.substring(0, start) + "@$user ")
                }.padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "$name erwähnen" })
        }
    }
    LaunchedEffect(preset) { if (preset != null) setText(preset) }     // „Bearbeiten“: the message's text to correct
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom) {
        actions.plus?.let { plus ->
            Box(Modifier.padding(end = 8.dp, bottom = 1.dp).size(34.dp).clip(CircleShape).background(colors.fill)
                .clickable(role = Role.Button) { keyboard?.hide(); focus.clearFocus(); plus() }.semantics { contentDescription = "Anhang" }, contentAlignment = Alignment.Center) {
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
            BasicTextField(field, { field = it; if (it.text.isNotEmpty()) actions.typing() }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, autoCorrectEnabled = true), textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 40.dp, top = 8.dp, bottom = 8.dp).semantics { contentDescription = "Nachricht" },
                decorationBox = { inner -> Box { if (text.isEmpty()) BasicText("LiChat", style = style(17f, 400, colors.tertiary)); inner() } })
            if (text.isNotBlank()) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(colors.blue)
                .clickable(role = Role.Button) { onSend(text); setText("") }.semantics { contentDescription = "Senden" },
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
fun AttachmentBubble(info: JSONObject, mine: Boolean, playing: Boolean, load: ((JSONObject) -> ByteArray)? = null, onOpen: () -> Unit) {
    val colors = palette()
    val kind = info.optString("art")
    val id = info.optString("id")
    val small = remember(id) {
        info.optString("vorschau").takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    // The photo itself at display size replaces the preview (photos up to 15 MB, like Messages loads them).
    val full by androidx.compose.runtime.produceState(FullImages.cache.get(id), id) {
        if (value == null && kind == "bild" && load != null && info.optLong("groesse") <= 15L * 1024 * 1024) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { load(info) }.getOrNull()?.let { FullImages.decode(it) }
            }?.also { FullImages.cache.put(id, it) }
        }
    }
    val preview = full ?: small
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
            BasicTextField(to, { to = it }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrectEnabled = false), textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
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
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                    autoCorrectEnabled = true, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { group.rename(name) }),
                modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "Gruppenname" },
                decorationBox = { inner -> Box { if (name.isEmpty()) BasicText("Gruppenname", style = style(17f, 400, colors.tertiary)); inner() } })
        }
        Section("${members.size} Personen") {
            members.forEachIndexed { i, member ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(member, account.picture(member), 32.dp)
                    BasicText(account.display(member) + if (member == account.username) " (ich)" else "", style = style(17f, 400, colors.label),
                        modifier = Modifier.weight(1f).padding(start = 12.dp))
                    // In a group someone can be taken out (test plan S2).
                    if (members.size > 2 && member != account.username && active) TextButton("Entfernen", colors.red) { onRemove(member) }
                }
                Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            }
            if (active) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(colors.fill), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Plus, colors.blue, 18.dp) }
                BasicTextField(adding, { adding = it.lowercase() }, singleLine = true, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        group.add(adding.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }); adding = "" }),
                    modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { contentDescription = "Person hinzufügen" },
                    decorationBox = { inner -> Box { if (adding.isEmpty()) BasicText("Person hinzufügen", style = style(17f, 400, colors.blue)); inner() } })
            }
        }
        if (active) Section(null) {
            // In a group a muted chat still notifies when someone writes @me or @alle (iOS, card d54175ed).
            SwitchRow(if (isGroup) "Nur bei Erwähnung benachrichtigen" else "Mitteilungen stummschalten", account.muted(cid), group.mute)
            Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            // Read receipts for this conversation (3c0400ad) – otherwise the general setting applies.
            SwitchRow("Lesebestätigungen senden", account.receiptsFor(cid), group.receipts)
            if (!isGroup && others.size == 1) group.block?.let { block ->
                val person = others[0]
                val blocked = account.blocked.has(person)
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
                BasicText(if (blocked) "${account.display(person)} nicht mehr blockieren" else "${account.display(person)} blockieren", style = style(17f, 400, colors.red),
                    modifier = Modifier.fillMaxWidth().clickable { block(person, !blocked) }.padding(16.dp))
            }
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
    val receipts: (Boolean) -> Unit = {},
    val block: ((String, Boolean) -> Unit)? = null,
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
                   receipts: Boolean = false, onReceipts: (Boolean) -> Unit = {}, onName: (String, String) -> Unit = { _, _ -> },
                   typing: Boolean = false, onTyping: (Boolean) -> Unit = {},
                   devices: List<Account.OwnDevice>? = null, linking: String? = null, onAddDevice: (String) -> Unit = {},
                   onRemoveDevice: (String) -> Unit = {}, onScanDevice: () -> Unit = {}) {
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
                    BasicText(account.display(account.username), style = style(20f, 600, colors.label))
                    BasicText("Profilbild wählen", style = style(15f, 400, colors.blue))
                }
            }
        }
        val myName = account.profiles.optJSONObject("name")
        var first by remember { mutableStateOf(myName?.optString("vorname") ?: "") }
        var last by remember { mutableStateOf(myName?.optString("nachname") ?: "") }
        Section("NAME", footer = "So sehen dich deine Gesprächspartner – nur Ende-zu-Ende. Dein Benutzername bleibt „${account.username}“.") {
            listOf(Triple("Vorname", first) { v: String -> first = v }, Triple("Nachname", last) { v: String -> last = v }).forEachIndexed { i, (label, value, set) ->
                BasicTextField(value, set, singleLine = true, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, autoCorrectEnabled = true, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onName(first, last) }),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = label },
                    decorationBox = { inner -> Box { if (value.isEmpty()) BasicText(label, style = style(17f, 400, colors.tertiary)); inner() } })
                if (i == 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
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
        Section("NACHRICHTEN", footer = "Wenn eingeschaltet, sehen andere, dass du ihre Nachrichten gelesen hast oder gerade schreibst – und " +
            "du siehst es bei ihnen (blaue Haken, „•••“). Ausgeschaltet gilt beides nicht. „Zugestellt“ gibt es immer.") {
            SwitchRow("Lesebestätigungen senden", receipts, onReceipts)
            Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            SwitchRow("Tipp-Anzeige senden", typing, onTyping)
        }
        // Several devices per person (card da5cbfcb): the list, „Gerät hinzufügen“ with the new device's code, sign out others.
        var adding by remember { mutableStateOf(false) }
        var code by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf<String?>(null) }
        Section("GERÄTE", footer = "Auf dem neuen Gerät „Mit vorhandenem Gerät verbinden“ wählen – es zeigt einen Code. Es liest alles, was ab dann " +
            "kommt; ältere Nachrichten bleiben auf deinen bisherigen Geräten. Gerätenamen bleiben auf dem Gerät, der Server kennt sie nicht.") {
            devices.orEmpty().forEachIndexed { i, d ->
                if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val since = java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.GERMANY).format(java.util.Date(d.since * 1000))
                    Column(Modifier.weight(1f)) {
                        BasicText(if (d.isThis) "Dieses Gerät" + (if (d.name.isNotEmpty()) " (${d.name})" else "") else "Gerät", style = style(17f, 400, colors.label))
                        BasicText("seit $since", style = style(13f, 400, colors.secondary))
                    }
                    if (!d.isThis) BasicText(if (confirm == d.id) "Wirklich abmelden?" else "Abmelden", style = style(15f, if (confirm == d.id) 600 else 400, colors.red),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                            if (confirm == d.id) { confirm = null; onRemoveDevice(d.id) } else confirm = d.id
                        }.padding(horizontal = 6.dp, vertical = 8.dp))
                }
            }
            if (devices == null) BasicText("Wird geladen …", style = style(15f, 400, colors.secondary), modifier = Modifier.padding(16.dp))
            Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            when {
                linking != null -> BasicText(linking, style = style(17f, 400, colors.secondary), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                adding -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(code, { code = it.uppercase().take(12) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii), textStyle = style(17f, 400, colors.label),
                        cursorBrush = SolidColor(colors.blue), modifier = Modifier.weight(1f).padding(vertical = 6.dp).semantics { contentDescription = "Code vom neuen Gerät" },
                        decorationBox = { inner -> if (code.isEmpty()) BasicText("Code vom neuen Gerät", style = style(17f, 400, colors.tertiary)); inner() })
                    TextButton("Verbinden", colors.blue, 600, enabled = code.replace("-", "").length >= 8) { adding = false; onAddDevice(code.trim()); code = "" }
                }
                else -> Unit
            }
            if (linking == null && adding) {
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
                BasicText("QR-Code scannen …", style = style(17f, 400, colors.blue),
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { adding = false; onScanDevice() }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            when {
                linking != null || adding -> Unit
                else -> BasicText("Gerät hinzufügen …", style = style(17f, 400, colors.blue),
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { adding = true }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
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
fun SetupScreen(busy: Boolean, problem: String?, linkCode: String? = null, onLink: (String) -> Unit = {}, onLinkCancel: () -> Unit = {},
                onSignIn: (String, String, String, String, String) -> Unit) {
    val colors = palette()
    // Our own server as a default (Tante Erna types nothing) – any other one can be entered.
    var server by remember { mutableStateOf("https://lichat.lisoftware.de") }
    var linkMode by remember { mutableStateOf(false) }
    if (linkMode || linkCode != null) {
        // A second device (card da5cbfcb): this one shows a code, the existing device accepts it – like Signal.
        Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.ime))
            .verticalScroll(rememberScrollState()).padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(88.dp)
            BasicText("Mit vorhandenem Gerät verbinden", style = style(24f, 700, colors.label).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(16.dp))
            if (linkCode == null) {
                Section(null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("Server", style = style(17f, 400, colors.label), modifier = Modifier.width(120.dp))
                        BasicTextField(server, { server = it }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri), textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Server" })
                    }
                }
                if (problem != null) BasicText(problem, style = style(13f, 400, colors.red).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(16.dp))
                val ready = !busy && server.length > 8
                Box(Modifier.padding(16.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(if (ready) colors.blue else colors.fill)
                    .clickable(enabled = ready, role = Role.Button) { onLink(server) }, contentAlignment = Alignment.Center) {
                    BasicText("Code anzeigen", style = style(17f, 600, if (ready) Color.White else colors.tertiary))
                }
            } else {
                QrCode(io.github.veritasx1.lichat.core.Account.linkUri(server, linkCode), Modifier.padding(top = 8.dp).size(220.dp)
                    .semantics { contentDescription = "QR-Code zum Koppeln" })
                BasicText(linkCode, style = style(40f, 700, colors.label).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    modifier = Modifier.padding(16.dp).semantics { contentDescription = "Code ${linkCode.toList().joinToString(" ")}" })
                BasicText("Auf deinem anderen Gerät: Einstellungen › Geräte › „Gerät hinzufügen …“ – den QR-Code scannen oder den Code eingeben. Er gilt 10 Minuten.",
                    style = style(15f, 400, colors.secondary).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 32.dp))
                BasicText(if (busy) "Warte auf dein anderes Gerät …" else "", style = style(15f, 400, colors.secondary), modifier = Modifier.padding(16.dp))
                if (problem != null) BasicText(problem, style = style(13f, 400, colors.red).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(16.dp))
            }
            TextButton("Abbrechen", colors.blue) { linkMode = false; onLinkCancel() }
        }
        return
    }
    var invite by remember { mutableStateOf("") }
    // Name as people write it (Olaf 09.10.: capitals, spaces); the user name is made from it and can be changed.
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nameTouched by remember { mutableStateOf(false) }
    if (!nameTouched) name = io.github.veritasx1.lichat.core.suggestUsername(first, last)
    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.ime))
        .verticalScroll(rememberScrollState()).padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(88.dp)
        BasicText("Bei LiChat anmelden", style = style(28f, 700, colors.label), modifier = Modifier.padding(top = 16.dp))
        BasicText("Mit der Einladung deines Servers. Alles, was du schreibst, ist Ende-zu-Ende verschlüsselt.", style = style(15f, 400, colors.secondary).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp))
        Section(null) {
            listOf(Triple("Server", server) { v: String -> server = v }, Triple("Einladung", invite) { v: String -> invite = v },
                Triple("Vorname", first) { v: String -> first = v }, Triple("Nachname", last) { v: String -> last = v },
                Triple("Benutzername", name) { v: String -> name = io.github.veritasx1.lichat.core.suggestUsername(v); nameTouched = true }).forEachIndexed { i, (label, value, set) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(label, style = style(17f, 400, colors.label), modifier = Modifier.width(120.dp))
                    BasicTextField(value, set, singleLine = true, keyboardOptions = when (label) { "Vorname", "Nachname" -> androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, autoCorrectEnabled = true); "Server" -> androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri); "Einladung" -> androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii); else -> androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrectEnabled = false) }, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                        modifier = Modifier.weight(1f).semantics { contentDescription = label })
                }
                if (i < 4) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            }
        }
        BasicText("Der Benutzername ist deine Kennung zum Finden (klein, ohne Leerzeichen). Deinen Namen sehen nur deine Gesprächspartner.",
            style = style(13f, 400, colors.secondary), modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp))
        if (problem != null) BasicText(problem, style = style(13f, 400, colors.red).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(16.dp))
        val ready = !busy && server.length > 8 && invite.isNotBlank() && name.isNotBlank()
        Box(Modifier.padding(16.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(if (ready) colors.blue else colors.fill)
            .clickable(enabled = ready, role = Role.Button) { onSignIn(server, invite.trim(), name.trim(), first.trim(), last.trim()) }, contentAlignment = Alignment.Center) {
            BasicText(if (busy) "Anmelden …" else "Anmelden", style = style(17f, 600, if (ready) Color.White else colors.tertiary))
        }
        TextButton("Mit vorhandenem Gerät verbinden", colors.blue) { linkMode = true }
        Spacer(Modifier.height(24.dp))
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

/** Olaf's template (typing-animation-3x.gif, measured by Michelle, 884f7c8a): a grey capsule, three dots; a wave runs
 *  left to right – each dot in turn darker (#B0B2B4 → #606060) and ~38 % bigger, peaks at 380/580/760 ms, a cycle of
 *  1.84 s with a rest. With „Animationen entfernen“ the dots stand still. */
@Composable
fun TypingBubble() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val still = remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val time = if (still) null else androidx.compose.animation.core.rememberInfiniteTransition(label = "tippt").animateFloat(0f, 1840f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1840, easing = androidx.compose.animation.core.LinearEasing)), label = "welle")
    // Dark: the capsule is the incoming bubble's colour, like iOS (Michelle: #3B3B3D was too light against #26252A).
    val capsule = if (dark) palette().theirs else Color(0xFFECECEC)
    val rest = if (dark) Color(0xFF6C6C72) else Color(0xFFB0B2B4)
    val peak = if (dark) Color(0xFFE6E6EA) else Color(0xFF606060)
    Row(Modifier.clip(RoundedCornerShape(18.dp)).background(capsule).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(380f, 580f, 760f).forEach { top ->
            val t = time?.value ?: 0f
            val strength = (1f - kotlin.math.abs(t - top) / 200f).coerceIn(0f, 1f)
            Box(Modifier.size(9.dp).graphicsLayer { scaleX = 1f + 0.38f * strength; scaleY = 1f + 0.38f * strength }
                .clip(CircleShape).background(androidx.compose.ui.graphics.lerp(rest, peak, strength)))
        }
    }
}
