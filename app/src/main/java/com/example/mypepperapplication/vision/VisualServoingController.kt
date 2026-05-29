package com.example.mypepperapplication.vision

import android.graphics.Bitmap
import android.util.Log
import com.example.mypepperapplication.controllers.PepperMovementController
import com.example.mypepperapplication.controllers.HeadMovementController
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.abs

// ===========================================================================
// VISUAL SERVOING CONTROLLER
// ===========================================================================

private const val TAG = "VisualServoing"

class VisualServoingController(
    private val movementController: PepperMovementController,
    private val headController:     HeadMovementController
) {

    interface VisualServoingListener {
        fun onObjectReached(label: String, box: BoundingBox)
        fun onObjectLost(labels: List<String>)
    }

    // Parametri DA CALIBRARE
    var targetArea:       Float  = 0.035f //CALIBRA PER AVVICINAMENTO A OGGETTO
    var kpRotation:       Float  = 0.8f
    var maxRotationStep:  Float  = 0.35f
    var bodyRotationZone: Float  = 0.12f
    var headOnlyZone:     Float  = 0.05f
    var lpfAlpha:         Float  = 0.5f
    var maxMissedFrames:  Int    = 10
    var cycleDelayMs:     Long   = 250L

    var centeredFrames = 0
    val centeredRequired = 3  // centrato per 3 frame di fila

    // Scan — sweep intelligente
    var scanStepRad:      Double = 0.25
    var scanSteps:        Int    = 14
    private val approachCorrectionZone = 0.25f

    // Stall detector
    private val stallThreshold = 0.003f
    private val stallMaxFrames = 6
    var scanDelayMs:        Long   = 300L

    var listener: VisualServoingListener? = null

    private var smoothErrX = 0f
    private var smoothErrY = 0f

    // Lo scope usa SupervisorJob per evitare che il crash di un singolo task tiri giù l'intero controller
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null

    fun onRobotReady() { Log.d(TAG, "Robot ready.") }
    fun onRobotLost()  {
        trackingJob?.cancel()
        trackingJob = null
    }

    fun startTracking(
        cameraController:    PepperCameraController,
        detectionController: ObjectDetectionController,
        labels:              List<String>
    ) {
        require(labels.isNotEmpty()) { "labels cannot be empty" }
        trackingJob?.cancel()
        smoothErrX = 0f
        smoothErrY = 0f
        centeredFrames = 0

        Log.i(TAG, "Visual servoing started. Targets=$labels")

        trackingJob = scope.launch {

            // ── FASE 0: SCAN ──────────────────────────────────────────────────
            Log.i(TAG, "PHASE 0 SCAN — looking for $labels over $scanSteps steps")
            val halfSteps = scanSteps / 2
            val angles = buildList {
                repeat(halfSteps) { add(scanStepRad + (Math.random() * 0.04 - 0.02)) }   // verso dx
                repeat(halfSteps) { add(-scanStepRad + (Math.random() * 0.04 - 0.02)) }  // verso sx
            }

            var found = false
            for ((idx, angle) in angles.withIndex()) {
                if (!isActive || found) break

                // FIX: Chiamata suspend corretta all'interno dello scope
                headController.stopGaze()
                movementController.rotateAwait(theta = angle)

                val scanErrY = when (idx % 3) {
                    0    -> -0.45f   // alto
                    1    -> -0.10f   // medio
                    else ->  0.20f   // basso
                }
                headController.setGaze(normErrX = 0f, normErrY = scanErrY, scanMode = true)
                delay(scanDelayMs)

                val bmp = captureFrame(cameraController)
                val hit = runDetection(detectionController, bmp).bestMatch(labels)

                if (hit != null) {
                    Log.i(TAG, "SCAN HIT [${hit.label}] score=${hit.score} idx=$idx")
                    headController.setGaze(normErrX = hit.cx - 0.5f, normErrY = hit.cy - 0.5f)
                    found = true
                }
            }

            if (!found) {
                Log.w(TAG, "SCAN complete — target not found")
                movementController.stopMovement()
                headController.resetHead() // FIX: Chiamata suspend nativa
                listener?.onObjectLost(labels)
                return@launch
            }

            // FASE 1 — centering
            var rotateStallCount = 0
            var lastRawErrX = 0f
            var nearZoneFrames = 0
            val nearZoneRequired = 4
            var missedFrames  = 0
            centeredFrames    = 0
            smoothErrX        = 0f
            smoothErrY        = 0f

            headController.stopGaze()
            //headController.setGaze(normErrX = 0f, normErrY = 0f)

            while (isActive) {
                val bitmap = captureFrame(cameraController)
                val target = runDetection(detectionController, bitmap).bestMatch(labels)

                if (target == null) {
                    missedFrames++
                    if (missedFrames >= maxMissedFrames) {
                        movementController.stopMovement()
                        headController.resetHead()
                        listener?.onObjectLost(labels)
                        return@launch
                    }
                    delay(cycleDelayMs)
                    continue
                }
                missedFrames = 0

                val rawErrX = target.cx - 0.5f
                val rawErrY = target.cy - 0.5f

                // LPF usato SOLO per la testa, non per decidere se ruotare
//                smoothErrX = lpfAlpha * rawErrX + (1f - lpfAlpha) * smoothErrX
//                smoothErrY = lpfAlpha * rawErrY + (1f - lpfAlpha) * smoothErrY

//                headController.setGaze(normErrX = smoothErrX, normErrY = smoothErrY)
                Log.d(TAG, "CENTER [${target.label}] rawErrX=%.3f smoothErrX=%.3f".format(rawErrX, smoothErrX))

                when {
                    // Decisione basata su RAW — non sul valore filtrato
                    abs(rawErrX) <= headOnlyZone -> {
                        nearZoneFrames = 0
                        centeredFrames++
                        Log.d(
                            TAG,
                            "CENTER OK frame $centeredFrames/$centeredRequired rawErrX=%.3f".format(
                                rawErrX
                            )
                        )
                        if (centeredFrames >= centeredRequired) {
                            Log.i(TAG, "PHASE 1 done — object centered")
                            headController.stopGaze()
                            break
                        }
                    }

                    abs(rawErrX) >= bodyRotationZone -> {
                        centeredFrames = 0
                        nearZoneFrames = 0

                        if (abs(rawErrX - lastRawErrX) < 0.01f) {
                            rotateStallCount++
                            Log.w(TAG, "ROTATE STALL $rotateStallCount rawErrX=%.3f".format(rawErrX))
                            if (rotateStallCount >= 4) {
                                Log.w(TAG, "ROTATE STALL MAX — forcing PHASE 2")
                                headController.stopGaze()
                                break
                            }
                        } else {
                            rotateStallCount = 0
                        }
                        lastRawErrX = rawErrX

                        val theta = (-kpRotation * rawErrX)
                            .coerceIn(-maxRotationStep, maxRotationStep).toDouble()
                        Log.i(TAG, "ROTATE theta=%.3f rawErrX=%.3f".format(theta, rawErrX))
                        headController.stopGaze()
                        movementController.rotateAwait(theta = theta, maxSpeed = 0.4f)
                        smoothErrX = 0f
                        smoothErrY = 0f
                    }

                    else -> {
                        val nearZoneMaxErr = bodyRotationZone * 0.85f

                        if (abs(rawErrX) > nearZoneMaxErr) {
                            centeredFrames = 0
                            nearZoneFrames = 0

                            val rawTheta = -kpRotation * rawErrX * 0.6f
                            val theta = if (abs(rawTheta) < 0.06f) {
                                if (rawTheta >= 0) 0.06 else -0.06
                            } else {
                                rawTheta.coerceIn(-maxRotationStep * 0.5f, maxRotationStep * 0.5f).toDouble()
                            }

                            Log.i(TAG, "NEAR-ZONE CORRECTION theta=%.3f rawErrX=%.3f".format(theta, rawErrX))
                            headController.stopGaze()
                            movementController.rotateAwait(theta = theta, maxSpeed = 0.3f)
                            smoothErrX = 0f
                            smoothErrY = 0f
                        } else {
                            nearZoneFrames++
                            Log.d(TAG, "CENTER near-zone rawErrX=%.3f frame $nearZoneFrames/$nearZoneRequired".format(rawErrX))
                            if (nearZoneFrames >= nearZoneRequired) {
                                Log.i(TAG, "PHASE 1 done — near-zone stable ($nearZoneRequired frames)")
                                headController.stopGaze()
                                break
                            }
                        }
                    }                }
                delay(cycleDelayMs)
            }
            if (!isActive) return@launch

            // ── FASE 2: APPROCCIO CONTINUO ────────────────────────────────────────
            Log.i(TAG, "PHASE 2 — continuous approach")
            missedFrames = 0
            var stallFrames = 0
            var lastArea    = 0f

            headController.stopGaze()  // testa fissa — NON richiamare setGaze dentro il loop
            movementController.moveTowardAsync(distanceMeters = 3.0)
            delay(200L)

            while (isActive) {
                delay(300L)

                val bitmap = captureFrame(cameraController)

                val target = runDetection(detectionController, bitmap)
                    .filter { box -> labels.any { it.equals(box.label, ignoreCase = true) } }
                    .let { boxes ->
                        if (lastArea > 0f && boxes.size > 1) {
                            // Preferisci il box con area più simile all'ultima vista
                            boxes.minByOrNull { abs(it.rect.width() * it.rect.height() - lastArea) }
                        } else {
                            boxes.maxByOrNull { it.score }
                        }
                    }

                if (target == null) {
                    missedFrames++
                    Log.d(TAG, "APPROACH no target ($missedFrames/$maxMissedFrames)")
                    if (missedFrames >= maxMissedFrames) {
                        movementController.stopMovement()
                        headController.resetHead()
                        listener?.onObjectLost(labels)
                        return@launch
                    }
                    continue
                }
                missedFrames = 0

                val rawErrX = target.cx - 0.5f
                val rawErrY = target.cy - 0.5f
                // Aggiorna il filtro ma NON usarlo per nulla in FASE 2 —
                // serve solo per avere un valore iniziale sensato se si torna in FASE 1
                smoothErrX = lpfAlpha * rawErrX + (1f - lpfAlpha) * smoothErrX
                smoothErrY = lpfAlpha * rawErrY + (1f - lpfAlpha) * smoothErrY

                val area = target.rect.width() * target.rect.height()
                Log.d(TAG, "APPROACH [${target.label}] area=%.4f target=%.4f rawErrX=%.3f"
                    .format(area, targetArea, rawErrX))

                // ← RIMOSSA headController.setGaze() — testa resta ferma, rawErrX è onesto

                // Correzione laterale: usa rawErrX (non smoothErrX) per la soglia E per il theta
                if (abs(rawErrX) > approachCorrectionZone) {
                    Log.i(TAG, "APPROACH correction rotate rawErrX=%.3f".format(rawErrX))
                    movementController.stopMovement()
                    delay(600L)

                    val theta = (-kpRotation * rawErrX)  // ← rawErrX, non smoothErrX
                        .coerceIn(-maxRotationStep, maxRotationStep).toDouble()
                    movementController.rotateAwait(theta = theta, maxSpeed = 0.3f)

                    smoothErrX = rawErrX * 0.5f
                    smoothErrY = rawErrY * 0.5f

                    movementController.moveTowardAsync(distanceMeters = 3.0)
                    delay(500L)
                    continue
                }

                // Stall detector
                if (abs(area - lastArea) < stallThreshold) stallFrames++ else stallFrames = 0
                lastArea = area

                if (stallFrames > stallMaxFrames) {
                    Log.i(TAG, "APPROACH STALL — target reached by proximity (rawErrX=%.3f)".format(rawErrX))

                    movementController.stopMovement()
                    delay(600L) // Stessa sicurezza di 600ms

                    if (abs(rawErrX) > 0.08f) {
                        Log.i(TAG, "FINAL ALIGNMENT (Stall) rawErrX=%.3f".format(rawErrX))
                        val theta = (-kpRotation * rawErrX * 0.5f)
                            .coerceIn(-0.15f, 0.15f)
                            .toDouble()

                        try {
                            movementController.rotateAwait(theta = theta, maxSpeed = 0.2f)
                            delay(400L)
                        } catch (e: Exception) {
                            Log.e(TAG, "Errore durante il micro-centraggio in stall: ${e.message}")
                        }
                    }

                    headController.resetHead()
                    listener?.onObjectReached(target.label, target)
                    return@launch
                }

                if (area >= targetArea) {
                    Log.i(TAG, "APPROACH DONE — area=%.4f >= target=%.4f rawErrX=%.3f".format(area, targetArea, rawErrX))

                    movementController.stopMovement()
                    delay(600L) // Portato a 600ms per garantire il rilascio delle risorse di movimento

                    // Micro-centraggio finale se siamo arrivati angolati
                    if (abs(rawErrX) > 0.08f) {
                        Log.i(TAG, "FINAL ALIGNMENT (Done) rawErrX=%.3f".format(rawErrX))
                        val theta = (-kpRotation * rawErrX * 0.5f)
                            .coerceIn(-0.15f, 0.15f)
                            .toDouble()

                        try {
                            movementController.rotateAwait(theta = theta, maxSpeed = 0.2f)
                            delay(400L) // Pausa per stabilizzare l'immagine dopo la rotazione
                        } catch (e: Exception) {
                            Log.e(TAG, "Errore durante il micro-centraggio finale: ${e.message}")
                        }
                    }

                    headController.resetHead()
                    listener?.onObjectReached(target.label, target)
                    return@launch
                }
            }

            Log.i(TAG, "Tracking loop finished.")
        }
    }

    /**
    Diventa una 'suspend fun'. Sostituisce i Thread.sleep con l'attesa
    deterministica tramite .join() e delay asincroni, eliminando i freeze nativi.
     */
    suspend fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Log.i(TAG, "Requesting tracking job cancellation...")
            trackingJob?.cancel()
            try {
                // Aspetta esplicitamente che il loop while(isActive) termini l'iterazione corrente
                // e rilasci in sicurezza le risorse asincrone (YOLO e telecamera)
                trackingJob?.join()
            } catch (e: Exception) {
                Log.w(TAG, "Error joining tracking job: ${e.message}")
            }
        }
        trackingJob = null

        // Arrestiamo la base
        movementController.stopMovement()

        // delay asincrono non-bloccante
        delay(250L)

        // Spegniamo lo sguardo asincrono della testa
        try {
            headController.stopGaze()
            delay(200L)
        } catch (e: Exception) {
            Log.w(TAG, "stopGaze error during teardown: ${e.message}")
        }
        Log.i(TAG, "Visual servoing tracking loop stopped completely.")
    }

    private suspend fun captureFrame(cam: PepperCameraController): Bitmap =
        suspendCancellableCoroutine { cont ->
            cam.takeSinglePicture { bmp, _ -> if (cont.isActive) cont.resume(bmp) }
        }

    private suspend fun runDetection(det: ObjectDetectionController, bmp: Bitmap): List<BoundingBox> =
        suspendCancellableCoroutine { cont ->
            det.detect(bmp) { boxes, _, _ -> if (cont.isActive) cont.resume(boxes) }
        }

    private fun List<BoundingBox>.bestMatch(labels: List<String>) =
        filter { box -> labels.any { it.equals(box.label, ignoreCase = true) } }
            .maxByOrNull { it.score }
}