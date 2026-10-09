package io.github.veritasx1.lichat.core

/** A GIF block by block (Michelle 999f37be): frames, colour tables, timing (graphic control) and the loop
 *  (NETSCAPE2.0 / ANIMEXTS1.0) stay; comments, XMP and every other application or text block go. Twin of Python's
 *  gif_without_metadata. */
object Gif {
    fun isGif(data: ByteArray) = data.size >= 6 && String(data, 0, 6, Charsets.ISO_8859_1) in setOf("GIF87a", "GIF89a")

    fun withoutMetadata(data: ByteArray): ByteArray {
        if (!isGif(data) || data.size < 13) throw LiChatException("Kein GIF")
        val out = java.io.ByteArrayOutputStream()
        out.write(data, 0, 13)
        val flags = data[10].toInt() and 0xFF
        var pos = 13
        if (flags and 0x80 != 0) {
            val size = 3 * (1 shl ((flags and 7) + 1))
            out.write(data, pos, size); pos += size
        }
        fun subBlocks(start: Int): Int {
            var at = start
            while (true) {
                if (at >= data.size) throw LiChatException("GIF unvollständig")
                val length = data[at].toInt() and 0xFF
                at += 1 + length
                if (length == 0) return at
            }
        }
        while (pos < data.size) {
            when (data[pos].toInt() and 0xFF) {
                0x3B -> { out.write(0x3B); return out.toByteArray() }
                0x2C -> {
                    val start = pos
                    val local = data[pos + 9].toInt() and 0xFF
                    pos += 10
                    if (local and 0x80 != 0) pos += 3 * (1 shl ((local and 7) + 1))
                    pos = subBlocks(pos + 1)
                    out.write(data, start, pos - start)
                }
                0x21 -> {
                    val label = data[pos + 1].toInt() and 0xFF
                    val end = subBlocks(pos + 2)
                    val loop = label == 0xFF && data[pos + 2].toInt() == 11 &&
                        String(data, pos + 3, 11, Charsets.ISO_8859_1) in setOf("NETSCAPE2.0", "ANIMEXTS1.0")
                    if (label == 0xF9 || loop) out.write(data, pos, end - pos)
                    pos = end
                }
                else -> throw LiChatException("GIF beschädigt")
            }
        }
        out.write(0x3B)
        return out.toByteArray()
    }
}
