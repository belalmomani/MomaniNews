package com.momaninews.app.data.admin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/** يحضّر صورة خبر: أي حجم أصلي مقبول؛ تُصغَّر إلى MAX_SIDE كحد أقصى للضلع وتُرمَّز JPEG بجودة تُبقي الحجم تحت MAX_BYTES. */
object NewsImagePrep {
    const val MAX_SIDE = 2560
    const val MAX_BYTES = 10 * 1024 * 1024

    /** أصغر قوة للعدد 2 تجعل أكبر ضلع ≤ [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var s = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxSide) { s *= 2; longest /= 2 }
        return s
    }

    fun toJpeg(context: Context, uri: Uri): ByteArray? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rotation = try {
            resolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        var bitmap = if (rotation == 0f) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)

        if (bitmap.hasAlpha()) { // JPEG بلا شفافية: نضع خلفية بيضاء بدل الأسود
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            bitmap = flat
        }
        val out = ByteArrayOutputStream()
        var quality = 88
        do {
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            quality -= 12
        } while (out.size() > MAX_BYTES && quality > 25)
        return if (out.size() in 1..MAX_BYTES) out.toByteArray() else null
    }
}
