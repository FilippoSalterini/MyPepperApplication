package com.example.mypepperapplication.vision

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import com.example.mypepperapplication.controllers.PepperMovementController

private const val TAG = "VisualServoing"

/**
 * VisualServoingController — IBVS semplificato.
 *
 * FIX in questa versione:
 *
 *   1. selectTarget — se il label cercato non è nei box → null.
 *      Evita che Pepper segua bottiglie/tazze invece della persona.
 *
 *   2. Semaforo isMoving — applyControl() non lancia un nuovo GoTo se il
 *      precedente è ancora in esecuzione. Elimina l'accumulo di GoTo paralleli
 *      che causava "Move task not started".
 *
 *   3. Il loop aspetta che isMoving sia false prima di scattare il frame successivo.
 */
class VisualServoingController(
    private val movementController: PepperMovementController
) {

    private var trackingJob: Job? = null

    var targetLabel: String? = "person"
    var horizontalDeadzone: Float = 0.08f
    var targetArea: Float = 0.45f
    var areaDeadzone: Float = 0.05f
    var rotationStep: Float = 0.20f
    var advanceStep: Float = 0.25f
    var maxMissedFrames: Int = 5
    var loopIntervalMs: Long = 800L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onRobotReady(ctx: QiContext) {
        movementController.onRobotReady(ctx)
        Log.d(TAG, "Robot ready.")
    }

    fun onRobotLost() {
        stopTracking()
        movementController.onRobotLost()
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    fun startTracking(
        cameraController: PepperCameraController,
        detectionController: ObjectDetectionController,
        label: String = targetLabel ?: "person"
    ) {
        targetLabel = label
        stopTracking()
        Log.i(TAG, "Visual servoing started. Target='$label' interval=${loopIntervalMs}ms")

        trackingJob = CoroutineScope(Dispatchers.IO).launch {
            var missedFrames = 0

            while (isActive) {
                val loopStart = System.currentTimeMillis()

                // FIX 1: aspetta che il movimento precedente sia terminato
                if (movementController.isMoving.get()) {
                    delay(100)
                    continue
                }

                // Cattura frame e manda al PC
                cameraController.takeSinglePicture { bitmap, _ ->
                    if (!isActive) return@takeSinglePicture

                    detectionController.detect(bitmap) { boxes, _, _ ->

                        // FIX 2: solo label esatto, nessun fallback
                        val target = selectTarget(boxes, label)

                        if (target == null) {
                            missedFrames++
                            Log.d(TAG, "Target '$label' not found ($missedFrames/$maxMissedFrames)")
                            if (missedFrames >= maxMissedFrames) {
                                Log.w(TAG, "Target lost — stopping movement.")
                                movementController.stopMovement()
                            }
                            return@detect
                        }

                        missedFrames = 0
                        Log.d(TAG, debugInfo(listOf(target)))
                        applyControl(target)
                    }
                }

                val elapsed = System.currentTimeMillis() - loopStart
                val remaining = loopIntervalMs - elapsed
                if (remaining > 0) delay(remaining)
            }
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

    val isTracking: Boolean get() = trackingJob?.isActive == true

    // ── Selezione target ──────────────────────────────────────────────────────

    /**
     * FIX: ritorna null se il label cercato non è presente.
     * Non fa mai fallback ad altri oggetti.
     */
    private fun selectTarget(boxes: List<BoundingBox>, label: String): BoundingBox? {
        if (boxes.isEmpty()) return null
        return boxes
            .filter { it.label.equals(label, ignoreCase = true) }
            .maxByOrNull { it.score }
    }

    // ── Controllo proporzionale P ─────────────────────────────────────────────

    private fun applyControl(target: BoundingBox) {
        // FIX 3: skip se il movimento precedente è ancora in corso
        if (movementController.isMoving.get()) {
            Log.d(TAG, "Movement in progress, skipping control.")
            return
        }

        val errX    = target.cx - 0.5f
        val boxArea = target.rect.width() * target.rect.height()
        val errArea = targetArea - boxArea

        val needsRotation = abs(errX) > horizontalDeadzone
        val needsAdvance  = abs(errArea) > areaDeadzone

        Log.d(TAG, "errX=%.3f errArea=%.3f | rot=$needsRotation adv=$needsAdvance"
            .format(errX, errArea))

        when {
            needsRotation -> {
                val theta = -errX.sign * rotationStep
                Log.d(TAG, "→ Rotate theta=%.2f rad".format(theta))
                movementController.isMoving.set(true)
                movementController.rotateByAngle(theta.toDouble()) {
                    Log.d(TAG, "Rotation done.")
                }
            }
            needsAdvance -> {
                val dist = errArea.sign * advanceStep
                Log.d(TAG, "→ Move dist=%.2f m".format(dist))
                movementController.isMoving.set(true)
                movementController.moveByDistance(dist.toDouble()) {
                    Log.d(TAG, "Advance done.")
                }
            }
            else -> {
                Log.d(TAG, "→ Target centrato e a distanza corretta.")
            }
        }
    }

    // ── Debug ─────────────────────────────────────────────────────────────────

    fun debugInfo(boxes: List<BoundingBox>): String {
        val target = boxes.firstOrNull { it.label.equals(targetLabel, ignoreCase = true) }
        return if (target != null) {
            val area = target.rect.width() * target.rect.height()
            "[${target.source}] '${target.label}' cx=%.2f cy=%.2f area=%.3f score=%.0f%%"
                .format(target.cx, target.cy, area, target.score * 100)
        } else {
            "Target '$targetLabel' NOT FOUND in ${boxes.size} detections"
        }
    }
}