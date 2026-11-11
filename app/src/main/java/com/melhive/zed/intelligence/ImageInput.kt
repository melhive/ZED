package com.melhive.zed.intelligence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A photo sent to a vision-capable model with the newest user message. */
data class ImageInput(val mimeType: String, val base64: String) {
    companion object {
        /** Largest photo file ZED reads (phone cameras produce 2–15 MB). */
        private const val MAX_FILE_BYTES = 40L shl 20

        /** Reads a photo from the camera or gallery, turns it upright and downsizes it (max 1280 px, JPEG). */
        suspend fun fromUri(context: Context, uri: Uri, maxSide: Int = 1280): ImageInput = withContext(Dispatchers.IO) {
            val data = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) throw IOException("That photo is too large (over 40 MB).")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } catch (e: SecurityException) {
                throw IOException("ZED wasn't allowed to read that photo. Choose it again from the gallery.", e)
            } ?: throw IOException("Couldn't open the photo. Try taking or choosing it again.")
            if (data.isEmpty()) throw IOException("The photo was empty. If the camera was closed early, take it again.")
            fromBytes(data, maxSide)
        }

        /** Same as [fromUri] for photo bytes already in memory. */
        fun fromBytes(data: ByteArray, maxSide: Int = 1280): ImageInput {
            val bitmap = decodeScaled(data, maxSide)
                ?: throw IOException("This file isn't a photo ZED can read. Try a JPEG, PNG, WebP or HEIC image.")
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            return ImageInput("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }

        /** Decodes at roughly [maxSide], then applies the camera's rotation and the exact final size. */
        private fun decodeScaled(data: ByteArray, maxSide: Int): Bitmap? {
            // 1) Size only. With inJustDecodeBounds Android fills in the size and returns null on purpose.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var raw: Bitmap? = null
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                raw = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            // 2) Formats BitmapFactory can't read on some phones (HEIC/AVIF): Android's newer decoder,
            //    which already turns the picture upright.
            if (raw == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val decoded = runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data))) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > maxSide) {
                            val s = maxSide.toFloat() / longest
                            decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                        }
                    }
                }.getOrNull()
                if (decoded != null) return fit(decoded, 0, maxSide)
            }
            val bmp = raw ?: return null
            val rotation = runCatching { ExifInterface(ByteArrayInputStream(data)).rotationDegrees }.getOrDefault(0)
            return fit(bmp, rotation, maxSide)
        }

        private fun fit(raw: Bitmap, rotation: Int, maxSide: Int): Bitmap {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
            if (scale == 1f && rotation == 0) return raw
            val m = Matrix().apply { postScale(scale, scale); if (rotation != 0) postRotate(rotation.toFloat()) }
            val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (out !== raw) raw.recycle()
            return out
        }

        /** A content:// address for the camera app to save a new photo into (app cache). */
        fun newCaptureUri(context: Context): Uri {
            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
            return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }
    }
}
