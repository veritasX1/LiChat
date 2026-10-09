package io.github.veritasx1.lichat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.veritasx1.lichat.core.Account
import io.github.veritasx1.lichat.core.KeyVault
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

    @Before
    fun conversations() {
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
        anna = person("anna", first[1]); val bernd = person("bernd", first[2]); val erna = person("erna", first[3])
        cid = bernd.start(listOf("anna"))
        bernd.send(cid, "Hallo Anna! Kommst du Samstag zum Grillen?")
        anna.fetch()
        anna.send(cid, "Ja, gern! Ich bringe Kartoffelsalat mit.")
        bernd.fetch()
        bernd.send(cid, "Super, bis dann 🙂")
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

    @Test
    fun listDark() = shot("1-liste-dunkel", dark = true) { ChatList(anna, 0, emptyMap(), null, true, {}, {}, {}, {}) }

    @Test
    fun chat() {
        shot("2-gespraech") { ChatScreen(anna, cid, 0, null, {}, {}, {}) }
        compose.onNodeWithText("Super, bis dann 🙂").assertExists()
    }

    /** Card 9780f3a0 / b7a191c4: attachments in the bubbles, ⊕ and 🎤 in the empty input line, recording, the ⊕ sheet. */
    @Test
    fun attachments() = shot("2b-anhaenge") {
        ChatScreen(anna, cid, 0, null, {}, {}, {}, io.github.veritasx1.lichat.ui.AttachmentActions(plus = {}, micStart = {}))
    }

    @Test
    fun recordingVoice() = shot("2c-aufnahme") {
        ChatScreen(anna, cid, 0, null, {}, {}, {}, io.github.veritasx1.lichat.ui.AttachmentActions(plus = {}, micStart = {}, recording = 4))
    }

    @Test
    fun attachmentSheet() = shot("2d-anhang-blatt") {
        ChatScreen(anna, cid, 0, null, {}, {}, {}, io.github.veritasx1.lichat.ui.AttachmentActions(plus = {}, micStart = {}))
        io.github.veritasx1.lichat.ui.AttachmentSheet({}, {}, {})
    }

    @Test
    fun chatDark() = shot("2-gespraech-dunkel", dark = true) { ChatScreen(anna, cid, 0, null, {}, {}, {}) }

    @Test
    fun groupChat() {
        shot("3-gruppe") { ChatScreen(anna, group, 0, null, {}, {}, {}) }
        compose.onNodeWithText("erna").assertExists()
    }

    @Test
    fun details() = shot("4-info") { Details(anna, group, 0, {}, {}) }

    @Test
    fun newMessage() = shot("5-neue-nachricht") { NewMessage(null, {}, { _, _ -> }) }

    @Test
    fun settings() = shot("6-einstellungen") { SettingsScreen(anna, false, false, {}, {}, {}, {}) }

    @Test
    fun setup() = shot("7-anmelden") { SetupScreen(false, null) { _, _, _ -> } }

    @Test
    fun explain() = shot("8-erklaerung") { ChatList(anna, 0, emptyMap(), null, false, {}, {}, {}, {}); Explain({}, {}) }
}
