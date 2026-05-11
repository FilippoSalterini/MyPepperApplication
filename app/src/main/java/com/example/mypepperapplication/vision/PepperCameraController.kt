package com.example.mypepperapplication.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import com.aldebaran.qi.sdk.`object`.streamablebuffer.StreamableBuffer
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
 * Gestisce l'acquisizione di frame dalla camera frontale di Pepper.
 *
 * Pepper espone due camere:
 *   - Top camera (640×480, usata per volti/oggetti lontani)
 *   - Bottom camera (320×240, usata per oggetti vicini e navigazione)
 *
 * QiSDK 1.x offre TakePicture (singolo scatto JPEG) oppure la Camera2
 * sottostante (stream continuo). Qui implementiamo ENTRAMBE le modalità:
 *
 *   1. [takeSinglePicture]  → snapshot one-shot, più semplice
 *   2. [startContinuousCapture] → polling a ~N fps via coroutine
 *
 * Il caller riceve un [FrameCallback] con Bitmap pronto per YOLO/GPT Vision.
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

    /** FPS target per la modalità continua (abbassa se il robot rallenta). */
    var targetFps: Int = 5

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
     * Scatta una singola foto con la camera top di Pepper.
     * Restituisce la Bitmap via callback (thread IO).
     */
    fun takeSinglePicture(callback: FrameCallback) {
        val ctx = qiContext ?: run {
            Log.e(TAG, "takeSinglePicture: qiContext null")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val takePicture = TakePictureBuilder.with(ctx).build()
                val timestampedImage: TimestampedImageHandle = takePicture.async().run().get()
                val bitmap = timestampedImage.toBitmap()
                if (bitmap != null) {
                    callback.onFrame(bitmap, System.currentTimeMillis())
                    Log.d(TAG, "Single picture captured: ${bitmap.width}×${bitmap.height}")
                } else {
                    Log.e(TAG, "Failed to decode picture bitmap")
                }
            } catch (e: Exception) {
                Log.e(TAG, "takeSinglePicture error: ${e.message}", e)
            }
        }
    }

    /**
     * Avvia la cattura continua a [targetFps] frame al secondo.
     * Ogni frame viene consegnato a [callback].
     *
     * NOTE: TakePicture su QiSDK è pensato per snapshot, non per streaming
     * video reale. Per frequenze > 5 fps si consiglia l'integrazione con
     * Camera2 (vedi [buildCamera2Note]). Qui usiamo polling come approccio
     * compatibile con qualsiasi versione QiSDK.
     */
    fun startContinuousCapture(callback: FrameCallback) {
        if (captureJob?.isActive == true) {
            Log.w(TAG, "Continuous capture already running, ignoring.")
            return
        }
        val ctx = qiContext ?: run {
            Log.e(TAG, "startContinuousCapture: qiContext null")
            return
        }
        val delayMs = (1000L / targetFps)
        Log.d(TAG, "Starting continuous capture at $targetFps fps (delay=$delayMs ms)")

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val takePicture = TakePictureBuilder.with(ctx).build()
                    val timestampedImage: TimestampedImageHandle = takePicture.async().run().get()
                    val bitmap = timestampedImage.toBitmap()
                    if (bitmap != null) {
                        callback.onFrame(bitmap, timestampedImage.time)
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Frame capture error: ${e.message}")
                }
                delay(delayMs)
            }
        }
    }

    /** Ferma la cattura continua. */
    fun stopContinuousCapture() {
        captureJob?.cancel()
        captureJob = null
        Log.d(TAG, "Continuous capture stopped.")
    }

    // ── Helper privati ────────────────────────────────────────────────────────

    /**
     * Converte TimestampedImageHandle → Bitmap.
     * Il buffer interno di Pepper è RGB888, non JPEG — lo convertiamo manualmente.
     */
    private fun TimestampedImageHandle.toBitmap(): Bitmap? {
        return try {
            val image = this.image.value
            val buffer: ByteBuffer = image.data
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width  = 640
            val height = 480

            // Pepper top camera produce dati RGB888 (3 byte/pixel)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixelArray = IntArray(width * height)
            for (i in pixelArray.indices) {
                val r = bytes[i * 3].toInt() and 0xFF
                val g = bytes[i * 3 + 1].toInt() and 0xFF
                val b = bytes[i * 3 + 2].toInt() and 0xFF
                pixelArray[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(pixelArray, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "toBitmap error: ${e.message}")
            // Fallback: tenta BitmapFactory se il formato è JPEG
            try {
                val image = this.image.value
                val buffer = image.data
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e2: Exception) {
                Log.e(TAG, "BitmapFactory fallback failed: ${e2.message}")
                null
            }
        }
    }

    /**
     * Converte StreamableBuffer in ByteBuffer leggibile.
     * QiSDK restituisce il buffer interno; dobbiamo copiarlo per sicurezza.
     */
    private fun StreamableBuffer.toByteBuffer(): ByteBuffer {
        // StreamableBuffer implementa ByteBuffer di tipo direct
        val direct = this.read(0L, this.size)
        val copy = ByteBuffer.allocate(direct.remaining())
        copy.put(direct)
        copy.rewind()
        return copy
    }

    // ── Note integrazione Camera2 ─────────────────────────────────────────────
    /**
     * Per streaming video reale (>10 fps) è possibile bypassare QiSDK e usare
     * direttamente l'API Android Camera2:
     *
     *   1. Ottenere il CameraManager da Context.
     *   2. Enumerare le camere con cameraManager.cameraIdList.
     *      Su Pepper: ID "0" = top camera, "1" = bottom camera (varia per versione HW).
     *   3. Aprire la camera con cameraManager.openCamera().
     *   4. Creare una CaptureSession con un ImageReader (formato YUV_420_888 o JPEG).
     *   5. Ogni ImageReader.OnImageAvailableListener fornisce un Image → convertire in Bitmap.
     *
     * Limitazione: Camera2 non si integra con il sistema di focus di QiSDK
     * (es. il robot potrebbe girare la testa in autonomia mentre acquisisci).
     * Usare HolderBuilder per bloccare i gradi di libertà della testa se necessario.
     *
     * Vedi: PepperCamera2Controller.kt (da creare nella prossima iterazione)
     */
    @Suppress("unused")
    private fun buildCamera2Note() = Unit
}
