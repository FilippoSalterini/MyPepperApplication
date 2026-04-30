package com.example.mypepperapplication.controllers

import android.util.Log
// QiSDK core
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
// QiSDK - actuation

import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import com.aldebaran.qi.sdk.`object`.actuation.PathPlanningPolicy
// QiSDK - autonomous abilities
import com.aldebaran.qi.sdk.`object`.autonomousabilities.DegreeOfFreedom
// QiSDK - holder
import com.aldebaran.qi.sdk.`object`.holder.Holder
// QiSDK - builders
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
// Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "PepperMovement"

class PepperMovementController {

    private var qiContext: QiContext? = null //master key for everything related to the robot
    private var goToFuture: Future<Void>? = null //it represents the movement in progress
    private var holder: Holder? = null //lock autonomous rotation of Pepper
    private val step = 1.0
    // ── Lifecycle ────────────────────────────────────────
    fun onRobotReady(qiContext: QiContext) {
        this.qiContext = qiContext
        holdBaseRotation()
    }
    fun onRobotLost() {
        stopMovement()
        releaseBaseRotation()
        qiContext = null
    }
    // ── Buttons ──────────────────────────────────────────
    fun moveForward()  = moveRobot(x =  step, y = 0.0, theta = 0.0)
    fun moveBackward() = moveRobot(x = -step, y = 0.0, theta = 0.0)
    fun rotateLeft()   = moveRobot(x = 0.0,  y = 0.0, theta =  -step)
    fun rotateRight()  = moveRobot(x = 0.0,  y = 0.0, theta = step)
    fun stopMovement() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                goToFuture?.requestCancellation()
                goToFuture = null
                Log.i(TAG, "Movement stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stop: ${e.message}")
            }
        }
    }
    // ── Internal Movement ────────────────────────────────
    private fun moveRobot(x: Double, y: Double, theta: Double) {
        val ctx = qiContext ?: run {
            Log.e(TAG, "qiContext is NULL, impossibility to move")
            return
        }
        goToFuture?.requestCancellation()
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
                    x > 0 -> {  // forward
                        GoToBuilder.with(ctx)
                            .withFrame(targetFrame.frame())
                            .withMaxSpeed(0.3f)
                            .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                            .withPathPlanningPolicy(PathPlanningPolicy.STRAIGHT_LINES_ONLY)
                            .build()
                    }
                    x < 0 -> {
                        GoToBuilder.with(ctx)
                            .withFrame(targetFrame.frame())
                            .withMaxSpeed(0.3f)
                            .build()
                    }
                    else -> {  // rotation pure: need ALIGN_X to be physically rotated
                        GoToBuilder.with(ctx)
                            .withFrame(targetFrame.frame())
                            .withMaxSpeed(0.3f)
                            .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                            .build()
                    }
                }

                Log.i(TAG, "GoTo built, starting...")
                goToFuture = goTo.async().run()

                goToFuture?.thenConsume { future ->
                    when {
                        future.isSuccess  -> Log.i(TAG, "Movement completed.")
                        future.hasError() -> Log.e(TAG, "Error GoTo: ${future.error}")
                        else              -> Log.d(TAG, "Movement deleted.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error movement: ${e.message}", e)
            }
        }
    }
    // ── Holder autonomous rotation ────────────────────────
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