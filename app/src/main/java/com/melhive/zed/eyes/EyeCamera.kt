package com.melhive.zed.eyes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.media.FaceDetector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Range
import android.view.Display
import android.view.Surface
import com.melhive.zed.android.CrashReporter
import com.melhive.zed.core.eyes.SceneWatcher
import com.melhive.zed.intelligence.ImageInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Eye mode's sight: the front camera at low resolution, used to find where the
 * user's face is, to notice something held up, and to take one picture when
 * ZED is asked "what is this?". Frames are looked at and thrown away; nothing
 * is stored.
 *
 * The camera's own face finder is used when the phone has one; otherwise
 * Android's built-in detector looks at a small grey copy of the frame.
 */
class EyeCamera(private val context: Context) {
    /** A face is in view, at [faceX],[faceY] in gaze terms: -1 left … 1 right, -1 up … 1 down, as seen from the screen. */
    @Volatile var faceSeen = false
        private set
    @Volatile var faceX = 0f
        private set
    @Volatile var faceY = 0f
        private set
    /** Something other than the face has come into view. */
    @Volatile var objectSeen = false
        private set
    @Volatile var objectX = 0f
        private set
    @Volatile var objectY = 0f
        private set
    @Volatile var running = false
        private set
    /** Average brightness of the view, 0..255 (a dark room is below about 45). */
    @Volatile var brightness = 128f
        private set
    /** Which way the screen is turned (degrees), to know which end of the phone the camera is at. */
    @Volatile private var screenTurn = 0

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var closed = true

    private var front = true
    private var sensorOrientation = 270
    private var active: Rect? = null
    private var faceMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF

    // The camera's own face finder reports here (left, top, right, bottom in the upright picture, 0..1).
    private val halFace = FloatArray(4)
    @Volatile private var halFaceAt = 0L

    private val watcher = SceneWatcher(GRID_W, GRID_H)
    private val grid = IntArray(GRID_W * GRID_H)
    private val face = FloatArray(4)
    private var softFaceAt = 0L
    private var softTriedAt = 0L
    private var lastFrameAt = 0L
    private var detector: FaceDetector? = null
    private var detectorBitmap: Bitmap? = null
    private var detectorPixels: IntArray? = null
    private val found = arrayOfNulls<FaceDetector.Face>(1)
    private val mid = PointF()

    @Volatile private var shot: CompletableDeferred<ImageInput?>? = null
    private var frameErrors = 0

    /** Opens the camera. Needs the camera permission; does nothing harmful without a camera. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!closed) return
        closed = false
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
                ?: ids.firstOrNull() ?: run { closed = true; return }
            val chars = manager.getCameraCharacteristics(id)
            front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
            active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0)
            faceMode = when {
                modes.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                modes.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                else -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            }
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
            if (sizes == null || sizes.isEmpty()) { closed = true; return }
            // Around 800 pixels wide: enough to recognise what is shown, light enough to watch all the time.
            val size = sizes.filter { it.width in 480..1400 && abs(it.width * 3 - it.height * 4) <= it.width / 10 }.minByOrNull { abs(it.width - 800) }
                ?: sizes.minByOrNull { abs(it.width - 640) + abs(it.height - 480) }!!
            // The slowest steady frame rate the camera offers: following a face doesn't need more.
            val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.filter { it.upper >= 10 }?.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

            val t = HandlerThread("zed-eye-camera").also { it.start() }
            val h = Handler(t.looper)
            thread = t; handler = h
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = r
            r.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try { if (!closed) onFrame(image) } catch (e: Throwable) { if (frameErrors++ < 2) CrashReporter.recordNonFatal("eye camera frame", e) } finally { runCatching { image.close() } }
            }, h)

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (closed) { runCatching { s.close() }; return }
                                session = s
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    request.addTarget(r.surface)
                                    request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    if (faceMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) request.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
                                    if (fps != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                                    s.setRepeatingRequest(request.build(), results, h)
                                    running = true
                                } catch (e: Throwable) { CrashReporter.recordNonFatal("eye camera request", e) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { CrashReporter.recordNonFatal("eye camera", IllegalStateException("Camera session could not be configured")) }
                        }, h)
                    } catch (e: Throwable) { CrashReporter.recordNonFatal("eye camera session", e) }
                }
                override fun onDisconnected(camera: CameraDevice) { runCatching { camera.close() }; device = null; lost() }
                override fun onError(camera: CameraDevice, error: Int) { runCatching { camera.close() }; device = null; lost() }
            }, h)
        } catch (e: Throwable) {
            // Another app holds the camera, or the phone has none: the eyes simply wander.
            CrashReporter.recordNonFatal("eye camera", e)
            stop()
        }
    }

    private fun lost() { running = false; faceSeen = false; objectSeen = false }

    /** Closes the camera. */
    fun stop() {
        closed = true
        lost()
        shot?.complete(null); shot = null
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
        runCatching { thread?.quitSafely() }; thread = null; handler = null
        runCatching { detectorBitmap?.recycle() }; detectorBitmap = null; detector = null
    }

    /** One picture of what the camera sees now, upright, for "what is this?". Null when the camera isn't running. */
    suspend fun capture(): ImageInput? {
        if (!running) return null
        val waiting = CompletableDeferred<ImageInput?>()
        shot = waiting
        return withTimeoutOrNull(2500) { waiting.await() }.also { if (shot === waiting) shot = null }
    }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val faces = result.get(CaptureResult.STATISTICS_FACES) ?: return
            val area = active ?: return
            val best = faces.maxByOrNull { it.bounds.width() * it.bounds.height() } ?: return
            if (best.score < 30 || area.width() <= 0 || area.height() <= 0) return
            val b = best.bounds
            val turn = rotation()
            val l = (b.left - area.left).toFloat() / area.width(); val r = (b.right - area.left).toFloat() / area.width()
            val t = (b.top - area.top).toFloat() / area.height(); val bt = (b.bottom - area.top).toFloat() / area.height()
            // Two opposite corners turned upright, then put in order.
            val x0 = uprightX(l, t, turn); val y0 = uprightY(l, t, turn)
            val x1 = uprightX(r, bt, turn); val y1 = uprightY(r, bt, turn)
            synchronized(halFace) { halFace[0] = minOf(x0, x1); halFace[1] = minOf(y0, y1); halFace[2] = maxOf(x0, x1); halFace[3] = maxOf(y0, y1) }
            halFaceAt = SystemClock.elapsedRealtime()
        }
    }

    /** How far the camera's picture must be turned clockwise to stand upright on the screen as it is held now. */
    private fun rotation(): Int {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val screen = when (display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        screenTurn = screen
        return if (front) (sensorOrientation + screen) % 360 else (sensorOrientation - screen + 360) % 360
    }

    // A point (u,v) of the camera's picture → the upright picture, both 0..1.
    private fun uprightX(u: Float, v: Float, turn: Int): Float = when (turn) { 90 -> 1f - v; 180 -> 1f - u; 270 -> v; else -> u }
    private fun uprightY(u: Float, v: Float, turn: Int): Float = when (turn) { 90 -> u; 180 -> 1f - v; 270 -> 1f - u; else -> v }
    // And back: a point (a,b) of the upright picture → the camera's picture.
    private fun rawU(a: Float, b: Float, turn: Int): Float = when (turn) { 90 -> b; 180 -> 1f - a; 270 -> 1f - b; else -> a }
    private fun rawV(a: Float, b: Float, turn: Int): Float = when (turn) { 90 -> 1f - a; 180 -> 1f - b; 270 -> a; else -> b }

    private fun onFrame(image: Image) {
        val turn = rotation()
        shot?.let { waiting ->
            shot = null
            waiting.complete(runCatching { picture(image, turn) }.onFailure { CrashReporter.recordNonFatal("eye camera picture", it) }.getOrNull())
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 66) return // about 15 looks a second is plenty
        val dt = if (lastFrameAt == 0L) 0.066f else ((now - lastFrameAt) / 1000f).coerceAtMost(0.5f)
        lastFrameAt = now

        val plane = image.planes[0]
        val buf = plane.buffer
        val stride = plane.rowStride
        val w = image.width; val h = image.height
        val limit = buf.limit()
        fun luma(a: Float, b: Float): Int {
            val x = (rawU(a, b, turn) * (w - 1)).toInt().coerceIn(0, w - 1)
            val y = (rawV(a, b, turn) * (h - 1)).toInt().coerceIn(0, h - 1)
            val i = y * stride + x
            return if (i < limit) buf.get(i).toInt() and 0xFF else 0
        }

        // A small grey picture of the view, upright.
        for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
            val a = (gx + 0.5f) / GRID_W; val b = (gy + 0.5f) / GRID_H
            val da = 0.25f / GRID_W; val db = 0.25f / GRID_H
            grid[gy * GRID_W + gx] = (luma(a - da, b - db) + luma(a + da, b - db) + luma(a - da, b + db) + luma(a + da, b + db)) / 4
        }

        // Where is the face? The camera's own finder first; Android's detector when that stays silent.
        var seen = false
        if (now - halFaceAt < 450) { synchronized(halFace) { halFace.copyInto(face) }; seen = true }
        else {
            if (now - softTriedAt > 330 && !watcher.dark) {
                softTriedAt = now
                if (softFace(turn, ::luma)) softFaceAt = now
            }
            seen = now - softFaceAt < 900
        }
        watcher.update(grid, dt, if (seen) face else null)
        if (watcher.dark) seen = false
        brightness = watcher.brightness

        if (seen) {
            val fx = (face[0] + face[2]) / 2f; val fy = (face[1] + face[3]) / 2f
            // In landscape the camera sits at one end of the phone, not behind the eyes: someone straight in front of the
            // screen looks off to one side to the camera. The closer the face (the wider it appears), the bigger the shift.
            val shift = if (front) (0.93f * (face[2] - face[0])).coerceIn(0f, 0.45f) * (when (screenTurn) { 90 -> 1f; 270 -> -1f; else -> 0f }) else 0f
            val tx = (gazeX(fx) - shift * 1.5f).coerceIn(-1f, 1f); val ty = gazeY(fy)
            // Smooth, so the eyes don't twitch with every small error.
            faceX = if (faceSeen) faceX + (tx - faceX) * 0.5f else tx
            faceY = if (faceSeen) faceY + (ty - faceY) * 0.5f else ty
        }
        faceSeen = seen
        if (watcher.present) { objectX = gazeX(watcher.x); objectY = gazeY(watcher.y) }
        objectSeen = watcher.present
    }

    // The front camera faces the user: what is on the picture's left is on the screen's right.
    private fun gazeX(x: Float): Float = ((if (front) 0.5f - x else x - 0.5f) * 2f * 1.5f).coerceIn(-1f, 1f)
    private fun gazeY(y: Float): Float = ((y - 0.5f) * 2f * 1.3f).coerceIn(-1f, 1f)

    /** Android's built-in face detector on a small grey upright copy. Fills [face] when it finds one. */
    private fun softFace(turn: Int, luma: (Float, Float) -> Int): Boolean {
        val sideways = turn == 90 || turn == 270
        val bw = if (sideways) 240 else 320
        val bh = if (sideways) 320 else 240
        var bitmap = detectorBitmap
        if (bitmap == null || bitmap.width != bw) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
            detectorBitmap = bitmap
            detector = FaceDetector(bw, bh, 1)
            detectorPixels = IntArray(bw * bh)
        }
        val pixels = detectorPixels!!
        for (y in 0 until bh) {
            val b = (y + 0.5f) / bh
            for (x in 0 until bw) {
                val v = luma((x + 0.5f) / bw, b)
                pixels[y * bw + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        bitmap!!.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        found[0] = null
        val count = detector!!.findFaces(bitmap, found)
        val f = found[0]
        if (count < 1 || f == null || f.confidence() < 0.4f) return false
        f.getMidPoint(mid)
        val eyes = f.eyesDistance()
        face[0] = (mid.x - eyes * 1.2f) / bw; face[2] = (mid.x + eyes * 1.2f) / bw
        face[1] = (mid.y - eyes * 1.3f) / bh; face[3] = (mid.y + eyes * 1.7f) / bh
        return true
    }

    /** The whole frame as an upright JPEG. */
    private fun picture(image: Image, turn: Int): ImageInput? {
        val w = image.width; val h = image.height
        val nv21 = ByteArray(w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2))
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer.duplicate()
        for (row in 0 until h) {
            yBuf.position(minOf(row * yPlane.rowStride, yBuf.limit()))
            yBuf.get(nv21, row * w, minOf(w, yBuf.remaining()))
        }
        val u = image.planes[1]; val v = image.planes[2]
        val ub = u.buffer; val vb = v.buffer
        var o = w * h
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            val vi = row * v.rowStride + col * v.pixelStride
            val ui = row * u.rowStride + col * u.pixelStride
            nv21[o++] = if (vi < vb.limit()) vb.get(vi) else 127
            nv21[o++] = if (ui < ub.limit()) ub.get(ui) else 127
        }
        val jpeg = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 90, jpeg)
        val bytes = jpeg.toByteArray()
        if (turn == 0) return ImageInput("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (upright !== raw) upright.recycle()
        raw.recycle()
        return ImageInput("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    companion object {
        private const val GRID_W = 48
        private const val GRID_H = 36
    }
}
