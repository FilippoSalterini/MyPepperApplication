package com.example.mypepperapplication.controllers

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.actuation.PathPlanningPolicy
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.builder.GoToBuilder
import kotlinx.coroutines.*
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.hypot

/**
 * ApproachHuman — avvicina Pepper a un umano, si ferma alla distanza
 * sociale e notifica onApproachComplete(). One-shot, nessun re-follow.
 */
class ApproachHuman(
    private val qiContext: QiContext,
    private val listener: ApproachHumanListener? = null
) {
    interface ApproachHumanListener {
        fun onApproachComplete(human: Human)
        fun onNoHumanFound()
        fun onApproachFailed()
        fun onDistanceChanged(distance: Double) {}
    }

    companion object {
        private const val TAG = "ApproachHuman"
        const val SOCIAL_DISTANCE = 1.2          // ferma qui
        private const val POLL_INTERVAL_MS = 500L
        private const val HUMAN_SEARCH_TIMEOUT_MS = 5_000L
        private const val MAX_GOTO_ERRORS = 5
    }

    private val isRunning  = AtomicBoolean(false)
    private val isComplete = AtomicBoolean(false)

    @Volatile private var goToFuture: Future<Void>? = null
    @Volatile private var robotFrame: Frame? = null
    @Volatile private var targetHuman: Human? = null

    private var distanceTimer = Timer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var errorCount = 0

    // -------------------------------------------------------------------------

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        isComplete.set(false)
        errorCount = 0

        // Recupera robotFrame una volta sola
        qiContext.actuation.async().robotFrame().andThenConsume { rf ->
            robotFrame = rf
        }

        scope.launch {
            val human = findHuman()
            if (human == null) {
                Log.w(TAG, "No human found")
                isRunning.set(false)
                withContext(Dispatchers.Main) { listener?.onNoHumanFound() }
                return@launch
            }
            targetHuman = human
            Log.i(TAG, "Human found — starting approach")
            startDistanceMonitor(human)
            issueGoTo(human)
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        cleanup()
        Log.i(TAG, "ApproachHuman stopped")
    }

    // -------------------------------------------------------------------------

    private fun issueGoTo(human: Human) {
        if (!isRunning.get() || isComplete.get()) return

        human.async().headFrame.andThenCompose { liveFrame ->
            GoToBuilder.with(qiContext)
                .withFrame(liveFrame)
                .withPathPlanningPolicy(PathPlanningPolicy.GET_AROUND_OBSTACLES)
                .buildAsync()
                .andThenCompose { goTo -> goTo.async().run() }
        }.thenConsume { f ->
            when {
                isComplete.get() -> { /* distanza raggiunta — ignora */ }
                !isRunning.get() -> { /* stop esterno */ }
                f.isSuccess -> {
                    // GoTo completato ma distanza non ancora raggiunta → riprova
                    Log.d(TAG, "GoTo success, re-issuing")
                    errorCount = 0
                    issueGoTo(human)
                }
                f.isCancelled -> Log.d(TAG, "GoTo cancelled")
                f.hasError() -> {
                    Log.w(TAG, "GoTo error: ${f.errorMessage}")
                    errorCount++
                    if (errorCount >= MAX_GOTO_ERRORS) {
                        Log.e(TAG, "Too many GoTo errors — approach failed")
                        isRunning.set(false)
                        cleanup()
                        listener?.onApproachFailed()
                    } else {
                        // breve pausa poi riprova
                        scope.launch {
                            delay(600L)
                            issueGoTo(human)
                        }
                    }
                }
            }
        }.also { goToFuture = it as Future<Void> }
    }

    /**
     * Polling della distanza: appena < SOCIAL_DISTANCE, ferma tutto
     * e notifica onApproachComplete.
     */
    private fun startDistanceMonitor(human: Human) {
        distanceTimer = Timer()
        distanceTimer.scheduleAtFixedRate(0L, POLL_INTERVAL_MS) {
            if (!isRunning.get()) { distanceTimer.cancel(); return@scheduleAtFixedRate }

            val rf = robotFrame ?: return@scheduleAtFixedRate

            human.async().headFrame.andThenConsume { liveFrame ->
                if (!isRunning.get()) return@andThenConsume
                try {
                    val t = liveFrame.computeTransform(rf).transform.translation
                    val dist = hypot(t.x, t.y)
                    listener?.onDistanceChanged(dist)

                    if (dist <= SOCIAL_DISTANCE && isComplete.compareAndSet(false, true)) {
                        Log.i(TAG, "Social distance reached (%.2fm) — complete".format(dist))
                        isRunning.set(false)
                        goToFuture?.requestCancellation()
                        distanceTimer.cancel()
                        listener?.onApproachComplete(human)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Distance check error: ${e.message}")
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun findHuman(): Human? = withTimeoutOrNull(HUMAN_SEARCH_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            qiContext.humanAwareness.async().humansAround
                .andThenConsume { humans ->
                    cont.resume(humans?.firstOrNull()) {}
                }
        }
    }

    private fun cleanup() {
        distanceTimer.cancel()
        goToFuture?.requestCancellation()
        goToFuture = null
        scope.coroutineContext.cancelChildren()
    }
}