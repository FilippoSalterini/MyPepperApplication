package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
import com.aldebaran.qi.sdk.`object`.actuation.PathPlanningPolicy
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.`object`.power.FlapSensor
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.LookAtBuilder
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.sqrt
// ===========================================================================
// FOLLOW HUMAN
// ===========================================================================

/*Serve per gestire il comportamente 'follow human' + goTo ciclico con
rilevamento degli ostacoli
*/

class FollowHuman(
    private val qiContext: QiContext,
    private val humanToFollow: Human,
    private val followHumanListener: FollowHumanListener? = null
) {

    interface FollowHumanListener {
        /** Il robot ha iniziato a muoversi verso l'umano */
        fun onFollowingHuman()
        /** Il robot è abbastanza vicino — si ferma */
        fun onCloseEnough()
        /** Il robot non riesce a raggiungere l'umano dopo vari tentativi */
        fun onCantReachHuman()
        /** Flap di ricarica aperto → il robot non può muoversi */
        fun onChargingFlapOpen()
        /** Aggiornamento distanza in metri */
        fun onDistanceToHumanChanged(distance: Double)
    }

    companion object {
        private const val TAG = "FollowHuman"
    }

    /** Distanza minima (m) sotto la quale il robot si ferma */
    var closeEnoughDistance: Double = 1.0

    /** Intervallo (ms) del timer di monitoraggio distanza — default 400ms */
    var distanceCheckIntervalMs: Long = 400L

    /** Numero di errori GoTo consecutivi prima di dichiarare "stuck" */
    var stuckThreshold: Int = 4

    // ── Stato interno ─────────────────────────────────────────────────────────

    private lateinit var chargingFlap: FlapSensor
    private lateinit var robotFrame: Frame
    private lateinit var headFrame: Frame

    private val shouldFollowHuman = AtomicBoolean(false)
    private val isFollowingHuman  = AtomicBoolean(false)

    private var goToFuture: Future<Void>? = null
    private var lookAtFuture: Future<Void>? = null

    private var consecutiveErrors  = 0
    private var seemsStuck         = false

    private var timer = Timer()

    init {
        qiContext.power.async().chargingFlap.andThenConsume { chargingFlap = it }
        qiContext.actuation.async().robotFrame().andThenConsume { robotFrame = it }
        humanToFollow.async().headFrame.andThenConsume { headFrame = it }
    }

    fun start() {
        if (!shouldFollowHuman.compareAndSet(false, true)) {
            Log.w(TAG, "Already following a human — ignoring start()")
            return
        }
        resetInternalState()
        startLookAt()
        startDistanceTimer()
        startFollowingHuman(useStraightLines = true)
        Log.i(TAG, "FollowHuman started")
    }

    fun stop() {
        shouldFollowHuman.set(false)
        isFollowingHuman.set(false)
        timer.cancel()
        timer = Timer()
        goToFuture?.requestCancellation()
        goToFuture = null
        lookAtFuture?.requestCancellation()
        lookAtFuture = null
        Log.i(TAG, "FollowHuman stopped")
    }

    private fun startLookAt() {
        if (lookAtFuture != null && !lookAtFuture!!.isDone) {
            return
        }
        lookAtFuture = humanToFollow.async().headFrame
            .andThenCompose { frame ->
                LookAtBuilder.with(qiContext)
                    .withFrame(frame)
                    .buildAsync()
                    .andThenCompose { lookAt ->

                        lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
                        lookAt.async().run()
                    }
            }
            .thenConsume { future ->
                if (future.hasError()) {
                    Log.w(TAG, "LookAt error: ${future.errorMessage}")
                }
                lookAtFuture = null
                if (shouldFollowHuman.get()) {
                    timer.schedule(300L) {
                        startLookAt()
                    }
                }
            }
    }

    // Timer 400ms — controlla flap prima della distanza
    private fun startDistanceTimer() {

        timer.scheduleAtFixedRate(0L, distanceCheckIntervalMs) {
            if (!shouldFollowHuman.get()) {
                return@scheduleAtFixedRate
            }
            if (::chargingFlap.isInitialized && chargingFlap.state.open) {

                Log.e(TAG, "Charging flap OPEN — stopping")

                followHumanListener?.onChargingFlapOpen()

                stop()

                return@scheduleAtFixedRate
            }

            val dist = computeDistance() ?: return@scheduleAtFixedRate

            followHumanListener?.onDistanceToHumanChanged(dist)

            // ─────────────────────────────────────
            // CLOSE ENOUGH
            // ─────────────────────────────────────

            if (dist < closeEnoughDistance) {

                val future = goToFuture

                if (future != null && !future.isDone) {

                    Log.i(TAG, "Close enough (%.2fm) — cancelling GoTo".format(dist))

                    future.requestCancellation()

                    followHumanListener?.onCloseEnough()
                }

                return@scheduleAtFixedRate
            }

            // ─────────────────────────────────────
            // HUMAN FAR AGAIN
            // ─────────────────────────────────────

            val future = goToFuture

            val noActiveGoTo =
                future == null || future.isDone

            if (noActiveGoTo) {

                Log.i(TAG, "Human far again (%.2fm) — resume follow".format(dist))

                startFollowingHuman(true)
            }
        }
    }

    private fun maybeFollowHuman(useStraightLines: Boolean) {
        if (!shouldFollowHuman.get()) return

        val dist = computeDistance()
        if (dist != null && dist < closeEnoughDistance) {
            timer.schedule(600L) {
                seemsStuck = false
                consecutiveErrors = 0
                maybeFollowHuman(true)
            }
        } else {
            startFollowingHuman(useStraightLines)
        }
    }

    private fun startFollowingHuman(useStraightLines: Boolean) {

        if (!shouldFollowHuman.get()) return

        // Evita GoTo multipli contemporanei
        if (goToFuture != null && !goToFuture!!.isDone) {
            return
        }

        startLookAt()

        val policy =
            if (useStraightLines)
                PathPlanningPolicy.STRAIGHT_LINES_ONLY
            else
                PathPlanningPolicy.GET_AROUND_OBSTACLES

        Log.i(TAG, "GoTo → straight=$useStraightLines stuck=$seemsStuck")

        goToFuture = humanToFollow.async().headFrame
            .andThenCompose { frame ->

                GoToBuilder.with(qiContext)
                    .withFrame(frame)
                    .withPathPlanningPolicy(policy)
                    .buildAsync()
                    .andThenCompose { goTo ->

                        goTo.addOnStartedListener {

                            if (isFollowingHuman.compareAndSet(false, true)) {
                                followHumanListener?.onFollowingHuman()
                            }
                        }

                        goTo.async().run()
                    }
            }
            .thenConsume { future ->

                goToFuture = null

                when {

                    future.isSuccess -> {

                        Log.i(TAG, "GoTo success")

                        seemsStuck = false
                        consecutiveErrors = 0
                    }

                    future.isCancelled -> {

                        Log.i(TAG, "GoTo cancelled")

                        seemsStuck = false
                        consecutiveErrors = 0
                    }

                    future.hasError() -> {

                        consecutiveErrors++

                        Log.e(
                            TAG,
                            "GoTo error #$consecutiveErrors: ${future.errorMessage}"
                        )

                        if (::chargingFlap.isInitialized &&
                            chargingFlap.state.open
                        ) {

                            Log.e(TAG, "Charging flap is OPEN")

                            followHumanListener?.onChargingFlapOpen()

                            stop()

                            return@thenConsume
                        }

                        if (consecutiveErrors >= stuckThreshold) {

                            seemsStuck = true

                            followHumanListener?.onCantReachHuman()
                        }

                        val delay =
                            (consecutiveErrors * 500L)
                                .coerceAtMost(3000L)

                        timer.schedule(delay) {

                            if (shouldFollowHuman.get()) {
                                maybeFollowHuman(!useStraightLines)
                            }
                        }
                    }
                }
            }
    }

    // Usa headFrame.computeTransform(robotFrame) per ottenere la traslazione 3D robot→umano.
    // Calcola sqrt(x² + y²) — distanza sul piano orizzontale (ignora z = altezza).
    // Ritorna null se i frame non sono ancora stati inizializzati (guard con isInitialized).
    private fun computeDistance(): Double? {
        if (!::headFrame.isInitialized || !::robotFrame.isInitialized) return null
        return try {
            val t = headFrame.computeTransform(robotFrame).transform.translation
            sqrt(t.x * t.x + t.y * t.y)
        } catch (e: Exception) {
            Log.w(TAG, "computeDistance error: ${e.message}")
            null
        }
    }

    private fun resetInternalState() {
        seemsStuck        = false
        consecutiveErrors = 0
        isFollowingHuman.set(false)
    }
}