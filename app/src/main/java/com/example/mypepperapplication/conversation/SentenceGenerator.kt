package com.example.mypepperapplication.conversation

import android.util.Log
import java.io.IOException
import android.content.Context
/*
* SentenceGenerator — generatore di frasi predefinite e "filler".
* Completamente autonomo dal server:
* - getPredefinedSentence(language, key): frasi fisse (errori server, saluti, ecc.)
* - getFillerSentence(language): frasi di riempimento mentre il server elabora ("Fammi pensare…")
* - loadFillerSentences(context): carica da asset file .txt
**/
private const val TAG = "SentenceGenerator"

class SentenceGenerator {
    // Instead of a single list for fillerSentences, we now have a map keyed by language
    private val fillerSentencesMap = mutableMapOf<String, List<String>>()

    private val mapPredefinedSentences= mapOf(
        "welcome_back" to mapOf(
            "it-IT" to "È bello rivederci! Di cosa potremmo parlare?",
            "en-US" to "Welcome back! I missed you. What would you like to talk about?"
        ),
        "server_unavailable" to mapOf(
            "it-IT" to "Il server non è disponibile in questo momento.",
            "en-US" to "The server is not available right now."
        ),
        "server_connection_error" to mapOf(
            "it-IT" to "Mi dispiace, non riesco a collegarmi al server.",
            "en-US" to "I'm sorry, I can't connect to the server."
        ),
        "goodbye" to mapOf(
            "it-IT" to "È stato bello conversare insieme. A presto!",
            "en-US" to "It was nice talking to you. See you soon!"
        ),
        "nothing_to_repeat" to mapOf(
            "it-IT" to "Mi dispiace, non ho niente da ripetere!",
            "en-US" to "I'm sorry, I have nothing to repeat!"
        ),
        "repeat_previous" to mapOf(
            "it-IT" to "Ho detto:",
            "en-US" to "I said:"
        ),
        "listening_user" to mapOf(
            "it-IT" to "Sto ascoltando...",
            "en-US" to "I'm listening..."
        ),
        "listening_robot" to mapOf(
            "it-IT" to "Sì, ti ascolto...",
            "en-US" to "Yes, I'm listening to you..."
        ),
        "prefix_repeat" to mapOf(
            "it-IT" to "Dicevo...",
            "en-US" to "I was saying..."
        ),
        "microphone_user" to mapOf(
            "it-IT" to "Microfono disattivato.",
            "en-US" to "Microphone disabled."
        ),
        "microphone_robot" to mapOf(
            "it-IT" to "Va bene, smetto di ascoltare. Toccami 4 volte la testa o dimmi: \"Hey, Pèpper\", quando vuoi parlare di nuovo con me.",
            "en-US" to "Ok, I'll stop listening. Touch my head four times or say \"Hey Pepper\" when you want to talk to me again."
        ),
        "microphone_autooff_1" to mapOf(
            "it-IT" to "Ora sono un po’ stanco, mi prendo una piccola pausa. Se vuoi continuare, dimmi: \"Hey, Pèpper\", o toccami 4 volte la testa.",
            "en-US" to "I need a short break. If you want to continue, say \"Hey Pepper\" or tap my head four times."
        ),
        "microphone_autooff_2" to mapOf(
            "it-IT" to "Faccio un breve riposo. Per riprendere, dimmi: \"Hey, Pèpper\", oppure toccami quattro volte la testa.",
            "en-US" to "Taking a quick rest. To resume, say: \"Hey Pepper\" or tap my head four times."
        ),
        "microphone_autooff_3" to mapOf(
            "it-IT" to "Mi fermo un attimo. Se vuoi continuare a chiacchierare, chiamami dicendo: \"Hey, Pèpper\", o toccami quattro volte la testa.",
            "en-US" to "I'll pause for a moment. If you'd like to keep chatting, say \"Hey Pepper\" or tap my head four times."
        ),
        "microphone_autooff_4" to mapOf(
            "it-IT" to "Anche i robot ogni tanto hanno bisogno di una pausa! Per continuare a parlare con me, dimmi: \"Hey, Pèpper\" o toccami la testa quattro volte.",
            "en-US" to "Even robots need a short break! To continue, say: \"Hey Pepper\" or tap my head four times."
        )
    )

    /*
     * Load filler sentences from two separate files:
     * - filler_sentences_it-IT.txt for Italian
     * - filler_sentences_en-US.txt for English
     * Store them in fillerSentencesMap with keys "it-IT" and "en-US".
     */
    fun loadFillerSentences(context: Context) {
        fun loadSentencesForLang(fileName: String): List<String> {
            return try {
                val inputStream = context.assets.open("filler_sentences/$fileName")
                inputStream.bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error loading filler sentences from $fileName: ${e.message}")
                listOf("...")
            }
        }

        val itSentences = loadSentencesForLang("filler_sentences_it-IT.txt")
        val enSentences = loadSentencesForLang("filler_sentences_en-US.txt")

        fillerSentencesMap["it-IT"] = itSentences
        fillerSentencesMap["en-US"] = enSentences

        Log.d(TAG, "Filler sentences loaded: it-IT=${itSentences.size}, en-US=${enSentences.size}")
    }

    fun getPredefinedSentence(language: String, key: String): String {
        val messageMap = mapPredefinedSentences[key]
        return messageMap?.get(language) ?: (messageMap?.get("it-IT") ?: "")
    }

    fun getFillerSentence(language: String): String {
        // Pick a random filler in IO context
        val fillerList = fillerSentencesMap[language] ?: fillerSentencesMap["it-IT"]!!
        return fillerList.random()
    }

    // in SentenceGenerator
    fun getRandomAutoOff(lang: String): String {
        val i = (1..4).random()
        return getPredefinedSentence(lang, "microphone_autooff_$i")
    }
}