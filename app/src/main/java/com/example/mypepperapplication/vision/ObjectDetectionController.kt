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
 */
data class BoundingBox(
    val label: String,
    val score: Float,
    val rect: RectF,
    val cx: Float = rect.centerX(),
    val cy: Float = rect.centerY(),
    val source: String = "unknown"
)
class ObjectDetectionController {
    fun interface DetectionCallback {
        fun onDetections(boxes: List<BoundingBox>, imageWidth: Int, imageHeight: Int)
    }

    // Configurazione ───────────────────────────────────────────────────────
    //URL da modificare con IP corretta
    var serverUrl: String = "http://10.186.13.27:8000"

    //Qualità JPEG per trasferimento
    var jpegQuality: Int = 70
    // qui ho impostato una mock detection(true) che restituisce una lista vuota
    // ma non mi interssa veramente che ritorni qualcosa di fake
    var useMockFallback: Boolean = true

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    //API pubblica
    // Invia [bitmap] al server YOLOv8 sul PC e consegna i risultati a [callback]
    fun detect(bitmap: Bitmap, callback: DetectionCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            val boxes = runRemoteYolo(bitmap)
            withContext(Dispatchers.Main) {
                callback.onDetections(boxes, bitmap.width, bitmap.height)
            }
        }
    }

    // Remote yolo - server pc

    private fun runRemoteYolo(bitmap: Bitmap): List<BoundingBox> {
        return try {
            // compresso in jpeg
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
    private fun fallback(): List<BoundingBox> {
        Log.w(TAG, "Server unreachable, returning empty list")
        return emptyList()
    }
    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}