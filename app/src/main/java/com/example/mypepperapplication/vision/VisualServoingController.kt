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
Costituisce il loop di controllo visivo : Scatta > Detecta > Centra/Ruota > avanza > Ripete
 1. SCATTA: sospende finché PepperCameraController non ha il bitmap pronto.
 2. DETECT: sospende finché ObjectDetectionController non ha le box pronte.
 3. FILTRA: prende il candidato con score massimo tra le label richieste.
 4. FASE 1 — CENTRA: se |cx − 0.5| > horizontalDeadzone, ruota di theta = −kp * errX
    e ricomincia dal passo 1 (continue).
 5. FASE 2 — AVANZA: se targetArea − area > areaDeadzone, avanza di kp * errArea
    clampato in [minAdvanceStep, maxAdvanceStep]. Se il passo calcolato
    < minAdvanceStep → TARGET RAGGIUNTO.
 6. Se entrambe le zone morte sono soddisfatte → TARGET RAGGIUNTO.
 7. missedFrames: se nessun target per maxMissedFrames cicli → onObjectLost().
 */
private const val TAG = "VisualServoing"
class VisualServoingController(
    private val movementController: PepperMovementController
) {

    interface VisualServoingListener {
        fun onObjectReached(label: String, box: BoundingBox)
        fun onObjectLost(labels: List<String>)
    }
    var listener: VisualServoingListener? = null

    var kpRotation:         Float = 1.0f
    var maxRotationStep:    Float = 0.25f
    var horizontalDeadzone: Float = 0.07f

    var kpForward:          Float = 0.6f
    var maxAdvanceStep:     Float = 0.20f
    var minAdvanceStep:     Float = 0.05f
    var targetArea:         Float = 0.20f //vedi calibrazione oggetto
    var areaDeadzone:       Float = 0.03f

    var maxMissedFrames:    Int   = 10
    var cycleDelayMs:       Long  = 200L

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var trackingJob: Job? = null

    fun onRobotReady() { Log.d(TAG, "Robot ready.") }
    fun onRobotLost() { stopTracking() }

    fun startTracking(
        cameraController:    PepperCameraController,
        detectionController: ObjectDetectionController,
        labels: List<String>
    ) {
        require(labels.isNotEmpty()) { "labels cannot be empty" }
        stopTracking()
        Log.i(TAG, "Visual servoing started. Targets=$labels")

        trackingJob = scope.launch {
            var missedFrames = 0

            while (isActive) {

                // scatta
                val bitmap: Bitmap = suspendCancellableCoroutine { cont ->
                    cameraController.takeSinglePicture { bmp, _ ->
                        if (cont.isActive) cont.resume(bmp)
                    }
                }

                // 2. Detection YOLO
                val boxes: List<BoundingBox> = suspendCancellableCoroutine { cont ->
                    detectionController.detect(bitmap) { b, _, _ ->
                        if (cont.isActive) cont.resume(b)
                    }
                }

                // miglior candidato con label score migliore
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

                // centra - fase 1
                if (abs(errX) > horizontalDeadzone) {
                    val theta = (-kpRotation * errX)
                        .coerceIn(-maxRotationStep, maxRotationStep)
                        .toDouble()
                    Log.i(TAG, "PHASE 1 ROTATE theta=%.3f (errX=%.3f)".format(theta, errX))
                    movementController.moveNonBlocking(theta = theta)
                    delay(cycleDelayMs)
                    continue
                }

                // 2 fase avvicinati
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
                    continue  //ogni passo dopo torna a fase 1  cioè centra - TODO - DELAY
                }
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