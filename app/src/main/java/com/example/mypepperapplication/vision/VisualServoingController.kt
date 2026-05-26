package com.example.mypepperapplication.vision

import android.graphics.Bitmap
import android.util.Log
import com.example.mypepperapplication.controllers.PepperMovementController
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
    private val movementController: PepperMovementController
) {

    interface VisualServoingListener {
        fun onObjectReached(label: String, box: BoundingBox)
        fun onObjectLost(labels: List<String>)
    }

    // ── Parametri calibrabili ─────────────────────────────────────────────

    // Servoing
    var kpRotation:         Float = 1.0f
    var maxRotationStep:    Float = 0.25f
    var horizontalDeadzone: Float = 0.07f

    var kpForward:          Float = 0.6f
    var maxAdvanceStep:     Float = 0.20f
    var minAdvanceStep:     Float = 0.05f
    var targetArea:         Float = 0.20f   // calibrare per oggetto specifico
    var areaDeadzone:       Float = 0.03f

    var maxMissedFrames:    Int   = 10
    var cycleDelayMs:       Long  = 200L    // frequenza loop servoing

    // Scan
    var scanStepRad:        Double = 0.3    // circa 17grad per ogni step
    var scanSteps:          Int    = 12
    var scanDelayMs:        Long   = 300L   // pausa dopo ogni rotazione prima di scattare

    var listener: VisualServoingListener? = null

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
        Log.i(TAG, "Visual servoing started. Targets=$labels")

        trackingJob = scope.launch {

            // ── FASE 0: SCAN ──────────────────────────────────────────────
            Log.i(TAG, "PHASE 0 SCAN — looking for $labels over $scanSteps steps")
            var found = false

            repeat(scanSteps) { step ->
                if (!isActive || found) return@repeat

                // Ruota di uno step
                movementController.cancelAndMoveAwait(theta = scanStepRad)
                delay(scanDelayMs)

                // Scatta
                val bmp: Bitmap = suspendCancellableCoroutine { cont ->
                    cameraController.takeSinglePicture { b, _ ->
                        if (cont.isActive) cont.resume(b)
                    }
                }

                // Detecta
                val boxes: List<BoundingBox> = suspendCancellableCoroutine { cont ->
                    detectionController.detect(bmp) { b, _, _ ->
                        if (cont.isActive) cont.resume(b)
                    }
                }

                val hit = boxes
                    .filter { box -> labels.any { it.equals(box.label, ignoreCase = true) } }
                    .maxByOrNull { it.score }

                if (hit != null) {
                    Log.i(TAG, "SCAN HIT [${hit.label}] score=${hit.score} at step=$step — entering servoing")
                    found = true
                } else {
                    Log.d(TAG, "SCAN step=$step — nothing found")
                }
            }

            if (!found) {
                Log.w(TAG, "SCAN complete — target not found after $scanSteps steps")
                movementController.stopMovement()
                listener?.onObjectLost(labels)
                return@launch
            }

            // ── FASE 1 - 2: SERVOING ────────────────────────────────────────
            Log.i(TAG, "Entering servoing loop")
            var missedFrames = 0

            while (isActive) {

                // Scatta
                val bitmap: Bitmap = suspendCancellableCoroutine { cont ->
                    cameraController.takeSinglePicture { bmp, _ ->
                        if (cont.isActive) cont.resume(bmp)
                    }
                }

                // Detecta
                val boxes: List<BoundingBox> = suspendCancellableCoroutine { cont ->
                    detectionController.detect(bitmap) { b, _, _ ->
                        if (cont.isActive) cont.resume(b)
                    }
                }

                // Miglior candidato
                val target = boxes
                    .filter { box -> labels.any { it.equals(box.label, ignoreCase = true) } }
                    .maxByOrNull { it.score }

                if (target == null) {
                    missedFrames++
                    Log.d(TAG, "No target detected ($missedFrames/$maxMissedFrames) labels=$labels")
                    if (missedFrames >= maxMissedFrames) {
                        Log.w(TAG, "Target lost — stop")
                        movementController.stopMovement()
                        listener?.onObjectLost(labels)
                        break
                    }
                    delay(cycleDelayMs)
                    continue
                }

                missedFrames = 0
                val errX    = target.cx - 0.5f
                val rawArea = target.rect.width() * target.rect.height()
                val errArea = targetArea - rawArea

                Log.d(TAG, "[${target.label}] cx=%.2f area=%.4f errX=%.3f errArea=%.3f"
                    .format(target.cx, rawArea, errX, errArea))

                // FASE 1 — Centra
                if (abs(errX) > horizontalDeadzone) {
                    val theta = (-kpRotation * errX)
                        .coerceIn(-maxRotationStep, maxRotationStep)
                        .toDouble()
                    Log.i(TAG, "PHASE 1 ROTATE theta=%.3f (errX=%.3f)".format(theta, errX))
                    movementController.moveNonBlocking(theta = theta)
                    delay(cycleDelayMs)
                    continue
                }

                // FASE 2 — Avanza
                if (errArea > areaDeadzone) {
                    val rawStep = kpForward * errArea
                    if (rawStep < minAdvanceStep) {
                        Log.i(TAG, "Step too small — TARGET REACHED [${target.label}]")
                        movementController.stopMovement()
                        listener?.onObjectReached(target.label, target)
                        break
                    }
                    val dist = rawStep.coerceAtMost(maxAdvanceStep).toDouble()
                    Log.i(TAG, "PHASE 2 ADVANCE dist=%.3f m (errArea=%.3f)".format(dist, errArea))
                    movementController.cancelAndMoveAwait(x = dist, theta = 0.0)
                    continue
                }

                // TARGET RAGGIUNTO
                Log.i(TAG, "TARGET REACHED: [${target.label}] cx=%.2f area=%.4f"
                    .format(target.cx, rawArea))
                movementController.stopMovement()
                listener?.onObjectReached(target.label, target)
                break
            }

            Log.i(TAG, "Tracking loop finished.")
        }
    }

    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            trackingJob?.cancel()
            movementController.stopMovement()
            Log.i(TAG, "Visual servoing stopped.")
        }
        trackingJob = null
    }
}