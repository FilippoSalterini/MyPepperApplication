package com.example.mypepperapplication.planning
import com.aldebaran.qi.sdk.`object`.human.Human
import com.example.mypepperapplication.vision.BoundingBox

/**
 * Dati opzionali restituiti da un'azione completata con successo.
 *
 * Il PlanExecutor NON deve decidere l'avanzamento in base a questo payload: lo instrada
 * al WorldStateManager, unico responsabile di tradurre i dati percettivi in predicati PDDL.
 */
sealed interface ActionPayload {
    data class ObjectFound(val label: String, val box: BoundingBox) : ActionPayload
    data class HumanFound(val human: Human) : ActionPayload
}
/**
 * Esito dell'esecuzione di una singola azione ground del piano.
 *
 * I quattro casi non sono ridondanti: ciascuno corrisponde a una decisione diversa
 * del ciclo plan-execute-monitor-replan.
 *
 * - [Success]   → lo stato del mondo è cambiato come previsto: l'executor avanza al passo successivo.
 * - [Failure]   → l'azione è terminata senza raggiungere il proprio effetto, ma il mondo può essere
 *                 cambiato (il robot si è mosso, ha guardato altrove, l'oggetto non c'era).
 *                 L'executor aggiorna lo stato e RIPIANIFICA.
 * - [Cancelled] → interruzione esterna (emergency stop, comando vocale, cambio di modo).
 *                 Non è il mondo ad aver resistito: è il sistema o l'utente ad aver deciso.
 *                 L'executor ABORTISCE il piano, NON ripianifica.
 * - [Rejected]  → l'azione non è nemmeno partita (qiContext null, robot in EMERGENCY_STOPPED,
 *                 PoI non caricato, switchModeAsync fallito). Il mondo NON è stato toccato,
 *                 quindi ripianificare produrrebbe esattamente lo stesso piano: loop infinito.
 *                 L'executor ABORTISCE e segnala l'errore.
 *
 * La distinzione [Failure] / [Rejected] è quella che evita il caso più insidioso in laboratorio:
 * il robot che ricicla all'infinito lo stesso piano perché la precondizione che blocca l'azione
 * non è rappresentata nello stato PDDL.
 */
    sealed class ActionResult {

        /** Motivo leggibile dell'esito. Sempre null per [Success], sempre valorizzato altrove. */
        abstract val reason: String?

        data class Success(val payload: ActionPayload? = null) : ActionResult() {
            override val reason: String? = null
        }

        data class Failure(override val reason: String) : ActionResult()

        data class Cancelled(override val reason: String) : ActionResult()

        data class Rejected(override val reason: String) : ActionResult()

        // ── Predicati di comodo per il PlanExecutor ──────────────────────────

        /** L'azione ha raggiunto il proprio effetto: si può avanzare nel piano. */
        val isSuccess: Boolean
            get() = this is Success

        /** Il mondo può essere cambiato in modo imprevisto: serve una nuova pianificazione. */
        val shouldReplan: Boolean
            get() = this is Failure

        /** Il piano va abbandonato senza ripianificare. */
        val shouldAbort: Boolean
            get() = this is Cancelled || this is Rejected

        /** Etichetta compatta per i log di esecuzione (utile per le metriche del Cap. 4). */
        val logTag: String
            get() = when (this) {
                is Success -> "SUCCESS"
                is Failure -> "FAILURE"
                is Cancelled -> "CANCELLED"
                is Rejected -> "REJECTED"
            }

        override fun toString(): String =
            if (reason == null) logTag else "$logTag($reason)"
    }