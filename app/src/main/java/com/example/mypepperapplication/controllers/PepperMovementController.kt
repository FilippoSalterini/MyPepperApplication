package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import com.aldebaran.qi.sdk.`object`.actuation.PathPlanningPolicy
import com.aldebaran.qi.sdk.`object`.holder.Holder
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "PepperMovement"

class PepperMovementController {

    private var qiContext: QiContext? = null
    private var goToFuture: Future<Void>? = null
    private var holder: Holder? = null
    private val step = 1.0

    val isMoving = AtomicBoolean(false)
    private var lastCommandTime = 0L
    private val moveMutex = Mutex()
    private val COMMAND_INTERVAL = 250L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onRobotReady(qiContext: QiContext) {
        this.qiContext = qiContext
        // holdBaseRotation() commentato — bloccava la rotazione della base
    }

    fun onRobotLost() {
        stopMovement()
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

    // ── API visual servoing (con onComplete — versione "wait")
    // cancelAndMove — per visual servoing fire-and-forget
    fun cancelAndMove(x: Double, y: Double, theta: Double) {
        val now = System.currentTimeMillis()
        if (now - lastCommandTime < COMMAND_INTERVAL) {
            Log.d(TAG, "cancelAndMove: rate limited, skip")
            return
        }
        lastCommandTime = now

        val ctx = qiContext ?: run {
            Log.e(TAG, "cancelAndMove: qiContext null")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            moveMutex.withLock {
                try {
                    // Cancella il movimento precedente
                    goToFuture?.requestCancellation()
                    goToFuture = null

                    Log.d(TAG, "cancelAndMove: x=%.3f theta=%.3f".format(x, theta))

                    val actuation = ctx.actuation
                    val mapping = ctx.mapping
                    val robotFrame = actuation.robotFrame()

                    val transform = TransformBuilder.create()
                        .from2DTransform(x, y, theta)

                    val targetFrame = mapping.makeFreeFrame()
                    targetFrame.update(robotFrame, transform, 0L)

                    val goTo = when {
                        x > 0 -> GoToBuilder.with(ctx)
                            .withFrame(targetFrame.frame())
                            .withMaxSpeed(0.35f)
                            .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                            .withPathPlanningPolicy(PathPlanningPolicy.STRAIGHT_LINES_ONLY)
                            .build()

                        x < 0 -> GoToBuilder.with(ctx)
                            .withFrame(targetFrame.frame())
                            .withMaxSpeed(0.35f)
                            .build()

                        else -> GoToBuilder.with(ctx)
                            .withFrame(targetFrame.frame())
                            .withMaxSpeed(0.35f)
                            .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                            .build()
                    }

                    goToFuture = goTo.async().run()
                    isMoving.set(true)

                    // Registra callback solo per logging — non blocca nulla
                    goToFuture?.thenConsume { future ->
                        isMoving.set(false)
                        when {
                            future.isSuccess -> Log.d(TAG, "cancelAndMove completed.")
                            future.hasError() -> Log.d(
                                TAG,
                                "cancelAndMove interrupted: ${future.error}"
                            )

                            else -> Log.d(TAG, "cancelAndMove cancelled.")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "cancelAndMove error: ${e.message}")
                    isMoving.set(false)
                }
            }
        }
    }

    // ── Movimento interno

    private fun moveRobot(
        x: Double,
        y: Double,
        theta: Double,
        onComplete: (() -> Unit)? = null
    ) {
        val ctx = qiContext ?: run {
            Log.e(TAG, "qiContext null")
            onComplete?.invoke()
            return
        }

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

// ── Holder (commentato — bloccava rotazione base) ─────────────────────────
//
//    private fun holdBaseRotation() {
//        val ctx = qiContext ?: return
//        try {
//            if (holder == null) {
//                holder = HolderBuilder.with(ctx)
//                    .withDegreesOfFreedom(DegreeOfFreedom.ROBOT_FRAME_ROTATION)
//                    .build()
//            }
//            holder?.async()?.hold()
//            Log.d(TAG, "Autonomous rotation blocked.")
//        } catch (e: Exception) {
//            Log.e(TAG, "Error hold: ${e.message}")
//        }
//    }
//
//    private fun releaseBaseRotation() {
//        try {
//            holder?.async()?.release()
//            holder = null
//            Log.d(TAG, "Autonomous rotation released.")
//        } catch (e: Exception) {
//            Log.e(TAG, "Error release: ${e.message}")
//        }
//    }

}