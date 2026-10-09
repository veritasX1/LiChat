package io.github.veritasx1.lichat

import io.github.veritasx1.lichat.core.isSingleEmoji
import io.github.veritasx1.lichat.ui.firstEmoji
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Card a7d9d987 „+“: the first emoji typed becomes the reaction – as a whole grapheme, letters do not count. */
class EmojiTest {
    @Test
    fun firstWholeEmoji() {
        assertEquals("🦄", firstEmoji("🦄"))
        assertEquals("👍🏽", firstEmoji("👍🏽"))
        assertEquals("👩‍👩‍👧", firstEmoji("👩‍👩‍👧"))
        assertEquals("🇫🇷", firstEmoji("ab 🇫🇷 c"))
        assertEquals("1️⃣", firstEmoji("1️⃣"))
        assertEquals("❤️", firstEmoji("❤️ x"))
        assertNull(firstEmoji("hallo 42"))
        assertNull(firstEmoji(""))
    }

    /** Michelle 09.10.: only exactly one emoji is a reaction – „Gelesen“, „ab“, „x😀“ are not. */
    @Test
    fun onlyOneEmojiIsAReaction() {
        for (good in listOf("❤️", "👍", "👍🏽", "👩‍👩‍👧‍👦", "🇫🇷", "1️⃣", "‼️", "❓", "🦄", "✅")) assertTrue(good, isSingleEmoji(good))
        for (bad in listOf("", "ab", "x😀", "😀x", "😀😀", "Gelesen von allen", "OK", "1", "🇫", " ", "❤️ ")) assertFalse(bad, isSingleEmoji(bad))
    }

    /** Olaf „per QR-Code koppeln“: the link in the new device's QR code, read back; and ZXing can read what it drew. */
    @Test
    fun linkUriAndQr() {
        val uri = io.github.veritasx1.lichat.core.Account.linkUri("https://exampleserver.lisoftware.de", "AB2C-DE3F")
        assertEquals("https://exampleserver.lisoftware.de" to "AB2C-DE3F", io.github.veritasx1.lichat.core.Account.parseLinkUri(uri))
        assertEquals(null, io.github.veritasx1.lichat.core.Account.parseLinkUri("https://example.org"))
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(uri, com.google.zxing.BarcodeFormat.QR_CODE, 200, 200)
        val pixels = IntArray(200 * 200) { if (matrix.get(it % 200, it / 200)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val source = com.google.zxing.RGBLuminanceSource(200, 200, pixels)
        assertEquals(uri, com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))).text)
    }
}
