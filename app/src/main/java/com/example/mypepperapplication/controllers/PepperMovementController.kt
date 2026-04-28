package com.example.mypepperapplication.controllers

// Android
import android.util.Log
// QiSDK core
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
// QiSDK - actuation
import com.aldebaran.qi.sdk.`object`.actuation.Actuation
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.actuation.FreeFrame
import com.aldebaran.qi.sdk.`object`.actuation.Mapping
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import com.aldebaran.qi.sdk.`object`.actuation.PathPlanningPolicy
// QiSDK - autonomous abilities
import com.aldebaran.qi.sdk.`object`.autonomousabilities.DegreeOfFreedom
// QiSDK - geometry
import com.aldebaran.qi.sdk.`object`.geometry.Transform
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

    private var qiContext: QiContext? = null //passpartuout per tutto cio che riguarda il robot
    private var goToFuture: Future<Void>? = null //sta a rappresentare il movimento in corso.
    // è un FUTURE VOID un oggetto che rappresenta un'operazione asincrona che non ritorna nessun valore (Void).
    // Lo teniamo salvato perché ci serve per cancellarlo con stopMovement().
    // Senza questo riferimento non potresti mai fermare Pepper a metà movimento.
    private var holder: Holder? = null //blocca rotazione autonoma di pepper

    private val STEP = 0.9

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

    // ── Bottoni ──────────────────────────────────────────

    fun moveForward()  = moveRobot(x =  STEP, y = 0.0, theta = 0.0)
    fun moveBackward() = moveRobot(x = -STEP, y = 0.0, theta = 0.0)
    fun rotateLeft()   = moveRobot(x = 0.0,  y = 0.0, theta =  STEP) // positivo antioraria
    fun rotateRight()  = moveRobot(x = 0.0,  y = 0.0, theta = -STEP) // negativo oraria

    fun stopMovement() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                goToFuture?.requestCancellation()
                goToFuture = null
                Log.i(TAG, "Movimento fermato.")
            } catch (e: Exception) {
                Log.e(TAG, "Errore stop: ${e.message}")
            }
        }
    }

    // ── Movimento interno ────────────────────────────────
    private fun moveRobot(x: Double, y: Double, theta: Double) {
        val ctx = qiContext ?: return

        goToFuture?.requestCancellation()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val actuation  = ctx.actuation
                val mapping    = ctx.mapping
                val robotFrame = actuation.robotFrame()

                val transform = TransformBuilder.create()
                    .from2DTransform(x, y, theta)

                val targetFrame = mapping.makeFreeFrame()

                // ⚠ timestamp corretto (millisecondi, NON microsecondi)
                val timestamp = System.currentTimeMillis()
                targetFrame.update(robotFrame, transform, timestamp)

                val goTo = GoToBuilder.with(ctx)
                    .withFrame(targetFrame.frame())
                    .withMaxSpeed(0.3f)
                    .withFinalOrientationPolicy(OrientationPolicy.FREE_ORIENTATION)
                    .build()

                goToFuture = goTo.async().run()

                goToFuture?.thenConsume { future ->
                    when {
                        future.isSuccess  -> Log.i(TAG, "Movimento completato.")
                        future.hasError() -> Log.e(TAG, "Errore GoTo: ${future.error}")
                        else              -> Log.d(TAG, "Movimento cancellato.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Errore movimento: ${e.message}", e)
            }
        }
    }
    // ── Holder rotazione autonoma ────────────────────────

    private fun holdBaseRotation() {
        val ctx = qiContext ?: return
        try {
            if (holder == null) {
                holder = HolderBuilder.with(ctx)
                    .withDegreesOfFreedom(DegreeOfFreedom.ROBOT_FRAME_ROTATION)
                    .build()
            }
            holder?.async()?.hold()
            Log.d(TAG, "Rotazione autonoma bloccata.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore hold: ${e.message}")
        }
    }

    private fun releaseBaseRotation() {
        try {
            holder?.async()?.release()
            holder = null
            Log.d(TAG, "Rotazione autonoma rilasciata.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore release: ${e.message}")
        }
    }
}