package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.human.Human
import kotlinx.coroutines.*
import kotlin.math.sqrt

class FindHuman(
    private val qiContext: QiContext,
    private val movementController: PepperMovementController,
    private val headController: HeadMovementController
) {
    interface FindPersonListener {
        fun onPersonFound(human: Human)
        fun onPersonNotFound()
    }

    companion object {
        private const val TAG = "FindPersonController"
        private const val ROTATION_STEP_DEG = 45.0
        private const val ROTATION_STEPS    = 8        // 8 × 45grad = 360grad
        private const val STEP_DELAY_MS     = 800L     // attesa dopo ogni rotazione CHECK
        private const val ROTATION_RETRIES  = 2
        private const val RETRY_DELAY_MS    = 400L
    }

    var listener: FindPersonListener? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch { scan() }
    }

    fun stop() {
        running = false
        scope.cancel()
        movementController.stopMovement()
        Log.i(TAG, "FindPerson stopped")
    }

    private suspend fun scan() {
        Log.i(TAG, "Starting person scan — 360° rotation")

        // Ferma LookAt attivo — testa libera durante la scan
        headController.stopGaze()
        delay(200L)

        var coveredDeg = 0.0
        var step = 0
        while (step < ROTATION_STEPS) {
            if (!running) return

            val human = detectHuman()
            if (human != null) {
                Log.i(TAG, "Person found at step $step (coverage %.0f°)".format(coveredDeg))
                running = false
                withContext(Dispatchers.Main) { listener?.onPersonFound(human) }
                return
            }

            val thetaRad = Math.toRadians(ROTATION_STEP_DEG)
            var rotated = movementController.rotateAwait(thetaRad)

            var attempt = 0
            while (!rotated && running && attempt < ROTATION_RETRIES) {
                attempt++
                Log.w(TAG, "Rotation step $step failed — retry $attempt/$ROTATION_RETRIES")
                delay(RETRY_DELAY_MS)
                rotated = movementController.rotateAwait(thetaRad)
            }

            if (rotated) {
                coveredDeg += ROTATION_STEP_DEG
            } else {
                Log.e(TAG, "Rotation step $step failed after $ROTATION_RETRIES retries — sector skipped")
            }

            step++
            delay(STEP_DELAY_MS)
        }
        Log.i(TAG, "Scan coverage: %.0f° of 360°".format(coveredDeg))

        // Controllo finale: il ciclo controlla PRIMA di ruotare, quindi l'ultimo
        // settore raggiunto non verrebbe mai osservato dopo esserci arrivati.

        if (running) {
            detectHuman()?.let { human ->
                Log.i(TAG, "Person found after final rotation")
                running = false
                withContext(Dispatchers.Main) { listener?.onPersonFound(human) }
                return
            }
        }

        if (running) {
            Log.w(TAG, "Scan complete — no person found (coverage %.0f°)".format(coveredDeg))
            running = false
            withContext(Dispatchers.Main) { listener?.onPersonNotFound() }
        }
    }
    private fun detectHuman(): Human? {
        return try {
            val humans = qiContext.humanAwareness
                .async().humansAround
                .get(2, java.util.concurrent.TimeUnit.SECONDS) ?: return null

            if (humans.isEmpty()) return null

            val rFrame = qiContext.actuation.robotFrame()
            humans.minByOrNull { human ->
                try {
                    val t = human.headFrame
                        .computeTransform(rFrame).transform.translation
                    sqrt(t.x * t.x + t.y * t.y)
                } catch (_: Exception) { Double.MAX_VALUE }
            }
        } catch (_: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "humansAround timed out")
            null
        } catch (e: Exception) {
            Log.w(TAG, "HumanAwareness error: ${e.message}")
            null
        }
    }
}