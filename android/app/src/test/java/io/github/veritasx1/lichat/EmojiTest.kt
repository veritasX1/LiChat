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
}
