package com.example.mypepperapplication.planning

import com.example.mypepperapplication.navigation.NavigationController
import android.util.Log
/**
 * Stato del mondo secondo la CREDENZA di Pepper — porting Kotlin di WorldState
 * in replan_loop.py, adattato al dominio reale pepper_domain (non esteso):
 * niente entrance/owner/task irrompenti, has_task e searched_human sono flag
 * globali senza parametro umano.
 */
class WorldState(
    val rooms: List<String>,
    val edges: List<Pair<String, String>>,
    var robotAt: String,
    val humans: MutableSet<String> = mutableSetOf()
) {
    var localized: Boolean = false
    val humanAt = mutableMapOf<String, String>()            // umano -> stanza (assente = ignoto)
    val nearHuman = mutableSetOf<String>()
    val objectAt = mutableMapOf<String, MutableSet<String>>()
    val objectFound = mutableSetOf<String>()
    val searchedRooms = mutableSetOf<String>()               // searched_human ?rm — nessun parametro umano
    val searched = mutableSetOf<Pair<String, String>>()      // (stanza, oggetto) — per all_searched
    var hasTask: Boolean = false                              // has_task ?r — flag globale
    val informed = mutableSetOf<Pair<String, String>>()      // (umano, oggetto)
    var bound: String? = null                                  // label YOLO legata al 'target' richiesto
    var taskDeclined: Boolean = false
    /** Tutti gli oggetti di cui esiste una qualche conoscenza — usato dal futuro ProblemGenerator. */
    val knownObjects: Set<String>
        get() = objectAt.keys + objectFound + searched.map { it.second }

    /** true se o è stato cercato in TUTTE le stanze senza esito — usato dal futuro ProblemGenerator
     *  per decidere se il planner può scegliere speak_report_not_found. */
    fun isAllSearched(obj: String): Boolean =
        obj !in objectFound && rooms.all { room -> (room to obj) in searched }
}

/**
 * Traduce l'esito reale delle azioni (ActionResult da RobotManager) in modifiche
 * allo stato di credenza. A differenza di execute() in replan_loop.py, qui non
 * esiste una "verità nascosta" da consultare: applichiamo solo quello che
 * l'ActionResult ci dice essere successo davvero.
 *
 * PLACEHOLDER: nessun sistema di identificazione persistente delle persone
 * esiste ancora lato Android (Human di QiSDK è un riferimento anonimo per
 * singola detection). Finché non serve distinguere più umani, si usa un nome
 * fisso — stesso ruolo di DEFAULT_OWNER in replan_loop.py.
 */
class WorldStateManager(
    navigationController: NavigationController,
    edges: List<Pair<String, String>>
) {
    companion object {
        const val DEFAULT_HUMAN = "user1"
        private const val TAG = "WorldStateManager"
    }
    val state: WorldState

    init {
        val poiNames = navigationController.getPoiNames()
        require(poiNames.isNotEmpty()) {
            "Nessun PoI caricato — WorldStateManager richiede una mappa già localizzata"
        }
        state = WorldState(
            rooms   = poiNames,
            edges   = edges,
            robotAt = poiNames.first()   // convenzione: il primo PoI salvato è la posizione di "nascita"
        )
        state.humans.add(DEFAULT_HUMAN)
    }
    /**
     * TODO: valuta far tornare pepper sempre alla posizione dove nasce
     * Azzera lo stato del compito per una nuova esecuzione, MANTENENDO robotAt:
     * dopo un piano Pepper è fisicamente dove è arrivato, e resettarlo farebbe
     * partire il primo go_to da una posizione sbagliata.
     * humanAt/nearHuman si azzerano perché la persona nel frattempo si è mossa:
     * lo stato riparte da human_unknown, che forza un find_human onesto.
     */
    fun resetForNewRun() {
        state.bound = null
        state.hasTask = false
        state.informed.clear()
        state.searchedRooms.clear()
        state.searched.clear()
        state.objectAt.clear()
        state.objectFound.clear()
        state.humanAt.clear()
        state.nearHuman.clear()
        state.taskDeclined = false
        Log.i(TAG, "Stato azzerato per una nuova esecuzione (robotAt=${state.robotAt})")
    }
    fun applyGoTo(poiName: String, result: ActionResult) {
        if (result is ActionResult.Success) {
            state.robotAt = poiName
            state.nearHuman.clear()
        }
        // Failure/Cancelled/Rejected: nessuna modifica, il robot non si è mosso davvero
    }

    fun applyReport(label: String, human: String = DEFAULT_HUMAN) {
        state.informed.add(human to label)
        // NB: pepper_domain non azzera has_task su report (a differenza del dominio
        // esteso) — non lo tocchiamo qui, per restare fedeli al modello.
    }
    fun applyFindHuman(room: String, result: ActionResult, human: String = DEFAULT_HUMAN) {
        when (result) {
            is ActionResult.Success -> {
                state.humanAt[human] = room
                state.humans.add(human)
            }
            is ActionResult.Failure -> state.searchedRooms.add(room)
            else -> { /* Cancelled/Rejected: azione mai completata, nessuna modifica */ }
        }
    }

    fun applyApproachHuman(result: ActionResult, human: String = DEFAULT_HUMAN) {
        when (result) {
            is ActionResult.Success -> state.nearHuman.add(human)
            is ActionResult.Failure -> state.humanAt.remove(human)   // solo la credenza cade, torna sconosciuto
            else -> { }
        }
    }

    fun applySearchObject(room: String, objectLabel: String, result: ActionResult) {
        when (result) {
            is ActionResult.Success -> {
                state.objectAt.getOrPut(objectLabel) { mutableSetOf() }.add(room)
                state.objectFound.add(objectLabel)
                state.searched.add(room to objectLabel)
                state.nearHuman.clear()
            }
            is ActionResult.Failure -> {
                state.searched.add(room to objectLabel)
                state.nearHuman.clear()
            }
            else -> { }
        }
    }

    /** Ponte provvisorio verso ConversationController/extract_label — quando "cerca X" viene
     *  riconosciuto e la label estratta, questo segna il task come attivo per il planner.
     *  Da ricollegare per bene quando ConversationController parlerà con PlanExecutor invece
     *  di chiamare startVisualServoing() direttamente come fa oggi. */
    fun setTask(label: String) {
        state.bound = label
        state.hasTask = true
    }
    fun applyDecline() {
        state.taskDeclined = true
    }
}