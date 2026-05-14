package com.example.mypepperapplication.vision

import android.graphics.Bitmap
import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt
import com.example.mypepperapplication.controllers.PepperMovementController

private const val TAG = "VisualServoing"

/**
 * Loop:
 *   1. Scatta frame (suspend)
 *   2. Detection PC (suspend)
 *   3. Seleziona target + smoothing
 *   4. cancelAndMove() — cancella precedente, lancia nuovo, NON aspetta
 *   5. delay(100ms) → torna al punto 1
 *   Ciclo totale: ~300ms → ~3Hz
 */
class VisualServoingController(
    private val movementController: PepperMovementController
) {

    private var trackingJob: Job? = null

    // ── Parametri ─────────────────────────────────────────────────────────────
    var targetLabel: String? = "person"
    var kpRotation: Float = 0.35f
    var kpForward: Float = 0.3f
    var maxRotationStep: Float = 0.12f
    var maxAdvanceStep: Float = 0.06f
    var horizontalDeadzone: Float = 0.05f
    var targetArea: Float = 0.10f
    var areaDeadzone: Float = 0.02f
    var maxMissedFrames: Int = 8
    var cycleDelayMs: Long = 100L
    var smoothingAlpha: Float = 0.4f

    // ── Smoothing state ───────────────────────────────────────────────────────

    private var smoothCx: Float = 0.5f
    private var smoothArea: Float = -1f

    // ── Target locking ────────────────────────────────────────────────────────

    private var lastTargetCx: Float = 0.5f
    private var lastTargetCy: Float = 0.5f
    private var isTargetLocked: Boolean = false

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
        resetState()
        Log.i(TAG, "Visual servoing v5 started. Target='$label'")

        trackingJob = CoroutineScope(Dispatchers.IO).launch {
            var missedFrames = 0

            while (isActive) {

                // 1. Scatta frame
                val bitmap: Bitmap = suspendCancellableCoroutine { cont ->
                    cameraController.takeSinglePicture(
                        PepperCameraController.FrameCallback { bmp, _ ->
                            if (cont.isActive) cont.resume(bmp)
                        }
                    )
                }

                // 2. Detection
                val boxes: List<BoundingBox> = suspendCancellableCoroutine { cont ->
                    detectionController.detect(bitmap) { b, _, _ ->
                        if (cont.isActive) cont.resume(b)
                    }
                }

                // 3. Seleziona target
                val target = selectTarget(boxes, label)

                if (target == null) {
                    missedFrames++
                    Log.d(TAG, "Target '$label' not found ($missedFrames/$maxMissedFrames)")
                    if (missedFrames >= maxMissedFrames) {
                        isTargetLocked = false
                        movementController.stopMovement()
                    }
                    delay(400)
                    continue
                }

                missedFrames = 0
                updateSmoothing(target)

                // 4. Calcola errori
                val errX    = smoothCx - 0.5f
                val errArea = targetArea - smoothArea

                val needsRotation = abs(errX)    > horizontalDeadzone
                val needsAdvance  = abs(errArea) > areaDeadzone

                Log.d(TAG, "errX=%.3f errArea=%.3f | rot=$needsRotation adv=$needsAdvance"
                    .format(errX, errArea))

                // 5. FIRE AND FORGET — cancella precedente, lancia nuovo, NON aspetta
                when {
                    needsRotation -> {
                        val theta = (-kpRotation * errX)
                            .coerceIn(-maxRotationStep, maxRotationStep)
                            .toDouble()
                        Log.d(TAG, "→ Rotate theta=%.3f rad".format(theta))
                        movementController.cancelAndMove(x = 0.0, y = 0.0, theta = theta)
                    }
                    needsAdvance -> {
                        val dist = (kpForward * errArea)
                            .coerceIn(-maxAdvanceStep, maxAdvanceStep)
                            .toDouble()
                        Log.d(TAG, "→ Move dist=%.3f m".format(dist))
                        movementController.cancelAndMove(x = dist, y = 0.0, theta = 0.0)
                    }
                    else -> {
                        Log.d(TAG, "→ Target centrato.")
                        movementController.stopMovement()
                    }
                }

                delay(cycleDelayMs)
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
        resetState()
    }

    val isTracking: Boolean get() = trackingJob?.isActive == true

    // ── Selezione target ──────────────────────────────────────────────────────

    private fun selectTarget(boxes: List<BoundingBox>, label: String): BoundingBox? {
        val candidates = boxes.filter { it.label.equals(label, ignoreCase = true) }
        if (candidates.isEmpty()) return null

        return if (!isTargetLocked) {
            candidates.maxByOrNull { it.score }!!.also { best ->
                lastTargetCx   = best.cx
                lastTargetCy   = best.cy
                isTargetLocked = true
                Log.d(TAG, "Target locked cx=%.2f cy=%.2f".format(lastTargetCx, lastTargetCy))
            }
        } else {
            candidates.minByOrNull { box ->
                val dx = box.cx - lastTargetCx
                val dy = box.cy - lastTargetCy
                sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            }?.also { box ->
                lastTargetCx = box.cx
                lastTargetCy = box.cy
            }
        }
    }

    // ── Smoothing ─────────────────────────────────────────────────────────────

    private fun updateSmoothing(target: BoundingBox) {
        val rawArea = target.rect.width() * target.rect.height()
        if (smoothArea < 0f) {
            smoothCx   = target.cx
            smoothArea = rawArea
        } else {
            smoothCx   = smoothingAlpha * target.cx + (1f - smoothingAlpha) * smoothCx
            smoothArea = smoothingAlpha * rawArea    + (1f - smoothingAlpha) * smoothArea
        }
        Log.d(TAG, "Smooth cx=%.2f area=%.3f | Raw cx=%.2f area=%.3f"
            .format(smoothCx, smoothArea, target.cx, rawArea))
    }

    private fun resetState() {
        smoothCx       = 0.5f
        smoothArea     = -1f
        lastTargetCx   = 0.5f
        lastTargetCy   = 0.5f
        isTargetLocked = false
    }

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