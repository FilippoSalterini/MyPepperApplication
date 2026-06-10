package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.builder.LookAtBuilder
import com.aldebaran.qi.sdk.`object`.actuation.FreeFrame
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
import kotlinx.coroutines.delay

// ===========================================================================
// HEAD MOVEMENT CONTROLLER
// ===========================================================================

private const val TAG = "HeadController"

class HeadMovementController {

    private var qiContext: QiContext? = null
    private var currentLookAtFuture: Future<Void>? = null
    private var gazeFreeFrame: FreeFrame? = null

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        Log.d(TAG, "HeadController ready.")
    }

    // Nota: onRobotLost non può essere suspend direttamente perché è una callback nativa,
    // ma esegue un cleanup rapido azzerando i riferimenti.
    fun onRobotLost() {
        try {
            currentLookAtFuture?.requestCancellation()
        } catch (_: Exception) {}
        currentLookAtFuture = null
        gazeFreeFrame = null
        qiContext = null
        Log.d(TAG, "HeadController lost.")
    }

    private fun buildGazeTransform(
        normErrX: Float,
        normErrY: Float,
        scanMode: Boolean = false
    ) = run {
        val lateral = clampLateral(-normErrX * 1.0f).toDouble() //prima era -normerrx * 1.3f

        // Distanza avanti: più grande = testa più alta
        val forward = if (scanMode) {
            3.5
        } else {
            (2.2f - normErrY * 1.8f)
                .coerceIn(1.2f, 4.0f)
                .toDouble()
        }

        TransformBuilder.create().from2DTransform(forward, lateral, 0.0)
    }

    /**
     * Aggiorna lo sguardo del robot. Rimane una funzione normale (non-suspend)
     * perché l'aggiornamento del FreeFrame o l'avvio asincrono del LookAt non sono bloccanti.
     */
    fun setGaze(
        normErrX: Float = 0f,
        normErrY: Float = 0f,
        scanMode: Boolean = false
    ) {
        val ctx = qiContext ?: return
        val transform = buildGazeTransform(normErrX, normErrY, scanMode)
        val robotFrame = ctx.actuation.robotFrame()
        val localFrame = gazeFreeFrame

        if (localFrame == null || currentLookAtFuture?.isDone == true) {
            try {
                val newFrame = ctx.mapping.makeFreeFrame()
                newFrame.update(robotFrame, transform, System.currentTimeMillis())
                gazeFreeFrame = newFrame

                val lookAt = LookAtBuilder.with(ctx)
                    .withFrame(newFrame.frame())
                    .build()

                lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
                currentLookAtFuture = lookAt.async().run() as Future<Void>
            } catch (e: Exception) {
                Log.e(TAG, "Error starting new LookAt: ${e.message}")
            }
        } else {
            try {
                // Aggiornamento fluido del frame esistente senza ricreare il LookAt nativo
                localFrame.update(robotFrame, transform, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Error updating FreeFrame: ${e.message}")
            }
        }
    }

    suspend fun stopGaze() {
        if (currentLookAtFuture == null) return

        try {
            currentLookAtFuture?.requestCancellation()
            Log.d(TAG, "Requested LookAt cancellation.")
        } catch (e: Exception) {
            Log.w(TAG, "stopGaze cancel error: ${e.message}")
        }

        delay(150L)

        currentLookAtFuture = null
        gazeFreeFrame = null
        Log.d(TAG, "Gaze stopped cleanly.")
    }

    suspend fun resetHead() {
        val ctx = qiContext ?: run {
            Log.d(TAG, "resetHead: QiContext null, skipping")
            gazeFreeFrame = null
            currentLookAtFuture = null
            return
        }

        val forward = TransformBuilder.create().from2DTransform(3.0, 0.0, 0.0)
        val robotFrame = ctx.actuation.robotFrame()
        val localFrame = gazeFreeFrame

        if (localFrame != null && currentLookAtFuture?.isDone == false) {
            try {
                localFrame.update(robotFrame, forward, System.currentTimeMillis())
                Log.d(TAG, "Head reset → forward (frame updated)")
            } catch (e: Exception) {
                Log.w(TAG, "resetHead frame update failed: ${e.message}")
                stopGaze()
            }
        } else {
            stopGaze()
            try {
                val newFrame = ctx.mapping.makeFreeFrame()
                newFrame.update(robotFrame, forward, System.currentTimeMillis())
                gazeFreeFrame = newFrame

                val lookAt = LookAtBuilder.with(ctx).withFrame(newFrame.frame()).build()
                lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
                currentLookAtFuture = lookAt.async().run() as Future<Void>

                Log.d(TAG, "Head reset → forward (new LookAt)")
            } catch (e: Exception) {
                Log.w(TAG, "resetHead new LookAt failed: ${e.message}")
            }
        }
    }

    private fun clampLateral(v: Float) =
        if (v < -1.5f) -1.5f else if (v > 1.5f) 1.5f else v
}