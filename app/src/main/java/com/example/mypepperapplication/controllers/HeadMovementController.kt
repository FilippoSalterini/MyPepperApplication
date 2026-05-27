package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.builder.LookAtBuilder
import com.aldebaran.qi.sdk.`object`.actuation.FreeFrame
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
    private var gazeFreeFrame: FreeFrame? = null

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        Log.d(TAG, "HeadController ready.")
    }

    fun onRobotLost() { stopGaze(); qiContext = null }

    fun setGaze(normErrX: Float = 0f, normErrY: Float = 0f) {
        val ctx = qiContext ?: return

        val lateral = clampLateral(-normErrX * 1.2f).toDouble()
        val thetaHeight = (-normErrY * 0.5f).toDouble()

        val transform = TransformBuilder.create().from2DTransform(0.8, lateral, thetaHeight)

        val robotFrame = ctx.actuation.robotFrame()
        val localFrame = gazeFreeFrame

        if (localFrame == null || currentLookAtFuture?.isDone == true) {
            val newFrame = ctx.mapping.makeFreeFrame()
            newFrame.update(robotFrame, transform, System.currentTimeMillis())
            gazeFreeFrame = newFrame

            val lookAt = LookAtBuilder.with(ctx)
                .withFrame(newFrame.frame())
                .build()
            lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
            currentLookAtFuture = lookAt.async().run() as Future<Void>
            Log.d(TAG, "LookAt started")
        } else {
            localFrame.update(robotFrame, transform, System.currentTimeMillis())
        }

        Log.v(TAG, "Gaze → lateral=%.2f, thetaHeight=%.2f".format(lateral, thetaHeight))
    }

    fun resetHead() = setGaze(0f, 0f)

    fun stopGaze() {
        currentLookAtFuture?.requestCancellation()
        currentLookAtFuture = null
        gazeFreeFrame = null
    }

    // CLAMPING LATERALE serve per limitare valori di lateral tra -1.5 e 1.5
    // ed evita che si presentino valori di lateral troppo grandi
    private fun clampLateral(v: Float) =
        if (v < -1.5f) -1.5f else if (v > 1.5f) 1.5f else v
}