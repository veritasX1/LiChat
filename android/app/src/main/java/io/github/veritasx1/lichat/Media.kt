package io.github.veritasx1.lichat

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import io.github.veritasx1.lichat.core.Account
import io.github.veritasx1.lichat.core.LiChatException
import io.github.veritasx1.lichat.core.Pictures
import org.json.JSONObject
import java.io.File

/** Attachments on the phone (card 9780f3a0): what is picked, recorded, played and handed to other apps. Plain bytes only
 *  in LiChat's own cache, never in shared storage – and gone with the next start (MainActivity). */
class Media(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var started = 0L
    private var player: MediaPlayer? = null
    private val recordFile get() = File(context.cacheDir, "aufnahme.m4a")

    /** A picked photo, video or file → prepared and sent. Photos without EXIF and smaller; [asFile]: unchanged as a file. */
    fun send(account: Account, cid: String, uri: Uri, asFile: Boolean) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Datei"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw LiChatException("Datei nicht lesbar")
        when {
            !asFile && mime.startsWith("image/") -> {
                val (jpeg, preview) = Pictures.photo(bytes)
                account.sendAttachment(cid, jpeg, name.substringBeforeLast('.') + ".jpg", "image/jpeg", "bild", preview = preview)
            }
            !asFile && mime.startsWith("video/") -> {
                val temp = File(context.cacheDir, "video-vorschau").apply { writeBytes(bytes) }
                val preview = try { Pictures.videoPreview(temp) } finally { temp.delete() }
                account.sendAttachment(cid, bytes, name, mime, "video", preview = preview)
            }
            else -> account.sendAttachment(cid, bytes, name, mime, if (mime.startsWith("audio/")) "sprache" else "datei")
        }
    }

    fun startRecording(): Boolean = runCatching {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(64_000)
        r.setOutputFile(recordFile.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        started = SystemClock.elapsedRealtime()
    }.isSuccess

    fun recordedSeconds() = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()

    /** Ends the recording: (bytes, seconds) to send, or null when discarded or too short. */
    fun stopRecording(send: Boolean): Pair<ByteArray, Double>? {
        val r = recorder ?: return null
        recorder = null
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val file = recordFile
        return try { if (send && ok && seconds >= 0.5) file.readBytes() to seconds else null } finally { file.delete() }
    }

    fun play(bytes: ByteArray, done: () -> Unit) {
        stopPlaying()
        val file = File(File(context.cacheDir, "geoeffnet").apply { mkdirs() }, "wiedergabe.m4a").apply { writeBytes(bytes) }
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopPlaying(); done() }
            prepare()
            start()
        }
    }

    fun stopPlaying() {
        player?.run { runCatching { stop() }; release() }
        player = null
        File(File(context.cacheDir, "geoeffnet"), "wiedergabe.m4a").delete()
    }

    /** A video or a file opened in the app that can show it; the plain copy only in LiChat's cache. Null, or a problem. */
    fun view(bytes: ByteArray, info: JSONObject): String? {
        val folder = File(context.cacheDir, "geoeffnet").apply { mkdirs() }
        val file = File(folder, info.optString("name", "anhang").replace('/', '_').ifBlank { "anhang" })
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".dateien", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.optString("mime", "application/octet-stream"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(Intent.createChooser(intent, info.optString("name")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
            .getOrElse { "Keine App zum Öffnen gefunden" }
    }
}
