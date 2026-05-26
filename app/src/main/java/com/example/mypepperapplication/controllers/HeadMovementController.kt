package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.LookAtBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.LookAt
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
// ===========================================================================
// HEAD MOVEMENT CONTROLLER
// ===========================================================================

/*
Wrapper per i movimenti della testa durante object detection nel visual servoing
*/
private const val TAG = "HeadController"

class HeadMovementController {

    private var qiContext: QiContext? = null

    private var currentLookAtFuture: Future<Void>? = null

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        Log.d(TAG, "HeadController ready.")
    }

    fun onRobotLost() {
        stopGaze()
        qiContext = null
    }
    fun setGaze(normErrX: Float = 0f, normErrY: Float = 0f) {
        val ctx = qiContext ?: run { Log.w(TAG, "QiContext null — skip gaze"); return }

        try {
            currentLookAtFuture?.requestCancellation()
            currentLookAtFuture = null

            val lateral = clamp(-normErrX * 1.2f, -1.5f, 1.5f).toDouble()

            val transform  = TransformBuilder.create().from2DTransform(2.0, lateral, 0.0)
            val robotFrame = ctx.actuation.robotFrame()
            val freeFrame  = ctx.mapping.makeFreeFrame()
            freeFrame.update(robotFrame, transform, System.currentTimeMillis())

            val lookAt: LookAt = LookAtBuilder.with(ctx)
                .withFrame(freeFrame.frame())
                .build()
            lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
            currentLookAtFuture = lookAt.async().run() as Future<Void>

            Log.v(TAG, "Gaze → lateral=%.2f (normErrX=%.2f)".format(lateral, normErrX))

        } catch (e: Exception) {
            Log.e(TAG, "setGaze error: ${e.message}")
        }
    }

    fun resetHead() = setGaze(0f, 0f)

    fun stopGaze() {
        try {
            currentLookAtFuture?.requestCancellation()
        } catch (e: Exception) {
            Log.w(TAG, "stopGaze: ${e.message}")
        }
        currentLookAtFuture = null
    }
    private fun clamp(v: Float, min: Float, max: Float) =
        if (v < min) min else if (v > max) max else v
}