package com.example.mypepperapplication.conversation

import com.example.mypepperapplication.core.AppConfig
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
/**
 * Gestisce il ciclo di conversazione, l'interazione vocale e l'integrazione con l'LLM per il robot Pepper.
 *
 * La classe coordina i flussi di Input/Output audio e la comprensione del linguaggio naturale:
 * - **Cattura Audio & Riconoscimento Vocale (STT)**: Registra l'audio dal microfono a 16kHz applicando
 *   la cancellazione del rumore ([NoiseSuppressor]) e la calibrazione dinamica della soglia d'ascolto.
 *   Pezzi d'audio formattati in WAV vengono inviati ai servizi Microsoft Azure Speech per la trascrizione in testo.
 * - **Intercezione Comandi Vocali (Intent Recognition)**: Analizza il testo dell'utente per identificare
 *   comandi di movimento/navigazione prima di consultare l'LLM. Gestisce keyword per:
 *   - Movimento e Inseguimento (`CMD_FOLLOW`, `CMD_STOP`, `CMD_APPROACH`).
 *   - Gestione Mappe (`CMD_START_MAP`, `CMD_STOP_MAP`, `CMD_LOAD_MAP`).
 *   - Navigazione Punti di Interesse (`save_poi` e `goto_poi`).
 *   - Visual Servoing / Ricerca Oggetti (estrazione etichette YOLO tramite l'endpoint `/extract_label`).
 * - **Integrazione LLM Backend**: Invia i messaggi dell'utente e lo storico conversazionale ([DialogueState])
 *   al server LLM esterno (porta HTTP POST `/chat`) per generare risposte naturali.
 * - **Sintesi Vocale (TTS)**: Convertitore Text-to-Speech nativo basato su QiSDK ([SayBuilder]) con
 *   supporto per la regolazione di velocità, tono, localizzazione e gestione della coda di feedback per azioni in corso.
 *
 * @property context Il contesto Android per le autorizzazioni audio e la gestione dei file temporanei.
 * @property qiContext Il contesto QiSDK del robot per l'esecuzione del TTS nativo.
 * @property azureKey La chiave di sottoscrizione per i Microsoft Cognitive Speech Services.
 * @property serverIp L'indirizzo IP del server Python backend per LLM ed estrazione etichette.
 * @property serverPort La porta del server backend (default 8000).
 * @property language Codice lingua della conversazione -> ITALIANO.
 * @property voiceSpeed Velocità di sintesi vocale QiSDK (default 100).
 * @property voicePitch Tono/Altezza di sintesi vocale QiSDK (default 100).
 */

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
private const val SPEECH_TAIL_MS = 250L   // coda audio del TTS dopo il ritorno di speak()
// Motion command labels — used by RobotManager to dispatch actions
const val CMD_FOLLOW  = "follow"
const val CMD_STOP    = "stop"
const val CMD_APPROACH = "approach"
const val CMD_START_MAP = "start_map"
const val CMD_STOP_MAP  = "stop_map"
const val CMD_LOAD_MAP  = "load_map"
const val CMD_FIND_PERSON = "find_person"
class ConversationController(
    private val context: Context,
    private val qiContext: QiContext,
    private val azureKey: String,
    private val serverIp: String,
    private val serverPort: Int = AppConfig.SERVER_PORT,
    private val language: String = "it-IT",
    private val voiceSpeed: Int = 100,
    private val voicePitch: Int = 100
) {
    private val dialogueState     = DialogueState() //cronologia conversazione
    private val sentenceGenerator = SentenceGenerator()
    private val saveTriggers = listOf("salva", "ricorda questo come", "chiama questo")
    private val httpClient = OkHttpClient.Builder() //comunicazione con server python
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var speechDetectionThreshold = 2000
    @Volatile var isRunning = false
        private set
    @Volatile private var isSpeaking = false
    private val speechMutex = Mutex()
    private val bufferSize = 2 * AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    )
    private fun sanitizeName(raw: String): String =
        raw.trim().trimEnd('.', ',', '!', '?', ';', ':').trim()

    // ── UI callbacks ──────────────────────────────────────────────────────
    var onListening:    (() -> Unit)?       = null
    var onThinking:     (() -> Unit)?       = null
    var onUserSpeech:   ((String) -> Unit)? = null
    var onRobotSpeech:  ((String) -> Unit)? = null
    var knownPoiNames: List<String> = emptyList()
    var isAwaitingTask: () -> Boolean = { false }

    // ── Motion callback — the ONLY bridge to RobotManager ────────────────
    // RobotManager sets this to handle CMD_FOLLOW / CMD_STOP / CMD_APPROACH
    var onMotionCommand: ((String) -> Unit)? = null
    // ── Motion keyword table (ITALIANO) ────────────────────────────────────
    private val motionCommands: Map<String, List<String>> = mapOf(
        CMD_FOLLOW    to listOf("seguimi", "vieni con me", "andiamo insieme", "segui"),
        CMD_STOP      to listOf("fermati", "aspetta", "ferma", "stai fermo", "un attimo"),
        CMD_APPROACH  to listOf("vieni qui", "vieni più vicino", "avvicinati", "vieni da me"),
        CMD_START_MAP to listOf("inizia la mappa", "crea la mappa", "inizia a mappare"),
        CMD_STOP_MAP  to listOf("ferma la mappa", "finisci la mappa", "termina la mappa"),
        CMD_LOAD_MAP  to listOf("carica la mappa", "ripristina la mappa", "carica mappa")
    )
    // Confirmation phrases Pepper says before executing the command
    private val motionReplies: Map<String, String> = mapOf(
        CMD_FOLLOW   to "Va bene, ti seguo!",
        CMD_STOP     to "Ok, mi fermo.",
        CMD_APPROACH to "Certo, mi avvicino!"
    )
    private val goToTriggers = listOf(
        "portami", "accompagnami", "vai a", "vai in", "vai verso",
        "va a", "va in", "va verso", "andare", "andiamo", "torna",
        "raggiungi", "spostati", "muoviti verso", "muoviti a", "muoviti in",
        "recati a", "recati in"
    )
    // Preposizioni e articoli che seguono i trigger di navigazione.
    private val leadingParticles = listOf(
        "allo", "alla", "agli", "alle", "nello", "nella", "negli", "nelle",
        "dello", "della", "degli", "delle", "sullo", "sulla",
        "nel", "del", "sul", "ai", "al", "in", "a", "da", "verso",
        "il", "lo", "la", "i", "gli", "le", "l'", "un", "una"
    )

    private fun stripLeadingParticles(raw: String): String {
        var s = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in leadingParticles) {
                if (s.startsWith("$p ", ignoreCase = true)) {
                    s = s.substring(p.length).trim(); changed = true; break
                }
                if (p.endsWith("'") && s.startsWith(p, ignoreCase = true)) {
                    s = s.substring(p.length).trim(); changed = true; break
                }
            }
        }
        return s
    }
    // ── Exit keywords ─────────────────────────────────────────────────────
    private val exitPhrases = listOf("arrivederci", "ciao ciao", "a presto", "è tutto", "basta così")
    /*
    - isTrackIntent() serve per controllare se la frase contiene le keyword di ricerca
    - extractLabel() invece chiama /extract_label e ottiene il label YOLO della frase
     */
    private val trackTriggers = listOf(
        "trova", "cerca", "dov'è", "dove è", "dove sono", "cercami", "guarda dove"
    )
    private fun isTrackIntent(sentence: String): Boolean =
        trackTriggers.any { sentence.lowercase().contains(it) }
    private val declinePhrases = setOf(
        "no", "no grazie", "niente", "nulla", "niente grazie",
        "sto bene", "va bene cosi", "va bene così", "tutto a posto", "no no"
    )
    private fun isDecline(s: String): Boolean =
        s.trim().lowercase().trimEnd('.', '!', '?') in declinePhrases
    // Estrae il nome del POI da salvare identificando il trigger iniziale più lungo.
    // Rimuove il prefisso trovato e restituisce il resto del testo pulito, oppure null se vuoto.
    private fun extractSavePoiName(sentence: String): String? {
        val lower = sentence.lowercase()
        val trigger = saveTriggers.filter { lower.startsWith("$it ") }.maxByOrNull { it.length } ?: return null
        val name = stripLeadingParticles(sanitizeName(sentence.substring(trigger.length)))
        return name.ifBlank { null }
    }

    // Estrae il nome di un POI dalla frase individuando la parola chiave di avvio più lunga e poi isola
    // il testo successivo al trigger, lo pulisce con `sanitizeName` e lo restituisce [se non vuoto].
    private fun extractGoToPoiName(sentence: String): String? {
        val lower = sentence.lowercase()
        val trigger = goToTriggers
            .filter { lower.contains(it) }
            .maxByOrNull { it.length } ?: return null
        val idx = lower.indexOf(trigger)
        val afterTrigger = sentence.substring(idx + trigger.length)
        val name = stripLeadingParticles(sanitizeName(afterTrigger))
        return name.ifBlank { null }
    }
    // comunica con il server per ottenere la label dell oggetto ed estrarla dalla frase
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
    /**
     * Gestisce il ciclo principale di conversazione: calibra l'audio, invia i saluti iniziali e processa
     * in continuo l'input vocale per intercettare comandi locali (uscita, movimento, POI, tracking)
     * prima di ricorrere al server per la risposta generica.
     */
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
            // 1b. Il planner sta aspettando la risposta a speak_ask_task.
            // Qui la frase e' una RISPOSTA, non un comando, e non ne ha la forma:
            // isTrackIntent fallirebbe su "la bottiglia" e /extract_label non
            // verrebbe mai chiamata. Bypass di tutti i controlli sui comandi,
            // tranne "fermati" che resta la via d'uscita.
            if (isAwaitingTask()) {
                if (matchMotionCommand(userSentence) == CMD_STOP) {
                    onMotionCommand?.invoke(CMD_STOP)
                    continue
                }
                if (isDecline(userSentence)) {
                    val reply = "Va bene, sono qui se ti serve."
                    onRobotSpeech?.invoke(reply); sayMessage(reply)
                    onMotionCommand?.invoke("decline_task")
                    continue
                }
                val label = extractLabel(userSentence)
                if (label == "none") {
                    val reply = "Non ho capito cosa cerchi. Puoi dirmi il nome dell'oggetto?"
                    onRobotSpeech?.invoke(reply); sayMessage(reply)
                    continue   // l'executor aspetta ancora: si riprova nello stesso timeout
                }
                val reply = "Va bene, cerco ${LabelIt.of(label)}."
                onRobotSpeech?.invoke(reply); sayMessage(reply)
                onMotionCommand?.invoke("track:$label")
                continue
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

            extractGoToPoiName(userSentence)?.let { rawName ->
                val match = knownPoiNames.firstOrNull { it.equals(rawName, ignoreCase = true) }
                    ?: knownPoiNames.firstOrNull { it.contains(rawName, ignoreCase = true) || rawName.contains(it, ignoreCase = true) }
                Log.i(TAG, "GoTo intent: rawName='$rawName', knownPoiNames=$knownPoiNames, match=$match")
                if (match != null) {
                    onMotionCommand?.invoke("goto_poi:$match")
                } else {
                    val reply = "Non conosco un posto che si chiama $rawName. Prova a dirlo in un altro modo."
                    onRobotSpeech?.invoke(reply)
                    sayMessage(reply)
                }
                continue
            }

            // 3. Track object intent
            if (isTrackIntent(userSentence)) {
                val label = extractLabel(userSentence)
                if (label == "person") {
                    // "trova una persona/l'umano" → usa FindPersonController, più rapido e affidabile
                    // del visual servoing YOLO per un target generico come "person"
                    val reply = "OK, cerco una persona!"
                    onRobotSpeech?.invoke(reply)
                    sayMessage(reply)
                    onMotionCommand?.invoke("find_person")
                    continue
                } else if (label != "none") {
                    val reply = "Va bene, cerco ${LabelIt.of(label)}!"
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
    // ─────────────────────────────────────────────────────────────────────
    // Motion command matching
    // ─────────────────────────────────────────────────────────────────────

    // cerca una sentence all interno di una mappa di comandi e le relative keywords, restituisce il comando
    // associato alla parola chiave piu lunga che trova nela frase
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
    /**
     * Calibra la soglia di rilevamento vocale (`speechDetectionThreshold`) misurando il rumore ambientale.
     * 1. Verifica i permessi audio (`RECORD_AUDIO`).
     * 2. Inizializza l'istanza `AudioRecord` e legge un singolo buffer di dati audio.
     * 3. Calcola il picco massimo di ampiezza presente nel rumore di fondo (`bg`).
     * 4. Imposta la nuova soglia sommando a questo valore un margine di offset (`THRESHOLD_ADJUSTMENT`).
     * 5. Libera correttamente le risorse hardware dell'hardware audio nel blocco `finally`.
     */
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
    /**
     * Registra l'audio dal microfono, rileva il parlato tramite soglia di ampiezza e trascrive il testo in modo asincrono.
     * 1. Configura `AudioRecord` e abilita il soppressore di rumore hardware (`NoiseSuppressor`), se disponibile sul dispositivo.
     * 2. Monitora in continuo l'ampiezza dell'audio; se supera `speechDetectionThreshold`, accumula i byte nello stream.
     * 3. Se il robot sta parlando (`isSpeaking`), resetta il buffer per evitare di ascoltare la propria voce (echo suppression).
     * 4. Gestisce il parlato a blocchi (chunking): dopo una pausa breve (`SHORT_SILENCE_MS`), invia il blocco accumulato a `recognizeChunk`
     *    lanciando una coroutine parallela per non interrompere la registrazione.
     * 5. Termina la registrazione al superamento di un silenzio prolungato (`LONG_SILENCE_MS`) o del timeout iniziale (`INITIAL_TIMEOUT_MS`).
     * 6. Nel blocco `finally` rilascia le risorse audio hardware, ricompone tutti i frammenti trascritti ordinandoli per indice
     *    e restituisce la frase completa formattata.
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
    /**
     * Converte un array di byte audio in testo tramite il servizio Microsoft Azure Speech Recognition.
     * 1. Salva temporaneamente i byte audio ricevuti come file WAV nella cache dell'app (`context.cacheDir`).
     * 2. Inizializza il client Azure Speech SDK (`SpeechConfig`, `AudioConfig`, `SpeechRecognizer`) impostando la lingua ("en-US")
     *    e disabilitando i controlli CRL di OpenSSL per evitare problemi di certificati HTTPS/TLS.
     * 3. Esegue la trascrizione sincrona del singolo frammento audio tramite `recognizeOnceAsync().get()`.
     * 4. Gestisce l'esito della richiesta (riconoscimento riuscito, nessun match o errore/cancellazione).
     * 5. Libera le risorse dell'SDK e cancella il file WAV temporaneo in un thread separato nel blocco `finally`
     *    per evitare di bloccare il thread chiamante durante la pulizia.
     */
    private fun recognizeChunk(audioBytes: ByteArray): String {
        if (audioBytes.isEmpty()) return ""

        val tempFile = File.createTempFile("chunk", ".wav", context.cacheDir)
        writeWavFile(audioBytes, tempFile)

        val speechConfig = SpeechConfig.fromSubscription(azureKey, AZURE_REGION).apply {
            speechRecognitionLanguage = language
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
    /**
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
    suspend fun sayMessage(text: String, isActionFeedback: Boolean = false) {
        speechMutex.withLock {
            withContext(Dispatchers.IO) {
                isSpeaking = true
                try {
                    speak(text)
                    delay(SPEECH_TAIL_MS)
                } finally {
                    isSpeaking = false
                }
            }
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
    /**
     * Converte un array di campioni audio a 16-bit (`ShortArray`) in un array di byte (`ByteArray`) in formato Little-Endian.
     *
     * Nello specifico:
     * Alloca un array di dimensione doppia (ogni Short richiede 2 byte) ed estrae il byte meno significativo (LSB)
     * e quello più significativo (MSB) tramite operazioni bitwise (`and 0x00FF` e shift a destra `shr 8`).
     */
    private fun shortsToBytes(sData: ShortArray): ByteArray {
        val bytes = ByteArray(sData.size * 2)
        for (i in sData.indices) {
            bytes[i * 2]     = (sData[i].toInt() and 0x00FF).toByte()
            bytes[i * 2 + 1] = (sData[i].toInt() shr 8).toByte()
        }
        return bytes
    }
    /**
     * Incapsula i dati PCM raw di un array di byte (`audioBytes`) all'interno di un file formattato WAV RIFF a 16-bit mono.
     * 1. Apre uno stream di scrittura (`FileOutputStream`) gestito tramite `use` per la chiusura automatica delle risorse.
     * 2. Scrive l'intestazione standard di 44 byte dell'header RIFF/WAVE (chunk `fmt` a 16 bit PCM mono e chunk `data`).
     * 3. Calcola dinamica il valore di `byteRate` in base al `SAMPLE_RATE` e aggiorna le dimensioni del file e dei blocchi audio.
     * 4. Scrivi infine i byte audio grezzi (`audioBytes`) in coda all'header.
     */
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

    // Converte un intero a 32-bit (`Int`) in un array di 4 byte disposti in ordine Little-Endian
    // (byte meno significativo viene inserito in fondo nella memoria)
    private fun intToBytes(v: Int) = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
    )

    // converte un valore intero rappresentante uno Short (16-bit) in un
    // array di 2 byte in ordine Little-Endian.
    private fun shortToBytes(v: Int) = byteArrayOf(
        v.toByte(), (v shr 8).toByte()
    )
}