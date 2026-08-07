package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.builder.LookAtBuilder
import com.aldebaran.qi.sdk.`object`.actuation.FreeFrame
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
import com.aldebaran.qi.sdk.`object`.geometry.Vector3
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

// ===========================================================================
// HEAD MOVEMENT CONTROLLER
// ===========================================================================

private const val TAG = "HeadController"

class HeadMovementController {

    private var qiContext: QiContext? = null
    private var currentLookAtFuture: Future<Void>? = null
    private var gazeFreeFrame: FreeFrame? = null
    private val isTearingDown = AtomicBoolean(false)

    fun onRobotReady(ctx: QiContext) {
        isTearingDown.set(false)
        qiContext = ctx
        Log.d(TAG, "HeadController ready.")
    }
    // Nota-> onRobotLost non può essere suspend direttamente perché è una callback nativa,
    // ma esegue un cleanup rapido azzerando i riferimenti.
    fun onRobotLost() {
        isTearingDown.set(true)
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
        scanMode: Boolean = false,
        scanHeightM: Double? = null
    ) = run {
        val lateral = clampLateral(-normErrX * 1.0f).toDouble()
        val forward = if (scanMode) 3.0 else (3.5f - normErrY * 1.8f).coerceIn(1.5f, 5.0f).toDouble()

        val transform = TransformBuilder.create().from2DTransform(forward, lateral, 0.0)
        if (scanMode && scanHeightM != null) {
            val t = transform.translation
            transform.translation = Vector3(t.x, t.y, scanHeightM)
            Log.d(TAG, "SCAN gaze target: forward=%.2f lateral=%.2f z(richiesto)=%.2f z(applicato)=%.2f"
                .format(forward, lateral, scanHeightM, transform.translation.z))

        }
        transform
    }

    fun setGaze(
        normErrX: Float = 0f,
        normErrY: Float = 0f,
        scanMode: Boolean = false,
        scanHeightM: Double? = null
    ) {
        //se è in corso un teardown (onRobotLost), non proseguire.
        if (isTearingDown.get()) {
            Log.d(TAG, "setGaze skipped — tearing down")
            return
        }

        val ctx = qiContext ?: return
        val transform = buildGazeTransform(normErrX, normErrY, scanMode, scanHeightM)
        val robotFrame = ctx.actuation.robotFrame()
        val localFrame = gazeFreeFrame

        if (localFrame == null || currentLookAtFuture?.isDone == true) {
            try {
                val newFrame = ctx.mapping.makeFreeFrame()
                newFrame.update(robotFrame, transform, System.currentTimeMillis())
                // ricontrolla subito prima di pubblicare lo stato,
                // nel caso onRobotLost() sia arrivato durante makeFreeFrame()/update().
                if (isTearingDown.get()) {
                    Log.d(TAG, "setGaze aborted mid-creation — tearing down")
                    return
                }

                gazeFreeFrame = newFrame

                val lookAt = LookAtBuilder.with(ctx)
                    .withFrame(newFrame.frame())
                    .build()

                lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
                currentLookAtFuture = lookAt.async().run() as Future<Void>
            } catch (e: Exception) {
                Log.e(TAG, "Error starting new LookAt: ${e.message}")
                gazeFreeFrame = null
                currentLookAtFuture = null
            }
        } else {
            try {
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
                if (isTearingDown.get()) {
                    Log.d(TAG, "resetHead aborted mid-creation — tearing down")
                    return
                }
                gazeFreeFrame = newFrame
                val lookAt = LookAtBuilder.with(ctx).withFrame(newFrame.frame()).build()
                lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
                currentLookAtFuture = lookAt.async().run() as Future<Void>
                Log.d(TAG, "Head reset → forward (new LookAt)")
            } catch (e: Exception) {
                Log.w(TAG, "resetHead new LookAt failed: ${e.message}")
                gazeFreeFrame = null
                currentLookAtFuture = null
            }
        }
    }
    private fun clampLateral(v: Float) =
        if (v < -1.5f) -1.5f else if (v > 1.5f) 1.5f else v
}