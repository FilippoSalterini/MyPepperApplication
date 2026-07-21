package com.example.mypepperapplication.core

import android.util.Log
import android.content.Context
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.`object`.holder.AutonomousAbilitiesType
import com.aldebaran.qi.sdk.`object`.holder.Holder
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.example.mypepperapplication.controllers.FollowHuman
import com.example.mypepperapplication.controllers.ApproachHuman
import com.example.mypepperapplication.controllers.FindHuman
import com.example.mypepperapplication.controllers.PepperMovementController
import com.example.mypepperapplication.controllers.HeadMovementController
import com.example.mypepperapplication.vision.BoundingBox
import com.example.mypepperapplication.vision.ObjectDetectionController
import com.example.mypepperapplication.vision.PepperCameraController
import com.example.mypepperapplication.vision.VisualServoingController
import com.example.mypepperapplication.conversation.ConversationController
import com.example.mypepperapplication.conversation.CMD_FOLLOW
import com.example.mypepperapplication.conversation.CMD_STOP
import com.example.mypepperapplication.conversation.CMD_APPROACH
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Timer
import java.util.TimerTask

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
    private val listener: RobotManagerListener? = null,
    private val context: Context,
    private val azureKey: String,
    private val serverIp: String

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
        fun onPersonFound(human: Human)
        fun onPersonNotFound()
    }

    companion object {
        private const val TAG = "RobotManager"
    }
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val modeMutex = Mutex()
    private val movementController  = PepperMovementController()
    private val cameraController    = PepperCameraController()
    private val headController      = HeadMovementController()
    val detectionController = ObjectDetectionController()
    var lastKnownPerson: Human? = null
        private set
    private var qiContext: QiContext? = null
    // locked human
    private var lockedHuman: Human? = null
    private var unlockTimerTask: TimerTask? = null
    private val unlockTimer = Timer()

    private var followHuman: FollowHuman? = null
    private var approachHuman: ApproachHuman? = null
    private var findHuman: FindHuman? = null
    private var conversationController: ConversationController? = null
    private var conversationJob: Job? = null
    private val currentMode = AtomicReference(RobotMode.IDLE)
    var onUserSpeechUi:  ((String) -> Unit)? = null
    var onRobotSpeechUi: ((String) -> Unit)? = null

    // disabilitare autonomous abilities
    private var servoingHolder: Holder? = null
    val mode: RobotMode get() = currentMode.get()
    private val servoingController = VisualServoingController(movementController, headController).also {
        it.listener = object : VisualServoingController.VisualServoingListener {
            override fun onObjectReached(label: String, box: BoundingBox) {
                Log.i(TAG, "Object reached: $label")
                managerScope.launch {
                    modeMutex.withLock {
                        cleanStopServoing()
                    }
                    conversationController?.sayMessage("I found the $label!", isActionFeedback = true)
                    withContext(Dispatchers.Main) { listener?.onObjectReached(label, box) }
                }
            }
            override fun onObjectLost(labels: List<String>) {
                Log.w(TAG, "Object lost: $labels")
                managerScope.launch {
                    modeMutex.withLock {
                        cleanStopServoing()
                        val labelStr = labels.joinToString(", ")
                        conversationController?.sayMessage("I couldn't find the $labelStr, sorry.", isActionFeedback = true)
                        withContext(Dispatchers.Main) {
                            listener?.onObjectLost(labels)
                            listener?.onServoingStopped()
                        }
                    }
                }
            }
        }
    }
    fun onRobotReady(ctx: QiContext) {
        qiContext = ctx
        movementController.onRobotReady(ctx)
        cameraController.onRobotReady(ctx)
        headController.onRobotReady(ctx)
        servoingController.onRobotReady()
        Log.i(TAG, "Robot ready")
        startConversationService(ctx)
    }

    fun onRobotLost() {
        unlockTimerTask?.cancel()
        unlockTimerTask = null
        conversationController?.stop()
        conversationController = null
        conversationJob?.cancel()
        conversationJob = null
        stopAll()
        movementController.onRobotLost()
        cameraController.onRobotLost()
        headController.onRobotLost()
        servoingController.onRobotLost()
        qiContext = null
        Log.i(TAG, "Robot lost")
    }
    private fun holdForServoing() {
        val ctx = qiContext ?: return
        try {
            releaseForServoing()
            servoingHolder = HolderBuilder.with(ctx)
                .withAutonomousAbilities(
                    AutonomousAbilitiesType.BASIC_AWARENESS,
                    AutonomousAbilitiesType.BACKGROUND_MOVEMENT,
                    AutonomousAbilitiesType.AUTONOMOUS_BLINKING
                )
                .build()
            servoingHolder?.async()?.hold()
            Log.i(TAG, "Autonomous abilities held")
        } catch (e: Exception) {
            Log.w(TAG, "Could not hold abilities: ${e.message}")
        }
    }

    private fun releaseForServoing() {
        try {
            servoingHolder?.async()?.release()
            Log.i(TAG, "Autonomous abilities successfully released")
        } catch (e: Exception) {
            Log.w(TAG, "Could not release abilities: ${e.message}")
        } finally {
            servoingHolder = null
        }
    }
    /**
     * FLUSSO DI CLEANUP ATOMICO CON TIMEOUT
     * Esegue l'arresto sequenziale e sicuro del ciclo di servoing.
     * Deve essere invocata sempre all'interno del blocco modeMutex.withLock.
     */
    private suspend fun cleanStopServoing() {
        Log.i(TAG, "Executing cleanStopServoing...")

        withTimeoutOrNull(1500L) {
            servoingController.stopTracking()
        }
        movementController.stopMovement()
        // Finestra di tolleranza per far respirare il middleware di Pepper
        delay(250L)
        // Rilascio effettivo delle abilità autonome libere da conflitti
        releaseForServoing()
        setMode(RobotMode.IDLE)
    }
    fun startFollowHumanAutoDetect(onNoHumanFound: (() -> Unit)? = null) {
        val ctx = qiContext ?: run { Log.e(TAG, "QiContext null"); return }

        ctx.humanAwareness.async().humansAround.andThenConsume { humans ->

            if (humans.isNullOrEmpty()) {
                Log.w(TAG, "No humans detected")
                if (currentMode.get() == RobotMode.FOLLOW_HUMAN) {
                    scheduleUnlock(onNoHumanFound)
                } else {
                    lockedHuman = null
                    onNoHumanFound?.invoke()
                }
                return@andThenConsume
            }

            unlockTimerTask?.cancel()
            unlockTimerTask = null

            if (lockedHuman != null && currentMode.get() == RobotMode.FOLLOW_HUMAN) {
                val stillPresent = humans.any { it === lockedHuman || isSameHuman(it, lockedHuman!!, ctx) }
                if (stillPresent) {
                    Log.d(TAG, "Locked human still present — continuing")
                } else {
                    Log.d(TAG, "Locked human not in list — scheduling unlock")
                    scheduleUnlock(onNoHumanFound)
                }
                return@andThenConsume
            }

            val nearest = findNearestHuman(humans, ctx)
            if (nearest == null) { onNoHumanFound?.invoke(); return@andThenConsume }

            Log.i(TAG, "Detected ${humans.size} human(s) — locking nearest")
            lockedHuman = nearest
            startFollowHuman(nearest)
        }
    }

    private fun findNearestHuman(humans: List<Human>, ctx: QiContext): Human? {
        if (humans.isEmpty()) return null
        if (humans.size == 1) return humans.first()

        return try {
            val rFrame = ctx.actuation.robotFrame()
            humans.minByOrNull { human ->
                try {
                    val t = human.headFrame
                        .computeTransform(rFrame).transform.translation
                    sqrt(t.x * t.x + t.y * t.y)
                } catch (_: Exception) {
                    Double.MAX_VALUE  // se non riesce a computare, metti in fondo
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findNearestHuman fallback: ${e.message}")
            humans.first()
        }
    }

    private fun scheduleUnlock(onNoHumanFound: (() -> Unit)?) {
        if (unlockTimerTask != null) return

        // Durante HOLDING Pepper sta ruotando — serve più tolleranza
        val delayMs = if (currentMode.get() == RobotMode.FOLLOW_HUMAN) 5000L else 3000L

        Log.d(TAG, "Human lost — scheduling unlock in ${delayMs}ms")
        unlockTimerTask = object : TimerTask() {
            override fun run() {
                unlockTimerTask = null
                Log.i(TAG, "Unlock: locked human gone — stopping follow")
                lockedHuman = null
                stopFollowHuman()
                onNoHumanFound?.invoke()
            }
        }
        unlockTimer.schedule(unlockTimerTask, delayMs)
    }

    fun startFollowHuman(human: Human) {
        val ctx = qiContext ?: run { Log.e(TAG, "QiContext null"); return }

        managerScope.launch {
            modeMutex.withLock {
                if (!switchModeAsync(RobotMode.FOLLOW_HUMAN)) return@withLock

                followHuman = FollowHuman(
                    qiContext           = ctx,
                    humanToFollow       = human,
                    followHumanListener = object : FollowHuman.FollowHumanListener {
                        override fun onFollowingHuman()                  { listener?.onFollowingHuman() }
                        override fun onCloseEnough()                     { listener?.onCloseEnoughToHuman()
                        managerScope.launch { conversationController?.sayMessage("I'm close enough!") }
                        }
                        override fun onCantReachHuman()                  { listener?.onCantReachHuman()
                        managerScope.launch { conversationController?.sayMessage("I'm having trouble reaching you!") }
                        }
                        override fun onChargingFlapOpen()                { listener?.onChargingFlapOpen()
                        managerScope.launch { conversationController?.sayMessage("I can't move, my charging flap is open!") }
                        }
                        override fun onDistanceToHumanChanged(distance: Double) { listener?.onDistanceChanged(distance) }
                    }
                ).also { it.start() }

                Log.i(TAG, "FollowHuman started under Mutex protection")
            }
        }
    }

    fun stopFollowHuman() {
        if (currentMode.get() != RobotMode.FOLLOW_HUMAN) return
        managerScope.launch {
            modeMutex.withLock {
                if (currentMode.get() == RobotMode.FOLLOW_HUMAN) {
                    lockedHuman = null
                    unlockTimerTask?.cancel()
                    unlockTimerTask = null
                    followHuman?.stop()
                    followHuman = null
                    setMode(RobotMode.IDLE)
                    Log.i(TAG, "FollowHuman stopped safely")
                }
            }
        }
    }
    fun startVisualServoing(label: String) = startVisualServoing(listOf(label))

    fun startVisualServoing(labels: List<String>) {
        managerScope.launch {
            modeMutex.withLock {
                if (!switchModeAsync(RobotMode.VISUAL_SERVOING)) return@withLock

                holdForServoing()
                servoingController.startTracking(cameraController, detectionController, labels)

                withContext(Dispatchers.Main) { listener?.onServoingStarted(labels) }
                Log.i(TAG, "VisualServoing started for $labels")
            }
        }
    }

    fun stopVisualServoing() {
        if (currentMode.get() != RobotMode.VISUAL_SERVOING) return
        managerScope.launch {
            modeMutex.withLock {
                if (currentMode.get() == RobotMode.VISUAL_SERVOING) {
                    cleanStopServoing()
                    withContext(Dispatchers.Main) { listener?.onServoingStopped() }
                }
            }
        }
    }
    fun stopAll() {
        managerScope.launch {
            modeMutex.withLock {
                Log.i(TAG, "stopAll invoked via Mutex")
                when (currentMode.get()) {
                    RobotMode.FOLLOW_HUMAN -> {
                        followHuman?.stop()
                        followHuman = null
                        lockedHuman = null
                        unlockTimerTask?.cancel()
                        unlockTimerTask = null
                    }
                    RobotMode.APPROACH_HUMAN -> {
                        approachHuman?.stop()
                        approachHuman = null
                    }
                    RobotMode.VISUAL_SERVOING -> {
                        cleanStopServoing()
                        withContext(Dispatchers.Main) { listener?.onServoingStopped() }
                    }
                    RobotMode.FIND_PERSON -> {
                        findHuman?.stop()
                        findHuman = null
                    }
                    RobotMode.IDLE -> { }
                }
                setMode(RobotMode.IDLE)
                movementController.stopMovement()
                Log.i(TAG, "stopAll completed successfully")
            }
        }
    }

    private suspend fun switchModeAsync(newMode: RobotMode): Boolean {
        val old = currentMode.get()
        if (old == newMode) { Log.w(TAG, "Already in $newMode"); return false }
        when (old) {
            RobotMode.FOLLOW_HUMAN -> {
                followHuman?.stop()
                followHuman = null
            }
            RobotMode.APPROACH_HUMAN -> {
                approachHuman?.stop()
                approachHuman = null
            }
            RobotMode.VISUAL_SERVOING -> {
                cleanStopServoing()
                withContext(Dispatchers.Main) { listener?.onServoingStopped() }
            }

            RobotMode.FIND_PERSON -> {
                findHuman?.stop()
                findHuman = null
            }
            RobotMode.IDLE -> { }
        }
        setMode(newMode)
        return true
    }

    private fun setMode(mode: RobotMode) {
        currentMode.set(mode)
        listener?.onModeChanged(mode)
        Log.i(TAG, "Mode → $mode")
    }

    //piccolo check da mettere poi nel lock per verificare che sia stesso umano entro un range
    private fun isSameHuman(candidate: Human, reference: Human, ctx: QiContext): Boolean {
        return try {
            val rFrame = ctx.actuation.robotFrame()
            val t1 = candidate.headFrame.computeTransform(rFrame).transform.translation
            val t2 = reference.headFrame.computeTransform(rFrame).transform.translation
            val dx = t1.x - t2.x
            val dy = t1.y - t2.y
            sqrt(dx * dx + dy * dy) < 0.5  // stessa persona se entro 50cm RIVEDI
        } catch (_: Exception) { false }
    }

    /*
    implementata funzione per approcciare l umano ( diverso da following che puo essere sfruttando per seguire l umanono
     */
    fun startApproachHuman() {
        val ctx = qiContext ?: run { Log.e(TAG, "QiContext null"); return }

        managerScope.launch {
            modeMutex.withLock {
                if (!switchModeAsync(RobotMode.APPROACH_HUMAN)) return@withLock

                approachHuman = ApproachHuman(
                    qiContext = ctx,
                    listener  = object : ApproachHuman.ApproachHumanListener {
                        override fun onApproachComplete(human: Human) {
                            managerScope.launch {
                                modeMutex.withLock {
                                    approachHuman = null
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage("I'm right here with you now.",isActionFeedback = true)
                                    withContext(Dispatchers.Main) { listener?.onCloseEnoughToHuman() }
                                }
                            }
                        }
                        override fun onNoHumanFound() {
                            managerScope.launch {
                                modeMutex.withLock {
                                    approachHuman=null
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage("I couldn't find anyone to approach.",isActionFeedback = true)
                                }
                            }
                        }
                        override fun onApproachFailed() {
                            managerScope.launch {
                                modeMutex.withLock {
                                    approachHuman=null
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage("I had trouble approaching, sorry.",isActionFeedback = true)
                                }
                            }
                        }
                        override fun onDistanceChanged(distance: Double) {
                            listener?.onDistanceChanged(distance)
                        }
                    }
                ).also { it.start() }

                Log.i(TAG, "ApproachHuman started under Mutex protection")
            }
        }
    }

    fun stopApproachHuman() {
        if (currentMode.get() != RobotMode.APPROACH_HUMAN) return
        managerScope.launch {
            modeMutex.withLock {
                if (currentMode.get() == RobotMode.APPROACH_HUMAN) {
                    approachHuman?.stop()
                    approachHuman = null
                    setMode(RobotMode.IDLE)
                    Log.i(TAG, "ApproachHuman stopped safely")
                }
            }
        }
    }

    fun startFindPerson() {
        val ctx = qiContext ?: run { Log.e(TAG, "QiContext null"); return }

        managerScope.launch {
            modeMutex.withLock {
                if (!switchModeAsync(RobotMode.FIND_PERSON)) return@withLock

                findHuman = FindHuman(
                    qiContext           = ctx,
                    movementController  = movementController,
                    headController      = headController
                ).also {
                    it.listener = object : FindHuman.FindPersonListener {
                        override fun onPersonFound(human: Human) {
                            managerScope.launch {
                                modeMutex.withLock {
                                    findHuman = null
                                    lastKnownPerson = human   // ← state tracking
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage("I found you! I can see you now.",isActionFeedback = true)
                                    withContext(Dispatchers.Main) { listener?.onPersonFound(human) }
                                }
                            }
                        }
                        override fun onPersonNotFound() {
                            managerScope.launch {
                                modeMutex.withLock {
                                    findHuman = null
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage("I looked around but I couldn't find anyone.",isActionFeedback = true)
                                    withContext(Dispatchers.Main) { listener?.onPersonNotFound() }
                                }
                            }
                        }
                    }
                    it.start()
                }
                Log.i(TAG, "FindPerson started")
            }
        }
    }

    fun stopFindPerson() {
        if (currentMode.get() != RobotMode.FIND_PERSON) return
        managerScope.launch {
            modeMutex.withLock {
                findHuman?.stop()
                findHuman = null
                setMode(RobotMode.IDLE)
            }
        }
    }
    private fun startConversationService(ctx: QiContext) {
        conversationController?.stop()
        conversationJob?.cancel()

        val controller = ConversationController(
            context   = context,
            qiContext = ctx,
            azureKey  = azureKey,
            serverIp  = serverIp
        ).also {
            it.onListening   = { Log.d(TAG, "Conversation: listening") }
            it.onUserSpeech  = { text -> Log.i(TAG, "User: $text") }
            it.onRobotSpeech = { text -> Log.i(TAG, "Pepper: $text") }
            it.onMotionCommand = { cmd ->
                when {
                    cmd == CMD_FOLLOW   -> startFollowHumanAutoDetect()
                    cmd == CMD_STOP     -> stopAll()
                //        managerScope.launch {
                //        movementController.stopMovement()
                //    }
                    cmd == CMD_APPROACH -> startApproachHuman()
                    cmd.startsWith("track:") -> {
                        val label = cmd.removePrefix("track:")
                        startVisualServoing(label)
                    }
                }
            }
            it.onUserSpeech  = { text ->
                Log.i(TAG, "User: $text")
                // callback verso MainActivity per aggiornare UI
                onUserSpeechUi?.invoke(text)
            }
            it.onRobotSpeech = { text ->
                Log.i(TAG, "Pepper: $text")
                onRobotSpeechUi?.invoke(text)
            }
        }
        conversationController = controller

        conversationJob = managerScope.launch {
            try {
                controller.startConversationLoop()
                delay(2000)
                val freshCtx = qiContext ?: return@launch   // esce se robot è perso
                if (isActive) startConversationService(freshCtx)
            } catch (_: CancellationException) {
                Log.d(TAG, "Conversation job cancelled")
            }
        }

        Log.i(TAG, "Conversation service started")
    }
}