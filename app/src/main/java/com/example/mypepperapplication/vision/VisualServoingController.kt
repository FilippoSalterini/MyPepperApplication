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

class VisualServoingController(
    private val movementController: PepperMovementController
) {

    private var trackingJob: Job? = null

    // ── Parametri ─────────────────────────────────────────────────────────────
    var targetLabel: String? = "person"
    var kpRotation: Float = 1.5f
    var kpForward: Float = 1.0f
    var maxRotationStep: Float = 0.25f
    var maxAdvanceStep: Float = 1f
    var horizontalDeadzone: Float = 0.05f
    var targetArea: Float = 0.5f
    var areaDeadzone: Float = 0.04f
    var maxMissedFrames: Int = 8
    var cycleDelayMs: Long = 120L
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
                    delay(150)
                    continue
                }

                missedFrames = 0
                updateSmoothing(target)

                // 4. Calcola errori
                val errX = smoothCx - 0.5f
                val errArea = targetArea - smoothArea

                val needsRotation = abs(errX) > horizontalDeadzone
                val needsAdvance = abs(errArea) > areaDeadzone

                Log.d(
                    TAG, "errX=%.3f errArea=%.3f | rot=$needsRotation adv=$needsAdvance"
                        .format(errX, errArea)
                )

                when {
                    needsRotation -> {
                        val theta = (-kpRotation * errX)
                            .coerceIn(-maxRotationStep, maxRotationStep)
                            .toDouble()
                        Log.i(TAG, ">>> ROTATE theta=%.3f rad (errX=%.3f)".format(theta, errX))
                        movementController.cancelAndMove(x = 0.0, theta = theta)
                        delay(500L)  // lascia tempo al GoTo
                    }

                    needsAdvance -> {
                        val dist = (kpForward * errArea)
                            .coerceIn(-maxAdvanceStep, maxAdvanceStep)
                            .toDouble()
                        Log.i(TAG, ">>> ADVANCE dist=%.3f m (errArea=%.3f)".format(dist, errArea))
                        movementController.cancelAndMove(x = dist, theta = 0.0)
                        delay(700L)
                    }

                    else -> {
                        Log.d(TAG, "→ Target centrato, fermo.")
                        movementController.stopMovement()
                    }
                }
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
            smoothCx   = smoothingAlpha * target.cx   + (1f - smoothingAlpha) * smoothCx
            smoothArea = smoothingAlpha * rawArea      + (1f - smoothingAlpha) * smoothArea
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