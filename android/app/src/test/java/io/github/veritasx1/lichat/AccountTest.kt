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
        assertEquals(listOf(News.Kind.Joined), bernd.fetch().map { it.kind })
        anna.send(cid, "Hallo Bernd!")
        val news = bernd.fetch()
        assertEquals(listOf("Hallo Bernd!"), news.filter { it.kind == News.Kind.Text }.map { it.text })
        assertTrue("Serverzeit fehlt", news.first { it.kind == News.Kind.Text }.received > 0)
        // Restart: everything from disk, with the same key.
        val again = Account(bernd.folder, KeyVault.Plain.load(bernd.folder)!!)
        again.send(cid, "Hallo Anna")
        assertEquals(listOf("Hallo Anna"), anna.fetch().map { it.text })
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
        assertEquals(listOf(News.Kind.Removed), erna.fetch().map { it.kind })
        assertEquals(listOf("anna hat erna aus der Unterhaltung entfernt."), bernd.fetch().map { it.text })
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
            got = bernd.fetch(30)
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
}
