package com.example.mypepperapplication.vision

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import com.example.mypepperapplication.controllers.PepperMovementController

private const val TAG = "VisualServoing"
class VisualServoingController {
    private val movementController = PepperMovementController()
    /**
     * Visual Servoing per Pepper.
     *
     * Data una lista di [BoundingBox] dal rilevatore, questo controller:
     *   1. Seleziona il target (per label o per confidenza massima).
     *   2. Calcola l'errore rispetto al centro del frame.
     *   3. Genera comandi di movimento (rotazione + avanzamento) per ridurre l'errore.
     *
     * Schema di controllo: Image-Based Visual Servoing (IBVS) semplificato.
     *
     *   errX = cx_target - 0.5   (negativo → target a sinistra, positivo → destra)
     *   errY = cy_target - 0.5   (negativo → target in alto, positivo → in basso)
     *   errArea = targetArea - currentArea  (negativo → troppo lontano)
     *
     * Azioni:
     *   - errX > deadzone  → ruota a destra
     *   - errX < -deadzone → ruota a sinistra
     *   - errArea < -areaThreshold → avanza
     *   - errArea >  areaThreshold → indietreggia (opzionale)
     *
     * Integrazione con il progetto esistente:
     *   Chiamare [startTracking] passando il label da seguire (es. "person").
     *   Il controller legge i frame da [PepperCameraController] e le detections
     *   da [ObjectDetectionController] tramite callback.
     */
    // ── Stato ─────────────────────────────────────────────────────────────────

    private var qiContext: QiContext? = null
    private var trackingJob: Job? = null

    /** Label da inseguire (es. "person", "chair"). Null = target con score più alto. */
    var targetLabel: String? = "person"

    /** Metà della dead-zone orizzontale (0..0.5). Sotto questa soglia non ruota. */
    var horizontalDeadzone: Float = 0.08f

    /** Area target normalizzata desiderata [0,1]. Il robot avanza finché l'area è minore. */
    var targetArea: Float = 0.15f

    /** Soglia di errore area sotto cui non avanza. */
    var areaDeadzone: Float = 0.03f

    /** Passo di rotazione in radianti per ogni correzione. */
    var rotationStep: Float = 0.25f

    /** Passo di avanzamento in metri per ogni correzione. */
    var advanceStep: Float = 0.3f

    /** Massimi tentativi consecutivi senza target prima di fermarsi. */
    var maxMissedFrames: Int = 5

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
    }

    fun onRobotLost() {
        stopTracking()
        qiContext = null
    }

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Avvia il loop di visual servoing.
     *
     * @param cameraController   sorgente frame
     * @param detectionController  rilevatore bounding box
     * @param label              label da seguire (sovrascrive [targetLabel])
     */
    fun startTracking(
        cameraController: PepperCameraController,
        detectionController: ObjectDetectionController,
        label: String = targetLabel ?: "person"
    ) {
        targetLabel = label
        if (trackingJob?.isActive == true) stopTracking()

        Log.i(TAG, "Visual servoing started. Target: '$label'")
        trackingJob = CoroutineScope(Dispatchers.IO).launch {
            var missedFrames = 0

            cameraController.startContinuousCapture { bitmap, _ ->
                if (!isActive) return@startContinuousCapture

                detectionController.detect(bitmap) { boxes, imageW, imageH ->
                    val target = selectTarget(boxes, label)

                    if (target == null) {
                        missedFrames++
                        Log.d(TAG, "Target '$label' not found ($missedFrames/$maxMissedFrames)")
                        if (missedFrames >= maxMissedFrames) {
                            Log.w(TAG, "Target lost, stopping movement.")
                            movementController.stopMovement()
                        }
                        return@detect
                    }

                    missedFrames = 0
                    applyControl(target)
                }
            }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        movementController.stopMovement()
        Log.i(TAG, "Visual servoing stopped.")
    }

    // ── Controllo ─────────────────────────────────────────────────────────────

    /**
     * Seleziona il box target dalla lista delle detections.
     * Priorità: label corrispondente → score massimo.
     */
    private fun selectTarget(boxes: List<BoundingBox>, label: String): BoundingBox? {
        val candidates = boxes.filter { it.label.equals(label, ignoreCase = true) }
        return if (candidates.isNotEmpty()) {
            candidates.maxByOrNull { it.score }
        } else {
            boxes.maxByOrNull { it.score }
        }
    }

    /**
     * Applica il controllo proporzionale P.
     *
     *   errX  ∈ [-0.5, +0.5]  → rotazione
     *   errArea ∈ [-1, +1]    → avanzamento/retrocessione
     */
    private fun applyControl(target: BoundingBox) {
        val errX = target.cx - 0.5f     // positivo = target a destra
        val boxArea = target.rect.width() * target.rect.height()
        val errArea = targetArea - boxArea // positivo = troppo lontano

        val needsRotation = abs(errX) > horizontalDeadzone
        val needsAdvance = abs(errArea) > areaDeadzone

        Log.d(
            TAG, "Control: errX=%.3f errArea=%.3f rot=$needsRotation adv=$needsAdvance"
                .format(errX, errArea)
        )

        when {
            // Prima centra orizzontalmente (priorità alta)
            needsRotation -> {
                val theta = -errX.sign * rotationStep  // negativo = ruota verso il target
                Log.d(TAG, "Rotating theta=$theta (errX=$errX)")
                movementController.rotateByAngle(theta.toDouble())
            }

            // Poi avanza/retrocedi per raggiungere la distanza target
            needsAdvance -> {
                val dist = errArea.sign * advanceStep
                Log.d(TAG, "Advancing x=$dist (errArea=$errArea)")
                movementController.moveByDistance(dist.toDouble())
            }

            else -> {
                Log.d(TAG, "Target centered and at correct distance. No movement needed.")
            }
        }
    }

    // ── Dati diagnostici ─────────────────────────────────────────────────────

    /**
     * Restituisce una stringa di debug con lo stato corrente del servoing.
     */
    fun debugInfo(boxes: List<BoundingBox>): String {
        val target = boxes.firstOrNull { it.label == targetLabel }
        return if (target != null) {
            "Target '${target.label}' @ cx=%.2f cy=%.2f area=%.3f score=%.2f"
                .format(
                    target.cx, target.cy,
                    target.rect.width() * target.rect.height(),
                    target.score
                )
        } else {
            "Target '${targetLabel}' NOT FOUND (${boxes.size} detections)"
        }
    }
}