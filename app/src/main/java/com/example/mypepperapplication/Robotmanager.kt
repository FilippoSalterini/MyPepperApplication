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
 * Orchestratore centrale di logica robot:
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
        fun onChargingFlapOpen()
    }

    companion object {
        private const val TAG = "RobotManager"
    }

    private val movementController  = PepperMovementController()
    private val cameraController    = PepperCameraController()
    val detectionController = ObjectDetectionController()

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
    // locked human
    private var lockedHuman: Human? = null
    private var unlockTimerTask: java.util.TimerTask? = null
    private val unlockTimer = java.util.Timer()

    private var followHuman: FollowHuman? = null
    private val currentMode = AtomicReference(RobotMode.IDLE)
    private var qiContext: QiContext? = null

    val mode: RobotMode get() = currentMode.get()
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

    fun startFollowHumanAutoDetect(onNoHumanFound: (() -> Unit)? = null) {
        val ctx = qiContext ?: run { Log.e(TAG, "QiContext null"); return }
        ctx.humanAwareness.async().humansAround.andThenConsume { humans ->
            if (humans.isNullOrEmpty()) {
                Log.w(TAG, "No humans detected")
                if (lockedHuman != null) {
                    scheduleUnlock(onNoHumanFound)
                } else {
                    onNoHumanFound?.invoke()
                }
                return@andThenConsume
            }
            // C'è almeno un umano — cancella eventuale unlock pendente
            unlockTimerTask?.cancel()
            unlockTimerTask = null

            // Se abbiamo già un target e stiamo seguendo → ignora, non fare nulla
            if (lockedHuman != null && currentMode.get() == RobotMode.FOLLOW_HUMAN) {
                Log.d(TAG, "Already following locked human — skipping")
                return@andThenConsume
            }

            // Prima detection (o dopo unlock): scegli il più vicino e bloccalo
            Log.i(TAG, "Detected ${humans.size} human(s) — following nearest")
            val nearest = humans.first() // TODO: ordinare per distanza se hai robotFrame
            lockedHuman = nearest
            startFollowHuman(nearest)
        }
    }

    private fun scheduleUnlock(onNoHumanFound: (() -> Unit)?) {
        if (unlockTimerTask != null) return
        Log.d(TAG, "Human lost — scheduling unlock in 3s")
        unlockTimerTask = object : java.util.TimerTask() {
            override fun run() {
                if (currentMode.get() == RobotMode.FOLLOW_HUMAN && followHuman != null) {
                    Log.d(TAG, "FollowHuman still active — extending lock")
                    unlockTimerTask = null
                    return
                }
                Log.i(TAG, "Unlock: human gone for 3s")
                lockedHuman = null
                unlockTimerTask = null
                stopFollowHuman()
                onNoHumanFound?.invoke()
            }
        }
        unlockTimer.schedule(unlockTimerTask, 3000L)
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
                override fun onChargingFlapOpen()                { listener?.onChargingFlapOpen() }
                override fun onDistanceToHumanChanged(d: Double) { listener?.onDistanceChanged(d) }
            }
        ).also { it.start() }
        Log.i(TAG, "FollowHuman started")
    }

    fun stopFollowHuman() {
        if (currentMode.get() != RobotMode.FOLLOW_HUMAN) return
        lockedHuman = null
        unlockTimerTask?.cancel()
        unlockTimerTask = null
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