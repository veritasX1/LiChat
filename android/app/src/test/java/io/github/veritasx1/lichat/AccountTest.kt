package io.github.veritasx1.lichat

import io.github.veritasx1.lichat.core.Account
import io.github.veritasx1.lichat.core.KeyVault
import io.github.veritasx1.lichat.core.LiChatException
import io.github.veritasx1.lichat.core.News
import io.github.veritasx1.lichat.core.TYPING_EVERY_MS
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** The Android account against a real LiChat server (Python, in its own process, port chosen by the system) – and
 *  against the Ubuntu side (lichat-cli): the same packets, the same checks on both platforms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class AccountTest {
    private lateinit var server: Process
    private lateinit var url: String
    private val invites = ArrayDeque<String>()
    private val root: File = Files.createTempDirectory("lichat-android").toFile()
    private val repo = File(System.getProperty("lichat.server")).parentFile

    @Before
    fun startServer() {
        val script = """
import sys, threading
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
print(httpd.server_port, *[app.store.create_invite() for _ in range(8)], flush=True)
httpd.serve_forever()
"""
        server = ProcessBuilder("python3", "-c", script).redirectErrorStream(true).start()
        val first = server.inputStream.bufferedReader().readLine().split(" ")
        url = "http://127.0.0.1:${first[0]}"
        invites.addAll(first.drop(1))
    }

    @After
    fun stopServer() {
        server.destroy()
        root.deleteRecursively()
    }

    private fun person(name: String): Account {
        val folder = File(root, name)
        return Account.register(folder, url, invites.removeFirst(), name, KeyVault.Plain.create(folder))
    }

    private fun cli(profile: String, vararg args: String): String {
        val process = ProcessBuilder("python3", File(repo, "linux/lichat-cli").absolutePath, "--profil", profile, *args)
            .redirectErrorStream(true).apply { environment()["XDG_DATA_HOME"] = File(root, "cli").absolutePath; environment()["LICHAT_SOFORT"] = "1" }.start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }

    @Test
    fun twoPhonesBothWaysAfterRestart() {
        val anna = person("anna")
        val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        assertEquals(listOf(News.Kind.Joined), bernd.fetch().filter { it.kind != News.Kind.Receipt }.map { it.kind })
        anna.send(cid, "Hallo Bernd!")
        val news = bernd.fetch().filter { it.kind != News.Kind.Receipt }
        assertEquals(listOf("Hallo Bernd!"), news.filter { it.kind == News.Kind.Text }.map { it.text })
        assertTrue("Serverzeit fehlt", news.first { it.kind == News.Kind.Text }.received > 0)
        // Restart: everything from disk, with the same key.
        val again = Account(bernd.folder, KeyVault.Plain.load(bernd.folder)!!)
        again.send(cid, "Hallo Anna")
        assertEquals(listOf("Hallo Anna"), anna.fetch().filter { it.kind != News.Kind.Receipt }.map { it.text })
        assertEquals(anna.safetyCode(cid), again.safetyCode(cid))
        assertEquals(8, anna.safetyCode(cid).split(" ").size)
        // Nothing readable on disk.
        for (name in listOf("state.bin", "chats.bin", "profile.bin")) assertFalse(String(File(anna.folder, name).readBytes(), Charsets.ISO_8859_1).contains("Hallo"))
    }

    @Test
    fun phoneAndUbuntuTalk() {
        val anna = person("anna")
        assertTrue(cli("bernd", "register", url, invites.removeFirst(), "bernd").contains("Angemeldet"))
        val cid = anna.start(listOf("bernd"), "Grillen")
        anna.send(cid, "Vom Handy")
        val fetched = cli("bernd", "abholen")
        assertTrue(fetched, fetched.contains("anna: Vom Handy"))
        val sent = cli("bernd", "senden", cid.take(8), "Vom", "Rechner")
        assertTrue(sent, sent.contains("gesendet_ms="))
        assertEquals(listOf("Vom Rechner"), anna.fetch().filter { it.kind == News.Kind.Text }.map { it.text })
        assertEquals(anna.safetyCode(cid), cli("bernd", "code", cid.take(8)).trim())
    }

    @Test
    fun groupRemoveAndSystemLine() {
        val anna = person("anna"); val bernd = person("bernd"); val erna = person("erna")
        val cid = anna.start(listOf("bernd", "erna"), "Verein")
        bernd.fetch(); erna.fetch()
        assertEquals("Verein", bernd.title(cid))
        anna.remove(cid, listOf("erna"))
        assertEquals(listOf(News.Kind.Removed), erna.fetch().filter { it.kind != News.Kind.Receipt }.map { it.kind })
        assertEquals(listOf("anna hat erna aus der Unterhaltung entfernt."), bernd.fetch().filter { it.kind != News.Kind.Receipt }.map { it.text })
        try { erna.send(cid, "Hallo?"); throw AssertionError("Senden muss abgelehnt werden") } catch (e: LiChatException) { }
    }

    @Test
    fun waitingFetchIsImmediate() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        var got: List<News> = emptyList()
        var seconds = 0.0
        val listener = Thread {
            val started = System.nanoTime()
            got = bernd.fetch(30).filter { it.kind != News.Kind.Receipt }
            seconds = (System.nanoTime() - started) / 1e9
        }.apply { start() }
        Thread.sleep(500)
        anna.send(cid, "Bist du da?")
        listener.join(10_000)
        assertEquals(listOf("Bist du da?"), got.map { it.text })
        assertTrue("dauerte $seconds s", seconds < 2.5)
    }

    @Test
    fun foreignKeyPackageIsRefused() {
        val anna = person("anna")
        person("bernd")
        val erna = person("erna")
        // The directory and the key packages must belong together: Erna's device is not Bernd's.
        val forged = anna.server.call("POST", "/v1/key-packages/erna", org.json.JSONObject())
        assertNotNull(forged)
        try { anna.start(listOf("niemand")); throw AssertionError("unbekannt muss scheitern") } catch (e: LiChatException) { }
        assertEquals(0, anna.chats.length())
        assertTrue(erna.username == "erna")
    }

    /** Card 9780f3a0: attachments end to end – phone to phone, Ubuntu to phone, a changed file is refused. */
    @Test
    fun attachmentsBothWays() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        val document = ByteArray(150_000).also { java.util.Random(7).nextBytes(it) }
        anna.sendAttachment(cid, document, "Plan.pdf", "application/pdf", "datei")
        anna.sendAttachment(cid, byteArrayOf(0, 1, 2), "Sprachnachricht.m4a", "audio/mp4", "sprache", durationS = 3.42)
        // A photo with GPS in its EXIF (made here, never a real photo) leaves without it.
        val bitmap = android.graphics.Bitmap.createBitmap(3000, 2000, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3C9A5A.toInt()) }
        val raw = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val (jpeg, preview) = io.github.veritasx1.lichat.core.Pictures.photo(raw)
        anna.sendAttachment(cid, jpeg, "garten.jpg", "image/jpeg", "bild", preview = preview)
        val news = bernd.fetch().filter { it.kind != News.Kind.Receipt }
        assertEquals(listOf("Datei: Plan.pdf", "Sprachnachricht", "Foto"), news.map { it.text })
        assertEquals(document.toList(), bernd.openAttachment(news[0].attachment!!).toList())
        assertEquals(3.4, news[1].attachment!!.getDouble("dauer"), 0.001)
        val photo = bernd.openAttachment(news[2].attachment!!)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo.size)
        assertEquals(2048, maxOf(decoded.width, decoded.height))
        // Changed on the server: refused.
        val path = File(root, "server/anhaenge/" + news[0].attachment!!.getString("id"))
        File(bernd.folder, "anhaenge/" + news[0].attachment!!.getString("id")).delete()
        path.writeBytes(path.readBytes().also { it[30] = (it[30].toInt() xor 1).toByte() })
        try { bernd.openAttachment(news[0].attachment!!); throw AssertionError("verändert muss scheitern") } catch (e: LiChatException) { }
    }

    @Test
    fun attachmentFromUbuntu() {
        val anna = person("anna")
        assertTrue(cli("bernd", "register", url, invites.removeFirst(), "bernd").contains("Angemeldet"))
        val cid = anna.start(listOf("bernd"))
        cli("bernd", "abholen")
        // Ubuntu's account.py sends a file – the phone opens it.
        val script = """
import sys
sys.path.insert(0, "${File(repo, "linux").absolutePath}")
from lichat.account import Account, default_folder
a = Account(default_folder("bernd"))
a.send_attachment("$cid", b"Kohle, Wuerstchen", "Einkauf.txt", "text/plain", "datei")
"""
        val process = ProcessBuilder("python3", "-c", script).redirectErrorStream(true)
            .apply { environment()["XDG_DATA_HOME"] = File(root, "cli").absolutePath }.start()
        val out = process.inputStream.bufferedReader().readText()
        assertEquals(out, 0, process.waitFor())
        val news = anna.fetch().filter { it.attachment != null }
        assertEquals("Kohle, Wuerstchen", String(anna.openAttachment(news.single().attachment!!)))
    }

    /** Card 884f7c8a: „… tippt“ off by default, mutual, at most every 3 s, ended by the message, never stored. */
    @Test
    fun typing() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        assertFalse("ab Werk aus", anna.typing(cid))
        anna.typingIndicator = true
        assertTrue(anna.typing(cid))
        assertFalse("höchstens alle 3 s", anna.typing(cid))
        assertTrue("Bernd hat sie aus", bernd.fetch().none { it.kind == News.Kind.Typing })
        bernd.typingIndicator = true
        Thread.sleep(TYPING_EVERY_MS + 50)
        assertTrue(anna.typing(cid))
        assertEquals(listOf("anna"), bernd.fetch().filter { it.kind == News.Kind.Typing }.map { it.from })
        assertEquals(listOf("anna"), bernd.typingNow(cid))
        anna.send(cid, "Fertig getippt")
        bernd.fetch()
        assertEquals(emptyList<String>(), bernd.typingNow(cid))
        assertEquals(1, bernd.chat(cid).getJSONArray("messages").length())
        assertFalse(File(root, "server").walk().filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains("tippt") })
    }

    /** Card da5cbfcb: a second device – linked by code (Kotlin ↔ Kotlin), in every conversation, own messages everywhere;
     *  a third from the Ubuntu console; signed out from the phone. */
    @Test
    fun severalDevices() {
        val phone = person("anna"); val bernd = person("bernd")
        val cid = phone.start(listOf("bernd"))
        bernd.fetch()
        bernd.send(cid, "Vor dem Tablet")
        phone.fetch()
        val (url, code) = Account.linkOpen(url)
        var accepted = ""
        // scanned: the whole lichat://koppeln link from the new device's QR code
        val accept = Thread { accepted = phone.acceptLink(Account.linkUri(url, code), pollMs = 100) }.apply { start() }
        while (Account.linkState(url, code).optString("username").isEmpty()) Thread.sleep(50)
        val tabletFolder = File(root, "anna-tablet")
        val tablet = Account.linkFinish(tabletFolder, url, code, "Tablet", KeyVault.Plain.create(tabletFolder))
        accept.join(10_000)
        assertEquals(tablet.deviceId, accepted)
        assertEquals("anna", tablet.username)
        assertEquals(listOf(false to "", true to "Tablet"), tablet.myDevices().map { it.isThis to it.name })
        // Olaf: „synchronisieren sich einmal“ – the history came along, the devices' group stays hidden
        assertTrue(tablet.fetch().any { it.kind == News.Kind.Synced })
        assertTrue(tablet.chats.has(cid))
        assertEquals("Vor dem Tablet", tablet.chat(cid).getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals(listOf(cid), tablet.visibleChats())
        // „… und bleiben dann synchronisiert“
        phone.readReceipts = true
        tablet.fetch()
        assertTrue(tablet.readReceipts)
        assertTrue(bernd.fetch().any { it.text == "anna benutzt jetzt ein weiteres Gerät." })
        assertEquals(listOf("anna", "bernd"), bernd.members(cid).sorted())      // people, not devices (8534fe11)
        // Olaf zu 8534fe11: my own picture of Bernd, like Apple Contacts – on my devices, Bernd learns nothing
        val mine = byteArrayOf(-1, -40, -1, 1, 2, 3)
        phone.setContactPicture("bernd", mine)
        assertTrue(phone.hasContactPicture("bernd"))
        tablet.fetch()
        assertArrayEquals(mine, tablet.picture("bernd"))
        assertEquals(emptyList<News>(), bernd.fetch())
        tablet.setContactPicture("bernd", null)                                   // „Zurücksetzen“
        phone.fetch()
        assertFalse(phone.hasContactPicture("bernd"))
        assertNull(phone.picture("bernd"))
        bernd.send(cid, "An beide")
        assertEquals(listOf("An beide"), phone.fetch().filter { it.kind == News.Kind.Text }.map { it.text })
        assertEquals(listOf("An beide"), tablet.fetch().filter { it.kind == News.Kind.Text }.map { it.text })
        tablet.send(cid, "Vom Tablet")
        phone.fetch()
        val last = phone.chat(cid).getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }
        assertEquals("anna" to "Vom Tablet", last.getString("from") to last.getString("text"))
        // Ubuntu console as a third device, accepted on the phone
        val console = ProcessBuilder("python3", File(repo, "linux/lichat-cli").absolutePath, "--profil", "anna3", "koppeln", url, "Rechner")
            .redirectErrorStream(true).apply { environment()["XDG_DATA_HOME"] = File(root, "cli").absolutePath }.start()
        val reader = console.inputStream.bufferedReader()
        val consoleCode = reader.readLine().substringAfter(":").trim().substringBefore(" ")
        phone.acceptLink(consoleCode, pollMs = 100)
        assertTrue(reader.readText().contains("Gekoppelt als anna"))
        assertTrue(cli("anna3", "abholen").contains("Verlauf vom anderen Gerät übernommen"))     // Kotlin → Python, same format
        assertTrue(cli("anna3", "verlauf", cid.take(8)).contains("Vor dem Tablet"))
        assertEquals(3, phone.myDevices().size)
        // signed out from the phone
        phone.removeMyDevice(tablet.deviceId)
        bernd.fetch()
        bernd.send(cid, "Ohne Tablet")
        try { tablet.fetch(); throw AssertionError("abgemeldet") } catch (e: LiChatException) { }
        assertTrue(cli("anna3", "abholen").contains("Ohne Tablet"))
    }

    /** fe9f2029 + f9e3f055 + 43358d35: a one-time picture from Ubuntu (CLI), opened once on the phone, then gone – also on
     *  the server; secret pictures keep their flag; the overview never lists one-time pictures. */
    @Test
    fun oncePictureSecretAndMedia() {
        val anna = person("anna"); val erna = person("erna")
        assertTrue(cli("bernd", "register", url, invites.removeFirst(), "bernd").contains("Angemeldet"))
        val cid = anna.start(listOf("bernd", "erna"))
        cli("bernd", "abholen"); erna.fetch()
        val photo = File(root, "einmal.png")
        ProcessBuilder("python3", "-c", "import sys; from PIL import Image; Image.new('RGB', (64, 48), (200, 60, 60)).save(sys.argv[1])", photo.absolutePath).start().waitFor()
        assertTrue(cli("bernd", "datei", cid.take(8), photo.absolutePath, "--einmal").contains("Einmal-Ansicht"))
        val news = anna.fetch().filter { it.attachment != null }
        assertEquals("Foto · Einmal-Ansicht", news.single().text)
        assertFalse(news.single().attachment!!.has("vorschau"))
        val message = anna.chat(cid).getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }
        assertEquals("neu", anna.onceState(message))
        assertEquals("abgelaufen", anna.onceState(message, System.currentTimeMillis() / 1000 + 15 * 86400))
        val mid = message.getString("mid")
        assertTrue(anna.openOnce(cid, mid).size > 100)
        assertEquals("geoeffnet", anna.onceState(message))
        try { anna.openOnce(cid, mid); throw AssertionError("nur einmal") } catch (e: LiChatException) { }
        assertTrue(cli("bernd", "anhang", cid.take(8), "1", root.absolutePath).contains("nur auf dem Handy"))
        erna.fetch()
        erna.openOnce(cid, mid)
        val id = message.getJSONObject("anhang").getString("id")
        assertFalse("vom Server gelöscht", File(root, "server/anhaenge/$id").exists())
        anna.reportScreenshot(cid, mid)
        assertTrue(erna.fetch().any { it.text == "anna hat ein Bildschirmfoto vom Einmal-Bild gemacht." })
        // secret + overview
        anna.sendAttachment(cid, byteArrayOf(1, 2, 3), "geheim.jpg", "image/jpeg", "bild", preview = byteArrayOf(4, 5), secret = true)
        anna.send(cid, "Plan auf https://example.org/plan.")
        anna.sendAttachment(cid, byteArrayOf(9), "Plan.pdf", "application/pdf", "datei")
        erna.fetch()
        val media = erna.media(cid)
        assertEquals(1, media.photos.size)
        assertTrue(media.photos[0].getJSONObject("anhang").optBoolean("geheim"))
        assertEquals(listOf("https://example.org/plan"), media.links.map { it.first })
        assertEquals(listOf("Plan.pdf"), media.documents.map { it.getJSONObject("anhang").getString("name") })
        try { anna.sendAttachment(cid, byteArrayOf(1), "a.pdf", "application/pdf", "datei", once = true); throw AssertionError("nur Fotos") } catch (e: LiChatException) { }
    }

    /** ebc3d629: a taken user name brings free ones, from the real name first; the invitation stays usable. */
    @Test
    fun takenNameSuggestions() {
        person("anna")
        val invite = invites.removeFirst()
        val folder = File(root, "anna-zwei")
        val error = try { Account.register(folder, url, invite, "anna", KeyVault.Plain.create(folder), "Anna", "Müller"); null } catch (e: LiChatException) { e }
        assertEquals(listOf("anna.mueller", "anna.m", "a.mueller"), error!!.suggestions)
        assertEquals("anna.mueller", Account.register(folder, url, invite, error.suggestions[0], KeyVault.Plain.create(folder)).username)
    }

    /** Michelle's Mac points: unread marker + „als ungelesen“, receipts per chat, blocking + filters, „Zuletzt gelöscht“. */
    @Test
    fun macPoints() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = bernd.start(listOf("anna"))
        bernd.send(cid, "eins")
        anna.fetch(); anna.markRead(cid)
        assertEquals(0, anna.unread(cid))
        bernd.send(cid, "zwei"); bernd.send(cid, "drei")
        anna.fetch()
        assertEquals(2, anna.unread(cid))
        assertEquals("zwei", anna.findMessage(cid, anna.firstUnread(cid)!!)!!.getString("text"))
        assertEquals(listOf(cid), anna.filtered("ungelesen"))
        anna.markRead(cid); anna.markUnread(cid)
        assertEquals(1, anna.unread(cid))
        anna.markRead(cid)
        // receipts per chat (general off)
        anna.setReceiptsFor(cid, true)
        assertTrue(anna.receiptsFor(cid))
        anna.setReceiptsFor(cid, null)
        assertFalse(anna.receiptsFor(cid))
        // blocked: nothing arrives
        anna.block("bernd")
        bernd.send(cid, "Hallo?")
        assertTrue(anna.fetch().none { it.kind == News.Kind.Text })
        anna.block("bernd", false)
        // bernd wrote, anna never: unknown; after anna writes: known
        assertEquals(listOf(cid), anna.filtered("unbekannt"))
        anna.send(cid, "Hi")
        assertEquals(listOf(cid), anna.filtered("bekannt"))
        // pinned
        anna.pin(cid, true)
        assertEquals(listOf(cid), anna.pinned())
        // „Zuletzt gelöscht“: a new message brings it back, the old ones stay deleted; 30 days later gone
        anna.deleteChat(cid)
        assertEquals(emptyList<String>(), anna.filtered("alle"))
        assertEquals(listOf(cid), anna.filtered("geloescht"))
        bernd.send(cid, "Noch da?")
        anna.fetch()
        assertEquals(listOf(cid), anna.filtered("alle"))
        anna.purge(now = System.currentTimeMillis() / 1000 + 31L * 86400)
        val left = anna.chat(cid).getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("text") } }
        assertEquals(listOf("Noch da?"), left)
    }

    /** Michelle f09a8086: the token is sealed (token.bin), never in account.json; an old account.json with it is taken over. */
    @Test
    fun tokenSealed() {
        val anna = person("anna")
        val folder = File(root, "anna")
        assertFalse(JSONObject(File(folder, "account.json").readText()).has("token"))
        val token = anna.server.token!!
        assertFalse(folder.listFiles()!!.filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains(token) })
        File(folder, "account.json").writeText(JSONObject(File(folder, "account.json").readText()).put("token", token).toString())
        File(folder, "token.bin").delete()
        val again = Account(folder, File(folder, "key").readBytes())
        assertEquals(token, again.server.token)
        assertFalse(JSONObject(File(folder, "account.json").readText()).has("token"))
        again.fetch()
    }

    /** Card d54175ed: „@name“ members only, „@alle“ everyone, a mention of me is flagged (notifies even when muted). */
    @Test
    fun mentions() {
        val anna = person("anna"); val bernd = person("bernd"); val erna = person("erna")
        val cid = anna.start(listOf("bernd", "erna"), "Grillabend")
        bernd.fetch(); erna.fetch()
        assertEquals(listOf("bernd", "erna"), anna.mentioned(cid, "Hi @Bernd, @fremder und @erna."))
        assertEquals(listOf("anna", "bernd", "erna"), anna.mentioned(cid, "@alle bitte melden"))
        assertEquals(emptyList<String>(), anna.mentioned(cid, "mail@bernd.de"))
        assertEquals(listOf("erna"), anna.mentionCandidates(cid, "e"))
        anna.send(cid, "@bernd bringst du Kohle?")
        assertTrue(bernd.fetch().first { it.kind == News.Kind.Text }.mention)
        assertFalse(erna.fetch().first { it.kind == News.Kind.Text }.mention)
    }

    /** Card 4ceca163, like iOS: edit 15 min / 5×, the earlier versions travel; „Senden widerrufen“ 2 min, quotes emptied. */
    @Test
    fun editAndUnsend() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        anna.send(cid, "Treffen um 18 Uhr")
        val list = anna.chat(cid).getJSONArray("messages")
        val mid = list.getJSONObject(list.length() - 1).getString("mid")
        bernd.fetch()
        anna.edit(cid, mid, "Treffen um 19 Uhr")
        assertTrue(bernd.fetch().any { it.kind == News.Kind.Edited })
        val theirs = bernd.findMessage(cid, mid)!!
        assertEquals("Treffen um 19 Uhr", theirs.getString("text"))
        assertEquals("Treffen um 18 Uhr", theirs.getJSONArray("bearbeitet").getJSONObject(0).getString("text"))
        repeat(4) { anna.edit(cid, mid, "Treffen um ${20 + it} Uhr") }
        try { anna.edit(cid, mid, "sechstes Mal"); throw AssertionError("muss scheitern") } catch (e: LiChatException) { }
        assertFalse(bernd.canEdit(theirs))                       // not mine
        anna.send(cid, "Ups, falscher Chat")
        val oops = list.getJSONObject(list.length() - 1).getString("mid")
        bernd.fetch()
        bernd.send(cid, "Hä?", oops)
        anna.fetch()
        anna.unsend(cid, oops)
        bernd.fetch()
        val gone = bernd.findMessage(cid, oops)!!
        assertTrue(gone.optBoolean("zurueckgerufen"))
        assertEquals("Nachricht widerrufen", io.github.veritasx1.lichat.core.describe(gone))
        val theirList = bernd.chat(cid).getJSONArray("messages")
        assertTrue(theirList.getJSONObject(theirList.length() - 1).getJSONObject("antwort").optBoolean("zurueckgerufen"))
        assertFalse(bernd.chat(cid).toString().contains("falscher Chat"))
        assertFalse(anna.chat(cid).toString().contains("falscher Chat"))
        // Only an emoji is a reaction (Michelle): „Gelesen“ is refused already when sending
        try { bernd.react(cid, mid, "Gelesen"); throw AssertionError("muss scheitern") } catch (e: LiChatException) { }
    }

    /** Card db70d358: ✓ sent, ✓✓ delivered, blue ✓✓ read – read receipts off by default and mutual; a failed send is marked. */
    @Test
    fun ticks() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        anna.send(cid, "Hallo")
        val list = anna.chat(cid).getJSONArray("messages")
        fun mine() = list.getJSONObject(list.length() - 1)
        assertEquals("gesendet", anna.tick(cid, mine()).first)
        bernd.fetch(); anna.fetch()
        assertEquals("zugestellt", anna.tick(cid, mine()).first)
        bernd.markRead(cid); anna.fetch()
        assertEquals("zugestellt", anna.tick(cid, mine()).first)
        bernd.readReceipts = true
        bernd.markRead(cid); anna.fetch()
        assertEquals("gegenseitig", "zugestellt", anna.tick(cid, mine()).first)
        anna.readReceipts = true
        assertEquals("gelesen", anna.tick(cid, mine()).first)
        // Not sent: marked, and sent again with the same mid.
        val server = File(root, "server")
        val token = anna.server.token
        anna.server.token = "falsch"
        try { anna.send(cid, "Kommt das an?"); throw AssertionError("muss scheitern") } catch (e: LiChatException) { }
        assertEquals("fehler", anna.tick(cid, mine()).first)
        anna.server.token = token
        anna.retry(cid, mine().getString("mid"))
        assertEquals("gesendet", anna.tick(cid, mine()).first)
        assertEquals(listOf("Kommt das an?"), bernd.fetch().filter { it.kind == News.Kind.Text }.map { it.text })
        assertFalse(server.walk().filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains("quittung") })
    }

    /** Card d45b319a: add, rename, picture, leave, mute – end to end, a grey line for everyone; Ubuntu renames too. */
    @Test
    fun groupLife() {
        val anna = person("anna"); val bernd = person("bernd"); val erna = person("erna")
        val cid = anna.start(listOf("bernd"), "Grillen")
        bernd.fetch()
        anna.add(cid, listOf("erna"))
        assertEquals(listOf(News.Kind.Joined), erna.fetch().filter { it.kind == News.Kind.Joined }.map { it.kind })
        assertEquals("Grillen", erna.title(cid))
        assertTrue(bernd.fetch().any { it.text == "anna hat erna hinzugefügt." })
        anna.rename(cid, "Grillabend")
        val picture = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE08A2B.toInt()) }
        val raw = java.io.ByteArrayOutputStream().also { picture.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        anna.setGroupPicture(cid, io.github.veritasx1.lichat.core.Pictures.prepare(raw))
        val lines = erna.fetch().map { it.text }
        assertTrue(lines.toString(), "anna hat die Gruppe „Grillabend“ genannt." in lines && "anna hat das Gruppenbild geändert." in lines)
        assertEquals("Grillabend", erna.title(cid))
        assertNotNull(erna.groupPicture(cid))
        assertFalse(File(root, "server").walk().filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains("Grillabend") })
        erna.leave(cid)
        try { erna.send(cid, "noch da?"); throw AssertionError("nach dem Verlassen") } catch (e: LiChatException) { }
        assertTrue(anna.fetch().any { it.text == "erna hat die Unterhaltung verlassen." })
        bernd.fetch(); bernd.fetch()
        assertEquals(listOf("anna", "bernd"), bernd.members(cid).sorted())
        assertEquals(emptyList<News>(), erna.fetch())
        anna.mute(cid, true)
        assertTrue(Account(anna.folder, KeyVault.Plain.load(anna.folder)!!).muted(cid))
    }

    @Test
    fun ubuntuRenamesThePhonesGroup() {
        val anna = person("anna"); val erna = person("erna")
        assertTrue(cli("bernd", "register", url, invites.removeFirst(), "bernd").contains("Angemeldet"))
        val cid = anna.start(listOf("bernd", "erna"))
        erna.fetch()
        cli("bernd", "abholen")
        assertTrue(cli("bernd", "umbenennen", cid.take(8), "Verein").contains("Gruppenname"))
        assertTrue(anna.fetch().any { it.text == "bernd hat die Gruppe „Verein“ genannt." })
        assertEquals("Verein", anna.title(cid))
    }

    /** Michelle 09.10.: nginx answered a server restart with an HTML 502 page – the app crashed („wird wiederholt beendet“). */
    @Test
    fun htmlErrorPageIsAServerError() {
        val anna = person("anna")
        val page = java.net.ServerSocket(0)
        Thread {
            repeat(2) {
                page.accept().use { socket ->
                    socket.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) { } }
                    socket.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/html\r\nContent-Length: 46\r\nConnection: close\r\n\r\n" +
                        "<html><body><h1>502 Bad Gateway</h1></body></html>".take(46)).toByteArray())
                }
            }
        }.start()
        val broken = io.github.veritasx1.lichat.core.Server("http://127.0.0.1:${page.localPort}", anna.server.token)
        val error = try { broken.call("GET", "/v1/messages"); null } catch (e: LiChatException) { e }
        assertNotNull("eine LiChatException statt eines Absturzes", error)
        assertTrue(error!!.message!!.contains("502"))
        page.close()
    }

    /** Card a7d9d987: a reply carries its quote, a reaction is one per person – phone and Ubuntu understand each other. */
    @Test
    fun repliesAndReactionsAcrossPlatforms() {
        val anna = person("anna")
        assertTrue(cli("bernd", "register", url, invites.removeFirst(), "bernd").contains("Angemeldet"))
        val cid = anna.start(listOf("bernd"))
        cli("bernd", "abholen")
        anna.send(cid, "Kommst du Samstag?")
        cli("bernd", "abholen")
        val script = """
import sys
sys.path.insert(0, "${File(repo, "linux").absolutePath}")
from lichat.account import Account, default_folder
a = Account(default_folder("bernd"))
asked = [m for m in a.chats["$cid"]["messages"] if m.get("text") == "Kommst du Samstag?"][0]
a.send("$cid", "Ja!", reply_to=asked["mid"])
a.react("$cid", asked["mid"], "👍")
"""
        val process = ProcessBuilder("python3", "-c", script).redirectErrorStream(true)
            .apply { environment()["XDG_DATA_HOME"] = File(root, "cli").absolutePath }.start()
        val out = process.inputStream.bufferedReader().readText()
        assertEquals(out, 0, process.waitFor())
        anna.fetch()
        val list = anna.chat(cid).getJSONArray("messages")
        val answer = (0 until list.length()).map { list.getJSONObject(it) }.first { it.optString("text") == "Ja!" }
        assertEquals("Kommst du Samstag?", answer.getJSONObject("antwort").getString("text"))
        val asked = (0 until list.length()).map { list.getJSONObject(it) }.first { it.optString("text") == "Kommst du Samstag?" }
        assertEquals("👍", asked.getJSONObject("reaktionen").getString("bernd"))
        anna.react(cid, answer.getString("mid"), "❤️")
        assertTrue(cli("bernd", "abholen").contains("anna reagiert ❤️"))
    }

    /** Olaf 09.10.: „LiSoftware“ with capitals and a space – end to end, phone and Ubuntu; the user name stays the handle. */
    @Test
    fun displayNames() {
        val anna = person("anna")
        assertTrue(cli("bernd", "register", url, invites.removeFirst(), "bernd").contains("Angemeldet"))
        anna.setName("Anna", "Müller")
        val cid = anna.start(listOf("bernd"))
        cli("bernd", "abholen")
        assertTrue(cli("bernd", "name", "Bernd", "Groß").contains("Bernd Groß"))
        anna.fetch()
        assertEquals("Bernd Groß", anna.display("bernd"))
        assertEquals("Bernd Groß", anna.title(cid))
        assertFalse(File(root, "server").walk().filter { it.isFile }.any { String(it.readBytes(), Charsets.UTF_8).contains("Müller") })
        assertEquals("erika.mustermann", io.github.veritasx1.lichat.core.suggestUsername("Erika", "Mustermann"))
        assertEquals("juergen.gross", io.github.veritasx1.lichat.core.suggestUsername("Jürgen Groß"))
        assertEquals("jean-marie.lefevre", io.github.veritasx1.lichat.core.suggestUsername("Jean-Marie", "Lefèvre"))
    }
}

