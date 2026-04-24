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

    private val STEP = 0.5

    // ── Lifecycle ────────────────────────────────────────

    fun onRobotReady(context: QiContext) {
        qiContext = context
        holdBaseRotation()  // blocca rotazione autonoma subito
    }

    fun onRobotLost() {
        stopMovement()
        releaseBaseRotation()
        qiContext = null
    }

    // ── Bottoni ──────────────────────────────────────────

    fun moveForward()  = moveRobot(x =  STEP, y = 0.0, theta = 0.0)
    fun moveBackward() = moveRobot(x = -STEP, y = 0.0, theta = 0.0)
    fun rotateLeft()   = moveRobot(x = 0.0,  y = 0.0, theta =  0.5) // theta positivo rotazione antioraria
    fun rotateRight()  = moveRobot(x = 0.0,  y = 0.0, theta = -0.5) // theta begativo rotazione oraria

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
        val ctx = qiContext ?: run {
            Log.e(TAG, "QiContext null — robot non connesso")
            return
        }

        // Cancella movimento precedente
        goToFuture?.requestCancellation()

    // Prima di tutto il guard clause — se qiContext è null usciamo subito.
    // Poi cancelliamo qualsiasi movimento precedente —
    // così se premo deu volte freccia in su in rapida successione,
    // il primo movimento viene annullato e parte il secondo.

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val actuation: Actuation = ctx.actuation
                val robotFrame: Frame    = actuation.robotFrame()
                val transform: Transform = TransformBuilder.create()
                    .from2DTransform(x, y, theta)
                val mapping: Mapping    = ctx.mapping

                val targetFrame: FreeFrame = mapping.makeFreeFrame()
                targetFrame.update(robotFrame, transform, 0L)

                val goTo = GoToBuilder.with(ctx)
                    .withFrame(targetFrame.frame())
                    .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                    .withMaxSpeed(0.2F)
                    .withPathPlanningPolicy(PathPlanningPolicy.STRAIGHT_LINES_ONLY)
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