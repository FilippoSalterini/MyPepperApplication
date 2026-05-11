package com.example.mypepperapplication.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "ObjectDetection"

/**
 * Bounding box normalizzata [0,1] + metadati del rilevamento.
 *
 * @param label     Etichetta classe (es. "person", "chair")
 * @param score     Confidenza [0.0 – 1.0]
 * @param rect      Coordinate normalizzate (left, top, right, bottom) in [0,1]
 * @param cx        Centro X normalizzato (pixel/imageWidth)
 * @param cy        Centro Y normalizzato (pixel/imageHeight)
 * @param source    "yolo_local" | "gpt_vision" | "mock"
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
 * Pipeline di object detection per Pepper.
 *
 * Strategia a due livelli:
 *   1. YOLO locale via TFLite  → veloce, no rete, ~20-30 ms/frame
 *   2. GPT-4o Vision (fallback) → open-world, richiede connessione WiFi
 *
 * Per abilitare YOLO locale:
 *   - Scarica yolov8n_float32.tflite da https://github.com/ultralytics/assets
 *   - Copialo in app/src/main/assets/yolov8n.tflite
 *   - Aggiungi in build.gradle.kts:
 *       implementation("org.tensorflow:tensorflow-lite:2.14.0")
 *       implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
 *
 * Per GPT-4o Vision:
 *   - Aggiungi in build.gradle.kts:
 *       implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *   - Imposta OPENAI_API_KEY (da BuildConfig o EncryptedSharedPreferences)
 */
class ObjectDetectionController(private val context: Context) {

    // ── Callback ──────────────────────────────────────────────────────────────

    fun interface DetectionCallback {
        fun onDetections(boxes: List<BoundingBox>, imageWidth: Int, imageHeight: Int)
    }

    // ── Config ────────────────────────────────────────────────────────────────

    var confidenceThreshold: Float = 0.45f
    var useGptFallback: Boolean = true
    var openAiApiKey: String = ""          // imposta da BuildConfig o Settings
    var gptModel: String = "gpt-4o"        // o "gpt-4-turbo"
    var gptPrompt: String = DEFAULT_PROMPT

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // TFLite interpreter (lazy — caricato solo se il file esiste)
    private var tfliteInterpreter: Any? = null   // org.tensorflow.lite.Interpreter
    private var tfliteLoaded = false

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        tryLoadTflite()
    }

    private fun tryLoadTflite() {
        try {
            // Riflette su TFLite per evitare hard-dependency a compile time.
            // Se la dipendenza non è nel gradle, semplicemente non si carica.
            val assets = context.assets
            val modelFiles = assets.list("") ?: emptyArray()
            if (modelFiles.none { it.contains("yolo") || it.contains("tflite") }) {
                Log.i(TAG, "No TFLite model found in assets. GPT fallback will be used.")
                return
            }
            // Esempio con reflection — rimuovi la reflection se hai la dipendenza diretta:
            // val modelFile = FileUtil.loadMappedFile(context, "yolov8n.tflite")
            // tfliteInterpreter = Interpreter(modelFile, Interpreter.Options().apply { numThreads = 4 })
            Log.i(TAG, "TFLite model found, attempting load...")
            tfliteLoaded = true // placeholder
        } catch (e: Exception) {
            Log.w(TAG, "TFLite not available: ${e.message}")
        }
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Rileva oggetti in [bitmap] e consegna i risultati a [callback].
     * Usa YOLO locale se disponibile, altrimenti GPT-4o Vision.
     */
    fun detect(bitmap: Bitmap, callback: DetectionCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            val boxes = if (tfliteLoaded && tfliteInterpreter != null) {
                runYoloLocal(bitmap)
            } else if (useGptFallback && openAiApiKey.isNotEmpty()) {
                runGptVision(bitmap)
            } else {
                Log.w(TAG, "No detector available. Return mock boxes for testing.")
                mockDetections(bitmap)
            }
            withContext(Dispatchers.Main) {
                callback.onDetections(boxes, bitmap.width, bitmap.height)
            }
        }
    }

    // ── YOLO locale (TFLite YOLOv8n) ─────────────────────────────────────────

    /**
     * Esegue YOLOv8n float32 tramite TFLite.
     * Input:  [1, 640, 640, 3]  float32
     * Output: [1, 84, 8400]     float32  (4 box coords + 80 classi COCO)
     *
     * Per YOLOv5/v8 con tflite-support usa ImageProcessor + TensorImage.
     */
    @Suppress("UNCHECKED_CAST")
    private fun runYoloLocal(bitmap: Bitmap): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        try {
            // ─── Preprocessing ───────────────────────────────────────────────
            val inputSize = 640
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val pixel = resized.getPixel(x, y)
                    inputBuffer[0][y][x][0] = ((pixel shr 16) and 0xFF) / 255f
                    inputBuffer[0][y][x][1] = ((pixel shr 8)  and 0xFF) / 255f
                    inputBuffer[0][y][x][2] = (pixel          and 0xFF) / 255f
                }
            }

            // ─── Inferenza ───────────────────────────────────────────────────
            // Output YOLOv8: [1, 84, 8400]
            val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } }
            // tfliteInterpreter.run(inputBuffer, outputBuffer)  // decommentare con TFLite

            // ─── Post-processing: NMS ─────────────────────────────────────────
            // Ogni colonna di outputBuffer[0] è un prediction:
            //   [0-3]: cx, cy, w, h (normalizzati)
            //   [4-83]: score per classe COCO
            val scaleX = bitmap.width.toFloat()  / inputSize
            val scaleY = bitmap.height.toFloat() / inputSize

            for (i in 0 until 8400) {
                val cx = outputBuffer[0][0][i]
                val cy = outputBuffer[0][1][i]
                val w  = outputBuffer[0][2][i]
                val h  = outputBuffer[0][3][i]

                var maxScore = 0f
                var maxClass = 0
                for (c in 4 until 84) {
                    val s = outputBuffer[0][c][i]
                    if (s > maxScore) { maxScore = s; maxClass = c - 4 }
                }

                if (maxScore >= confidenceThreshold) {
                    val left   = ((cx - w / 2) * scaleX).coerceIn(0f, bitmap.width.toFloat())
                    val top    = ((cy - h / 2) * scaleY).coerceIn(0f, bitmap.height.toFloat())
                    val right  = ((cx + w / 2) * scaleX).coerceIn(0f, bitmap.width.toFloat())
                    val bottom = ((cy + h / 2) * scaleY).coerceIn(0f, bitmap.height.toFloat())

                    boxes.add(BoundingBox(
                        label  = COCO_LABELS.getOrElse(maxClass) { "class_$maxClass" },
                        score  = maxScore,
                        rect   = RectF(left / bitmap.width, top / bitmap.height,
                            right / bitmap.width, bottom / bitmap.height),
                        source = "yolo_local"
                    ))
                }
            }
            Log.d(TAG, "YOLO detected ${boxes.size} objects")
        } catch (e: Exception) {
            Log.e(TAG, "YOLO error: ${e.message}", e)
        }
        return nonMaxSuppression(boxes, iouThreshold = 0.45f)
    }

    // ── GPT-4o Vision ─────────────────────────────────────────────────────────

    /**
     * Invia il frame a GPT-4o Vision e parsa le bounding box dalla risposta JSON.
     *
     * Il prompt chiede a GPT di rispondere SOLO con JSON:
     * { "objects": [ {"label":"...", "score":0.9, "cx":0.5, "cy":0.4, "w":0.2, "h":0.3} ] }
     *
     * cx, cy, w, h sono normalizzati [0,1] rispetto alle dimensioni dell'immagine.
     */
    private fun runGptVision(bitmap: Bitmap): List<BoundingBox> {
        return try {
            val base64Image = bitmap.toBase64Jpeg(quality = 70)
            val requestBody = buildGptRequestBody(base64Image)
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $openAiApiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "GPT API error: ${response.code} ${response.message}")
                return emptyList()
            }

            val responseText = response.body?.string() ?: return emptyList()
            parseGptResponse(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "GPT Vision error: ${e.message}", e)
            emptyList()
        }
    }

    private fun buildGptRequestBody(base64Image: String): String {
        val json = JSONObject().apply {
            put("model", gptModel)
            put("max_tokens", 512)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", gptPrompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                                put("detail", "low")   // "low" = più veloce e meno token
                            })
                        })
                    })
                })
            })
        }
        return json.toString()
    }

    private fun parseGptResponse(responseText: String): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        try {
            val root    = JSONObject(responseText)
            val content = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // Rimuovi markdown se GPT aggiunge ```json ... ```
            val clean = content.removePrefix("```json").removeSuffix("```").trim()
            val parsed = JSONObject(clean)
            val objects = parsed.getJSONArray("objects")

            for (i in 0 until objects.length()) {
                val obj   = objects.getJSONObject(i)
                val label = obj.getString("label")
                val score = obj.optDouble("score", 0.9).toFloat()
                val cx    = obj.getDouble("cx").toFloat()
                val cy    = obj.getDouble("cy").toFloat()
                val w     = obj.getDouble("w").toFloat()
                val h     = obj.getDouble("h").toFloat()

                boxes.add(BoundingBox(
                    label  = label,
                    score  = score,
                    rect   = RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2),
                    source = "gpt_vision"
                ))
            }
            Log.d(TAG, "GPT Vision detected ${boxes.size} objects")
        } catch (e: Exception) {
            Log.e(TAG, "GPT response parse error: ${e.message}\nResponse: $responseText")
        }
        return boxes
    }

    // ── Mock (test senza robot) ───────────────────────────────────────────────

    private fun mockDetections(bitmap: Bitmap): List<BoundingBox> {
        Log.d(TAG, "Using mock detections (no detector configured)")
        return listOf(
            BoundingBox("person", 0.91f, RectF(0.35f, 0.1f, 0.65f, 0.9f), source = "mock"),
            BoundingBox("chair",  0.78f, RectF(0.7f,  0.4f, 0.95f, 0.85f), source = "mock")
        )
    }

    // ── NMS ──────────────────────────────────────────────────────────────────

    private fun nonMaxSuppression(boxes: List<BoundingBox>, iouThreshold: Float): List<BoundingBox> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<BoundingBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best.rect, it.rect) > iouThreshold }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left, b.left)
        val interTop    = maxOf(a.top, b.top)
        val interRight  = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea   = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        val unionArea   = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun Bitmap.toBase64Jpeg(quality: Int = 80): String {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    // ── Costanti ──────────────────────────────────────────────────────────────

    companion object {
        private const val DEFAULT_PROMPT = """
Analyze this image from a robot camera and detect all visible objects.
Respond ONLY with a valid JSON object (no markdown, no extra text):
{
  "objects": [
    {"label": "person", "score": 0.95, "cx": 0.50, "cy": 0.45, "w": 0.30, "h": 0.80},
    {"label": "chair",  "score": 0.88, "cx": 0.75, "cy": 0.60, "w": 0.20, "h": 0.40}
  ]
}
cx, cy, w, h are normalized coordinates in [0,1] relative to image width/height.
cx and cy are the center of the bounding box.
"""

        // 80 classi COCO standard (YOLOv8)
        val COCO_LABELS = listOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
            "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
            "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
            "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
            "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
            "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
            "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
            "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse",
            "remote","keyboard","cell phone","microwave","oven","toaster","sink",
            "refrigerator","book","clock","vase","scissors","teddy bear","hair drier",
            "toothbrush"
        )
    }
}