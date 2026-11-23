package com.mandonothing.ops

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun compressNearbyPhoto(open: () -> InputStream?): ByteArray? {
    val source = FieldImages.read(open, 680) ?: return null
    try {
        for (limit in listOf(680, 520, 400, 300)) {
            val scale = minOf(1f, limit.toFloat() / maxOf(source.width, source.height))
            val bitmap = if (scale == 1f) source else Bitmap.createScaledBitmap(source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1), true)
            try {
                for (quality in listOf(80, 65, 48)) {
                    val buffer = ByteArrayOutputStream()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer) &&
                        buffer.size() in 1..NEARBY_PRIVATE_PHOTO_LIMIT) return buffer.toByteArray()
                }
            } finally {
                if (bitmap !== source) bitmap.recycle()
            }
        }
    } finally {
        source.recycle()
    }
    return null
}

internal fun decodeNearbyPhoto(bytes: ByteArray): Bitmap? {
    if (bytes.size !in 1..NEARBY_PRIVATE_PHOTO_LIMIT) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..960 || bounds.outHeight !in 1..960 || bounds.outMimeType != "image/jpeg") return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
