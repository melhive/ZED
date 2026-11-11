package com.melhive.zed

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.melhive.zed.intelligence.ImageInput
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs real photos through Android's image decoder (Robolectric native graphics). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageInputTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun photo(w: Int, h: Int, format: Bitmap.CompressFormat, name: String): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 160, 220)) }
        val f = File(context.cacheDir, name)
        FileOutputStream(f).use { bmp.compress(format, 90, it) }
        return f
    }

    private fun decode(img: ImageInput): Bitmap {
        assertEquals("image/jpeg", img.mimeType)
        val bytes = Base64.decode(img.base64, Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
    }

    @Test fun cameraJpegIsReadAndDownsized() = runBlocking {
        // The old loader threw "Couldn't open the photo" for every image (inJustDecodeBounds returns null).
        val out = decode(ImageInput.fromUri(context, Uri.fromFile(photo(4000, 3000, Bitmap.CompressFormat.JPEG, "big.jpg"))))
        assertEquals(1280, maxOf(out.width, out.height))
        assertEquals(960, out.height)
    }

    @Test fun rotatedPhoneShotComesOutUpright() = runBlocking {
        val f = photo(2000, 1000, Bitmap.CompressFormat.JPEG, "rotated.jpg")
        ExifInterface(f.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val out = decode(ImageInput.fromUri(context, Uri.fromFile(f)))
        assertTrue(out.height > out.width, "portrait after rotation: ${out.width}x${out.height}")
    }

    @Test fun smallPngFromGalleryWorks() = runBlocking {
        val out = decode(ImageInput.fromUri(context, Uri.fromFile(photo(640, 480, Bitmap.CompressFormat.PNG, "small.png"))))
        assertEquals(640, out.width)
    }

    @Test fun notAPhotoExplainsWhy() {
        val f = File(context.cacheDir, "notes.txt").apply { writeText("not an image") }
        val e = assertFailsWith<java.io.IOException> { runBlocking { ImageInput.fromUri(context, Uri.fromFile(f)) } }
        assertTrue("isn't a photo" in (e.message ?: ""), e.message)
    }
}
