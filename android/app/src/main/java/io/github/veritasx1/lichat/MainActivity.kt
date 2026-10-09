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

sealed class Page {
    data object List : Page()
    data class Chat(val cid: String) : Page()
    data object New : Page()
    data class Info(val cid: String) : Page()
    data object Settings : Page()
}

class MainActivity : ComponentActivity() {
    private val opened = mutableStateOf<String?>(null)

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
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<Page>(Page.List) }
    val seen = remember { mutableStateMapOf<String, Int>() }
    var preview by remember { mutableStateOf(Settings.preview(context)) }
    var unrestricted by remember { mutableStateOf(runCatching { Settings.unrestricted(context) }.getOrDefault(true)) }
    var explaining by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var recording by remember { mutableStateOf<Int?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val acc = account ?: return@rememberLauncherForActivityResult
        val group = groupPictureFor.also { groupPictureFor = null }
        if (uri != null) work({
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw LiChatException("Bild nicht lesbar")
            if (group != null) acc.setGroupPicture(group, Pictures.prepare(bytes)) else acc.setPicture(Pictures.prepare(bytes))
        }) { _, error -> problem = error; Hub.changed() }
    }

    fun chatId() = (page as? Page.Chat)?.cid
    fun sendFile(uri: Uri?, asFile: Boolean) {
        val acc = account ?: return
        val cid = chatId() ?: return
        if (uri == null) return
        work({ media.send(acc, cid, uri, asFile) }) { _, error -> problem = error; Hub.changed() }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { sendFile(it, false) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { sendFile(it, true) }
    fun startRecording() {
        if (media.startRecording()) {
            recording = 0
            main.postDelayed(object : Runnable {
                override fun run() { if (recording != null) { recording = media.recordedSeconds(); main.postDelayed(this, 250) } }
            }, 250)
        } else problem = "Aufnahme nicht möglich"
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else problem = "LiChat darf das Mikrofon nicht benutzen – in den Android-Einstellungen erlauben."
    }
    val actions = AttachmentActions(
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
                acc.sendAttachment(cid, voice.first, "Sprachnachricht.m4a", "audio/mp4", "sprache", voice.second)
            }) { _, error -> problem = error; Hub.changed() }
        },
        open = { info ->
            val acc = account
            if (acc != null) {
                if (info.optString("art") == "sprache" && playing == info.optString("id")) { media.stopPlaying(); playing = null }
                else work({ acc.openAttachment(info) }) { bytes, error ->
                    problem = error
                    if (bytes != null) when (info.optString("art")) {
                        "bild" -> photo = bytes
                        "sprache" -> { playing = info.optString("id"); media.play(bytes) { playing = null } }
                        else -> media.view(bytes, info)?.let { problem = it }
                    }
                }
            }
        },
        recording = recording,
        playing = playing,
        retry = { mid ->
            val acc = account
            val cid = chatId()
            if (acc != null && cid != null) work({ acc.retry(cid, mid) }) { _, error -> problem = error; Hub.changed() }
        },
    )
    var receipts by remember { mutableStateOf(account?.readReceipts ?: false) }

    CompositionLocalProvider(LocalPalette provides remember(dark) { Palette(dark) }) {
        val acc = account
        if (acc == null) {
            SetupScreen(busy, problem) { server, invite, name, first, last ->
                busy = true
                problem = null
                work({
                    Account.register(Hub.folder(context), server, invite, name, vault.create(Hub.folder(context)))
                        .also { if (first.isNotEmpty() || last.isNotEmpty()) it.setName(first, last) }
                }) { created, error ->
                    busy = false
                    problem = error
                    if (created != null) {
                        Hub.account = created
                        account = created
                        runCatching { LiChatService.start(context) }
                        if (!Settings.explained(context)) explaining = true
                    }
                }
            }
            return@CompositionLocalProvider
        }
        LaunchedEffect(Unit) { if (!Settings.explained(context)) explaining = true }
        when (val p = page) {
            Page.List -> ChatList(acc, version, seen, serverProblem, unrestricted,
                onOpen = { page = Page.Chat(it) }, onNew = { problem = null; page = Page.New }, onSettings = { page = Page.Settings },
                onUnrestrict = { askBattery() }, notifying = notifying, onNotifications = { openNotificationSettings() })
            is Page.Chat -> {
                BackHandler { page = Page.List }
                LaunchedEffect(version, p.cid) {
                    seen[p.cid] = synchronized(acc.lock) { acc.chat(p.cid).getJSONArray("messages").length() }
                    // On screen = read (only reported with read receipts on, card db70d358).
                    if (receipts) work({ acc.markRead(p.cid) }) { _, _ -> }
                }
                ChatScreen(acc, p.cid, version, problem, onBack = { page = Page.List }, onInfo = { page = Page.Info(p.cid) },
                    onSend = { text, replyTo -> work({ acc.send(p.cid, text, replyTo) }) { _, error -> problem = error; Hub.changed() } },
                    actions = actions,
                    onReact = { mid, emoji -> work({ acc.react(p.cid, mid, emoji) }) { _, error -> problem = error; Hub.changed() } })
            }
            Page.New -> {
                BackHandler { page = Page.List }
                NewMessage(problem, onCancel = { page = Page.List }) { names, text ->
                    work({ acc.start(names).also { acc.send(it, text) } }) { cid, error ->
                        problem = error
                        Hub.changed()
                        if (cid != null) page = Page.Chat(cid)
                    }
                }
            }
            is Page.Info -> {
                BackHandler { page = Page.Chat(p.cid) }
                fun groupWork(job: () -> Unit) = work(job) { _, error -> problem = error; Hub.changed() }
                Details(acc, p.cid, version, onBack = { page = Page.Chat(p.cid) }, onRemove = { name -> groupWork { acc.remove(p.cid, listOf(name)) } },
                    group = GroupActions(
                        rename = { name -> groupWork { acc.rename(p.cid, name) } },
                        picture = { groupPictureFor = p.cid; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        add = { names -> groupWork { acc.add(p.cid, names) } },
                        mute = { on -> groupWork { acc.mute(p.cid, on) } },
                        leave = { groupWork { acc.leave(p.cid) }; page = Page.Chat(p.cid) },
                    ))
            }
            Page.Settings -> {
                BackHandler { page = Page.List }
                SettingsScreen(acc, preview, unrestricted, onPreview = { preview = it; Settings.setPreview(context, it) },
                    onPicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onUnrestrict = { askBattery() }, onBack = { page = Page.List }, notifying = notifying,
                    onNotifications = { openNotificationSettings() }, receipts = receipts,
                    onReceipts = { on -> receipts = on; work({ acc.readReceipts = on }) { _, _ -> Hub.changed() } },
                    onName = { first, last -> work({ acc.setName(first, last) }) { _, error -> problem = error; Hub.changed() } })
            }
        }
        if (sheet) AttachmentSheet(
            onPhotos = { sheet = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onFile = { sheet = false; pickFile.launch(arrayOf("*/*")) }, onCancel = { sheet = false })
        photo?.let { bytes -> BackHandler { photo = null }; PhotoViewer(bytes) { photo = null } }
        if (explaining) Explain(onContinue = {
            explaining = false
            Settings.setExplained(context)
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else askBattery()
        }, onLater = { explaining = false; Settings.setExplained(context) })
    }
}
