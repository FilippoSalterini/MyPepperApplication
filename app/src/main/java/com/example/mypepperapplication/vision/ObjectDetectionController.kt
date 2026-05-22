package com.example.mypepperapplication.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// ===========================================================================
// OBJECT DETECTION CONTROLLER
// ===========================================================================

/*
Serve per inviare frame JPEG al server YOLOv8n su pc e decodifica le bounding box
normalizzate [0,1]
*/

private const val TAG = "ObjectDetection"

// Data class immutabile per i risultati
data class BoundingBox(
    val label: String,
    val score: Float,
    val rect: RectF,
    val cx: Float = rect.centerX(),
    val cy: Float = rect.centerY(),
    val source: String = "unknown"
)

class ObjectDetectionController {

    private val detectionRunning = AtomicBoolean(false)
    private val classJob = SupervisorJob()

    // Lo scope ora fa da supervisore globale per i job lanciati
    private val scope = CoroutineScope(classJob + Dispatchers.Main)

    fun interface DetectionCallback {
        fun onDetections(boxes: List<BoundingBox>, imageWidth: Int, imageHeight: Int)
    }

    /*
     * URL del server YOLOv8n
     */
    @Volatile
    var serverUrl: String = "http://10.186.13.27:8000"
    var jpegQuality: Int = 70

    // OkHttpClient configurato localmente, ma idealmente (appunto in questo
    // progetto non risulta essere limitante siccome obj det controller
    // viene creato una sola volta) dovrebbe essere un singleton applicativo.
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    fun detect(bitmap: Bitmap, callback: DetectionCallback) {
        if (!detectionRunning.compareAndSet(false, true)) {
            Log.v(TAG, "Detection skipped: previous request still running")
            return
        }

        scope.launch {
            try {
                val boxes = runRemoteYolo(bitmap)
                callback.onDetections(boxes, bitmap.width, bitmap.height)
            } catch (e: CancellationException) {
                Log.d(TAG, "Detection coroutine cancelled explicitly. Frame dropped.")
                throw e
            } finally {
                detectionRunning.set(false)
            }
        }
    }

    /*
     * Esegue la richiesta HTTP in modo asincrono e nativamente cancellabile.
     * Grazie a [suspendCancellableCoroutine], se il job viene cancellato dall'esterno,
     * la chiamata in volo di OkHttp viene interrotta IMMEDIATAMENTE via socket.
     */
    private suspend fun runRemoteYolo(bitmap: Bitmap): List<BoundingBox> = withContext(Dispatchers.IO) {
        val jpegBytes = bitmapToJpeg(bitmap, jpegQuality)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name     = "file",
                filename = "frame.jpg",
                body     = jpegBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$serverUrl/detect")
            .post(requestBody)
            .build()

        // Sfruttiamo l'API asincrona di OkHttp incapsulata nelle coroutine
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)

            // Se la coroutine viene cancellata (es. via cancelChildren), cancella subito la chiamata HTTP
            continuation.invokeOnCancellation {
                Log.d(TAG, "Coroutine cancelled. Aborting HTTP call immediately.")
                call.cancel()
            }

            // Usiamo enqueue() invece di execute() per non bloccare il thread di I/O delle coroutine
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    Log.e(TAG, "Remote YOLO request failed: ${e.message}")
                    continuation.resume(fallback())
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            Log.e(TAG, "Server error: ${res.code} ${res.message}")
                            continuation.resume(fallback())
                            return
                        }
                        val body = res.body?.string()
                        if (body == null) {
                            continuation.resume(fallback())
                            return
                        }
                        val parsed = parseServerResponse(body)
                        continuation.resume(parsed)
                    }
                }
            })
        }
    }

    private fun parseServerResponse(json: String): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        try {
            val root    = JSONObject(json)
            val objects = root.getJSONArray("objects")
            for (i in 0 until objects.length()) {
                val obj   = objects.getJSONObject(i)
                val label = obj.getString("label")
                val score = obj.getDouble("score").toFloat()
                val cx    = obj.getDouble("cx").toFloat()
                val cy    = obj.getDouble("cy").toFloat()
                val w     = obj.getDouble("w").toFloat()
                val h     = obj.getDouble("h").toFloat()

                val left   = (cx - w / 2f).coerceIn(0f, 1f)
                val top    = (cy - h / 2f).coerceIn(0f, 1f)
                val right  = (cx + w / 2f).coerceIn(0f, 1f)
                val bottom = (cy + h / 2f).coerceIn(0f, 1f)

                boxes.add(BoundingBox(label, score, RectF(left, top, right, bottom), cx, cy, "remote_yolo"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
        return boxes
    }

    private fun fallback(): List<BoundingBox> = emptyList()

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}