package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.GoTo
import com.aldebaran.qi.sdk.`object`.geometry.Transform
private const val TAG = "PepperMovement"

class PepperMovementController {

    private var qiContext: QiContext? = null
    private var currentGoToFuture: Future<Void>? = null
    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        Log.d(TAG, "Robot ready, autonomous abilities held.")
    }
    fun onRobotLost() {
        stopMovement()
        qiContext = null
    }

    fun cancelAndMove(x: Double = 0.0, theta: Double = 0.0) {
        // se viene chiamato per errore un cancelAndMove con parametri nulli viene costrito
        // e lanciato un goTo che non si muove--> aggiunto un guard per evitare questo errore
        if(x == 0.0 && theta == 0.0){
            Log.i(TAG, "cancelAndMove called: no movement")
            return
        }

        val ctx = qiContext ?: run {
            Log.e(TAG, "cancelAndMove: QiContext NULL — robot non pronto!")
            return
        }
        Log.i(TAG, "cancelAndMove called: x=$x theta=$theta")  // ← aggiungi questo

        currentGoToFuture?.requestCancellation()
        currentGoToFuture = null

        val transform: Transform = buildTransform(x, theta)
        val robotFrame = ctx.actuation.robotFrame()
        val targetFreeFrame = ctx.mapping.makeFreeFrame()
        targetFreeFrame.update(robotFrame, transform, 0L)

        val goTo: GoTo = GoToBuilder.with(ctx)
            .withFrame(targetFreeFrame.frame())
            .build()

        currentGoToFuture = goTo.async().run().thenConsume { future ->
            when {
                future.isSuccess -> {
                    currentGoToFuture = null
                    Log.i(TAG, "GoTo completed ✓ x=$x theta=$theta")
                }                future.isCancelled -> Log.i(TAG, "GoTo cancelled")
                future.hasError()  -> Log.e(TAG, "GoTo ERROR: ${future.errorMessage}")
            }
        }
    }

    fun stopMovement() {
        currentGoToFuture?.requestCancellation()
        currentGoToFuture = null
    }

    private fun buildTransform(x: Double, theta: Double): Transform {
        return if (x == 0.0 && theta != 0.0) {
            TransformBuilder.create().from2DTransform(0.0, 0.0, theta)
        } else if (x != 0.0 && theta == 0.0) {
            TransformBuilder.create().fromXTranslation(x)
        } else {
            TransformBuilder.create().from2DTransform(x, 0.0, theta)
        }
    }
}