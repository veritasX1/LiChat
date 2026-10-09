package io.github.veritasx1.lichat

import io.github.veritasx1.lichat.core.Gif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Michelle 999f37be: an animated GIF stays animated – frames and loop kept, comment and XMP gone (made by PIL). */
class GifTest {
    @Test
    fun animationStaysMetadataGoes() {
        val dir = Files.createTempDirectory("gif").toFile()
        val gif = File(dir, "tanz.gif")
        val script = """
from PIL import Image
frames = [Image.new("RGB", (20, 20), c) for c in ((255, 0, 0), (0, 255, 0), (0, 0, 255))]
frames[0].save("${gif.absolutePath}", "GIF", save_all=True, append_images=frames[1:], loop=0, duration=100, comment=b"Ort: Bremen")
raw = open("${gif.absolutePath}", "rb").read()
payload = b"<x:xmpmeta Bremen"
open("${gif.absolutePath}", "wb").write(raw[:-1] + b"\x21\xff\x0bXMP DataXMP" + bytes([len(payload)]) + payload + b"\x00\x3b")
"""
        assertEquals(0, ProcessBuilder("python3", "-c", script).inheritIO().start().waitFor())
        val raw = gif.readBytes()
        assertTrue(String(raw, Charsets.ISO_8859_1).contains("Bremen"))
        val clean = Gif.withoutMetadata(raw)
        val text = String(clean, Charsets.ISO_8859_1)
        assertFalse(text.contains("Bremen"))
        assertTrue(text.contains("NETSCAPE2.0"))
        gif.writeBytes(clean)
        val check = ProcessBuilder("python3", "-c", "from PIL import Image; i = Image.open('${gif.absolutePath}'); print(i.n_frames, i.info.get('loop'))")
            .redirectErrorStream(true).start()
        assertEquals("3 0", check.inputStream.bufferedReader().readText().trim())
        dir.deleteRecursively()
    }
}
