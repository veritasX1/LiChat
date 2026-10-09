package io.github.veritasx1.lichat.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** MP4 without metadata (Michelle 9780f3a0): MediaMuxer writes the phone's maker, model and Android version into
 *  moov/udta and moov/meta by itself. Every „udta“ and „meta“ box inside moov and its tracks is dropped; when moov lies
 *  before mdat, the chunk offsets (stco/co64) move by what was cut. Samples, timing and rotation stay as they are. */
object Mp4 {
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")
    private val DROP = setOf("udta", "meta")

    private class Box(val type: String, val start: Int, val size: Int, val header: Int)

    private fun boxes(data: ByteArray, from: Int, to: Int): List<Box> {
        val list = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            val buffer = ByteBuffer.wrap(data, at, to - at)
            var size = buffer.int.toLong() and 0xffffffffL
            val type = String(data, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = ByteBuffer.wrap(data, at + 8, 8).long; header = 16 }
            if (size == 0L) size = (to - at).toLong()
            if (size < header || at + size > to) throw IllegalArgumentException("MP4 kaputt bei $type")
            list.add(Box(type, at, size.toInt(), header))
            at += size.toInt()
        }
        return list
    }

    fun withoutMetadata(data: ByteArray): ByteArray {
        val top = boxes(data, 0, data.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return data
        val mdat = top.firstOrNull { it.type == "mdat" }
        val cleaned = rewrite(data, moov)
        val cut = moov.size - cleaned.size
        // moov before mdat: the samples move up by what was cut – the chunk offsets must follow.
        if (cut != 0 && mdat != null && moov.start < mdat.start) shiftOffsets(cleaned, -cut.toLong())
        val out = ByteArrayOutputStream(data.size)
        for (box in top) if (box === moov) out.write(cleaned) else out.write(data, box.start, box.size)
        return out.toByteArray()
    }

    /** A container box without its udta/meta children (recursively); other boxes are copied byte for byte. */
    private fun rewrite(data: ByteArray, box: Box): ByteArray {
        if (box.type !in CONTAINERS) return data.copyOfRange(box.start, box.start + box.size)
        val body = ByteArrayOutputStream()
        for (child in boxes(data, box.start + box.header, box.start + box.size)) {
            if (child.type in DROP) continue
            body.write(rewrite(data, child))
        }
        val content = body.toByteArray()
        val out = ByteBuffer.allocate(8 + content.size)
        out.putInt(8 + content.size).put(box.type.toByteArray(Charsets.ISO_8859_1)).put(content)
        return out.array()
    }

    private fun shiftOffsets(moov: ByteArray, delta: Long) {
        fun walk(from: Int, to: Int) {
            for (box in boxes(moov, from, to)) {
                when (box.type) {
                    in CONTAINERS -> walk(box.start + box.header, box.start + box.size)
                    "stco" -> {
                        val b = ByteBuffer.wrap(moov)
                        val count = b.getInt(box.start + box.header + 4)
                        for (i in 0 until count) {
                            val at = box.start + box.header + 8 + i * 4
                            b.putInt(at, ((b.getInt(at).toLong() and 0xffffffffL) + delta).toInt())
                        }
                    }
                    "co64" -> {
                        val b = ByteBuffer.wrap(moov)
                        val count = b.getInt(box.start + box.header + 4)
                        for (i in 0 until count) {
                            val at = box.start + box.header + 8 + i * 8
                            b.putLong(at, b.getLong(at) + delta)
                        }
                    }
                }
            }
        }
        walk(8, moov.size)
    }
}
