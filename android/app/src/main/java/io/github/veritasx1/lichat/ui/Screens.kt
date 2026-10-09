package io.github.veritasx1.lichat.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
             onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onUnrestrict: () -> Unit) {
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
        account.title(cid).lowercase().contains(q) || messages(account.chat(cid)).any { it.optString("text").lowercase().contains(q) }
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
                    Avatar(title, if (others.size == 1) account.picture(others[0]) else null, 44.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(title, style = style(17f, 600, colors.label), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            last?.let { BasicText(whenShort(it.optLong("at")), style = style(15f, 400, colors.secondary)) }
                            GlyphIcon(Glyph.Chevron, colors.tertiary, 14.dp, Modifier.padding(start = 4.dp))
                        }
                        val preview = when {
                            last == null -> "Noch keine Nachrichten"
                            last.optBoolean("system") -> last.optString("text")
                            last.optString("from") == account.username -> "Du: " + last.optString("text")
                            else -> last.optString("text")
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

@Composable
fun ChatScreen(account: Account, cid: String, version: Int, problem: String?, onBack: () -> Unit, onInfo: () -> Unit,
               onSend: (String) -> Unit) {
    val colors = palette()
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
                Avatar(title, if (others.size == 1) account.picture(others[0]) else null, 36.dp)
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
                Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    BasicText(m.optString("text"), style = style(17f, 400, if (mine) Color.White else colors.label),
                        modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp)).background(if (mine) colors.blue else colors.theirs)
                            .padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            chat.optString("removed").takeIf { it.isNotEmpty() }?.let { by ->
                item { BasicText("$by hat dich aus der Unterhaltung entfernt.", style = style(11f, 400, colors.secondary).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) }
            }
        }
        if (problem != null) BasicText(problem, style = style(12f, 400, colors.red), modifier = Modifier.padding(horizontal = 16.dp))
        if (chat.optString("removed").isEmpty()) InputBar(onSend)
        else Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/** The input line: a rounded field „LiChat“ (like „iMessage“) and the blue send arrow once there is text. */
@Composable
fun InputBar(onSend: (String) -> Unit) {
    val colors = palette()
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(colors.background)
            .border(0.5.dp, colors.separator, RoundedCornerShape(18.dp))) {
            BasicTextField(text, { text = it }, textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 40.dp, top = 8.dp, bottom = 8.dp).semantics { contentDescription = "Nachricht" },
                decorationBox = { inner -> Box { if (text.isEmpty()) BasicText("LiChat", style = style(17f, 400, colors.tertiary)); inner() } })
            if (text.isNotBlank()) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(colors.blue)
                .clickable(role = Role.Button) { onSend(text); text = "" }.semantics { contentDescription = "Senden" },
                contentAlignment = Alignment.Center) { GlyphIcon(Glyph.ArrowUp, Color.White, 18.dp) }
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
        InputBar { text -> onSend(to.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }, text) }
    }
}

// ---------------------------------------------------------------------------------------------- details

@Composable
fun Details(account: Account, cid: String, version: Int, onBack: () -> Unit, onRemove: (String) -> Unit) {
    val colors = palette()
    val members = remember(version, cid) { runCatching { account.members(cid) }.getOrDefault(emptyList()) }
    val code = remember(version, cid) { runCatching { account.safetyCode(cid) }.getOrDefault("") }
    val title = account.title(cid)
    val others = account.others(cid)
    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { TextButton("Fertig", colors.blue, 600, onClick = onBack) }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(title, if (others.size == 1) account.picture(others[0]) else null, 88.dp)
            BasicText(title, style = style(22f, 600, colors.label), modifier = Modifier.padding(top = 10.dp, bottom = 20.dp))
        }
        Section("${members.size} Personen") {
            members.forEachIndexed { i, name ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name, account.picture(name), 32.dp)
                    BasicText(if (name == account.username) "$name (ich)" else name, style = style(17f, 400, colors.label),
                        modifier = Modifier.weight(1f).padding(start = 12.dp))
                    // In a group someone can be taken out (test plan S2).
                    if (members.size > 2 && name != account.username) TextButton("Entfernen", colors.red) { onRemove(name) }
                }
                if (i < members.lastIndex) Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))
            }
        }
        if (code.isNotEmpty()) Section("SICHERHEITSCODE", footer = "Vergleicht den Code auf allen Geräten – gleich heißt: niemand liest mit.") {
            BasicText(code.split(" ").chunked(4).joinToString("\n") { it.joinToString("  ") }, style = style(20f, 500, colors.label).copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "Sicherheitscode $code" })
        }
    }
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
                   onUnrestrict: () -> Unit, onBack: () -> Unit) {
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
        Section("MITTEILUNGEN", footer = "Aus: Die Mitteilung sagt nur „Neue Nachricht“. An: Absender und Text stehen auch auf dem Sperrbildschirm.") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Absender und Text zeigen", style = style(17f, 400, colors.label), modifier = Modifier.weight(1f))
                Switch(preview, onPreview)
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

/** Apple's switch: green when on, 51 × 31 dp (Li-Gestaltung). */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = palette()
    Box(Modifier.size(51.dp, 31.dp).clip(CircleShape).background(if (on) colors.green else colors.fill)
        .clickable(role = Role.Switch) { onChange(!on) }.padding(2.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(27.dp).clip(CircleShape).background(Color.White))
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
        Avatar("Li Chat", null, 88.dp)
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
