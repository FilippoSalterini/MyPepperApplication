package com.example.mypepperapplication.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import com.aldebaran.qi.sdk.builder.TakePictureBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

private const val TAG = "PepperCamera"

/**
 * PepperCameraController — acquisizione frame dalla camera frontale di Pepper.
 *
 * Architettura PC-side:
 *   Questo controller fornisce SOLO i frame raw.
 *   NON esegue AI. I frame vengono inviati al PC da ObjectDetectionController.
 *
 * Nota su TakePicture:
 *   QiSDK TakePicture è pensato per snapshot, NON per streaming video.
 *   Frequenza reale: 2-5 fps (limitato da QiSDK, non dalla rete).
 *   Per visual servoing a 2-3 fps è sufficiente con inferenza CPU sul PC.
 *
 * Nota su [startContinuousCapture]:
 *   Non usare startContinuousCapture insieme a VisualServoingController.startTracking(),
 *   perché VisualServoingController chiama già takeSinglePicture() nel suo loop.
 *   Usa startContinuousCapture solo per preview/debug standalone.
 */
class PepperCameraController {

    // ── Interfaccia callback ──────────────────────────────────────────────────

    fun interface FrameCallback {
        /** Chiamato su thread IO ogni volta che un nuovo frame è disponibile. */
        fun onFrame(bitmap: Bitmap, timestampMs: Long)
    }

    // ── Stato interno ─────────────────────────────────────────────────────────

    private var qiContext: QiContext? = null
    private var captureJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        Log.d(TAG, "Camera controller ready.")
    }

    fun onRobotLost() {
        stopContinuousCapture()
        qiContext = null
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Scatta un singolo frame.
     * Usato da VisualServoingController nel suo loop di controllo.
     */
    fun takeSinglePicture(callback: FrameCallback) {
        val ctx = qiContext ?: run {
            Log.e(TAG, "takeSinglePicture: qiContext null — robot non connesso?")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val takePicture = TakePictureBuilder.with(ctx).build()
                val timestampedImage: TimestampedImageHandle = takePicture.async().run().get()
                val bitmap = timestampedImage.toBitmap()
                if (bitmap != null) {
                    callback.onFrame(bitmap, System.currentTimeMillis())
                    Log.d(TAG, "Frame captured: ${bitmap.width}×${bitmap.height}")
                } else {
                    Log.e(TAG, "Frame decode failed")
                }
            } catch (e: Exception) {
                if (e.message?.contains("video device") == true) {
                    Log.w(TAG, "Camera busy during movement, retry in 200ms...")
                    delay(200)
                    try {
                        val takePicture2 = TakePictureBuilder.with(ctx).build()
                        val img = takePicture2.async().run().get()
                        val bitmap = img.toBitmap()
                        if (bitmap != null) callback.onFrame(bitmap, System.currentTimeMillis())
                    } catch (e2: Exception) {
                        Log.e(TAG, "Retry failed: ${e2.message}")
                    }
                } else {
                    Log.e(TAG, "takeSinglePicture error: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Cattura continua a fps fissi. Usa solo per preview standalone o debug.
     * NON usare insieme a VisualServoingController (duplica le richieste camera).
     */
    fun startContinuousCapture(targetFps: Int = 3, callback: FrameCallback) {
        if (captureJob?.isActive == true) {
            Log.w(TAG, "Continuous capture already running.")
            return
        }
        val ctx = qiContext ?: run {
            Log.e(TAG, "startContinuousCapture: qiContext null")
            return
        }
        val delayMs = 1000L / targetFps
        Log.d(TAG, "Starting continuous capture at $targetFps fps")

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val takePicture = TakePictureBuilder.with(ctx).build()
                    val img: TimestampedImageHandle = takePicture.async().run().get()
                    val bitmap = img.toBitmap()
                    if (bitmap != null) callback.onFrame(bitmap, img.time)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Capture error: ${e.message}")
                }
                delay(delayMs)
            }
        }
    }

    fun stopContinuousCapture() {
        captureJob?.cancel()
        captureJob = null
        Log.d(TAG, "Continuous capture stopped.")
    }

    // ── toBitmap ──────────────────────────────────────────────────────────────

    /**
     * Converte TimestampedImageHandle → Bitmap.
     * Pepper top camera: formato RGB888 raw (3 byte/pixel, 640×480).
     * Fallback: BitmapFactory se il formato è JPEG.
     */
    private fun TimestampedImageHandle.toBitmap(): Bitmap? {
        return try {
            val image  = this.image.value
            val buffer: ByteBuffer = image.data
            val bytes  = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width  = 640
            val height = 480

            // RGB888 → ARGB_8888
            if (bytes.size >= width * height * 3) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(width * height)
                for (i in pixels.indices) {
                    val r = bytes[i * 3].toInt() and 0xFF
                    val g = bytes[i * 3 + 1].toInt() and 0xFF
                    val b = bytes[i * 3 + 2].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                bitmap
            } else {
                // Prova come JPEG
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "toBitmap error: ${e.message}")
            try {
                val image  = this.image.value
                val buffer = image.data
                val bytes  = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback JPEG decode failed: ${e2.message}")
                null
            }
        }
    }
}