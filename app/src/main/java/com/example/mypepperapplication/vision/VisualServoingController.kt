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
/**
*Azione strutturata in due fasi: scanning (SCAN 360°, con accumulo di tutti gli oggetti
*visti oltre al target) e centraggio di precisione (PHASE 1)
 */
private const val TAG = "VisualServoing"

class VisualServoingController(
    private val movementController: PepperMovementController,
    private val headController:     HeadMovementController
) {

    interface VisualServoingListener {
        fun onObjectCentered(label: String, box: BoundingBox)
        fun onObjectLost(labels: List<String>)
        fun onObjectsSpotted(spotted: List<BoundingBox>)
    }

    // Parametri di controllo
    var kpRotation:       Float  = 0.8f
    var maxRotationStep:  Float  = 0.35f
    var bodyRotationZone: Float  = 0.12f
    var headOnlyZone:     Float  = 0.05f
    var lpfAlpha:         Float  = 0.5f
    var maxMissedFramesApproach: Int = 7
    var cycleDelayMs:     Long   = 250L

    var centeredFrames = 0
    val centeredRequired = 3

    /*
    Parametri di Scan

    Viene poi definito halfSteps che sarà la metà di scanStepRad.
    angoli di destra 7 * 0.25rad = 1.75rad totale (circa 100°)
    angoli di sinistra 7 * -0.25rad = -1.75rad totale (circa 100°)
    la copertura totale non copre 360°, infatti era pensato per coprire
    la parte della stanza d'interesse, ma cio implicherebbe comunque integrare
    un orientamento verso il muro, quindi possibilità di cambiare gli step per
    ottenere circa 360° di copertura quindi

    var scanStepRad: Double = 0.25
    var scanSteps:   Int    = 26
    Ovviamente questo aumenterebbe il tempo di esecuzione ma permette di coprire tutta la
    stanza -> TODO verifica in fase di sperimentazione
     */
    var scanStepRad:      Double = 0.25
    var scanSteps:        Int    = 14
    var scanDelayMs : Long   = 300L

    var listener : VisualServoingListener? = null

    private var smoothErrX = 0f
    private var smoothErrY = 0f

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
                repeat(halfSteps) { add(scanStepRad + (Math.random() * 0.04 - 0.02)) }
                repeat(halfSteps) { add(-scanStepRad + (Math.random() * 0.04 - 0.02)) }
            }

            // Accumulo di TUTTI gli oggetti visti durante lo scan, non solo il target.
            // Dedup per label: si tiene solo la detection con score più alto vista finora.
            val spottedByLabel = mutableMapOf<String, BoundingBox>()

            var found = false
            for ((idx, angle) in angles.withIndex()) {
                if (!isActive || found) break

                headController.stopGaze()
                delay(200L)

                movementController.rotateAwait(theta = angle)

                val scanErrY = when (idx % 3) {
                    0    -> -0.45f
                    1    -> -0.10f
                    else ->  0.20f
                }
                headController.setGaze(normErrX = 0f, normErrY = scanErrY, scanMode = true)
                delay(scanDelayMs)

                val bmp = captureFrame(cameraController)
                val boxesThisFrame = if (bmp != null) runDetection(detectionController, bmp) else emptyList()

                // Accumula ogni detection di questo frame (indipendentemente dal target),
                // tenendo per ciascuna label lo score più alto osservato finora.
                for (box in boxesThisFrame) {
                    val existing = spottedByLabel[box.label]
                    if (existing == null || box.score > existing.score) {
                        spottedByLabel[box.label] = box
                    }
                }

                val hit = boxesThisFrame.bestMatch(labels)

                if (hit != null) {
                    Log.i(TAG, "SCAN HIT [${hit.label}] score=${hit.score} idx=$idx")
                    headController.stopGaze()
                    delay(150L)
                    headController.setGaze(normErrX = hit.cx - 0.5f, normErrY = hit.cy - 0.5f)
                    found = true
                }
            }

            if (spottedByLabel.isNotEmpty()) {
                Log.i(TAG, "SCAN spotted ${spottedByLabel.size} distinct label(s): ${spottedByLabel.keys}")
                listener?.onObjectsSpotted(spottedByLabel.values.toList())
            }

            if (!found) {
                Log.w(TAG, "SCAN complete — target not found")
                movementController.stopMovement()
                headController.resetHead()
                listener?.onObjectLost(labels)
                return@launch
            }

            // ── FASE 1: CENTRAGGIO DI PRECISIONE ──────────────────────────────
            /*
            - rotate stall count rete di sicurezza, se robot si trova in una situazione di
            microscatti inifiniti causa rumore pixel il codice non si frezza ma porosegue
            alla fase 2
            - utilizzo di un LPF che serve per atteuare il jitter della bb di yolo,
            permettendo ai comandi inviati alla testa di essere morbidi
             */

            var rotateStallCount = 0
            var lastRawErrX      = 0f
            var nearZoneFrames   = 0
            val nearZoneRequired = 4
            var missedFrames     = 0
            var lastCenteredTarget: BoundingBox? = null
            centeredFrames       = 0
            smoothErrX           = 0f
            smoothErrY           = 0f

            headController.stopGaze()
            delay(150L)

            while (isActive) {
                val bitmap = captureFrame(cameraController)
                val target = if (bitmap != null) runDetection(detectionController, bitmap).bestMatch(labels) else null

                if (target == null) {
                    missedFrames++
                    if (missedFrames >= maxMissedFramesApproach) {
                        movementController.stopMovement()
                        headController.resetHead()
                        listener?.onObjectLost(labels)
                        return@launch
                    }
                    delay(cycleDelayMs)
                    continue
                }
                missedFrames = 0
                lastCenteredTarget = target

                val rawErrX = target.cx - 0.5f
                val rawErrY = target.cy - 0.5f

                smoothErrX = lpfAlpha * rawErrX + (1f - lpfAlpha) * smoothErrX
                smoothErrY = lpfAlpha * rawErrY + (1f - lpfAlpha) * smoothErrY
                headController.setGaze(normErrX = rawErrX, normErrY = rawErrY)

                Log.d(TAG, "CENTER [${target.label}] rawErrX=%.3f smoothErrX=%.3f".format(rawErrX, smoothErrX))

                when {
                    abs(rawErrX) <= headOnlyZone -> {
                        nearZoneFrames = 0
                        centeredFrames++
                        if (centeredFrames >= centeredRequired) {
                            Log.i(TAG, "PHASE 1 done — object centered")
                            headController.stopGaze()
                            delay(200L)
                            break
                        }
                    }

                    abs(rawErrX) >= bodyRotationZone -> {
                        centeredFrames = 0
                        nearZoneFrames = 0

                        if (abs(rawErrX - lastRawErrX) < 0.01f) {
                            rotateStallCount++
                            if (rotateStallCount >= 4) {
                                Log.w(TAG, "ROTATE STALL MAX — exiting centering loop early")
                                headController.stopGaze()
                                delay(200L)
                                break
                            }
                        } else {
                            rotateStallCount = 0
                        }
                        lastRawErrX = rawErrX

                        val theta = (-kpRotation * rawErrX).coerceIn(-maxRotationStep, maxRotationStep).toDouble()
                        Log.i(TAG, "ROTATE theta=%.3f rawErrX=%.3f".format(theta, rawErrX))

                        headController.stopGaze()
                        delay(200L)
                        movementController.rotateAwait(theta = theta, maxSpeed = 0.4f)
                        smoothErrX = 0f
                        smoothErrY = 0f
                        headController.setGaze(normErrX = 0f, normErrY = -0.1f)
                        delay(300L)

                    }

                    else -> {
                        val nearZoneMaxErr = bodyRotationZone * 0.85f

                        if (abs(rawErrX) > nearZoneMaxErr) {
                            centeredFrames = 0
                            nearZoneFrames = 0

                            val rawTheta = -kpRotation * rawErrX * 0.6f
                            val theta = if (abs(rawTheta) < 0.10f) {
                                if (rawTheta >= 0) 0.10 else -0.10
                            } else {
                                rawTheta.coerceIn(-maxRotationStep * 0.5f, maxRotationStep * 0.5f).toDouble()
                            }

                            Log.i(TAG, "NEAR-ZONE CORRECTION theta=%.3f rawErrX=%.3f".format(theta, rawErrX))
                            headController.stopGaze()
                            delay(200L)
                            movementController.rotateAwait(theta = theta, maxSpeed = 0.3f)
                            smoothErrX = 0f
                            smoothErrY = 0f
                        } else {
                            nearZoneFrames++
                            if (nearZoneFrames >= nearZoneRequired) {
                                Log.i(TAG, "PHASE 1 done — near-zone stable ($nearZoneRequired frames)")
                                headController.stopGaze()
                                delay(200L)
                                break
                            }
                        }
                    }
                }
                delay(cycleDelayMs)
            }
            if (!isActive) return@launch

            // ── (PHASE 2) target centrato ─────────────
            //lastCenteredTarget è aggiornato ad ogni iterazione di PHASE 1 — nessuno scatto extra necessario.
            headController.resetHead()

            if (lastCenteredTarget != null) {
                Log.i(TAG, "PHASE 1 result — object centered: ${lastCenteredTarget.label}")
                listener?.onObjectCentered(lastCenteredTarget.label, lastCenteredTarget)
            } else {
                Log.w(TAG, "PHASE 1 ended without a confirmed target")
                listener?.onObjectLost(labels)
            }

            Log.i(TAG, "Tracking loop finished.")
        }
    }

    suspend fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Log.i(TAG, "Requesting tracking job cancellation...")
            trackingJob?.cancel()
            try {
                // CHECK verifica se portare a 2000L il timeout -> per logica PLANNER
                withTimeoutOrNull(500L) { trackingJob?.join() }
            } catch (e: Exception) {
                Log.w(TAG, "Error joining job: ${e.message}")
            }
        }
        trackingJob = null
        movementController.stopMovement()
        delay(250L)
        try {
            headController.stopGaze()
            delay(200L)
        } catch (e: Exception) { Log.w(TAG, "stopGaze error during teardown: ${e.message}") }
        Log.i(TAG, "Visual servoing tracking loop stopped completely.")
    }

    private suspend fun captureFrame(cam: PepperCameraController): Bitmap? =
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