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

/*
 Loop di controllo visivo: Scan > Scatta > Detecta > Centra/Ruota > Avanza > Ripete
 FASE 0 — SCAN:
   Ruota a step di scanStepRad per un massimo di scanSteps passi.
   Ad ogni step scatta e detecta. Appena trova il target esce e parte il servoing.
   Se dopo scanSteps passi non trova nulla → onObjectLost.

 FASE 1 — CENTRA:
   Se |cx − 0.5| > horizontalDeadzone, ruota di theta = −kp * errX e ricomincia.

 FASE 2 — AVANZA:
   Se targetArea − area > areaDeadzone, avanza clampato in [minAdvanceStep, maxAdvanceStep].
   Se il passo calcolato < minAdvanceStep → TARGET RAGGIUNTO.

 FASE 3 — TARGET RAGGIUNTO:
   Se entrambe le zone morte sono soddisfatte → onObjectReached.

 missedFrames: se nessun target per maxMissedFrames cicli → onObjectLost.
*/

private const val TAG = "VisualServoing"

class VisualServoingController(
    private val movementController: PepperMovementController,
    private val headController:     HeadMovementController
) {

    interface VisualServoingListener {
        fun onObjectReached(label: String, box: BoundingBox)
        fun onObjectLost(labels: List<String>)
    }

    // Parametri da calibrare

    // Servoing
    var kpRotation:         Float = 0.8f
    var maxRotationStep:    Float = 0.20f
    var bodyRotationZone:   Float = 0.18f
    var headOnlyZone:       Float = 0.07f
    var horizontalDeadzone: Float = 0.05f

    var kpForward:          Float = 0.6f
    var maxAdvanceStep:     Float = 0.20f
    var minAdvanceStep:     Float = 0.05f
    var targetArea:         Float = 0.20f   // calibrare per oggetto specifico
    var areaDeadzone:       Float = 0.03f

    var maxMissedFrames:    Int   = 10
    var cycleDelayMs:       Long  = 200L    // frequenza loop servoing

    var lpfAlpha:           Float  = 0.35f

    // Scan
    var scanStepRad:        Double = 0.3    // circa 17grad per ogni step
    var scanSteps:          Int    = 12
    var scanDelayMs:        Long   = 300L   // pausa dopo ogni rotazione prima di scattare

    var listener: VisualServoingListener? = null

    private var smoothErrX = 0f
    private var smoothErrY = 0f
    private val scope       = CoroutineScope(Dispatchers.IO + Job())
    private var trackingJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun onRobotReady() { Log.d(TAG, "Robot ready.") }
    fun onRobotLost()  { stopTracking() }

    // ── API pubblica ──────────────────────────────────────────────────────

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
                movementController.rotateAwait(theta = scanStepRad + jitter)

                headController.resetHead()
                delay(scanDelayMs)

                val bmp   = captureFrame(cameraController)
                val boxes = runDetection(detectionController, bmp)
                val hit   = boxes.bestMatch(labels)

                if (hit != null) {
                    Log.i(TAG, "SCAN HIT [${hit.label}] score=${hit.score} step=$step")
                    // ★ FIX COMPATIBILITÀ: Rimosso speed e normErrY opzionale (usa di default 0f)
                    headController.setGaze(normErrX = hit.cx - 0.5f)
                    found = true
                } else {
                    Log.d(TAG, "SCAN step=$step — nothing")
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

            while (isActive) {

                val bitmap = captureFrame(cameraController)
                val boxes  = runDetection(detectionController, bitmap)
                val target = boxes.bestMatch(labels)

                if (target == null) {
                    missedFrames++
                    Log.d(TAG, "No target ($missedFrames/$maxMissedFrames)")
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
                val rawErrX  = target.cx - 0.5f
                val rawErrY  = target.cy - 0.5f
                smoothErrX   = lpfAlpha * rawErrX + (1f - lpfAlpha) * smoothErrX
                smoothErrY   = lpfAlpha * rawErrY + (1f - lpfAlpha) * smoothErrY

                val rawArea  = target.rect.width() * target.rect.height()
                val errArea  = targetArea - rawArea

                Log.d(TAG, "[${target.label}] cx=%.2f errX=%.3f(s=%.3f) area=%.4f"
                    .format(target.cx, rawErrX, smoothErrX, rawArea))

                headController.setGaze(
                    normErrX = smoothErrX,
                    normErrY = smoothErrY
                )

                // FASE 1a — Errore piccolo: solo testa
                if (abs(smoothErrX) < headOnlyZone) {
                    Log.v(TAG, "HEAD ONLY zone — no body rotation")
                    if (errArea > areaDeadzone) {
                        val rawStep = kpForward * errArea
                        if (rawStep >= minAdvanceStep) {
                            val dist = rawStep.coerceAtMost(maxAdvanceStep).toDouble()
                            Log.i(TAG, "PHASE 2 ADVANCE dist=%.3f".format(dist))
                            movementController.cancelAndMoveAwait(x = dist)
                        }
                    } else if (abs(smoothErrX) <= horizontalDeadzone) {
                        Log.i(TAG, "TARGET REACHED [${target.label}]")
                        movementController.stopMovement()
                        listener?.onObjectReached(target.label, target)
                        break
                    }
                    delay(cycleDelayMs)
                    continue
                }

                // FASE 1b — Errore medio/grande: ruota corpo
                if (abs(smoothErrX) >= bodyRotationZone) {
                    val theta = (-kpRotation * smoothErrX)
                        .coerceIn(-maxRotationStep, maxRotationStep)
                        .toDouble()
                    Log.i(TAG, "PHASE 1 ROTATE theta=%.3f (smoothErrX=%.3f)".format(theta, smoothErrX))
                    movementController.rotateAwait(theta = theta, maxSpeed = 0.4f)
                    smoothErrX *= 0.5f
                    delay(cycleDelayMs)
                    continue
                }

                // FASE 1c — Zona intermedia
                val theta = (-kpRotation * smoothErrX * 0.5f)
                    .coerceIn(-maxRotationStep * 0.5f, maxRotationStep * 0.5f)
                    .toDouble()
                if (abs(theta) > 0.03) {
                    movementController.rotateAwait(theta = theta, maxSpeed = 0.3f)
                }

                delay(cycleDelayMs)
            }

            Log.i(TAG, "Tracking loop finished.")
        }
    }

    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            trackingJob?.cancel()
            movementController.stopMovement()
            headController.resetHead()
            Log.i(TAG, "Visual servoing stopped.")
        }
        trackingJob = null
    }

    private suspend fun captureFrame(cam: PepperCameraController): Bitmap =
        suspendCancellableCoroutine { cont ->
            cam.takeSinglePicture { bmp, _ -> if (cont.isActive) cont.resume(bmp) }
        }

    private suspend fun runDetection(
        det: ObjectDetectionController,
        bmp: Bitmap
    ): List<BoundingBox> =
        suspendCancellableCoroutine { cont ->
            det.detect(bmp) { boxes, _, _ -> if (cont.isActive) cont.resume(boxes) }
        }

    private fun List<BoundingBox>.bestMatch(labels: List<String>) =
        filter { box -> labels.any { it.equals(box.label, ignoreCase = true) } }
            .maxByOrNull { it.score }
}