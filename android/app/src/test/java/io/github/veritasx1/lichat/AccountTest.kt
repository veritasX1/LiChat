package io.github.veritasx1.lichat

import io.github.veritasx1.lichat.core.Account
import io.github.veritasx1.lichat.core.Server
import io.github.veritasx1.lichat.core.KeyVault
import io.github.veritasx1.lichat.core.LiChatException
import io.github.veritasx1.lichat.core.News
import io.github.veritasx1.lichat.core.RESEND_S
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
        // Michelle: the old token (still in backups of account.json) was renewed at the first fetch – it is void now
        assertTrue(again.server.token != token)
        try { io.github.veritasx1.lichat.core.Server(url, token).call("GET", "/v1/messages"); throw AssertionError("alter Token gilt noch") } catch (e: LiChatException) { assertTrue(e.message!!.contains("(401)")) }
        assertEquals(again.server.token, Account(folder, File(folder, "key").readBytes()).server.token)
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

    /** Card 2fcc3eef: photo and text leave as one message – the caption arrives with the picture on a phone and in the CLI,
     *  and the CLI's --text comes back the same way. */
    @Test
    fun captionTravelsWithThePicture() {
        val anna = person("anna"); val bernd = person("bernd")
        assertTrue(cli("erna", "register", url, invites.removeFirst(), "erna").contains("Angemeldet"))
        val cid = anna.start(listOf("bernd", "erna"))
        bernd.fetch(); cli("erna", "abholen")
        val bitmap = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3C9A5A.toInt()) }
        val raw = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val (jpeg, preview) = io.github.veritasx1.lichat.core.Pictures.photo(raw)
        anna.sendAttachment(cid, jpeg, "garten.jpg", "image/jpeg", "bild", preview = preview, caption = "  Der Garten im Oktober  ")
        val list = anna.chat(cid).getJSONArray("messages")
        assertEquals("Der Garten im Oktober", list.getJSONObject(list.length() - 1).getString("text"))
        val got = bernd.fetch().filter { it.attachment != null }.single()
        assertEquals("Foto: Der Garten im Oktober", got.text)
        val theirs = bernd.chat(cid).getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }
        assertEquals("Der Garten im Oktober", theirs.getString("text"))
        assertTrue(cli("erna", "abholen").contains("Foto: Der Garten im Oktober"))
        val file = File(root, "notiz.txt").apply { writeText("Kohle") }
        cli("erna", "datei", cid, file.absolutePath, "--text", "Einkaufsliste", "für", "Samstag")
        assertEquals(listOf("Datei: notiz.txt: Einkaufsliste für Samstag"), anna.fetch().filter { it.attachment != null }.map { it.text })
    }

    /** Card 1a682a79: a voice message's real wave goes along (50 bars 0…31), a forged one is dropped, heard is remembered. */
    @Test
    fun voiceWaveAndHeard() {
        val levels = (0 until 230).map { if (it in 80..120) 30000 else 900 }
        val bars = io.github.veritasx1.lichat.Media.wave(levels)
        assertEquals(50, bars.size)
        assertTrue(bars.all { it in 1..31 })
        assertEquals(31, bars.max())
        assertTrue(bars.first() < 10)
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        anna.sendAttachment(cid, byteArrayOf(1, 2, 3), "Sprachnachricht.m4a", "audio/mp4", "sprache", 4.2, wave = bars)
        val got = bernd.fetch().single { it.attachment != null }.attachment!!
        assertEquals(bars, got.getJSONArray("welle").let { w -> (0 until w.length()).map { w.getInt(it) } })
        bernd.markListened(cid, got.getString("id"), 9.9)
        assertTrue(bernd.chat(cid).getJSONArray("messages").let { a -> a.getJSONObject(a.length() - 1) }.getBoolean("angehoert"))
        assertEquals("die mitgeschickte Länge bleibt", 4.2, bernd.chat(cid).getJSONArray("messages").let { a -> a.getJSONObject(a.length() - 1) }.getJSONObject("anhang").getDouble("dauer"), 0.0)
        // without a length (Michelle: CLI file stayed „0:00“): known once played
        anna.sendAttachment(cid, byteArrayOf(5), "ohne.m4a", "audio/mp4", "sprache")
        val plain = bernd.fetch().single { it.attachment != null }.attachment!!
        bernd.markListened(cid, plain.getString("id"), 12.34)
        assertEquals(12.3, bernd.chat(cid).getJSONArray("messages").let { a -> a.getJSONObject(a.length() - 1) }.getJSONObject("anhang").getDouble("dauer"), 0.0)
        // a photo cannot carry a wave, and a voice message only numbers 0…31
        anna.sendAttachment(cid, byteArrayOf(4), "x.m4a", "audio/mp4", "sprache", 1.0, wave = List(300) { 99 })
        val clipped = bernd.fetch().single { it.attachment != null }.attachment!!.getJSONArray("welle")
        assertEquals(io.github.veritasx1.lichat.core.WAVE_MAX, clipped.length())
        assertTrue((0 until clipped.length()).all { clipped.getInt(it) == 31 })
    }

    /** Olaf 10.10.: „Server: unbekannt (404)“ when writing to „anna“ (she is anna.berg) – now a plain sentence with a guess. */
    @Test
    fun unknownNameIsSaidPlainly() {
        File(root, "aw").let { Account.register(it, url, invites.removeFirst(), "anna.berg", KeyVault.Plain.create(it)) }
        val olaf = person("olaf")
        olaf.chatWith("anna.berg")
        val error = try { olaf.start(listOf("anna")); null } catch (e: LiChatException) { e.message }
        assertEquals("„anna“ gibt es auf diesem Server nicht – meinst du anna.berg?", error)
    }

    /** Card 979f9b22: begun text stays with its conversation, also after a restart; never sent anywhere. */
    @Test
    fun draftPerConversation() {
        val anna = person("anna"); val bernd = person("bernd")
        val a = anna.start(listOf("bernd")); val b = anna.start(listOf("bernd"), "Grillabend")
        anna.setDraft(a, "Kommst du mor"); anna.setDraft(b, "Wer bringt")
        val again = Account(File(root, "anna"), KeyVault.Plain.load(File(root, "anna"))!!)
        assertEquals("Kommst du mor", again.draft(a)); assertEquals("Wer bringt", again.draft(b))
        again.setDraft(a, "")
        assertEquals("", again.draft(a))
        // f003/f004: the answer begun stays with the draft; either one marks the chat as „Entwurf“
        assertTrue(again.hasDraft(b)); assertFalse(again.hasDraft(a))
        again.setDraftReply(a, "abc123")
        assertEquals("abc123", Account(File(root, "anna"), KeyVault.Plain.load(File(root, "anna"))!!).draftReply(a))
        assertTrue(again.hasDraft(a))
        again.setDraftReply(a, null)
        assertEquals(null, again.draftReply(a)); assertFalse(again.hasDraft(a))
        bernd.fetch()
        assertTrue(bernd.visibleChats().none { bernd.chat(it).has("entwurf") })
    }

    /** f0-04: photos laid above the input stay with their conversation after a restart – sealed, never in plain on disk. */
    @Test
    fun draftPhotosPerConversation() {
        val anna = person("anna"); person("bernd")
        val a = anna.start(listOf("bernd")); val b = anna.start(listOf("bernd"), "Grillabend")
        val photo = ByteArray(40_000).also { java.util.Random(7).nextBytes(it) }
        val marker = "UNVERSCHLUESSELT-ERKENNBAR".toByteArray()
        anna.setDraftPhotos(a, listOf(Account.DraftPhoto(photo, "Strand.jpg"), Account.DraftPhoto(marker + photo, "Plan.png", "image/png", secret = true, asFile = true)))
        val again = Account(File(root, "anna"), KeyVault.Plain.load(File(root, "anna"))!!)
        val kept = again.draftPhotos(a)
        assertEquals(listOf("Strand.jpg", "Plan.png"), kept.map { it.name })
        assertEquals(photo.toList(), kept[0].bytes.toList())
        assertTrue(kept[1].secret && kept[1].asFile && !kept[1].once)
        assertEquals("image/png", kept[1].mime)
        assertEquals("die andere Unterhaltung hat keine", 0, again.draftPhotos(b).size)
        val onDisk = File(File(root, "anna"), "entwuerfe").walkTopDown().filter { it.isFile }.map { it.readBytes() }.toList()
        assertTrue(onDisk.isNotEmpty())
        assertTrue("nichts im Klartext", onDisk.none { file -> String(file, Charsets.ISO_8859_1).contains("UNVERSCHLUESSELT") || String(file, Charsets.ISO_8859_1).contains("Strand") })
        again.setDraftPhotos(a, emptyList())
        assertEquals(0, again.draftPhotos(a).size)
        again.setDraftPhotos(b, listOf(Account.DraftPhoto(photo)))
        again.deleteChat(b)
        assertEquals("ein gelöschtes Gespräch nimmt seine Fotos mit", 0, again.draftPhotos(b).size)
    }

    /** Michelle 4da136eb: someone taken out of a group stays in „known people“; an unknown name leaves the group as it was. */
    @Test
    fun addPeopleKnownAndUnknown() {
        val anna = person("anna"); val bernd = person("bernd"); val erna = person("erna")
        val cid = anna.start(listOf("bernd", "erna"), "Grillabend")
        bernd.fetch(); erna.fetch()
        erna.send(cid, "Hallo zusammen")
        anna.fetch()
        anna.remove(cid, listOf("erna"))
        assertTrue("erna" in anna.knownPeople())
        val before = anna.chat(cid).getJSONObject("roster").keys().asSequence().toSet()
        val error = try { anna.add(cid, listOf("gibtsnicht")); null } catch (e: LiChatException) { e.message }
        assertEquals("„gibtsnicht“ gibt es auf diesem Server nicht", error)
        assertEquals(before, anna.chat(cid).getJSONObject("roster").keys().asSequence().toSet())
        anna.add(cid, listOf("erna"))
        assertTrue(anna.chat(cid).getJSONObject("roster").has("erna"))
    }

    /** Michelle 1a682a79 (b): playing my own voice message leaves the dot of theirs alone. */
    @Test
    fun playingMineKeepsTheirsUnheard() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        anna.sendAttachment(cid, byteArrayOf(1), "a.m4a", "audio/mp4", "sprache", 11.0)
        bernd.fetch()
        bernd.sendAttachment(cid, byteArrayOf(2), "b.m4a", "audio/mp4", "sprache")
        anna.fetch()
        val all = anna.chat(cid).getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.has("anhang") }
        val mine = all.first { it.optString("from") == "anna" }; val theirs = all.first { it.optString("from") == "bernd" }
        anna.markListened(cid, mine.getJSONObject("anhang").getString("id"), 11.0)
        assertFalse(theirs.optBoolean("angehoert"))
    }

    /** Olaf 10.10.: invite by QR code – the code brings server and invitation, the new person only chooses a name and lands
     *  in the conversation with whoever invited them. Both link forms are read. */
    @Test
    fun inviteByQrCode() {
        val anna = person("anna")
        val (code, link) = anna.newInvite()
        // the link names the server's public address (here its default) – the test server itself is local
        assertEquals(code, Account.parseInviteUri(link)?.second)
        assertEquals("https://exampleserver.lisoftware.de" to "ABCD-EFGH", Account.parseInviteUri("lichat://einladung?server=https%3A%2F%2Fexampleserver.lisoftware.de&code=abcd-efgh"))
        assertNull(Account.parseInviteUri("lichat://kontakt?name=anna"))
        val invite = Account.parseInviteUri(link)!!.second
        val erna = File(root, "erna").let { Account.register(it, url, invite, "tante.erna", KeyVault.Plain.create(it)) }
        assertEquals("anna", erna.invitedBy)
        erna.addContact("anna")
        val cid = erna.chatWith("anna")
        erna.send(cid, "Hallo, ich bin jetzt auch hier!")
        assertTrue(anna.fetch().any { it.text == "Hallo, ich bin jetzt auch hier!" })
    }

    /** Card 89aae55d (Olaf 10.10.): a scanned QR code leads straight into the conversation – phone and Ubuntu compute the same
     *  fingerprint, so codes work both ways; a wrong fingerprint is refused; a PIN works too; known people are offered. */
    @Test
    fun contactByQrAndPin() {
        val anna = person("anna"); val bernd = person("bernd")
        assertTrue(cli("erna", "register", url, invites.removeFirst(), "erna").contains("Angemeldet"))
        // Ubuntu's code scanned on the phone: verified, a new conversation, the same one on a second scan
        val ernaLink = cli("erna", "kontakt").lines().last { it.startsWith("lichat://kontakt") }
        assertEquals("erna", anna.addContactLink(ernaLink))
        val cid = anna.chatWith("erna")
        assertEquals(listOf("erna"), anna.others(cid))
        assertEquals(cid, anna.chatWith("erna"))
        assertTrue(anna.profiles.getJSONObject("kontakte").getJSONObject("erna").getBoolean("verifiziert"))
        // the phone's code scanned in Ubuntu: verified there too (same fingerprint)
        assertTrue(cli("erna", "kontakt-hinzufuegen", anna.contactLink()).contains("verifiziert"))
        // a code with a wrong fingerprint is refused
        val forged = ernaLink.replace(Regex("fp=[0-9a-f]+"), "fp=" + "0".repeat(32))
        try { anna.addContactLink(forged); throw AssertionError("muss scheitern") } catch (e: LiChatException) { assertTrue(e.message!!.contains("passt nicht")) }
        try { anna.addContactLink(anna.contactLink()); throw AssertionError("muss scheitern") } catch (e: LiChatException) { }
        // a PIN: bernd adds anna without typing her name
        val pin = anna.newPin()
        assertEquals("anna", bernd.redeemPin(pin))
        assertEquals(listOf("anna"), bernd.knownPeople())
        assertEquals(listOf("erna"), anna.knownPeople())
    }

    /** Card 5749ebd0: four photos picked together arrive as one album on a phone and in the CLI, in order, the caption
     *  on the last; the transcript draws them in one row (the last one). */
    @Test
    fun albumStaysTogether() {
        val anna = person("anna"); val bernd = person("bernd")
        assertTrue(cli("erna", "register", url, invites.removeFirst(), "erna").contains("Angemeldet"))
        val cid = anna.start(listOf("bernd", "erna"))
        bernd.fetch(); cli("erna", "abholen")
        val part = anna.newAlbum(4)
        val laid = (1..4).map { nr ->
            val bitmap = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt() + nr * 0x303030) }
            val raw = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            val (jpeg, preview) = io.github.veritasx1.lichat.core.Pictures.photo(raw)
            anna.sendAttachment(cid, jpeg, "$nr.jpg", "image/jpeg", "bild", preview = preview, caption = if (nr == 4) "Herbst" else "", album = part(nr), defer = true)
        }
        assertTrue(laid.all { it.optString("status") == "wartet" })
        laid.forEach { anna.sendQueued(cid, it) }
        bernd.fetch()
        val theirs = bernd.chat(cid).getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.has("anhang") }
        assertEquals(listOf(1, 2, 3, 4), theirs.map { it.getJSONObject("album").getInt("nr") })
        val runs = io.github.veritasx1.lichat.ui.albumRuns(bernd.chat(cid).getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } })
        assertEquals(setOf(theirs.last().optString("mid")), runs.leaders.keys)
        assertEquals(3, runs.hidden.size)
        assertEquals("Herbst", theirs.last().getString("text"))
        val script = """
import sys
sys.path.insert(0, "${File(repo, "linux").absolutePath}")
from lichat.account import Account, default_folder
a = Account(default_folder("erna"))
a.fetch()
print([m["album"]["nr"] for m in a.chats["$cid"]["messages"] if m.get("album")])
"""
        val process = ProcessBuilder("python3", "-c", script).redirectErrorStream(true)
            .apply { environment()["XDG_DATA_HOME"] = File(root, "cli").absolutePath }.start()
        assertTrue(process.inputStream.bufferedReader().readText().contains("[1, 2, 3, 4]"))
    }

    /** Card cf647f61: the bubble is there before the upload; a failed upload stays „Nicht gesendet“ and goes up again on a
     *  retry (once at the other end); the ring fills to 100 %; a cancelled upload takes bubble and outbox bytes with it. */
    @Test
    fun uploadBubbleProgressRetryCancel() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        val data = ByteArray(300_000).also { java.util.Random(3).nextBytes(it) }
        val list = anna.chat(cid).getJSONArray("messages")
        fun last() = list.getJSONObject(list.length() - 1)
        val outbox = File(File(root, "anna"), "anhaenge")
        // 1. upload fails: the bubble stays, with its bytes in the outbox
        val token = anna.server.token
        anna.server.token = "falsch"
        try { anna.sendAttachment(cid, data, "Plan.pdf", "application/pdf", "datei"); throw AssertionError("muss scheitern") } catch (e: LiChatException) { }
        assertEquals("fehler", anna.tick(cid, last()).first)
        assertTrue(last().getJSONObject("anhang").getString("id").startsWith(io.github.veritasx1.lichat.core.OUTBOX))
        assertEquals(1, outbox.list()!!.count { it.startsWith(io.github.veritasx1.lichat.core.OUTBOX) })
        // 2. retry: up, delivered once, the outbox empty, the ring went to 100 %
        anna.server.token = token
        val shares = mutableListOf<Float>()
        val mid = last().getString("mid")
        anna.retry(cid, mid) { anna.uploads[mid]?.let { shares.add(it) } }
        assertEquals("gesendet", anna.tick(cid, last()).first)
        assertFalse(last().getJSONObject("anhang").getString("id").startsWith(io.github.veritasx1.lichat.core.OUTBOX))
        assertEquals(0, outbox.list()!!.count { it.startsWith(io.github.veritasx1.lichat.core.OUTBOX) })
        assertEquals(1f, shares.last(), 0f)
        assertTrue("mehrere Schritte", shares.size >= 3)
        val got = bernd.fetch().filter { it.kind == News.Kind.Text }
        assertEquals(1, got.size)
        assertEquals(data.toList(), bernd.openAttachment(got[0].attachment!!).toList())
        assertEquals(0, anna.uploads.size)
        // 3. cancelled as soon as it starts: bubble and bytes gone, nothing at the other end
        val before = list.length()
        val answer = anna.sendAttachment(cid, data, "Zweiter.pdf", "application/pdf", "datei",
            changed = { anna.uploads.keys.forEach { anna.cancelUpload(it) } })
        assertEquals(0, answer.length())
        assertEquals(before, list.length())
        assertEquals(0, outbox.list()!!.count { it.startsWith(io.github.veritasx1.lichat.core.OUTBOX) })
        assertTrue(bernd.fetch().none { it.kind == News.Kind.Text })
    }

    /** Olaf 10.10.: a photo going up holds up nothing – a text sent meanwhile shows at once and arrives first. */
    @Test
    fun textGoesWhileAPhotoGoesUp() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        val going = java.util.concurrent.CountDownLatch(1); val goOn = java.util.concurrent.CountDownLatch(1)
        val data = ByteArray(50_000).also { java.util.Random(5).nextBytes(it) }
        val upload = Thread {
            anna.sendAttachment(cid, data, "Plan.pdf", "application/pdf", "datei",
                changed = { if (anna.uploads.isNotEmpty() && going.count > 0) { going.countDown(); goOn.await(10, java.util.concurrent.TimeUnit.SECONDS) } })
        }.also { it.start() }
        assertTrue(going.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val shown = mutableListOf<String>()
        val list = anna.chat(cid).getJSONArray("messages")
        anna.onChange = { shown.add(list.getJSONObject(list.length() - 1).optString("text")) }
        anna.send(cid, "Schon unterwegs")
        assertEquals("die Blase ist da, bevor sie rausgeht", "Schon unterwegs", shown.first())
        assertEquals(listOf("Schon unterwegs"), bernd.fetch().filter { it.kind == News.Kind.Text }.map { it.text })
        goOn.countDown()
        upload.join(10_000)
        assertEquals(1, bernd.fetch().count { it.attachment != null })
    }

    /** f021: „lautlos“ arrives as such; f020: planned stays here until due, then goes – a deleted one never, „jetzt“ at once. */
    @Test
    fun silentAndPlanned() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        anna.send(cid, "Psst", silent = true)
        val got = bernd.fetch().filter { it.kind == News.Kind.Text }
        assertEquals(listOf("Psst" to true), got.map { it.text to it.silent })
        val now = System.currentTimeMillis() / 1000
        anna.send(cid, "Guten Morgen!", later = now + 3600)
        val gone = anna.send(cid, "Doch nicht", later = now + 3600)
        val soon = anna.send(cid, "Gleich", later = now + 7200)
        anna.deleteMessage(cid, gone.getString("mid"))
        assertEquals(now + 3600, anna.nextPlanned())
        assertEquals("geplant", anna.tick(cid, anna.findMessage(cid, soon.getString("mid"))!!).first)
        assertEquals(0, anna.sendDue(now + 60))
        assertTrue(bernd.fetch().none { it.kind == News.Kind.Text })
        anna.send(cid, "Zwischendurch")
        bernd.fetch()
        assertEquals(1, anna.sendDue(now + 3601))
        assertEquals(listOf("Guten Morgen!"), bernd.fetch().filter { it.kind == News.Kind.Text }.map { it.text })
        anna.chat(cid).getJSONArray("messages").let { assertEquals("wo sie losging", "Guten Morgen!", it.getJSONObject(it.length() - 1).optString("text")) }
        anna.sendPlannedNow(cid, soon.getString("mid"))
        assertEquals(listOf("Gleich"), bernd.fetch().filter { it.kind == News.Kind.Text }.map { it.text })
        assertEquals(0, anna.sendDue(now + 99_999))
        assertEquals(null, anna.nextPlanned())
        try { anna.send(cid, "Zu spät", later = now - 10); throw AssertionError("muss scheitern") } catch (e: LiChatException) { }
    }

    /** f012–f014: an effect travels with the message, an unknown one is dropped; Tinte stays covered in list and notification. */
    @Test
    fun effects() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        anna.send(cid, "Alles Gute!", effect = "konfetti")
        anna.send(cid, "Geheim", effect = "tinte")
        anna.send(cid, "Nix", effect = "rakete")
        val got = bernd.fetch().filter { it.kind == News.Kind.Text }
        assertEquals(listOf("Alles Gute!", "Nachricht mit unsichtbarer Tinte", "Nix"), got.map { it.text })
        val list = bernd.chat(cid).getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf("konfetti", "tinte", ""), list.takeLast(3).map { it.optString("effekt") })
        assertEquals("Nachricht mit unsichtbarer Tinte", io.github.veritasx1.lichat.core.describe(list.last { it.optString("effekt") == "tinte" }))
    }

    /** Card 8e7a493b: no way to the server – a plain sentence, never address and port on the screen; the cause only as detail. */
    @Test
    fun unreachablePlainSentence() {
        val error = try { Server("http://127.0.0.1:9").call("GET", "/v1/health"); null } catch (e: LiChatException) { e }
        assertEquals("Keine Verbindung zum Server. LiChat versucht es gleich wieder.", error?.message)
        assertTrue(error?.detail.orEmpty().contains("Exception"))
    }

    /** Card 72c8a716: what failed during an outage goes out by itself when the connection is back, in order – not older than a day. */
    @Test
    fun resendAfterOutage() {
        val anna = person("anna"); val bernd = person("bernd")
        val cid = anna.start(listOf("bernd"))
        bernd.fetch()
        val token = anna.server.token
        anna.server.token = "falsch"
        for (text in listOf("Alt", "Eins", "Zwei")) try { anna.send(cid, text); throw AssertionError("muss scheitern") } catch (e: LiChatException) { }
        val list = anna.chat(cid).getJSONArray("messages")
        val all = (0 until list.length()).map { list.getJSONObject(it) }
        all.first { it.optString("text") == "Alt" }.put("at", System.currentTimeMillis() / 1000 - RESEND_S - 60)
        assertEquals("Server weg: nichts geht", 0, anna.resendFailed())
        anna.server.token = token
        assertEquals(2, anna.resendFailed())
        assertEquals(listOf("Eins", "Zwei"), bernd.fetch().filter { it.kind == News.Kind.Text }.map { it.text })
        assertEquals(listOf("fehler", "gesendet", "gesendet"), all.map { anna.tick(cid, it).first }.takeLast(3))
        assertEquals("schon gesendet: nichts doppelt", 0, anna.resendFailed())
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

