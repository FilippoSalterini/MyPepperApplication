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
import com.example.mypepperapplication.conversation.CMD_START_MAP
import com.example.mypepperapplication.conversation.CMD_STOP_MAP
import com.example.mypepperapplication.conversation.CMD_LOAD_MAP
import com.example.mypepperapplication.conversation.CMD_FOLLOW
import com.example.mypepperapplication.conversation.CMD_STOP
import com.example.mypepperapplication.conversation.CMD_APPROACH
import com.example.mypepperapplication.conversation.CMD_FIND_PERSON
import com.example.mypepperapplication.navigation.NavigationController
import com.example.mypepperapplication.planning.ActionResult
import com.example.mypepperapplication.conversation.LabelIt
import com.example.mypepperapplication.planning.ActionPayload
import com.example.mypepperapplication.planning.ConversationSpeechBridge
import com.example.mypepperapplication.planning.WorldStateManager
import com.example.mypepperapplication.planning.PlanExecutor
import com.example.mypepperapplication.planning.PlannerClient
import com.example.mypepperapplication.planning.ExecutionOutcome
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.resume
import java.util.Timer
import java.util.TimerTask
import android.graphics.Bitmap
import java.io.FileOutputStream

// =============================================================================
// RobotManager
// =============================================================================
/**
 * Orchestratore e gestore centrale dello stato del robot Pepper coordinando l'hardware, i controller di movimento,
 * la visione artificiale e la logica di conversazione.
 *
 * Le sue responsabilità principali includono:
 * - **Gestione della Concorrenza e degli Stati**: Regola la modalità operativa corrente ([RobotMode])
 *   garantendo la thread-safety e transizioni atomiche tramite un [Mutex] (`modeMutex`), prevenendo
 *   conflitti tra azioni concorrenti (es. insegui umano vs. tracciamento oggetto).
 *
 * - **Coordinamento dei Moduli**: Integre e gestisce il ciclo di vita dei controller di movimento
 *   ([PepperMovementController], [HeadMovementController]), della fotocamera ([PepperCameraController])
 *   e della visione artificiale ([ObjectDetectionController], [VisualServoingController]).
 *
 * - **Interazione Umana**: Gestisce i flussi di ricerca, avvicinamento e inseguimento delle persone
 *   ([FindHuman], [ApproachHuman], [FollowHuman]) integrando logiche di rilascio/puntamento dinamico.
 *
 * - **Gestione Abilità Autonome**: Sospende o ripristina le abilità di base di Pepper
 *   ([AutonomousAbilitiesType]) tramite [Holder] per evitare interferenze durante compiti specifici
 *   (es. Visual Servoing o Mappatura).
 *
 * - **Integrazione Navigazione & Conversazione**: Collega il [ConversationController] con il
 *   [NavigationController] per tradurre i comandi vocali/multimodali ricevuti in azioni concrete
 *   (avvio/stop mappatura, salvataggio e navigazione verso i PoI, salvataggio di preview grafiche della mappa).
 *
 * Per ricavare informazioni sulla mappa:
 * - (controlla) adb shell run-as com.example.mypepperapplication cat files/map_preview.png > map_preview.png
 * - cmd /c "adb exec-out run-as com.example.mypepperapplication cat files/map_preview.png > map_preview.png"
 * - Format-Hex map_preview.png -Count 8
 *
 * Per ricavare traiettoria e POI :
 * - adb shell run-as com.example.mypepperapplication cat files/trajectory.json > trajectory.json
 * - adb shell run-as com.example.mypepperapplication cat files/pois.json > pois.json
 *
 * @property listener Interfaccia di callback [RobotManagerListener] per notificare gli eventi di stato all'UI/MainActivity.
 * @property context Il contesto Android dell'applicazione.
 * @property azureKey Chiave API per i servizi vocali/conversazionali Azure.
 * @property serverIp Indirizzo IP del server backend per l'elaborazione dei comandi o l'LLM.
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

        /** Invocata quando il target del servoing è stato centrato (PHASE 1). Nessun approccio fisico avviene più. */
        fun onObjectCentered(label: String, box: BoundingBox)
        fun onObjectLost(labels: List<String>)

        /**
         * Invocata a fine SCAN con tutti gli oggetti visti durante la rotazione a 360°,
         * associati implicitamente al PoI/stanza corrente (nota: l'associazione esplicita
         * a WorldStateManager non è ancora implementata — vedi TODO in memoria).
         */
        fun onObjectsSpotted(spotted: List<BoundingBox>)
        fun onChargingFlapOpen()
        fun onPersonFound(human: Human)
        fun onPersonNotFound()
    }

    companion object {
        private const val TAG = "RobotManager"
        private const val VISUAL_SERVOING_AWAIT_TIMEOUT_MS = 180_000L
        private const val FIND_HUMAN_AWAIT_TIMEOUT_MS = 45_000L
        private const val APPROACH_HUMAN_AWAIT_TIMEOUT_MS = 60_000L
    }

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val modeMutex = Mutex()
    private val movementController = PepperMovementController()
    private val cameraController = PepperCameraController()
    private val headController = HeadMovementController()
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
    private var mappingJob: Job? = null
    private var localizeJob: Job? = null
    private var worldStateManager: WorldStateManager? = null
    private var planExecutor: PlanExecutor? = null
    private var planJob: Job? = null

    private var conversationController: ConversationController? = null

    val speechBridge = ConversationSpeechBridge { msg ->
        conversationController?.sayMessage(msg, isActionFeedback = true)
    }
    private var navigationController: NavigationController? = null
    private val mapFile: File by lazy { File(context.filesDir, "map.bin") }
    private var conversationJob: Job? = null
    private val currentMode = AtomicReference(RobotMode.IDLE)
    var onUserSpeechUi: ((String) -> Unit)? = null
    var onRobotSpeechUi: ((String) -> Unit)? = null

    // disabilitare autonomous abilities
    private var servoingHolder: Holder? = null
    val mode: RobotMode get() = currentMode.get()
    private val servoingController =
        VisualServoingController(movementController, headController).also { it ->
            it.listener = object : VisualServoingController.VisualServoingListener {
                override fun onObjectCentered(label: String, box: BoundingBox) {
                    Log.i(TAG, "Object centered: $label")
                    managerScope.launch {
                        modeMutex.withLock {
                            cleanStopServoing()
                        }
                        conversationController?.sayMessage(
                            "Ho trovato ${LabelIt.of(label)}!",
                            isActionFeedback = true
                        )
                        withContext(Dispatchers.Main) { listener?.onObjectCentered(label, box) }
                    }
                }

                override fun onObjectLost(labels: List<String>) {
                    Log.w(TAG, "Object lost: $labels")
                    managerScope.launch {
                        modeMutex.withLock {
                            cleanStopServoing()
                            val labelStr = labels.joinToString(", ") { LabelIt.of(it) }
                            conversationController?.sayMessage(
                                "Non riesco a trovare $labelStr, mi dispiace.",
                                isActionFeedback = true
                            )
                            withContext(Dispatchers.Main) {
                                listener?.onObjectLost(labels)
                                listener?.onServoingStopped()
                            }
                        }
                    }
                }

                override fun onObjectsSpotted(spotted: List<BoundingBox>) {
                    Log.i(TAG, "Objects spotted during scan: ${spotted.map { it.label }}")
                    managerScope.launch {
                        withContext(Dispatchers.Main) { listener?.onObjectsSpotted(spotted) }
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
        navigationController = NavigationController(ctx)
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
        navigationController = null
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
        if (currentMode.get() != RobotMode.EMERGENCY_STOPPED)
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
                val stillPresent =
                    humans.any { it === lockedHuman || isSameHuman(it, lockedHuman!!, ctx) }
                if (stillPresent) {
                    Log.d(TAG, "Locked human still present — continuing")
                } else {
                    Log.d(TAG, "Locked human not in list — scheduling unlock")
                    scheduleUnlock(onNoHumanFound)
                }
                return@andThenConsume
            }

            val nearest = findNearestHuman(humans, ctx)
            if (nearest == null) {
                onNoHumanFound?.invoke(); return@andThenConsume
            }

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
                    qiContext = ctx,
                    humanToFollow = human,
                    followHumanListener = object : FollowHuman.FollowHumanListener {
                        override fun onFollowingHuman() {
                            listener?.onFollowingHuman()
                        }

                        override fun onCloseEnough() {
                            listener?.onCloseEnoughToHuman()
                            managerScope.launch { conversationController?.sayMessage("Sono abbastanza vicino!") }
                        }

                        override fun onCantReachHuman() {
                            listener?.onCantReachHuman()
                            managerScope.launch { conversationController?.sayMessage("Faccio fatica a raggiungerti!") }
                        }

                        override fun onChargingFlapOpen() {
                            listener?.onChargingFlapOpen()
                            managerScope.launch { conversationController?.sayMessage("Non riesco a muovermi, lo sportello di ricarica è aperto!") }
                        }

                        override fun onDistanceToHumanChanged(distance: Double) {
                            listener?.onDistanceChanged(distance)
                        }
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
        planJob?.cancel()
        planJob = null
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

                    RobotMode.NAVIGATING -> {
                        navigationController?.stopMovement()
                    }

                    RobotMode.IDLE -> {}
                    RobotMode.EMERGENCY_STOPPED -> {}
                }
                setMode(RobotMode.IDLE)
                movementController.stopMovement()
                Log.i(TAG, "stopAll completed successfully")
            }
        }
    }

    private suspend fun switchModeAsync(newMode: RobotMode): Boolean {
        val old = currentMode.get()
        if (old == newMode) {
            Log.w(TAG, "Already in $newMode"); return false
        }
        if (old == RobotMode.EMERGENCY_STOPPED) {
            Log.w(TAG, "Blocked: robot is EMERGENCY_STOPPED — call resetEmergencyStop() first")
            return false
        }
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

            RobotMode.NAVIGATING -> {
                navigationController?.stopMovement()
            }

            RobotMode.IDLE -> {}
            RobotMode.EMERGENCY_STOPPED -> {
                Log.w(TAG, "Blocked: robot is EMERGENCY_STOPPED — call resetEmergencyStop() first")
                return false
            }
        }
        setMode(newMode)
        return true
    }

    // EMERGENCY STOP
    fun emergencyStop() {
        Log.w(TAG, "EMERGENCY STOP triggered")
        movementController.stopMovement()
        navigationController?.stopMovement()
        planJob?.cancel()
        planJob = null
        managerScope.launch {
            modeMutex.withLock {
                unlockTimerTask?.cancel(); unlockTimerTask = null
                followHuman?.stop(); followHuman = null
                approachHuman?.stop(); approachHuman = null
                findHuman?.stop(); findHuman = null

                if (currentMode.get() == RobotMode.VISUAL_SERVOING) {
                    withTimeoutOrNull(300L) { servoingController.stopTracking() }
                    releaseForServoing()
                }
                lockedHuman = null
                movementController.stopMovement()
                navigationController?.stopMovement()
                setMode(RobotMode.EMERGENCY_STOPPED)
                withContext(Dispatchers.Main) { listener?.onServoingStopped() }
                Log.w(
                    TAG,
                    "EMERGENCY STOP completed — robot locked, call resetEmergencyStop() to resume"
                )
            }
        }
    }

    /* Sblocca il robot da EMERGENCY_STOPPED (classica funzionalità RESET e lo riporta in IDLE. */
    fun resetEmergencyStop() {
        managerScope.launch {
            modeMutex.withLock {
                if (currentMode.get() != RobotMode.EMERGENCY_STOPPED) return@withLock
                setMode(RobotMode.IDLE)
                Log.i(TAG, "Emergency stop reset — back to IDLE")
            }
        }
    }

    /**
     * Variante bloccante di GoTo, pensata per il PlanExecutor: attende il completamento
     * della navigazione e ne restituisce l'esito come [ActionResult].
     *
     * Il mutex viene acquisito solo per la transizione di modo e rilasciato prima
     * dell'attesa: un GoTo può durare minuti, e tenere il lock bloccherebbe stopAll(),
     * emergencyStop() e ogni altra transizione.
     */
    suspend fun moveToAwait(poiName: String): ActionResult {
        val nav = navigationController
            ?: return ActionResult.Rejected("NavigationController non disponibile")

        if (currentMode.get() == RobotMode.EMERGENCY_STOPPED)
            return ActionResult.Rejected("Robot in EMERGENCY_STOPPED")

        // Verifica del PoI PRIMA di moveTo: NavigationController.moveTo restituisce
        // FAILED sia per PoI inesistente sia per GoTo fallito, ma i due casi richiedono
        // decisioni opposte nel ciclo di replanning (Rejected = abort, Failure = replan).
        if (poiName !in nav.getPoiNames())
            return ActionResult.Rejected("PoI '$poiName' non presente nella mappa caricata")

        val acquired = modeMutex.withLock { switchModeAsync(RobotMode.NAVIGATING) }
        if (!acquired)
            return ActionResult.Rejected("Transizione a NAVIGATING rifiutata da ${currentMode.get()}")

        val status = try {
            nav.moveTo(poiName) // attesa FUORI dal lock
        } finally {
            // NonCancellable: il modo deve tornare a IDLE anche se la coroutine
            // chiamante viene cancellata, altrimenti il robot resta bloccato
            // in NAVIGATING e ogni azione successiva viene rifiutata.
            withContext(NonCancellable) {
                modeMutex.withLock {
                    if (currentMode.get() == RobotMode.NAVIGATING) setMode(RobotMode.IDLE)
                }
            }
        }

        // Se emergencyStop() è scattato durante il GoTo, il modo è EMERGENCY_STOPPED
        // e il finally non lo ha resettato: è questo che distingue una cancellazione
        // per emergenza da una cancellazione ordinaria.
        val wasEmergency = currentMode.get() == RobotMode.EMERGENCY_STOPPED

        val result = when (status) {
            NavigationController.GoToStatus.FINISHED -> ActionResult.Success()
            NavigationController.GoToStatus.CANCELLED ->
                if (wasEmergency) ActionResult.Cancelled("GoTo interrotto da emergency stop")
                else ActionResult.Cancelled("GoTo cancellato dall'esterno")

            NavigationController.GoToStatus.FAILED ->
                ActionResult.Failure("GoTo verso '$poiName' fallito dopo i retry")
        }

        Log.i(TAG, "moveToAwait('$poiName') → $result")
        return result
    }

    /**
     * Variante bloccante di FindHuman, pensata per il PlanExecutor: attende l'esito
     * della scansione a 360° e lo restituisce come [ActionResult].
     *
     * Stesso schema di moveToAwait — mutex acquisito solo per la transizione di modo,
     * rilasciato prima dell'attesa, ripreso in un finally NonCancellable per riportare
     * il modo a IDLE. A differenza di moveToAwait, FindHuman.stop() non risveglia MAI
     * da solo il listener su stop esterno (stopAll/emergencyStop) — il withTimeout è
     * la rete di sicurezza minima; da rivedere quando decidiamo la politica di
     * prelazione nel PlanExecutor.
     *
     * Nessun sayMessage qui: nel dominio la comunicazione verbale è un'azione a sé
     * (speak_*), find_human resta un'azione muta di sola percezione/stato.
     */
    suspend fun findHumanAwait(): ActionResult {
        val ctx = qiContext ?: return ActionResult.Rejected("QiContext non disponibile")

        if (currentMode.get() == RobotMode.EMERGENCY_STOPPED)
            return ActionResult.Rejected("Robot in EMERGENCY_STOPPED")

        val acquired = modeMutex.withLock { switchModeAsync(RobotMode.FIND_PERSON) }
        if (!acquired)
            return ActionResult.Rejected("Transizione a FIND_PERSON rifiutata da ${currentMode.get()}")

        var timedOut = false
        val human: Human? = try {
            try {
                withTimeout(FIND_HUMAN_AWAIT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        val fh = FindHuman(
                            qiContext = ctx,
                            movementController = movementController,
                            headController = headController
                        )
                        fh.listener = object : FindHuman.FindPersonListener {
                            override fun onPersonFound(person: Human) {
                                if (cont.isActive) cont.resume(person)
                            }

                            override fun onPersonNotFound() {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                        findHuman = fh
                        cont.invokeOnCancellation { fh.stop() }
                        fh.start()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                null
            }
        } finally {
            withContext(NonCancellable) {
                modeMutex.withLock {
                    findHuman?.stop()
                    findHuman = null
                    if (currentMode.get() == RobotMode.FIND_PERSON) setMode(RobotMode.IDLE)
                }
            }
        }

        val wasEmergency = currentMode.get() == RobotMode.EMERGENCY_STOPPED

        val result = when {
            wasEmergency -> ActionResult.Cancelled("FindHuman interrotto da emergency stop")
            timedOut -> ActionResult.Cancelled("FindHuman non ha risposto entro il timeout (probabile stop esterno)")
            human != null -> {
                lastKnownPerson = human
                ActionResult.Success(ActionPayload.HumanFound(human))
            }

            else -> ActionResult.Failure("Nessuna persona trovata")
        }

        Log.i(TAG, "findHumanAwait() → $result")
        return result
    }

    /**
     * Variante bloccante di ApproachHuman, pensata per il PlanExecutor.
     * Stesse note di findHumanAwait su timeout e assenza di sayMessage.
     */
    suspend fun approachHumanAwait(): ActionResult {
        val ctx = qiContext ?: return ActionResult.Rejected("QiContext non disponibile")

        if (currentMode.get() == RobotMode.EMERGENCY_STOPPED)
            return ActionResult.Rejected("Robot in EMERGENCY_STOPPED")

        val acquired = modeMutex.withLock { switchModeAsync(RobotMode.APPROACH_HUMAN) }
        if (!acquired)
            return ActionResult.Rejected("Transizione a APPROACH_HUMAN rifiutata da ${currentMode.get()}")

        var timedOut = false
        val outcome: ApproachOutcome? = try {
            try {
                withTimeout(APPROACH_HUMAN_AWAIT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        val ah = ApproachHuman(
                            qiContext = ctx,
                            knownHuman = lastKnownPerson,
                            listener = object : ApproachHuman.ApproachHumanListener {
                                override fun onApproachComplete(human: Human) {
                                    if (cont.isActive) cont.resume(ApproachOutcome.Complete(human))
                                }

                                override fun onNoHumanFound() {
                                    if (cont.isActive) cont.resume(ApproachOutcome.NoHuman)
                                }

                                override fun onApproachFailed() {
                                    if (cont.isActive) cont.resume(ApproachOutcome.Failed)
                                }
                            }
                        )
                        approachHuman = ah
                        cont.invokeOnCancellation { ah.stop() }
                        ah.start()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                null
            }
        } finally {
            withContext(NonCancellable) {
                modeMutex.withLock {
                    approachHuman?.stop()
                    approachHuman = null
                    if (currentMode.get() == RobotMode.APPROACH_HUMAN) setMode(RobotMode.IDLE)
                }
            }
        }

        val wasEmergency = currentMode.get() == RobotMode.EMERGENCY_STOPPED

        val result = when {
            wasEmergency -> ActionResult.Cancelled("ApproachHuman interrotto da emergency stop")
            timedOut -> ActionResult.Cancelled("ApproachHuman non ha risposto entro il timeout (probabile stop esterno)")
            outcome is ApproachOutcome.Complete -> {
                lastKnownPerson = outcome.human
                ActionResult.Success(ActionPayload.HumanFound(outcome.human))
            }

            outcome is ApproachOutcome.NoHuman -> ActionResult.Failure("Nessuna persona trovata da avvicinare")
            outcome is ApproachOutcome.Failed -> ActionResult.Failure("Approach fallito (troppi errori GoTo)")
            else -> ActionResult.Failure("Esito approach sconosciuto")
        }

        Log.i(TAG, "approachHumanAwait() → $result")
        return result
    }
    /**
     * Variante bloccante di VisualServoing, pensata per il PlanExecutor.
     * A differenza di FindHuman/ApproachHuman, servoingController è un'istanza
     * CONDIVISA con un solo listener assegnato una volta sola (nell'.also{} del
     * campo). Qui lo scambio temporaneamente e lo ripristino nel finally, per non
     * rompere il percorso vocale reattivo (startVisualServoing/stopVisualServoing)
     * che continua a usare quello originale.
     * Il reset di modo/risorse avviene UNA sola volta nel finally tramite
     * cleanStopServoing() (la stessa funzione già usata dal listener condiviso),
     * invece che duplicato in ogni branch come nella versione reattiva.
     * onObjectsSpotted inoltra a WorldStateManager la conoscenza gratuita raccolta
     * durante lo scan (gli oggetti visti finiscono in object_at sulla stanza corrente),
     * oltre a propagare verso la UI esterna.
     * Nessun sayMessage: stesso principio delle altre await, la voce è un'azione
     * a sé nel dominio.
     */
    suspend fun visualServoingAwait(label: String): ActionResult = visualServoingAwait(listOf(label))

    suspend fun visualServoingAwait(labels: List<String>): ActionResult {
        if (labels.isEmpty())
            return ActionResult.Rejected("labels non può essere vuota")

        if (currentMode.get() == RobotMode.EMERGENCY_STOPPED)
            return ActionResult.Rejected("Robot in EMERGENCY_STOPPED")

        val acquired = modeMutex.withLock { switchModeAsync(RobotMode.VISUAL_SERVOING) }
        if (!acquired)
            return ActionResult.Rejected("Transizione a VISUAL_SERVOING rifiutata da ${currentMode.get()}")

        val originalListener = servoingController.listener

        var timedOut = false
        val outcome: ServoingOutcome? = try {
            try {
                withTimeout(VISUAL_SERVOING_AWAIT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        servoingController.listener = object : VisualServoingController.VisualServoingListener {
                            override fun onObjectCentered(label: String, box: BoundingBox) {
                                if (cont.isActive) cont.resume(ServoingOutcome.Centered(label, box))
                            }
                            override fun onObjectLost(labels: List<String>) {
                                if (cont.isActive) cont.resume(ServoingOutcome.Lost(labels))
                            }
                            override fun onObjectsSpotted(spotted: List<BoundingBox>) {
                                worldStateManager?.applySpottedObjects(spotted.map { it.label })
                                managerScope.launch {
                                    withContext(Dispatchers.Main) { listener?.onObjectsSpotted(spotted) }
                                }
                            }
                        }
                        cont.invokeOnCancellation {
                            managerScope.launch { servoingController.stopTracking() }
                        }
                        holdForServoing()
                        servoingController.startTracking(cameraController, detectionController, labels)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                null
            }
        } finally {
            withContext(NonCancellable) {
                modeMutex.withLock {
                    cleanStopServoing()
                    servoingController.listener = originalListener
                }
            }
        }

        val wasEmergency = currentMode.get() == RobotMode.EMERGENCY_STOPPED

        val result = when {
            wasEmergency -> ActionResult.Cancelled("VisualServoing interrotto da emergency stop")
            timedOut     -> ActionResult.Cancelled("VisualServoing non ha risposto entro il timeout (probabile stop esterno)")
            outcome is ServoingOutcome.Centered -> ActionResult.Success(ActionPayload.ObjectFound(outcome.label, outcome.box))
            outcome is ServoingOutcome.Lost     -> ActionResult.Failure("Oggetto non trovato: ${outcome.labels.joinToString(", ")}")
            else -> ActionResult.Failure("Esito servoing sconosciuto")
        }

        Log.i(TAG, "visualServoingAwait($labels) → $result")
        return result
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
        } catch (_: Exception) {
            false
        }
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
                    listener = object : ApproachHuman.ApproachHumanListener {
                        override fun onApproachComplete(human: Human) {
                            managerScope.launch {
                                modeMutex.withLock {
                                    approachHuman = null
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage(
                                        "Eccomi, sono vicino a te.",
                                        isActionFeedback = true
                                    )
                                    withContext(Dispatchers.Main) { listener?.onCloseEnoughToHuman() }
                                }
                            }
                        }

                        override fun onNoHumanFound() {
                            managerScope.launch {
                                modeMutex.withLock {
                                    approachHuman = null
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage(
                                        "Non ho trovato nessuno a cui avvicinarmi.",
                                        isActionFeedback = true
                                    )
                                }
                            }
                        }

                        override fun onApproachFailed() {
                            managerScope.launch {
                                modeMutex.withLock {
                                    approachHuman = null
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage(
                                        "Ho avuto difficoltà ad avvicinarmi, mi dispiace.",
                                        isActionFeedback = true
                                    )
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
                    qiContext = ctx,
                    movementController = movementController,
                    headController = headController
                ).also {
                    it.listener = object : FindHuman.FindPersonListener {
                        override fun onPersonFound(human: Human) {
                            managerScope.launch {
                                modeMutex.withLock {
                                    findHuman = null
                                    lastKnownPerson = human
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage(
                                        "Eccoti!",
                                        isActionFeedback = true
                                    )
                                    withContext(Dispatchers.Main) { listener?.onPersonFound(human) }
                                }
                            }
                        }

                        override fun onPersonNotFound() {
                            managerScope.launch {
                                modeMutex.withLock {
                                    findHuman = null
                                    setMode(RobotMode.IDLE)
                                    conversationController?.sayMessage(
                                        "Mi sono guardato intorno ma non ho trovato nessuno.",
                                        isActionFeedback = true
                                    )
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
            context = context,
            qiContext = ctx,
            azureKey = azureKey,
            serverIp = serverIp,
            language = AppConfig.LANGUAGE
        ).also {
            it.onListening = { Log.d(TAG, "Conversation: listening") }
            it.onUserSpeech = { text -> Log.i(TAG, "User: $text") }
            it.onRobotSpeech = { text -> Log.i(TAG, "Pepper: $text") }
            it.isAwaitingTask = { speechBridge.isAwaitingTask }
            it.onMotionCommand = { cmd ->
                when {
                    cmd == "decline_task" -> { speechBridge.offerDecline() }
                    cmd == CMD_FOLLOW -> startFollowHumanAutoDetect()
                    cmd == CMD_STOP -> stopAll()
                    cmd == CMD_APPROACH -> startApproachHuman()
                    cmd == CMD_FIND_PERSON -> startFindPerson()
                    cmd.startsWith("track:") -> {
                        val label = cmd.removePrefix("track:")
                        if (!speechBridge.offerLabel(label)) {
                            startVisualServoing(label)
                        }
                    }

                    cmd == CMD_START_MAP -> managerScope.launch {
                        modeMutex.withLock {
                            if (mappingJob?.isActive == true) {
                                Log.w(TAG, "Mapping already running"); return@withLock
                            }
                            holdForServoing()
                            conversationController?.sayMessage(
                                "Per favore allontanati da me, sto per iniziare a mappare.",
                                isActionFeedback = true
                            )
                            mappingJob = managerScope.launch {
                                navigationController?.localizeAndMap(
                                    false,
                                    managerScope
                                )
                            }
                        }
                    }

                    cmd == CMD_STOP_MAP -> managerScope.launch {
                        navigationController?.stopCurrentAction()
                        mappingJob?.join()
                        navigationController?.persistPois()
                        navigationController?.saveMapToFile(mapFile)
                        navigationController?.saveTrajectoryToFile()
                        navigationController?.getMapBitmap()?.let { bmp ->
                            val imgFile = File(context.filesDir, "map_preview.png")
                            FileOutputStream(imgFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            Log.i(TAG, "Map preview saved to $imgFile")
                        }
                        releaseForServoing()
                        Log.i(TAG, "Map saved to $mapFile")
                    }

                    cmd == CMD_LOAD_MAP -> managerScope.launch {
                        modeMutex.withLock {
                            if (localizeJob?.isActive == true) {
                                Log.w(TAG, "Localize already running"); return@withLock
                            }
                            val loaded = navigationController?.loadMapFromFile(mapFile) ?: false
                            if (!loaded) {
                                Log.w(TAG, "Nessuna mappa trovata su file"); return@withLock
                            }
                            localizeJob = managerScope.launch {
                                val localized = navigationController?.localize() ?: false
                                if (localized) {
                                    navigationController?.loadPois()
                                    conversationController?.knownPoiNames =
                                        navigationController?.getPoiNames() ?: emptyList()
                                    Log.i(TAG, "Localizzazione riuscita, PoI caricati: ${conversationController?.knownPoiNames}")
                                    initPlanning()
                                } else {
                                    Log.w(TAG, "Localizzazione fallita")
                                }
                            }
                        }
                    }

                    cmd.startsWith("save_poi:") -> managerScope.launch {
                        val name = cmd.removePrefix("save_poi:")
                        Log.i(TAG, "Saving PoI: $name")
                        navigationController?.saveCurrentPositionAsPoi(name)
                        conversationController?.knownPoiNames =
                            navigationController?.getPoiNames() ?: emptyList()
                        Log.i(TAG, "PoI saved: $name")
                    }

                    cmd.startsWith("goto_poi:") -> managerScope.launch {
                        val name = cmd.removePrefix("goto_poi:")
                        Log.i(TAG, "GoTo requested: $name")
                        val result = moveToAwait(name)
                        Log.i(TAG, "GoTo $name result: $result")
                    }
                }
            }
            it.onUserSpeech = { text ->
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
    private sealed class ApproachOutcome {
        data class Complete(val human: Human) : ApproachOutcome()
        object NoHuman : ApproachOutcome()
        object Failed : ApproachOutcome()
    }
    private sealed class ServoingOutcome {
        data class Centered(val label: String, val box: BoundingBox) : ServoingOutcome()
        data class Lost(val labels: List<String>) : ServoingOutcome()
    }
    /*
    METODI PER IL PLANNER
     */
    /**
     * Bootstrap del planning, da chiamare DOPO loadPois(): WorldStateManager ha
     * bisogno dei nomi dei PoI, e lo stato nasce già localizzato perché la
     * localizzazione è precondizione di esistenza del piano, non un'azione.
     */
    private fun initPlanning() {
        val nav = navigationController ?: return
        val poiNames = nav.getPoiNames()

        // TEMPORANEO: valido solo con 2 PoI. Con più posizioni (es. quadrato a 4
        // stanze) le adiacenze reali vanno definite qui — un grafo completo sarebbe
        // sbagliato, permetterebbe spostamenti tra PoI non fisicamente adiacenti.
        val edges = when (poiNames.size) {
            2 -> listOf(poiNames[0] to poiNames[1])
            else -> {
                Log.e(TAG, "Topologia non definita per ${poiNames.size} PoI — planning NON inizializzato")
                return
            }
        }

        val wsm = WorldStateManager(nav, edges)
        wsm.state.localized = true

        worldStateManager = wsm
        planExecutor = PlanExecutor(
            robotManager      = this,
            worldStateManager = wsm,
            plannerClient     = PlannerClient(serverIp),
            speech            = speechBridge
        )
        Log.i(TAG, "Planning inizializzato — PoI: $poiNames, robotAt=${wsm.state.robotAt}")
    }

    fun startPlan() {
        val executor = planExecutor
        val wsm = worldStateManager
        if (executor == null || wsm == null) {
            Log.e(TAG, "Planning non inizializzato — carica prima la mappa")
            managerScope.launch {
                conversationController?.sayMessage("Devo prima caricare la mappa.", isActionFeedback = true)
            }
            return
        }
        if (planJob?.isActive == true) { Log.w(TAG, "Piano già in esecuzione"); return }

        wsm.resetForNewRun()

        planJob = managerScope.launch {
            Log.i(TAG, "=== PIANO AVVIATO ===")
            try {
                val outcome = executor.run()
                Log.i(TAG, "=== PIANO CONCLUSO: $outcome ===")
                announceOutcome(outcome)
            } catch (e: CancellationException) {
                Log.i(TAG, "=== PIANO INTERROTTO ===")
                throw e
            }
        }
    }

    /** Delega a stopAll(), che ora cancella anche il planJob. Esiste per dare un
     *  nome esplicito all'azione lato UI/adb. */
    fun stopPlan() {
        Log.i(TAG, "Interruzione del piano richiesta")
        stopAll()
    }

    private suspend fun announceOutcome(outcome: ExecutionOutcome) {
        // GoalReached tace: ha già parlato speak_report_found/not_found.
        val msg = when (outcome) {
            is ExecutionOutcome.GoalReached  -> null
            is ExecutionOutcome.NoPlan       -> "Non so come aiutarti con questo, mi dispiace."
            is ExecutionOutcome.PlannerError -> "Ho un problema tecnico, non riesco a organizzarmi."
            is ExecutionOutcome.Aborted      -> "Ho dovuto interrompere quello che stavo facendo."
        }
        msg?.let { conversationController?.sayMessage(it, isActionFeedback = true) }
    }
}