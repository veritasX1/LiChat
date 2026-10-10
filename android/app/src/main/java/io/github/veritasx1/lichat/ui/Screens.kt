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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
    var typingClock by remember { mutableStateOf(0L) }     // „schreibt …“ in the list ends 6 s after the last sign
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
                        // Olaf zu 884f7c8a: while someone types, a small blue wave with „schreibt …“ replaces the preview
                        val typists = remember(version, cid, typingClock) { account.typingNow(cid) }
                        LaunchedEffect(typists, typingClock, version) {
                            if (typists.isNotEmpty()) { kotlinx.coroutines.delay(io.github.veritasx1.lichat.core.TYPING_FRESH_MS + 100); typingClock++ }
                        }
                        if (typists.isNotEmpty()) {
                            val group = account.members(cid).size > 2 || account.chat(cid).optString("title").isNotEmpty()
                            ListTyping(when {
                                !group -> "schreibt …"
                                typists.size == 1 -> "${account.display(typists[0])} schreibt …"
                                else -> "${typists.size} Personen schreiben …"
                            })
                            return@Column
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
    // Leaving the chat covers every secret picture again (f9e3f055)
    androidx.compose.runtime.DisposableEffect(cid) { onDispose { SecretShown.ids.clear() } }
    var menuFor by remember(cid) { mutableStateOf<JSONObject?>(null) }
    // Like iOS: the menu of a long press closes the keyboard first (Olaf 19020c8c: „Bearbeiten“ was cut off under it).
    val menuKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val menuFocus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(menuFor) { if (menuFor != null) { menuKeyboard?.hide(); menuFocus.clearFocus() } }
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
                    TypingBubble(group = isGroup, label = typingLabel(typing.map { account.display(it) }))
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
                    if (!mine && isGroup && info == null) Box(Modifier.padding(end = 6.dp)) { Avatar(account.display(m.optString("from")), account.picture(m.optString("from")), 28.dp) }
                    // While its menu is open the bubble is shown lifted – the original is hidden (Michelle 20eb5cc5).
                    Reacted(m, account, gestures.alpha(if (menuFor?.optString("mid") == mid && mid.isNotEmpty()) 0f else 1f)) {
                        Bubble(m, mine, Modifier.onGloballyPositioned { if (mid.isNotEmpty()) bounds[mid] = it.boundsInWindow() },
                            Modifier.combinedClickable(onClick = { info?.let { actions.open(it) } }, onLongClick = { if (mid.isNotEmpty()) menuFor = m }),
                            playing = info != null && actions.playing == info.optString("id"), open = actions.open, load = actions.load, actions = actions)
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

/** Like Messages while a photo goes up: a ring that fills, an × in the middle – a tap stops it (cf647f61). */
@Composable
fun UploadRing(share: Float, color: Color, modifier: Modifier, onCancel: () -> Unit) {
    androidx.compose.foundation.Canvas(modifier.clickable(onClick = onCancel)
        .semantics { contentDescription = "Wird gesendet, ${(share * 100).toInt()} Prozent – tippen zum Abbrechen" }) {
        val ring = size.minDimension * 0.36f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val corner = androidx.compose.ui.geometry.Offset(center.x - ring, center.y - ring)
        val box = androidx.compose.ui.geometry.Size(ring * 2, ring * 2)
        drawArc(color.copy(alpha = 0.3f), 0f, 360f, false, corner, box, style = stroke)
        drawArc(color, -90f, 360f * share.coerceIn(0.02f, 1f), false, corner, box, style = stroke)
        val x = ring * 0.38f
        drawLine(color, center + androidx.compose.ui.geometry.Offset(-x, -x), center + androidx.compose.ui.geometry.Offset(x, x), stroke.width)
        drawLine(color, center + androidx.compose.ui.geometry.Offset(-x, x), center + androidx.compose.ui.geometry.Offset(x, -x), stroke.width)
    }
}

/** A bubble as it is: text, or the attachment. The same in the transcript and lifted above it while its menu is open. */
@Composable
fun Bubble(m: JSONObject, mine: Boolean, modifier: Modifier = Modifier, interact: Modifier = Modifier, playing: Boolean = false,
           open: (JSONObject) -> Unit = {}, load: ((JSONObject) -> ByteArray)? = null, maxLines: Int = Int.MAX_VALUE,
           actions: AttachmentActions? = null) {
    val colors = palette()
    val info = m.optJSONObject("anhang")
    if (info != null) Box(modifier.then(interact)) {
        if (info.optBoolean("einmal")) OnceBubble(m, mine) { actions?.openOnce?.invoke(m.optString("mid")) }
        else AttachmentBubble(info, mine, playing, load, actions?.paused ?: false, actions?.progress ?: { null }, actions?.seek ?: {},
            uploading = if (mine) actions?.uploading?.invoke(m.optString("mid")) else null,
            onCancel = { actions?.cancelUpload?.invoke(m.optString("mid")) }) { open(info) }
    }
    else {
        // As wide as its longest line (Olaf 18cfe3d2: wrapped text kept the full 280 dp and left blue space on the right).
        val text = withMentions(m.optString("text"), if (mine) Color.White else colors.blue)
        val textStyle = style(17f, 400, if (mine) Color.White else colors.label)
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val inner = with(density) { (280.dp - 24.dp).roundToPx() }
        val tight = remember(text, textStyle, inner, maxLines) {
            val layout = measurer.measure(text, textStyle, maxLines = maxLines, constraints = androidx.compose.ui.unit.Constraints(maxWidth = inner))
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
            with(density) { kotlin.math.ceil(widest).toInt().coerceAtMost(inner).toDp() }
        }
        BasicText(text, style = textStyle, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = modifier.clip(RoundedCornerShape(18.dp)).background(if (mine) colors.blue else colors.theirs)
                .then(interact).padding(horizontal = 12.dp, vertical = 7.dp).width(tight + 1.dp))
    }
}

/** „@name“ bold and links tappable in a bubble (cards d54175ed, 22c918ce): a tap on a link opens it (browser, mail, phone);
 *  underlined in the bubble's own colour. No preview is fetched. */
fun withMentions(text: String, linkColor: Color = Color.Unspecified): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    val links = io.github.veritasx1.lichat.core.findLinks(text)
    for (link in links) {
        addLink(androidx.compose.ui.text.LinkAnnotation.Url(link.target, androidx.compose.ui.text.TextLinkStyles(
            androidx.compose.ui.text.SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))),
            link.range.first, link.range.last + 1)
    }
    for (match in io.github.veritasx1.lichat.core.MENTION.findAll(text)) {
        if (links.any { it.range.first <= match.range.last && match.range.first <= it.range.last }) continue
        addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), match.range.first, match.range.last + 1)
    }
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
            // Above the keyboard while searching (Michelle 20eb5cc5: it covered the results).
            EmojiPicker({ onReact(it) }, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)).padding(8.dp))
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
                // A link in it (22c918ce): copy or share it, like the iPhone's long press on a link.
                io.github.veritasx1.lichat.core.findLinks(message.optString("text")).firstOrNull()?.let { link ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val shown = link.target.removePrefix("mailto:").removePrefix("tel:")
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText("Link kopieren", style = style(17f, 400, colors.label), modifier = Modifier.fillMaxWidth()
                        .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(shown)); onClose() }.padding(14.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                    BasicText("Link teilen …", style = style(17f, 400, colors.label), modifier = Modifier.fillMaxWidth().clickable {
                        runCatching { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, shown), null)) }
                        onClose()
                    }.padding(14.dp))
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
        }, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .onGloballyPositioned { origin = it.positionInWindow() }) { parts, limits ->
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
    /** The voice message playing is paused (its place kept – 864041bf). */
    val paused: Boolean = false,
    /** (position, duration) in ms of the voice message loaded, or null. */
    val progress: () -> Pair<Int, Int>? = { null },
    /** Jump in the voice message playing (0…1 of its length). */
    val seek: (Float) -> Unit = {},
    /** The attachment's bytes (downloaded once, kept sealed on the phone) – for photos sharp in the bubble (ed0686a1). */
    val load: ((JSONObject) -> ByteArray)? = null,
    /** A one-time picture tapped (its mid): opened once, full screen, then gone (fe9f2029). */
    val openOnce: (String) -> Unit = {},
    /** How far my attachment (its mid) is up (0…1), or null when it is not going up now (cf647f61). */
    val uploading: (String) -> Float? = { null },
    /** The ring tapped: stop sending that attachment (its mid). */
    val cancelUpload: (String) -> Unit = {},
)

/** Secret pictures (f9e3f055) tapped open while the app runs – everywhere else they stay pixelated. */
object SecretShown {
    val ids = androidx.compose.runtime.mutableStateListOf<String>()
    const val SECONDS = 10
}

/** Pixelated here, from the picture itself: 6 blocks on the short side (f9e3f055; Michelle: 14 still showed stripes). */
fun pixelated(bitmap: androidx.compose.ui.graphics.ImageBitmap): androidx.compose.ui.graphics.ImageBitmap {
    val source = bitmap.asAndroidBitmap()
    val block = (minOf(source.width, source.height) / 6).coerceAtLeast(1)
    val small = android.graphics.Bitmap.createScaledBitmap(source, (source.width / block).coerceAtLeast(1), (source.height / block).coerceAtLeast(1), true)
    return android.graphics.Bitmap.createScaledBitmap(small, source.width, source.height, false).asImageBitmap()
}

/** A one-time picture's state, the same words as Account.onceState – worked out from the message alone. */
fun onceState(m: JSONObject, mine: Boolean, nowS: Long = System.currentTimeMillis() / 1000): String = when {
    mine -> "eigen"
    m.has("geoeffnet") -> "geoeffnet"
    nowS - m.optLong("at") > io.github.veritasx1.lichat.core.ONCE_DAYS * 86400L -> "abgelaufen"
    else -> "neu"
}

/** A one-time picture in the chat: never a preview, only what it is and where it stands (fe9f2029). */
@Composable
fun OnceBubble(m: JSONObject, mine: Boolean, onOpen: () -> Unit) {
    val colors = palette()
    val state = onceState(m, mine)
    val note = when (state) {
        "eigen" -> if ((m.optJSONObject("geoeffnet_von")?.length() ?: 0) > 0) "Geöffnet" else "Einmal-Ansicht"
        "neu" -> "Tippen zum Ansehen"
        "geoeffnet" -> "Geöffnet"
        else -> "Abgelaufen"
    }
    val active = state == "neu"
    val foreground = if (mine) Color.White else if (active) colors.label else colors.secondary
    Row(Modifier.clip(RoundedCornerShape(18.dp)).background(if (mine) colors.blue else colors.theirs)
        .clickable(enabled = active, onClick = onOpen).semantics { contentDescription = "Foto, Einmal-Ansicht, $note" }
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).border(2.dp, if (active) colors.blue.takeIf { !mine } ?: Color.White else foreground, CircleShape),
            contentAlignment = Alignment.Center) { BasicText("1", style = style(12f, 700, if (active && !mine) colors.blue else foreground)) }
        Column(Modifier.padding(start = 10.dp)) {
            BasicText("Foto", style = style(16f, 600, foreground))
            BasicText(note, style = style(13f, 400, if (mine) Color.White.copy(alpha = 0.85f) else colors.secondary))
        }
    }
}

/** A one-time picture full screen (fe9f2029): no screenshot (FLAG_SECURE also hides it in the app overview); Android 14+
 *  reports an attempt, which puts a fixed line into the chat. Closing it – „Fertig“, back, tap – and it is gone. */
@Composable
fun OnceViewer(bytes: ByteArray, secret: Boolean, onScreenshot: () -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    val bitmap = remember(bytes) { FullImages.decode(bytes, 2048) }
    var hidden by remember(bytes) { mutableStateOf(secret) }
    androidx.compose.runtime.DisposableEffect(activity) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val callback = if (android.os.Build.VERSION.SDK_INT >= 34 && activity != null)
            android.app.Activity.ScreenCaptureCallback { onScreenshot() }.also { activity.registerScreenCaptureCallback(activity.mainExecutor, it) } else null
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= 34 && callback != null) activity?.unregisterScreenCaptureCallback(callback as android.app.Activity.ScreenCaptureCallback)
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            (activity as? io.github.veritasx1.lichat.MainActivity)?.applyOverview()     // the app lock's protection stays (99eea3f7)
        }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    // A tap only uncovers a secret one – closing is „Fertig“ or back (Michelle c5efaa9: the second tap of the opening
    // landed here and closed it before anything was seen).
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = hidden) { hidden = false }
        .semantics { contentDescription = "Einmal-Bild" }, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(if (hidden) pixelated(bitmap) else bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("Einmal-Ansicht", style = style(15f, 600, Color.White))
            BasicText(if (hidden) "Tippen zum Aufdecken" else "Nach „Fertig“ ist das Foto weg", style = style(13f, 400, Color.White.copy(alpha = 0.75f)))
        }
        Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(8.dp)) { TextButton("Fertig", Color.White, 600, onClick = onClose) }
    }
}

/** Before a photo leaves (fe9f2029, f9e3f055): normal, as a one-time picture, pixelated – or both. */
@Composable
fun PhotoSendSheet(bytes: ByteArray?, onCancel: () -> Unit, onMarkup: (() -> Unit)? = null, onSend: (once: Boolean, secret: Boolean) -> Unit) {
    val colors = palette()
    var once by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf(false) }
    val bitmap = remember(bytes) { bytes?.let { FullImages.decode(it, 600) } }
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.systemBars)) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { TextButton("Abbrechen", colors.blue, onClick = onCancel) }
            BasicText("Foto", style = style(17f, 600, colors.label), modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { TextButton("Senden", colors.blue, 600) { onSend(once, secret) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(if (secret) pixelated(bitmap) else bitmap, contentDescription = "Foto", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width / bitmap.height.coerceAtLeast(1).toFloat()).clip(RoundedCornerShape(12.dp)))
        }
        // 4fb76dfd: draw on it before it leaves, like Apple's „Markieren“
        if (onMarkup != null && bytes != null) BasicText("Markieren", style = style(17f, 400, colors.blue).copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onMarkup).padding(vertical = 10.dp))
        Section(null, footer = "Ein Einmal-Bild lässt sich genau einmal ansehen, dann ist es überall weg – Bildschirmfotos sind gesperrt. Verpixelt zeigt erst ein Tippen das Foto.") {
            SwitchRow("Einmal-Ansicht", once) { once = it }
            Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(colors.separator))
            SwitchRow("Verpixeln", secret) { secret = it }
        }
        Spacer(Modifier.height(12.dp))
    }
}


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
fun AttachmentBubble(info: JSONObject, mine: Boolean, playing: Boolean, load: ((JSONObject) -> ByteArray)? = null,
                     paused: Boolean = false, progress: () -> Pair<Int, Int>? = { null }, seek: (Float) -> Unit = {},
                     uploading: Float? = null, onCancel: () -> Unit = {}, onOpen: () -> Unit) {
    val colors = palette()
    val kind = info.optString("art")
    val id = info.optString("id")
    val small = remember(id) {
        info.optString("vorschau").takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val hidden = info.optBoolean("geheim") && id !in SecretShown.ids
    // Uncovered, it is covered again after a few seconds like on the iPhone (Michelle: it stayed sharp)
    if (info.optBoolean("geheim") && !hidden) LaunchedEffect(id) { kotlinx.coroutines.delay(SecretShown.SECONDS * 1000L); SecretShown.ids.remove(id) }
    // The photo itself at display size replaces the preview (photos up to 15 MB, like Messages loads them) – a secret one
    // only once it was tapped open (Michelle c5efaa9: it stood sharp in the bubble after a minute).
    val full by androidx.compose.runtime.produceState(if (hidden) null else FullImages.cache.get(id), id, hidden) {
        if (!hidden && value == null && kind == "bild" && load != null && info.optLong("groesse") <= 15L * 1024 * 1024) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { load(info) }.getOrNull()?.let { FullImages.decode(it) }
            }?.also { FullImages.cache.put(id, it) }
        }
    }
    val coarse = remember(id, small) { if (info.optBoolean("geheim")) small?.let { pixelated(it) } else null }
    val preview = if (hidden) coarse else full ?: small
    // An animated GIF runs in the bubble like in Messages (Michelle 999f37be) – still with „Animationen entfernen“.
    val gifBytes by androidx.compose.runtime.produceState<ByteArray?>(null, id) {
        if (!info.optBoolean("geheim") && info.optString("mime") == "image/gif" && load != null && android.os.Build.VERSION.SDK_INT >= 28)
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { load(info) }.getOrNull() }
    }
    val label = when (kind) { "bild" -> "Foto"; "video" -> "Video"; "sprache" -> "Sprachnachricht"; else -> "Datei ${info.optString("name")}" }
    if ((kind == "bild" || kind == "video") && preview != null) {
        val width = 220.dp
        val height = width * (preview.height.toFloat() / preview.width.coerceAtLeast(1)).coerceIn(0.4f, 1.6f)
        Box(Modifier.size(width, height).clip(RoundedCornerShape(18.dp)).clickable { if (hidden) SecretShown.ids.add(id) else onOpen() }
            .semantics { contentDescription = if (hidden) "Geheimbild, tippen zum Aufdecken" else label }, contentAlignment = Alignment.Center) {
            val bytes = gifBytes
            if (hidden) {
                Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color(0x8C8E8E93)))      // frosted: no colours or shapes to make out
                BasicText("Geheimbild", style = style(15f, 600, Color.White), modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            else if (bytes != null && android.os.Build.VERSION.SDK_INT >= 28) GifView(bytes, Modifier.fillMaxSize())
            else Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (uploading != null) UploadRing(uploading, Color.White, Modifier.size(44.dp).clip(CircleShape).background(Color(0x99000000)), onCancel)
            else if (kind == "video") Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Play, Color.White, 24.dp)
            }
        }
        return
    }
    val foreground = if (mine) Color.White else colors.label
    Row(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(if (mine) colors.blue else colors.theirs)
        .clickable(onClick = onOpen).semantics { contentDescription = label }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (uploading != null) UploadRing(uploading, foreground, Modifier.padding(end = 8.dp).size(28.dp), onCancel)
        if (kind == "sprache") {
            // Like Messages (Olaf 864041bf): ▶ ⇄ ⏸, the wave fills as it plays, the time left counts down, a pause keeps
            // the place, a swipe or tap on the wave jumps, at the end it is back at the start.
            var fraction by remember(id) { mutableStateOf(0f) }
            var left by remember(id) { mutableStateOf<Int?>(null) }
            LaunchedEffect(playing, paused) {
                if (!playing) { fraction = 0f; left = null; return@LaunchedEffect }
                while (true) {
                    progress()?.let { (position, length) -> if (length > 0) { fraction = position / length.toFloat(); left = (length - position + 999) / 1000 } }
                    if (paused) break
                    kotlinx.coroutines.delay(100)
                }
            }
            GlyphIcon(if (playing && !paused) Glyph.Pause else Glyph.Play, foreground, 20.dp)
            val bars = listOf(6, 12, 18, 10, 16, 8, 14, 20, 12, 6, 10, 16, 8, 12)
            Row(Modifier.padding(horizontal = 10.dp).then(if (playing) Modifier.pointerInput(id) {
                    detectTapGestures { offset -> seek(offset.x / size.width); fraction = offset.x / size.width }
                }.pointerInput("$id-ziehen") {
                    detectHorizontalDragGestures { change, _ -> change.consume(); val f = (change.position.x / size.width).coerceIn(0f, 1f); seek(f); fraction = f }
                } else Modifier).semantics { if (playing) contentDescription = "Abgespielt ${(fraction * 100).toInt()} Prozent" },
                verticalAlignment = Alignment.CenterVertically) {
                bars.forEachIndexed { i, h ->
                    val done = playing && (i + 0.5f) / bars.size <= fraction
                    Box(Modifier.padding(horizontal = 1.dp).size(3.dp, h.dp).clip(RoundedCornerShape(2.dp))
                        .background(foreground.copy(alpha = if (!playing) 0.8f else if (done) 1f else 0.35f)))
                }
            }
            val seconds = left ?: info.optDouble("dauer", 0.0).toInt()
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
    val person = others.singleOrNull()?.takeIf { !isGroup }
    val picture = if (person != null) account.picture(person) else account.groupPicture(cid)
    // Olaf zu 8534fe11: a tap on the picture shows it big and whole
    var big by remember(cid) { mutableStateOf(false) }
    if (big && picture != null) {
        androidx.activity.compose.BackHandler { big = false }
        PhotoViewer(picture) { big = false }
        return
    }
    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.ime)).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { TextButton("Fertig", colors.blue, 600, onClick = onBack) }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(CircleShape).semantics { contentDescription = "Bild" }
                .clickable(enabled = picture != null || (isGroup && active)) { if (picture != null) big = true else group.picture() }) {
                Avatar(title, picture, 88.dp)
            }
            if (isGroup && active) BasicText("Bild ändern", style = style(15f, 400, colors.blue), modifier = Modifier.padding(top = 6.dp).clickable(onClick = group.picture))
            // Like Apple Contacts: my own picture of this person, only for me – „Zurücksetzen“ brings their shared one back
            if (person != null) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BasicText("Bild ändern", style = style(15f, 400, colors.blue), modifier = Modifier.clickable { group.contactPicture(person) })
                if (account.hasContactPicture(person))
                    BasicText("Zurücksetzen", style = style(15f, 400, colors.blue), modifier = Modifier.clickable { group.resetContactPicture(person) })
            }
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
        // Members only in a group – a single chat shows the person on top, like Messages (Michelle zu 8534fe11)
        if (isGroup) Section("${members.size} Personen") {
            members.forEachIndexed { i, member ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(account.display(member), account.picture(member), 32.dp)     // the same initials as everywhere else
                    BasicText(if (member == account.username) "Ich" else account.display(member), style = style(17f, 400, colors.label),
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
        MediaSections(account, cid, version, group)
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
    val contactPicture: (String) -> Unit = {},
    val resetContactPicture: (String) -> Unit = {},
    val openAttachment: (JSONObject) -> Unit = {},
    val openLink: (String) -> Unit = {},
)

/** Fotos, Links, Dokumente, Sprachnachrichten of this chat in the info, like the iPhone (43358d35): sent and received,
 *  newest first; one-time pictures never, secret ones only pixelated. A section shows only when it has something. */
@Composable
fun MediaSections(account: Account, cid: String, version: Int, group: GroupActions) {
    val colors = palette()
    val media = remember(cid, version) { runCatching { account.media(cid) }.getOrNull() } ?: return
    var allPhotos by remember(cid) { mutableStateOf(false) }
    val date = java.text.SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
    fun who(m: JSONObject) = if (m.optString("from") == account.username) "Du" else account.display(m.optString("from"))
    fun line(m: JSONObject) = "${who(m)} · ${date.format(java.util.Date(m.optLong("at") * 1000))}"
    if (media.photos.isNotEmpty()) Section("FOTOS") {
        val shown = if (allPhotos) media.photos else media.photos.take(8)
        shown.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { m ->
                    val info = m.getJSONObject("anhang")
                    val bitmap = remember(info.optString("id")) {
                        info.optString("vorschau").takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                            ?.let { if (info.optBoolean("geheim")) pixelated(it) else it }
                    }
                    Box(Modifier.weight(1f).aspectRatio(1f).background(colors.fill).clickable { group.openAttachment(info) }
                        .semantics { contentDescription = if (info.optBoolean("geheim")) "Geheimbild" else if (info.optString("art") == "video") "Video" else "Foto" },
                        contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (info.optBoolean("geheim")) Box(Modifier.fillMaxSize().background(Color(0x8C8E8E93)))
                        if (info.optString("art") == "video") GlyphIcon(Glyph.Play, Color.White, 20.dp)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (media.photos.size > 8) BasicText(if (allPhotos) "Weniger zeigen" else "Alle ${media.photos.size} zeigen", style = style(17f, 400, colors.blue),
            modifier = Modifier.fillMaxWidth().clickable { allPhotos = !allPhotos }.padding(horizontal = 16.dp, vertical = 11.dp))
    }
    @Composable
    fun rows(header: String, items: List<Pair<String, JSONObject>>, open: (Int) -> Unit) {
        if (items.isEmpty()) return
        Section(header) {
            items.take(20).forEachIndexed { index, (title, m) ->
                if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(colors.separator))
                Column(Modifier.fillMaxWidth().clickable { open(index) }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                    BasicText(title, style = style(17f, 400, colors.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(line(m), style = style(13f, 400, colors.secondary))
                }
            }
        }
    }
    rows("LINKS", media.links.map { (url, m) -> url.removePrefix("https://").removePrefix("http://") to m }) { group.openLink(media.links[it].first) }
    rows("DOKUMENTE", media.documents.map { it.getJSONObject("anhang").let { a -> "${a.optString("name")} · ${sizeText(a.optLong("groesse"))}" } to it }) {
        group.openAttachment(media.documents[it].getJSONObject("anhang")) }
    rows("SPRACHNACHRICHTEN", media.voice.map { m -> m.getJSONObject("anhang").optDouble("dauer", 0.0).toInt().let { "Sprachnachricht · ${it / 60}:${"%02d".format(it % 60)}" } to m }) {
        group.openAttachment(media.voice[it].getJSONObject("anhang")) }
}

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
                   onRemoveDevice: (String) -> Unit = {}, onScanDevice: () -> Unit = {},
                   lock: Boolean = false, lockAfter: Int = 0, lockPossible: Boolean = true, onLock: (Boolean) -> Unit = {}, onLockAfter: (Int) -> Unit = {}) {
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
        // 99eea3f7 (Olaf): off by default; unlocked with the phone's own screen lock, no password of its own
        var choosingLock by remember { mutableStateOf(false) }
        Section("APP-SPERRE", footer = if (!lockPossible) "Dafür braucht das Handy eine Displaysperre (PIN, Muster oder Passwort) – in den Android-Einstellungen einrichten."
            else "Entsperrt wird mit der Displaysperre des Handys oder dem Fingerabdruck. Solange die Sperre an ist, zeigen Mitteilungen keinen Inhalt und die App-Übersicht kein Bild.") {
            Row(Modifier.fillMaxWidth().toggleable(value = lock, enabled = lockPossible || lock, role = Role.Switch, onValueChange = onLock)
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Mit Displaysperre schützen", style = style(17f, 400, if (lockPossible || lock) colors.label else colors.tertiary), modifier = Modifier.weight(1f))
                Switch(lock)
            }
            if (lock) {
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
                Row(Modifier.fillMaxWidth().clickable { choosingLock = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Sperren", style = style(17f, 400, colors.label), modifier = Modifier.weight(1f))
                    BasicText(LOCK_TIMES.first { it.first == lockAfter }.second, style = style(17f, 400, colors.secondary))
                    GlyphIcon(Glyph.Chevron, colors.tertiary, 14.dp, Modifier.padding(start = 6.dp))
                }
            }
        }
        if (choosingLock) ChoiceSheet(LOCK_TIMES.map { it.second }, LOCK_TIMES.indexOfFirst { it.first == lockAfter },
            onChoose = { onLockAfter(LOCK_TIMES[it].first); choosingLock = false }, onCancel = { choosingLock = false })
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

val LOCK_TIMES = listOf(0 to "Sofort", 60 to "Nach 1 Minute", 900 to "Nach 15 Minuten", 3600 to "Nach 1 Stunde")

/** LiChat locked (99eea3f7): nothing of the chats shows; „Entsperren“ asks for the phone's screen lock – at once when it appears. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val colors = palette()
    LaunchedEffect(Unit) { onUnlock() }
    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.systemBars),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppIcon(72.dp)
        GlyphIcon(Glyph.Lock, colors.secondary, 22.dp, Modifier.padding(top = 24.dp))
        BasicText("LiChat ist gesperrt", style = style(22f, 600, colors.label), modifier = Modifier.padding(top = 8.dp))
        BasicText("Mit der Displaysperre deines Handys entsperren.", style = style(15f, 400, colors.secondary), modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.padding(top = 28.dp).clip(RoundedCornerShape(14.dp)).background(colors.blue).clickable(role = Role.Button, onClick = onUnlock)
            .padding(horizontal = 40.dp, vertical = 14.dp)) { BasicText("Entsperren", style = style(17f, 600, Color.White)) }
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
                suggestions: List<String> = emptyList(),
                onSignIn: (String, String, String, String, String) -> Unit) {
    val colors = palette()
    // Our own server as a default (Tante Erna types nothing) – any other one can be entered.
    var server by remember { mutableStateOf("https://exampleserver.lisoftware.de") }
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
        // ebc3d629: the name is taken – free ones to tap, then „Anmelden“ again
        var picked by remember(suggestions) { mutableStateOf<String?>(null) }
        if (suggestions.isNotEmpty() && picked == null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            suggestions.forEach { free ->
                BasicText(free, style = style(15f, 400, colors.blue), modifier = Modifier.clip(CircleShape).background(colors.cell)
                    .clickable(role = Role.Button) { name = free; nameTouched = true; picked = free }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
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

/** „… tippt“ as Olaf chose it (884f7c8a, variants 4 and 5 of the template page): alone a thought bubble – the wave in a
 *  grey capsule with two small circles at its lower left; in a group „X schreibt …“ over a smaller wave. The wave: each dot
 *  in turn darker and ~38 % bigger, peaks at 380/580/760 ms, a cycle of 1.84 s with a rest. With „Animationen entfernen“
 *  the dots breathe together (lighter, darker – no movement). */
@Composable
fun TypingBubble(group: Boolean = false, label: String = "") {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = palette()
    val still = remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    // Our own clock (frames, not the animator): it runs for the breathing too when the system's animations are off.
    var time by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        if (!TypingAnimation.running) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) androidx.compose.runtime.withFrameMillis { time = ((it - start) % 1840).toFloat() }
    }
    val capsule = if (dark) colors.theirs else Color(0xFFECECEC)
    val rest = if (dark) Color(0xFF6C6C72) else Color(0xFFB0B2B4)
    val peak = if (dark) Color(0xFFE6E6EA) else Color(0xFF606060)
    val dot = if (group) 6.dp else 9.dp
    fun strength(top: Float) = if (still) (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI.toFloat() * time / 1840f))
        else (1f - kotlin.math.abs(time - top) / 200f).coerceIn(0f, 1f)
    Column {
        if (group && label.isNotEmpty()) BasicText(label, style = style(11f, 400, colors.secondary), modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
        Box(Modifier.padding(start = if (group) 0.dp else 6.dp, bottom = if (group) 0.dp else 10.dp)) {
            if (!group) {
                // the thought bubble's two little circles at the lower left
                Box(Modifier.align(Alignment.BottomStart).offset(x = (-2).dp, y = 6.dp).size(12.dp).clip(CircleShape).background(capsule))
                Box(Modifier.align(Alignment.BottomStart).offset(x = (-7).dp, y = 13.dp).size(6.dp).clip(CircleShape).background(capsule))
            }
            Row(Modifier.clip(RoundedCornerShape(18.dp)).background(capsule).padding(horizontal = if (group) 10.dp else 12.dp, vertical = if (group) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (group) 4.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(380f, 580f, 760f).forEach { top ->
                    val power = strength(top)
                    val grow = if (still) 1f else 1f + 0.38f * power
                    Box(Modifier.size(dot).graphicsLayer { scaleX = grow; scaleY = grow }.clip(CircleShape)
                        .background(androidx.compose.ui.graphics.lerp(rest, peak, power)))
                }
            }
        }
    }
}

/** „schreibt …“ in the chat list: three small dots in the accent blue as a wave (breathing with „Animationen entfernen“). */
@Composable
fun ListTyping(label: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = palette()
    val still = remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    var time by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        if (!TypingAnimation.running) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) androidx.compose.runtime.withFrameMillis { time = ((it - start) % 1840).toFloat() }
    }
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(380f, 580f, 760f).forEach { top ->
                val power = if (still) 0.5f - 0.5f * kotlin.math.cos(2 * Math.PI.toFloat() * time / 1840f) else (1f - kotlin.math.abs(time - top) / 200f).coerceIn(0f, 1f)
                val lift = if (still) 0f else -3f * power
                Box(Modifier.size(6.dp).graphicsLayer { translationY = lift * density }.clip(CircleShape)
                    .background(colors.blue.copy(alpha = 0.45f + 0.55f * power)))
            }
        }
        BasicText(label, style = style(15f, 400, colors.blue), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp))
    }
}

/** Tests switch the endless frame clock off (it would drive their virtual clock past the 6 s of a „tippt“). */
object TypingAnimation { @Volatile var running = true }

/** „Anna schreibt …“, „Anna und Bernd schreiben …“, „Anna, Bernd und Erna schreiben …“ */
fun typingLabel(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "${names[0]} schreibt …"
    else -> names.dropLast(1).joinToString(", ") + " und ${names.last()} schreiben …"
}

/** A GIF playing (Android 9+): ImageDecoder's AnimatedImageDrawable in an ImageView, cropped like a photo; it stands still
 *  when the system's animations are off (a tap opens it in the viewer). */
@androidx.annotation.RequiresApi(28)
@Composable
fun GifView(bytes: ByteArray, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val still = remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val drawable = remember(bytes) {
        runCatching { android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) }.getOrNull()
    }
    androidx.compose.ui.viewinterop.AndroidView({ c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } }, modifier,
        update = { view ->
            view.setImageDrawable(drawable)
            (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.let { if (still) it.stop() else it.start() }
        })
}
