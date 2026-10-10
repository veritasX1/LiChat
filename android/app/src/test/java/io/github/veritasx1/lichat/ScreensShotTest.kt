package io.github.veritasx1.lichat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.background
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextInput
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** f003: a conversation with something begun shows „Entwurf:“ and the text instead of the last message. */
    @Test
    fun draftInList() {
        anna.setDraft(cid, "Bringst du Senf mit")
        try {
            shot("1c-entwurf") { ChatList(anna, 0, emptyMap(), null, false, {}, {}, {}, {}) }
            compose.onNodeWithText("Entwurf: Bringst du Senf mit").assertExists()
        } finally { anna.setDraft(cid, "") }
    }

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

    /** cf647f61 + 2fcc3eef: my photo going up (ring with ×, 40 %), a received photo with its caption under it, and two photos
     *  waiting above the input (one pixelated) with × and the send button already there. */
    @Test
    fun photoUploadCaptionStaged() {
        val picture = android.graphics.Bitmap.createBitmap(1200, 900, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF4FA36B.toInt()) }
        val raw = java.io.ByteArrayOutputStream().also { picture.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val (jpeg, preview) = io.github.veritasx1.lichat.core.Pictures.photo(raw)
        val info = org.json.JSONObject().put("id", "p1").put("art", "bild").put("vorschau", java.util.Base64.getEncoder().encodeToString(preview))
        val mine = org.json.JSONObject().put("from", "anna").put("text", "").put("mid", "m1").put("anhang", info)
        val theirs = org.json.JSONObject().put("from", "bernd").put("text", "Der Garten im Oktober 🍂").put("mid", "m2")
            .put("anhang", org.json.JSONObject(info.toString()).put("id", "p2"))
        val staged = listOf(io.github.veritasx1.lichat.ui.Staged(android.net.Uri.EMPTY, jpeg),
            io.github.veritasx1.lichat.ui.Staged(android.net.Uri.EMPTY, jpeg, secret = true))
        shot("2l-foto-hochladen-bildunterschrift-eingabe") {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(io.github.veritasx1.lichat.ui.palette().background)) {
                io.github.veritasx1.lichat.ui.Bubble(mine, true, actions = io.github.veritasx1.lichat.ui.AttachmentActions(uploading = { 0.4f }))
                io.github.veritasx1.lichat.ui.Bubble(theirs, false)
                io.github.veritasx1.lichat.ui.InputBar({}, io.github.veritasx1.lichat.ui.AttachmentActions(staged = staged))
            }
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Wird gesendet, 40 Prozent – tippen zum Abbrechen")).assertExists()
        compose.onNodeWithText("Der Garten im Oktober 🍂").assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Foto 2 entfernen")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Senden")).assertExists()
        compose.onNodeWithText("Text zum Foto").assertExists()
    }

    /** 5749ebd0: two photos side by side, three as one large and two small, five as a stack with „1 / 5“. */
    @Test
    fun albumCollageAndStack() {
        fun photos(count: Int) = (1..count).map { nr ->
            val picture = android.graphics.Bitmap.createBitmap(600, 450, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(listOf(0xFF4FA36B, 0xFFE2A33B, 0xFF3B7BE2, 0xFFD9534F, 0xFF8E6BD9)[nr - 1].toInt()) }
            val raw = java.io.ByteArrayOutputStream().also { picture.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            val preview = io.github.veritasx1.lichat.core.Pictures.photo(raw).second
            org.json.JSONObject().put("mid", "a$count-$nr").put("from", "bernd").put("anhang", org.json.JSONObject().put("id", "p$count-$nr").put("art", "bild")
                .put("vorschau", java.util.Base64.getEncoder().encodeToString(preview)))
        }
        shot("2m-album-collage-stapel") {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(io.github.veritasx1.lichat.ui.palette().background),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.ui.unit.Dp(12f))) {
                io.github.veritasx1.lichat.ui.AlbumBubble(photos(2), null, {}, { null }, {})
                io.github.veritasx1.lichat.ui.AlbumBubble(photos(3), null, {}, { null }, {})
                io.github.veritasx1.lichat.ui.AlbumBubble(photos(5), null, {}, { null }, {})
            }
        }
        compose.onNodeWithText("1 / 5").assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("3 Fotos")).assertExists()
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
        // Michelle: covered again after a few seconds
        compose.mainClock.advanceTimeBy(11_000)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Geheimbild, tippen zum Aufdecken")).assertExists()
        io.github.veritasx1.lichat.ui.SecretShown.ids.clear()
    }

    /** Michelle (Gerätetest c5efaa9): a secret picture stood sharp in its bubble after about a minute, untapped. */
    @Test
    fun secretStaysPixelatedWhenThePhotoLoads() {
        val photo = testJpeg(0xFF3366CC.toInt(), 800, 600)
        bernd.sendAttachment(cid, photo, "geheim.jpg", "image/jpeg", "bild", preview = testJpeg(0xFF3366CC.toInt()), secret = true)
        anna.fetch()
        var loads = 0
        compose.mainClock.autoAdvance = false
        shot("2i-geheim-bleibt") { ChatScreen(anna, cid, 1, null, {}, {}, { _, _ -> }, AttachmentActions(load = { if (it.optBoolean("geheim")) loads++; photo })) }
        compose.mainClock.advanceTimeBy(70_000)
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Geheimbild, tippen zum Aufdecken")).assertExists()
        org.junit.Assert.assertEquals("das scharfe Foto wird erst nach dem Antippen geladen", 0, loads)
        compose.mainClock.autoAdvance = true
    }

    /** 4fb76dfd „Markieren“: a stroke drawn on screen is in the sent picture, cropping and rotating change it, undo takes back. */
    @Test
    fun markup() {
        val photo = testJpeg(0xFF34A853.toInt(), 800, 600)
        var result: ByteArray? = null
        shot("2j-markieren") { io.github.veritasx1.lichat.ui.MarkupEditor(photo, {}) { result = it } }
        val canvas = compose.onNode(androidx.compose.ui.test.hasContentDescription("Foto zum Markieren"))
        canvas.performTouchInput { swipe(Offset(centerX - 200, centerY), Offset(centerX + 200, centerY), 300) }    // red pen across the middle
        compose.onNodeWithText("Rückgängig").performClick()
        canvas.performTouchInput { swipe(Offset(centerX - 200, centerY), Offset(centerX + 200, centerY), 300) }
        compose.onNodeWithText("Fertig").performClick()
        val picture = android.graphics.BitmapFactory.decodeByteArray(result!!, 0, result!!.size)
        org.junit.Assert.assertEquals(800 to 600, picture.width to picture.height)
        val red = (0 until picture.height step 2).sumOf { y -> (0 until picture.width step 2).count { x ->
            picture.getPixel(x, y).let { android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) < 120 && android.graphics.Color.blue(it) < 120 } } }
        org.junit.Assert.assertTrue("roter Strich im Bild: $red Punkte", red > 200)
        org.junit.Assert.assertTrue("nur ein Strich (der erste ist zurückgenommen): $red Punkte", red < 600)
        val corner = picture.getPixel(20, 20)
        org.junit.Assert.assertTrue("Rand bleibt grün", android.graphics.Color.green(corner) > 120 && android.graphics.Color.red(corner) < 120)
        org.junit.Assert.assertEquals("keine Metadaten", 0, androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(result)).let { e ->
            listOf(androidx.exifinterface.media.ExifInterface.TAG_MAKE, androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE, androidx.exifinterface.media.ExifInterface.TAG_DATETIME).count { e.getAttribute(it) != null } })
    }

    @Test
    fun markupFlattenCropRotate() {
        val picture = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
        val marks = listOf(io.github.veritasx1.lichat.ui.Mark.Shape(io.github.veritasx1.lichat.ui.Tool.Rect, Offset(10f, 10f), Offset(390f, 290f), 0xFFFF3B30.toInt(), 6f),
            io.github.veritasx1.lichat.ui.Mark.Sticker("⭐", Offset(200f, 150f), 60f))
        val flat = io.github.veritasx1.lichat.ui.flatten(picture, marks)
        org.junit.Assert.assertEquals(0xFFFF3B30.toInt(), flat.getPixel(200, 10))
        val cropped = io.github.veritasx1.lichat.ui.flatten(picture, marks, android.graphics.RectF(100f, 50f, 300f, 250f))
        org.junit.Assert.assertEquals(200 to 200, cropped.width to cropped.height)
        val turned = io.github.veritasx1.lichat.ui.rotated(flat)
        org.junit.Assert.assertEquals(300 to 400, turned.width to turned.height)
        org.junit.Assert.assertEquals("das Original bleibt unberührt", 0xFFFFFFFF.toInt(), picture.getPixel(200, 10))
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

    /** f0-12, like Messages: holding the microphone records and letting go keeps it to hear first; a short tap only records. */
    @Test
    fun holdToRecordThenHear() {
        var recording by androidx.compose.runtime.mutableStateOf<Int?>(null)
        var kept by androidx.compose.runtime.mutableStateOf<Pair<Double, Boolean>?>(null)
        var sent = 0
        shot("2c-aufnahme-halten") {
            ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }, io.github.veritasx1.lichat.ui.AttachmentActions(plus = {},
                micStart = { recording = 3 }, recording = recording, recorded = kept,
                micPreview = { recording = null; kept = 3.4 to false },
                previewToggle = { kept = kept?.let { it.first to !it.second } },
                micStop = { send -> if (send) sent++; recording = null; kept = null }))
        }
        val mic = compose.onNode(androidx.compose.ui.test.hasContentDescription("Sprachnachricht aufnehmen"))
        mic.performTouchInput { down(center); advanceEventTime(800); up() }       // held and let go
        compose.waitForIdle()
        assertEquals(3.4 to false, kept)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Anhören")).performClick()
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Anhalten")).assertExists()
        if (out != null) captureScreenRoboImage("$out/2c-aufnahme-anhoeren.png")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Sprachnachricht senden")).performClick()
        compose.waitForIdle()
        assertEquals(1, sent)
        mic.performTouchInput { down(center); advanceEventTime(100); up() }       // a tap: it records on
        compose.waitForIdle()
        assertEquals(3, recording)
        assertEquals(null, kept)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Aufnahme beenden und anhören")).performClick()
        compose.waitForIdle()
        assertEquals(3.4 to false, kept)
    }

    /** f0-07, like Messages: a swipe to the left shows every message's time while held; to the right on a bubble answers. */
    @Test
    fun swipeForTimesAndToAnswer() {
        shot("2f-uhrzeiten") { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }
        val salad = "Ja, gern! Ich bringe Kartoffelsalat mit."
        val list = anna.chat(cid).getJSONArray("messages")
        val at = (0 until list.length()).map { list.getJSONObject(it) }.first { it.optString("text") == salad }.optLong("at")
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(at * 1000))
        val bubble = compose.onNodeWithText(salad)
        val before = compose.onAllNodesWithText(time).fetchSemanticsNodes().size
        val right = bubble.fetchSemanticsNode().boundsInRoot.right
        bubble.performTouchInput { down(center); repeat(6) { moveBy(Offset(-30f, 0f)) } }
        compose.waitForIdle()
        println("f0-07: vorher $before, jetzt ${compose.onAllNodesWithText(time).fetchSemanticsNodes().size}, rechts $right → ${bubble.fetchSemanticsNode().boundsInRoot.right}")
        assertTrue("Blase zur Seite", bubble.fetchSemanticsNode().boundsInRoot.right < right - 20)
        assertTrue("Uhrzeit beim Wischen nach links", compose.onAllNodesWithText(time).fetchSemanticsNodes().size > before)
        if (out != null) captureScreenRoboImage("$out/2f-uhrzeiten.png")
        bubble.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals("losgelassen: wieder weg", before, compose.onAllNodesWithText(time).fetchSemanticsNodes().size)
        compose.onNodeWithText("Antwort an", substring = true).assertDoesNotExist()
        bubble.performTouchInput { swipe(Offset(centerX - 100, centerY), Offset(centerX + 150, centerY), 300) }
        compose.waitForIdle()
        compose.onNodeWithText("Antwort an", substring = true).assertExists()
    }

    /** f005–f011: a formatted message – signs gone, the spoiler covered until tapped, a titled link asks first. */
    @Test
    fun formattedMessage() {
        bernd.send(cid, "*Grillen* am _Samstag_ ~Freitag~ – wer? ||Barney|| [Plan](https://example.org/plan)")
        anna.fetch()
        shot("2g-formatiert") { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) }
        val shown = "Grillen am Samstag Freitag – wer? Barney Plan"
        compose.onNodeWithText(shown).assertExists()
        val text = compose.onNodeWithText(shown).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first()
        val spoiler = text.getLinkAnnotations(0, text.length).first { (it.item as? androidx.compose.ui.text.LinkAnnotation.Clickable)?.tag == "spoiler" }
        assertEquals("Barney", text.substring(spoiler.start, spoiler.end))
        assertTrue("verdeckt", text.spanStyles.any { it.start == spoiler.start && it.item.color == androidx.compose.ui.graphics.Color.Transparent })
        (spoiler.item as androidx.compose.ui.text.LinkAnnotation.Clickable).linkInteractionListener!!.onClick(spoiler.item)
        compose.waitForIdle()
        val after = compose.onNodeWithText(shown).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first()
        assertTrue("aufgedeckt", after.spanStyles.none { it.item.color == androidx.compose.ui.graphics.Color.Transparent })
        val link = after.getLinkAnnotations(0, after.length).first { (it.item as? androidx.compose.ui.text.LinkAnnotation.Clickable)?.tag == "https://example.org/plan" }
        (link.item as androidx.compose.ui.text.LinkAnnotation.Clickable).linkInteractionListener!!.onClick(link.item)
        compose.waitForIdle()
        compose.onNodeWithText("Link öffnen?").assertExists()
        compose.onNodeWithText("https://example.org/plan").assertExists()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Link öffnen?").assertDoesNotExist()
        assertEquals("wer? ••• Plan", io.github.veritasx1.lichat.core.describe(anna.chat(cid).getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }).substringAfter("Freitag – "))
    }

    /** f012–f014: a new message with Konfetti plays it over the conversation; „Nicken“ moves, „Wiedergeben“ plays again. */
    @Test
    fun effectPlays() {
        bernd.send(cid, "Alles Gute!", effect = "konfetti")
        anna.fetch()
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalPalette provides Palette(false)) { ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }) } }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals("konfetti", io.github.veritasx1.lichat.ui.Effects.screen?.first)
        if (out != null) captureScreenRoboImage("$out/2j-konfetti.png")
        compose.mainClock.advanceTimeBy(4000)
        assertEquals(null, io.github.veritasx1.lichat.ui.Effects.screen)
        compose.onNodeWithText("↻ Wiedergeben").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals("konfetti", io.github.veritasx1.lichat.ui.Effects.screen?.first)
        compose.mainClock.autoAdvance = true
    }

    /** f005–f011: text selected – a row of formats; „Fett“ puts it between stars. */
    @Test
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    fun formatSelection() {
        var sent = ""
        shot("2h-formatieren") { androidx.compose.foundation.layout.Column { io.github.veritasx1.lichat.ui.InputBar({ sent = it }, draft = "Kommt alle") } }
        compose.onNodeWithText("Fett").assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Nachricht")).performTextInputSelection(androidx.compose.ui.text.TextRange(0, 5))
        compose.waitForIdle()
        compose.onNodeWithText("Fett").performClick()
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Senden")).performClick()
        assertEquals("*Kommt* alle", sent)
    }

    /** f020/f021: holding ↑ offers „Lautlos senden“ and „Später senden“ with quick times. */
    @Test
    fun sendOptions() {
        var sent: Triple<String, Boolean, Long?>? = null
        var effect: String? = null
        shot("2i-sendeoptionen") { androidx.compose.foundation.layout.Column {
            io.github.veritasx1.lichat.ui.InputBar({}, draft = "Psst", onSendWith = { text, silent, later, e -> sent = Triple(text, silent, later); effect = e }) } }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Senden")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Lautlos senden").performClick()
        compose.waitForIdle()
        assertEquals(Triple("Psst", true, null), sent)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Nachricht")).performTextInput("Morgen")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Senden")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Später senden …").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Morgen 09:00").performClick()
        compose.waitForIdle()
        assertEquals("Morgen", sent?.first)
        assertEquals(false, sent?.second)
        assertEquals(io.github.veritasx1.lichat.ui.laterChoices().last().second, sent?.third)
        // f012–f014: „Mit Effekt senden“ → Konfetti
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Nachricht")).performTextInput("Alles Gute")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Senden")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Mit Effekt senden …").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Konfetti").performClick()
        compose.waitForIdle()
        assertEquals("Alles Gute" to "konfetti", sent?.first to effect)
    }

    /** f0-01/30a02f81: the ⊕ menu like iOS 17 – round coloured symbols over the faded chat, a tap beside it closes it. */
    @Test
    fun attachmentSheet() {
        var open by androidx.compose.runtime.mutableStateOf(true)
        var chosen = ""
        shot("2d-anhang-blatt") {
            ChatScreen(anna, cid, 0, null, {}, {}, { _, _ -> }, io.github.veritasx1.lichat.ui.AttachmentActions(plus = {}, micStart = {}))
            if (open) io.github.veritasx1.lichat.ui.AttachmentSheet({ chosen = "Fotos" }, { chosen = "Dateien" }, { open = false },
                onCamera = { chosen = "Kamera" }, onAudio = { chosen = "Audio" })
        }
        for (label in listOf("Kamera", "Fotos", "Audio", "Dateien")) {
            compose.onNodeWithText(label).performClick()
            assertEquals(label, chosen)
        }
        compose.onNodeWithText("Abbrechen").assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Anhang-Menü schließen")).performTouchInput { click(Offset(width - 20f, 200f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Kamera").assertDoesNotExist()
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
    fun newMessage() {
        // 89aae55d: QR code, my code, PIN, and the people I know – nobody types an exact user name
        shot("5-neue-nachricht") { NewMessage(null, {}, people = listOf("bernd" to "Bernd Brandt", "erna" to "erna")) { _, _ -> } }
        compose.onNodeWithText("QR-Code scannen").assertExists()
        compose.onNodeWithText("Meinen QR-Code zeigen").assertExists()
        compose.onNodeWithText("PIN eingeben").assertExists()
        compose.onNodeWithText("Bernd Brandt").assertExists()
    }

    /** 4da136eb (Olaf 10.10.): add people to a group from my contacts – check marks, who is in already greyed out. */
    @Test
    fun addPeopleToGroup() {
        var added: List<String>? = null
        shot("4b-personen-hinzufuegen") {
            io.github.veritasx1.lichat.ui.AddPeopleSheet(listOf("anna.berg" to "Anna Berg", "bernd" to "Bernd Brandt", "erna" to "erna", "ted" to "Ted Mosby"),
                setOf("olaf", "bernd"), {}) { added = it }
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Bernd Brandt, schon in der Gruppe")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Anna Berg")).performClick()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Ted Mosby")).performClick()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Bernd Brandt, schon in der Gruppe")).performClick()
        compose.onNodeWithText("Hinzufügen (2)").performClick()
        org.junit.Assert.assertEquals(listOf("anna.berg", "ted"), added)
    }

    /** 1a682a79: voice messages like Signal – mine playing at 1,5× with its real wave, ticks and time inside; theirs unheard. */
    @Test
    fun voiceLikeSignal() {
        val wave = org.json.JSONArray(io.github.veritasx1.lichat.Media.wave((0 until 200).map { (Math.abs(Math.sin(it / 9.0)) * 30000).toInt() + 500 }))
        val mine = org.json.JSONObject().put("id", "v1").put("art", "sprache").put("dauer", 194.0).put("welle", wave)
        val theirs = org.json.JSONObject().put("id", "v2").put("art", "sprache").put("dauer", 23.0)
        shot("2n-sprachnachricht-wie-signal") {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(io.github.veritasx1.lichat.ui.palette().background)) {
                io.github.veritasx1.lichat.ui.AttachmentBubble(mine, true, playing = true, paused = false, progress = { 30_000 to 194_000 },
                    speed = 1.5f, stamp = ("gelesen" to 1791606000L) to 1791606000L) {}
                io.github.veritasx1.lichat.ui.AttachmentBubble(theirs, false, playing = false, unheard = true, stamp = ("" to 1791606300L) to 1791606300L) {}
            }
        }
        compose.onNodeWithText("1,5×").assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Noch nicht angehört")).assertExists()
        compose.onNodeWithText("0:23").assertExists()
    }

    /** Olaf 10.10.: invite by QR code – the sheet to show, and the setup screen after scanning (only the name is left). */
    @Test
    fun inviteSheet() {
        shot("0b-einladen") { io.github.veritasx1.lichat.ui.InviteSheet("K7QX-ABCD-EFGH-JKMN" to "https://exampleserver.lisoftware.de/e/K7QX-ABCD-EFGH-JKMN", {}, {}) }
        compose.onNodeWithText("K7QX-ABCD-EFGH-JKMN").assertExists()
    }

    @Test
    fun setupAfterScanningAnInvitation() {
        shot("0c-anmelden-nach-scan") {
            io.github.veritasx1.lichat.ui.SetupScreen(false, null, scannedInvite = "https://exampleserver.lisoftware.de" to "K7QX-ABCD-EFGH-JKMN") { _, _, _, _, _ -> }
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Einladung erkannt, Server https://exampleserver.lisoftware.de")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Einladung")).assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Vorname")).assertExists()
    }

    /** 89aae55d: my contact code to show – QR, user name, and a PIN on request. */
    @Test
    fun myContactCode() {
        shot("5b-mein-qr-code") { io.github.veritasx1.lichat.ui.ContactCodeSheet("lichat://kontakt?server=https%3A%2F%2Fbeispiel.de&name=anna&fp=0123456789abcdef0123456789abcdef", "anna", "4711", {}, {}) }
        compose.onNodeWithText("4711").assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("QR-Code zum Hinzufügen von anna")).assertExists()
    }

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
