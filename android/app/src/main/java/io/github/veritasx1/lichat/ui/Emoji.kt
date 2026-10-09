package io.github.veritasx1.lichat.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Olaf 09.10. (20eb5cc5): „+“ opened the letter keyboard and the phone's emoji keyboard was „seltsam angeordnet“ – our
 *  own picker like Messages: recently used, categories, a search over German words (all on the phone, nothing sent). */
class EmojiCategory(val name: String, val symbol: String, val emojis: List<Pair<String, String>>)

private fun list(vararg pairs: String) = pairs.toList().chunked(2).map { it[0] to it[1] }

val EMOJI_CATEGORIES = listOf(
    EmojiCategory("Smileys", "😀", list(
        "😀", "grinsen lachen freude", "😃", "lachen freude", "😄", "lachen augen", "😁", "strahlen grinsen", "😆", "lachen zugekniffen",
        "😅", "schweiß lachen erleichtert", "😂", "tränen lachen haha", "🤣", "kugeln lachen", "🙂", "lächeln", "🙃", "kopfüber ironie",
        "😉", "zwinkern", "😊", "lächeln rot", "😇", "engel unschuldig", "🥰", "verliebt herzen", "😍", "herzaugen verliebt",
        "🤩", "begeistert sterne", "😘", "kuss", "😗", "kuss", "😚", "kuss", "😋", "lecker", "😛", "zunge", "😜", "zunge zwinkern",
        "🤪", "verrückt", "😝", "zunge", "🤗", "umarmung", "🤭", "hand vor mund kichern", "🤫", "psst leise", "🤔", "nachdenken",
        "🤐", "mund zu geheim", "🤨", "skeptisch", "😐", "neutral", "😑", "ausdruckslos", "😶", "sprachlos", "😏", "grinsen schief",
        "😒", "genervt", "🙄", "augen rollen", "😬", "grimasse peinlich", "😌", "erleichtert", "😔", "nachdenklich traurig",
        "😪", "müde schläfrig", "😴", "schlafen", "😷", "maske krank", "🤒", "fieber krank", "🤢", "übel", "🤮", "kotzen",
        "🥵", "heiß schwitzen", "🥶", "kalt frieren", "🥴", "beschwipst", "😵", "schwindelig", "🤯", "kopf explodiert",
        "🥳", "party feiern", "😎", "cool sonnenbrille", "🤓", "streber brille", "😕", "verwirrt", "😟", "besorgt", "🙁", "traurig",
        "😮", "staunen", "😯", "überrascht", "😲", "erstaunt", "😳", "rot peinlich", "🥺", "bitte bitte", "😢", "weinen träne",
        "😭", "heulen", "😱", "schrei angst", "😖", "verzweifelt", "😣", "durchhalten", "😞", "enttäuscht", "😓", "schweiß",
        "😩", "erschöpft", "😫", "müde", "🥱", "gähnen", "😤", "schnauben stolz", "😡", "wütend", "😠", "ärger", "🤬", "fluchen",
        "😈", "teufel", "💀", "totenkopf", "💩", "kacke", "🤡", "clown", "👻", "gespenst", "👽", "außerirdischer", "🤖", "roboter",
        "😺", "katze lächeln", "😹", "katze lachen", "😻", "katze verliebt",
    )),
    EmojiCategory("Gesten", "👍", list(
        "👍", "daumen hoch gut", "👎", "daumen runter schlecht", "👌", "ok", "✌️", "frieden victory", "🤞", "daumen drücken",
        "🤟", "liebe dich", "🤘", "rock", "🤙", "ruf an", "👈", "links", "👉", "rechts", "👆", "oben", "👇", "unten", "☝️", "zeigefinger",
        "✋", "hand stopp", "👋", "winken hallo tschüss", "🤚", "hand", "🖐️", "hand", "🖖", "vulkanier", "👏", "klatschen applaus",
        "🙌", "hände hoch jubel", "👐", "offene hände", "🤲", "hände", "🤝", "handschlag", "🙏", "bitte danke beten",
        "✍️", "schreiben", "💪", "stark muskel", "🫶", "herz hände", "🫡", "salut", "🤷", "achselzucken weiß nicht",
        "🤦", "facepalm", "🙋", "melden hallo", "🙇", "verbeugen", "💁", "info", "🙆", "ok gut", "🙅", "nein stopp",
        "👀", "augen schauen", "👂", "ohr hören", "👃", "nase", "👄", "mund", "💋", "kuss",
    )),
    EmojiCategory("Herzen", "❤️", list(
        "❤️", "herz liebe", "🧡", "herz orange", "💛", "herz gelb", "💚", "herz grün", "💙", "herz blau", "💜", "herz lila",
        "🖤", "herz schwarz", "🤍", "herz weiß", "🤎", "herz braun", "💔", "gebrochenes herz", "❣️", "herz ausruf", "💕", "zwei herzen",
        "💞", "herzen kreisen", "💓", "herz schlagen", "💗", "herz wachsen", "💖", "herz glitzer", "💘", "herz pfeil", "💝", "herz geschenk",
        "💟", "herz", "💌", "liebesbrief", "😻", "katze verliebt", "🌹", "rose",
    )),
    EmojiCategory("Tiere & Natur", "🐶", list(
        "🐶", "hund", "🐱", "katze", "🐭", "maus", "🐹", "hamster", "🐰", "hase", "🦊", "fuchs", "🐻", "bär", "🐼", "panda",
        "🐨", "koala", "🐯", "tiger", "🦁", "löwe", "🐮", "kuh", "🐷", "schwein", "🐸", "frosch", "🐵", "affe", "🐔", "huhn",
        "🐧", "pinguin", "🐦", "vogel", "🦆", "ente", "🦉", "eule", "🐴", "pferd", "🦄", "einhorn", "🐝", "biene", "🦋", "schmetterling",
        "🐌", "schnecke", "🐞", "marienkäfer", "🐢", "schildkröte", "🐍", "schlange", "🐙", "krake", "🐟", "fisch", "🐬", "delfin",
        "🐳", "wal", "🦈", "hai", "🐘", "elefant", "🦒", "giraffe", "🐿️", "eichhörnchen", "🌸", "blüte kirsch", "🌻", "sonnenblume",
        "🌷", "tulpe", "🌱", "pflanze keim", "🌲", "tanne baum", "🍀", "kleeblatt glück", "🍁", "ahorn herbst", "🍂", "laub herbst",
        "🌈", "regenbogen", "☀️", "sonne", "⛅", "wolken sonne", "🌧️", "regen", "⛈️", "gewitter", "❄️", "schnee", "🌙", "mond",
        "⭐", "stern", "🔥", "feuer", "💧", "tropfen", "🌊", "welle meer",
    )),
    EmojiCategory("Essen", "🍕", list(
        "🍏", "apfel grün", "🍎", "apfel", "🍐", "birne", "🍊", "orange", "🍋", "zitrone", "🍌", "banane", "🍉", "melone",
        "🍇", "trauben", "🍓", "erdbeere", "🍒", "kirsche", "🍑", "pfirsich", "🥭", "mango", "🍍", "ananas", "🥥", "kokos",
        "🥝", "kiwi", "🍅", "tomate", "🥑", "avocado", "🥕", "karotte", "🌽", "mais", "🥔", "kartoffel", "🥨", "brezel",
        "🥐", "croissant", "🍞", "brot", "🧀", "käse", "🥚", "ei", "🍳", "spiegelei", "🥓", "speck", "🍔", "burger",
        "🍟", "pommes", "🍕", "pizza", "🌭", "würstchen", "🥗", "salat", "🍝", "nudeln", "🍜", "ramen suppe", "🍣", "sushi",
        "🍰", "kuchen", "🎂", "geburtstagskuchen", "🧁", "cupcake", "🍪", "keks", "🍫", "schokolade", "🍦", "eis", "🍩", "donut",
        "☕", "kaffee", "🍵", "tee", "🍺", "bier", "🍻", "prost", "🍷", "wein", "🥂", "anstoßen sekt", "🍸", "cocktail", "🧃", "saft",
    )),
    EmojiCategory("Aktivität", "⚽", list(
        "⚽", "fußball", "🏀", "basketball", "🏈", "football", "⚾", "baseball", "🎾", "tennis", "🏐", "volleyball", "🏓", "tischtennis",
        "🏸", "badminton", "⛳", "golf", "🚴", "radfahren", "🏃", "laufen joggen", "🏊", "schwimmen", "⛷️", "ski", "🏂", "snowboard",
        "🧘", "yoga", "🏆", "pokal sieg", "🥇", "gold erster", "🎉", "party konfetti", "🎊", "konfetti", "🎈", "luftballon",
        "🎁", "geschenk", "🎄", "weihnachten", "🎃", "kürbis halloween", "🎶", "musik noten", "🎸", "gitarre", "🎹", "klavier",
        "🎤", "mikrofon singen", "🎧", "kopfhörer", "🎬", "film", "📚", "bücher lesen", "🎨", "malen kunst", "🎮", "spielen",
        "🧩", "puzzle", "♟️", "schach",
    )),
    EmojiCategory("Reisen", "🚗", list(
        "🚗", "auto", "🚕", "taxi", "🚌", "bus", "🚑", "krankenwagen", "🚒", "feuerwehr", "🚲", "fahrrad", "🛴", "roller",
        "🚂", "zug lok", "🚆", "zug", "✈️", "flugzeug", "🚀", "rakete", "⛵", "segelboot", "🚢", "schiff", "⚓", "anker",
        "🏠", "haus", "🏡", "haus garten", "🏢", "büro", "🏥", "krankenhaus", "🏫", "schule", "⛪", "kirche", "🏰", "burg",
        "🗼", "turm", "🗽", "freiheitsstatue", "🏖️", "strand urlaub", "🏝️", "insel", "⛰️", "berg", "🏕️", "zelten camping",
        "🌍", "erde welt", "🗺️", "karte", "📍", "ort", "⏰", "wecker", "⌛", "zeit sanduhr",
    )),
    EmojiCategory("Objekte", "💡", list(
        "📱", "handy", "💻", "laptop", "⌨️", "tastatur", "🖥️", "bildschirm", "📷", "kamera", "📞", "telefon", "📺", "fernseher",
        "💡", "idee glühbirne", "🔦", "taschenlampe", "🕯️", "kerze", "💰", "geld", "💳", "karte", "✉️", "brief", "📦", "paket",
        "📝", "notiz", "📅", "kalender", "📌", "pin", "📎", "büroklammer", "✂️", "schere", "🔒", "schloss sicher", "🔑", "schlüssel",
        "🔨", "hammer", "🛠️", "werkzeug", "⚙️", "zahnrad", "💊", "tablette", "🩺", "arzt", "🛏️", "bett", "🛒", "einkaufen",
        "🎀", "schleife", "👓", "brille", "👕", "tshirt", "👗", "kleid", "👟", "schuh", "🎓", "abschluss",
    )),
    EmojiCategory("Symbole", "✅", list(
        "✅", "erledigt haken", "☑️", "haken", "✔️", "haken", "❌", "falsch kreuz", "❎", "kreuz", "‼️", "ausrufezeichen", "❗", "ausrufezeichen",
        "❓", "fragezeichen", "❔", "frage", "💯", "hundert", "🆗", "ok", "🆕", "neu", "🆒", "cool", "⚠️", "achtung warnung",
        "🚫", "verboten", "⛔", "stopp", "♻️", "recycling", "✨", "glitzer funkeln", "💤", "schlafen", "💬", "sprechblase",
        "💭", "gedanke", "🔔", "glocke", "🔕", "stumm", "➕", "plus", "➖", "minus", "➡️", "pfeil rechts", "⬅️", "pfeil links",
        "⬆️", "pfeil hoch", "⬇️", "pfeil runter", "🔄", "wiederholen", "🎵", "musik", "™️", "marke", "©️", "copyright",
        "🔴", "rot", "🟢", "grün", "🔵", "blau", "⚪", "weiß", "⚫", "schwarz", "1️⃣", "eins", "2️⃣", "zwei", "3️⃣", "drei",
    )),
    EmojiCategory("Flaggen", "🏳️", list(
        "🇩🇪", "deutschland", "🇦🇹", "österreich", "🇨🇭", "schweiz", "🇫🇷", "frankreich", "🇮🇹", "italien", "🇪🇸", "spanien",
        "🇬🇧", "england großbritannien", "🇺🇸", "usa amerika", "🇳🇱", "niederlande", "🇵🇱", "polen", "🇩🇰", "dänemark", "🇸🇪", "schweden",
        "🇳🇴", "norwegen", "🇵🇹", "portugal", "🇬🇷", "griechenland", "🇹🇷", "türkei", "🇺🇦", "ukraine", "🇪🇺", "europa",
        "🏳️‍🌈", "regenbogen stolz pride", "🏁", "zielflagge",
    )),
)

/** The last emojis chosen (on this phone only), newest first. */
object RecentEmojis {
    private const val KEY = "zuletzt_emojis"
    fun get(context: Context): List<String> =
        context.getSharedPreferences("lichat_emoji", Context.MODE_PRIVATE).getString(KEY, "")!!.split(" ").filter { it.isNotEmpty() }

    fun add(context: Context, emoji: String) {
        val list = (listOf(emoji) + get(context).filter { it != emoji }).take(16)
        context.getSharedPreferences("lichat_emoji", Context.MODE_PRIVATE).edit().putString(KEY, list.joinToString(" ")).apply()
    }
}

/** The picker: search, „Zuletzt benutzt“, categories as tabs, a grid of 8 per row – a tap is the reaction. */
@Composable
fun EmojiPicker(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = palette()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(-1) }       // -1 = all, in order
    val recent = remember { RecentEmojis.get(context) }
    fun pick(emoji: String) { RecentEmojis.add(context, emoji); onPick(emoji) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(colors.cell).clickable(enabled = false) {}.padding(10.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.fill).padding(horizontal = 10.dp, vertical = 8.dp)) {
            BasicTextField(query, { query = it }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Done), textStyle = style(17f, 400, colors.label), cursorBrush = SolidColor(colors.blue),
                // „Fertig“ closes the keyboard and leaves the picker open (like iOS)
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Emoji suchen" },
                decorationBox = { inner -> if (query.isEmpty()) BasicText("Emoji suchen", style = style(17f, 400, colors.tertiary)); inner() })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            (listOf(-1 to "🕘") + EMOJI_CATEGORIES.mapIndexed { i, c -> i to c.symbol }).forEach { (i, symbol) ->
                BasicText(symbol, style = style(22f).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(if (category == i) colors.blue.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { category = i; query = "" }.padding(top = 6.dp)
                        .semantics { contentDescription = if (i == -1) "Zuletzt benutzt" else EMOJI_CATEGORIES[i].name })
            }
        }
        val wanted = query.trim().lowercase()
        val sections: List<Pair<String, List<String>>> = when {
            wanted.isNotEmpty() -> listOf("Treffer" to EMOJI_CATEGORIES.flatMap { it.emojis }.filter { (_, words) -> words.split(" ").any { it.startsWith(wanted) } }.map { it.first }.distinct())
            category >= 0 -> listOf(EMOJI_CATEGORIES[category].name to EMOJI_CATEGORIES[category].emojis.map { it.first })
            else -> listOfNotNull(("Zuletzt benutzt" to recent).takeIf { recent.isNotEmpty() }) + EMOJI_CATEGORIES.map { it.name to it.emojis.map { e -> e.first } }
        }
        // Up to 260 dp; with the keyboard open as much as fits (at least two rows of results stay visible).
        LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 96.dp, max = 260.dp)) {
            for ((title, emojis) in sections) {
                item(span = { GridItemSpan(8) }) { BasicText(title.uppercase(), style = style(12f, 600, colors.secondary), modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) }
                if (emojis.isEmpty()) item(span = { GridItemSpan(8) }) { BasicText("Nichts gefunden", style = style(15f, 400, colors.secondary), modifier = Modifier.padding(8.dp)) }
                items(emojis) { emoji ->
                    BasicText(emoji, style = style(26f).copy(textAlign = TextAlign.Center),
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable { pick(emoji) }.padding(top = 4.dp)
                            .semantics { contentDescription = "Mit ${tapbackWord(emoji)} reagieren" })
                }
            }
        }
    }
}
