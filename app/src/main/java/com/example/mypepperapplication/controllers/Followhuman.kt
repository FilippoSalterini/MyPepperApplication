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
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.sqrt

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
        private const val CLOSE_ENOUGH_DISTANCE = 0.8   // sotto questa → HOLDING
        private const val TOO_FAR_DISTANCE      = 1.4   // sopra questa → torna PURSUING
        private const val DISTANCE_INTERVAL_MS  = 800L  // più reattivo del 1000ms originale
        private const val STUCK_THRESHOLD       = 5
    }


    private lateinit var chargingFlap: FlapSensor
    private lateinit var robotFrame: Frame

    private val shouldFollowHuman = AtomicBoolean(false)
    private val isFollowingHuman  = AtomicBoolean(false)
    // FIX: flag HOLDING — blocca riavvii quando siamo vicini
    private val isHolding         = AtomicBoolean(false)

    private var goToFuture:  Future<Void>? = null
    private var lookAtFuture: Future<Void>? = null

    private var goToAttemptCounter = 0
    private var consecutiveErrors = 0
    private var seemsStuck = false
    private var timer = Timer()

    init {
        qiContext.power.async().chargingFlap.andThenConsume { chargingFlap = it }
        qiContext.actuation.async().robotFrame().andThenConsume { robotFrame = it }
    }

    fun start() {
        if (!shouldFollowHuman.compareAndSet(false, true)) {
            Log.w(TAG, "Already following — ignoring start()")
            return
        }
        seemsStuck = false
        isFollowingHuman.set(false)
        isHolding.set(false)
        goToAttemptCounter = 0

        timer.scheduleAtFixedRate(0L, DISTANCE_INTERVAL_MS) {
            if (!shouldFollowHuman.get()) return@scheduleAtFixedRate

            if (::chargingFlap.isInitialized && chargingFlap.state.open) {
                followHumanListener?.onChargingFlapOpen()
                stop()
                return@scheduleAtFixedRate
            }

            val dist = computeDistance() ?: return@scheduleAtFixedRate
            followHumanListener?.onDistanceToHumanChanged(dist)

            when {
                // Entra in HOLDING solo se non ci siamo già
                dist < CLOSE_ENOUGH_DISTANCE && !isHolding.get() -> {
                    Log.i(TAG, "Entering HOLDING (%.2fm)".format(dist))
                    isHolding.set(true)
                    // Cancella il GoTo corrente — il thenConsume lo vedrà come isCancelled
                    // e non ripartirà perché isHolding=true
                    goToFuture?.requestCancellation()
                    followHumanListener?.onCloseEnough()
                }
                // Esci da HOLDING quando l'umano si è abbastanza allontanato
                dist > TOO_FAR_DISTANCE && isHolding.get() -> {
                    Log.i(TAG, "Leaving HOLDING — human moved away (%.2fm)".format(dist))
                    isHolding.set(false)
                    startFollowingHuman(useStraightLines = true)
                }
            }
        }

        startFollowingHuman(useStraightLines = true)
        Log.i(TAG, "FollowHuman started")
    }

    fun stop() {
        shouldFollowHuman.set(false)
        isFollowingHuman.set(false)
        isHolding.set(false)
        timer.cancel()
        timer = Timer()
        goToFuture?.cancel(true)
        goToFuture = null
        lookAtFuture?.cancel(true)
        lookAtFuture = null
        Log.i(TAG, "FollowHuman stopped")
    }

    private fun startFollowingHuman(useStraightLines: Boolean) {
        if (!shouldFollowHuman.get()) return
        // FIX: non ripartire se siamo in HOLDING
        if (isHolding.get()) return
        // Non sovrapporre un GoTo già attivo
        if (goToFuture?.isDone == false) return

        val policy = if (useStraightLines)
            PathPlanningPolicy.STRAIGHT_LINES_ONLY
        else
            PathPlanningPolicy.GET_AROUND_OBSTACLES

        Log.i(TAG, "GoTo → straight=$useStraightLines stuck=$seemsStuck")

        goToFuture = humanToFollow.async().headFrame
            .andThenCompose { liveHeadFrame ->

                // Avvia LookAt con lo stesso frame live
                if (lookAtFuture == null || lookAtFuture!!.isDone) {
                    lookAtFuture = LookAtBuilder.with(qiContext)
                        .withFrame(liveHeadFrame)
                        .buildAsync()
                        .andThenCompose { lookAt ->
                            lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
                            lookAt.async().run()
                        }
                        .thenConsume { f ->
                            if (f.hasError()) Log.w(TAG, "LookAt error: ${f.errorMessage}")
                            lookAtFuture = null
                        }
                }

                // GoTo con lo stesso liveHeadFrame — non usare humanToFollow.headFrame qui
                GoToBuilder.with(qiContext)
                    .withFrame(liveHeadFrame)
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
                    .thenConsume { f ->
                        when {
                            f.isSuccess -> {
                                Log.i(TAG, "GoTo success")
                                seemsStuck = false
                                if (!isHolding.get() && shouldFollowHuman.get()) {
                                    consecutiveErrors = 0
                                    timer.schedule(300L) {
                                        maybeFollowHuman(true)
                                    }
                                }
                            }

                            f.isCancelled -> {
                                Log.i(TAG, "GoTo cancelled")
                                seemsStuck = false
                                // Se cancellato dal timer per HOLDING — non ripartire
                                // Se cancellato per altro — ripartiamo
                                if (!isHolding.get() && shouldFollowHuman.get()) {
                                    timer.schedule(300L) {
                                        maybeFollowHuman(true)
                                    }
                                }
                            }

                            f.hasError() -> {
                                Log.e(TAG, "GoTo error: ${f.errorMessage}")

                                if (::chargingFlap.isInitialized && chargingFlap.state.open) {
                                    followHumanListener?.onChargingFlapOpen()
                                    stop()
                                    return@thenConsume
                                }

                                val attemptSnapshot = goToAttemptCounter
                                timer.schedule(5000L) {
                                    val dist = computeDistance()
                                    val newAttempts = goToAttemptCounter - attemptSnapshot
                                    if (newAttempts >= STUCK_THRESHOLD &&
                                        dist != null && dist > CLOSE_ENOUGH_DISTANCE
                                    ) {
                                        if (seemsStuck && !useStraightLines) {
                                            followHumanListener?.onCantReachHuman()
                                        }
                                        seemsStuck = true
                                    }
                                }
                                goToAttemptCounter++

                                if (!isHolding.get() && shouldFollowHuman.get()) {
                                    consecutiveErrors++
                                    val delay = minOf(300L * consecutiveErrors, 3000L)
                                    timer.schedule(delay) {
                                        maybeFollowHuman(if (seemsStuck) !useStraightLines else true)
                                    }
                                }
                            }
                        }
                    }
            }
    }

    private fun maybeFollowHuman(useStraightLines: Boolean) {
        if (!shouldFollowHuman.get()) return
        if (isHolding.get()) return
        val dist = computeDistance()
        if (dist != null && dist < CLOSE_ENOUGH_DISTANCE) {
            // Entra in HOLDING — il timer farà uscire quando serve
            if (!isHolding.getAndSet(true)) {
                lookAtFuture?.requestCancellation()
                lookAtFuture = null
                followHumanListener?.onCloseEnough()
            }
        } else {
            startFollowingHuman(useStraightLines)
        }
    }

    private fun computeDistance(): Double? {
        if (!::robotFrame.isInitialized) return null
        return try {
            val t = humanToFollow.headFrame
                .computeTransform(robotFrame).transform.translation
            sqrt(t.x * t.x + t.y * t.y)
        } catch (e: Exception) {
            Log.w(TAG, "computeDistance error: ${e.message}")
            null
        }
    }
}