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
        private const val CLOSE_ENOUGH_DISTANCE = 0.8
        private const val TOO_FAR_DISTANCE      = 1.4
        private const val DISTANCE_INTERVAL_MS  = 800L
        private const val STUCK_AFTER_ERRORS = 4
        private const val CANT_REACH_AFTER   = 10
    }

    private lateinit var chargingFlap: FlapSensor
    private lateinit var robotFrame: Frame
    @Volatile private var cachedHeadFrame: Frame? = null // Gestito in modo volatile e nullo all'inizio

    private val shouldFollowHuman = AtomicBoolean(false)
    private val isFollowingHuman  = AtomicBoolean(false)
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
        // Rimosso l'aggancio rigido dall'init per evitare frame obsoleti o non pronti
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
                dist < CLOSE_ENOUGH_DISTANCE && !isHolding.get() -> {
                    Log.i(TAG, "Entering HOLDING (%.2fm)".format(dist))
                    isHolding.set(true)
                    goToFuture?.requestCancellation() // Genera f.isCancelled
                    followHumanListener?.onCloseEnough()
                }
                dist > TOO_FAR_DISTANCE && isHolding.get() -> {
                    Log.i(TAG, "Leaving HOLDING — human moved away (%.2fm)".format(dist))
                    isHolding.set(false)
                    // Mantenuto il tuo fix del delay di 400ms prima di ripartire
                    timer.schedule(400L) {
                        if (shouldFollowHuman.get() && !isHolding.get()) {
                            startFollowingHuman(useStraightLines = true)
                        }
                    }
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
        cachedHeadFrame = null
        Log.i(TAG, "FollowHuman stopped")
    }

    private fun startFollowingHuman(useStraightLines: Boolean) {
        if (!shouldFollowHuman.get()) return
        if (isHolding.get()) return
        if (goToFuture?.isDone == false) return

        val policy = if (useStraightLines)
            PathPlanningPolicy.STRAIGHT_LINES_ONLY
        else
            PathPlanningPolicy.GET_AROUND_OBSTACLES

        Log.i(TAG, "GoTo → straight=$useStraightLines stuck=$seemsStuck")

        goToFuture = humanToFollow.async().headFrame
            .andThenCompose { liveHeadFrame ->
                // Aggiorna la cache con un frame attivo e valido per il calcolo della distanza
                cachedHeadFrame = liveHeadFrame

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
                        // FIX COMPONENTI ATOMICI: resettiamo sempre lo stato di moto all'uscita del Future
                        isFollowingHuman.set(false)

                        when {
                            f.isSuccess -> {
                                Log.i(TAG, "GoTo success")
                                consecutiveErrors = 0
                                goToAttemptCounter = 0
                                seemsStuck = false
                                if (!isHolding.get() && shouldFollowHuman.get()) {
                                    timer.schedule(300L) {
                                        goToFuture = null // Libera il reference
                                        maybeFollowHuman(true)
                                    }
                                }
                            }

                            f.isCancelled -> {
                                Log.i(TAG, "GoTo cancelled")
                                consecutiveErrors = 0
                                seemsStuck = false
                                lookAtFuture = null
                                goToFuture = null // Cruciale per permettere il riavvio al ciclo successivo del timer
                            }

                            f.hasError() -> {
                                Log.e(TAG, "GoTo error: ${f.errorMessage}")

                                if (::chargingFlap.isInitialized && chargingFlap.state.open) {
                                    followHumanListener?.onChargingFlapOpen()
                                    stop()
                                    return@thenConsume
                                }

                                consecutiveErrors++
                                goToAttemptCounter++

                                if (consecutiveErrors >= STUCK_AFTER_ERRORS) {
                                    seemsStuck = true
                                }

                                if (goToAttemptCounter >= CANT_REACH_AFTER) {
                                    Log.e(TAG, "Can't reach human after $goToAttemptCounter attempts")
                                    followHumanListener?.onCantReachHuman()
                                    stop()
                                    return@thenConsume
                                }

                                if (!isHolding.get() && shouldFollowHuman.get()) {
                                    val delay = minOf(500L * consecutiveErrors, 3000L)
                                    val useObstacleAvoidance = seemsStuck
                                    Log.i(TAG, "Retry in ${delay}ms, obstacleAvoidance=$useObstacleAvoidance")
                                    timer.schedule(delay) {
                                        goToFuture = null // Libera il reference prima del retry
                                        maybeFollowHuman(!useObstacleAvoidance)
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
        val frame = cachedHeadFrame ?: return null
        return try {
            val t = frame.computeTransform(robotFrame).transform.translation
            sqrt(t.x * t.x + t.y * t.y)
        } catch (e: Exception) {
            Log.w(TAG, "computeDistance error: ${e.message}")
            null
        }
    }
}