package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PepperMovementController {

    private var qiContext: QiContext? = null

    // qui setto la distanza per ogni movimento, possibile settarlo direttamente nei metodi
    // ma meglio passarlo da qua
    private var moveJob: Job? = null  // tiene traccia del movimento in corso

    private val STEP = 0.5

    fun onRobotReady(context: QiContext) {
        qiContext = context
    }

    fun onRobotLost() {
        stopMovement()
        qiContext = null
    }

    // qui vi sono i metodi pubblici richiamati dai bottoni

    fun moveForward() = move(x = STEP, y = 0.0, theta = 0.0)
    fun moveBackward() = move(x = -STEP, y = 0.0, theta = 0.0)

    fun rotateLeft() = move(x = STEP, y = 0.0, theta = 0.3)
    fun rotateRight() = move(x = STEP, y = 0.0, theta = -0.3)

    fun stopMovement() {
        moveJob?.cancel()  // ora funziona davvero
        moveJob = null
    }
    // qui  implemento la logica interna

    private fun move(x: Double, y: Double, theta: Double) {
        val context = qiContext ?: run {
            Log.e("PepperMovement", "QiContext null — robot non connesso")
            return
        }
        // Cancella movimento precedente
        moveJob?.cancel()
        // Lancia coroutine su thread IO
        moveJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val actuation = context.actuation
                val mapping = context.mapping
                // Frame corrente del robot
                val robotFrame = actuation.robotFrame()

                // Transform = spostamento relativo alla posizione attuale
                val transform = TransformBuilder.create()
                    .from2DTransform(x, y, theta)

                // Frame target = dove vogliamo arrivare
                val targetFrame = mapping.makeFreeFrame()
                targetFrame.update(robotFrame, transform, 0L)

                // Costruisci e avvia GoTo
                val goTo = GoToBuilder.with(context)
                    .withFrame(targetFrame.frame())
                    .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                    .build()

                goTo.run() //risulta bloccante nel thread separato --> ok
            } catch (e: CancellationException) {
                Log.d("PepperMovement", "Movimento cancellato")
            } catch (e: Exception) {
                Log.e("PepperMovement", "Errore: ${e.message}")
            }
        }
    }
}