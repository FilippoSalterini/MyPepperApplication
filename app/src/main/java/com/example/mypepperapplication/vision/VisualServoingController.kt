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

    // Parametri di controllo
    var targetArea:       Float  = 0.035f
    var kpRotation:       Float  = 0.8f
    var maxRotationStep:  Float  = 0.35f
    var bodyRotationZone: Float  = 0.12f
    var headOnlyZone:     Float  = 0.05f
    var lpfAlpha:         Float  = 0.5f
    var maxMissedFrames:  Int    = 10
    var cycleDelayMs:     Long   = 250L

    var centeredFrames = 0
    val centeredRequired = 3

    // Parametri di Scan
    var scanStepRad:      Double = 0.25
    var scanSteps:        Int    = 14
    private val approachCorrectionZone = 0.25f

    // Stall detector
    private val stallThreshold = 0.003f
    private val stallMaxFrames = 8
    var scanDelayMs:        Long   = 300L

    var listener: VisualServoingListener? = null

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
                val hit = runDetection(detectionController, bmp).bestMatch(labels)

                if (hit != null) {
                    Log.i(TAG, "SCAN HIT [${hit.label}] score=${hit.score} idx=$idx")
                    headController.stopGaze()
                    delay(150L)
                    headController.setGaze(normErrX = hit.cx - 0.5f, normErrY = hit.cy - 0.5f)
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

            // ── FASE 1: CENTRAGGIO DI PRECISIONE ──────────────────────────────
            /*
            - rotate stall count rete di sicurezza, se robot si trova in una situazione di
            microscatti inifiniti causa rumore pixel il codice non si frezza ma porosegue
            alla fase 2
            - utilizzo di un LPF che serve per atteuare il jitter della bb di yolo,
            permettendo ai comandi inviati alla testa di essere morbidi
             */
            var rotateStallCount = 0
            var lastRawErrX = 0f
            var nearZoneFrames = 0
            val nearZoneRequired = 4
            var missedFrames  = 0
            centeredFrames    = 0
            smoothErrX        = 0f
            smoothErrY        = 0f

            headController.stopGaze()
            delay(150L)

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
                                Log.w(TAG, "ROTATE STALL MAX — forcing PHASE 2")
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

            // ── FASE 2: APPROCCIO CONTINUO BILANCIATO ─────────────────────────
            /*
            Fase da fixare per quanto riguarda la gestione degli errori per l approccio
            continuo
             */
            Log.i(TAG, "PHASE 2 — continuous approach")
            missedFrames = 0
            var stallFrames = 0
            var lastArea    = 0f

            smoothErrX = 0f
            smoothErrY = 0f
            headController.stopGaze()
            delay(200L)

            headController.setGaze(normErrX = 0f, normErrY = 0f)

            movementController.moveTowardAsync(distanceMeters = 1.5)
            delay(400L)

            while (isActive) {
                delay(300L)
                val bitmap = captureFrame(cameraController)

                val target = runDetection(detectionController, bitmap)
                    .filter { box -> labels.any { it.equals(box.label, ignoreCase = true) } }
                    .let { boxes ->
                        if (lastArea > 0f && boxes.size > 1) {
                            boxes.minByOrNull { abs(it.rect.width() * it.rect.height() - lastArea) }
                        } else {
                            boxes.maxByOrNull { it.score }
                        }
                    }

                if (target == null) {
                    missedFrames++
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

                smoothErrX = lpfAlpha * rawErrX + (1f - lpfAlpha) * smoothErrX
                smoothErrY = lpfAlpha * rawErrY + (1f - lpfAlpha) * smoothErrY

                headController.setGaze(normErrX = smoothErrX, normErrY = smoothErrY)

                val area = target.rect.width() * target.rect.height()
                Log.d(TAG, "APPROACH [${target.label}] area=%.4f target=%.4f rawErrX=%.3f".format(area, targetArea, rawErrX))

                if (abs(smoothErrX) > approachCorrectionZone) {
                    Log.i(TAG, "APPROACH correction rotate rawErrX=%.3f. Stopping layout engines...".format(rawErrX))

                    movementController.stopMovement()

                    headController.stopGaze()
                    delay(400L)

                    val theta = (-kpRotation * rawErrX).coerceIn(-maxRotationStep, maxRotationStep).toDouble()
                    // retry in caso di "Move task not started"
                    var rotated = false
                    repeat(2) {
                        if (!rotated) {
                            try {
                                movementController.rotateAwait(theta = theta, maxSpeed = 0.3f)
                                rotated = true
                            } catch (e: Exception) {
                                Log.w(TAG, "rotateAwait failed, retrying after delay: ${e.message}")
                                delay(300L)
                            }
                        }
                    }
                    smoothErrX = 0f
                    smoothErrY = 0f

                    val estimatedRemainingDistance = ((targetArea / area.coerceAtLeast(0.001f)) * 0.5f).coerceIn(0.4f, 1.5f).toDouble()
                    Log.i(TAG, "Resuming progressive translation: %.2fm".format(estimatedRemainingDistance))

                    movementController.moveTowardAsync(distanceMeters = estimatedRemainingDistance)
                    delay(500L)
                    continue
                }

                // Stall detector
                if (abs(area - lastArea) < stallThreshold) stallFrames++ else stallFrames = 0
                lastArea = area

                if (stallFrames > stallMaxFrames) {
                    Log.i(TAG, "APPROACH STALL — target reached by proximity (rawErrX=%.3f)".format(rawErrX))
                    movementController.stopMovement()
                    delay(400L)

                    if (abs(rawErrX) > 0.08f) {
                        val theta = (-kpRotation * rawErrX * 0.5f).coerceIn(-0.15f, 0.15f).toDouble()
                        try {
                            headController.stopGaze()
                            delay(150L)
                            movementController.rotateAwait(theta = theta, maxSpeed = 0.2f)
                        } catch (e: Exception) { Log.e(TAG, "Error during stall alignment: ${e.message}") }
                    }

                    headController.resetHead()
                    listener?.onObjectReached(target.label, target)
                    return@launch
                }

                if (area >= targetArea) {
                    Log.i(TAG, "APPROACH DONE — area=%.4f >= target=%.4f".format(area, targetArea))
                    movementController.stopMovement()
                    delay(500L)

                    if (abs(rawErrX) > 0.08f) {
                        val theta = (-kpRotation * rawErrX * 0.5f).coerceIn(-0.15f, 0.15f).toDouble()
                        try {
                            headController.stopGaze()
                            delay(150L)
                            movementController.rotateAwait(theta = theta, maxSpeed = 0.2f)
                            delay(300L)
                        } catch (e: Exception) { Log.e(TAG, "Error during final alignment: ${e.message}") }
                    }

                    headController.resetHead()
                    listener?.onObjectReached(target.label, target)
                    return@launch
                }
            }
            Log.i(TAG, "Tracking loop finished.")
        }
    }

    suspend fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Log.i(TAG, "Requesting tracking job cancellation...")
            trackingJob?.cancel()
            try {
                withTimeoutOrNull(500L) { trackingJob?.join() }
            } catch (e: Exception) {
             // try { trackingJob?.join() } catch (e: Exception) {
                Log.w(TAG, "Error joining job: ${e.message}") }
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