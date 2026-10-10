package io.github.veritasx1.lichat

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import io.github.veritasx1.lichat.core.Account
import io.github.veritasx1.lichat.core.KeyVault
import io.github.veritasx1.lichat.core.LiChatException
import io.github.veritasx1.lichat.core.Pictures
import io.github.veritasx1.lichat.core.UNREACHABLE
import io.github.veritasx1.lichat.ui.AttachmentActions
import io.github.veritasx1.lichat.ui.AttachmentSheet
import io.github.veritasx1.lichat.ui.ChatList
import io.github.veritasx1.lichat.ui.PhotoViewer
import io.github.veritasx1.lichat.ui.ChatScreen
import io.github.veritasx1.lichat.ui.Details
import io.github.veritasx1.lichat.ui.Explain
import io.github.veritasx1.lichat.ui.GroupActions
import io.github.veritasx1.lichat.ui.LocalPalette
import io.github.veritasx1.lichat.ui.NewMessage
import io.github.veritasx1.lichat.ui.Palette
import io.github.veritasx1.lichat.ui.SettingsScreen
import io.github.veritasx1.lichat.ui.SetupScreen
import java.util.concurrent.Executors

/** Everything the screens send to the server runs here, one after the other (the service's loop only receives). */
private val worker = Executors.newSingleThreadExecutor()
private val main = Handler(Looper.getMainLooper())

fun <T> work(job: () -> T, done: (T?, String?) -> Unit) = worker.execute {
    val result = runCatching(job)
    val error = result.exceptionOrNull()?.let { (it as? LiChatException)?.message ?: "Fehler: ${it.message}" }
    main.post { done(result.getOrNull(), error) }
}

/** Long waits (a new device to link, up to 10 minutes) on their own thread – sending must not wait behind them. */
private val waiter = Executors.newCachedThreadPool()

fun <T> waitWork(job: () -> T, done: (T?, String?) -> Unit) = waiter.execute {
    val result = runCatching(job)
    val error = result.exceptionOrNull()?.let { (it as? LiChatException)?.message ?: "Fehler: ${it.message}" }
    main.post { done(result.getOrNull(), error) }
}

sealed class Page {
    data object List : Page()
    data class Chat(val cid: String) : Page()
    data object New : Page()
    data class Info(val cid: String) : Page()
    data object Settings : Page()
}

/** The app lock's state (99eea3f7): locked at a cold start and after the chosen time in the background. */
object AppLock {
    val locked = mutableStateOf(false)
    private var stoppedAt = -1L
    /** The system's PIN screen is an activity of its own: coming back from it must not lock again. */
    @Volatile var prompting = false

    fun started(context: android.content.Context) {
        if (!Settings.lock(context)) { locked.value = false; return }
        if (prompting) return
        val away = if (stoppedAt < 0) Long.MAX_VALUE else (android.os.SystemClock.elapsedRealtime() - stoppedAt) / 1000
        if (away >= Settings.lockAfter(context)) locked.value = true
    }

    fun stopped() { stoppedAt = android.os.SystemClock.elapsedRealtime() }
}

class MainActivity : ComponentActivity() {
    private val opened = mutableStateOf<String?>(null)

    /** The app overview shows no content while the lock is on (Android 13+: without blocking screenshots; before: FLAG_SECURE). */
    fun applyOverview() {
        val on = Settings.lock(this)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!on)
        else if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Unlock with the phone's own screen lock – fingerprint/face where set up, else PIN, pattern or password. No own password. */
    @Suppress("DEPRECATION")
    fun unlock() {
        if (AppLock.prompting) return
        AppLock.prompting = true
        // Never crash here – a crash at every start shut Olaf out (20:16, missing USE_BIOMETRIC): fall back to the system's
        // PIN screen, and without any screen lock there is nothing to protect with, so it opens.
        try { prompt() } catch (e: Exception) {
            android.util.Log.w("LiChat", "Entsperren per BiometricPrompt nicht möglich: $e")
            try { keyguard() } catch (e2: Exception) { AppLock.prompting = false; AppLock.locked.value = false }
        }
    }

    @Suppress("DEPRECATION")
    private fun keyguard() {
        val intent = getSystemService(android.app.KeyguardManager::class.java)?.takeIf { it.isDeviceSecure }?.createConfirmDeviceCredentialIntent("LiChat entsperren", null)
        if (intent == null) { AppLock.prompting = false; AppLock.locked.value = false } else startActivityForResult(intent, UNLOCK)
    }

    @Suppress("DEPRECATION")
    private fun prompt() {
        if (Build.VERSION.SDK_INT >= 29) {
            val builder = android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle("LiChat entsperren")
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else builder.setDeviceCredentialAllowed(true)
            builder.build().authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                    AppLock.prompting = false; AppLock.locked.value = false
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { AppLock.prompting = false }
            })
        } else keyguard()
    }

    @Deprecated("only for Android 8–9")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UNLOCK) { AppLock.prompting = false; if (resultCode == RESULT_OK) AppLock.locked.value = false }
    }

    override fun onStart() {
        super.onStart()
        AppLock.started(this)
        applyOverview()
    }

    override fun onStop() {
        AppLock.stopped()
        super.onStop()
    }

    companion object { const val UNLOCK = 41 }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.channels(this)
        // Plain copies handed to other apps (a video, a PDF) live only until the next start.
        java.io.File(cacheDir, "geoeffnet").deleteRecursively()
        opened.value = intent.getStringExtra("gespraech")
        runCatching { LiChatService.start(this) }
        setContent { LiChatApp(opened) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("gespraech")?.let { opened.value = it }
    }

    override fun onResume() {
        super.onResume()
        Hub.visible = true
    }

    override fun onPause() {
        Hub.visible = false
        super.onPause()
    }
}

@Composable
fun LiChatApp(opened: androidx.compose.runtime.MutableState<String?> = mutableStateOf(null), vault: KeyVault = KeyVault.Keystore) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val window = (context as? ComponentActivity)?.window
    // The status bar's clock follows the appearance, also when it changes while LiChat is open (Li-Gestaltung).
    if (window != null) SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).run { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    var account by remember { mutableStateOf(runCatching { Hub.open(context, vault) }.getOrNull()) }
    val version by Hub.changes.collectAsState()
    val serverProblem by Hub.problem.collectAsState()
    var problem by remember { mutableStateOf<String?>(null) }
    val connected by Hub.connected.collectAsState()
    // Back online: a „no connection“ line is out of date – what failed is sent again by the service (8e7a493b, 72c8a716).
    LaunchedEffect(connected) { if (connected && problem in UNREACHABLE) problem = null }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<Page>(Page.List) }
    val seen = remember { mutableStateMapOf<String, Int>() }
    var preview by remember { mutableStateOf(Settings.preview(context)) }
    var lockOn by remember { mutableStateOf(Settings.lock(context)) }
    var lockAfter by remember { mutableStateOf(Settings.lockAfter(context)) }
    val locked by AppLock.locked
    var unrestricted by remember { mutableStateOf(runCatching { Settings.unrestricted(context) }.getOrDefault(true)) }
    var explaining by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var recording by remember { mutableStateOf<Int?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var voiceSpeed by remember { mutableStateOf(1f) }        // 1× / 1,5× / 2× like Signal (1a682a79)
    val media = remember { Media(context) }
    var notifying by remember { mutableStateOf(runCatching { Settings.notifying(context) }.getOrDefault(true)) }
    // Back from Android's settings: look again (the person may have switched notifications on or off there).
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notifying = runCatching { Settings.notifying(context) }.getOrDefault(true)
                unrestricted = runCatching { Settings.unrestricted(context) }.getOrDefault(true)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    fun openNotificationSettings() {
        runCatching { context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)) }
    }

    LaunchedEffect(opened.value) { opened.value?.let { cid -> if (account?.chats?.has(cid) == true) page = Page.Chat(cid); opened.value = null } }
    Hub.openChat = (page as? Page.Chat)?.cid

    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = runCatching { Settings.unrestricted(context) }.getOrDefault(true)
    }
    fun askBattery() {
        runCatching { battery.launch(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askBattery() }
    // The same picker for my profile picture and a group's picture (groupPictureFor = that chat).
    var groupPictureFor by remember { mutableStateOf<String?>(null) }
    var contactPictureFor by remember { mutableStateOf<String?>(null) }   // „Bild ändern“ of a person: only for me
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val acc = account ?: return@rememberLauncherForActivityResult
        val group = groupPictureFor.also { groupPictureFor = null }
        val person = contactPictureFor.also { contactPictureFor = null }
        if (uri != null) work({
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw LiChatException("Bild nicht lesbar")
            when {
                person != null -> acc.setContactPicture(person, Pictures.prepare(bytes))
                group != null -> acc.setGroupPicture(group, Pictures.prepare(bytes))
                else -> acc.setPicture(Pictures.prepare(bytes))
            }
        }) { _, error -> problem = error; Hub.changed() }
    }

    fun chatId() = (page as? Page.Chat)?.cid
    fun sendFile(uri: Uri?, asFile: Boolean) {
        val acc = account ?: return
        val cid = chatId() ?: return
        if (uri == null) return
        work({ media.send(acc, cid, uri, asFile) }) { _, error -> problem = error; Hub.changed() }
    }
    // A photo lies above the input first (2fcc3eef, like Messages): text to it, options by a tap; videos go at once.
    val staged = remember { androidx.compose.runtime.mutableStateListOf<io.github.veritasx1.lichat.ui.Staged>() }
    var stagedOpen by remember { mutableStateOf<Int?>(null) }
    // they belong to the conversation they were picked in – and wait there when one goes elsewhere and comes back (979f9b22)
    val stagedByChat = remember { mutableMapOf<String, List<io.github.veritasx1.lichat.ui.Staged>>() }
    var stagedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chatId()) {
        stagedFor?.let { stagedByChat[it] = staged.toList() }
        staged.clear(); stagedOpen = null
        chatId()?.let { cid -> staged.addAll(stagedByChat.remove(cid).orEmpty()) }
        stagedFor = chatId()
    }
    // Up to 10 at once (5749ebd0, like Messages): photos lie above the input, videos go at once.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(io.github.veritasx1.lichat.core.ALBUM_MAX)) { uris ->
        for (uri in uris) {
            if (context.contentResolver.getType(uri)?.startsWith("image/") == true) {
                if (staged.size < io.github.veritasx1.lichat.core.ALBUM_MAX)
                    staged.add(io.github.veritasx1.lichat.ui.Staged(uri, runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()))
            } else sendFile(uri, false)
        }
    }
    fun sendStaged(caption: String) {
        val acc = account ?: return
        val cid = chatId() ?: return
        val items = staged.toList()
        staged.clear()
        work({
            // Two or more ordinary photos form an album (5749ebd0): all bubbles at once, then up one after the other.
            val plain = items.filter { !it.once && !it.secret }
            val album = if (plain.size >= 2) acc.newAlbum(plain.size) else null
            var problemText: String? = null
            val laid = items.mapIndexedNotNull { index, item -> try {
                val text = if (index == items.lastIndex) caption else ""     // the text belongs to the last photo
                val part = album?.takeIf { !item.once && !item.secret }?.invoke(plain.indexOf(item) + 1)
                if (item.markedUp && item.bytes != null) {
                    // the marked-up picture: a new JPEG, then the same way as every photo (upright, 2048 px, preview)
                    val (jpeg, preview) = Pictures.photo(item.bytes)
                    acc.sendAttachment(cid, jpeg, "Foto.jpg", "image/jpeg", "bild", preview = preview, once = item.once, secret = item.secret,
                        caption = text, album = part, defer = true)
                } else media.send(acc, cid, item.uri, item.asFile, item.once, item.secret, text, album = part, defer = true)
            } catch (e: Exception) {
                // one photo not readable: the others still go (laid down ones never hang on „wartet“)
                problemText = problemText ?: (e as? LiChatException)?.message ?: "Ein Foto ließ sich nicht vorbereiten"; null
            } }
            for (message in laid) {
                try { acc.sendQueued(cid, message) } catch (e: LiChatException) { problemText = problemText ?: e.message }
            }
            problemText?.let { throw LiChatException(it) }
        }) { _, error -> problem = error; Hub.changed() }
    }
    // A one-time picture open now: chat, mid, bytes (only in memory) and whether it is also secret.
    var oncePhoto by remember { mutableStateOf<Triple<String, String, ByteArray>?>(null) }
    var onceSecret by remember { mutableStateOf(false) }
    var onceOpening by remember { mutableStateOf<String?>(null) }
    var markingUp by remember { mutableStateOf(false) }       // 4fb76dfd: the editor over the send sheet
    // A picture chosen as a file waits in the input too (Michelle 2fcc3eef: it went at once, without preview or text) –
    // it still leaves at full size; everything else goes at once.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val type = uri?.let { context.contentResolver.getType(it) }.orEmpty()
        if (uri != null && type.startsWith("image/") && type != "image/svg+xml" && staged.size < io.github.veritasx1.lichat.core.ALBUM_MAX)
            staged.add(io.github.veritasx1.lichat.ui.Staged(uri, runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull(), asFile = true))
        else sendFile(uri, true)
    }
    fun startRecording() {
        if (media.startRecording()) {
            recording = 0
            main.postDelayed(object : Runnable {
                // every 100 ms: the seconds shown, and the loudness for the voice message's wave (1a682a79)
                override fun run() { if (recording != null) { recording = media.recordedSeconds(); media.sampleLevel(); main.postDelayed(this, 100) } }
            }, 100)
        } else problem = "Aufnahme nicht möglich"
    }
    // Scanning a new device's QR code (Olaf: „per QR-Code koppeln“): the camera only for that moment.
    var scanning by remember { mutableStateOf(false) }
    var scanFor by remember { mutableStateOf("koppeln") }                 // what the scanner looks for: koppeln | kontakt
    var myCode by remember { mutableStateOf<Pair<String?, String?>?>(null) }   // my contact QR (link, PIN) while shown (89aae55d)
    // Olaf 10.10.: invite by QR code – my invitation while shown (code, link); a scanned/opened one on the setup screen.
    var inviting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var invitingOpen by remember { mutableStateOf(false) }
    var scannedInvite by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) {
        // the invitation page's „In LiChat öffnen“ (lichat://einladung?…) lands here with server and code
        (context as? android.app.Activity)?.intent?.data?.toString()?.let { Account.parseInviteUri(it) }?.let { scannedInvite = it }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else problem = "LiChat darf die Kamera nicht benutzen – den Code stattdessen eintippen."
    }
    fun startScan(purpose: String) {
        scanFor = purpose
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) scanning = true else camera.launch(Manifest.permission.CAMERA)
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else problem = "LiChat darf das Mikrofon nicht benutzen – in den Android-Einstellungen erlauben."
    }
    val actions = AttachmentActions(
        load = { info -> account?.openAttachment(info) ?: throw LiChatException("kein Konto") },
        uploading = { mid -> account?.uploads?.get(mid) },
        speed = voiceSpeed,
        cycleSpeed = { voiceSpeed = media.cycleSpeed() },
        tick = { m -> chatId()?.let { cid -> account?.tick(cid, m) } },
        staged = staged,
        unstage = { index -> if (index in staged.indices) staged.removeAt(index) },
        openStaged = { index -> stagedOpen = index },
        sendStaged = ::sendStaged,
        cancelUpload = { mid -> account?.cancelUpload(mid) },
        openOnce = { mid ->
            val acc = account; val cid = chatId()
            // One opening at a time: a double tap must not spend the picture on a second, failing call (Michelle c5efaa9).
            if (acc != null && cid != null && onceOpening == null) { onceOpening = mid; work({ acc.openOnce(cid, mid) }) { bytes, error ->
                onceOpening = null
                problem = error
                if (bytes != null) { onceSecret = acc.findMessage(cid, mid)?.optJSONObject("anhang")?.optBoolean("geheim") == true; oncePhoto = Triple(cid, mid, bytes) }
                Hub.changed()
            } }
        },
        plus = { sheet = true },
        micStart = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording() else microphone.launch(Manifest.permission.RECORD_AUDIO)
        },
        micStop = { send ->
            recording = null
            val voice = media.stopRecording(send)
            val acc = account
            val cid = chatId()
            if (voice != null && acc != null && cid != null) work({
                acc.sendAttachment(cid, voice.first, "Sprachnachricht.m4a", "audio/mp4", "sprache", voice.second, wave = voice.third)
            }) { _, error -> problem = error; Hub.changed() }
        },
        open = { info ->
            val acc = account
            if (acc != null) {
                // The same voice message again: pause / go on from the same place (864041bf)
                if (info.optString("art") == "sprache" && playing == info.optString("id")) {
                    if (media.isPlaying) { media.pause(); paused = true } else { media.resume(); paused = false }
                }
                else work({ acc.openAttachment(info) }) { bytes, error ->
                    problem = error
                    if (bytes != null) when (info.optString("art")) {
                        "bild" -> photo = bytes
                        "sprache" -> {
                            playing = info.optString("id"); paused = false; media.play(bytes) { playing = null; paused = false }
                            val length = media.progress()?.second?.takeIf { it > 0 }?.let { it / 1000.0 }
                            chatId()?.let { cid -> acc.markListened(cid, info.optString("id"), length); Hub.changed() }      // the dot goes (1a682a79)
                        }
                        else -> media.view(bytes, info)?.let { problem = it }
                    }
                }
            }
        },
        recording = recording,
        playing = playing,
        paused = paused,
        progress = { media.progress() },
        seek = { fraction -> media.seek(fraction) },
        retry = { mid ->
            val acc = account
            val cid = chatId()
            if (acc != null && cid != null) work({ acc.retry(cid, mid) }) { _, error -> problem = error; Hub.changed() }
        },
        typing = {
            val acc = account
            val cid = chatId()
            if (acc != null && cid != null && acc.typingIndicator) work({ acc.typing(cid) }) { _, _ -> }
        },
    )
    var receipts by remember { mutableStateOf(account?.readReceipts ?: false) }
    var typingOn by remember { mutableStateOf(account?.typingIndicator ?: false) }
    var linkCode by remember { mutableStateOf<String?>(null) }
    var nameSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }   // a taken user name: free ones (ebc3d629)
    var devices by remember { mutableStateOf<List<Account.OwnDevice>?>(null) }
    var linking by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalPalette provides remember(dark) { Palette(dark) }) {
        val acc = account
        if (acc == null) {
            SetupScreen(busy, problem, linkCode, suggestions = nameSuggestions, scannedInvite = scannedInvite,
                onScanInvite = { problem = null; startScan("einladung") },
                onLink = { server ->
                    // A second device (card da5cbfcb): show a code, wait for the other device to accept it, then sign in.
                    problem = null
                    work({ Account.linkOpen(server) }) { opened, error ->
                        problem = error
                        if (opened == null) return@work
                        linkCode = opened.second
                        busy = true
                        waitWork({
                            val end = System.currentTimeMillis() + 10 * 60_000
                            while (Account.linkState(opened.first, opened.second).optString("username").isEmpty()) {
                                if (System.currentTimeMillis() > end || linkCode != opened.second) throw LiChatException("Abgelaufen – noch einmal „Code anzeigen“")
                                Thread.sleep(2000)
                            }
                            Account.linkFinish(Hub.folder(context), opened.first, opened.second, "Android", vault.create(Hub.folder(context)))
                        }) { created, failed ->
                            busy = false
                            problem = failed
                            if (created != null) {
                                linkCode = null
                                Hub.account = created
                                account = created
                                runCatching { LiChatService.start(context) }
                                if (!Settings.explained(context)) explaining = true
                            }
                        }
                    }
                },
                onLinkCancel = { linkCode = null; busy = false; problem = null }) { server, invite, name, first, last ->
                busy = true
                problem = null
                nameSuggestions = emptyList()
                work({
                    try {
                        Account.register(Hub.folder(context), server, invite, name, vault.create(Hub.folder(context)), first, last)
                            .also { if (first.isNotEmpty() || last.isNotEmpty()) it.setName(first, last) }
                    } catch (e: LiChatException) {
                        if (e.suggestions.isNotEmpty()) { nameSuggestions = e.suggestions; throw LiChatException("„$name“ ist schon vergeben. Frei wären:") }
                        throw e
                    }
                }) { created, error ->
                    busy = false
                    problem = error
                    if (created != null) {
                        Hub.account = created
                        account = created
                        runCatching { LiChatService.start(context) }
                        if (!Settings.explained(context)) explaining = true
                        // invited by someone (Olaf 10.10.): they are a contact at once, and their conversation is open – „direkt loslegen“
                        created.invitedBy?.let { inviter ->
                            work({ created.addContact(inviter); created.chatWith(inviter) }) { cid, _ -> Hub.changed(); if (cid != null) page = Page.Chat(cid) }
                        }
                    }
                }
            }
            if (scanning) io.github.veritasx1.lichat.ui.QrScanner { text ->
                scanning = false
                val invite = text?.let { Account.parseInviteUri(it) }
                if (invite != null) scannedInvite = invite else if (text != null) problem = "Das ist keine LiChat-Einladung."
            }
            return@CompositionLocalProvider
        }
        if (locked) {
            io.github.veritasx1.lichat.ui.LockScreen { (context as? MainActivity)?.unlock() }
            return@CompositionLocalProvider
        }
        LaunchedEffect(Unit) { if (!Settings.explained(context)) explaining = true }
        Navigation(page, onBack = { page = it }) { p -> when (p) {
            Page.List -> ChatList(acc, version, seen, serverProblem, unrestricted,
                onOpen = { page = Page.Chat(it) }, onNew = { problem = null; page = Page.New }, onSettings = { page = Page.Settings },
                onUnrestrict = { askBattery() }, notifying = notifying, onNotifications = { openNotificationSettings() },
                onAction = { cid, action ->
                    work({
                        when (action) {
                            "anheften" -> acc.pin(cid, true); "loesen" -> acc.pin(cid, false)
                            "ungelesen" -> acc.markUnread(cid); "gelesen" -> acc.markRead(cid)
                            "stumm" -> acc.mute(cid, true); "laut" -> acc.mute(cid, false)
                            "loeschen" -> acc.deleteChat(cid); "wiederherstellen" -> acc.restore(cid); "endgueltig" -> acc.purge(cid)
                        }
                    }) { _, error -> problem = error; Hub.changed() }
                })
            is Page.Chat -> {
                LaunchedEffect(p.cid) { Notifications.cancel(context, p.cid) }    // opened = read (2e35e6a3)
                LaunchedEffect(version, p.cid) {
                    seen[p.cid] = synchronized(acc.lock) { acc.chat(p.cid).getJSONArray("messages").length() }
                    // On screen = read (only reported with read receipts on, card db70d358).
                    // read up to here on all my devices; receipts only if on for this chat (inside markRead)
                    work({ acc.markRead(p.cid) }) { _, _ -> }
                }
                ChatScreen(acc, p.cid, version, problem, onBack = { page = Page.List }, onInfo = { page = Page.Info(p.cid) },
                    onSend = { text, replyTo -> work({ acc.send(p.cid, text, replyTo) }) { _, error -> problem = error; Hub.changed() } },
                    actions = actions,
                    onReact = { mid, emoji -> work({ acc.react(p.cid, mid, emoji) }) { _, error -> problem = error; Hub.changed() } },
                    onEdit = { mid, text -> work({ acc.edit(p.cid, mid, text) }) { _, error -> problem = error; Hub.changed() } },
                    onUnsend = { mid -> work({ acc.unsend(p.cid, mid) }) { _, error -> problem = error; Hub.changed() } },
                    onDelete = { mid -> work({ acc.deleteMessage(p.cid, mid) }) { _, error -> problem = error; Hub.changed() } })
            }
            Page.New -> {
                // 89aae55d (Olaf 10.10.): someone new by QR code or PIN, someone known by a tap – straight into the conversation.
                fun openWith(job: () -> String) = work({ acc.chatWith(job()) }) { cid, error ->
                    problem = error
                    Hub.changed()
                    if (cid != null) page = Page.Chat(cid)
                }
                NewMessage(problem, onCancel = { page = Page.List }, people = remember(version) { acc.knownPeople().map { it to acc.display(it) } },
                    onScan = { problem = null; startScan("kontakt") },
                    onMyCode = { myCode = null to null; work({ acc.contactLink() }) { link, error -> problem = error; myCode = if (link != null) link to null else null } },
                    onPin = { pin -> problem = null; openWith { acc.redeemPin(pin) } },
                    onPick = { user -> openWith { user } },
                    onInvite = { inviting = null; invitingOpen = true; work({ acc.newInvite() }) { made, error -> problem = error; if (made != null) inviting = made else invitingOpen = false } }) { names, text ->
                    work({ acc.start(names).also { acc.send(it, text) } }) { cid, error ->
                        problem = error
                        Hub.changed()
                        if (cid != null) page = Page.Chat(cid)
                    }
                }
            }
            is Page.Info -> {
                fun groupWork(job: () -> Unit) = work(job) { _, error -> problem = error; Hub.changed() }
                Details(acc, p.cid, version, onBack = { page = Page.Chat(p.cid) }, onRemove = { name -> groupWork { acc.remove(p.cid, listOf(name)) } },
                    group = GroupActions(
                        rename = { name -> groupWork { acc.rename(p.cid, name) } },
                        picture = { groupPictureFor = p.cid; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        add = { names -> groupWork { acc.add(p.cid, names) } },
                        mute = { on -> groupWork { acc.mute(p.cid, on) } },
                        receipts = { on -> groupWork { acc.setReceiptsFor(p.cid, on) } },
                        block = { person, on -> groupWork { acc.block(person, on) } },
                        openAttachment = { info -> actions.open(info) },
                        openLink = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        contactPicture = { person -> contactPictureFor = person; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        resetContactPicture = { person -> groupWork { acc.setContactPicture(person, null) } },
                        leave = { groupWork { acc.leave(p.cid) }; page = Page.Chat(p.cid) },
                    ))
            }
            Page.Settings -> {
                SettingsScreen(acc, preview, unrestricted, onPreview = { preview = it; Settings.setPreview(context, it) },
                    lock = lockOn, lockAfter = lockAfter, lockPossible = Settings.lockPossible(context),
                    onLock = { on -> lockOn = on; Settings.setLock(context, on); (context as? MainActivity)?.applyOverview() },
                    onLockAfter = { seconds -> lockAfter = seconds; Settings.setLockAfter(context, seconds) },
                    onPicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onUnrestrict = { askBattery() }, onBack = { page = Page.List }, notifying = notifying,
                    onNotifications = { openNotificationSettings() }, receipts = receipts,
                    onReceipts = { on -> receipts = on; work({ acc.readReceipts = on }) { _, _ -> Hub.changed() } },
                    onName = { first, last -> work({ acc.setName(first, last) }) { _, error -> problem = error; Hub.changed() } },
                    typing = typingOn, onTyping = { on -> typingOn = on; work({ acc.typingIndicator = on }) { _, _ -> Hub.changed() } },
                    devices = devices, linking = linking,
                    onAddDevice = { code ->
                        linking = "Warte auf das neue Gerät …"
                        waitWork({ acc.acceptLink(code); acc.myDevices() }) { list, error -> linking = null; problem = error; list?.let { devices = it }; Hub.changed() }
                    },
                    onRemoveDevice = { id -> work({ acc.removeMyDevice(id); acc.myDevices() }) { list, error -> problem = error; list?.let { devices = it } } },
                    onScanDevice = { startScan("koppeln") })
                LaunchedEffect(Unit) { work({ acc.myDevices() }) { list, _ -> devices = list ?: emptyList() } }
            }
        } }
        if (sheet) AttachmentSheet(
            onPhotos = { sheet = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onFile = { sheet = false; pickFile.launch(arrayOf("*/*")) }, onCancel = { sheet = false })
        photo?.let { bytes -> BackHandler { photo = null }; PhotoViewer(bytes) { photo = null } }
        stagedOpen?.let { index -> staged.getOrNull(index)?.let { item ->
            if (markingUp && item.bytes != null) io.github.veritasx1.lichat.ui.MarkupEditor(item.bytes, onCancel = { markingUp = false }) { marked ->
                markingUp = false
                staged[index] = item.copy(bytes = marked, markedUp = true)
            }
            else io.github.veritasx1.lichat.ui.PhotoSendSheet(item.bytes, onCancel = { stagedOpen = null },
                onMarkup = { markingUp = true }, startOnce = item.once, startSecret = item.secret, confirm = "Fertig") { once, secret ->
                staged[index] = item.copy(once = once, secret = secret)
                stagedOpen = null
            }
        } ?: run { stagedOpen = null } }
        oncePhoto?.let { (cid, mid, bytes) ->
            io.github.veritasx1.lichat.ui.OnceViewer(bytes, onceSecret, onScreenshot = {
                account?.let { acc -> work({ acc.reportScreenshot(cid, mid) }) { _, error -> problem = error; Hub.changed() } }
            }) { oncePhoto = null }
        }
        if (scanning) io.github.veritasx1.lichat.ui.QrScanner { text ->
            scanning = false
            val acc = account
            if (text != null && acc != null && Account.parseContactUri(text) != null) {
                // a contact's code: into my contacts (verified by its fingerprint) and straight into the conversation
                work({ acc.chatWith(acc.addContactLink(text)) }) { cid, error ->
                    problem = error
                    Hub.changed()
                    if (cid != null) page = Page.Chat(cid)
                }
            } else if (text != null && acc != null) {
                if (Account.parseLinkUri(text) == null)
                    problem = if (scanFor == "kontakt") "Das ist kein LiChat-Kontakt." else "Das ist kein LiChat-Code zum Koppeln."
                else {
                    linking = "Warte auf das neue Gerät …"
                    waitWork({ acc.acceptLink(text); acc.myDevices() }) { list, error -> linking = null; problem = error; list?.let { devices = it }; Hub.changed() }
                }
            }
        }
        if (invitingOpen) io.github.veritasx1.lichat.ui.InviteSheet(inviting,
            onShare = { link ->
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "Komm zu LiChat: $link"), "Einladung teilen"))
            },
            onClose = { invitingOpen = false; inviting = null })
        myCode?.let { (link, pin) ->
            io.github.veritasx1.lichat.ui.ContactCodeSheet(link, account?.username ?: "", pin,
                onPin = { account?.let { acc -> work({ acc.newPin() }) { p, error -> problem = error; if (p != null) myCode = link to p } } },
                onClose = { myCode = null })
        }
        if (explaining) Explain(onContinue = {
            explaining = false
            Settings.setExplained(context)
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else askBattery()
        }, onLater = { explaining = false; Settings.setExplained(context) })
    }
}
