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
        fun onFollowingHuman()
        fun onCloseEnough()
        fun onCantReachHuman()
        fun onChargingFlapOpen()
        fun onDistanceToHumanChanged(distance: Double)
    }

    companion object {
        private const val TAG = "FollowHuman"
    }

    var closeEnoughDistance: Double = 0.8
    var distanceCheckIntervalMs: Long = 400L
    var stuckThreshold: Int = 4

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

        // 1. Avviamo lo sguardo IMMEDIATAMENTE e una volta sola.
        // Resterà attivo in background inseguendo l'umano in modo fluido.
        startLookAt()

        // 2. Avviamo il monitoraggio e il movimento
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

        // Fermiamo esplicitamente il LookAt quando interrompiamo il comportamento
        lookAtFuture?.requestCancellation()
        lookAtFuture = null
        Log.i(TAG, "FollowHuman stopped")
    }

    /**
     * Gestisce lo sguardo in modo indipendente dal movimento della base.
     */
    private fun startLookAt() {
        // Se c'è già un'azione di LookAt attiva, non sovrascriviamola
        if (lookAtFuture != null && !lookAtFuture!!.isDone) {
            return
        }

        lookAtFuture = humanToFollow.async().headFrame
            .andThenCompose { frame ->
                LookAtBuilder.with(qiContext)
                    .withFrame(frame)
                    .buildAsync()
                    .andThenCompose { lookAt ->
                        // CRITICO: Usiamo HEAD_ONLY. La testa segue l'umano in tempo reale,
                        // lasciando la base totalmente libera di eseguire i comandi GoTo.
                        lookAt.policy = LookAtMovementPolicy.HEAD_ONLY

                        Log.i(TAG, "LookAt (HEAD_ONLY) avviato in background")
                        lookAt.async().run()
                    }
            }
            .thenConsume { future ->
                if (future.hasError()) {
                    Log.w(TAG, "LookAt error: ${future.errorMessage}")
                    // Se fallisce (es. perdita temporanea del frame), riproponilo con un piccolo delay
                    if (shouldFollowHuman.get()) {
                        timer.schedule(500L) { startLookAt() }
                    }
                }
            }
    }

    private fun startDistanceTimer() {
        timer.scheduleAtFixedRate(0L, distanceCheckIntervalMs) {
            if (!shouldFollowHuman.get()) return@scheduleAtFixedRate

            if (::chargingFlap.isInitialized && chargingFlap.state.open) {
                Log.e(TAG, "Charging flap OPEN — stopping")
                followHumanListener?.onChargingFlapOpen()
                stop()
                return@scheduleAtFixedRate
            }

            val dist = computeDistance() ?: return@scheduleAtFixedRate
            followHumanListener?.onDistanceToHumanChanged(dist)

            if (dist < closeEnoughDistance) {
                val future = goToFuture
                if (future != null && !future.isDone) {
                    Log.i(TAG, "Close enough (%.2fm) — cancelling GoTo".format(dist))
                    future.requestCancellation()
                    followHumanListener?.onCloseEnough()
                }
                return@scheduleAtFixedRate
            }

            val future = goToFuture
            val noActiveGoTo = future == null || future.isDone

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
        if (goToFuture != null && !goToFuture!!.isDone) return

        // Rimosso il richiamo continuo a startLookAt() da qui.
        // Il LookAt ora vive di vita propria gestito da start().

        val policy = if (useStraightLines)
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
                        Log.e(TAG, "GoTo error #$consecutiveErrors: ${future.errorMessage}")

                        if (::chargingFlap.isInitialized && chargingFlap.state.open) {
                            followHumanListener?.onChargingFlapOpen()
                            stop()
                            return@thenConsume
                        }

                        if (consecutiveErrors >= stuckThreshold) {
                            seemsStuck = true
                            followHumanListener?.onCantReachHuman()
                        }

                        val delay = (consecutiveErrors * 500L).coerceAtMost(3000L)
                        timer.schedule(delay) {
                            if (shouldFollowHuman.get()) {
                                maybeFollowHuman(!useStraightLines)
                            }
                        }
                    }
                }
            }
    }

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