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
 * VisualServoingController — Image-Based Visual Servoing (IBVS) semplificato.
 *
 * Architettura corretta (PC-side inference):
 *
 *   Pepper Camera → JPEG → PC YOLOv8 → BoundingBox → [questo controller] → PepperMovement
 *
 * Il loop di controllo gira su Pepper (coroutine IO), ma:
 *   - L'inferenza AI è già avvenuta sul PC
 *   - Questo controller riceve solo bbox già pronte
 *   - Calcola errori e comanda il movimento
 *
 * Schema controllo P (proporzionale):
 *
 *   errX    = cx_target - 0.5   → [-0.5, +0.5]  negativo = sinistra
 *   errArea = targetArea - boxArea              negativo = troppo lontano
 *
 * Decisione:
 *   |errX| > horizontalDeadzone  → ruota verso il target
 *   |errArea| > areaDeadzone     → avanza / arretra
 *   entrambi sotto soglia        → target centrato, fermo

 */
class VisualServoingController(
    private val movementController: PepperMovementController
) {

    // ── Stato ─────────────────────────────────────────────────────────────────

    private var trackingJob: Job? = null

    // ── Parametri controllo ───────────────────────────────────────────────────

    /** Label da inseguire. Null = score più alto tra tutte le detections. */
    var targetLabel: String? = "person"

    /** Dead-zone orizzontale: sotto questa soglia non ruota. Range [0, 0.5]. */
    var horizontalDeadzone: Float = 0.08f

    /** Area normalizzata desiderata nel frame. Il robot avanza finché è minore. */
    var targetArea: Float = 0.15f

    /** Soglia area: sotto questa differenza non avanza. */
    var areaDeadzone: Float = 0.03f

    /** Angolo di rotazione per step (radianti). Riduci se il robot oscilla. */
    var rotationStep: Float = 0.20f

    /** Distanza avanzamento per step (metri). */
    var advanceStep: Float = 0.25f

    /** Frame consecutivi senza target prima di fermarsi. */
    var maxMissedFrames: Int = 5

    /**
     * Intervallo minimo tra un ciclo detect e il successivo (ms).
     * YOLOv8n su CPU Intel UHD ≈ 300-600ms → usa 500ms come default sicuro.
     * Abbassa a 300ms se la tua LAN è veloce e il PC risponde bene.
     */
    var loopIntervalMs: Long = 500L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onRobotReady(ctx: QiContext) {
        movementController.onRobotReady(ctx)
        Log.d(TAG, "Robot ready, movement controller initialized.")
    }

    fun onRobotLost() {
        stopTracking()
        movementController.onRobotLost()
        Log.d(TAG, "Robot lost.")
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Avvia il loop di visual servoing.
     *
     * @param cameraController    sorgente frame da Pepper
     * @param detectionController invia frame al PC e riceve bbox
     * @param label               label da seguire (default: "person")
     */
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

                // 1. Cattura frame da Pepper (snapshot singolo per ciclo)
                var frameCaptured = false
                cameraController.takeSinglePicture { bitmap, _ ->
                    if (!isActive) return@takeSinglePicture
                    frameCaptured = true

                    // 2. Invia al PC per detection
                    detectionController.detect(bitmap) { boxes, _, _ ->
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

                // 3. Rispetta l'intervallo minimo tra cicli
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
     * Seleziona il box target:
     *   1. Cerca per label corrispondente → prende quello con score più alto
     *   2. Se nessuno corrisponde → prende il box con score più alto in assoluto
     */
    private fun selectTarget(boxes: List<BoundingBox>, label: String): BoundingBox? {
        if (boxes.isEmpty()) return null
        val byLabel = boxes.filter { it.label.equals(label, ignoreCase = true) }
        return byLabel.maxByOrNull { it.score } ?: boxes.maxByOrNull { it.score }
    }

    // ── Controllo proporzionale P ─────────────────────────────────────────────

    /**
     * Applica controllo proporzionale:
     *
     *   Priorità 1: centra orizzontalmente (rotazione)
     *   Priorità 2: raggiungi la distanza target (avanzamento)
     *
     * La separazione delle priorità evita movimenti diagonali instabili.
     */
    private fun applyControl(target: BoundingBox) {
        val errX    = target.cx - 0.5f
        val boxArea = target.rect.width() * target.rect.height()
        val errArea = targetArea - boxArea

        val needsRotation = abs(errX) > horizontalDeadzone
        val needsAdvance  = abs(errArea) > areaDeadzone

        Log.d(TAG, "errX=%.3f errArea=%.3f | rot=$needsRotation adv=$needsAdvance"
            .format(errX, errArea))

        when {
            needsRotation -> {
                // errX positivo = target a destra → ruota a destra (theta negativo in QiSDK)
                val theta = -errX.sign * rotationStep
                Log.d(TAG, "→ Rotate theta=%.2f rad".format(theta))
                movementController.rotateByAngle(theta.toDouble())
            }
            needsAdvance -> {
                // errArea positivo = troppo lontano → avanza
                val dist = errArea.sign * advanceStep
                Log.d(TAG, "→ Move dist=%.2f m".format(dist))
                movementController.moveByDistance(dist.toDouble())
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
            "[${target.source}] '${target.label}' " +
                    "cx=%.2f cy=%.2f area=%.3f score=%.0f%%"
                        .format(target.cx, target.cy, area, target.score * 100)
        } else {
            "Target '$targetLabel' NOT FOUND in ${boxes.size} detections"
        }
    }
}