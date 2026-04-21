package com.example.mypepperapplication

import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.GoTo
import com.aldebaran.qi.sdk.`object`.geometry.Transform

class PepperMovementController {

    private var qiContext: QiContext? = null
    private var currentMovement: Future<Void>? = null

    // qui setto la distanza per ogni movimento, possibile settarlo direttamente nei metodi
    // ma meglio passarlo da qua
    private val STEP = 0.5

    fun onRobotReady(context: QiContext) {
        qiContext = context
    }

    fun onRobotLost() {
        stopMovement()
        qiContext = null
    }

    // qui vi sono i metodi pubblici richiamati dai bottoni

    fun moveForward()  = move(x =  STEP, y = 0.0, theta = 0.0)
    fun moveBackward() = move(x = -STEP, y = 0.0, theta = 0.0)

    fun moveLeft()  = move(x = 0.3, y = 0.0, theta =  0.3)
    fun moveRight() = move(x = 0.3, y = 0.0, theta = -0.3)

    fun stopMovement() {
        currentMovement?.requestCancellation()
        currentMovement = null
    }
    // qui  implemento la logica interna

    private fun move(x: Double, y: Double, theta: Double) {
        val context = qiContext ?: return

        // Cancella movimento precedente se ancora in corso
        stopMovement()

        Thread {
            try {
                // Frame corrente del robot
                val robotFrame = context.actuation.robotFrame()

                // Transform = spostamento relativo alla posizione attuale
                val transform: Transform = TransformBuilder.create()
                    .from2DTransform(x, y, theta)

                // Frame target = dove vogliamo arrivare
                val targetFrame = context.mapping
                    .makeFreeFrame()
                    .apply {
                        update(robotFrame, transform, 0L)
                    }
                    .frame()

                // Costruisci e avvia GoTo
                val goTo: GoTo = GoToBuilder.with(context)
                    .withFrame(targetFrame)
                    .build()

                // Esegui in modo asincrono così possiamo cancellarlo
                currentMovement = goTo.async().run()
                currentMovement?.get() // aspetta completamento

            } catch (e: Exception) {
                // Movimento cancellato o errore — normale con Stop
            }
        }.start()
    }
}