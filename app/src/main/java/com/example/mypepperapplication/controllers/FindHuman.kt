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

        repeat(ROTATION_STEPS) { step ->
            if (!running) return

            val human = detectHuman()
            if (human != null) {
                Log.i(TAG, "Person found at step $step")
                running = false
                withContext(Dispatchers.Main) { listener?.onPersonFound(human) }
                return
            }

            val thetaRad = Math.toRadians(ROTATION_STEP_DEG)
            movementController.rotateAwait(thetaRad)
            delay(STEP_DELAY_MS)
        }

        if (running) {
            Log.w(TAG, "Scan complete — no person found")
            running = false
            withContext(Dispatchers.Main) { listener?.onPersonNotFound() }
        }
    }
    private fun detectHuman(): Human? {
        return try {
            val humans = qiContext.humanAwareness
                .async().humansAround
                .get() ?: return null

            if (humans.isEmpty()) return null

            // Restituisce la persona più vicina
            val rFrame = qiContext.actuation.robotFrame()
            humans.minByOrNull { human ->
                try {
                    val t = human.headFrame
                        .computeTransform(rFrame).transform.translation
                    sqrt(t.x * t.x + t.y * t.y)
                } catch (_: Exception) { Double.MAX_VALUE }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HumanAwareness error: ${e.message}")
            null
        }
    }
}