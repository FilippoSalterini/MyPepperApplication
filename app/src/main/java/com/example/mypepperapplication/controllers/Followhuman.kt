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

class FollowHuman(
    private val qiContext: QiContext,
    private val humanToFollow: Human,
    private val followHumanListener: FollowHumanListener? = null
) {

    // ── Interfaccia callback ──────────────────────────────────────────────────

    interface FollowHumanListener {
        /** Il robot ha iniziato a muoversi verso l'umano */
        fun onFollowingHuman()
        /** Il robot è abbastanza vicino — si ferma */
        fun onCloseEnough()
        /** Il robot non riesce a raggiungere l'umano dopo vari tentativi */
        fun onCantReachHuman()
        /** Aggiornamento distanza in metri */
        fun onDistanceToHumanChanged(distance: Double)
    }

    companion object {
        private const val TAG = "FollowHuman"
    }

    // ── Configurazione pubblica ───────────────────────────────────────────────

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

    private var goToAttemptCounter = 0
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
        humanToFollow.async().headFrame.andThenCompose { frame ->
            LookAtBuilder.with(qiContext)
                .withFrame(frame)
                .buildAsync()
                .andThenCompose { lookAt ->
                    lookAt.policy = LookAtMovementPolicy.HEAD_ONLY
                    lookAt.async().run()
                }
        }.thenConsume { f ->
            if (f.hasError()) {
                Log.w(TAG, "LookAt error: ${f.errorMessage}")
            }
            lookAtFuture = null
        }.also { lookAtFuture = it as? Future<Void> }
    }

    // Timer 400ms
    private fun startDistanceTimer() {
        timer.scheduleAtFixedRate(0L, distanceCheckIntervalMs) {
            val dist = computeDistance() ?: return@scheduleAtFixedRate
            followHumanListener?.onDistanceToHumanChanged(dist)

            if (dist < closeEnoughDistance) {
                Log.i(TAG, "Close enough (%.2fm) — cancelling GoTo".format(dist))
                goToFuture?.requestCancellation()
                followHumanListener?.onCloseEnough()
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
        val policy = if (useStraightLines)
            PathPlanningPolicy.STRAIGHT_LINES_ONLY
        else
            PathPlanningPolicy.GET_AROUND_OBSTACLES

        Log.i(TAG, "GoTo → straight=$useStraightLines stuck=$seemsStuck")

        goToFuture = humanToFollow.async().headFrame.andThenCompose { frame ->
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
        }.thenConsume { f ->
            when {
                f.isSuccess -> {
                    Log.i(TAG, "GoTo success")
                    seemsStuck = false
                    consecutiveErrors = 0
                    maybeFollowHuman(true)
                }
                f.isCancelled -> {
                    Log.i(TAG, "GoTo cancelled")
                    seemsStuck = false
                    consecutiveErrors = 0
                    // Non ripartiamo — o è stop() o è il timer di "vicino"
                }
                f.hasError() -> {
                    consecutiveErrors++
                    Log.e(TAG, "GoTo error #$consecutiveErrors: ${f.errorMessage}")
                    if (::chargingFlap.isInitialized && chargingFlap.state.open) {
                        Log.e(TAG, "Charging flap is OPEN — cannot move")
                    }

                    if (consecutiveErrors >= stuckThreshold) {
                        seemsStuck = true
                        followHumanListener?.onCantReachHuman()
                    }

                    // Backoff: max 3s
                    val delay = (consecutiveErrors * 500L).coerceAtMost(3000L)
                    timer.schedule(delay) {
                        // Alterna politica di path planning
                        maybeFollowHuman(!useStraightLines)
                    }
                }
            }
        }
    }

    // ── Distanza euclidea robot→umano ─────────────────────────────────────────

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

    // ── Reset ─────────────────────────────────────────────────────────────────

    private fun resetInternalState() {
        seemsStuck        = false
        consecutiveErrors = 0
        goToAttemptCounter = 0
        isFollowingHuman.set(false)
    }
}