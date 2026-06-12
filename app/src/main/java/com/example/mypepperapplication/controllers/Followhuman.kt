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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.hypot

// =============================================================================
// FollowHuman
// =============================================================================
/**
 * FollowHuman implementato partendo da https://github.com/softbankrobotics-labs/pepper-follow-me.git
 * modificando e adattando il codice al progetto.
 */

class FollowHuman(
    private val qiContext: QiContext,
    private val humanToFollow: Human,
    private val followHumanListener: FollowHumanListener? = null,
    private val closeEnoughDistance: Double = 1.0,
    private val tooFarDistance: Double = 1.7 ) {

    interface FollowHumanListener {
        fun onFollowingHuman()
        fun onCloseEnough()
        fun onCantReachHuman()
        fun onChargingFlapOpen()
        fun onDistanceToHumanChanged(distance: Double)
    }
    // CHECK PARAMETRI
    companion object {
        private const val TAG                  = "FollowHuman"
        private const val CLOSE_ENOUGH_DISTANCE = 1.0
        private const val TOO_FAR_DISTANCE      = 1.7
        private const val DISTANCE_INTERVAL_MS  = 500L
        private const val STUCK_AFTER_ERRORS    = 4
        private const val CANT_REACH_AFTER      = 10
    }

    private lateinit var chargingFlap: FlapSensor
    private lateinit var robotFrame:   Frame

    private val shouldFollowHuman  = AtomicBoolean(false)
    private val isFollowingHuman   = AtomicBoolean(false)
    private val isHolding          = AtomicBoolean(false)
    private val isBodyLookAtActive = AtomicBoolean(false)
    private val isGoToRunning = AtomicBoolean(false)

    @Volatile private var goToFuture:   Future<Void>? = null
    @Volatile private var lookAtFuture: Future<Void>? = null

    private val goToAttemptCounter = AtomicInteger(0)
    private val consecutiveErrors  = AtomicInteger(0)
    @Volatile private var seemsStuck = false

    private var timer = Timer()

    init {
        qiContext.power.async().chargingFlap.andThenConsume { chargingFlap = it }
        qiContext.actuation.async().robotFrame().andThenConsume { robotFrame = it }
    }

    // -------------------------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------------------------

    fun start() {
        if (!shouldFollowHuman.compareAndSet(false, true)) {
            Log.w(TAG, "Already following — ignoring start()")
            return
        }
        resetState()
        timer = Timer()

        timer.scheduleAtFixedRate(0L, DISTANCE_INTERVAL_MS) {
            if (!shouldFollowHuman.get()) return@scheduleAtFixedRate

            if (::chargingFlap.isInitialized && chargingFlap.state.open) {
                followHumanListener?.onChargingFlapOpen()
                stop(); return@scheduleAtFixedRate
            }

            // Interrogazione asincrona isolata nel tick del timer
            humanToFollow.async().headFrame.andThenConsume { liveFrame ->
                if (!shouldFollowHuman.get()) return@andThenConsume
                if (liveFrame == null || !::robotFrame.isInitialized) return@andThenConsume

                try {
                    val t = liveFrame.computeTransform(robotFrame).transform.translation
                    val dist = hypot(t.x, t.y)

                    followHumanListener?.onDistanceToHumanChanged(dist)

                    when {
                        // Ingresso in HOLDING
                        dist < CLOSE_ENOUGH_DISTANCE && !isHolding.get() -> {
                            tryEnterHolding()
                        }

                        // Uscita da HOLDING
                        dist > TOO_FAR_DISTANCE && isHolding.get() -> {
                            Log.i(TAG, "Leaving HOLDING — human moved away (%.2fm)".format(dist))
                            isHolding.set(false)
                            isBodyLookAtActive.set(false)
                            stopLookAt()

                            try {
                                timer.schedule(400L) {
                                    if (shouldFollowHuman.get() && !isHolding.get()) {
                                        startFollowingHuman(useStraightLines = true)
                                    }
                                }
                            } catch (_: IllegalStateException) {
                                Log.w(TAG, "Timer cancelled during HOLDING exit — skip")
                            }
                        }
                        isHolding.get() && !isBodyLookAtActive.get() -> {
                            Log.d(TAG, "HOLDING Watchdog: LookAt HEAD_AND_BASE was idle, restarting")
                            enterHolding()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error computing distance in timer tick: ${e.message}")
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
        isBodyLookAtActive.set(false)

        timer.cancel()

        goToFuture?.cancel(true);   goToFuture  = null
        lookAtFuture?.cancel(true); lookAtFuture = null
        Log.i(TAG, "FollowHuman stopped")
    }

    // -------------------------------------------------------------------------
    // HOLDING
    // -------------------------------------------------------------------------

    private fun tryEnterHolding() {
        if (!isHolding.getAndSet(true)) {
            Log.i(TAG, "Entering HOLDING")
            goToFuture?.requestCancellation()
            followHumanListener?.onCloseEnough()
            enterHolding()
        }
    }

    private fun enterHolding() {
        if (!shouldFollowHuman.get() || !isHolding.get()) return
        if (isBodyLookAtActive.get()) return

        stopLookAt()
        isBodyLookAtActive.set(true)
        val currentGoTo = goToFuture
        val baseReleaseFuture = if (currentGoTo != null && !currentGoTo.isDone) {
            currentGoTo.thenCompose { Future.of(null) }
        } else {
            Future.of(null)
        }

        val future = baseReleaseFuture.andThenCompose {
            if (!shouldFollowHuman.get() || !isHolding.get()) return@andThenCompose Future.of(null)

            humanToFollow.async().headFrame.andThenCompose { liveHeadFrame ->
                LookAtBuilder.with(qiContext)
                    .withFrame(liveHeadFrame)
                    .buildAsync()
                    .andThenCompose { lookAt ->
                        lookAt.policy = LookAtMovementPolicy.HEAD_AND_BASE
                        Log.i(TAG, "LookAt HEAD_AND_BASE avviato (holding)")
                        lookAt.async().run()
                    }
            }
        }

        @Suppress("UNCHECKED_CAST")
        lookAtFuture = future as Future<Void>

        future.thenConsume { f ->
            isBodyLookAtActive.set(false)
            lookAtFuture = null
            when {
                f.hasError()  -> Log.w(TAG, "LookAt HEAD_AND_BASE error: ${f.errorMessage}")
                f.isCancelled -> Log.i(TAG, "LookAt HEAD_AND_BASE cancelled")
                f.isSuccess   -> Log.i(TAG, "LookAt HEAD_AND_BASE success")
            }
        }
    }

    // -------------------------------------------------------------------------
    // MOVIMENTO
    // -------------------------------------------------------------------------

    private fun startFollowingHuman(useStraightLines: Boolean) {
        if (!shouldFollowHuman.get()) return
        if (isHolding.get()) return
        if (goToFuture?.isDone == false) return

        if (!isGoToRunning.compareAndSet(false, true)) {
            Log.d(TAG, "GoTo already running or starting up — skip")
            return
        }
        val policy = if (useStraightLines)
            PathPlanningPolicy.STRAIGHT_LINES_ONLY
        else
            PathPlanningPolicy.GET_AROUND_OBSTACLES

        Log.i(TAG, "GoTo → straight=$useStraightLines stuck=$seemsStuck")

        goToFuture = humanToFollow.async().headFrame
            .andThenCompose { liveHeadFrame ->

                if (lookAtFuture == null || lookAtFuture!!.isDone) {
                    isBodyLookAtActive.set(false)
                    lookAtFuture = LookAtBuilder.with(qiContext)
                        .withFrame(liveHeadFrame)
                        .buildAsync()
                        .andThenCompose { lookAt ->
                            lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
                            Log.i(TAG, "LookAt HEAD_ONLY avviato (movimento)")
                            lookAt.async().run()
                        }
                        .thenConsume { f ->
                            if (f.hasError()) Log.w(TAG, "LookAt error: ${f.errorMessage}")
                            if (!isBodyLookAtActive.get()) lookAtFuture = null
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
                        isFollowingHuman.set(false)
                        isGoToRunning.set(false) //libero flag appena task muore
                        goToFuture = null

                        when {
                            f.isSuccess -> {
                                Log.i(TAG, "GoTo success")
                                consecutiveErrors.set(0)
                                goToAttemptCounter.set(0)
                                seemsStuck = false
                            }

                            f.isCancelled -> {
                                Log.i(TAG, "GoTo cancelled")
                                consecutiveErrors.set(0)
                                seemsStuck = false
                                if (isHolding.get() && shouldFollowHuman.get()) {
                                    enterHolding()
                                }
                            }

                            f.hasError() -> {
                                Log.e(TAG, "GoTo error: ${f.errorMessage}")

                                if (::chargingFlap.isInitialized && chargingFlap.state.open) {
                                    followHumanListener?.onChargingFlapOpen()
                                    stop(); return@thenConsume
                                }

                                val errors   = consecutiveErrors.incrementAndGet()
                                val attempts = goToAttemptCounter.incrementAndGet()

                                if (errors >= STUCK_AFTER_ERRORS) seemsStuck = true

                                if (attempts >= CANT_REACH_AFTER) {
                                    Log.e(TAG, "Can't reach human after $attempts attempts")
                                    followHumanListener?.onCantReachHuman()
                                    stop(); return@thenConsume
                                }

                                if (!isHolding.get() && shouldFollowHuman.get()) {
                                    val delay = minOf(500L * (1L shl (errors - 1)), 4000L) // 500, 1000, 2000, 4000
                                    Log.i(TAG, "Retry in ${delay}ms, obstacleAvoidance=$seemsStuck")

                                    try {
                                        timer.schedule(delay) {
                                            maybeFollowHuman(!seemsStuck)
                                        }
                                    } catch (_: IllegalStateException) {
                                        Log.w(TAG, "Timer already cancelled — skip retry")
                                    }
                                }
                            }
                        }
                    }
            }
    }

    // -------------------------------------------------------------------------
    // UTILITIES
    // -------------------------------------------------------------------------

    private fun stopLookAt() {
        lookAtFuture?.requestCancellation()
        lookAtFuture = null
    }

    private fun maybeFollowHuman(useStraightLines: Boolean) {
        if (!shouldFollowHuman.get()) return
        if (isHolding.get()) return

        // Ricaviamo il frame al volo per evitare disallineamenti di stato
        humanToFollow.async().headFrame.andThenConsume { liveFrame ->
            if (!shouldFollowHuman.get()) return@andThenConsume
            if (liveFrame == null || !::robotFrame.isInitialized) return@andThenConsume

            try {
                val t = liveFrame.computeTransform(robotFrame).transform.translation
                val dist = hypot(t.x, t.y)
                if (dist < CLOSE_ENOUGH_DISTANCE) {
                    tryEnterHolding()
                } else {
                    startFollowingHuman(useStraightLines)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in maybeFollowHuman distance check: ${e.message}")
            }
        }
    }

    private fun resetState() {
        seemsStuck = false
        isFollowingHuman.set(false)
        isHolding.set(false)
        isBodyLookAtActive.set(false)
        goToAttemptCounter.set(0)
        consecutiveErrors.set(0)
    }
}