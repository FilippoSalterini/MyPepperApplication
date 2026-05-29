package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.geometry.Transform
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import kotlinx.coroutines.*
import kotlin.coroutines.resume

// ===========================================================================
// PEPPER MOVEMENT CONTROLLER
// ===========================================================================

private const val TAG = "PepperMovement"

class PepperMovementController {

    private var qiContext: QiContext? = null

    // Volatile garantisce che la lettura/scrittura del puntatore sia atomica tra thread diversi
    @Volatile
    private var currentGoToFuture: Future<Void>? = null

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        Log.d(TAG, "Robot ready.")
    }

    fun onRobotLost() {
        stopMovement()
        qiContext = null
    }
    suspend fun rotateAwait(theta: Double, maxSpeed: Float = 0.3f) {
        if (theta == 0.0) return
        val ctx = qiContext ?: run { Log.e(TAG, "rotateAwait: QiContext NULL"); return }

        currentGoToFuture?.requestCancellation()
        currentGoToFuture = null

        val transform  = TransformBuilder.create().from2DTransform(0.0, 0.0, theta)
        val robotFrame = ctx.actuation.robotFrame()
        val freeFrame  = ctx.mapping.makeFreeFrame()
        freeFrame.update(robotFrame, transform, System.currentTimeMillis())

        suspendCancellableCoroutine { cont ->
            val future = GoToBuilder.with(ctx)
                .withFrame(freeFrame.frame())
                .withMaxSpeed(maxSpeed)
                .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                .build()
                .async().run()

            currentGoToFuture = future

            future.thenConsume { f ->
                // FIX APPLICATO: Protezione contro sovrascritture concorrenti
                if (currentGoToFuture == future) {
                    currentGoToFuture = null
                }

                when {
                    f.isSuccess   -> { Log.d(TAG, "rotateAwait ✓ theta=$theta"); if (cont.isActive) cont.resume(Unit) }
                    f.isCancelled -> { Log.d(TAG, "rotateAwait cancelled");       if (cont.isActive) cont.resume(Unit) }
                    f.hasError()  -> {
                        Log.e(TAG, "rotateAwait error: ${f.errorMessage}")
                        CoroutineScope(Dispatchers.Default).launch {
                            delay(300L)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
            }
            cont.invokeOnCancellation { future.requestCancellation() }
        }
    }

    fun moveTowardAsync(distanceMeters: Double = 3.0) {
        val ctx = qiContext ?: run { Log.e(TAG, "moveTowardAsync: QiContext NULL"); return }

        currentGoToFuture?.requestCancellation()
        currentGoToFuture = null

        val robotFrame = ctx.actuation.robotFrame()
        val transform  = TransformBuilder.create().fromXTranslation(distanceMeters)
        val freeFrame  = ctx.mapping.makeFreeFrame()
        freeFrame.update(robotFrame, transform, System.currentTimeMillis())

        val future = GoToBuilder.with(ctx)
            .withFrame(freeFrame.frame())
            .withMaxSpeed(0.25f)
            .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
            .build()
            .async().run()

        currentGoToFuture = future

        future.thenConsume { f ->
            // FIX APPLICATO: Anche nei flussi puramente asincroni difendiamo il puntatore globale
            if (currentGoToFuture == future) {
                currentGoToFuture = null
            }

            when {
                f.isSuccess   -> Log.d(TAG, "moveTowardAsync: destination reached")
                f.isCancelled -> Log.d(TAG, "moveTowardAsync: cancelled (normal)")
                f.hasError()  -> Log.w(TAG, "moveTowardAsync error: ${f.errorMessage}")
            }
        }
    }

    fun stopMovement() {
        if (currentGoToFuture != null) {
            try {
                currentGoToFuture?.requestCancellation()
                Log.d(TAG, "stopMovement: GoTo cancellation requested.")
            } catch (e: Exception) {
                Log.w(TAG, "stopMovement error: ${e.message}")
            }
            currentGoToFuture = null
        }
    }

//    private fun buildTransform(x: Double, theta: Double): Transform {
//        return when {
//            x == 0.0     -> TransformBuilder.create().from2DTransform(0.0, 0.0, theta)
//            theta == 0.0 -> TransformBuilder.create().fromXTranslation(x)
//            else         -> TransformBuilder.create().from2DTransform(x, 0.0, theta)
//        }
//    }
}