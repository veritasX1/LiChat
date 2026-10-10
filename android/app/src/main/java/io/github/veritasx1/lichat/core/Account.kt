package io.github.veritasx1.lichat.core

import org.json.JSONArray
import org.json.JSONObject
import uniffi.lichat_core.CoreException
import uniffi.lichat_core.Device
import uniffi.lichat_core.Incoming
import uniffi.lichat_core.conversationOf
import uniffi.lichat_core.keyPackageOwner
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * One LiChat account on this phone – the twin of linux/lichat/account.py: same files, same packets, same checks.
 *
 * On the wire (the server's blob): one tag byte and the core's bytes – „W“ + welcome, „M“ + MLS. Inside the MLS
 * application messages a small JSON: {"t": "roster" | "text" | "profile", …}.
 * On disk (folder 700, files 600): account.json, state.bin (sealed by the core), chats.bin (LICHATC1) and profile.bin
 * (LICHATP1), both AES-256-GCM with the device key. The key itself is kept by KeyVault (Android Keystore), never here.
 */

/** [suggestions]: free user names when the wanted one is taken (ebc3d629). */
class LiChatException(message: String, val suggestions: List<String> = emptyList(), val detail: String? = null) : Exception(message)

/** The bubble's ring was tapped while the attachment went up (cf647f61): stop, the bubble goes. */
class UploadCancelled : RuntimeException("Abgebrochen")

/** No way to the server: one plain sentence per kind – the raw text (public IP, port, the phone's home address) only as
 *  [LiChatException.detail] for the log, never on the screen (Michelle 8e7a493b). */
fun unreachable(e: java.io.IOException) = LiChatException(when (e) {
    is java.net.UnknownHostException -> NO_HOST
    is javax.net.ssl.SSLException -> NO_TLS
    else -> NO_WAY
}, detail = e.toString())

const val NO_HOST = "Server nicht gefunden. Besteht eine Internetverbindung?"
const val NO_TLS = "Keine sichere Verbindung zum Server möglich."
const val NO_WAY = "Keine Verbindung zum Server. LiChat versucht es gleich wieder."
/** The sentences that are over once the connection is back – the chat's red line goes with them (Michelle, Nachtest bdac9a1). */
val UNREACHABLE = setOf(NO_HOST, NO_TLS, NO_WAY)

private const val WELCOME: Byte = 'W'.code.toByte()
private const val MLS: Byte = 'M'.code.toByte()
private val CHATS_MAGIC = "LICHATC1".toByteArray()
private val PROFILE_MAGIC = "LICHATP1".toByteArray()
private val TOKEN_MAGIC = "LICHATT1".toByteArray()     // the device's server token, sealed like the rest (Michelle f09a8086)
const val SUPPLY = 20
const val PICTURE_MAX = 150 * 1024
private val ATTACHMENT_MAGIC = "LICHATA1".toByteArray()
private val HISTORY_MAGIC = "LICHATV1".toByteArray()
private val DRAFT_MAGIC = "LICHATE1".toByteArray()      // photos waiting above the input (f0-04), sealed like the rest
const val ATTACHMENT_MAX = 100 * 1024 * 1024 - 64
val KINDS = setOf("bild", "video", "sprache", "datei")
const val ONCE_DAYS = 14      // an unopened one-time picture lapses (fe9f2029; the server deletes it too)

/** The info's overview (43358d35), newest first: one-time pictures never, secret ones carry „geheim“. */
data class Media(val photos: List<JSONObject>, val links: List<Pair<String, JSONObject>>, val documents: List<JSONObject>, val voice: List<JSONObject>)
private const val TIMEOUT_S = 20
val MENTION = Regex("(?<![\\w@])@([a-zA-Z0-9][a-zA-Z0-9._-]{1,31})")
val EVERYONE = setOf("alle", "all")
const val UNREADABLE = "Eine Nachricht konnte nicht entschlüsselt werden."
const val EDIT_WINDOW_S = 15 * 60L   // like iOS: edit for 15 minutes …
const val EDIT_MAX = 5               // … at most five times
const val UNSEND_WINDOW_S = 2 * 60L  // and call back for two minutes
const val WAVE_MAX = 100            // bars in a voice message's wave at most (1a682a79)
const val ALBUM_MAX = 10            // photos picked together at most (5749ebd0, like Messages)
const val OUTBOX = "ausgang-"       // an attachment's local id until it is up (cf647f61)
const val RESEND_S = 24 * 3600L     // „Nicht gesendet“ goes out by itself when the connection is back – for a day (72c8a716)
private const val LATE_S = 60L       // slack for clocks a little apart (only the sender's own times are compared)
const val TYPING_EVERY_MS = 3_000L   // between two „tippt“ of mine
const val TYPING_FRESH_MS = 6_000L   // a „tippt“ stays shown this long after it arrived
private const val TYPING_LATE_S = 30 // an older one (fetched late) is ignored

fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** What arrived for the user. [received]: when the server took it (Michelle Z5: server_ms). */
data class News(val chat: String?, val kind: Kind, val from: String = "", val text: String = "", val at: Long = 0, val received: Double = 0.0,
                val attachment: JSONObject? = null, val mention: Boolean = false) {
    enum class Kind { Text, Joined, Picture, Removed, System, Problem, Receipt, Reaction, Typing, Edited, Synced, Settings }
}

/** One line for the list and notifications: the text, or what the attachment is (the same words as Ubuntu). */
/** „Olaf Winkler“ → „olaf.winkler“: a user name the server accepts, made from the name instead of refusing input. */
fun suggestUsername(first: String, last: String = ""): String {
    var text = "$first $last".trim().lowercase()
    listOf("ä" to "ae", "ö" to "oe", "ü" to "ue", "ß" to "ss").forEach { (a, b) -> text = text.replace(a, b) }
    text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "")
    return text.replace(Regex("[^a-z0-9._-]+"), ".").trim('.', '_', '-').replace(Regex("\\.{2,}"), ".").take(32)
}

private fun pictographic(cp: Int) = cp >= 0x1F000 || cp in setOf(0x00A9, 0x00AE, 0x3030, 0x303D, 0x3297, 0x3299, 0x2139) ||
    (cp in 0x2000..0x2BFF && !Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))

/** f015: how many emoji the text is – only emoji (spaces between allowed), else 0. 1–3 are shown big without a bubble
 *  like Messages. The twin of Python's emoji_only. */
fun emojiOnly(text: String): Int {
    val cps = text.codePoints().toArray()
    fun joins(cp: Int) = cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
    fun regional(cp: Int) = cp in 0x1F1E6..0x1F1FF
    var count = 0
    var i = 0
    while (i < cps.size) {
        val cp = cps[i]
        if (Character.isWhitespace(cp)) { i++; continue }
        val keycap = (cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A) && i + 1 < cps.size && (cps[i + 1] == 0xFE0F || cps[i + 1] == 0x20E3)
        if (!keycap && !pictographic(cp)) return 0
        i++
        if (regional(cp)) {
            if (i >= cps.size || !regional(cps[i])) return 0
            i++
        }
        while (i < cps.size) {
            if (joins(cps[i])) i++
            else if (cps[i] == 0x200D && i + 1 < cps.size && pictographic(cps[i + 1])) i += 2
            else break
        }
        count++
    }
    return count
}

/** Exactly one emoji – also as a sequence (👍🏽, 👩‍👩‍👧‍👦, 🇫🇷, 1️⃣, ❤️) – and nothing else (Michelle 09.10.: a reaction must not
 *  be „Gelesen“ or „OK“, else anyone could fake a status under a bubble). The twin of Python's single_emoji. */
fun isSingleEmoji(text: String): Boolean {
    val cps = text.codePoints().toArray()
    if (cps.isEmpty() || text.length > 16) return false
    fun joins(cp: Int) = cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
    fun regional(cp: Int) = cp in 0x1F1E6..0x1F1FF
    val first = cps[0]
    val keycap = (first in 0x30..0x39 || first == 0x23 || first == 0x2A) && cps.size > 1 && (cps[1] == 0xFE0F || cps[1] == 0x20E3)
    if (!keycap && !pictographic(first)) return false
    var end = 1
    if (regional(first)) {
        if (cps.size < 2 || !regional(cps[1])) return false
        end = 2
    }
    while (end < cps.size) {
        end += when {
            joins(cps[end]) -> 1
            cps[end] == 0x200D && end + 1 < cps.size && pictographic(cps[end + 1]) -> 2
            else -> return false
        }
    }
    return true
}

fun describe(message: JSONObject): String {
    if (message.optBoolean("zurueckgerufen")) return "Nachricht widerrufen"
    val info = message.optJSONObject("anhang") ?: return previewText(message.optString("text"))
    if (info.optBoolean("einmal")) return "Foto · Einmal-Ansicht"
    val label = when (info.optString("art")) {
        "bild" -> "Foto"; "video" -> "Video"; "sprache" -> "Sprachnachricht"
        else -> "Datei: " + info.optString("name")
    }
    return message.optString("text").takeIf { it.isNotBlank() }?.let { "$label: ${previewText(it)}" } ?: label   // with its caption (2fcc3eef)
}

/** https only – except for a server on this phone (adb reverse in tests: 127.0.0.1 is the computer). */
fun checkServer(url: String): String {
    val uri = URI(url.trim())
    val local = uri.host in setOf("127.0.0.1", "localhost", "::1")
    if (uri.scheme != "https" && !(uri.scheme == "http" && local)) throw LiChatException("Server nur über https (http nur auf diesem Gerät)")
    return url.trim().trimEnd('/')
}

class Server(url: String, var token: String? = null) {
    val url = checkServer(url)
    @Volatile private var open: HttpURLConnection? = null

    fun call(method: String, path: String, body: JSONObject? = null, timeoutS: Int = TIMEOUT_S): JSONObject {
        val connection = (URI(url + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = timeoutS * 1000
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        open = connection
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readBytes()) } ?: ""
            // nginx answers a restart with an HTML page (502) – that is a server error, never a crash (Michelle 09.10.).
            val json = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrNull()
            if (code !in 200..299) throw LiChatException("Server: ${json?.optString("error")?.ifBlank { null } ?: "Fehler"} ($code)",
                json?.optJSONArray("vorschlaege")?.let { a -> (0 until minOf(a.length(), 3)).mapNotNull { a.optString(it).takeIf { n -> n.isNotEmpty() } } } ?: emptyList())
            return json ?: throw LiChatException("Server: unverständliche Antwort ($code)")
        } catch (e: java.io.IOException) {
            throw unreachable(e)
        } finally {
            open = null
            connection.disconnect()
        }
    }

    /** Bytes in, bytes out – the attachments (up to 100 MB, no JSON around them). Going up in 64 KB pieces: [progress]
     *  hears (sent, total) after each, [cancelled] is asked before each (cf647f61). */
    fun raw(method: String, path: String, data: ByteArray? = null, progress: ((Long, Long) -> Unit)? = null,
            cancelled: () -> Boolean = { false }): ByteArray {
        val connection = (URI(url + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/octet-stream")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            if (data != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { out ->
                    var sent = 0
                    while (sent < data.size) {
                        if (cancelled()) throw UploadCancelled()
                        val piece = minOf(64 * 1024, data.size - sent)
                        out.write(data, sent, piece)
                        sent += piece
                        progress?.invoke(sent.toLong(), data.size.toLong())
                    }
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream?.use { String(it.readBytes()) } ?: ""
                val message = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw LiChatException("Server: ${message.ifBlank { "Fehler" }} ($code)")
            }
            return connection.inputStream.use { it.readBytes() }
        } catch (e: java.io.IOException) {
            throw unreachable(e)
        } finally {
            connection.disconnect()
        }
    }

    /** Ends a waiting fetch at once – the network changed, a new connection is better than a dead one. */
    fun interrupt() {
        open?.disconnect()
    }
}

private fun writePrivate(file: File, data: ByteArray) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { it.write(data); it.fd.sync() }
    tmp.setReadable(false, false); tmp.setReadable(true, true)
    tmp.setWritable(false, false); tmp.setWritable(true, true)
    if (!tmp.renameTo(file)) throw LiChatException("${file.name} nicht gespeichert")
}

private fun seal(key: ByteArray, magic: ByteArray, plain: ByteArray): ByteArray {
    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(magic)
    return magic + nonce + cipher.doFinal(plain)
}

private fun unseal(key: ByteArray, magic: ByteArray, sealed: ByteArray): ByteArray {
    if (sealed.size < magic.size + 28 || !sealed.copyOfRange(0, magic.size).contentEquals(magic)) throw LiChatException("Daten beschädigt")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, magic.size, 12))
    cipher.updateAAD(magic)
    return cipher.doFinal(sealed, magic.size + 12, sealed.size - magic.size - 12)
}

class Account(val folder: File, private val key: ByteArray) {
    val info: JSONObject
    val device: Device
    val chats: JSONObject
    val profiles: JSONObject
    val server: Server
    /** Held while the state changes (never during a call to the server); the screens read under it too. */
    val lock = Any()
    private val owePicture = mutableSetOf<String>()
    /** Chat → mids that arrived and still get their „zugestellt“ (card db70d358). */
    private val oweDelivered = mutableMapOf<String, MutableList<String>>()
    private val typingSeen = mutableMapOf<String, MutableMap<String, Long>>()   // chat → sender → when (ms), never saved
    private val typed = mutableMapOf<String, Long>()                              // chat → when I last said „tippt“
    /** Chat → people who left and whose devices I take out of the MLS group (the first remaining member does it). */
    private val oweRemoval = mutableMapOf<String, MutableSet<String>>()

    init {
        val accountFile = File(folder, "account.json")
        if (!accountFile.exists()) throw LiChatException("Noch kein Konto")
        info = JSONObject(accountFile.readText())
        device = try { Device.load(File(folder, "state.bin").readBytes(), key) } catch (e: CoreException) {
            throw LiChatException("Gerätezustand lässt sich nicht öffnen")
        }
        chats = File(folder, "chats.bin").takeIf { it.exists() }?.let { JSONObject(String(unseal(key, CHATS_MAGIC, it.readBytes()))) } ?: JSONObject()
        profiles = File(folder, "profile.bin").takeIf { it.exists() }?.let { JSONObject(String(unseal(key, PROFILE_MAGIC, it.readBytes()))) }
            ?: JSONObject().put("me", JSONObject.NULL).put("people", JSONObject())
        val plain = info.optString("token")
        val token = if (plain.isNotEmpty()) {
            // An account from before 09.10. evening: the token moves out of account.json into token.bin (sealed), no new
            // sign-in (Michelle f09a8086: a copied account.json let someone fetch – and so lose – this device's packets).
            // The old token stays in backups of account.json – renewed at the first fetch (Michelle, renewTokenIfDue).
            writePrivate(File(folder, "token.bin"), seal(key, TOKEN_MAGIC, JSONObject().put("token", plain).put("erneuern", true).toString().toByteArray()))
            info.remove("token")
            writePrivate(accountFile, info.toString(1).toByteArray())
            plain
        } else {
            File(folder, "token.bin").takeIf { it.exists() }?.let { JSONObject(String(unseal(key, TOKEN_MAGIC, it.readBytes()))).optString("token") }
                ?.takeIf { it.isNotEmpty() } ?: throw LiChatException("Anmeldedaten fehlen – dieses Gerät bitte neu koppeln")
        }
        server = Server(info.getString("server"), token)
    }

    val username: String get() = info.getString("username")
    val deviceId: String get() = info.getString("device")

    companion object {
        fun register(folder: File, server: String, invite: String, username: String, key: ByteArray, first: String = "", last: String = ""): Account {
            if (File(folder, "account.json").exists()) throw LiChatException("Hier gibt es schon ein Konto")
            folder.mkdirs()
            val anonymous = Server(server)
            val device = Device(username)
            // The real name only for suggestions when the user name is taken (ebc3d629) – the server keeps none of it.
            val answer = anonymous.call("POST", "/v1/register", JSONObject().put("invite", invite).put("username", username)
                .put("vorname", first.take(40)).put("nachname", last.take(40)).put("identity", b64(device.identityKey())))
            val info = JSONObject().put("server", anonymous.url).put("username", username)
                .put("device", answer.getString("device"))
            // Key packages are made – their private halves kept in the state – before they are offered.
            val packages = device.keyPackages(SUPPLY.toUInt())
            val reserve = device.lastResortKeyPackage()
            writePrivate(File(folder, "state.bin"), device.save(key))
            writePrivate(File(folder, "token.bin"), seal(key, TOKEN_MAGIC, JSONObject().put("token", answer.getString("token")).toString().toByteArray()))
            writePrivate(File(folder, "account.json"), info.toString(1).toByteArray())
            device.close()
            val account = Account(folder, key)
            account.save()
            account.server.call("POST", "/v1/key-packages", JSONObject().put("key_packages", JSONArray(packages.map { b64(it) })))
            account.server.call("PUT", "/v1/key-packages/last-resort", JSONObject().put("key_package", b64(reserve)))
            account.invitedBy = answer.optString("von").takeIf { Regex("[a-z0-9._-]{2,32}").matches(it) }
            return account
        }

        /** (server, code) from an invitation's QR code or link (Olaf 10.10.: „der qr code übergibt alle daten zum server“):
         *  lichat://einladung?server=…&code=…, or the page https://host/e/CODE that any phone camera opens. */
        fun parseInviteUri(text: String): Pair<String, String>? {
            val clean = text.trim()
            Regex("^(https?://[^/\\s]+)/e/([A-Za-z0-9-]{4,40})/?$").find(clean)?.let { return it.groupValues[1] to it.groupValues[2].uppercase() }
            val uri = runCatching { URI(clean) }.getOrNull() ?: return null
            if (uri.scheme != "lichat" || uri.host != "einladung") return null
            val query = (uri.rawQuery ?: "").split("&").mapNotNull { part -> part.split("=", limit = 2).takeIf { it.size == 2 } }
                .associate { (k, v) -> k to java.net.URLDecoder.decode(v, "UTF-8") }
            val code = query["code"]?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return (query["server"] ?: "") to code
        }

        // -------------------------------------------------------------------------------------- linking (card da5cbfcb)
        // Like Signal: the NEW device shows a code, an existing device of the person accepts it, then the new device
        // makes its keys for that name and signs in once; the existing device puts it into every conversation.

        /** What the new device's QR code says (Olaf: „per QR-Code koppeln“): lichat://koppeln?server=…&code=… */
        fun linkUri(server: String, code: String) = "lichat://koppeln?server=${URLEncoder.encode(server, "UTF-8")}&code=$code"

        /** 16 bytes of SHA-256 over a user's identity keys (sorted byte-wise) – what a contact QR carries (89aae55d);
         *  the same as Python's Account.fingerprint, so a phone's code works in the Ubuntu app and back. */
        fun fingerprint(identities: Collection<ByteArray>): String {
            val sorted = identities.sortedWith { a, b ->
                (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] }?.let { (a[it].toInt() and 0xFF) - (b[it].toInt() and 0xFF) } ?: (a.size - b.size)
            }
            return java.security.MessageDigest.getInstance("SHA-256").digest(sorted.fold(ByteArray(0)) { all, k -> all + k }).hex().take(32)
        }

        /** (server, name, fingerprint) from a scanned/pasted lichat://kontakt link, or null. */
        fun parseContactUri(text: String): Triple<String, String, String>? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "lichat" || uri.host != "kontakt") return null
            val query = (uri.rawQuery ?: "").split("&").mapNotNull { part -> part.split("=", limit = 2).takeIf { it.size == 2 } }
                .associate { (k, v) -> k to java.net.URLDecoder.decode(v, "UTF-8") }
            val name = query["name"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return Triple(query["server"] ?: "", name, query["fp"] ?: "")
        }

        /** (server, code) from a scanned/pasted lichat://koppeln link, or null. */
        fun parseLinkUri(text: String): Pair<String, String>? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "lichat" || uri.host != "koppeln") return null
            val query = (uri.rawQuery ?: "").split("&").mapNotNull { part -> part.split("=", limit = 2).takeIf { it.size == 2 } }
                .associate { (k, v) -> k to java.net.URLDecoder.decode(v, "UTF-8") }
            val code = query["code"]?.uppercase() ?: return null
            return (query["server"] ?: "") to code
        }

        /** New device, step 1: (server url, code to show). */
        fun linkOpen(server: String): Pair<String, String> {
            val anonymous = Server(server)
            return anonymous.url to anonymous.call("POST", "/v1/kopplung", JSONObject()).getString("code")
        }

        /** {} while waiting, {username} once accepted, {username, device} once signed in. */
        fun linkState(server: String, code: String): JSONObject = Server(server).call("GET", "/v1/kopplung/${URLEncoder.encode(code, "UTF-8")}")

        /** New device, step 3 (after an existing device accepted): own keys for that name, signed in once. The name stays
         *  on this device – never on the server (Michelle). */
        fun linkFinish(folder: File, server: String, code: String, name: String, key: ByteArray): Account {
            if (File(folder, "account.json").exists()) throw LiChatException("Hier gibt es schon ein Konto")
            val anonymous = Server(server)
            val path = "/v1/kopplung/${URLEncoder.encode(code, "UTF-8")}"
            val username = anonymous.call("GET", path).optString("username")
            if (username.isEmpty()) throw LiChatException("Noch nicht angenommen – auf dem anderen Gerät „Gerät hinzufügen“ und den Code eingeben")
            folder.mkdirs()
            val device = Device(username)
            val answer = anonymous.call("POST", "$path/geraet", JSONObject().put("identity", b64(device.identityKey())))
            val info = JSONObject().put("server", anonymous.url).put("username", username)
                .put("device", answer.getString("device"))
            val packages = device.keyPackages(SUPPLY.toUInt())
            val reserve = device.lastResortKeyPackage()
            writePrivate(File(folder, "state.bin"), device.save(key))
            writePrivate(File(folder, "token.bin"), seal(key, TOKEN_MAGIC, JSONObject().put("token", answer.getString("token")).toString().toByteArray()))
            writePrivate(File(folder, "account.json"), info.toString(1).toByteArray())
            device.close()
            val account = Account(folder, key)
            account.profiles.put("gekoppelt", System.currentTimeMillis() / 1000)
            if (name.isNotBlank()) account.profiles.put("geraetename", name.trim().take(40))
            account.save()
            account.server.call("POST", "/v1/key-packages", JSONObject().put("key_packages", JSONArray(packages.map { b64(it) })))
            account.server.call("PUT", "/v1/key-packages/last-resort", JSONObject().put("key_package", b64(reserve)))
            return account
        }
    }

    data class OwnDevice(val id: String, val since: Long, val isThis: Boolean, val name: String)

    /** Existing device: that code is my new device – waits until it signed in, then puts it into every conversation. */
    fun acceptLink(typed: String, waitS: Long = 600, pollMs: Long = 2000): String {
        // typed, or the scanned lichat://koppeln link
        val code = parseLinkUri(typed)?.let { (other, code) ->
            if (other.isNotEmpty() && other.trimEnd('/') != server.url.trimEnd('/')) throw LiChatException("Das neue Gerät hat einen anderen Server gewählt")
            code
        } ?: typed.trim()
        server.call("POST", "/v1/kopplung/annehmen", JSONObject().put("code", code))
        val end = System.currentTimeMillis() + waitS * 1000
        while (System.currentTimeMillis() < end) {
            val state = server.call("GET", "/v1/kopplung/${URLEncoder.encode(code, "UTF-8")}")
            state.optString("device").takeIf { it.isNotEmpty() }?.let {
                syncOwnDevices()
                ownGroup()            // my devices' private group (holds the new one now)
                sendHistory(it)       // Olaf: „synchronisieren sich einmal und bleiben dann synchronisiert“
                return it
            }
            Thread.sleep(pollMs)
        }
        throw LiChatException("Das neue Gerät hat sich nicht gemeldet – noch einmal versuchen")
    }

    // ------------------------------------------------------------------------------------------ my devices' own group
    // A conversation of my devices only („intern“, never shown): the history goes through it once when a device is
    // linked, my settings follow on every change. The twin of Python's own_group/send_history – the same format.

    fun ownGroupId(): String? = chats.keys().asSequence().firstOrNull { chats.getJSONObject(it).optBoolean("intern") && chats.getJSONObject(it).optString("removed").isEmpty() }

    /** The conversations people see (without my devices' own group). */
    fun visibleChats(): List<String> = chats.keys().asSequence().filter { !chats.getJSONObject(it).optBoolean("intern") }.toList()

    fun ownGroup(): String? {
        ownGroupId()?.let { return it }
        val listed = directory(username)
        val packages = mutableListOf<ByteArray>()
        val devices = mutableListOf<String>()
        for (id in (listed.keys - deviceId).sorted()) {
            val claimed = server.call("POST", "/v1/key-packages/${URLEncoder.encode(username, "UTF-8")}", JSONObject().put("device", id)).getJSONArray("key_packages")
            for (i in 0 until claimed.length()) {
                val item = claimed.getJSONObject(i)
                val bytes = unb64(item.getString("key_package"))
                val owner = try { keyPackageOwner(bytes) } catch (e: CoreException) { continue }
                if (item.getString("device") == id && owner.identity == username && listed[id]?.contentEquals(owner.signatureKey) == true) {
                    packages.add(bytes); devices.add(id)
                }
            }
        }
        if (packages.isEmpty()) return null
        val roster = JSONObject().put(username, JSONArray((devices + deviceId).sorted()))
        val cid: String
        val welcome: ByteArray
        val hello: ByteArray
        synchronized(lock) {
            val started = device.startConversation(packages)
            cid = started.conversation.hex()
            welcome = started.welcome
            chats.put(cid, newChat().put("roster", roster).put("intern", true))
            hello = device.encrypt(started.conversation, payload(JSONObject().put("t", "roster").put("devices", roster).put("title", "").put("intern", true)))
            save()
        }
        post(devices, byteArrayOf(WELCOME) + welcome)
        post(devices, byteArrayOf(MLS) + hello)
        return cid
    }

    private fun tellOwnDevices(content: JSONObject) {
        val cid = ownGroupId() ?: return
        if (targets(cid).isNotEmpty()) groupMessage(cid, content, null)
    }

    /** Everything I have, once to my new device: sealed with a fresh key, put on the server as an attachment, the key only
     *  inside my devices' own group. */
    fun sendHistory(newDevice: String): Boolean {
        val cid = ownGroupId() ?: return false
        val archive = synchronized(lock) {
            val list = JSONObject()
            for (c in visibleChats()) {
                val chat = chat(c)
                list.put(c, JSONObject().put("title", chat.optString("title")).put("bild", chat.opt("bild") ?: JSONObject.NULL)
                    .put("stumm", chat.optBoolean("stumm")).put("messages", chat.getJSONArray("messages")))
            }
            val profile = JSONObject(profiles.toString()).apply { remove("gekoppelt"); remove("geraetename") }
            JSONObject().put("chats", list).put("profile", profile).toString().toByteArray()
        }
        val deflater = java.util.zip.Deflater(9)
        deflater.setInput(archive); deflater.finish()
        val packed = java.io.ByteArrayOutputStream().also { out -> val buf = ByteArray(65536); while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf)) }.toByteArray()
        deflater.end()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(HISTORY_MAGIC)
        val sealed = nonce + cipher.doFinal(packed)
        if (sealed.size > ATTACHMENT_MAX) throw LiChatException("Der Verlauf ist zu groß für eine Übertragung")
        val id = JSONObject(String(server.raw("PUT", "/v1/anhaenge", sealed))).getString("id")
        groupMessage(cid, JSONObject().put("t", "verlauf").put("fuer", newDevice).put("id", id).put("schluessel", b64(key))
            .put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(sealed).hex()), null)
        return true
    }

    private fun takeHistory(data: JSONObject): List<News> {
        val sealed = server.raw("GET", "/v1/anhaenge/${data.getString("id")}")
        if (java.security.MessageDigest.getInstance("SHA-256").digest(sealed).hex() != data.optString("sha256"))
            throw LiChatException("Verlauf verändert – nicht übernommen")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(unb64(data.getString("schluessel")), "AES"), GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(HISTORY_MAGIC)
        val packed = cipher.doFinal(sealed, 12, sealed.size - 12)
        val inflater = java.util.zip.Inflater()
        inflater.setInput(packed)
        val plain = java.io.ByteArrayOutputStream().also { out -> val buf = ByteArray(65536); while (!inflater.finished()) { val n = inflater.inflate(buf); if (n == 0 && inflater.needsInput()) break; out.write(buf, 0, n) } }.toByteArray()
        inflater.end()
        val archive = JSONObject(String(plain))
        val old = archive.optJSONObject("chats") ?: JSONObject()
        for (c in old.keys()) {
            val from = old.getJSONObject(c)
            if (!chats.has(c)) chats.put(c, newChat())
            val chat = chat(c)
            val before = from.optJSONArray("messages") ?: JSONArray()
            val known = (0 until before.length()).mapNotNull { before.getJSONObject(it).optString("mid").takeIf { m -> m.isNotEmpty() } }.toSet()
            val merged = JSONArray()
            for (i in 0 until before.length()) merged.put(before.getJSONObject(i))
            val now = chat.getJSONArray("messages")
            for (i in 0 until now.length()) {
                val m = now.getJSONObject(i)
                if (m.optString("text") == "Ältere Nachrichten gibt es nur auf deinen anderen Geräten.") continue
                if (m.optString("mid").isNotEmpty() && m.optString("mid") in known) continue
                merged.put(m)
            }
            chat.put("messages", merged)
            if (chat.optString("title").isEmpty() && from.optString("title").isNotEmpty()) chat.put("title", from.optString("title"))
            if (chat.opt("bild") !is String && from.opt("bild") is String) chat.put("bild", from.getString("bild"))
            if (from.optBoolean("stumm")) chat.put("stumm", true)
        }
        val profile = archive.optJSONObject("profile") ?: JSONObject()
        for (k in profile.keys()) {
            val value = profile.get(k)
            if (k in setOf("people", "names", "kontakte") && value is JSONObject) {
                val mine = profiles.optJSONObject(k) ?: JSONObject().also { profiles.put(k, it) }
                for (n in value.keys()) mine.put(n, value.get(n))
            } else if (!profiles.has(k) || profiles.isNull(k) || profiles.optString(k).isEmpty() || (profiles.opt(k) as? JSONObject)?.length() == 0) {
                profiles.put(k, value)
            }
        }
        return listOf(News(null, News.Kind.Synced))
    }

    /** My devices with the time each signed in; a name only for this one (it stays here). */
    fun myDevices(): List<OwnDevice> = server.call("GET", "/v1/devices").getJSONArray("devices").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            val mine = it.getString("device") == deviceId
            OwnDevice(it.getString("device"), it.optLong("created"), mine, if (mine) profiles.optString("geraetename") else "")
        }
    }

    /** Signs out another of my devices and takes it out of every conversation – it reads nothing that follows. */
    fun removeMyDevice(id: String) {
        if (id == deviceId) throw LiChatException("Dieses Gerät meldest du in seinen eigenen Einstellungen ab")
        server.call("DELETE", "/v1/devices/$id")
        syncOwnDevices()
    }

    /** Every conversation holds exactly my devices from the directory: new ones added (MLS), signed-out ones taken out. */
    fun syncOwnDevices(): Int {
        val listed = directory(username)
        val listedKeys = listed.values.toList()
        var changed = 0
        for (cid in chats.keys().asSequence().toList()) {
            val chat = chat(cid)
            if (chat.optString("removed").isNotEmpty()) continue
            val keys = try { device.deviceKeys(cid.unhex(), username) } catch (e: CoreException) { continue }
            val roster = chat.getJSONObject("roster")
            val have = roster.optJSONArray(username)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
            val goneKeys = keys.filter { k -> listedKeys.none { it.contentEquals(k) } }
            for (key in goneKeys) {
                val before: List<String>
                val commit: ByteArray
                synchronized(lock) {
                    before = targets(cid)
                    commit = device.removeDevice(cid.unhex(), key)
                    roster.put(username, JSONArray(have.filter { it in listed.keys }))
                    save()
                }
                post(before, byteArrayOf(MLS) + commit)
            }
            val packages = mutableListOf<ByteArray>()
            val adding = mutableListOf<String>()
            for (id in (listed.keys - have).sorted()) {
                val claimed = server.call("POST", "/v1/key-packages/${URLEncoder.encode(username, "UTF-8")}", JSONObject().put("device", id))
                    .getJSONArray("key_packages")
                for (i in 0 until claimed.length()) {
                    val item = claimed.getJSONObject(i)
                    val bytes = unb64(item.getString("key_package"))
                    val owner = try { keyPackageOwner(bytes) } catch (e: CoreException) { continue }
                    if (item.getString("device") == id && owner.identity == username && listed[id]?.contentEquals(owner.signatureKey) == true) {
                        packages.add(bytes); adding.add(id)
                    }
                }
            }
            if (packages.isNotEmpty()) {
                val before: List<String>
                val everyone: List<String>
                val started: uniffi.lichat_core.Started
                val packets = mutableListOf<ByteArray>()
                synchronized(lock) {
                    before = targets(cid)
                    started = device.addMembers(cid.unhex(), packages)
                    val now = roster.optJSONArray(username)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    roster.put(username, JSONArray((now + adding).toSortedSet().toList()))
                    val payloads = mutableListOf(JSONObject().put("t", "roster").put("devices", roster).put("title", chat.optString("title")))
                    if (chat.optBoolean("intern")) payloads[0].put("intern", true)   // a third device knows it as my devices' group
                    (chat.opt("bild") as? String)?.takeIf { it.isNotEmpty() }?.let { payloads.add(JSONObject().put("t", "gruppenbild").put("bild", it)) }
                    payloads.forEach { packets.add(device.encrypt(cid.unhex(), payload(it))) }
                    save()
                    everyone = targets(cid)
                }
                post(before, byteArrayOf(MLS) + started.conversation)
                post(adding, byteArrayOf(WELCOME) + started.welcome)
                packets.forEach { post(everyone, byteArrayOf(MLS) + it) }
            }
            if (packages.isNotEmpty() || goneKeys.isNotEmpty()) changed++
        }
        return changed
    }

    fun save() {
        writePrivate(File(folder, "state.bin"), device.save(key))
        writePrivate(File(folder, "chats.bin"), seal(key, CHATS_MAGIC, chats.toString().toByteArray()))
        writePrivate(File(folder, "profile.bin"), seal(key, PROFILE_MAGIC, profiles.toString().toByteArray()))
    }

    // ------------------------------------------------------------------------------------------ conversations

    fun chat(cid: String): JSONObject = chats.getJSONObject(cid)

    private fun newChat(title: String = "") = JSONObject().put("title", title).put("roster", JSONObject()).put("messages", JSONArray())

    private fun payload(data: JSONObject) = data.toString().toByteArray()

    fun targets(cid: String): List<String> {
        val roster = chat(cid).getJSONObject("roster")
        return roster.keys().asSequence().flatMap { user -> roster.getJSONArray(user).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .filter { it != deviceId }.toSortedSet().toList()
    }

    private fun post(targets: List<String>, blob: ByteArray): JSONObject =
        server.call("POST", "/v1/messages", JSONObject().put("to", JSONArray(targets)).put("blob", b64(blob)))

    /** A conversation with these people (all their devices). Every claimed key package must belong to the user and the
     *  device the directory names – the server cannot slip in a device of its own. */
    fun start(usernames: List<String>, title: String = ""): String {
        val others = usernames.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != username }.toSortedSet()
        if (others.isEmpty()) throw LiChatException("Mit wem? Mindestens eine andere Person")
        checkNames(others)
        val (roster, packages) = claim(others.toList())
        roster.put(username, JSONArray().put(deviceId))
        val packets = mutableListOf<ByteArray>()
        val welcome: ByteArray
        val cid: String
        val targets: List<String>
        synchronized(lock) {
            val started = device.startConversation(packages)
            welcome = started.welcome
            cid = started.conversation.hex()
            chats.put(cid, newChat(title).put("roster", roster))
            packets.add(device.encrypt(started.conversation, payload(JSONObject().put("t", "roster").put("devices", roster).put("title", title))))
            profiles.optString("me").takeIf { profiles.get("me") != JSONObject.NULL && it.isNotEmpty() }?.let { me ->
                packets.add(device.encrypt(started.conversation, payload(JSONObject().put("t", "profile").put("bild", me))))
            }
            profiles.optJSONObject("name")?.let { packets.add(device.encrypt(started.conversation, payload(JSONObject(it.toString()).put("t", "name")))) }
            save()
            targets = targets(cid)
        }
        post(targets, byteArrayOf(WELCOME) + welcome)
        packets.forEach { post(targets, byteArrayOf(MLS) + it) }
        syncOwnDevices()      // my other devices belong in it too (card da5cbfcb)
        return cid
    }

    /** One key package per device of each user – each checked against the directory (name and identity key). */
    private fun claim(users: List<String>): Pair<JSONObject, List<ByteArray>> {
        val roster = JSONObject()
        val packages = mutableListOf<ByteArray>()
        for (user in users) {
            val name = URLEncoder.encode(user, "UTF-8")
            val listed = directory(user)
            val claimed = server.call("POST", "/v1/key-packages/$name", JSONObject()).getJSONArray("key_packages")
            if (claimed.length() == 0) throw LiChatException("$user hat gerade keinen Schlüssel frei")
            val devices = JSONArray()
            for (i in 0 until claimed.length()) {
                val item = claimed.getJSONObject(i)
                val packageBytes = unb64(item.getString("key_package"))
                val owner = try { keyPackageOwner(packageBytes) } catch (e: CoreException) { throw LiChatException("Key-Package von $user ungültig") }
                if (owner.identity != user || !(listed[item.getString("device")]?.contentEquals(owner.signatureKey) ?: false))
                    throw LiChatException("Key-Package passt nicht zu $user – nichts geändert")
                devices.put(item.getString("device"))
                packages.add(packageBytes)
            }
            roster.put(user, devices)
        }
        return roster to packages
    }

    private fun directory(user: String): Map<String, ByteArray> = try {
        server.call("GET", "/v1/users/${URLEncoder.encode(user, "UTF-8")}").getJSONArray("devices").let { a ->
            (0 until a.length()).associate { a.getJSONObject(it).getString("device") to unb64(a.getJSONObject(it).getString("identity")) }
        }
    } catch (e: LiChatException) {
        if ("(404)" in (e.message ?: "")) emptyMap() else throw e
    }

    // ------------------------------------------------------------------------------------------ groups (card d45b319a)

    /** Adds people: the commit to everyone so far, the welcome to the new devices, then roster, name, picture to all. */
    fun add(cid: String, usernames: List<String>) {
        val chat = chat(cid)
        if (chat.optString("removed").isNotEmpty()) throw LiChatException("Du bist nicht mehr in diesem Gespräch")
        val roster = chat.getJSONObject("roster")
        val new = usernames.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != username && !roster.has(it) }.toSortedSet().toList()
        if (new.isEmpty()) throw LiChatException("Wen hinzufügen? Die Person ist schon dabei")
        checkNames(new)          // before anything changes – an unknown name leaves the group as it was (Michelle 4da136eb)
        val (added, packages) = claim(new)
        val before: List<String>
        val everyone: List<String>
        val started: uniffi.lichat_core.Started
        val packets = mutableListOf<ByteArray>()
        synchronized(lock) {
            before = targets(cid)
            started = device.addMembers(cid.unhex(), packages)
            added.keys().forEach { roster.put(it, added.getJSONArray(it)) }
            new.forEach { system(chat, "Du hast $it hinzugefügt.") }
            val payloads = mutableListOf(JSONObject().put("t", "roster").put("devices", roster).put("title", chat.optString("title")))
            (chat.opt("bild") as? String)?.takeIf { it.isNotEmpty() }?.let { payloads.add(JSONObject().put("t", "gruppenbild").put("bild", it)) }
            (profiles.opt("me") as? String)?.takeIf { it.isNotEmpty() }?.let { payloads.add(JSONObject().put("t", "profile").put("bild", it)) }
            profiles.optJSONObject("name")?.let { payloads.add(JSONObject(it.toString()).put("t", "name")) }
            payloads.forEach { packets.add(device.encrypt(cid.unhex(), payload(it))) }
            save()
            everyone = targets(cid)
        }
        post(before, byteArrayOf(MLS) + started.conversation)
        post(everyone.filter { it !in before }, byteArrayOf(WELCOME) + started.welcome)
        packets.forEach { post(everyone, byteArrayOf(MLS) + it) }
    }

    private fun groupMessage(cid: String, content: JSONObject, line: String?) {
        val chat = chat(cid)
        if (chat.optString("removed").isNotEmpty()) throw LiChatException("Du bist nicht mehr in diesem Gespräch")
        val packet: ByteArray
        val targets: List<String>
        synchronized(lock) {
            line?.let { system(chat, it) }
            packet = device.encrypt(cid.unhex(), payload(content))
            save()
            targets = targets(cid)
        }
        post(targets, byteArrayOf(MLS) + packet)
    }

    /** The group's name – only inside the conversation, never on the server. */
    fun rename(cid: String, title: String) {
        val clean = title.trim().take(80)
        chat(cid).put("title", clean)
        groupMessage(cid, JSONObject().put("t", "titel").put("titel", clean),
            if (clean.isNotEmpty()) "Du hast die Gruppe „$clean“ genannt." else "Du hast den Gruppennamen entfernt.")
    }

    /** The group's picture (already 512 px without EXIF – Pictures.prepare); null removes it. */
    fun setGroupPicture(cid: String, jpeg: ByteArray?) {
        val value = jpeg?.let { b64(it) } ?: ""
        chat(cid).put("bild", if (value.isEmpty()) JSONObject.NULL else value)
        groupMessage(cid, JSONObject().put("t", "gruppenbild").put("bild", value),
            if (value.isNotEmpty()) "Du hast das Gruppenbild geändert." else "Du hast das Gruppenbild entfernt.")
    }

    /** Leaving: the others are told, the first of them takes this device out; here the history stays readable. */
    fun leave(cid: String) {
        groupMessage(cid, JSONObject().put("t", "verlassen"), "Du hast die Unterhaltung verlassen.")
        synchronized(lock) { chat(cid).put("removed", username); save() }
    }

    /** Stummschalten – on all my devices (their own group). */
    fun mute(cid: String, on: Boolean) = chatSetting(cid, "stumm", on)

    // ------------------------------------------------------------------------------------------ Mac points (Michelle 09.10.)
    // Twin of Python: pinned (206118bf), unread marker (300129f8), receipts per chat (3c0400ad), blocking + filters
    // (1f800b4f), „Zuletzt gelöscht“ (a0ceee6e). Chat settings follow on my devices; deleting stays on this one.

    private val syncedChatKeys = setOf("stumm", "angeheftet", "lesebestaetigungen", "ungelesen", "gelesen_mid")

    private fun chatSetting(cid: String, key: String, value: Any?, tell: Boolean = true) {
        synchronized(lock) { if (value == null) chat(cid).remove(key) else chat(cid).put(key, value); save() }
        if (tell && key in syncedChatKeys) tellOwnDevices(JSONObject().put("t", "gespraech").put("chat", cid).put("key", key).put("value", value ?: JSONObject.NULL))
    }

    /** What I had begun to type here (979f9b22, like Messages): kept per conversation, also over a restart – only on this
     *  phone, sealed with the chats, never sent to my other devices; empty once sent. */
    fun draft(cid: String): String = chats.optJSONObject(cid)?.optString("entwurf").orEmpty()

    fun setDraft(cid: String, text: String) {
        if (draft(cid) == text || chats.optJSONObject(cid) == null) return
        chatSetting(cid, "entwurf", text.takeIf { it.isNotBlank() }, tell = false)
    }

    /** f004: the message I had begun to answer here (its mid) – it waits with the draft, like Messages. */
    fun draftReply(cid: String): String? = chats.optJSONObject(cid)?.optString("entwurf_antwort")?.takeIf { it.isNotEmpty() }

    fun setDraftReply(cid: String, mid: String?) {
        if (chats.optJSONObject(cid) == null || draftReply(cid) == mid?.takeIf { it.isNotEmpty() }) return
        chatSetting(cid, "entwurf_antwort", mid?.takeIf { it.isNotEmpty() }, tell = false)
    }

    /** f003: something begun here – text, the answer to a message or photos – shown in the list as „Entwurf:“. */
    fun hasDraft(cid: String): Boolean = draft(cid).isNotBlank() || draftReply(cid) != null ||
        File(draftFolder(cid), "liste.bin").exists()

    fun receiptsFor(cid: String): Boolean {
        val chat = chats.optJSONObject(cid) ?: return readReceipts
        return if (chat.has("lesebestaetigungen")) chat.optBoolean("lesebestaetigungen") else readReceipts
    }

    /** null = like the general setting again. */
    fun setReceiptsFor(cid: String, on: Boolean?) = chatSetting(cid, "lesebestaetigungen", on)

    fun pin(cid: String, on: Boolean) = chatSetting(cid, "angeheftet", if (on) System.currentTimeMillis() / 1000 else null)

    fun pinned(): List<String> = visibleChats().filter { chat(it).has("angeheftet") && !chat(it).has("geloescht") }.sortedBy { chat(it).optLong("angeheftet") }

    private fun liveMessages(cid: String): List<JSONObject> = chat(cid).getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .filter { !it.has("geloescht") }

    private fun readUpTo(cid: String) {
        val chat = chat(cid)
        val last = liveMessages(cid).lastOrNull { it.has("mid") }?.getString("mid")
        if (chat.optBoolean("ungelesen")) chatSetting(cid, "ungelesen", null)
        if (last != null && chat.optString("gelesen_mid") != last) chatSetting(cid, "gelesen_mid", last)
    }

    /** „Als ungelesen markieren“ – the blue dot comes back (on all my devices). */
    fun markUnread(cid: String) = chatSetting(cid, "ungelesen", true)

    /** Messages of others after the read marker (a chat marked unread counts at least 1). */
    fun unread(cid: String): Int {
        val chat = chats.optJSONObject(cid) ?: return 0
        val messages = liveMessages(cid)
        val marker = chat.optString("gelesen_mid").ifEmpty { null }
        val index = messages.indexOfFirst { marker != null && it.optString("mid") == marker }
        val after = if (index >= 0) messages.drop(index + 1) else if (marker == null) emptyList() else messages
        val count = after.count { it.optString("from") !in setOf("", username) && !it.optBoolean("system") }
        return maxOf(count, if (chat.optBoolean("ungelesen")) 1 else 0)
    }

    /** The mid of the first unread message („↑ Zur ersten ungelesenen“), or null. */
    fun firstUnread(cid: String): String? {
        val messages = liveMessages(cid)
        val marker = chat(cid).optString("gelesen_mid").ifEmpty { return null }
        val index = messages.indexOfFirst { it.optString("mid") == marker }
        if (index < 0) return null
        return messages.drop(index + 1).firstOrNull { it.optString("from") !in setOf("", username) && it.has("mid") }?.getString("mid")
    }

    val blocked: JSONObject get() = profiles.optJSONObject("blockiert") ?: JSONObject().also { profiles.put("blockiert", it) }

    /** Nothing more from this person: messages, reactions, „tippt“ are dropped, no receipts, no notification. */
    fun block(user: String, on: Boolean = true) {
        synchronized(lock) { if (on) blocked.put(user, System.currentTimeMillis() / 1000) else blocked.remove(user); save() }
        tellOwnDevices(JSONObject().put("t", "blockiert").put("name", user).put("an", on))
    }

    private val contactsObject: JSONObject get() = profiles.optJSONObject("kontakte") ?: JSONObject()

    // ------------------------------------------------------------------------------------------ contacts (89aae55d)
    // Olaf 10.10.: „man soll auch einen QR code des anderen scannen können und man kommt direkt zu ihm in den
    // gesprächsdialog“ – no exact typing of names. The QR holds only name, server and the keys' fingerprint.

    /** My contact as a link (and QR code): lichat://kontakt?server=…&name=…&fp=… */
    fun contactLink(): String {
        val fp = fingerprint(directory(username).values.ifEmpty { listOf(device.identityKey()) })
        return "lichat://kontakt?server=${URLEncoder.encode(server.url, "UTF-8")}&name=$username&fp=$fp"
    }

    /** Into my contacts – verified when the fingerprint from their QR code matches the server's directory now. */
    fun addContact(user: String, fp: String = ""): Boolean {
        val name = user.trim().lowercase()
        val listed = directory(name)
        if (listed.isEmpty()) throw LiChatException("$name gibt es auf diesem Server nicht")
        val verified = fp.isNotEmpty() && fingerprint(listed.values) == fp
        if (fp.isNotEmpty() && !verified) throw LiChatException("Der Schlüssel von $name passt nicht zum QR-Code – Kontakt nicht übernommen")
        synchronized(lock) {
            val contacts = profiles.optJSONObject("kontakte") ?: JSONObject().also { profiles.put("kontakte", it) }
            val known = contacts.optJSONObject(name) ?: JSONObject()
            contacts.put(name, JSONObject().put("verifiziert", verified || known.optBoolean("verifiziert"))
                .put("seit", known.optLong("seit", System.currentTimeMillis() / 1000)))
            save()
        }
        return verified
    }

    /** A scanned/pasted contact link: added (and verified) – its user name, for opening the conversation with them. */
    fun addContactLink(text: String): String {
        val (server, name, fp) = parseContactUri(text) ?: throw LiChatException("Das ist kein LiChat-Kontakt")
        if (server.isNotEmpty() && runCatching { checkServer(server) }.getOrNull() != this.server.url)
            throw LiChatException("Dieser Kontakt ist auf einem anderen LiChat-Server")
        if (name == username) throw LiChatException("Das ist dein eigener Code")
        addContact(name, fp)
        return name
    }

    /** A voice message heard (1a682a79): Signal's dot beside its time goes. Only here, not told to anyone. */
    fun markListened(cid: String, attachment: String, seconds: Double? = null) = synchronized(lock) {
        val list = chat(cid).getJSONArray("messages")
        for (k in 0 until list.length()) {
            val m = list.getJSONObject(k)
            val info = m.optJSONObject("anhang") ?: continue
            if (info.optString("id") != attachment) continue
            m.put("angehoert", true)
            // one without its length (e.g. sent as a file from the CLI) knows it once played (Michelle: stayed „0:00“)
            if (!info.has("dauer") && seconds != null && seconds > 0) info.put("dauer", Math.round(seconds * 10) / 10.0)
            save()
        }
    }

    /** Who invited this account – set right after register, for opening the conversation with them (not stored). */
    @Volatile var invitedBy: String? = null

    /** An invitation for someone new (7 days, at most 5 open): (code, link). The link's page has its own QR code and opens
     *  LiChat; scanned in LiChat it fills in server and code – the new person only types their name. */
    fun newInvite(): Pair<String, String> = server.call("POST", "/v1/einladungen", JSONObject()).let { it.getString("code") to it.getString("link") }

    /** A PIN for someone to add me (10 minutes, once). */
    fun newPin(): String = server.call("POST", "/v1/pins", JSONObject()).getString("pin")

    /** Someone's PIN typed in: added – their user name. */
    fun redeemPin(pin: String): String {
        val name = server.call("POST", "/v1/pins/einloesen", JSONObject().put("pin", pin.trim())).getString("username")
        addContact(name)
        return name
    }

    /** The conversation with just this person: the one there is, else a new one (scan → straight into it). */
    fun chatWith(user: String): String =
        visibleChats().firstOrNull { cid -> chat(cid).optString("removed").isEmpty() && chat(cid).optString("title").isEmpty() && others(cid) == listOf(user) }
            ?: start(listOf(user))

    /** A name nobody has (Olaf 10.10.: „Server: unbekannt (404)“ for „anna“ – she is anna.berg): plainly, with a guess. */
    private fun checkNames(names: Collection<String>) {
        names.firstOrNull { directory(it).isEmpty() }?.let { missing ->
            val guess = knownPeople().firstOrNull { missing in it || it.substringBefore('.') == missing }
            throw LiChatException("„$missing“ gibt es auf diesem Server nicht" + (guess?.let { " – meinst du $it?" } ?: ""))
        }
    }

    /** People I could write to without typing their exact name: contacts, everyone in my conversations, and everyone who
     *  ever wrote in one of them (also someone who was taken out of a group since – Michelle 4da136eb). */
    fun knownPeople(): List<String> = (contactsObject.keys().asSequence().toList() +
        visibleChats().flatMap { cid -> others(cid) + liveMessagesAll(cid).mapNotNull { m -> m.optString("from").takeIf { it.isNotEmpty() && !m.optBoolean("system") } } })
        .distinct().filter { it != username }.sorted()

    /** Bekannt: someone in my contacts, or a chat I wrote in myself; else Unbekannt. */
    fun known(cid: String): Boolean =
        others(cid).any { contactsObject.has(it) } || liveMessages(cid).any { it.optString("from") == username && !it.optBoolean("system") }

    /** alle | bekannt | unbekannt | ungelesen | geloescht */
    fun filtered(which: String = "alle"): List<String> {
        val chats = visibleChats()
        if (which == "geloescht") return chats.filter { c -> chat(c).has("geloescht") ||
            chat(c).getJSONArray("messages").let { a -> (0 until a.length()).any { a.getJSONObject(it).has("geloescht") } } }
        val shown = chats.filter { !chat(it).has("geloescht") }
        return when (which) {
            "bekannt" -> shown.filter { known(it) }
            "unbekannt" -> shown.filter { !known(it) }
            "ungelesen" -> shown.filter { unread(it) > 0 }
            else -> shown
        }
    }

    fun deleteChat(cid: String) {
        setDraftPhotos(cid, emptyList())
        chatSetting(cid, "geloescht", System.currentTimeMillis() / 1000, tell = false)
    }

    // ------------------------------------------------------------------------------------------ draft photos (f0-04)
    // Like Messages: photos laid above the input stay with their conversation, also after the app was closed. Only on
    // this device, sealed with the profile key in entwuerfe/<hash of the chat>/ – never sent, gone once they are sent.

    class DraftPhoto(val bytes: ByteArray, val name: String = "Foto.jpg", val mime: String = "image/jpeg", val once: Boolean = false,
                     val secret: Boolean = false, val asFile: Boolean = false, val markedUp: Boolean = false)

    private fun draftFolder(cid: String) = File(File(folder, "entwuerfe"), sha256(cid.toByteArray()).take(32))

    fun draftPhotos(cid: String): List<DraftPhoto> = synchronized(lock) {
        val dir = draftFolder(cid)
        val index = File(dir, "liste.bin").takeIf { it.exists() } ?: return emptyList()
        runCatching {
            val list = JSONArray(String(unseal(key, DRAFT_MAGIC, index.readBytes())))
            (0 until list.length()).mapNotNull { n ->
                val o = list.getJSONObject(n)
                val file = File(dir, o.getString("datei")).takeIf { it.exists() } ?: return@mapNotNull null
                DraftPhoto(unseal(key, DRAFT_MAGIC, file.readBytes()), o.optString("name", "Foto.jpg"), o.optString("mime", "image/jpeg"),
                    o.optBoolean("einmal"), o.optBoolean("geheim"), o.optBoolean("datei_art"), o.optBoolean("markiert"))
            }
        }.getOrDefault(emptyList())
    }

    fun setDraftPhotos(cid: String, photos: List<DraftPhoto>) = synchronized(lock) {
        val dir = draftFolder(cid)
        dir.deleteRecursively()
        if (photos.isEmpty()) return@synchronized
        dir.mkdirs()
        val list = JSONArray()
        photos.forEachIndexed { n, p ->
            writePrivate(File(dir, "$n.bin"), seal(key, DRAFT_MAGIC, p.bytes))
            list.put(JSONObject().put("datei", "$n.bin").put("name", p.name).put("mime", p.mime).put("einmal", p.once)
                .put("geheim", p.secret).put("datei_art", p.asFile).put("markiert", p.markedUp))
        }
        writePrivate(File(dir, "liste.bin"), seal(key, DRAFT_MAGIC, list.toString().toByteArray()))
    }

    fun deleteMessage(cid: String, mid: String) = synchronized(lock) { findMessage(cid, mid)?.put("geloescht", System.currentTimeMillis() / 1000); save() }

    /** Back from „Zuletzt gelöscht“: the conversation (and its messages), or one message. */
    fun restore(cid: String, mid: String? = null) = synchronized(lock) {
        if (mid == null) {
            chat(cid).remove("geloescht")
            liveMessagesAll(cid).forEach { it.remove("geloescht") }
        } else findMessage(cid, mid)?.remove("geloescht")
        save()
    }

    private fun liveMessagesAll(cid: String): List<JSONObject> = chat(cid).getJSONArray("messages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** Gone for good: what was deleted 30 days ago, or now when asked. The conversation stays a member, empty and hidden. */
    fun purge(cid: String? = null, mid: String? = null, now: Long = System.currentTimeMillis() / 1000) = synchronized(lock) {
        val limit = now - 30L * 86400
        for (c in chats.keys().asSequence().toList()) {
            if (cid != null && c != cid) continue
            val chat = chat(c)
            if (chat.has("geloescht") && mid == null && (cid != null || chat.optLong("geloescht") < limit)) {
                chat.put("messages", JSONArray()); chat.put("geloescht", 1)
            }
            val kept = JSONArray()
            for (m in liveMessagesAll(c)) {
                val gone = m.has("geloescht") && ((mid != null && m.optString("mid") == mid) || (mid == null && (cid != null || m.optLong("geloescht") < limit)))
                if (!gone) kept.put(m)
            }
            chat.put("messages", kept)
        }
        save()
    }

    fun muted(cid: String) = chats.optJSONObject(cid)?.optBoolean("stumm") ?: false

    // ------------------------------------------------------------------------------------------ mentions (d54175ed)

    /** The names „@…“ in a text (lower case, without trailing punctuation). */
    private fun mentionNames(text: String) = MENTION.findAll(text).map { it.groupValues[1].lowercase().trimEnd('.', '_', '-') }.toSet()

    /** Who „@name“ means: members of this conversation only, „@alle“ = everyone – worked out by every receiver from the text. */
    fun mentioned(cid: String, text: String): List<String> {
        val names = mentionNames(text)
        val members = chats.optJSONObject(cid)?.optJSONObject("roster")?.keys()?.asSequence()?.toSet() ?: emptySet()
        return (if (names.any { it in EVERYONE }) members else names intersect members).sorted()
    }

    fun mentionsMe(text: String) = mentionNames(text).let { username in it || it.any { n -> n in EVERYONE } }

    /** While typing „@…“ in a group: the members that fit (user or display name), me left out. */
    fun mentionCandidates(cid: String, typed: String): List<String> {
        val members = chats.optJSONObject(cid)?.optJSONObject("roster")?.keys()?.asSequence()?.toList() ?: emptyList()
        return members.filter { it != username && (it.startsWith(typed.lowercase()) || display(it).lowercase().startsWith(typed.lowercase())) }.sorted()
    }

    fun groupPicture(cid: String): ByteArray? = (chats.optJSONObject(cid)?.opt("bild") as? String)?.takeIf { it.isNotEmpty() }?.let { unb64(it) }

    /** [replyTo]: the mid of the message this answers (Olaf 09.10.: swipe to answer) – sender and a short quote travel along. */
    fun send(cid: String, text: String, replyTo: String? = null): JSONObject {
        val clean = text.trim()
        if (clean.isEmpty()) throw LiChatException("Leere Nachricht")
        chat(cid).optString("removed").takeIf { it.isNotEmpty() }?.let { throw LiChatException("$it hat dich aus dem Gespräch entfernt") }
        val at = System.currentTimeMillis() / 1000
        val message = JSONObject().put("from", username).put("text", clean).put("at", at).put("mid", newMid()).put("status", "wartet")
        val content = JSONObject().put("t", "text").put("text", clean).put("at", at).put("mid", message.getString("mid"))
        replyTo?.let { mid -> findMessage(cid, mid) }?.let { quoted ->
            val answer = JSONObject().put("mid", quoted.getString("mid")).put("from", quoted.optString("from"))
                .put("text", describe(quoted).take(120))
            message.put("antwort", answer)
            content.put("antwort", answer)
        }
        synchronized(lock) { chat(cid).getJSONArray("messages").put(message); save() }
        onChange()          // the bubble is there at once, before it goes out (Olaf 10.10.)
        return deliver(cid, message, content)
    }

    fun findMessage(cid: String, mid: String): JSONObject? {
        val list = chat(cid).getJSONArray("messages")
        return (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("mid") == mid }
    }

    /** My reaction on a message (one per person, like iOS tapbacks); "" takes it back. */
    fun react(cid: String, mid: String, emoji: String) {
        val clean = emoji.trim()   // any emoji (Olaf: „+“), ZWJ sequences included – but only an emoji
        if (clean.isNotEmpty() && !isSingleEmoji(clean)) throw LiChatException("Als Reaktion geht genau ein Emoji")
        synchronized(lock) {
            val target = findMessage(cid, mid) ?: throw LiChatException("Nachricht nicht gefunden")
            val reactions = target.optJSONObject("reaktionen") ?: JSONObject().also { target.put("reaktionen", it) }
            if (clean.isEmpty()) reactions.remove(username) else reactions.put(username, clean)
        }
        groupMessage(cid, JSONObject().put("t", "reaktion").put("mid", mid).put("emoji", clean), null)
    }

    // ------------------------------------------------------------------------------------------ edit / unsend (4ceca163)

    fun canEdit(message: JSONObject, now: Long = System.currentTimeMillis() / 1000) =
        message.optString("from") == username && message.optString("text").isNotEmpty() && !message.optBoolean("zurueckgerufen") &&
            message.has("mid") && now - message.optLong("at") <= EDIT_WINDOW_S && (message.optJSONArray("bearbeitet")?.length() ?: 0) < EDIT_MAX

    fun canUnsend(message: JSONObject, now: Long = System.currentTimeMillis() / 1000) =
        message.optString("from") == username && message.has("mid") && !message.optBoolean("zurueckgerufen") &&
            now - message.optLong("at") <= UNSEND_WINDOW_S

    /** My message, corrected (iOS: 15 minutes, five times) – everyone sees „Bearbeitet“ and the earlier versions. */
    fun edit(cid: String, mid: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) throw LiChatException("Leere Nachricht – zum Entfernen „Senden widerrufen“")
        val now = System.currentTimeMillis() / 1000
        synchronized(lock) {
            val message = findMessage(cid, mid) ?: throw LiChatException("Nachricht nicht gefunden")
            if (!canEdit(message, now)) throw LiChatException("Bearbeiten geht nur 15 Minuten lang und höchstens fünfmal")
            applyEdit(message, clean, now)
            save()
        }
        groupMessage(cid, JSONObject().put("t", "bearbeiten").put("mid", mid).put("text", clean).put("at", now), null)
    }

    /** My message taken back (iOS: two minutes) – for everyone a grey line instead of the bubble. */
    fun unsend(cid: String, mid: String) {
        synchronized(lock) {
            val message = findMessage(cid, mid) ?: throw LiChatException("Nachricht nicht gefunden")
            if (!canUnsend(message)) throw LiChatException("Widerrufen geht nur in den ersten zwei Minuten")
            applyUnsend(chat(cid), message)
            save()
        }
        groupMessage(cid, JSONObject().put("t", "zurueckrufen").put("mid", mid).put("at", System.currentTimeMillis() / 1000), null)
    }

    private fun applyEdit(message: JSONObject, text: String, at: Long) {
        val versions = message.optJSONArray("bearbeitet") ?: JSONArray().also { message.put("bearbeitet", it) }
        versions.put(JSONObject().put("text", message.optString("text")).put("at", message.optLong("bearbeitet_am", message.optLong("at"))))
        message.put("text", text).put("bearbeitet_am", at)
    }

    private fun applyUnsend(chat: JSONObject, message: JSONObject) {
        for (key in listOf("anhang", "bearbeitet", "bearbeitet_am", "antwort", "reaktionen")) message.remove(key)
        message.put("text", "").put("zurueckgerufen", true)
        val list = chat.getJSONArray("messages")
        for (k in 0 until list.length()) {
            val quote = list.getJSONObject(k).optJSONObject("antwort") ?: continue
            if (quote.optString("mid") == message.optString("mid")) quote.put("text", "").put("zurueckgerufen", true)
        }
    }

    private fun newMid() = ByteArray(8).also { SecureRandom().nextBytes(it) }.hex()

    /** Encrypts and posts one of my messages; its status follows: wartet → gesendet, or fehler (a red line, retry). */
    private fun deliver(cid: String, message: JSONObject, content: JSONObject): JSONObject {
        val answer = try {
            val packet: ByteArray
            val targets: List<String>
            // Just joined: the roster (devices) comes with the next fetch – say so instead of the server's 400 (Jean-Marie 09.10.).
            if (synchronized(lock) { targets(cid) }.isEmpty())
                throw LiChatException("Das Gespräch wird gerade noch eingerichtet – in ein paar Sekunden noch einmal senden")
            synchronized(lock) {
                packet = device.encrypt(cid.unhex(), payload(content))
                save()
                targets = targets(cid)
            }
            post(targets, byteArrayOf(MLS) + packet)
        } catch (e: Exception) {
            synchronized(lock) { message.put("status", "fehler"); save() }
            throw (e as? LiChatException) ?: LiChatException("Nicht gesendet")
        }
        synchronized(lock) { message.put("status", "gesendet").put("gesendet", System.currentTimeMillis() / 1000); save() }
        return answer
    }

    /** „Nicht gesendet“ tapped: the same message (same mid) once more. */
    fun retry(cid: String, mid: String, changed: () -> Unit = onChange): JSONObject {
        val list = chat(cid).getJSONArray("messages")
        val message = (0 until list.length()).map { list.getJSONObject(it) }.first { it.optString("mid") == mid }
        if (message.optJSONObject("anhang")?.optString("id")?.startsWith(OUTBOX) == true) {
            message.put("status", "wartet")
            return upload(cid, message, changed)
        }
        val content = message.optJSONObject("anhang")?.let { a ->
            JSONObject(a.toString()).put("t", "anhang").also { c ->
                message.optString("text").takeIf { it.isNotEmpty() }?.let { c.put("text", it) }
                message.optJSONObject("album")?.let { c.put("album", it) }
            }
        } ?: JSONObject().put("t", "text").put("text", message.optString("text"))
        content.put("at", message.optLong("at")).put("mid", mid)
        message.put("status", "wartet")
        return deliver(cid, message, content)
    }

    /** Back online: what stayed „Nicht gesendet“ during the outage goes out by itself, oldest first (72c8a716: Olaf's
     *  messages from the morning of the IP change never left the phone). Only the last day – older ones wait for a tap. */
    fun resendFailed(nowS: Long = System.currentTimeMillis() / 1000, changed: () -> Unit = onChange): Int {
        val failed = synchronized(lock) {
            visibleChats().filter { chat(it).optString("removed").isEmpty() }.flatMap { cid ->
                val list = chat(cid).getJSONArray("messages")
                (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.optString("from") == username && it.optString("status") == "fehler" && nowS - it.optLong("at") <= RESEND_S }
                    .map { cid to it }
            }.sortedBy { it.second.optLong("at") }
        }
        var sent = 0
        for ((cid, message) in failed) {
            try { retry(cid, message.getString("mid"), changed); sent++ } catch (_: LiChatException) { }
        }
        return sent
    }

    // ------------------------------------------------------------------------------------------ delivery state (db70d358)

    /** Read receipts: off by default like the iPhone (Olaf), mutual – off means none sent and no blue ticks seen. */
    var readReceipts: Boolean
        get() = profiles.optBoolean("lesebestaetigungen", false)
        set(on) {
            synchronized(lock) { profiles.put("lesebestaetigungen", on); save() }
            tellOwnDevices(JSONObject().put("t", "einstellungen").put("lesebestaetigungen", on))
        }

    // ------------------------------------------------------------------------------------------ typing (884f7c8a)

    /** „… tippt“ (Olaf 09.10.): off by default for privacy, mutual like read receipts; flüchtig – never stored. */
    var typingIndicator: Boolean
        get() = profiles.optBoolean("tippanzeige", false)
        set(on) {
            synchronized(lock) { profiles.put("tippanzeige", on); if (!on) typingSeen.clear(); save() }
            tellOwnDevices(JSONObject().put("t", "einstellungen").put("tippanzeige", on))
        }

    /** I am typing in this chat: the others see „…“ – at most every 3 s, nothing when switched off. */
    fun typing(cid: String): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (!typingIndicator || chat(cid).optString("removed").isNotEmpty() || now - (typed[cid] ?: -TYPING_EVERY_MS) < TYPING_EVERY_MS) return false
        typed[cid] = now
        val packet: ByteArray
        val targets: List<String>
        synchronized(lock) {
            targets = targets(cid)
            if (targets.isEmpty()) return false      // nobody to tell yet (just joined) – no 400 every 3 s from the server
            packet = device.encrypt(cid.unhex(), payload(JSONObject().put("t", "tippt").put("at", System.currentTimeMillis() / 1000)))
            save()
        }
        post(targets, byteArrayOf(MLS) + packet)
        return true
    }

    /** Who is typing in this chat right now. */
    fun typingNow(cid: String): List<String> = synchronized(lock) {
        if (!typingIndicator) return emptyList()
        val now = System.currentTimeMillis()
        typingSeen[cid]?.filterValues { now - it < TYPING_FRESH_MS }?.keys?.sorted() ?: emptyList()
    }

    private fun receipt(cid: String, kind: String, mids: List<String>) {
        if (mids.isEmpty() || chat(cid).optString("removed").isNotEmpty()) return
        val packet: ByteArray
        val targets: List<String>
        synchronized(lock) {
            packet = device.encrypt(cid.unhex(), payload(JSONObject().put("t", "quittung").put("art", kind)
                .put("mids", JSONArray(mids)).put("at", System.currentTimeMillis() / 1000)))
            save()
            targets = targets(cid)
        }
        post(targets, byteArrayOf(MLS) + packet)
    }

    /** The chat is on screen: with read receipts on, the others learn which of their messages were read. */
    fun markRead(cid: String) {
        readUpTo(cid)                      // read up to here, on all my devices (300129f8)
        if (!receiptsFor(cid)) return      // receipts for this chat, else the general setting (3c0400ad)
        val mids = synchronized(lock) {
            val list = chat(cid).getJSONArray("messages")
            (0 until list.length()).map { list.getJSONObject(it) }
                .filter { it.has("mid") && it.optString("from") !in setOf("", username) && !it.optBoolean("gelesen_gemeldet") }
                .onEach { it.put("gelesen_gemeldet", true) }.map { it.getString("mid") }
        }
        receipt(cid, "gelesen", mids)
    }

    /** (state, time) of one of my messages: wartet | fehler | gesendet | zugestellt | gelesen – in a group once everyone has. */
    fun tick(cid: String, message: JSONObject): Pair<String, Long> {
        val state = message.optString("status", "gesendet")
        if (state == "wartet" || state == "fehler") return state to message.optLong("at")
        val others = others(cid)
        val delivered = message.optJSONObject("zugestellt") ?: JSONObject()
        val read = message.optJSONObject("gelesen") ?: JSONObject()
        if (others.isNotEmpty() && receiptsFor(cid) && others.all { read.has(it) }) return "gelesen" to others.maxOf { read.getLong(it) }
        if (others.isNotEmpty() && others.all { delivered.has(it) || read.has(it) })
            return "zugestellt" to others.maxOf { if (delivered.has(it)) delivered.getLong(it) else read.getLong(it) }
        return "gesendet" to message.optLong("gesendet", message.optLong("at"))
    }

    fun remove(cid: String, names: List<String>) {
        val out = names.filter { it != username }.toSortedSet().toList()
        val commit: ByteArray
        val targets: List<String>
        synchronized(lock) {
            targets = targets(cid)
            commit = device.removeMembers(cid.unhex(), out)
            val chat = chat(cid)
            out.forEach { chat.getJSONObject("roster").remove(it); system(chat, "Du hast $it aus der Unterhaltung entfernt.") }
            save()
        }
        post(targets, byteArrayOf(MLS) + commit)
    }

    private fun system(chat: JSONObject, text: String) {
        chat.getJSONArray("messages").put(JSONObject().put("from", "").put("system", true).put("text", text).put("at", System.currentTimeMillis() / 1000))
    }

    /** The people in it – each once, however many devices they have (Michelle 8534fe11). */
    fun members(cid: String): List<String> =
        if (chat(cid).optString("removed").isNotEmpty()) listOf(username) else device.members(cid.unhex()).distinct()

    /** Groups of five digits, the same on every member's device (the same digits as Ubuntu). */
    fun safetyCode(cid: String): String {
        if (chat(cid).optString("removed").isNotEmpty()) return ""
        val raw = device.safetyCode(cid.unhex())
        return (0..raw.size - 4 step 4).joinToString(" ") { i ->
            val value = ((raw[i].toLong() and 0xff) shl 24) or ((raw[i + 1].toLong() and 0xff) shl 16) or ((raw[i + 2].toLong() and 0xff) shl 8) or (raw[i + 3].toLong() and 0xff)
            "%05d".format(value % 100000)
        }
    }

    fun title(cid: String): String {
        val chat = chat(cid)
        chat.optString("title").takeIf { it.isNotEmpty() }?.let { return it }
        val roster = chat.getJSONObject("roster").keys().asSequence().toList()
        val names = roster.filter { it != username }.ifEmpty { runCatching { members(cid).filter { it != username } }.getOrDefault(emptyList()) }
        return names.map { display(it) }.sorted().joinToString(", ").ifEmpty { "(leer)" }
    }

    fun others(cid: String): List<String> = chat(cid).getJSONObject("roster").keys().asSequence().filter { it != username }.toList()

    // ------------------------------------------------------------------------------------------ attachments (card 9780f3a0)

    /** Encrypts [data] with its own key, uploads the encrypted bytes, announces it end-to-end. Photos come already
     *  prepared (Pictures.photo: without EXIF, at most 2048 px) with their [preview]. */
    fun sendAttachment(cid: String, data: ByteArray, name: String, mime: String, kind: String, durationS: Double? = null,
                       preview: ByteArray? = null, once: Boolean = false, secret: Boolean = false, caption: String = "",
                       album: JSONObject? = null, defer: Boolean = false, wave: List<Int>? = null, changed: () -> Unit = onChange): JSONObject {
        if (kind !in KINDS) throw LiChatException("unbekannte Art von Anhang")
        if ((once || secret) && kind != "bild") throw LiChatException("Einmal-Ansicht und Verpixeln gibt es nur für Fotos")
        chat(cid).optString("removed").takeIf { it.isNotEmpty() }?.let { throw LiChatException("$it hat dich aus dem Gespräch entfernt") }
        if (data.size > ATTACHMENT_MAX) throw LiChatException("Anhang zu groß (höchstens 100 MB)")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(ATTACHMENT_MAGIC)
        val sealed = nonce + cipher.doFinal(data)
        // The bubble is there at once (cf647f61, like Messages): the sealed bytes wait in the outbox under a local id until
        // they are up – then the server's id replaces it. A one-time picture's preview stays out as before.
        val mid = newMid()
        val local = OUTBOX + mid
        keepSealed(local, sealed)
        val info = JSONObject().put("id", local).put("schluessel", b64(key)).put("sha256", sha256(sealed)).put("name", name.take(200))
            .put("mime", mime.take(100)).put("groesse", data.size).put("art", kind)
        durationS?.let { info.put("dauer", Math.round(it * 10) / 10.0) }
        // A voice message's real wave (1a682a79, like Signal): ~50 bars 0…31 measured while recording.
        if (kind == "sprache" && !wave.isNullOrEmpty()) info.put("welle", JSONArray(wave.take(WAVE_MAX).map { it.coerceIn(0, 31) }))
        if (once) info.put("einmal", true)            // no preview, nothing kept – the sender cannot open it either
        else preview?.let { info.put("vorschau", b64(it)) }
        if (secret) info.put("geheim", true)
        val at = System.currentTimeMillis() / 1000
        // The caption lives in the same message as the picture (2fcc3eef, like Messages) – one bubble, one delivery.
        val message = JSONObject().put("from", username).put("text", caption.trim()).put("at", at).put("anhang", info).put("mid", mid).put("status", "wartet")
        // Several photos picked together stay together (5749ebd0): collage for 2–3, a stack from 4 – one-time and secret ones alone.
        if (album != null && !once && !secret) message.put("album", album)
        synchronized(lock) { chat(cid).getJSONArray("messages").put(message); save() }
        if (defer) return message          // an album shows up whole once its first photo goes up – not growing bubble by bubble
        changed()
        return upload(cid, message, changed)
    }

    /** An attachment laid down with defer = true goes up now (an album: all bubbles first, then one after the other). */
    fun sendQueued(cid: String, message: JSONObject, changed: () -> Unit = onChange): JSONObject = upload(cid, message, changed)

    /** A fresh album: {id, nr, anzahl} for photo [nr] (1…) of [count]. */
    fun newAlbum(count: Int): (Int) -> JSONObject {
        val id = newMid()
        return { nr -> JSONObject().put("id", id).put("nr", nr).put("anzahl", count) }
    }

    /** Told when an upload moves on (the app redraws its bubbles) – set once by whoever shows them. */
    @Volatile var onChange: () -> Unit = {}

    /** Attachments going up now: mid → share sent (0…1). The bubble shows it as a ring; a tap on it cancels. */
    val uploads = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val cancelling = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun cancelUpload(mid: String) { if (uploads.containsKey(mid)) cancelling.add(mid) }

    /** Up with it (if it still waits in the outbox), then delivered. Fails → „Nicht gesendet“, the bytes stay for a retry;
     *  cancelled → the bubble and the bytes go. */
    private fun upload(cid: String, message: JSONObject, changed: () -> Unit): JSONObject {
        val info = message.getJSONObject("anhang")
        val mid = message.getString("mid")
        val local = info.optString("id")
        if (local.startsWith(OUTBOX)) {
            val file = File(File(folder, "anhaenge"), local)
            if (!file.exists()) {
                synchronized(lock) { message.put("status", "fehler"); save() }
                throw LiChatException("Anhang ist nicht mehr da – bitte neu senden")
            }
            uploads[mid] = 0f
            changed()
            try {
                val once = info.optBoolean("einmal")
                val people = members(cid).count { it != username }.coerceAtLeast(1)
                val answer = server.raw("PUT", if (once) "/v1/anhaenge?einmal=$people" else "/v1/anhaenge", file.readBytes(),
                    progress = { sent, total ->
                        val share = sent.toFloat() / total
                        if (share - (uploads[mid] ?: 0f) >= 0.04f || sent == total) { uploads[mid] = share; changed() }
                    }, cancelled = { mid in cancelling })
                val id = JSONObject(String(answer)).getString("id")
                synchronized(lock) {
                    info.put("id", id)
                    if (once) file.delete() else file.renameTo(File(File(folder, "anhaenge"), id))
                    save()
                }
            } catch (e: UploadCancelled) {
                synchronized(lock) {
                    val list = chat(cid).getJSONArray("messages")
                    (0 until list.length()).firstOrNull { list.getJSONObject(it).optString("mid") == mid }?.let { list.remove(it) }
                    save()
                }
                file.delete()
                return JSONObject()
            } catch (e: Exception) {
                synchronized(lock) { message.put("status", "fehler"); save() }
                throw (e as? LiChatException) ?: LiChatException("Nicht gesendet")
            } finally {
                uploads.remove(mid)
                cancelling.remove(mid)
                changed()
            }
        }
        val content = JSONObject(info.toString()).put("t", "anhang").put("at", message.optLong("at")).put("mid", mid)
        message.optString("text").takeIf { it.isNotEmpty() }?.let { content.put("text", it) }
        message.optJSONObject("album")?.let { content.put("album", it) }
        return deliver(cid, message, content)
    }

    private fun sha256(data: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(data).hex()

    private fun keepSealed(id: String, sealed: ByteArray) {
        val folder = File(folder, "anhaenge").apply { mkdirs() }
        writePrivate(File(folder, id), sealed)
    }

    /** The plain bytes: from the local (still encrypted) copy or fetched again, checked against the announced SHA-256. */
    fun openAttachment(info: JSONObject): ByteArray {
        val id = info.getString("id")
        val local = File(File(folder, "anhaenge"), id)
        val sealed = if (local.exists()) local.readBytes() else server.raw("GET", "/v1/anhaenge/$id")
        if (sha256(sealed) != info.getString("sha256")) throw LiChatException("Anhang beschädigt oder verändert")
        if (!local.exists()) keepSealed(id, sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(unb64(info.getString("schluessel")), "AES"), GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(ATTACHMENT_MAGIC)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    /** A one-time picture: "eigen" (I sent it), "neu", "geoeffnet" or "abgelaufen" (14 days unopened); else null. */
    fun onceState(message: JSONObject, nowS: Long = System.currentTimeMillis() / 1000): String? {
        if (message.optJSONObject("anhang")?.optBoolean("einmal") != true) return null
        return when {
            message.optString("from") == username -> "eigen"
            message.has("geoeffnet") -> "geoeffnet"
            nowS - message.optLong("at") > ONCE_DAYS * 86400L -> "abgelaufen"
            else -> "neu"
        }
    }

    /** The one-time picture's bytes – only in memory, once: then marked opened here, my other devices drop it, the sender
     *  sees „Geöffnet“, and the server deletes it when every other person has opened it. */
    fun openOnce(cid: String, mid: String): ByteArray {
        val message = findMessage(cid, mid)
        if (message == null || onceState(message) != "neu") throw LiChatException("Dieses Einmal-Bild lässt sich nicht mehr öffnen")
        synchronized(lock) { if (!openingOnce.add(mid)) throw LiChatException("Das Einmal-Bild wird schon geöffnet") }
        try { return openOnceNow(cid, mid, message) } finally { synchronized(lock) { openingOnce.remove(mid) } }
    }

    private val openingOnce = mutableSetOf<String>()

    private fun openOnceNow(cid: String, mid: String, message: JSONObject): ByteArray {
        val info = message.getJSONObject("anhang")
        val sealed = try { server.raw("GET", "/v1/anhaenge/${info.getString("id")}") } catch (e: LiChatException) {
            if (e.message?.let { "(404)" in it || "(410)" in it } != true) throw e        // offline: try again later
            synchronized(lock) { message.put("geoeffnet", System.currentTimeMillis() / 1000); save() }
            throw LiChatException("Dieses Einmal-Bild ist nicht mehr da")
        }
        if (sha256(sealed) != info.getString("sha256")) throw LiChatException("Anhang beschädigt oder verändert")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(unb64(info.getString("schluessel")), "AES"), GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(ATTACHMENT_MAGIC)
        val data = cipher.doFinal(sealed, 12, sealed.size - 12)
        synchronized(lock) { message.put("geoeffnet", System.currentTimeMillis() / 1000); save() }
        runCatching { server.call("POST", "/v1/anhaenge/${info.getString("id")}/gesehen", JSONObject()) }   // lapses after 14 days anyway
        groupMessage(cid, JSONObject().put("t", "einmal_geoeffnet").put("mid", mid), null)
        return data
    }

    /** The phone saw a screenshot while a one-time picture was open: a fixed line for everyone in the chat. */
    fun reportScreenshot(cid: String, mid: String) =
        groupMessage(cid, JSONObject().put("t", "bildschirmfoto").put("mid", mid), "Du hast ein Bildschirmfoto vom Einmal-Bild gemacht.")

    fun media(cid: String): Media {
        val photos = mutableListOf<JSONObject>(); val links = mutableListOf<Pair<String, JSONObject>>()
        val documents = mutableListOf<JSONObject>(); val voice = mutableListOf<JSONObject>()
        val list = chat(cid).getJSONArray("messages")
        for (k in list.length() - 1 downTo 0) {
            val m = list.getJSONObject(k)
            if (m.optBoolean("system") || m.optBoolean("zurueckgerufen") || m.optBoolean("geloescht")) continue
            val info = m.optJSONObject("anhang")
            if (info != null) {
                if (info.optBoolean("einmal")) continue
                when (info.optString("art")) { "bild", "video" -> photos.add(m); "sprache" -> voice.add(m); else -> documents.add(m) }
            } else findLinks(m.optString("text")).filter { it.target.startsWith("http://") || it.target.startsWith("https://") }.forEach { links.add(it.target to m) }
        }
        return Media(photos, links, documents, voice)
    }

    // ------------------------------------------------------------------------------------------ profile pictures

    /** What people see (Olaf 09.10.): „Vorname Nachname“ when that person told us end-to-end, else the user name. */
    fun display(user: String): String {
        val name = if (user == username) profiles.optJSONObject("name") else profiles.optJSONObject("names")?.optJSONObject(user)
        return listOfNotNull(name?.optString("vorname"), name?.optString("nachname")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { user }
    }

    /** My first and last name – capitals, spaces, umlauts – sent end-to-end into every conversation. */
    fun setName(first: String, last: String) {
        val name = JSONObject().put("vorname", first.trim().take(40)).put("nachname", last.trim().take(40))
        val sends = mutableListOf<Pair<List<String>, ByteArray>>()
        synchronized(lock) {
            profiles.put("name", name)
            for (cid in chats.keys()) {
                if (chat(cid).optString("removed").isNotEmpty()) continue
                sends.add(targets(cid) to device.encrypt(cid.unhex(), payload(JSONObject(name.toString()).put("t", "name"))))
            }
            save()
        }
        sends.filter { it.first.isNotEmpty() }.forEach { (targets, packet) -> post(targets, byteArrayOf(MLS) + packet) }
    }

    fun picture(user: String): ByteArray? {
        val own = contactPictures.opt(user) as? String
        val value = own?.takeIf { it.isNotEmpty() } ?: if (user == username) profiles.opt("me") else profiles.getJSONObject("people").opt(user)
        return (value as? String)?.takeIf { it.isNotEmpty() }?.let { unb64(it) }
    }

    private val contactPictures: JSONObject get() = profiles.optJSONObject("kontaktbilder") ?: JSONObject().also { profiles.put("kontaktbilder", it) }

    fun hasContactPicture(user: String): Boolean = (contactPictures.opt(user) as? String)?.isNotEmpty() == true

    /** A picture of someone only for me, like Apple Contacts (Olaf 8534fe11): it follows on my devices (their own group),
     *  the person learns nothing. [jpeg] already prepared (Pictures.prepare); null: their shared picture again („Zurücksetzen“). */
    fun setContactPicture(user: String, jpeg: ByteArray?) {
        val value = jpeg?.let { b64(it) }
        synchronized(lock) { if (value != null) contactPictures.put(user, value) else contactPictures.remove(user); save() }
        tellOwnDevices(JSONObject().put("t", "kontaktbild").put("name", user).put("bild", value ?: JSONObject.NULL))
    }

    /** My picture (already square, 512 px, JPEG without EXIF – Pictures.prepare), sent end-to-end into every chat. */
    fun setPicture(jpeg: ByteArray?) {
        val value = jpeg?.let { b64(it) } ?: ""
        val sends = mutableListOf<Pair<List<String>, ByteArray>>()
        synchronized(lock) {
            profiles.put("me", if (value.isEmpty()) JSONObject.NULL else value)
            for (cid in chats.keys()) {
                if (chat(cid).optString("removed").isNotEmpty()) continue
                sends.add(targets(cid) to device.encrypt(cid.unhex(), payload(JSONObject().put("t", "profile").put("bild", value))))
            }
            save()
        }
        sends.filter { it.first.isNotEmpty() }.forEach { (targets, packet) -> post(targets, byteArrayOf(MLS) + packet) }
    }

    // ------------------------------------------------------------------------------------------ mailbox

    /** Fetches the mailbox, decrypts, keeps – and only then acknowledges. [waitS]: the server answers as soon as something
     *  arrives (the standing connection, no push service). Only one fetch at a time – the service's loop. */
    /** After the move out of account.json: a new token, the old one void – copies of the old account.json fetch nothing
     *  any more. Offline or an older server: tried again at the next fetch. */
    private fun renewTokenIfDue() {
        if (tokenSettled) return
        val file = File(folder, "token.bin")
        val stored = runCatching { JSONObject(String(unseal(key, TOKEN_MAGIC, file.readBytes()))) }.getOrNull() ?: return
        if (!stored.optBoolean("erneuern")) { tokenSettled = true; return }
        val fresh = runCatching { server.call("POST", "/v1/devices/me/token", JSONObject()).getString("token") }.getOrNull() ?: return
        synchronized(lock) { writePrivate(file, seal(key, TOKEN_MAGIC, JSONObject().put("token", fresh).toString().toByteArray())) }
        server.token = fresh
        tokenSettled = true
    }

    @Volatile private var tokenSettled = false

    fun fetch(waitS: Int = 0): List<News> {
        renewTokenIfDue()
        val path = if (waitS > 0) "/v1/messages?warten=$waitS" else "/v1/messages"
        val waiting = server.call("GET", path, timeoutS = TIMEOUT_S + waitS).getJSONArray("messages")
        val news = mutableListOf<News>()
        val done = JSONArray()
        var joined = 0
        synchronized(lock) {
            for (i in 0 until waiting.length()) {
                val item = waiting.getJSONObject(i)
                val blob = unb64(item.getString("blob"))
                val received = item.optDouble("received")
                try {
                    when (blob.firstOrNull()) {
                        WELCOME -> {
                            val cid = device.join(blob.copyOfRange(1, blob.size)).hex()
                            if (!chats.has(cid)) chats.put(cid, newChat())
                            joined++
                            news.add(News(cid, News.Kind.Joined, received = received))
                        }
                        MLS -> news.addAll(take(device.receive(blob.copyOfRange(1, blob.size)), received))
                        else -> news.add(News(null, News.Kind.Problem, text = "unbekanntes Paket"))
                    }
                } catch (e: CoreException) {
                    // Replayed, forged or from an epoch long gone: the core refused it. Not swallowed (Michelle 09.10.): its
                    // conversation shows a grey line, so nobody believes a message never came.
                    val cid = if (blob.firstOrNull() == MLS) runCatching { conversationOf(blob.copyOfRange(1, blob.size)).hex() }.getOrNull() else null
                    if (cid != null && chats.has(cid)) {
                        system(chat(cid), UNREADABLE)
                        news.add(News(cid, News.Kind.System, text = UNREADABLE, received = received))
                    } else news.add(News(null, News.Kind.Problem, text = "Paket verworfen"))
                }
                done.put(item.getLong("id"))
            }
            if (done.length() == 0) return news
            save()
        }
        server.call("POST", "/v1/messages/ack", JSONObject().put("ids", done))
        if (joined > 0) {
            val packages = synchronized(lock) { device.keyPackages(joined.toUInt()).also { save() } }
            server.call("POST", "/v1/key-packages", JSONObject().put("key_packages", JSONArray(packages.map { b64(it) })))
        }
        if (oweRemoval.isNotEmpty()) {
            val owed = synchronized(lock) { oweRemoval.toMap().also { oweRemoval.clear() } }
            for ((cid, names) in owed) {
                val (targets, commit) = synchronized(lock) { targets(cid) to device.removeMembers(cid.unhex(), names.sorted()).also { save() } }
                val left = names.flatMap { directory(it).keys }
                post((targets + left).toSortedSet().toList(), byteArrayOf(MLS) + commit)
            }
        }
        if (oweDelivered.isNotEmpty()) {
            // „zugestellt“ for what just arrived (always on – only „gelesen“ can be switched off).
            val owed = synchronized(lock) { oweDelivered.toMap().also { oweDelivered.clear() } }
            owed.forEach { (cid, mids) -> receipt(cid, "zugestellt", mids) }
        }
        if (owePicture.isNotEmpty()) {
            val sends = synchronized(lock) {
                val me = profiles.opt("me") as? String
                val name = profiles.optJSONObject("name")
                val owed = owePicture.toList().also { owePicture.clear() }
                owed.flatMap { cid ->
                    listOfNotNull(
                        me?.takeIf { it.isNotEmpty() }?.let { targets(cid) to device.encrypt(cid.unhex(), payload(JSONObject().put("t", "profile").put("bild", it))) },
                        name?.let { targets(cid) to device.encrypt(cid.unhex(), payload(JSONObject(it.toString()).put("t", "name"))) })
                }.also { save() }
            }
            sends.forEach { (targets, packet) -> post(targets, byteArrayOf(MLS) + packet) }
        }
        return news
    }

    private fun rememberMid(cid: String, message: JSONObject, data: JSONObject) {
        val mid = data.optString("mid")
        if (Regex("[0-9a-f]{16}").matches(mid)) {
            message.put("mid", mid)
            // my own, written on another of my devices: no receipt to myself
            if (message.optString("from") != username) oweDelivered.getOrPut(cid) { mutableListOf() }.add(mid)
        }
    }

    private fun take(received: Incoming, at: Double): List<News> {
        val conversation = when (received) {
            is Incoming.Message -> received.conversation
            is Incoming.Changed -> received.conversation
            is Incoming.Removed -> received.conversation
        }
        val cid = conversation.hex()
        if (!chats.has(cid)) chats.put(cid, newChat())
        val chat = chat(cid)
        when (received) {
            is Incoming.Removed -> {
                if (chat.optString("removed") == username) return emptyList()   // I left myself – the commit only confirms it
                chat.put("removed", received.by)
                return listOf(News(cid, News.Kind.Removed, from = received.by, received = at))
            }
            is Incoming.Changed -> {
                // Whoever is no longer in the group gets no more packets – and everyone reads who took whom out (31ec7628).
                val members = members(cid).toSet()
                val roster = chat.getJSONObject("roster")
                val gone = if (chat.optString("removed").isNotEmpty()) emptyList()
                    else roster.keys().asSequence().filter { it !in members }.sorted().toList()
                return gone.map { name ->
                    roster.remove(name)
                    val text = "${display(received.by)} hat ${display(name)} aus der Unterhaltung entfernt."
                    system(chat, text)
                    News(cid, News.Kind.System, text = text, received = at)
                }
            }
            is Incoming.Message -> {
                val sender = received.sender
                val data = runCatching { JSONObject(String(received.payload)) }.getOrNull()
                    ?: return listOf(News(cid, News.Kind.Problem, text = "unlesbare Nachricht von $sender"))
                if (chat.optBoolean("intern") || data.optBoolean("intern")) {
                    // My devices' own group: only I may write in it (MLS says who sent it).
                    if (sender != username) return listOf(News(cid, News.Kind.Problem, text = "fremde Nachricht in der Gerätegruppe – verworfen"))
                    chat.put("intern", true)
                    when (data.optString("t")) {
                        "roster" -> chat.put("roster", JSONObject().put(username, data.optJSONObject("devices")?.optJSONArray(username) ?: JSONArray()))
                        "verlauf" -> return if (data.optString("fuer") == deviceId) takeHistory(data) else emptyList()
                        "einstellungen" -> {
                            for (k in listOf("lesebestaetigungen", "tippanzeige")) if (data.has(k)) profiles.put(k, data.getBoolean(k))
                            return listOf(News(null, News.Kind.Settings))
                        }
                        "gespraech" -> {
                            val key = data.optString("key")
                            val target = chats.optJSONObject(data.optString("chat"))
                            if (target != null && key in syncedChatKeys) { if (data.isNull("value")) target.remove(key) else target.put(key, data.get("value")) }
                            return listOf(News(null, News.Kind.Settings))
                        }
                        "kontaktbild" -> {
                            val value = data.opt("bild") as? String
                            if (!value.isNullOrEmpty() && value.length <= PICTURE_MAX * 2) contactPictures.put(data.optString("name"), value)
                            else contactPictures.remove(data.optString("name"))
                            return listOf(News(null, News.Kind.Settings))
                        }
                        "blockiert" -> {
                            if (data.optBoolean("an")) blocked.put(data.optString("name"), System.currentTimeMillis() / 1000) else blocked.remove(data.optString("name"))
                            return listOf(News(null, News.Kind.Settings))
                        }
                        "stumm" -> {
                            chats.optJSONObject(data.optString("chat"))?.put("stumm", data.optBoolean("an"))
                            return listOf(News(null, News.Kind.Settings))
                        }
                        "name" -> {
                            val name = JSONObject().put("vorname", data.optString("vorname").take(40)).put("nachname", data.optString("nachname").take(40))
                            profiles.put("name", name)
                            (profiles.optJSONObject("names") ?: JSONObject().also { profiles.put("names", it) }).put(sender, JSONObject(name.toString()))
                            return listOf(News(null, News.Kind.Settings))
                        }
                        "profile" -> {
                            profiles.put("me", data.optString("bild").ifEmpty { null } ?: JSONObject.NULL)
                            return listOf(News(null, News.Kind.Settings))
                        }
                    }
                    return emptyList()
                }
                val kind = data.optString("t")
                if (blocked.has(sender) && kind in setOf("text", "anhang", "reaktion", "tippt", "bearbeiten", "zurueckrufen", "profile", "name"))
                    return emptyList()     // blocked (1f800b4f): nothing from them, no receipt, no notification
                if (chat.has("geloescht") && kind in setOf("text", "anhang")) {
                    // A new message brings a deleted conversation back – what was there stays in „Zuletzt gelöscht“.
                    val stamp = chat.optLong("geloescht").takeIf { it > 1 } ?: (System.currentTimeMillis() / 1000)
                    liveMessagesAll(cid).forEach { if (!it.has("geloescht")) it.put("geloescht", stamp) }
                    chat.remove("geloescht")
                }
                when (data.optString("t")) {
                    "roster" -> {
                        val devices = data.optJSONObject("devices") ?: JSONObject()
                        val names = devices.keys().asSequence().toSet()
                        // Only names that really are in the MLS group may get packets.
                        if (names != members(cid).toSet() || names.any { devices.optJSONArray(it) == null })
                            return listOf(News(cid, News.Kind.Problem, text = "Teilnehmerliste von $sender passt nicht zur Gruppe – nicht übernommen"))
                        // Per device too (da5cbfcb): as many devices per person as the MLS group has leaves for them.
                        for (name in names) {
                            val count = devices.getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) }.toSet().size }
                            if (count != device.deviceKeys(cid.unhex(), name).size)
                                return listOf(News(cid, News.Kind.Problem, text = "Geräteliste von $name passt nicht zur Gruppe – nicht übernommen"))
                        }
                        val oldRoster = chat.getJSONObject("roster")
                        val known = oldRoster.keys().asSequence().toSet()
                        val lines = mutableListOf<News>()
                        if (known.isNotEmpty()) {
                            for (name in names.filter { it !in known }.sorted()) {
                                val text = if (name == username) "${display(sender)} hat dich hinzugefügt." else "${display(sender)} hat ${display(name)} hinzugefügt."
                                system(chat, text)
                                lines.add(News(cid, News.Kind.System, text = text, received = at))
                            }
                            for (name in names.filter { it in known && it != username }.sorted()) {
                                if (devices.getJSONArray(name).length() > (oldRoster.optJSONArray(name)?.length() ?: 0)) {
                                    val text = "${display(name)} benutzt jetzt ein weiteres Gerät."
                                    system(chat, text)
                                    lines.add(News(cid, News.Kind.System, text = text, received = at))
                                }
                            }
                        } else if (profiles.has("gekoppelt") && (devices.optJSONArray(username)?.length() ?: 0) > 1 &&
                            chat.getJSONArray("messages").length() == 0) {
                            system(chat, "Ältere Nachrichten gibt es nur auf deinen anderen Geräten.")
                        }
                        chat.put("roster", devices)
                        data.optString("title").takeIf { it.isNotEmpty() }?.let { chat.put("title", it) }
                        if ((profiles.opt("me") is String || profiles.has("name")) && sender != username) owePicture.add(cid)
                        return lines
                    }
                    "titel" -> {
                        val title = data.optString("titel").take(80)
                        chat.put("title", title)
                        val text = if (title.isNotEmpty()) "${display(sender)} hat die Gruppe „$title“ genannt." else "${display(sender)} hat den Gruppennamen entfernt."
                        system(chat, text)
                        return listOf(News(cid, News.Kind.System, text = text, received = at))
                    }
                    "gruppenbild" -> {
                        val picture = data.optString("bild")
                        val raw = runCatching { if (picture.isEmpty()) ByteArray(0) else unb64(picture) }.getOrDefault(byteArrayOf(0))
                        val jpeg = raw.size >= 3 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte() && raw[2] == 0xFF.toByte()
                        if (raw.isNotEmpty() && (raw.size > PICTURE_MAX || !jpeg))
                            return listOf(News(cid, News.Kind.Problem, text = "Gruppenbild von $sender nicht übernommen"))
                        chat.put("bild", if (picture.isEmpty()) JSONObject.NULL else picture)
                        val text = if (picture.isNotEmpty()) "${display(sender)} hat das Gruppenbild geändert." else "${display(sender)} hat das Gruppenbild entfernt."
                        system(chat, text)
                        return listOf(News(cid, News.Kind.System, text = text, received = at))
                    }
                    "verlassen" -> {
                        chat.getJSONObject("roster").remove(sender)
                        val text = "${display(sender)} hat die Unterhaltung verlassen."
                        system(chat, text)
                        val remaining = chat.getJSONObject("roster").keys().asSequence().sorted().toList()
                        if (remaining.firstOrNull() == username && sender in members(cid)) oweRemoval.getOrPut(cid) { mutableSetOf() }.add(sender)
                        return listOf(News(cid, News.Kind.System, text = text, received = at))
                    }
                    "name" -> {
                        val first = data.optString("vorname").trim().take(40)
                        val last = data.optString("nachname").trim().take(40)
                        val names = profiles.optJSONObject("names") ?: JSONObject().also { profiles.put("names", it) }
                        if (first.isEmpty() && last.isEmpty()) names.remove(sender) else names.put(sender, JSONObject().put("vorname", first).put("nachname", last))
                        return listOf(News(cid, News.Kind.Picture, from = sender, received = at))
                    }
                    "profile" -> {
                        val picture = data.optString("bild")
                        val raw = runCatching { if (picture.isEmpty()) ByteArray(0) else unb64(picture) }.getOrDefault(byteArrayOf(0))
                        val jpeg = raw.size >= 3 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte() && raw[2] == 0xFF.toByte()
                        if (raw.isNotEmpty() && (raw.size > PICTURE_MAX || !jpeg))
                            return listOf(News(cid, News.Kind.Problem, text = "Profilbild von $sender nicht übernommen"))
                        val people = profiles.getJSONObject("people")
                        if (raw.isEmpty()) people.remove(sender) else people.put(sender, picture)
                        return listOf(News(cid, News.Kind.Picture, from = sender, received = at))
                    }
                    "bearbeiten", "zurueckrufen" -> {
                        // Only the one who wrote it, only in time – on the sender's own clock (message „at“ vs. the edit's).
                        val target = findMessage(cid, data.optString("mid")) ?: return emptyList()
                        val sentAt = data.optLong("at")
                        if (target.optString("from") != sender || target.optBoolean("zurueckgerufen")) return emptyList()
                        if (data.optString("t") == "bearbeiten") {
                            val text = data.optString("text").trim()
                            if (text.isEmpty() || target.optString("text").isEmpty() || sentAt - target.optLong("at") > EDIT_WINDOW_S + LATE_S ||
                                (target.optJSONArray("bearbeitet")?.length() ?: 0) >= EDIT_MAX) return emptyList()
                            applyEdit(target, text, sentAt)
                        } else {
                            if (sentAt - target.optLong("at") > UNSEND_WINDOW_S + LATE_S) return emptyList()
                            applyUnsend(chat, target)
                        }
                        return listOf(News(cid, News.Kind.Edited, from = sender, received = at))
                    }
                    "einmal_geoeffnet" -> {
                        val target = findMessage(cid, data.optString("mid"))?.takeIf { it.optJSONObject("anhang")?.optBoolean("einmal") == true } ?: return emptyList()
                        if (sender == username) {          // opened on another of my devices: gone here too
                            target.put("geoeffnet", System.currentTimeMillis() / 1000)
                            File(File(folder, "anhaenge"), target.getJSONObject("anhang").optString("id")).delete()
                        } else if (target.optString("from") == username)
                            (target.optJSONObject("geoeffnet_von") ?: JSONObject().also { target.put("geoeffnet_von", it) }).put(sender, System.currentTimeMillis() / 1000)
                        return listOf(News(cid, News.Kind.Settings))
                    }
                    "bildschirmfoto" -> {
                        val text = (if (sender == username) "Du hast" else "${display(sender)} hat") + " ein Bildschirmfoto vom Einmal-Bild gemacht."
                        system(chat, text)
                        return listOf(News(cid, News.Kind.System, text = text, received = at))
                    }
                    "reaktion" -> {
                        val target = findMessage(cid, data.optString("mid")) ?: return emptyList()
                        val emoji = data.optString("emoji").trim()
                        if (emoji.isNotEmpty() && !isSingleEmoji(emoji)) return emptyList()   // no „Gelesen“ dressed up as a reaction
                        val reactions = target.optJSONObject("reaktionen") ?: JSONObject().also { target.put("reaktionen", it) }
                        if (emoji.isEmpty()) reactions.remove(sender) else reactions.put(sender, emoji)
                        return listOf(News(cid, News.Kind.Reaction, from = sender, text = emoji, received = at))
                    }
                    "tippt" -> {
                        val late = kotlin.math.abs(System.currentTimeMillis() / 1000 - data.optLong("at")) > TYPING_LATE_S
                        if (sender == username || !typingIndicator || late) return emptyList()
                        typingSeen.getOrPut(cid) { mutableMapOf() }[sender] = System.currentTimeMillis()
                        return listOf(News(cid, News.Kind.Typing, from = sender, received = at))
                    }
                    "quittung" -> {
                        val kind = data.optString("art")
                        val mids = data.optJSONArray("mids")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
                        if (kind in setOf("zugestellt", "gelesen") && sender != username) {
                            val sentAt = data.optLong("at", System.currentTimeMillis() / 1000)
                            val list = chat.getJSONArray("messages")
                            for (k in 0 until list.length()) {
                                val m = list.getJSONObject(k)
                                if (m.optString("from") == username && m.optString("mid") in mids)
                                    (m.optJSONObject(kind) ?: JSONObject().also { m.put(kind, it) }).put(sender, sentAt)
                            }
                        }
                        return listOf(News(cid, News.Kind.Receipt, received = at))
                    }
                    "anhang" -> {
                        val info = JSONObject()
                        for (k in listOf("id", "schluessel", "sha256", "name", "mime", "groesse", "art", "dauer", "vorschau")) if (data.has(k)) info.put(k, data.get(k))
                        // the wave only as it should be: a voice message, at most WAVE_MAX numbers 0…31
                        data.optJSONArray("welle")?.takeIf { w -> data.optString("art") == "sprache" && w.length() in 1..WAVE_MAX &&
                            (0 until w.length()).all { w.opt(it) is Int && w.getInt(it) in 0..31 } }?.let { info.put("welle", it) }
                        val good = Regex("[0-9a-f]{32}").matches(info.optString("id")) &&
                            runCatching { unb64(info.optString("schluessel")).size == 32 }.getOrDefault(false) &&
                            Regex("[0-9a-f]{64}").matches(info.optString("sha256")) && info.optString("art") in KINDS &&
                            info.optString("vorschau").length <= 64 * 1024
                        if (!good) return listOf(News(cid, News.Kind.Problem, text = "Anhang von $sender unvollständig – nicht übernommen"))
                        info.put("name", info.optString("name", "Datei").take(200).replace('/', '_'))
                        for (flag in listOf("einmal", "geheim")) if (data.optBoolean(flag) && info.optString("art") == "bild") info.put(flag, true)
                        if (info.optBoolean("einmal")) info.remove("vorschau")
                        val sent = data.optLong("at", System.currentTimeMillis() / 1000)
                        val message = JSONObject().put("from", sender).put("text", data.optString("text")).put("at", sent).put("anhang", info)
                        data.optJSONObject("album")?.let { a ->
                            val nr = a.optInt("nr"); val count = a.optInt("anzahl")
                            if (Regex("[0-9a-f]{16}").matches(a.optString("id")) && count in 2..ALBUM_MAX && nr in 1..count && !info.optBoolean("einmal") && !info.optBoolean("geheim"))
                                message.put("album", JSONObject().put("id", a.optString("id")).put("nr", nr).put("anzahl", count))
                        }
                        typingSeen[cid]?.remove(sender)
                        rememberMid(cid, message, data)
                        chat.getJSONArray("messages").put(message)
                        return listOf(News(cid, News.Kind.Text, from = sender, text = describe(message), at = sent, received = at, attachment = info))
                    }
                    "text" -> {
                        val text = data.optString("text")
                        val sent = data.optLong("at", System.currentTimeMillis() / 1000)
                        val message = JSONObject().put("from", sender).put("text", text).put("at", sent)
                        data.optJSONObject("antwort")?.takeIf { Regex("[0-9a-f]{16}").matches(it.optString("mid")) }?.let { a ->
                            message.put("antwort", JSONObject().put("mid", a.optString("mid")).put("from", a.optString("from").take(40))
                                .put("text", a.optString("text").take(120)))
                        }
                        typingSeen[cid]?.remove(sender)
                        rememberMid(cid, message, data)
                        chat.getJSONArray("messages").put(message)
                        // „@ich“ / „@alle“ in a group: a notification even when the chat is muted (iOS)
                        val group = chat.getJSONObject("roster").length() > 2 || chat.optString("title").isNotEmpty()
                        val mention = group && mentionsMe(text)
                        return listOf(News(cid, News.Kind.Text, from = sender, text = text, at = sent, received = at, mention = mention))
                    }
                }
                return emptyList()
            }
        }
    }
}
