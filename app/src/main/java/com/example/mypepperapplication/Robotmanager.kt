package com.example.mypepperapplication

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.human.Human
import com.example.mypepperapplication.controllers.FollowHuman
import com.example.mypepperapplication.controllers.PepperMovementController
import com.example.mypepperapplication.vision.ObjectDetectionController
import com.example.mypepperapplication.vision.PepperCameraController
import com.example.mypepperapplication.vision.VisualServoingController
import java.util.concurrent.atomic.AtomicReference

// RobotManager — orchestratore centrale -  serve per gestire le varie modalità
class RobotManager(
    private val listener: RobotManagerListener? = null
) {

    // ── Callback verso l'Activity ─────────────────────────────────────────────

    interface RobotManagerListener {
        fun onModeChanged(mode: RobotMode)
        fun onFollowingHuman()
        fun onCloseEnoughToHuman()
        fun onCantReachHuman()
        fun onDistanceChanged(meters: Double)
        fun onServoingStarted(label: String)
        fun onServoingStopped()
    }

    companion object {
        private const val TAG = "RobotManager"
    }

    // ── Controller interni ────────────────────────────────────────────────────

    val movementController  = PepperMovementController()
    val cameraController    = PepperCameraController()
    val detectionController = ObjectDetectionController()
    val servoingController  = VisualServoingController(movementController)

    // FollowHuman viene istanziato solo quando il robot è pronto e c'è un umano
    private var followHuman: FollowHuman? = null

    // ── Stato ─────────────────────────────────────────────────────────────────

    private val currentMode = AtomicReference(RobotMode.IDLE)
    private var qiContext: QiContext? = null

    val mode: RobotMode get() = currentMode.get()

    // ── QiContext lifecycle ───────────────────────────────────────────────────

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        movementController.onRobotReady(ctx)
        cameraController.onRobotReady(ctx)
        servoingController.onRobotReady(ctx)
        Log.i(TAG, "RobotManager: robot ready")
    }

    fun onRobotLost() {
        stopAll()
        movementController.onRobotLost()
        cameraController.onRobotLost()
        servoingController.onRobotLost()
        qiContext = null
        Log.i(TAG, "RobotManager: robot lost")
    }

    fun startFollowHumanAutoDetect(onNoHumanFound: (() -> Unit)? = null) {
        val ctx = qiContext ?: run {
            Log.e(TAG, "startFollowHumanAutoDetect: QiContext null")
            return
        }
        // HumanAwareness è un servizio diretto del QiContext (nessun Builder)
        ctx.humanAwareness.async().humansAround.andThenConsume { humans ->
            if (humans.isNullOrEmpty()) {
                Log.w(TAG, "No humans detected")
                onNoHumanFound?.invoke()
                return@andThenConsume
            }
            val nearest = humans.first()
            Log.i(TAG, "Auto-detected ${humans.size} human(s) — following nearest")
            startFollowHuman(nearest)
        }
    }

    fun startFollowHuman(human: Human) {
        val ctx = qiContext ?: run {
            Log.e(TAG, "startFollowHuman: QiContext null")
            return
        }
        if (!switchMode(RobotMode.FOLLOW_HUMAN)) return

        followHuman = FollowHuman(
            qiContext       = ctx,
            humanToFollow   = human,
            followHumanListener = object : FollowHuman.FollowHumanListener {
                override fun onFollowingHuman() {
                    Log.i(TAG, "→ Following human")
                    listener?.onFollowingHuman()
                }
                override fun onCloseEnough() {
                    Log.i(TAG, "→ Close enough to human")
                    listener?.onCloseEnoughToHuman()
                }
                override fun onCantReachHuman() {
                    Log.w(TAG, "→ Can't reach human")
                    listener?.onCantReachHuman()
                }
                override fun onDistanceToHumanChanged(distance: Double) {
                    listener?.onDistanceChanged(distance)
                }
            }
        ).also { it.start() }

        Log.i(TAG, "FollowHuman started")
    }

    fun stopFollowHuman() {
        if (currentMode.get() != RobotMode.FOLLOW_HUMAN) return
        followHuman?.stop()
        followHuman = null
        setMode(RobotMode.IDLE)
        Log.i(TAG, "FollowHuman stopped")
    }

    fun startVisualServoing(label: String = "person") {
        if (!switchMode(RobotMode.VISUAL_SERVOING)) return

        servoingController.startTracking(
            cameraController    = cameraController,
            detectionController = detectionController,
            label               = label
        )
        listener?.onServoingStarted(label)
        Log.i(TAG, "VisualServoing started for '$label'")
    }

    fun stopVisualServoing() {
        if (currentMode.get() != RobotMode.VISUAL_SERVOING) return
        servoingController.stopTracking()
        setMode(RobotMode.IDLE)
        listener?.onServoingStopped()
        Log.i(TAG, "VisualServoing stopped")
    }

    fun stopAll() {
        when (currentMode.get()) {
            RobotMode.FOLLOW_HUMAN -> {
                followHuman?.stop()
                followHuman = null
            }
            RobotMode.VISUAL_SERVOING -> {
                servoingController.stopTracking()
                listener?.onServoingStopped()
            }
            RobotMode.IDLE -> { /* niente da fermare */ }
        }
        setMode(RobotMode.IDLE)
        movementController.stopMovement()
        Log.i(TAG, "All modes stopped")
    }

    private fun switchMode(newMode: RobotMode): Boolean {
        val old = currentMode.get()
        if (old == newMode) {
            Log.w(TAG, "Already in mode $newMode — ignoring")
            return false
        }
        // Ferma modalità corrente
        when (old) {
            RobotMode.FOLLOW_HUMAN -> {
                followHuman?.stop()
                followHuman = null
            }
            RobotMode.VISUAL_SERVOING -> {
                servoingController.stopTracking()
                listener?.onServoingStopped()
            }
            RobotMode.IDLE -> { /* niente */ }
        }
        setMode(newMode)
        return true
    }

    private fun setMode(mode: RobotMode) {
        currentMode.set(mode)
        listener?.onModeChanged(mode)
        Log.i(TAG, "Mode → $mode")
    }
}