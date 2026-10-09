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
import io.github.veritasx1.lichat.ui.ChatList
import io.github.veritasx1.lichat.ui.ChatScreen
import io.github.veritasx1.lichat.ui.Details
import io.github.veritasx1.lichat.ui.Explain
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

    LaunchedEffect(opened.value) { opened.value?.let { cid -> if (account?.chats?.has(cid) == true) page = Page.Chat(cid); opened.value = null } }
    Hub.openChat = (page as? Page.Chat)?.cid

    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = runCatching { Settings.unrestricted(context) }.getOrDefault(true)
    }
    fun askBattery() {
        runCatching { battery.launch(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askBattery() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val acc = account ?: return@rememberLauncherForActivityResult
        if (uri != null) work({
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw LiChatException("Bild nicht lesbar")
            acc.setPicture(Pictures.prepare(bytes))
        }) { _, error -> problem = error; Hub.changed() }
    }

    CompositionLocalProvider(LocalPalette provides remember(dark) { Palette(dark) }) {
        val acc = account
        if (acc == null) {
            SetupScreen(busy, problem) { server, invite, name ->
                busy = true
                problem = null
                work({ Account.register(Hub.folder(context), server, invite, name, vault.create(Hub.folder(context))) }) { created, error ->
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
                onUnrestrict = { askBattery() })
            is Page.Chat -> {
                BackHandler { page = Page.List }
                LaunchedEffect(version, p.cid) { seen[p.cid] = synchronized(acc.lock) { acc.chat(p.cid).getJSONArray("messages").length() } }
                ChatScreen(acc, p.cid, version, problem, onBack = { page = Page.List }, onInfo = { page = Page.Info(p.cid) }) { text ->
                    work({ acc.send(p.cid, text) }) { _, error -> problem = error; Hub.changed() }
                }
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
                Details(acc, p.cid, version, onBack = { page = Page.Chat(p.cid) }) { name ->
                    work({ acc.remove(p.cid, listOf(name)) }) { _, error -> problem = error; Hub.changed() }
                }
            }
            Page.Settings -> {
                BackHandler { page = Page.List }
                SettingsScreen(acc, preview, unrestricted, onPreview = { preview = it; Settings.setPreview(context, it) },
                    onPicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onUnrestrict = { askBattery() }, onBack = { page = Page.List })
            }
        }
        if (explaining) Explain(onContinue = {
            explaining = false
            Settings.setExplained(context)
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else askBattery()
        }, onLater = { explaining = false; Settings.setExplained(context) })
    }
}
