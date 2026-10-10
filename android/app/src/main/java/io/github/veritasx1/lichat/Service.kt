package io.github.veritasx1.lichat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.veritasx1.lichat.core.Account
import io.github.veritasx1.lichat.core.KeyVault
import io.github.veritasx1.lichat.core.LiChatException
import io.github.veritasx1.lichat.core.News
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/*
 * Instant messages without a push service (Olaf 09.10.: „wie bei einem echten Messenger“): a foreground service keeps
 * one waiting fetch open at our own server, like Signal without Google. Android demands a lasting notification for
 * that – it sits in its own silent channel „Verbindung“ that can be hidden (Michelle Z5 d). Started by the app, after a
 * restart of the phone (BootReceiver) and again by Android when the process was killed (START_STICKY, Z5 e).
 */

/** What the service and the screens share: the account and a counter that moves with every change. */
object Hub {
    @Volatile var account: Account? = null
        set(value) { value?.onChange = ::changed; field = value }   // uploads redraw their bubble (cf647f61)
    val changes = MutableStateFlow(0)
    val problem = MutableStateFlow<String?>(null)
    val connected = MutableStateFlow(false)
    /** The chat on screen right now – no notification for it (Messages does the same). */
    @Volatile var openChat: String? = null
    @Volatile var visible = false

    fun folder(context: Context) = File(context.filesDir, "konto")

    fun open(context: Context, vault: KeyVault = KeyVault.Keystore): Account? {
        account?.let { return it }
        val folder = folder(context)
        if (!File(folder, "account.json").exists()) return null
        val key = vault.load(folder) ?: return null
        return Account(folder, key).also { account = it }
    }

    fun changed() {
        changes.value = changes.value + 1
    }
}

object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)
    /** Sender and text in notifications – off by default, then only „Neue Nachricht“ (Michelle S4). */
    fun preview(context: Context) = prefs(context).getBoolean("vorschau", false)
    fun setPreview(context: Context, on: Boolean) = prefs(context).edit().putBoolean("vorschau", on).apply()
    fun explained(context: Context) = prefs(context).getBoolean("erklaert", false)
    fun setExplained(context: Context) = prefs(context).edit().putBoolean("erklaert", true).apply()

    /** Notifications allowed – the app's switch and the channel „Nachrichten“ (Michelle 24e8f89c: say it when not). */
    fun notifying(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(Notifications.MESSAGES)?.importance ?: NotificationManager.IMPORTANCE_DEFAULT) != NotificationManager.IMPORTANCE_NONE
    }

    /** App lock (99eea3f7, Olaf: can be switched on, off by default): unlocked with the phone's own screen lock. */
    fun lock(context: Context) = prefs(context).getBoolean("sperre", false)
    fun setLock(context: Context, on: Boolean) = prefs(context).edit().putBoolean("sperre", on).apply()
    /** Seconds in the background before it locks again: 0, 60, 900 or 3600. */
    fun lockAfter(context: Context) = prefs(context).getInt("sperre_nach", 0)
    fun setLockAfter(context: Context, seconds: Int) = prefs(context).edit().putInt("sperre_nach", seconds).apply()
    /** A lock needs a screen lock on the phone (PIN, pattern, password) – else there is nothing to unlock with. */
    fun lockPossible(context: Context) = context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true

    /** Without this, Android stops the connection in Doze and messages come late (Michelle Z6: said honestly). */
    fun unrestricted(context: Context) =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
}

object Notifications {
    const val MESSAGES = "nachrichten"
    const val CONNECTION = "verbindung"
    const val SERVICE_ID = 1

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MESSAGES, "Nachrichten", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Neue Nachrichten in deinen Gesprächen"
        })
        manager.createNotificationChannel(NotificationChannel(CONNECTION, "Verbindung", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Zeigt, dass LiChat verbunden ist. Darf ausgeblendet werden."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    /** „LiChat“ or „LiChat Test“ – the app's own name (Michelle: both said „LiChat ist verbunden“). */
    fun appName(context: Context) = context.applicationInfo.loadLabel(context.packageManager).toString()

    fun connection(context: Context, connected: Boolean): Notification =
        Notification.Builder(context, CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_lichat)
            .setContentTitle(if (connected) "${appName(context)} ist verbunden" else "${appName(context)} verbindet …")
            .setOngoing(true)
            .setContentIntent(open(context, null))
            .build()

    /** The lines of each chat's notification since it was last read (MessagingStyle shows them like Messages). */
    private val lines = mutableMapOf<String, MutableList<Triple<String, String, Long>>>()

    /** One notification per conversation (Olaf 2e35e6a3): sender (or group) as the title – no „LiChat“ twice – and gone
     *  as soon as the conversation is read here or on another of my devices. */
    fun message(context: Context, news: News, who: String = news.from, group: String? = null) {
        val chat = news.chat ?: return
        val preview = Settings.preview(context) && !Settings.lock(context)     // locked app: notifications without content
        val text = if (preview) (if (news.mention) "hat dich erwähnt: ${news.text}" else news.text) else if (news.mention) "Neue Erwähnung" else "Neue Nachricht"
        val list = synchronized(lines) { lines.getOrPut(chat) { mutableListOf() }.also { it.add(Triple(if (preview) who else "", text, System.currentTimeMillis())); while (it.size > 6) it.removeAt(0) }.toList() }
        val builder = Notification.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_lichat)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open(context, chat))
        if (preview) {
            val style = Notification.MessagingStyle(android.app.Person.Builder().setName("Ich").build())
            if (group != null) { style.conversationTitle = group; style.isGroupConversation = true }
            list.forEach { (name, line, at) -> style.addMessage(line, at, android.app.Person.Builder().setName(name).build()) }
            builder.setStyle(style).setContentTitle(group ?: who).setContentText(text)
        } else {
            // Without preview only „Neue Nachricht“ (Michelle S4) – the title says nothing either.
            builder.setContentTitle(appName(context)).setContentText(if (list.size > 1) "${list.size} neue Nachrichten" else text)
        }
        context.getSystemService(NotificationManager::class.java).notify(chat.hashCode(), builder.build())
    }

    /** The conversation was read (here or on another of my devices): its notification goes. */
    fun cancel(context: Context, chat: String) {
        synchronized(lines) { lines.remove(chat) }
        context.getSystemService(NotificationManager::class.java).cancel(chat.hashCode())
    }

    private fun open(context: Context, chat: String?): PendingIntent =
        PendingIntent.getActivity(context, chat?.hashCode() ?: 0,
            Intent(context, MainActivity::class.java).putExtra("gespraech", chat).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

class LiChatService : Service() {
    @Volatile private var running = false
    /** Wakes the pause after an error at once when a network comes (Michelle 79b25b24: 21,8 s after flight mode). */
    private val wake = Object()
    @Volatile private var networkCame = false
    private var connectedSince = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val WAIT_S = 50

        fun start(context: Context) {
            if (Hub.open(context) == null) return
            context.startForegroundService(Intent(context, LiChatService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.channels(this)
        val notification = Notifications.connection(this, Hub.connected.value)
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(Notifications.SERVICE_ID, notification)
        if (!running) {
            running = true
            watchNetwork()
            Thread(::listen, "LiChat-Verbindung").start()
        }
        return START_STICKY
    }

    /** A new network (Wi-Fi ↔ mobile): end the waiting fetch at once – the next one goes over the new way. */
    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java)
        var first = true
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (first) { first = false; return }
                // A new way out: end the waiting fetch on the old one, and end any pause – connect again right now.
                Hub.account?.server?.interrupt()
                synchronized(wake) { networkCame = true; wake.notifyAll() }
            }

            override fun onLost(network: Network) {
                // The way is gone (flight mode): do not wait for the old connection's timeout.
                Hub.account?.server?.interrupt()
            }
        }.also { manager.registerDefaultNetworkCallback(it) }
    }

    private fun listen() {
        var pause = 2_000L
        while (running) {
            val account = Hub.open(this) ?: break
            try {
                val news = account.fetch(WAIT_S)
                if (!Hub.connected.value) {
                    connectedSince = System.currentTimeMillis()
                    setConnected(true)
                    // What failed while the server was out of reach goes now – beside the fetch, never holding it up (72c8a716).
                    Thread({ if (runCatching { account.resendFailed() }.getOrDefault(0) > 0) Hub.changed() }, "LiChat-Nachsenden").start()
                }
                Hub.problem.value = null
                pause = 2_000L
                if (news.isNotEmpty()) handle(account, news)
            } catch (e: Exception) {
                // Whatever goes wrong here – a server restart, no network, a broken answer – the connection waits and
                // tries again; it never takes the app down (Michelle 09.10.: „wird wiederholt beendet“).
                setConnected(false)
                Hub.problem.value = (e as? LiChatException)?.message ?: "Verbindung unterbrochen – neuer Versuch"
                // The raw cause (address, port) only in the test build's log, never on the screen (8e7a493b).
                if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.w("LiChat", "Verbindung: ${(e as? LiChatException)?.detail ?: e}")
                synchronized(wake) {
                    if (!networkCame) wake.wait(pause)
                    if (networkCame) { networkCame = false; pause = 1_000L } else pause = minOf(pause * 2, 60_000L)
                }
            }
        }
    }

    private fun setConnected(on: Boolean) {
        if (Hub.connected.value == on) return
        Hub.connected.value = on
        getSystemService(NotificationManager::class.java).notify(Notifications.SERVICE_ID, Notifications.connection(this, on))
    }

    private fun handle(account: Account, news: List<News>) {
        Hub.changed()
        // Read on another of my devices (its read marker came through my devices' group): the notification goes here too.
        if (news.any { it.kind == News.Kind.Settings || it.kind == News.Kind.Synced })
            for (c in account.visibleChats()) if (account.unread(c) == 0) Notifications.cancel(this, c)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        for (item in news) {
            if (item.kind != News.Kind.Text || item.from == account.username) continue
            if (Hub.visible && Hub.openChat == item.chat) continue
            if (item.chat != null && account.muted(item.chat) && !item.mention) continue   // muted – but a mention comes through (d54175ed)
            val shown = Settings.notifying(this)
            if (shown) Notifications.message(this, item, account.display(item.from),
                item.chat?.let { c -> if (account.others(c).size > 1 || account.chat(c).optString("title").isNotEmpty()) account.title(c) else null })
            if (debuggable) {
                // Michelle Z5 a: one line per message, without content and names – only to measure the way. When Android
                // does not let LiChat notify, it says so instead of a time (24e8f89c).
                Log.i("LiChatZ5", "hash=${(item.chat + item.at + item.text.length).hashCode().toUInt().toString(16)} " +
                    "server_ms=${(item.received * 1000).toLong()} " + (if (shown) "gezeigt_ms=${System.currentTimeMillis()}" else "gezeigt=nein") +
                    " verbunden_seit=$connectedSince")
            }
        }
    }

    override fun onDestroy() {
        running = false
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        Hub.account?.server?.interrupt()
        Hub.connected.value = false
        super.onDestroy()
    }
}

/** After a restart of the phone (and after an update of the app) the connection comes back by itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { LiChatService.start(context) }
        }
    }
}
