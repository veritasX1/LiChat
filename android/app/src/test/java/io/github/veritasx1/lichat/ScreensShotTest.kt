package io.github.veritasx1.lichat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.veritasx1.lichat.core.Account
import io.github.veritasx1.lichat.core.KeyVault
import io.github.veritasx1.lichat.ui.AttachmentActions
import io.github.veritasx1.lichat.ui.ChatList
import io.github.veritasx1.lichat.ui.ChatScreen
import io.github.veritasx1.lichat.ui.Details
import io.github.veritasx1.lichat.ui.Explain
import io.github.veritasx1.lichat.ui.LocalPalette
import io.github.veritasx1.lichat.ui.NewMessage
import io.github.veritasx1.lichat.ui.Palette
import io.github.veritasx1.lichat.ui.SettingsScreen
import io.github.veritasx1.lichat.ui.SetupScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** Pictures of every screen (iOS 18 Messages), light and dark: ./gradlew recordRoborazziDebug -Pshots=/folder */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensShotTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("lichat.shots")
    private lateinit var server: Process
    private val root: File = Files.createTempDirectory("lichat-shots").toFile()
    private val repo = File(System.getProperty("lichat.server")).parentFile
    private lateinit var anna: Account
    private lateinit var cid: String
    private lateinit var group: String
    private lateinit var bernd: Account

    @Before
    fun conversations() {
        io.github.veritasx1.lichat.ui.TypingAnimation.running = false
        val script = """
import sys
sys.path.insert(0, "${File(repo, "server").absolutePath}")
from socketserver import ThreadingMixIn
from wsgiref.simple_server import WSGIServer, WSGIRequestHandler, make_server
from lichat_server.app import create_app
class T(ThreadingMixIn, WSGIServer):
    daemon_threads = True
class Q(WSGIRequestHandler):
    def log_message(self, *a): pass
app = create_app("${File(root, "server").absolutePath}")
httpd = make_server("127.0.0.1", 0, app, server_class=T, handler_class=Q)
print(httpd.server_port, *[app.store.create_invite() for _ in range(4)], flush=True)
httpd.serve_forever()
"""
        server = ProcessBuilder("python3", "-c", script).redirectErrorStream(true).start()
        val first = server.inputStream.bufferedReader().readLine().split(" ")
        val url = "http://127.0.0.1:${first[0]}"
        fun person(name: String, invite: String) = File(root, name).let { Account.register(it, url, invite, name, KeyVault.Plain.create(it)) }
        anna = person("anna", first[1]); bernd = person("bernd", first[2]); val erna = person("erna", first[3])
        cid = bernd.start(listOf("anna"))
        bernd.send(cid, "Hallo Anna! Kommst du Samstag zum Grillen?")
        anna.fetch()
        anna.send(cid, "Ja, gern! Ich bringe Kartoffelsalat mit.")
        bernd.fetch()
        bernd.fetch()
        val salad = bernd.chat(cid).getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }.getString("mid")
        bernd.send(cid, "Super, bis dann 🙂", replyTo = salad)
        bernd.react(cid, salad, "❤️")
        bernd.sendAttachment(cid, ByteArray(48_000), "Einladung.pdf", "application/pdf", "datei")
        bernd.sendAttachment(cid, byteArrayOf(0), "Sprachnachricht.m4a", "audio/mp4", "sprache", durationS = 7.0)
        val picture = android.graphics.Bitmap.createBitmap(1200, 900, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF4FA36B.toInt()) }
        val raw = java.io.ByteArrayOutputStream().also { picture.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val (jpeg, preview) = io.github.veritasx1.lichat.core.Pictures.photo(raw)
        bernd.sendAttachment(cid, jpeg, "garten.jpg", "image/jpeg", "bild", preview = preview)
        anna.fetch()
        group = anna.start(listOf("bernd", "erna"), "Grillabend")
        anna.send(group, "Wer bringt Getränke mit?")
        erna.fetch(); erna.send(group, "Ich nehme Apfelschorle mit")
        anna.fetch()
    }

    @After
    fun stop() {
        server.destroy()
        root.deleteRecursively()
    }

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalPalette provides Palette(dark)) { content() } }
        compose.waitForIdle()
        if (out != null) captureScreenRoboImage("$out/$name.png")
    }

    @Test
    fun list() = shot("1-liste") { ChatList(anna, 0, mapOf(group to 1), null, false, {}, {}, {}, {}) }

    /** Michelle 24e8f89c: notifications refused – said plainly in the list and in the settings. */
    @Test
    fun notificationsOff() {
        shot("1b-mitteilungen-aus") { ChatList(anna, 0, emptyMap(), null, true, {}, {}, {}, {}, notifying = false) }
        compose.onNodeWithText("Mitteilungen sind aus", substring = true).assertExists()
    }

    @Test
    fun notificationsOffSettings() {
        shot("6b-einstellungen-mitteilungen-aus") { SettingsScreen(anna, false, true, {}, {}, {}, {}, notifying = false) }
        compose.onNodeWithText("Android zeigt für LiChat keine Mitteilungen", substring = true).assertExists()
    }

    /** Card 884f7c8a / 0c01f599: Bernd types – „•••“ at the very bottom, in view (the list is anchored at its end). */
    /** d54175ed: in a group „@“ suggests the members, a tap writes „@name “. */
    @Test
    fun mentionSuggestions() {
        var sent = ""
        shot("3b-erwaehnen") { ChatScreen(anna, group, 0, null, {}, {}, { text, _ -> sent = text }) }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Danke @er")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("erna erwähnen")).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction().and(androidx.compose.ui.test.hasText("Danke @erna "))).assertExists()
    }

    /** 4ceca163: „Bearbeitet“ under a corrected bubble (a tap shows the earlier versions), a grey line for one called back,
     *  „Bearbeiten“ and „Senden widerrufen“ in the menu of a fresh message of mine. */
    @Test
    fun editedAndUnsent() {
        anna.send(cid, "Ich bringe Kartoffelsalat")
        val list = anna.chat(cid).getJSONArray("messages")
        val salad = list.getJSONObject(list.length() - 1)
        anna.edit(cid, salad.getString("mid"), "Ich bringe Nudelsalat")
        anna.send(cid, "Ups")
        anna.unsend(cid, list.getJSONObject(list.length() - 1).getString("mid"))
        shot("2h-bearbeitet") { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }
        compose.onNodeWithText("Du hast eine Nachricht widerrufen.").assertExists()
        compose.onNodeWithText("Bearbeitet").performClick()
        compose.onNodeWithText("Ich bringe Kartoffelsalat").assertExists()
        compose.onNodeWithText("Ich bringe Nudelsalat").performTouchInput { longClick() }
        compose.onNodeWithText("Bearbeiten").assertExists()
        compose.onNodeWithText("Senden widerrufen").assertExists()
    }

    /** „+“ (20eb5cc5): our own emoji picker – a German word finds it, a tap is the reaction; categories too. */
    @Test
    fun anyEmoji() {
        var reacted = ""
        val m = anna.chat(cid).getJSONArray("messages").getJSONObject(1)
        compose.setContent { CompositionLocalProvider(LocalPalette provides Palette(false)) { io.github.veritasx1.lichat.ui.MessageMenu(m, anna, true, null, {}, { reacted = it }, {}, {}, {}) } }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Anderes Emoji")).performClick()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Emoji suchen")).performTextInput("einhorn")
        compose.waitForIdle()
        if (out != null) captureScreenRoboImage("$out/2g-emoji-suche.png")
        compose.onNodeWithText("🦄").performClick()
        org.junit.Assert.assertEquals("🦄", reacted)
    }

    @Test
    fun emojiCategories() {
        var reacted = ""
        shot("2g-emoji") { io.github.veritasx1.lichat.ui.EmojiPicker({ reacted = it }) }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Tiere & Natur")).performClick()
        compose.onAllNodesWithText("🐶").fetchSemanticsNodes().size.let { org.junit.Assert.assertEquals(2, it) }   // tab + grid
        compose.onAllNodesWithText("🐶")[1].performClick()
        org.junit.Assert.assertEquals("🐶", reacted)
    }

    /** 864041bf: a voice message paused at 1 of 4 s – ▶ again, the wave a quarter full, 0:03 left. */
    @Test
    fun voicePaused() {
        val info = org.json.JSONObject().put("id", "v1").put("art", "sprache").put("dauer", 4.0).put("name", "Sprachnachricht.m4a")
        shot("2k-sprache") {
            io.github.veritasx1.lichat.ui.AttachmentBubble(info, true, playing = true, paused = true, progress = { 1000 to 4000 }) {}
        }
        compose.onNodeWithText("0:03").assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Abgespielt 25 Prozent")).assertExists()
    }

    /** 18cfe3d2: a wrapped message's bubble is only as wide as its longest line – no blue space on the right; links tappable. */
    @Test
    fun wrappedBubbleIsTight() {
        anna.send(cid, "für Gruppen wie du\nvorgeschlagen hast")
        anna.send(cid, "Mehr auf www.lisoftware.de")
        shot("2j-umbruch") { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }
        val bubble = compose.onNodeWithText("für Gruppen wie du\nvorgeschlagen hast").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("schmal: ${bubble.width}", bubble.width < 220f * 3)
        val link = compose.onNodeWithText("Mehr auf www.lisoftware.de").fetchSemanticsNode()
        val text = link.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first()
        org.junit.Assert.assertEquals("https://www.lisoftware.de",
            (text.getLinkAnnotations(0, text.length).single().item as androidx.compose.ui.text.LinkAnnotation.Url).url)
    }

    /** 20eb5cc5: a long message lifted for its menu is cut to six lines, the original hidden. */
    @Test
    fun longBubbleLifted() {
        anna.send(cid, (1..40).joinToString(" ") { "Zeile $it mit etwas mehr Text damit sie umbricht" })
        val list = anna.chat(cid).getJSONArray("messages")
        val long = list.getJSONObject(list.length() - 1).getString("text")
        shot("2i-lang") { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }
        compose.onNodeWithText(long).performTouchInput { longClick(androidx.compose.ui.geometry.Offset(centerX, bottom - 60f)) }   // taller than the screen
        compose.waitForIdle()
        if (out != null) captureScreenRoboImage("$out/2i-lang-menue.png")
        // The lifted text sits inside the menu's merged node – the unmerged tree shows each text alone.
        val lifted = compose.onAllNodesWithText(long, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.minBy { it.height }
        org.junit.Assert.assertTrue("höchstens ~6 Zeilen: ${lifted.height}", lifted.height < 6.5f * 22 * 3)
        compose.onNodeWithText("Antworten").assertExists()
    }

    /** 206118bf / 1f800b4f: pinned on top as big pictures; „Filter“ offers Messages' choices; long press offers the actions. */
    @Test
    fun pinnedAndFilter() {
        anna.pin(cid, true)
        var action = ""
        shot("1c-angeheftet") { ChatList(anna, 0, emptyMap(), null, true, {}, {}, {}, {}, onAction = { _, a -> action = a }) }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("bernd, angeheftet", substring = true)).assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("bernd, angeheftet", substring = true)).performTouchInput { longClick() }
        compose.onNodeWithText("Nicht mehr anheften").performClick()
        org.junit.Assert.assertEquals("loesen", action)
        compose.onNodeWithText("Filter").performClick()
        compose.onNodeWithText("Zuletzt gelöscht").assertExists()
        compose.onNodeWithText("Ungelesene Nachrichten").performClick()
        compose.onNodeWithText("Ungelesene Nachrichten").assertExists()      // the title says which filter is on
    }

    @Test
    fun detailsReceiptsAndBlock() {
        var receipts: Boolean? = null; var blocked = ""
        shot("4b-info-einzeln") { Details(anna, cid, 0, {}, {}, io.github.veritasx1.lichat.ui.GroupActions(receipts = { receipts = it }, block = { p, _ -> blocked = p })) }
        compose.onNodeWithText("Lesebestätigungen senden").performScrollTo().performClick()
        org.junit.Assert.assertEquals(true, receipts)
        compose.onNodeWithText("bernd blockieren").performScrollTo().performClick()
        org.junit.Assert.assertEquals("bernd", blocked)
    }

    /** Olaf zu 8534fe11: the other's picture on top, a tap shows it big, „Bild ändern“ only for me, „Zurücksetzen“. */
    @Test
    fun detailsContactPicture() {
        var changed = ""; var reset = ""
        val jpeg = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        anna.setContactPicture("bernd", jpeg)
        val actions = io.github.veritasx1.lichat.ui.GroupActions(contactPicture = { changed = it }, resetContactPicture = { reset = it })
        shot("4c-info-kontaktbild") { Details(anna, cid, 0, {}, {}, actions) }
        compose.onNodeWithText("Bild ändern").performClick()
        org.junit.Assert.assertEquals("bernd", changed)
        compose.onNodeWithText("Zurücksetzen").performClick()
        org.junit.Assert.assertEquals("bernd", reset)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Bild")).performClick()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Foto, tippen zum Schließen")).assertIsDisplayed()
        anna.setContactPicture("bernd", null)
    }

    private fun testJpeg(color: Int, w: Int = 400, h: Int = 300) = java.io.ByteArrayOutputStream().also { out ->
        android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            android.graphics.Canvas(this).drawCircle(w / 2f, h / 2f, h / 3f, android.graphics.Paint().apply { this.color = 0xFFFFFFFF.toInt() })
        }.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out) }.toByteArray()

    /** fe9f2029 + f9e3f055: a one-time picture shows no preview, a tap asks to open it; a secret one is pixelated until tapped. */
    @Test
    fun onceAndSecretBubbles() {
        val photo = testJpeg(0xFF3366CC.toInt())
        bernd.sendAttachment(cid, photo, "geheim.jpg", "image/jpeg", "bild", preview = photo, secret = true)
        bernd.sendAttachment(cid, photo, "einmal.jpg", "image/jpeg", "bild", once = true)
        anna.fetch()
        var opened = ""
        shot("2f-einmal-geheim") { ChatScreen(anna, cid, 1, null, {}, {}, { _, _ -> }, AttachmentActions(openOnce = { opened = it })) }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Foto, Einmal-Ansicht, Tippen zum Ansehen")).performClick()
        org.junit.Assert.assertTrue(opened.isNotEmpty())
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Geheimbild, tippen zum Aufdecken")).performClick()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Geheimbild, tippen zum Aufdecken")).assertDoesNotExist()
        io.github.veritasx1.lichat.ui.SecretShown.ids.clear()
    }

    @Test
    fun photoSendSheet() {
        var chosen: Pair<Boolean, Boolean>? = null
        shot("2g-foto-senden") { io.github.veritasx1.lichat.ui.PhotoSendSheet(testJpeg(0xFF34A853.toInt()), {}) { o, s -> chosen = o to s } }
        compose.onNodeWithText("Einmal-Ansicht").performClick()
        compose.onNodeWithText("Senden").performClick()
        org.junit.Assert.assertEquals(true to false, chosen)
    }

    @Test
    fun onceViewer() = shot("2h-einmal-ansicht") { io.github.veritasx1.lichat.ui.OnceViewer(testJpeg(0xFFCC3333.toInt()), false, {}) {} }

    /** 43358d35: Fotos, Links, Dokumente in the info – never one-time pictures. */
    @Test
    fun detailsMedia() {
        val photo = testJpeg(0xFFFF9500.toInt())
        bernd.sendAttachment(cid, photo, "a.jpg", "image/jpeg", "bild", preview = photo)
        bernd.sendAttachment(cid, photo, "b.jpg", "image/jpeg", "bild", preview = photo, secret = true)
        bernd.sendAttachment(cid, photo, "c.jpg", "image/jpeg", "bild", once = true)
        bernd.send(cid, "Hier der Plan: https://www.lisoftware.de/lichat")
        bernd.sendAttachment(cid, byteArrayOf(1, 2, 3), "Einkaufsliste.pdf", "application/pdf", "datei")
        anna.fetch()
        var link = ""
        shot("4d-info-medien") { Details(anna, cid, 1, {}, {}, io.github.veritasx1.lichat.ui.GroupActions(openLink = { link = it })) }
        compose.onNodeWithText("FOTOS").performScrollTo().assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Geheimbild")).assertExists()
        compose.onNodeWithText("Einkaufsliste.pdf", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("www.lisoftware.de/lichat").performScrollTo().performClick()
        org.junit.Assert.assertEquals("https://www.lisoftware.de/lichat", link)
    }

    /** Olaf zu 884f7c8a: „schreibt …“ with the blue wave in place of the preview line in the list. */
    @Test
    fun listTyping() {
        anna.typingIndicator = true; bernd.typingIndicator = true
        org.junit.Assert.assertTrue(bernd.typing(cid))
        anna.fetch()
        shot("1c-liste-schreibt") { ChatList(anna, 1, emptyMap(), null, false, {}, {}, {}, {}) }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("schreibt …")).assertExists()
    }

    @Test
    fun typing() {
        anna.typingIndicator = true; bernd.typingIndicator = true
        org.junit.Assert.assertTrue(bernd.typing(cid))
        anna.fetch()
        shot("2e-tippt") { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("tippt", substring = true)).assertIsDisplayed()   // the animated dots (884f7c8a)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("tippt", substring = true)).assertExists()
    }

    @Test
    fun typingSwitch() {
        shot("6c-einstellungen-tippen") { SettingsScreen(anna, false, true, {}, {}, {}, {}, typing = true) }
        compose.onNode(androidx.compose.ui.test.isToggleable().and(androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Tipp-Anzeige senden")))
            .or(androidx.compose.ui.test.isToggleable().and(androidx.compose.ui.test.hasText("Tipp-Anzeige senden")))).assertIsOn()
    }

    @Test
    fun listDark() = shot("1-liste-dunkel", dark = true) { ChatList(anna, 0, emptyMap(), null, true, {}, {}, {}, {}) }

    @Test
    fun chat() {
        shot("2-gespraech") { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }
        compose.onNodeWithText("Super, bis dann 🙂").assertExists()
    }

    /** Card 9780f3a0 / b7a191c4: attachments in the bubbles, ⊕ and 🎤 in the empty input line, recording, the ⊕ sheet. */
    @Test
    fun attachments() = shot("2b-anhaenge") {
        ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }, io.github.veritasx1.lichat.ui.AttachmentActions(plus = {}, micStart = {}))
    }

    @Test
    fun recordingVoice() = shot("2c-aufnahme") {
        ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }, io.github.veritasx1.lichat.ui.AttachmentActions(plus = {}, micStart = {}, recording = 4))
    }

    @Test
    fun attachmentSheet() = shot("2d-anhang-blatt") {
        ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }, io.github.veritasx1.lichat.ui.AttachmentActions(plus = {}, micStart = {}))
        io.github.veritasx1.lichat.ui.AttachmentSheet({}, {}, {})
    }

    /** Card a7d9d987: long press shows the tapbacks and Antworten/Kopieren. */
    @Test
    fun messageMenu() {
        // Michelle a7d9d987: the reaction at the bubble's lower right; a long press opens the menu right at the bubble.
        shot("2e-reaktion") { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }
        val salad = "Ja, gern! Ich bringe Kartoffelsalat mit."
        val bubble = compose.onNodeWithText(salad).fetchSemanticsNode().boundsInRoot
        val badge = compose.onNode(androidx.compose.ui.test.hasContentDescription("Reaktionen – bernd: Herz")).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("unten rechts: $badge / $bubble", badge.left > bubble.center.x && badge.right >= bubble.right - 8f && badge.top > bubble.center.y)
        compose.onNodeWithText(salad).performTouchInput { longClick() }
        compose.waitForIdle()
        if (out != null) captureScreenRoboImage("$out/2f-reaktion-menue.png")
        val lifted = compose.onAllNodesWithText(salad).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.height > 0f }.minBy { kotlin.math.abs(it.top - bubble.top) + if (it == bubble) 1000f else 0f }
        val bar = compose.onNode(androidx.compose.ui.test.hasContentDescription("Mit Herz reagieren").or(
            androidx.compose.ui.test.hasContentDescription("Reaktion Herz zurücknehmen"))).fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithText("Antworten").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("die Blase bleibt, wo sie war", bubble.top, lifted.top, 2f)
        org.junit.Assert.assertTrue("Leiste direkt darüber: $bar / $lifted", bar.bottom <= lifted.top && lifted.top - bar.bottom < 80f)
        org.junit.Assert.assertTrue("Menü direkt darunter: $menu / $lifted", menu.top >= lifted.bottom && menu.top - lifted.bottom < 120f)
    }

    @Test
    fun chatDark() = shot("2-gespraech-dunkel", dark = true) { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }

    @Test
    fun groupChat() {
        shot("3-gruppe") { ChatScreen(anna, group, 0, null, {}, {}, { _, _ -> }) }
        compose.onNodeWithText("erna").assertExists()
    }

    @Test
    fun details() = shot("4-info") { Details(anna, group, 0, {}, {}, io.github.veritasx1.lichat.ui.GroupActions()) }

    @Test
    fun newMessage() = shot("5-neue-nachricht") { NewMessage(null, {}, { _, _ -> }) }

    @Test
    fun settings() = shot("6-einstellungen") { SettingsScreen(anna, false, false, {}, {}, {}, {}) }

    /** 99eea3f7: the app lock – off by default, „Sperren“ with its times once on; the lock screen itself. */
    @Test
    fun appLock() {
        var on: Boolean? = null; var after = -1
        var lockOn by androidx.compose.runtime.mutableStateOf(false)
        shot("6c-app-sperre") { SettingsScreen(anna, false, false, {}, {}, {}, {}, lock = lockOn, onLock = { on = it; lockOn = it }, onLockAfter = { after = it }) }
        compose.onNodeWithText("Mit Displaysperre schützen").performScrollTo().performClick()
        org.junit.Assert.assertEquals(true, on)
        compose.onNodeWithText("Sofort").performScrollTo().performClick()
        compose.onNodeWithText("Nach 15 Minuten").performClick()
        org.junit.Assert.assertEquals(900, after)
    }

    @Test
    fun lockScreen() {
        var asked = 0
        shot("6d-gesperrt") { io.github.veritasx1.lichat.ui.LockScreen { asked++ } }
        compose.onNodeWithText("Entsperren").performClick()
        org.junit.Assert.assertEquals("gleich beim Erscheinen und auf Antippen", 2, asked)
    }

    @Test
    fun appLockTimes() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        Settings.setLock(context, false)
        AppLock.started(context)
        org.junit.Assert.assertFalse("ab Werk aus", AppLock.locked.value)
        Settings.setLock(context, true); Settings.setLockAfter(context, 60)
        AppLock.started(context)
        org.junit.Assert.assertTrue("Kaltstart: gesperrt", AppLock.locked.value)
        AppLock.locked.value = false
        AppLock.stopped(); AppLock.started(context)
        org.junit.Assert.assertFalse("kurz weg, unter 1 Minute: offen", AppLock.locked.value)
        Settings.setLockAfter(context, 0)
        AppLock.stopped(); AppLock.started(context)
        org.junit.Assert.assertTrue("„Sofort“: gesperrt", AppLock.locked.value)
        AppLock.locked.value = false; AppLock.prompting = true
        AppLock.stopped(); AppLock.started(context)
        org.junit.Assert.assertFalse("zurück von der PIN-Abfrage: nicht gleich wieder sperren", AppLock.locked.value)
        AppLock.prompting = false; Settings.setLock(context, false)
    }

    /** Michelle: the switches had no checked state and only their pixels reacted – now the whole row is one switch. */
    /** da5cbfcb: the devices in the settings – this one, another with „Abmelden“ (asked twice), „Gerät hinzufügen …“ with the code. */
    @Test
    fun devices() {
        var added = ""; var removed = ""
        val list = listOf(io.github.veritasx1.lichat.core.Account.OwnDevice("aaaa", 1791500000, true, "Pixel"),
            io.github.veritasx1.lichat.core.Account.OwnDevice("bbbb", 1791540000, false, ""))
        shot("6d-geraete") { SettingsScreen(anna, false, true, {}, {}, {}, {}, devices = list, onAddDevice = { added = it }, onRemoveDevice = { removed = it }) }
        compose.onNodeWithText("Dieses Gerät (Pixel)").assertExists()
        compose.onNodeWithText("Abmelden").performScrollTo().performClick()
        org.junit.Assert.assertEquals("", removed)
        compose.onNodeWithText("Wirklich abmelden?").performScrollTo().performClick()
        org.junit.Assert.assertEquals("bbbb", removed)
        compose.onNodeWithText("Gerät hinzufügen …").performScrollTo().performClick()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Code vom neuen Gerät")).performTextInput("abcd-ef23")
        compose.onNodeWithText("Verbinden").performScrollTo().performClick()
        org.junit.Assert.assertEquals("ABCD-EF23", added)
        compose.onNodeWithText("Gerät hinzufügen …").performScrollTo().performClick()
        compose.onNodeWithText("QR-Code scannen …").assertExists()
    }

    @Test
    fun linkSetup() {
        var server = ""
        shot("7b-koppeln") { SetupScreen(false, null, onLink = { server = it }) { _, _, _, _, _ -> } }
        compose.onNodeWithText("Mit vorhandenem Gerät verbinden").performClick()
        compose.onNodeWithText("Code anzeigen").assertExists()
    }

    @Test
    fun linkCodeShown() = shot("7c-koppeln-code") {
        // the code as the new device shows it (the mode is opened by the link on the sign-in screen)
        SetupScreen(true, null, linkCode = "ABCD-EF23") { _, _, _, _, _ -> }
    }.also { compose.onNode(androidx.compose.ui.test.hasContentDescription("QR-Code zum Koppeln")).assertExists() }

    @Test
    fun switchRowsAreSwitches() {
        var on = false
        compose.setContent {
            CompositionLocalProvider(LocalPalette provides Palette(false)) {
                var state by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                SettingsScreen(anna, false, true, {}, {}, {}, {}, receipts = state, onReceipts = { state = it; on = it })
            }
        }
        val row = compose.onNode(androidx.compose.ui.test.hasText("Lesebestätigungen senden").and(androidx.compose.ui.test.isToggleable())
            .or(androidx.compose.ui.test.isToggleable().and(androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Lesebestätigungen senden")))),
            useUnmergedTree = false)
        row.assertExists()
        row.assertIsOff()
        compose.onNodeWithText("Lesebestätigungen senden").performClick()
        row.assertIsOn()
        org.junit.Assert.assertTrue(on)
    }

    @Test
    fun setup() = shot("7-anmelden") { SetupScreen(false, null) { _, _, _, _, _ -> } }

    /** ebc3d629: a taken user name – free ones to tap; the tapped one is what „Anmelden“ sends. */
    @Test
    fun setupSuggestions() {
        var sent = ""
        shot("7d-name-vergeben") { SetupScreen(false, "„anna“ ist schon vergeben. Frei wären:", suggestions = listOf("anna.mueller", "anna.m", "anna2")) { _, _, n, _, _ -> sent = n } }
        compose.onNodeWithText("anna.m").performClick()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Einladung")).performTextInput("ABCD-EFGH")
        compose.onNodeWithText("Anmelden").performClick()
        org.junit.Assert.assertEquals("anna.m", sent)
    }

    @Test
    fun explain() = shot("8-erklaerung") { ChatList(anna, 0, emptyMap(), null, false, {}, {}, {}, {}); Explain({}, {}) }
}
