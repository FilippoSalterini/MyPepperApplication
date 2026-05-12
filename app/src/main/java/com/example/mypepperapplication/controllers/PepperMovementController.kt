package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import com.aldebaran.qi.sdk.`object`.actuation.PathPlanningPolicy
import com.aldebaran.qi.sdk.`object`.autonomousabilities.DegreeOfFreedom
import com.aldebaran.qi.sdk.`object`.holder.Holder
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PepperMovement"

class PepperMovementController {

    private var qiContext: QiContext? = null
    private var goToFuture: Future<Void>? = null
    private var holder: Holder? = null
    private val step = 1.0

    /**
     * Semaforo: true mentre un GoTo è in esecuzione.
     * VisualServoingController lo usa per non accumulare comandi.
     */
    val isMoving = AtomicBoolean(false)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onRobotReady(qiContext: QiContext) {
        this.qiContext = qiContext
        holdBaseRotation()
    }

    fun onRobotLost() {
        stopMovement()
        releaseBaseRotation()
        qiContext = null
    }

    // ── Bottoni manuali ───────────────────────────────────────────────────────

    fun moveForward()  = moveRobot(x =  step, y = 0.0, theta = 0.0)
    fun moveBackward() = moveRobot(x = -step, y = 0.0, theta = 0.0)
    fun rotateLeft()   = moveRobot(x = 0.0,  y = 0.0, theta = -step)
    fun rotateRight()  = moveRobot(x = 0.0,  y = 0.0, theta =  step)

    fun stopMovement() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                goToFuture?.requestCancellation()
                goToFuture = null
                isMoving.set(false)
                Log.i(TAG, "Movement stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stop: ${e.message}")
            }
        }
    }

    // ── API visual servoing ───────────────────────────────────────────────────

    /**
     * Ruota di [angleRad] radianti.
     * Positivo = antiorario (sinistra), Negativo = orario (destra).
     * [onComplete] viene chiamato su thread IO quando il movimento finisce.
     */
    fun rotateByAngle(angleRad: Double, onComplete: (() -> Unit)? = null) =
        moveRobot(x = 0.0, y = 0.0, theta = angleRad, onComplete = onComplete)

    /**
     * Avanza di [distanceMeters] metri (negativo = indietro).
     * [onComplete] viene chiamato su thread IO quando il movimento finisce.
     */
    fun moveByDistance(distanceMeters: Double, onComplete: (() -> Unit)? = null) =
        moveRobot(x = distanceMeters, y = 0.0, theta = 0.0, onComplete = onComplete)

    // ── Movimento interno ─────────────────────────────────────────────────────

    /**
     * FIX principale: non cancella il movimento precedente se ancora in corso.
     * Il chiamante (VisualServoingController) deve controllare isMoving PRIMA
     * di chiamare questo metodo.
     *
     * [onComplete] viene invocato al termine del GoTo (successo o errore).
     */
    private fun moveRobot(
        x: Double,
        y: Double,
        theta: Double,
        onComplete: (() -> Unit)? = null
    ) {
        val ctx = qiContext ?: run {
            Log.e(TAG, "qiContext null — impossibile muoversi")
            onComplete?.invoke()
            return
        }

        // Cancella il movimento corrente solo se richiamato dai bottoni manuali
        // (onComplete == null). Il visual servoing gestisce il semaforo da solo.
        if (onComplete == null) {
            goToFuture?.requestCancellation()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Starting movement x=$x y=$y theta=$theta")

                val actuation  = ctx.actuation
                val mapping    = ctx.mapping
                val robotFrame = actuation.robotFrame()

                val transform = TransformBuilder.create()
                    .from2DTransform(x, y, theta)

                val targetFrame = mapping.makeFreeFrame()
                targetFrame.update(robotFrame, transform, 0L)

                val goTo = when {
                    x > 0 -> GoToBuilder.with(ctx)
                        .withFrame(targetFrame.frame())
                        .withMaxSpeed(0.3f)
                        .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                        .withPathPlanningPolicy(PathPlanningPolicy.STRAIGHT_LINES_ONLY)
                        .build()
                    x < 0 -> GoToBuilder.with(ctx)
                        .withFrame(targetFrame.frame())
                        .withMaxSpeed(0.3f)
                        .build()
                    else -> GoToBuilder.with(ctx)
                        .withFrame(targetFrame.frame())
                        .withMaxSpeed(0.3f)
                        .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                        .build()
                }

                Log.i(TAG, "GoTo built, starting...")
                goToFuture = goTo.async().run()

                goToFuture?.thenConsume { future ->
                    when {
                        future.isSuccess  -> Log.i(TAG, "Movement completed.")
                        future.hasError() -> Log.e(TAG, "Error GoTo: ${future.error}")
                        else              -> Log.d(TAG, "Movement deleted.")
                    }
                    // Notifica il chiamante (visual servoing)
                    isMoving.set(false)
                    onComplete?.invoke()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error movement: ${e.message}", e)
                isMoving.set(false)
                onComplete?.invoke()
            }
        }
    }

    // ── Holder rotazione autonoma ─────────────────────────────────────────────

    private fun holdBaseRotation() {
        val ctx = qiContext ?: return
        try {
            if (holder == null) {
                holder = HolderBuilder.with(ctx)
                    .withDegreesOfFreedom(DegreeOfFreedom.ROBOT_FRAME_ROTATION)
                    .build()
            }
            holder?.async()?.hold()
            Log.d(TAG, "Autonomous rotation blocked.")
        } catch (e: Exception) {
            Log.e(TAG, "Error hold: ${e.message}")
        }
    }

    private fun releaseBaseRotation() {
        try {
            holder?.async()?.release()
            holder = null
            Log.d(TAG, "Autonomous rotation released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error release: ${e.message}")
        }
    }
}