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
    fun send(account: Account, cid: String, uri: Uri, asFile: Boolean, once: Boolean = false, secret: Boolean = false, caption: String = "") {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Datei"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw LiChatException("Datei nicht lesbar")
        when {
            mime == "image/gif" || io.github.veritasx1.lichat.core.Gif.isGif(bytes) -> {
                // A GIF stays a GIF – animated, looping (Michelle 999f37be): only comments, XMP and other extra blocks go.
                val gif = io.github.veritasx1.lichat.core.Gif.withoutMetadata(bytes)
                account.sendAttachment(cid, gif, name.substringBeforeLast('.') + ".gif", "image/gif", "bild", preview = Pictures.photo(gif).second, once = once, secret = secret, caption = caption)
            }
            !asFile && mime.startsWith("image/") -> {
                val (jpeg, preview) = Pictures.photo(bytes)
                account.sendAttachment(cid, jpeg, name.substringBeforeLast('.') + ".jpg", "image/jpeg", "bild", preview = preview, once = once, secret = secret, caption = caption)
            }
            !asFile && mime.startsWith("video/") -> {
                // Without the container's metadata (Michelle: an MP4 carries the place ©xyz and the recording time).
                val clean = clean(bytes, "mp4") ?: throw LiChatException("Video lässt sich nicht ohne Ortsangaben verschicken")
                val temp = File(context.cacheDir, "video-vorschau").apply { writeBytes(clean) }
                val preview = try { Pictures.videoPreview(temp) } finally { temp.delete() }
                account.sendAttachment(cid, clean, name.substringBeforeLast('.') + ".mp4", "video/mp4", "video", preview = preview)
            }
            !asFile && mime.startsWith("audio/") -> {
                val clean = clean(bytes, "m4a") ?: throw LiChatException("Aufnahme lässt sich nicht ohne Metadaten verschicken")
                account.sendAttachment(cid, clean, name.substringBeforeLast('.') + ".m4a", "audio/mp4", "sprache")
            }
            // Sent as a file the content stays as it is – but never its metadata (Michelle: an MP4 via „Datei“ kept its place).
            asFile && mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml" -> {
                // Full size, only without EXIF – and shown as a picture with its preview, like on the iPhone (Michelle).
                val full = Pictures.withoutMetadata(bytes)
                account.sendAttachment(cid, full, name.substringBeforeLast('.') + ".jpg", "image/jpeg", "bild", preview = Pictures.photo(full).second)
            }
            asFile && mime.startsWith("video/") -> account.sendAttachment(cid,
                clean(bytes, "mp4") ?: throw LiChatException("Video lässt sich nicht ohne Ortsangaben verschicken"),
                name.substringBeforeLast('.') + ".mp4", "video/mp4", "datei")
            asFile && mime.startsWith("audio/") -> account.sendAttachment(cid,
                clean(bytes, "m4a") ?: throw LiChatException("Aufnahme lässt sich nicht ohne Metadaten verschicken"),
                name.substringBeforeLast('.') + ".m4a", "audio/mp4", "datei")
            else -> account.sendAttachment(cid, bytes, name, mime, "datei")
        }
    }

    /** Copies the audio and video tracks into a fresh MP4 – samples unchanged, nothing else: no place, no user data,
     *  no camera; only the rotation is kept so the video stands upright. Null when it cannot be read. */
    fun clean(bytes: ByteArray, suffix: String): ByteArray? {
        val input = File(context.cacheDir, "bereinigen-ein.$suffix").apply { writeBytes(bytes) }
        val output = File(context.cacheDir, "bereinigen-aus.$suffix")
        return try {
            val extractor = android.media.MediaExtractor().apply { setDataSource(input.absolutePath) }
            val muxer = android.media.MediaMuxer(output.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val tracks = mutableMapOf<Int, Int>()
            var buffer = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val type = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (!type.startsWith("video/") && !type.startsWith("audio/")) continue
                if (type.startsWith("video/") && format.containsKey(android.media.MediaFormat.KEY_ROTATION))
                    muxer.setOrientationHint(format.getInteger(android.media.MediaFormat.KEY_ROTATION))
                if (format.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE))
                    buffer = maxOf(buffer, format.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE))
                tracks[i] = muxer.addTrack(format)
            }
            if (tracks.isEmpty()) return null
            muxer.start()
            val data = java.nio.ByteBuffer.allocate(buffer)
            val info = android.media.MediaCodec.BufferInfo()
            for ((source, target) in tracks) {
                extractor.selectTrack(source)
                while (true) {
                    val size = extractor.readSampleData(data, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(target, data, info)
                    extractor.advance()
                }
                extractor.unselectTrack(source)
                extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            muxer.stop(); muxer.release(); extractor.release()
            // MediaMuxer adds the phone's maker, model and Android version itself (Michelle) – those go too.
            io.github.veritasx1.lichat.core.Mp4.withoutMetadata(output.readBytes())
        } catch (e: Exception) {
            null
        } finally {
            input.delete(); output.delete()
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
            setOnCompletionListener { stopPlaying(); done() }      // at the end back to the start (Messages)
            prepare()
            start()
        }
    }

    // Voice messages like Messages (Olaf 864041bf): pause keeps the place, play goes on from there, a swipe jumps.
    fun pause() = runCatching { player?.pause() }
    fun resume() = runCatching { player?.start() }
    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    /** (position, duration) in ms of what is loaded, or null. */
    fun progress(): Pair<Int, Int>? = runCatching { player?.let { it.currentPosition to it.duration } }.getOrNull()
    fun seek(fraction: Float) = runCatching { player?.let { it.seekTo((it.duration * fraction.coerceIn(0f, 1f)).toInt()) } }

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
