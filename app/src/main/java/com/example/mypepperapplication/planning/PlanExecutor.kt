package com.example.mypepperapplication.planning

import android.util.Log
import com.example.mypepperapplication.core.RobotManager

private const val TAG = "PlanExecutor"

/**
 * Ponte verso la parte conversazionale. Il PlanExecutor non parla direttamente:
 * dichiara qui cosa gli serve. L'implementazione che collega ConversationController
 * (e la pipeline /extract_label) è un passo separato, ancora da scrivere.
 */
interface SpeechBridge {
    /** speak_ask_task: chiede cosa cercare e ATTENDE la risposta.
     *  Ritorna la label COCO estratta, o null se la persona non risponde. */
    suspend fun askTask(): String?
    /** speak_report_found */
    suspend fun reportFound(label: String)
    /** speak_report_not_found */
    suspend fun reportNotFound(label: String)
}

sealed class ExecutionOutcome {
    object GoalReached : ExecutionOutcome()
    data class NoPlan(val reason: String) : ExecutionOutcome()
    data class Aborted(val reason: String) : ExecutionOutcome()
    data class PlannerError(val reason: String) : ExecutionOutcome()
}

/**
 * Ciclo sense-plan-act: genera il problema dallo stato corrente, chiede un piano,
 * esegue SOLO la prima azione, aggiorna lo stato con l'esito reale, ricomincia.
 * Stessa struttura del loop in replan_loop.py.
 *
 * Eseguire una sola azione per ciclo non è un'inefficienza: è ciò che permette al
 * segnaposto 'target' di essere sostituito dalla label vera dopo speak_ask_task,
 * e al piano di adattarsi ai fallimenti reali invece di proseguire alla cieca.
 *
 * Cancellabile: è una suspend function, cancellare la coroutine chiamante propaga
 * la cancellazione nelle await di RobotManager.
 */
class PlanExecutor(
    private val robotManager: RobotManager,
    private val worldStateManager: WorldStateManager,
    private val plannerClient: PlannerClient,
    private val speech: SpeechBridge
) {
    companion object {
        private const val MAX_CYCLES = 30
    }

    private val state get() = worldStateManager.state

    suspend fun run(): ExecutionOutcome {
        var cycle = 0
        while (cycle < MAX_CYCLES) {
            cycle++

            val generator = ProblemGenerator(state)
            val goal = generator.computeGoal()
            val problem = generator.generateProblem(goal)
            Log.i(TAG, "Ciclo $cycle — goal: $goal")
            Log.i(TAG, "problem.pddl generato:\n${problem.text}")
            Log.i(TAG, "nameMap: ${problem.nameMap}")

            when (val result = plannerClient.requestPlan(problem.text)) {
                is PlanResult.Error ->
                    return ExecutionOutcome.PlannerError(result.reason)

                is PlanResult.Unsolvable ->
                    return ExecutionOutcome.NoPlan("Nessun piano per il goal corrente")

                is PlanResult.Plan -> {
                    if (result.isGoalAlreadySatisfied) {
                        Log.i(TAG, "Goal già soddisfatto — fine")
                        return ExecutionOutcome.GoalReached
                    }
                    val first = result.actions.first()
                    Log.i(TAG, "Piano di ${result.actions.size} azioni, eseguo: $first")
                    executeAction(first, problem.nameMap)?.let { return it }
                }
            }
        }
        return ExecutionOutcome.Aborted("Superato il limite di $MAX_CYCLES cicli")
    }

    /** Esegue una singola azione e aggiorna lo stato.
     *  Ritorna non-null SOLO se il ciclo deve fermarsi. */
    private suspend fun executeAction(action: String, nameMap: Map<String, String>): ExecutionOutcome? {
        val tokens = action.trim().split(Regex("\\s+"))
        val name = tokens.firstOrNull()
            ?: return ExecutionOutcome.Aborted("Azione vuota nel piano")

        // i nomi nel piano sono codificati per PDDL: qui tornano quelli veri
        fun arg(i: Int): String? = tokens.getOrNull(i)?.let { nameMap[it] ?: it }

        return when (name) {
            // go_to ?r ?from ?to
            "go_to" -> {
                val to = arg(3) ?: return ExecutionOutcome.Aborted("go_to senza destinazione: $action")
                val result = robotManager.moveToAwait(to)
                worldStateManager.applyGoTo(to, result)
                abortIfNeeded(result, action)
            }

            // find_human ?r ?h ?rm
            "find_human" -> {
                val room = arg(3) ?: state.robotAt
                val result = robotManager.findHumanAwait()
                worldStateManager.applyFindHuman(room, result)
                abortIfNeeded(result, action)
            }

            // approach_human ?r ?h ?rm
            "approach_human" -> {
                val result = robotManager.approachHumanAwait()
                worldStateManager.applyApproachHuman(result)
                abortIfNeeded(result, action)
            }

            // search_object_* ?r ?o ?rm
            "search_object_blind", "search_object_known" -> {
                val obj = arg(2) ?: return ExecutionOutcome.Aborted("search senza oggetto: $action")
                val room = arg(3) ?: state.robotAt
                val result = robotManager.visualServoingAwait(obj)
                worldStateManager.applySearchObject(room, obj, result)
                abortIfNeeded(result, action)
            }

            "speak_ask_task" -> {
                val label = speech.askTask()
                    ?: return ExecutionOutcome.Aborted("La persona non ha indicato cosa cercare")
                worldStateManager.setTask(label)
                null
            }

            "speak_report_found" -> {
                val label = state.bound
                    ?: return ExecutionOutcome.Aborted("report_found senza oggetto legato")
                speech.reportFound(label)
                worldStateManager.applyReport(label)
                null
            }

            "speak_report_not_found" -> {
                val label = state.bound
                    ?: return ExecutionOutcome.Aborted("report_not_found senza oggetto legato")
                speech.reportNotFound(label)
                worldStateManager.applyReport(label)
                null
            }

            // La localizzazione è bootstrap, non un'azione di piano: se il planner la
            // emette significa che lo stato dice localized=false, cioè che il bootstrap
            // non è stato fatto. Meglio fermarsi che fingere di averla eseguita.
            "localize" ->
                ExecutionOutcome.Aborted("Il planner ha richiesto localize: bootstrap non completato")

            else -> ExecutionOutcome.Aborted("Azione sconosciuta nel piano: $action")
        }
    }

    private fun abortIfNeeded(result: ActionResult, action: String): ExecutionOutcome? =
        if (result.shouldAbort) {
            Log.w(TAG, "Azione '$action' → $result: interrompo il ciclo")
            ExecutionOutcome.Aborted("$action → $result")
        } else {
            null   // Success o Failure: si replanifica al ciclo successivo
        }
}