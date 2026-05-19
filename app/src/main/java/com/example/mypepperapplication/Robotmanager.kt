package com.example.mypepperapplication

import android.graphics.Bitmap
import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.human.Human
import com.example.mypepperapplication.controllers.FollowHuman
import com.example.mypepperapplication.controllers.PepperMovementController
import com.example.mypepperapplication.vision.BoundingBox
import com.example.mypepperapplication.vision.ObjectDetectionController
import com.example.mypepperapplication.vision.PepperCameraController
import com.example.mypepperapplication.vision.VisualServoingController
import java.util.concurrent.atomic.AtomicReference

// =============================================================================
// RobotManager
// =============================================================================
/**
 * Orchestratore centrale
 *   - startFollowHuman / stopFollowHuman
 *   - startVisualServoing / stopVisualServoing
 *   - processSnapshot
 */
class RobotManager(
    private val listener: RobotManagerListener? = null
) {

    interface RobotManagerListener {
        fun onModeChanged(mode: RobotMode)
        fun onFollowingHuman()
        fun onCloseEnoughToHuman()
        fun onCantReachHuman()
        fun onDistanceChanged(meters: Double)
        fun onServoingStarted(labels: List<String>)
        fun onServoingStopped()
        fun onObjectReached(label: String, box: BoundingBox)
        fun onObjectLost(labels: List<String>)
    }

    companion object {
        private const val TAG = "RobotManager"
    }

    // Controller interni: privati — l'Activity non li deve conoscere.
    private val movementController  = PepperMovementController()
    private val cameraController    = PepperCameraController()
    val detectionController = ObjectDetectionController()   // rimane interno; esposto solo per config in onRobotFocusGained

    private val servoingController = VisualServoingController(movementController).also {
        it.listener = object : VisualServoingController.VisualServoingListener {
            override fun onObjectReached(label: String, box: BoundingBox) {
                Log.i(TAG, "Object reached: $label")
                setMode(RobotMode.IDLE)
                listener?.onObjectReached(label, box)
            }
            override fun onObjectLost(labels: List<String>) {
                Log.w(TAG, "Object lost: $labels")
                setMode(RobotMode.IDLE)
                listener?.onObjectLost(labels)
                listener?.onServoingStopped()
            }
        }
    }

    private var followHuman: FollowHuman? = null
    private val currentMode = AtomicReference(RobotMode.IDLE)
    private var qiContext: QiContext? = null

    val mode: RobotMode get() = currentMode.get()

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        movementController.onRobotReady(ctx)
        cameraController.onRobotReady(ctx)
        servoingController.onRobotReady()
        Log.i(TAG, "Robot ready")
    }

    fun onRobotLost() {
        stopAll()
        movementController.onRobotLost()
        cameraController.onRobotLost()
        servoingController.onRobotLost()
        qiContext = null
        Log.i(TAG, "Robot lost")
    }

    // ----------------------------------------------------------------
    // Public API  (tutto ciò che MainActivity può chiamare)
    // ----------------------------------------------------------------

    fun startFollowHumanAutoDetect(onNoHumanFound: (() -> Unit)? = null) {
        val ctx = qiContext ?: run { Log.e(TAG, "QiContext null"); return }
        ctx.humanAwareness.async().humansAround.andThenConsume { humans ->
            if (humans.isNullOrEmpty()) {
                Log.w(TAG, "No humans detected")
                onNoHumanFound?.invoke()
                return@andThenConsume
            }
            Log.i(TAG, "Detected ${humans.size} human(s) — following nearest")
            startFollowHuman(humans.first())
            // TODO: scegliere l'umano più vicino invece del primo in lista
        }
    }

    fun startFollowHuman(human: Human) {
        val ctx = qiContext ?: run { Log.e(TAG, "QiContext null"); return }
        if (!switchMode(RobotMode.FOLLOW_HUMAN)) return

        followHuman = FollowHuman(
            qiContext           = ctx,
            humanToFollow       = human,
            followHumanListener = object : FollowHuman.FollowHumanListener {
                override fun onFollowingHuman()                  { listener?.onFollowingHuman() }
                override fun onCloseEnough()                     { listener?.onCloseEnoughToHuman() }
                override fun onCantReachHuman()                  { listener?.onCantReachHuman() }
                override fun onDistanceToHumanChanged(d: Double) { listener?.onDistanceChanged(d) }
            }
        ).also { it.start() }
        Log.i(TAG, "FollowHuman started")
    }

    fun stopFollowHuman() {
        if (currentMode.get() != RobotMode.FOLLOW_HUMAN) return
        followHuman?.stop()
        followHuman = null
        setMode(RobotMode.IDLE)
    }

    fun startVisualServoing(label: String) = startVisualServoing(listOf(label))

    fun startVisualServoing(labels: List<String>) {
        if (!switchMode(RobotMode.VISUAL_SERVOING)) return
        servoingController.startTracking(
            cameraController    = cameraController,
            detectionController = detectionController,
            labels              = labels
        )
        listener?.onServoingStarted(labels)
        Log.i(TAG, "VisualServoing started for $labels")
    }

    fun stopVisualServoing() {
        if (currentMode.get() != RobotMode.VISUAL_SERVOING) return
        servoingController.stopTracking()
        setMode(RobotMode.IDLE)
        listener?.onServoingStopped()
    }

    /**
     * Facade per scattare una foto e rilevare oggetti.
     * L'Activity passa solo due lambda; non conosce cameraController né detectionController.
     */
    fun processSnapshot(
        onBitmap: (Bitmap) -> Unit,
        onDetection: (boxes: List<com.example.mypepperapplication.vision.BoundingBox>, w: Int, h: Int) -> Unit
    ) {
        cameraController.takeSinglePicture { bitmap, _ ->
            onBitmap(bitmap)
            detectionController.detect(bitmap) { boxes, w, h ->
                onDetection(boxes, w, h)
            }
        }
    }

    fun stopAll() {
        when (currentMode.get()) {
            RobotMode.FOLLOW_HUMAN    -> { followHuman?.stop(); followHuman = null }
            RobotMode.VISUAL_SERVOING -> { servoingController.stopTracking(); listener?.onServoingStopped() }
            RobotMode.IDLE            -> { }
        }
        setMode(RobotMode.IDLE)
        movementController.stopMovement()
        Log.i(TAG, "stopAll")
    }

    // ----------------------------------------------------------------
    // Internal mode management
    // ----------------------------------------------------------------

    private fun switchMode(newMode: RobotMode): Boolean {
        val old = currentMode.get()
        if (old == newMode) { Log.w(TAG, "Already in $newMode"); return false }
        when (old) {
            RobotMode.FOLLOW_HUMAN    -> { followHuman?.stop(); followHuman = null }
            RobotMode.VISUAL_SERVOING -> { servoingController.stopTracking(); listener?.onServoingStopped() }
            RobotMode.IDLE            -> { }
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