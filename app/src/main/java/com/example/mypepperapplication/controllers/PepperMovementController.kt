package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.geometry.Transform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
// ===========================================================================
// PEPPER MOVEMENT CONTROLLER
// ===========================================================================

/* Wrapper per i movimenti di traslazione e rotazione del robot utilizzando goTo
e freeFrame
*/
private const val TAG = "PepperMovement"

class PepperMovementController {

    private var qiContext: QiContext? = null
    private var currentGoToFuture: Future<Void>? = null

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        Log.d(TAG, "Robot ready.")
    }

    fun onRobotLost() {
        stopMovement()
        qiContext = null
    }
    // Crea un FreeFrame relativo al robotFrame corrente con la rotazione theta (radianti).
    // Lancia GoTo in modo asincrono e ritorna subito (non-blocking).
    // Cancella il movimento precedente prima di lanciare il nuovo (evita accodamento).
    // Usato dal VisualServoingController per le rotazioni di centraggio (fase 1).
    fun moveNonBlocking(theta: Double) {
        if (theta == 0.0) return
        val ctx = qiContext ?: run {
            Log.e(TAG, "moveNonBlocking: QiContext NULL")
            return
        }
        currentGoToFuture?.requestCancellation()
        currentGoToFuture = null

        val transform  = TransformBuilder.create().from2DTransform(0.0, 0.0, theta)
        val robotFrame = ctx.actuation.robotFrame()
        val freeFrame  = ctx.mapping.makeFreeFrame()
        freeFrame.update(robotFrame, transform, 0L)

        val future = GoToBuilder.with(ctx)
            .withFrame(freeFrame.frame())
            .build()
            .async().run()

        currentGoToFuture = future
        future.thenConsume { f ->
            currentGoToFuture = null
            when {
                f.isSuccess   -> Log.d(TAG, "Rotation ✓ theta=$theta")
                f.isCancelled -> Log.d(TAG, "Rotation cancelled")
                f.hasError()  -> Log.w(TAG, "Rotation error: ${f.errorMessage}")
            }
        }
    }
    // Versione sospendente (coroutine) di moveNonBlocking.
    // Attende il completamento del GoTo prima di ritornare — usato per gli avanzamenti (fase 2).
    // In caso di errore aspetta 300ms e poi riprende (evita loop infiniti veloci).
    // cont.invokeOnCancellation cancella il Future QiSDK se la coroutine viene cancellata.
    suspend fun cancelAndMoveAwait(x: Double = 0.0, theta: Double = 0.0) {
        if (x == 0.0 && theta == 0.0) return
        val ctx = qiContext ?: run {
            Log.e(TAG, "cancelAndMoveAwait: QiContext NULL")
            return
        }
        Log.i(TAG, "cancelAndMoveAwait: x=$x theta=$theta")

        currentGoToFuture?.requestCancellation()
        currentGoToFuture = null

        val transform  = buildTransform(x, theta)
        val robotFrame = ctx.actuation.robotFrame()
        val freeFrame  = ctx.mapping.makeFreeFrame()
        freeFrame.update(robotFrame, transform, 0L)

        suspendCancellableCoroutine { cont ->
            val future = GoToBuilder.with(ctx)
                .withFrame(freeFrame.frame())
                .build()
                .async().run()
            currentGoToFuture = future

            future.thenConsume { f ->
                currentGoToFuture = null
                when {
                    f.isSuccess -> {
                        Log.i(TAG, "cancelAndMoveAwait ✓ x=$x theta=$theta")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    f.isCancelled -> {
                        Log.i(TAG, "cancelAndMoveAwait cancelled")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    f.hasError() -> {
                        Log.e(TAG, "cancelAndMoveAwait ERROR: ${f.errorMessage}")
                        Thread.sleep(300L)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            cont.invokeOnCancellation { future.requestCancellation() }
        }
    }
    fun stopMovement() {
        currentGoToFuture?.requestCancellation()
        currentGoToFuture = null
    }

    // TODO: Questo blocco per implementazione spostamento tra stanze
    // Da implementare con *NavigationController*
    // Richiede: Mapping SDK, LocalizeAndMap, waypoint salvati a runtime.
    //
    // suspend fun goToWaypoint(name: String) {
    //     val frame = waypointMap[name] ?: run {
    //         Log.e(TAG, "Waypoint '$name' non trovato"); return
    //     }
    //     GoToBuilder.with(ctx).withFrame(frame).build().async().run() ...
    // }

    private fun buildTransform(x: Double, theta: Double): Transform {
        return when {
            x == 0.0     -> TransformBuilder.create().from2DTransform(0.0, 0.0, theta)
            theta == 0.0 -> TransformBuilder.create().fromXTranslation(x)
            else         -> TransformBuilder.create().from2DTransform(x, 0.0, theta)
        }
    }
}