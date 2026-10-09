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

class LiChatException(message: String) : Exception(message)

private const val WELCOME: Byte = 'W'.code.toByte()
private const val MLS: Byte = 'M'.code.toByte()
private val CHATS_MAGIC = "LICHATC1".toByteArray()
private val PROFILE_MAGIC = "LICHATP1".toByteArray()
const val SUPPLY = 20
const val PICTURE_MAX = 150 * 1024
private val ATTACHMENT_MAGIC = "LICHATA1".toByteArray()
const val ATTACHMENT_MAX = 100 * 1024 * 1024 - 64
val KINDS = setOf("bild", "video", "sprache", "datei")
private const val TIMEOUT_S = 20
val MENTION = Regex("(?<![\\w@])@([a-zA-Z0-9][a-zA-Z0-9._-]{1,31})")
val EVERYONE = setOf("alle", "all")
const val UNREADABLE = "Eine Nachricht konnte nicht entschlüsselt werden."
const val EDIT_WINDOW_S = 15 * 60L   // like iOS: edit for 15 minutes …
const val EDIT_MAX = 5               // … at most five times
const val UNSEND_WINDOW_S = 2 * 60L  // and call back for two minutes
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
    enum class Kind { Text, Joined, Picture, Removed, System, Problem, Receipt, Reaction, Typing, Edited }
}

/** One line for the list and notifications: the text, or what the attachment is (the same words as Ubuntu). */
/** „Erika Mustermann“ → „erika.mustermann“: a user name the server accepts, made from the name instead of refusing input. */
fun suggestUsername(first: String, last: String = ""): String {
    var text = "$first $last".trim().lowercase()
    listOf("ä" to "ae", "ö" to "oe", "ü" to "ue", "ß" to "ss").forEach { (a, b) -> text = text.replace(a, b) }
    text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "")
    return text.replace(Regex("[^a-z0-9._-]+"), ".").trim('.', '_', '-').replace(Regex("\\.{2,}"), ".").take(32)
}

private fun pictographic(cp: Int) = cp >= 0x1F000 || cp in setOf(0x00A9, 0x00AE, 0x3030, 0x303D, 0x3297, 0x3299, 0x2139) ||
    (cp in 0x2000..0x2BFF && !Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))

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
    val info = message.optJSONObject("anhang") ?: return message.optString("text")
    return when (info.optString("art")) {
        "bild" -> "Foto"; "video" -> "Video"; "sprache" -> "Sprachnachricht"
        else -> "Datei: " + info.optString("name")
    }
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
            if (code !in 200..299) throw LiChatException("Server: ${json?.optString("error")?.ifBlank { null } ?: "Fehler"} ($code)")
            return json ?: throw LiChatException("Server: unverständliche Antwort ($code)")
        } catch (e: java.io.IOException) {
            throw LiChatException("Server nicht erreichbar: ${e.message}")
        } finally {
            open = null
            connection.disconnect()
        }
    }

    /** Bytes in, bytes out – the attachments (up to 100 MB, no JSON around them). */
    fun raw(method: String, path: String, data: ByteArray? = null): ByteArray {
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
                connection.outputStream.use { it.write(data) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream?.use { String(it.readBytes()) } ?: ""
                val message = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw LiChatException("Server: ${message.ifBlank { "Fehler" }} ($code)")
            }
            return connection.inputStream.use { it.readBytes() }
        } catch (e: java.io.IOException) {
            throw LiChatException("Server nicht erreichbar: ${e.message}")
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
        server = Server(info.getString("server"), info.getString("token"))
    }

    val username: String get() = info.getString("username")
    val deviceId: String get() = info.getString("device")

    companion object {
        fun register(folder: File, server: String, invite: String, username: String, key: ByteArray): Account {
            if (File(folder, "account.json").exists()) throw LiChatException("Hier gibt es schon ein Konto")
            folder.mkdirs()
            val anonymous = Server(server)
            val device = Device(username)
            val answer = anonymous.call("POST", "/v1/register", JSONObject().put("invite", invite).put("username", username)
                .put("identity", b64(device.identityKey())))
            val info = JSONObject().put("server", anonymous.url).put("username", username)
                .put("device", answer.getString("device")).put("token", answer.getString("token"))
            // Key packages are made – their private halves kept in the state – before they are offered.
            val packages = device.keyPackages(SUPPLY.toUInt())
            val reserve = device.lastResortKeyPackage()
            writePrivate(File(folder, "state.bin"), device.save(key))
            writePrivate(File(folder, "account.json"), info.toString(1).toByteArray())
            device.close()
            val account = Account(folder, key)
            account.save()
            account.server.call("POST", "/v1/key-packages", JSONObject().put("key_packages", JSONArray(packages.map { b64(it) })))
            account.server.call("PUT", "/v1/key-packages/last-resort", JSONObject().put("key_package", b64(reserve)))
            return account
        }

        // -------------------------------------------------------------------------------------- linking (card da5cbfcb)
        // Like Signal: the NEW device shows a code, an existing device of the person accepts it, then the new device
        // makes its keys for that name and signs in once; the existing device puts it into every conversation.

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
                .put("device", answer.getString("device")).put("token", answer.getString("token"))
            val packages = device.keyPackages(SUPPLY.toUInt())
            val reserve = device.lastResortKeyPackage()
            writePrivate(File(folder, "state.bin"), device.save(key))
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
    fun acceptLink(code: String, waitS: Long = 600, pollMs: Long = 2000): String {
        server.call("POST", "/v1/kopplung/annehmen", JSONObject().put("code", code))
        val end = System.currentTimeMillis() + waitS * 1000
        while (System.currentTimeMillis() < end) {
            val state = server.call("GET", "/v1/kopplung/${URLEncoder.encode(code, "UTF-8")}")
            state.optString("device").takeIf { it.isNotEmpty() }?.let { syncOwnDevices(); return it }
            Thread.sleep(pollMs)
        }
        throw LiChatException("Das neue Gerät hat sich nicht gemeldet – noch einmal versuchen")
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

    fun mute(cid: String, on: Boolean) = synchronized(lock) { chat(cid).put("stumm", on); save() }

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
    fun retry(cid: String, mid: String): JSONObject {
        val list = chat(cid).getJSONArray("messages")
        val message = (0 until list.length()).map { list.getJSONObject(it) }.first { it.optString("mid") == mid }
        val content = message.optJSONObject("anhang")?.let { JSONObject(it.toString()).put("t", "anhang") }
            ?: JSONObject().put("t", "text").put("text", message.optString("text"))
        content.put("at", message.optLong("at")).put("mid", mid)
        message.put("status", "wartet")
        return deliver(cid, message, content)
    }

    // ------------------------------------------------------------------------------------------ delivery state (db70d358)

    /** Read receipts: off by default like the iPhone (Olaf), mutual – off means none sent and no blue ticks seen. */
    var readReceipts: Boolean
        get() = profiles.optBoolean("lesebestaetigungen", false)
        set(on) = synchronized(lock) { profiles.put("lesebestaetigungen", on); save() }

    // ------------------------------------------------------------------------------------------ typing (884f7c8a)

    /** „… tippt“ (Olaf 09.10.): off by default for privacy, mutual like read receipts; flüchtig – never stored. */
    var typingIndicator: Boolean
        get() = profiles.optBoolean("tippanzeige", false)
        set(on) = synchronized(lock) { profiles.put("tippanzeige", on); if (!on) typingSeen.clear(); save() }

    /** I am typing in this chat: the others see „…“ – at most every 3 s, nothing when switched off. */
    fun typing(cid: String): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (!typingIndicator || chat(cid).optString("removed").isNotEmpty() || now - (typed[cid] ?: -TYPING_EVERY_MS) < TYPING_EVERY_MS) return false
        typed[cid] = now
        val packet: ByteArray
        val targets: List<String>
        synchronized(lock) {
            packet = device.encrypt(cid.unhex(), payload(JSONObject().put("t", "tippt").put("at", System.currentTimeMillis() / 1000)))
            save()
            targets = targets(cid)
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
        if (!readReceipts) return
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
        if (others.isNotEmpty() && readReceipts && others.all { read.has(it) }) return "gelesen" to others.maxOf { read.getLong(it) }
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

    fun members(cid: String): List<String> =
        if (chat(cid).optString("removed").isNotEmpty()) listOf(username) else device.members(cid.unhex())

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
                       preview: ByteArray? = null): JSONObject {
        if (kind !in KINDS) throw LiChatException("unbekannte Art von Anhang")
        chat(cid).optString("removed").takeIf { it.isNotEmpty() }?.let { throw LiChatException("$it hat dich aus dem Gespräch entfernt") }
        if (data.size > ATTACHMENT_MAX) throw LiChatException("Anhang zu groß (höchstens 100 MB)")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(ATTACHMENT_MAGIC)
        val sealed = nonce + cipher.doFinal(data)
        val id = JSONObject(String(server.raw("PUT", "/v1/anhaenge", sealed))).getString("id")
        val info = JSONObject().put("id", id).put("schluessel", b64(key)).put("sha256", sha256(sealed)).put("name", name.take(200))
            .put("mime", mime.take(100)).put("groesse", data.size).put("art", kind)
        durationS?.let { info.put("dauer", Math.round(it * 10) / 10.0) }
        preview?.let { info.put("vorschau", b64(it)) }
        keepSealed(id, sealed)
        val at = System.currentTimeMillis() / 1000
        val message = JSONObject().put("from", username).put("text", "").put("at", at).put("anhang", info).put("mid", newMid()).put("status", "wartet")
        synchronized(lock) { chat(cid).getJSONArray("messages").put(message); save() }
        return deliver(cid, message, JSONObject(info.toString()).put("t", "anhang").put("at", at).put("mid", message.getString("mid")))
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
        val value = if (user == username) profiles.opt("me") else profiles.getJSONObject("people").opt(user)
        return (value as? String)?.takeIf { it.isNotEmpty() }?.let { unb64(it) }
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
    fun fetch(waitS: Int = 0): List<News> {
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
                        val good = Regex("[0-9a-f]{32}").matches(info.optString("id")) &&
                            runCatching { unb64(info.optString("schluessel")).size == 32 }.getOrDefault(false) &&
                            Regex("[0-9a-f]{64}").matches(info.optString("sha256")) && info.optString("art") in KINDS &&
                            info.optString("vorschau").length <= 64 * 1024
                        if (!good) return listOf(News(cid, News.Kind.Problem, text = "Anhang von $sender unvollständig – nicht übernommen"))
                        info.put("name", info.optString("name", "Datei").take(200).replace('/', '_'))
                        val sent = data.optLong("at", System.currentTimeMillis() / 1000)
                        val message = JSONObject().put("from", sender).put("text", "").put("at", sent).put("anhang", info)
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
