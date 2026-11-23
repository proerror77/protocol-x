package com.mandonothing.ops

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.InputStream

internal object BindingQr {
    private const val PREFIX = "protocol-x:bind:"
    private val codePattern = Regex("[A-Za-z0-9_-]{22,128}")

    fun render(code: String, size: Int = 520): Bitmap {
        require(code.matches(codePattern))
        val grid = QRCodeWriter().encode(PREFIX + code, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(grid.width * grid.height) { i ->
            if (grid[i % grid.width, i / grid.width]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, grid.width, grid.height, Bitmap.Config.ARGB_8888)
    }

    fun read(bitmap: Bitmap): String? = runCatching {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val value = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.TRY_HARDER to true)).text
        codeFrom(value)
    }.getOrNull()

    fun codeFrom(value: String): String? = value.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)?.takeIf { it.matches(codePattern) }
}

internal object FieldImages {
    fun capture(context: Context): Pair<File, Intent> {
        val dir = File(context.cacheDir, "capture")
        check(dir.isDirectory || dir.mkdirs())
        val file = File.createTempFile("field-", ".jpg", dir)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".capture", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("Protocol X capture", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return file to intent
    }

    fun read(open: () -> InputStream?, limit: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val source = open() ?: return null
        source.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > limit) sample *= 2
        return open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
