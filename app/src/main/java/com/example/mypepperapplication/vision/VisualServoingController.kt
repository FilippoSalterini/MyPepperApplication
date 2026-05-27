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
    var kpRotation:         Float = 0.8f
    var maxRotationStep:    Float = 0.20f
    var bodyRotationZone:   Float = 0.10f
    var headOnlyZone:       Float = 0.07f
    var horizontalDeadzone: Float = 0.05f

    var kpForward:          Float = 0.7f
    var maxAdvanceStep:     Float = 0.35f
    var minAdvanceStep:     Float = 0.08f
    var targetArea:         Float = 0.10f
    var areaDeadzone:       Float = 0.02f

    var maxMissedFrames:    Int   = 10
    var cycleDelayMs:       Long  = 250L

    var lpfAlpha:           Float  = 0.35f

    // Scan
    var scanStepRad:        Double = 0.3
    var scanSteps:          Int    = 12
    var scanDelayMs:        Long   = 300L

    var listener: VisualServoingListener? = null

    private var smoothErrX = 0f
    private var smoothErrY = 0f
    private val scope       = CoroutineScope(Dispatchers.IO + Job())
    private var trackingJob: Job? = null

    fun onRobotReady() { Log.d(TAG, "Robot ready.") }
    fun onRobotLost()  { stopTracking() }

    fun startTracking(
        cameraController:    PepperCameraController,
        detectionController: ObjectDetectionController,
        labels:              List<String>
    ) {
        require(labels.isNotEmpty()) { "labels cannot be empty" }
        stopTracking()
        smoothErrX = 0f
        smoothErrY = 0f
        Log.i(TAG, "Visual servoing started. Targets=$labels")

        trackingJob = scope.launch {

            // FASE 0 - SCAN
            Log.i(TAG, "PHASE 0 SCAN — looking for $labels over $scanSteps steps")
            var found = false
            repeat(scanSteps) { step ->
                if (!isActive || found) return@repeat
                val jitter = (Math.random() * 0.05 - 0.025)
                headController.stopGaze()
                movementController.rotateAwait(theta = scanStepRad + jitter)
                headController.resetHead()
                delay(scanDelayMs)

                val bmp = captureFrame(cameraController)
                val boxes = runDetection(detectionController, bmp)
                val hit = boxes.bestMatch(labels)

                if (hit != null) {
                    Log.i(TAG, "SCAN HIT [${hit.label}] score=${hit.score} step=$step")
                    headController.setGaze(normErrX = 0f, normErrY = -1f)
                    found = true
                }
            }

            if (!found) {
                Log.w(TAG, "SCAN complete — target not found")
                movementController.stopMovement()
                headController.resetHead()
                listener?.onObjectLost(labels)
                return@launch
            }

            Log.i(TAG, "Entering servoing loop")
            var missedFrames = 0
            var stallFrames = 0
            var lastArea = 0F

            while (isActive) {
                val bitmap = captureFrame(cameraController)
                val boxes = runDetection(detectionController, bitmap)
                val target = boxes.bestMatch(labels)

                if (target == null) {
                    missedFrames++
                    if (missedFrames >= maxMissedFrames) {
                        Log.w(TAG, "Target lost")
                        movementController.stopMovement()
                        headController.resetHead()
                        listener?.onObjectLost(labels)
                        break
                    }
                    delay(cycleDelayMs)
                    continue
                }

                missedFrames = 0

                // LOW-PASS FILTER

                // Calcola lo scostamento del target rispetto al centro ideale del fotogramma (0.5, 0.5).
                // Applica una media mobile (alfa = 0.35) per smorzare le oscillazioni della detection
                // ed evitare movimenti scattosi o isteresi del collo del robot.
                val rawErrX = target.cx - 0.5f
                val rawErrY = target.cy - 0.5f
                smoothErrX = lpfAlpha * rawErrX + (1f - lpfAlpha) * smoothErrX
                smoothErrY = lpfAlpha * rawErrY + (1f - lpfAlpha) * smoothErrY
                // Calcola l'area occupata dal Bounding Box come metrica indiretta della vicinanza.
                // Determina l'errore residuo (errArea) rispetto all'area target finale desiderata.
                val rawArea = target.rect.width() * target.rect.height()
                val errArea = targetArea - rawArea
                // Monitora il delta di variazione dell'area tra fotogrammi consecutivi.
                // Se la variazione è insignificante (< 0.001), incrementa il contatore di stallo.
                if (abs(rawArea - lastArea) < 0.001f) stallFrames++ else stallFrames = 0
                lastArea = rawArea
                // Se l'immagine rimane immobile per 8 frame consecutivi, assume che il robot
                // abbia raggiunto la distanza corretta dall'oggetto (o sia impossibilitato ad avanzare).
                // Ferma i motori, resetta la postura e notifica il successo interrompendo il loop.
                if (stallFrames > 8) {
                    Log.i(TAG, "STALL — TARGET REACHED [${target.label}]")
                    movementController.stopMovement()
                    headController.resetHead()
                    listener?.onObjectReached(target.label, target)
                    break
                }

                Log.d(TAG, "[${target.label}] cx=%.2f errX=%.3f area=%.4f".format(target.cx, smoothErrX, rawArea))

                // Applica lo sguardo inseguendo attivamente anche l'asse verticale (Y)
                headController.setGaze(normErrX = smoothErrX, normErrY = smoothErrY)

                // FASE 1a — Errore piccolo: solo testa
                if (abs(smoothErrX) < headOnlyZone) {
                    Log.v(TAG, "HEAD ONLY zone — no body rotation")
                    if (errArea > areaDeadzone) {
                        val rawStep = kpForward * errArea
                        if (rawStep >= minAdvanceStep) {
                            val dist = rawStep.coerceIn(minAdvanceStep, maxAdvanceStep).toDouble()
                            Log.i(TAG, "PHASE 1a ADVANCE dist=%.3f".format(dist))

                            headController.stopGaze()
                            movementController.cancelAndMoveAwait(x = dist)
                            headController.setGaze(normErrX = smoothErrX, normErrY = smoothErrY)
                        }
                    } else if (abs(smoothErrX) <= horizontalDeadzone) {
                        Log.i(TAG, "TARGET REACHED [${target.label}]")
                        movementController.stopMovement()
                        headController.resetHead()
                        listener?.onObjectReached(target.label, target)
                        break
                    }
                    delay(cycleDelayMs)
                    continue
                }

                // FASE 1b — Ruota corpo
                if (abs(smoothErrX) >= bodyRotationZone) {
                    val theta = (-kpRotation * smoothErrX)
                        .coerceIn(-maxRotationStep, maxRotationStep).toDouble()
                    Log.i(TAG, "PHASE 1 ROTATE theta=%.3f".format(theta))
                    headController.stopGaze()
                    movementController.rotateAwait(theta = theta, maxSpeed = 0.4f)
                    headController.setGaze(normErrX = smoothErrX * 0.5f, normErrY = smoothErrY) // ← Passiamo Float corretti
                    smoothErrX *= 0.5f
                    delay(cycleDelayMs)
                    continue
                }

                // FASE 2 — Avanza (dentro headOnlyZone)
                if (errArea > areaDeadzone) {
                    val rawStep = kpForward * errArea
                    if (rawStep >= minAdvanceStep) {
                        val dist = rawStep.coerceIn(minAdvanceStep, maxAdvanceStep).toDouble()
                        Log.i(TAG, "PHASE 2 ADVANCE dist=%.3f".format(dist))
                        headController.stopGaze()
                        movementController.cancelAndMoveAwait(x = dist)
                        headController.setGaze(normErrX = smoothErrX, normErrY = smoothErrY)
                    }
                }
                delay(cycleDelayMs)            }
        }
    }

    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            trackingJob?.cancel()
            movementController.stopMovement()
            try { headController.resetHead() } catch (e: Exception) { Log.w(TAG, "resetHead on stop: ${e.message}") }
            Log.i(TAG, "Visual servoing stopped.")
        }
        trackingJob = null
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