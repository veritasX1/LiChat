package io.github.veritasx1.lichat.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** A profile picture as it may leave the phone (Michelle B7): square from the middle, 512 px, JPEG – and nothing else
 *  from the file. A Bitmap carries no EXIF, so place, camera and time stay behind. The same as account.py. */
object Pictures {
    const val PX = 512
    const val PHOTO_PX = 2048

    private fun upright(source: ByteArray, longest: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0) throw LiChatException("Kein Bild")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw LiChatException("Kein Bild")
        val rotation = runCatching { ExifInterface(ByteArrayInputStream(source)).rotationDegrees }.getOrDefault(0)
        return if (rotation == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    private fun fit(bitmap: Bitmap, longest: Int): Bitmap {
        val scale = minOf(1f, longest.toFloat() / maxOf(bitmap.width, bitmap.height))
        return if (scale >= 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun jpeg(bitmap: Bitmap, quality: Int) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** A photo as it leaves (card 9780f3a0): upright, at most 2048 px, JPEG without EXIF – and a small preview (320 px). */
    fun photo(source: ByteArray): Pair<ByteArray, ByteArray> {
        val image = fit(upright(source, PHOTO_PX), PHOTO_PX)
        return jpeg(image, 85) to jpeg(fit(image, 320), 70)
    }

    /** The preview of a video: a frame from its first second, 320 px (MediaMetadataRetriever reads the file). */
    fun videoPreview(file: java.io.File): ByteArray? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(500_000)?.let { jpeg(fit(it, 320), 70) }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    fun prepare(source: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0) throw LiChatException("Kein Bild")
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw LiChatException("Kein Bild")
        // Upright as shown in the gallery: the rotation is read from EXIF once, then EXIF is gone.
        val rotation = runCatching { ExifInterface(ByteArrayInputStream(source)).rotationDegrees }.getOrDefault(0)
        val upright = if (rotation == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true)
        val side = minOf(upright.width, upright.height)
        val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, PX, PX, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }
}
