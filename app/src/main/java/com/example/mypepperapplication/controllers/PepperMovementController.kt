package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.GoTo
import com.aldebaran.qi.sdk.`object`.geometry.Transform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

//    fun cancelAndMove(x: Double = 0.0, theta: Double = 0.0) {
//        if (x == 0.0 && theta == 0.0) {
//            Log.i(TAG, "cancelAndMove called: no movement")
//            return
//        }
//        val ctx = qiContext ?: run {
//            Log.e(TAG, "cancelAndMove: QiContext NULL — robot non pronto!")
//            return
//        }
//        Log.i(TAG, "cancelAndMove called: x=$x theta=$theta")
//
//        currentGoToFuture?.requestCancellation()
//        currentGoToFuture = null
//
//        val transform: Transform = buildTransform(x, theta)
//        val robotFrame = ctx.actuation.robotFrame()
//        val targetFreeFrame = ctx.mapping.makeFreeFrame()
//        targetFreeFrame.update(robotFrame, transform, 0L)
//
//        val goTo: GoTo = GoToBuilder.with(ctx)
//            .withFrame(targetFreeFrame.frame())
//            .build()
//
//        currentGoToFuture = goTo.async().run().thenConsume { future ->
//            when {
//                future.isSuccess  -> {
//                    currentGoToFuture = null
//                    Log.i(TAG, "GoTo completed ✓ x=$x theta=$theta")
//                }
//                future.isCancelled -> Log.i(TAG, "GoTo cancelled")
//                future.hasError()  -> Log.e(TAG, "GoTo ERROR: ${future.errorMessage}")
//            }
//        }
//    }

    //aspetta il completamento reale del GoTo

    suspend fun cancelAndMoveAwait(x: Double = 0.0, theta: Double = 0.0) {
        if (x == 0.0 && theta == 0.0) {
            Log.i(TAG, "cancelAndMoveAwait: no movement")
            return
        }
        val ctx = qiContext ?: run {
            Log.e(TAG, "cancelAndMoveAwait: QiContext NULL — robot non pronto!")
            return
        }
        Log.i(TAG, "cancelAndMoveAwait called: x=$x theta=$theta")

        currentGoToFuture?.requestCancellation()
        currentGoToFuture = null

        val transform: Transform = buildTransform(x, theta)
        val robotFrame = ctx.actuation.robotFrame()
        val targetFreeFrame = ctx.mapping.makeFreeFrame()
        targetFreeFrame.update(robotFrame, transform, 0L)

        val goTo: GoTo = GoToBuilder.with(ctx)
            .withFrame(targetFreeFrame.frame())
            .build()

        suspendCancellableCoroutine { cont ->
            val future = goTo.async().run()
            currentGoToFuture = future

            future.thenConsume { f ->
                when {
                    f.isSuccess -> {
                        currentGoToFuture = null
                        Log.i(TAG, "GoTo completed ✓ x=$x theta=$theta")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    f.isCancelled -> {
                        Log.i(TAG, "GoTo cancelled — loop può ripartire")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    f.hasError() -> {
                        Log.e(TAG, "GoTo ERROR: ${f.errorMessage}")
                        Thread.sleep(300L)
                        if (cont.isActive) cont.resume(Unit) // non bloccare il loop
                    }
                }
            }

            cont.invokeOnCancellation {
                future.requestCancellation()
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