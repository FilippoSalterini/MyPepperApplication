package com.example.mypepperapplication.planning

import android.util.Log
import com.example.mypepperapplication.conversation.LabelIt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "SpeechBridge"

/**
 * Implementazione di SpeechBridge appoggiata alla pipeline vocale esistente.
 *
 * askTask() pronuncia la domanda e sospende la coroutine dell'executor su un
 * CompletableDeferred. Il loop di conversazione continua a girare normalmente
 * (è una coroutine separata): quando la persona risponde, il ramo "track:" di
 * RobotManager chiama offerLabel() e risveglia l'attesa.
 *
 * Se nessuno sta aspettando, offerLabel() ritorna false e il chiamante prosegue
 * col comportamento reattivo di sempre: i due modi convivono senza interferire.
 *
 * @param say pronuncia un messaggio. È una lambda e non un riferimento diretto a
 *        ConversationController perché quest'ultimo viene ricreato ad ogni
 *        startConversationService(): una reference catturata diventerebbe stantia.
 */
class ConversationSpeechBridge(
    private val say: suspend (String) -> Unit
) : SpeechBridge {

    companion object {
        /** La persona deve sentire la domanda, rispondere, poi servono STT +
         *  /extract_label. Stima da calibrare sul campo. */
        private const val ASK_TIMEOUT_MS = 30_000L
    }

    private val pending = AtomicReference<CompletableDeferred<String>?>(null)

    override suspend fun askTask(): String? {
        val deferred = CompletableDeferred<String>()
        pending.set(deferred)
        return try {
            say("Dimmi, cosa posso cercare per te?")
            val label = withTimeoutOrNull(ASK_TIMEOUT_MS) { deferred.await() }
            if (label == null) Log.w(TAG, "Nessuna risposta entro il timeout")
            else Log.i(TAG, "Task ricevuto dalla persona: $label")
            label
        } finally {
            pending.compareAndSet(deferred, null)
        }
    }

    // Frasi fisse e deterministiche: sono il punto in cui Pepper dichiara un fatto
    // sul mondo, ed è lì che un LLM sarebbe più pericoloso. Per variare, basta una
    // listOf(...).random() qui dentro — ma per i primi test è meglio deterministico,
    // così le frasi si correlano esattamente col log.
    override suspend fun reportFound(label: String) {
        say("Ho trovato ${LabelIt.of(label)}!")
    }

    override suspend fun reportNotFound(label: String) {
        say("Non sono riuscito a trovare ${LabelIt.of(label)}, mi dispiace.")
    }

    /**
     * Offre una label estratta dalla voce a un'eventuale askTask() in attesa.
     * @return true se consumata dal planner, false se nessuno aspettava (e quindi
     *         il chiamante deve procedere col comportamento reattivo di sempre).
     */
    fun offerLabel(label: String): Boolean {
        val deferred = pending.getAndSet(null) ?: return false
        return deferred.complete(label)
    }
}