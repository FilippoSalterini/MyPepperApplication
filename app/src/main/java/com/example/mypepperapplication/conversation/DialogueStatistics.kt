package com.example.mypepperapplication.conversation

import org.json.JSONObject
/*
* DialogueStatistics — statistiche sui turni: chi ha parlato, quante volte, probabilità a priori dei topic.
* Serve al server per capire il "ritmo" della conversazione. [ non fondamentale ]
**/
data class DialogueStatistics(
    var mappingIndexSpeaker: MutableList<String> = mutableListOf(),
    var sameTurn: MutableList<MutableList<Int>> = mutableListOf(mutableListOf(0)),
    var successiveTurn: MutableList<MutableList<Int>> = mutableListOf(mutableListOf(0)),
    var averageTopicDistance: MutableList<MutableList<Double>> = mutableListOf(mutableListOf(0.0)),
    var speakersTurns: MutableList<Int> = mutableListOf(0),
    var aPrioriProb: MutableList<Double> = mutableListOf(0.0),
    var movingWindow: MutableList<Any> = mutableListOf(),
    var latestTurns: MutableList<Any> = mutableListOf()
) {
    constructor(profileId: String) : this() {
        mappingIndexSpeaker = mutableListOf(profileId)
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "mapping_index_speaker" to mappingIndexSpeaker,
            "same_turn" to sameTurn,
            "successive_turn" to successiveTurn,
            "average_topic_distance" to averageTopicDistance,
            "speakers_turns" to speakersTurns,
            "a_priori_prob" to aPrioriProb,
            "moving_window" to movingWindow,
            "latest_turns" to latestTurns
        )
    }

    fun updateFromJson(json: JSONObject): DialogueStatistics {
        this.mappingIndexSpeaker = json.optJSONArray("mappingIndexSpeaker")?.let { jsonArray ->
            (0 until jsonArray.length()).map { jsonArray.getString(it) }.toMutableList()
        } ?: this.mappingIndexSpeaker

        this.sameTurn = json.optJSONArray("sameTurn")?.let { jsonArray ->
            (0 until jsonArray.length()).map { idx ->
                val innerArray = jsonArray.getJSONArray(idx)
                (0 until innerArray.length()).map { innerArray.getInt(it) }.toMutableList()
            }.toMutableList()
        } ?: this.sameTurn

        this.successiveTurn = json.optJSONArray("successiveTurn")?.let { jsonArray ->
            (0 until jsonArray.length()).map { idx ->
                val innerArray = jsonArray.getJSONArray(idx)
                (0 until innerArray.length()).map { innerArray.getInt(it) }.toMutableList()
            }.toMutableList()
        } ?: this.successiveTurn

        this.averageTopicDistance = json.optJSONArray("averageTopicDistance")?.let { jsonArray ->
            (0 until jsonArray.length()).map { idx ->
                val innerArray = jsonArray.getJSONArray(idx)
                (0 until innerArray.length()).map { innerArray.getDouble(it) }.toMutableList()
            }.toMutableList()
        } ?: this.averageTopicDistance

        this.speakersTurns = json.optJSONArray("speakersTurns")?.let { jsonArray ->
            (0 until jsonArray.length()).map { jsonArray.getInt(it) }.toMutableList()
        } ?: this.speakersTurns

        this.aPrioriProb = json.optJSONArray("aPrioriProb")?.let { jsonArray ->
            (0 until jsonArray.length()).map { jsonArray.getDouble(it) }.toMutableList()
        } ?: this.aPrioriProb

        // MovingWindow and LatestTurns are more complex structures, keeping them as MutableList<Any>
        this.movingWindow = json.optJSONArray("movingWindow")?.let { jsonArray ->
            (0 until jsonArray.length()).map { jsonArray.get(it) }.toMutableList()
        } ?: this.movingWindow

        this.latestTurns = json.optJSONArray("latestTurns")?.let { jsonArray ->
            (0 until jsonArray.length()).map { jsonArray.get(it) }.toMutableList()
        } ?: this.latestTurns

        return this
    }

    override fun toString(): String {
        return """
            DialogueStatistics(
                mappingIndexSpeaker=${mappingIndexSpeaker.joinToString()},
                sameTurn=${sameTurn.joinToString { it.joinToString(prefix = "[", postfix = "]") }},
                successiveTurn=${successiveTurn.joinToString { it.joinToString(prefix = "[", postfix = "]") }},
                averageTopicDistance=${averageTopicDistance.joinToString { it.joinToString(prefix = "[", postfix = "]") }},
                speakersTurns=${speakersTurns.joinToString()},
                aPrioriProb=${aPrioriProb.joinToString()},
                movingWindow=${movingWindow.joinToString()},
                latestTurns=${latestTurns.joinToString()}
            )
        """.trimIndent()
    }
}