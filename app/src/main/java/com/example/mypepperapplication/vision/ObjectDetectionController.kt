package com.example.mypepperapplication.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "ObjectDetection"

/**
 * Bounding box normalizzata [0,1] + metadati del rilevamento.
 *
 * @param label   Etichetta classe (es. "person", "chair")
 * @param score   Confidenza [0.0 – 1.0]
 * @param rect    Coordinate normalizzate (left, top, right, bottom) in [0,1]
 * @param cx      Centro X normalizzato
 * @param cy      Centro Y normalizzato
 * @param source  "remote_yolo" | "mock"
 */
data class BoundingBox(
    val label: String,
    val score: Float,
    val rect: RectF,
    val cx: Float = rect.centerX(),
    val cy: Float = rect.centerY(),
    val source: String = "unknown"
)

/**
 * ObjectDetectionController — Architettura PC-side inference.
 *
 * Pepper NON esegue AI localmente.
 * Invia il frame JPEG al server Python (YOLOv8n su PC) via HTTP POST
 * e riceve le bounding boxes normalizzate come JSON.
 *
 * Flusso:
 *   Pepper Camera → JPEG → POST /detect → PC YOLOv8 → JSON boxes → Pepper
 *
 * Setup server (PC):
 *   pip install fastapi uvicorn ultralytics pillow python-multipart
 *   uvicorn server:app --host 0.0.0.0 --port 8000
 *
 * Configurazione:
 *   detectionController.serverUrl = "http://<IP_PC>:8000"
 *
 * Dipendenze Gradle:
 *   implementation("com.squareup.okhttp3:okhttp:4.12.0")
 */
class ObjectDetectionController {

    // ── Callback ──────────────────────────────────────────────────────────────

    fun interface DetectionCallback {
        fun onDetections(boxes: List<BoundingBox>, imageWidth: Int, imageHeight: Int)
    }

    // ── Config ────────────────────────────────────────────────────────────────

    /** URL del server Python sul PC. Modifica con l'IP del tuo PC sulla LAN. */
    var serverUrl: String = "http://10.186.13.27:8000"

    /** Qualità JPEG per il trasferimento (70 = buon bilanciamento qualità/velocità). */
    var jpegQuality: Int = 70

    /** Se true, usa mock detections quando il server non è raggiungibile. */
    var useMockFallback: Boolean = true

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)   // fallisce veloce se PC non risponde
            .readTimeout(10, TimeUnit.SECONDS)      // 10s max per inferenza CPU
            .build()
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Invia [bitmap] al server YOLOv8 sul PC e consegna i risultati a [callback].
     * Chiamata NON bloccante — eseguita su Dispatchers.IO.
     */
    fun detect(bitmap: Bitmap, callback: DetectionCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            val boxes = runRemoteYolo(bitmap)
            withContext(Dispatchers.Main) {
                callback.onDetections(boxes, bitmap.width, bitmap.height)
            }
        }
    }

    // ── Remote YOLO (PC server) ───────────────────────────────────────────────

    private fun runRemoteYolo(bitmap: Bitmap): List<BoundingBox> {
        return try {
            // Comprimi in JPEG per ridurre il payload di rete
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

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Server error: ${response.code} ${response.message}")
                return fallback()
            }

            val body = response.body?.string() ?: return fallback()
            val parsed = parseServerResponse(body)

            Log.d(TAG, "Remote YOLO: ${parsed.size} objects detected")
            parsed

        } catch (e: Exception) {
            Log.e(TAG, "Remote YOLO request failed: ${e.message}")
            fallback()
        }
    }

    // ── Parser risposta server ────────────────────────────────────────────────

    /**
     * Parsa la risposta JSON del server:
     * {
     *   "objects": [
     *     {"label":"person","score":0.92,"cx":0.50,"cy":0.45,"w":0.30,"h":0.80}
     *   ],
     *   "inference_ms": 312
     * }
     */
    private fun parseServerResponse(json: String): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        try {
            val root    = JSONObject(json)
            val objects = root.getJSONArray("objects")
            val inferMs = root.optInt("inference_ms", -1)

            if (inferMs > 0) Log.d(TAG, "Server inference time: ${inferMs}ms")

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

                boxes.add(
                    BoundingBox(
                        label  = label,
                        score  = score,
                        rect   = RectF(left, top, right, bottom),
                        cx     = cx,
                        cy     = cy,
                        source = "remote_yolo"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}\nRaw: $json")
        }
        return boxes
    }

    // ── Fallback mock ─────────────────────────────────────────────────────────

    /**
     * Usato solo se il server non è raggiungibile.
     * Utile per testare la pipeline UI/movimento senza PC connesso.
     */
    private fun fallback(): List<BoundingBox> {
        if (!useMockFallback) return emptyList()
        Log.w(TAG, "Server unreachable — using mock detections")
        return listOf(
            BoundingBox("person", 0.91f, RectF(0.35f, 0.10f, 0.65f, 0.90f), source = "mock"),
            BoundingBox("chair",  0.78f, RectF(0.70f, 0.40f, 0.95f, 0.85f), source = "mock")
        )
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}