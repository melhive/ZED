package com.melhive.zenith.intelligence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A photo sent to a vision-capable model with the newest user message. */
data class ImageInput(val mimeType: String, val base64: String) {
    companion object {
        /** Decodes, upright-rotates and downsizes a photo (max 1280 px, JPEG) for upload. */
        suspend fun fromUri(context: Context, uri: Uri, maxSide: Int = 1280): ImageInput = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: error("Couldn't open the photo")
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("Couldn't read the photo")
            val rotation = runCatching {
                resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }.getOrDefault(0)
            val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            ImageInput("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }

        /** A content:// address for the camera app to save a new photo into (app cache). */
        fun newCaptureUri(context: Context): Uri {
            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
            return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }
    }
}
