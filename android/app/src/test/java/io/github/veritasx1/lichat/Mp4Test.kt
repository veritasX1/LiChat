package io.github.veritasx1.lichat

import io.github.veritasx1.lichat.core.Mp4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Michelle 9780f3a0: no maker, model, Android version or place left in a video – and it still plays (ffprobe). */
class Mp4Test {
    private val folder: File = Files.createTempDirectory("lichat-mp4").toFile()

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertEquals(out, 0, process.waitFor())
        return out
    }

    private fun video(name: String, faststart: Boolean): File {
        val file = File(folder, name)
        run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-f", "lavfi",
            "-i", "sine=frequency=440:duration=2", "-shortest", "-c:v", "libx264", "-c:a", "aac",
            "-metadata", "com.android.manufacturer=motorola", "-metadata", "com.android.model=moto g84 5G",
            "-metadata", "location=+52.5200+013.4050/", "-movflags", "+use_metadata_tags" + (if (faststart) "+faststart" else ""),
            file.absolutePath)
        return file
    }

    private fun check(faststart: Boolean) {
        val source = video("ein-$faststart.mp4", faststart)
        val raw = source.readBytes()
        assertTrue(String(raw, Charsets.ISO_8859_1).contains("motorola"))
        val clean = Mp4.withoutMetadata(raw)
        val text = String(clean, Charsets.ISO_8859_1)
        for (secret in listOf("motorola", "moto g84", "+52.5200", "com.android")) assertFalse(secret, text.contains(secret))
        val out = File(folder, "aus-$faststart.mp4").apply { writeBytes(clean) }
        val tags = run("ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "default=nw=1", out.absolutePath)
        assertFalse(tags, tags.contains("motorola") || tags.contains("location"))
        // Still the same samples: every frame decodes.
        val frames = run("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames",
            "-of", "csv=p=0", out.absolutePath).trim()
        assertEquals("20", frames)
        val decoded = run("ffmpeg", "-v", "error", "-i", out.absolutePath, "-f", "null", "-")
        assertEquals("", decoded.trim())
    }

    @Test
    fun moovAfterMdat() = check(faststart = false)

    @Test
    fun moovBeforeMdatShiftsOffsets() = check(faststart = true)
}
