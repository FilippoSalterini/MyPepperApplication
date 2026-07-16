package com.example.mypepperapplication.conversation

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.collections.get
/*
* DialogueState.kt - il cuore dello stato conversazionale. Contiene:
*
* - dialogueSentence / prevDialogueSentence: lista di coppie [tipo, frase] — la risposta che il robot deve dire,
*   e quella del turno precedente
* - conversationHistory: gli ultimi 5 turni della conversazione (formato OpenAI-style: role/content) —
*   serve per dare contesto al server LLM
* - topic, prevTopic: indice del topic corrente (numerico, mappato lato server)
* - addressedSpeaker: chi sta parlando col robot
* - ongoingConversation, formalLanguage, bool, flags, familiarities: metadati usati
*   dal planner conversazionale del server
* I metodi chiave sono updateFromJson() (aggiorna lo stato dalla risposta del server)
* e toMap() (serializza per mandarlo al server).
**/
private const val TAG = "DialogueState"

fun jsonArrayToList(jsonArray: JSONArray): List<Int> {
    val list = mutableListOf<Int>()
    for (i in 0 until jsonArray.length()) {
        list.add(jsonArray.optInt(i)) // optInt to avoid exceptions
    }
    return list
}

data class DialogueState(
    var dialogueSentence: List<List<String>> = listOf(),
    var prevDialogueSentence: List<List<String>> = listOf(),
    var addressedSpeaker: String? = null,
    var topic: Int? = null,
    var prevTopic: Int? = null,
    var sentenceType: String? = null,
    var pattern: List<String>? = null,
    var bool: Boolean? = null,
    var familiarities: Map<String, Any>? = null,
    var flags: Map<String, List<Int>>? = null,
    var addressedCommunity: String? = null,
    var conversationHistory: MutableList<Map<String, String>> = mutableListOf(),
    var ongoingConversation: Boolean = true,
    var formalLanguage: Boolean = true,

    ) {
    // Constructor using snake_case from server-side
    constructor(dialogueState: Map<String, Any?>) : this(
        dialogueSentence = parseDialogueSentence(dialogueState["dialogue_sentence"]),
        prevDialogueSentence = parseDialogueSentence(dialogueState["prev_dialogue_sentence"]),
        addressedSpeaker = dialogueState["addressed_speaker"] as? String,
        topic = (dialogueState["topic"] as? Number)?.toInt(),  // Adjusted
        prevTopic = (dialogueState["prev_topic"] as? Number)?.toInt(),
        sentenceType = dialogueState["sentence_type"] as? String,
        pattern = (dialogueState["pattern"] as? List<*>)?.filterIsInstance<String>() ?: listOf(),
        bool = dialogueState["bool"] as? Boolean,
        familiarities = (dialogueState["familiarities"] as? Map<*, *>)?.filterKeys { it is String }?.filterValues { it is Any }?.mapKeys { it.key as String }?.mapValues { it.value as Any } ?: mapOf(),
        flags = (dialogueState["flags"] as? Map<*, *>)?.filterKeys { it is String }?.filterValues { it is Any }?.mapKeys { it.key as String }?.mapValues { it.value as Any } as Map<String, List<Int>>?
            ?: mapOf(),
        addressedCommunity = dialogueState["addressed_community"] as? String,
        conversationHistory = (dialogueState["conversation_history"] as? List<*>)?.filterIsInstance<Map<String, String>>()?.toMutableList() ?: mutableListOf(),
        ongoingConversation = dialogueState["ongoing_conversation"] as Boolean,
        formalLanguage = dialogueState["formal_language"] as Boolean
    )
    companion object {
        fun parseDialogueSentence(data: Any?): List<List<String>> {
            return (data as? List<*>)?.mapNotNull {
                when (it) {
                    is List<*> -> {
                        it.filterIsInstance<String>()
                    }
                    is Map<*, *> -> {
                        val first = it["first"]?.toString()
                        val second = it["second"]?.toString()
                        if (first != null && second != null) listOf(first, second) else null
                    }
                    else -> null
                }
            } ?: listOf()
        }
    }

    fun printDebug() {
        Log.d(TAG, "familiarities: $familiarities")
        Log.d(TAG, "flags: $flags")
    }

    // Serializing camelCase fields to snake_case for server-side
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "dialogue_sentence" to dialogueSentence,
            "prev_dialogue_sentence" to prevDialogueSentence,
            "addressed_speaker" to addressedSpeaker,
            "topic" to topic,
            "prev_topic" to prevTopic,
            "sentence_type" to sentenceType,
            "pattern" to pattern,
            "bool" to bool,
            "familiarities" to familiarities,
            "flags" to flags,
            "addressed_community" to addressedCommunity,
            "conversation_history" to conversationHistory,
            "ongoing_conversation" to ongoingConversation,
            "formal_language" to formalLanguage,
        )
    }

    fun updateConversation(role: String, sentence: String) {
        conversationHistory.add(
            mapOf("role" to role, "content" to sentence)
        )

        if (conversationHistory.size > 5) {
            conversationHistory = conversationHistory.takeLast(5).toMutableList()
        }

        Log.d(TAG, "Current conversationHistory: $conversationHistory")
    }

    fun resetConversation() {
        conversationHistory = mutableListOf()
    }

    // Update dialogue state from JSON (handles snake_case from the server)
    fun updateFromJson(json: JSONObject): DialogueState {
        Log.d(TAG, "Received JSON to update DialogueState: $json")

        // Copy current dialogueSentence into prevDialogueSentence before updating
        this.prevDialogueSentence = this.dialogueSentence

        // Update dialogueSentence
        this.dialogueSentence = json.optJSONArray("dialogue_sentence")?.let { jsonArray ->
            (0 until jsonArray.length()).mapNotNull { idx ->
                val innerArray = jsonArray.optJSONArray(idx)
                if (innerArray != null) {
                    (0 until innerArray.length()).map { innerArray.optString(it, "") }
                } else {
                    null
                }
            }
        } ?: this.dialogueSentence

        // Update addressedSpeaker
        this.addressedSpeaker = this.addressedSpeaker?.let {
            json.optString("addressed_speaker",
                it
            )
        }

        // Update topic
        this.topic = if (json.has("topic")) {
            json.optInt("topic", this.topic ?: 0)
        } else {
            this.topic
        }

        // Update prevTopic
        this.prevTopic = if (json.has("prev_topic")) {
            json.optInt("prev_topic", this.prevTopic ?: 0)
        } else {
            this.prevTopic
        }

        // Update sentenceType
        this.sentenceType = this.sentenceType?.let { json.optString("sentence_type", it) }

        // Update pattern
        this.pattern = json.optJSONArray("pattern")?.let { jsonArray ->
            (0 until jsonArray.length()).map { jsonArray.getString(it) }
        } ?: this.pattern

        // Update bool
        this.bool = if (json.has("bool")) json.optBoolean("bool") else this.bool

        // Update familiarities
        Log.d(TAG, "familiarities before updateFromJSON: $familiarities")
        this.familiarities = json.optJSONObject("familiarities")?.let { familiaritiesJson ->
            familiaritiesJson.keys().asSequence().associateWith { familiaritiesJson[it] as Any }
        } ?: this.familiarities
        Log.d(TAG, "familiarities after updateFromJSON: $familiarities")

        Log.d(TAG, "Flags before updateFromJSON: $flags")
        // Update flags
        /*this.flags = json.optJSONObject("flags")?.let { flagsJson ->
            flagsJson.keys().asSequence().associateWith { flagsJson[it] as List<Int> }
        } ?: this.flags*/
        this.flags = json.optJSONObject("flags")?.let { flagsJson ->
            flagsJson.keys().asSequence().associateWith { key ->
                val jsonArray = flagsJson.optJSONArray(key)
                jsonArray?.let { jsonArrayToList(it) } ?: emptyList()
            }
        }
        Log.d(TAG, "Flags after updateFromJSON: $flags")

        // Update addressedCommunity
        this.addressedCommunity =
            this.addressedCommunity?.let { json.optString("addressed_community", it) }

        // Update ongoingConversation
        this.ongoingConversation = if (json.has("ongoing_conversation")) json.optBoolean("ongoing_conversation") else this.ongoingConversation

        // Update formalLanguage
        this.formalLanguage = if (json.has("formal_language")) json.optBoolean("formal_language") else this.formalLanguage

        return this
    }
}