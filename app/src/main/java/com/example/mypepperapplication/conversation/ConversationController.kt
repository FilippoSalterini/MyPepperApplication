package com.example.mypepperapplication.conversation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.Phrase
import com.aldebaran.qi.sdk.`object`.locale.Language
import com.aldebaran.qi.sdk.`object`.locale.Locale
import com.aldebaran.qi.sdk.`object`.locale.Region
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.microsoft.cognitiveservices.speech.*
import com.microsoft.cognitiveservices.speech.audio.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "ConversationController"

private const val SAMPLE_RATE       = 16000 // indica microfono registra a 16kHz
private const val AUDIO_SOURCE      = MediaRecorder.AudioSource.MIC
private const val CHANNEL_CONFIG    = AudioFormat.CHANNEL_IN_MONO // un solo canale audio
private const val AUDIO_FORMAT      = AudioFormat.ENCODING_PCM_16BIT //campioni a 16bit
private const val THRESHOLD_ADJUSTMENT = 2000
private const val SHORT_SILENCE_MS  = 400L //dopo 400ms di silence, inizia la richiesta
private const val LONG_SILENCE_MS   = 2500L //dopo 2.5s considera terminata tutta la frase
private const val INITIAL_TIMEOUT_MS = 60_000L
private const val AZURE_REGION      = "westeurope"

// Motion command labels — used by RobotManager to dispatch actions
const val CMD_FOLLOW  = "follow"
const val CMD_STOP    = "stop"
const val CMD_APPROACH = "approach"
const val CMD_START_MAP = "start_map"
const val CMD_STOP_MAP  = "stop_map"
const val CMD_LOAD_MAP  = "load_map"

class ConversationController(
    private val context: Context,
    private val qiContext: QiContext,
    private val azureKey: String,
    private val serverIp: String,
    private val serverPort: Int = 8000,
    private val language: String = "en-US",
    private val voiceSpeed: Int = 100,
    private val voicePitch: Int = 100
) {
    private val dialogueState     = DialogueState() //cronologia conversazione
    private val sentenceGenerator = SentenceGenerator()

    private val httpClient = OkHttpClient.Builder() //comunicazione con server python
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var speechDetectionThreshold = 2000

    @Volatile var isRunning = false
        private set
    @Volatile private var isSpeaking = false
    private var pendingFeedback: String? = null
    private val bufferSize = 2 * AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    )

    // ── UI callbacks ──────────────────────────────────────────────────────
    var onListening:    (() -> Unit)?       = null
    var onThinking:     (() -> Unit)?       = null
    var onUserSpeech:   ((String) -> Unit)? = null
    var onRobotSpeech:  ((String) -> Unit)? = null

    // ── Motion callback — the ONLY bridge to RobotManager ────────────────
    // RobotManager sets this to handle CMD_FOLLOW / CMD_STOP / CMD_APPROACH
    var onMotionCommand: ((String) -> Unit)? = null

    // ── Motion keyword table (English) ────────────────────────────────────
    private val motionCommands: Map<String, List<String>> = mapOf(
        CMD_FOLLOW    to listOf("follow me", "come with me", "come along", "follow"),
        CMD_STOP      to listOf("stop", "wait", "stay", "hold on", "stand still"),
        CMD_APPROACH  to listOf("come here", "come closer", "get closer", "approach me"),
        CMD_START_MAP to listOf("start mapping", "start the map"),
        CMD_STOP_MAP  to listOf("stop mapping", "finish mapping", "stop the mapping"),
        CMD_LOAD_MAP  to listOf("restore map", "load map", "load the map", "restore the map")
    )

    // Confirmation phrases Pepper says before executing the command
    private val motionReplies: Map<String, String> = mapOf(
        CMD_FOLLOW   to "Ok, I will follow you!",
        CMD_STOP     to "Ok, stopping.",
        CMD_APPROACH to "Sure, coming closer!"
    )

    // ── Exit keywords ─────────────────────────────────────────────────────
    private val exitPhrases = listOf("goodbye", "bye", "see you", "that's all")
/*
- isTrackIntent() serve per controllare se la frase contiene le keyword di ricerca
- extractLabel() invece chiama /extract_label e ottiene il label YOLO della frase
 */
    private val trackTriggers = listOf(
        "find", "look for", "where is", "where are",
        "search", "track", "locate", "get me"
    )

    private fun isTrackIntent(sentence: String): Boolean =
        trackTriggers.any { sentence.lowercase().contains(it) }

    private fun extractSavePoiName(sentence: String): String? {
        val lower = sentence.lowercase()
        return if (lower.startsWith("save ")) lower.removePrefix("save ").trim() else null
    }

    private fun extractGoToPoiName(sentence: String): String? {
        val lower = sentence.lowercase()
        return if (lower.startsWith("go to")) lower.removePrefix("go to").trim() else null
    }

    private suspend fun extractLabel(sentence: String): String = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("message", sentence)
                put("history", JSONArray())
            }.toString()
            val response = httpClient.newCall(
                Request.Builder()
                    .url("http://$serverIp:$serverPort/extract_label")
                    .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
            ).execute()
            JSONObject(response.body!!.string()).getString("label")
        } catch (e: Exception) {
            Log.e(TAG, "extractLabel error: ${e.message}")
            "none"
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Main loop
    // ─────────────────────────────────────────────────────────────────────

    suspend fun startConversationLoop() {
        isRunning = true
        Log.i(TAG, "Starting conversation loop")
        // calibrate_threshold serve per misurare il rumore ambientale
        withContext(Dispatchers.IO) { calibrateThreshold() }

        val welcome = sentenceGenerator.getPredefinedSentence(language, "welcome_back")
        onRobotSpeech?.invoke(welcome)
        sayMessage(welcome)

        while (currentCoroutineContext().isActive && isRunning) {

            onListening?.invoke()

            val userSentence = listenAndRecognize()
            if (userSentence.isBlank()) continue

            Log.i(TAG, "User said: [$userSentence]")
            onUserSpeech?.invoke(userSentence)

            // 1. Exit check
            if (exitPhrases.any { userSentence.contains(it, ignoreCase = true) }) {
                val goodbye = sentenceGenerator.getPredefinedSentence(language, "goodbye")
                onRobotSpeech?.invoke(goodbye)
                sayMessage(goodbye)
                isRunning = false
                break
            }

            // 2. Motion command check — intercepts BEFORE hitting the server
            val matchedCmd = matchMotionCommand(userSentence)
            if (matchedCmd != null) {
                val reply = motionReplies[matchedCmd] ?: "Ok!"
                onRobotSpeech?.invoke(reply)
                sayMessage(reply)
                onMotionCommand?.invoke(matchedCmd)   // RobotManager handles the rest
                continue
            }

            // 2b. Save/GoTo PoI intent — parametrized, stesso pattern di track:$label più sotto
            extractSavePoiName(userSentence)?.let { name ->
                onMotionCommand?.invoke("save_poi:$name")
                continue
            }
            extractGoToPoiName(userSentence)?.let { name ->
                onMotionCommand?.invoke("goto_poi:$name")
                continue
            }

            // 3. Track object intent
            if (isTrackIntent(userSentence)) {
                val label = extractLabel(userSentence)
                if (label != "none") {
                    val reply = "Ok, I'll look for the $label!"
                    onRobotSpeech?.invoke(reply)
                    sayMessage(reply)
                    onMotionCommand?.invoke("track:$label")
                    continue
                }
            }
            // 4. Normal conversational turn → server
            // Snapshot history BEFORE adding user message, so server receives
            // only the prior context (not the message it's supposed to reply to)
            val historySnapshot = dialogueState.conversationHistory.toList()
            dialogueState.updateConversation("user", userSentence)

            onThinking?.invoke()
            val reply = chatRequest(userSentence, historySnapshot)
            Log.i(TAG, "Robot reply: [$reply]")

            dialogueState.updateConversation("assistant", reply)
            onRobotSpeech?.invoke(reply)
            sayMessage(reply)
        }

        Log.i(TAG, "Conversation loop ended")
        isRunning = false
    }

    fun stop() {
        isRunning = false
    }

    fun resetHistory() {
        dialogueState.resetConversation()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Motion command matching
    // ─────────────────────────────────────────────────────────────────────

    private fun matchMotionCommand(sentence: String): String? {
        val lower = sentence.lowercase()
        return motionCommands.entries
            .flatMap { (cmd, keywords) -> keywords.filter { lower.contains(it) }.map { cmd to it } }
            .maxByOrNull { it.second.length }
            ?.first
    }

    // ─────────────────────────────────────────────────────────────────────
    // Threshold calibration
    // ─────────────────────────────────────────────────────────────────────

    private fun calibrateThreshold() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val audioRecord = AudioRecord(
            AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )
        val buffer = ShortArray(bufferSize)
        try {
            audioRecord.startRecording()
            audioRecord.read(buffer, 0, bufferSize)
            val bg = buffer.maxOrNull()?.toInt() ?: 0
            speechDetectionThreshold = bg + THRESHOLD_ADJUSTMENT
            Log.i(TAG, "Threshold calibrated: $speechDetectionThreshold (bg=$bg)")
        } catch (e: Exception) {
            Log.e(TAG, "Calibration error: ${e.message}")
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Recording + Azure recognition
    // ─────────────────────────────────────────────────────────────────────
    /*
    Questa funziona svolge contemporanemante: registrazione audio, rilevazione quando
    si sta parlando, l audio viene diviso in blocchi e ogni blocco viene trasmesso ad azure
    RecognizeChunk restituisce il testo riconosciuto -> il codcie salva tmp il blocco audio
    in un file WAV (writewavfile()) siccome speechrecognizer viene configurato per leggere
    da un file WAV e poi crea :
    SpeechConfig
    AudioConfig
    SpeechRecognizer
    ed invoca recognizeOnceAsync() -> se il ricosnoscimento funziona recognizedSpeech restituisce
    il testo, se invece non trova corrispondenze o l operazione viene annullata, registra un messaggio
    di LOG -> alla fine chiude tutte le risorse e cancella il file tmp.
     */
    private suspend fun listenAndRecognize(): String = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission missing")
            return@withContext ""
        }

        val audioRecord = AudioRecord(
            AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )
        val noiseSuppressor = if (NoiseSuppressor.isAvailable())
            NoiseSuppressor.create(audioRecord.audioSessionId) else null

        val audioBuffer  = ShortArray(bufferSize)
        val byteStream   = ByteArrayOutputStream()
        var lastDetection: Long? = null
        val startTime    = System.currentTimeMillis()
        val results      = ConcurrentHashMap<Int, String>()
        var chunkIndex   = 0

        try {
            audioRecord.startRecording()

            // coroutineScope inherits cancellation from the parent — no leak
            coroutineScope {
                while (true) {
                    val now = System.currentTimeMillis()
                    if (now - startTime > INITIAL_TIMEOUT_MS) break

                    val ret = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                    if (ret < 0) { Log.e(TAG, "AudioRecord read error $ret"); break }

                    if (isSpeaking) {
                        byteStream.reset()
                        lastDetection = null
                        continue
                    }

                    val maxAmp = audioBuffer.maxOrNull()?.toInt() ?: 0
                    if (maxAmp > speechDetectionThreshold) {
                        lastDetection = now
                        byteStream.write(shortsToBytes(audioBuffer))
                    }

                    lastDetection?.let { det ->
                        if (now - det > SHORT_SILENCE_MS && byteStream.size() > 0) {
                            val audioBytes = byteStream.toByteArray()
                            byteStream.reset()
                            val index = chunkIndex++
                            launch(Dispatchers.Default) {
                                val text = recognizeChunk(audioBytes)
                                if (text.isNotEmpty()) results[index] = text
                            }
                        }
                        if (now - det > LONG_SILENCE_MS) {
                            Log.d(TAG, "Long silence — sentence complete")
                            return@coroutineScope
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}")
        } finally {
            audioRecord.stop()
            audioRecord.release()
            noiseSuppressor?.release()
        }

        results.toSortedMap().values.joinToString(" ").trim()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Single chunk recognition via Azure
    // ─────────────────────────────────────────────────────────────────────

    private fun recognizeChunk(audioBytes: ByteArray): String {
        if (audioBytes.isEmpty()) return ""

        val tempFile = File.createTempFile("chunk", ".wav", context.cacheDir)
        writeWavFile(audioBytes, tempFile)

        val speechConfig = SpeechConfig.fromSubscription(azureKey, AZURE_REGION).apply {
            speechRecognitionLanguage = "en-US"
            setProperty("OPENSSL_DISABLE_CRL_CHECK", "true")
        }
        val audioConfig = AudioConfig.fromWavFileInput(tempFile.absolutePath)
        val recognizer  = SpeechRecognizer(speechConfig, audioConfig)

        var text = ""
        try {
            val result = recognizer.recognizeOnceAsync().get()
            when (result.reason) {
                ResultReason.RecognizedSpeech -> {
                    text = result.text ?: ""
                    Log.d(TAG, "Chunk recognized: $text")
                }
                ResultReason.NoMatch  -> Log.w(TAG, "No match")
                ResultReason.Canceled -> {
                    val d = CancellationDetails.fromResult(result)
                    Log.e(TAG, "Canceled: ${d.reason} / ${d.errorDetails}")
                }
                else -> {}
            }
            result.close()
        } catch (e: Exception) {
            Log.e(TAG, "recognizeChunk error: ${e.message}")
        } finally {
            Thread {
                recognizer.close(); speechConfig.close()
                audioConfig.close(); tempFile.delete()
            }.start()
        }
        return text
    }

    // ─────────────────────────────────────────────────────────────────────
    // LLM request
    // ─────────────────────────────────────────────────────────────────────
    /*
    funzione chatRequest che serve per comnicare con il server LLM, crea un file
    JSON contenente il messaggio corrente e lo storico della cvorsazione
    --> invia una richuiesta HTTP POST all endpoint chat del server python, se server
    risponde correttamente allora HTTP 200 ed estrae il campo reply dal risposta json, SE server
    non dovesse essere raggiungibile o resisituisce un errore ippure usa una frase predefinita di default
     */
    private suspend fun chatRequest(
        userMessage: String,
        history: List<Map<String, String>>
    ): String = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("message", userMessage)
                put("history", JSONArray().apply {
                    history.forEach { put(JSONObject(it as Map<*, *>)) }
                })
            }.toString()

            val response = httpClient.newCall(
                Request.Builder()
                    .url("http://$serverIp:$serverPort/chat")
                    .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Chat request HTTP ${response.code}")
                return@withContext sentenceGenerator.getPredefinedSentence(language, "server_unavailable")
            }

            JSONObject(response.body!!.string()).getString("reply")

        } catch (e: Exception) {
            Log.e(TAG, "chatRequest error: ${e.message}")
            sentenceGenerator.getPredefinedSentence(language, "server_connection_error")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TTS via QiSDK
    // ─────────────────────────────────────────────────────────────────────

//    suspend fun sayMessage(text: String) = withContext(Dispatchers.IO) {
//        try {
//            isSpeaking = true
//            val locale = if (language == "en-US")
//                Locale(Language.ENGLISH, Region.UNITED_STATES)
//            else
//                Locale(Language.ITALIAN, Region.ITALY)
//
//            val phrase = Phrase("\\rspd=$voiceSpeed\\\\\\vct=$voicePitch\\\\$text")
//            SayBuilder.with(qiContext)
//                .withPhrase(phrase)
//                .withLocale(locale)
//                .build()
//                .run()
//        } catch (e: Exception) {
//            Log.e(TAG, "sayMessage error: ${e.message}")
//        } finally {
//            isSpeaking = false
//        }
//    }
    suspend fun sayMessage(text: String, isActionFeedback: Boolean = false) = withContext(Dispatchers.IO) {
        if (isSpeaking) {
            if (isActionFeedback) {
                // Non perdiamo feedback importanti — li accodiamo
                pendingFeedback = text
                Log.d(TAG, "Feedback queued: $text")
            }
            return@withContext
        }
        isSpeaking = true
        try {
            speak(text)
            // Dopo aver finito, controlla se c'è un feedback in attesa
            pendingFeedback?.let { pending ->
                pendingFeedback = null
                speak(pending)
            }
        } finally {
            isSpeaking = false
        }
    }

    private fun speak(text: String) {
        val locale = if (language == "en-US")
            Locale(Language.ENGLISH, Region.UNITED_STATES)
        else
            Locale(Language.ITALIAN, Region.ITALY)
        val phrase = Phrase("\\rspd=$voiceSpeed\\\\\\vct=$voicePitch\\\\$text")
        SayBuilder.with(qiContext).withPhrase(phrase).withLocale(locale).build().run()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Audio utilities
    // ─────────────────────────────────────────────────────────────────────

    private fun shortsToBytes(sData: ShortArray): ByteArray {
        val bytes = ByteArray(sData.size * 2)
        for (i in sData.indices) {
            bytes[i * 2]     = (sData[i].toInt() and 0x00FF).toByte()
            bytes[i * 2 + 1] = (sData[i].toInt() shr 8).toByte()
        }
        return bytes
    }

    private fun writeWavFile(audioBytes: ByteArray, file: File) {
        try {
            FileOutputStream(file).use { fos ->
                val byteRate = 16 * SAMPLE_RATE / 8
                fos.write("RIFF".toByteArray(Charsets.US_ASCII))
                fos.write(intToBytes(audioBytes.size + 36))
                fos.write("WAVE".toByteArray(Charsets.US_ASCII))
                fos.write("fmt ".toByteArray(Charsets.US_ASCII))
                fos.write(intToBytes(16))
                fos.write(shortToBytes(1))
                fos.write(shortToBytes(1))
                fos.write(intToBytes(SAMPLE_RATE))
                fos.write(intToBytes(byteRate))
                fos.write(shortToBytes(2))
                fos.write(shortToBytes(16))
                fos.write("data".toByteArray(Charsets.US_ASCII))
                fos.write(intToBytes(audioBytes.size))
                fos.write(audioBytes)
            }
        } catch (e: IOException) {
            Log.e(TAG, "writeWavFile error: ${e.message}")
        }
    }

    private fun intToBytes(v: Int) = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
    )

    private fun shortToBytes(v: Int) = byteArrayOf(
        v.toByte(), (v shr 8).toByte()
    )
}