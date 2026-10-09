package io.github.veritasx1.lichat

import io.github.veritasx1.lichat.core.Account
import io.github.veritasx1.lichat.core.KeyVault
import io.github.veritasx1.lichat.core.LiChatException
import io.github.veritasx1.lichat.core.News
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            .redirectErrorStream(true).apply { environment()["XDG_DATA_HOME"] = File(root, "cli").absolutePath }.start()
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
        anna.server.token = "falsch"
        try { anna.send(cid, "Kommt das an?"); throw AssertionError("muss scheitern") } catch (e: LiChatException) { }
        assertEquals("fehler", anna.tick(cid, mine()).first)
        anna.server.token = anna.info.getString("token")
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
        val broken = io.github.veritasx1.lichat.core.Server("http://127.0.0.1:${page.localPort}", anna.info.getString("token"))
        val error = try { broken.call("GET", "/v1/messages"); null } catch (e: LiChatException) { e }
        assertNotNull("eine LiChatException statt eines Absturzes", error)
        assertTrue(error!!.message!!.contains("502"))
        page.close()
    }
}

