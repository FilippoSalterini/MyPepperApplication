package com.example.mypepperapplication.storage

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.example.mypepperapplication.conversation.DialogueState
import com.example.mypepperapplication.conversation.DialogueStatistics
import com.example.mypepperapplication.conversation.SpeakersInfo
import java.io.File
/*
* FileStorageManager — serializzazione/deserializzazione JSON su file tramite Gson.
* Ogni classe ha il suo file (dialogue_state.json, ecc.).
**/
private const val TAG = "FileStorageManager"

class FileStorageManager(
    var dialogueStateFile: File? = null,
    val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
    var speakersInfoFile: File? = null,
    var dialogueStatisticsFile: File? = null
) {
    // Secondary constructor that takes Context and filesDir
    constructor(filesDir: File) : this(
        dialogueStateFile = File(filesDir, "dialogue_state.json"),
        speakersInfoFile = File(filesDir, "speakers_info.json"),
        dialogueStatisticsFile = File(filesDir, "dialogue_statistics.json")
    ) {
        Log.d(TAG, "FileStorageManager Constructor with filesDir=$filesDir")
    }

    // Write data to file
    inline fun <reified T> writeToFile(data: T) {
        val jsonData = gson.toJson(data)
        when {
            isDialogueState(data) -> dialogueStateFile?.writeText(jsonData)
            isSpeakersInfo(data) -> speakersInfoFile?.writeText(jsonData)
            isDialogueStatistics(data) -> dialogueStatisticsFile?.writeText(jsonData)
        }
    }

    // Read data from file
    fun <T> readFromFile(classOfT: Class<T>): T? {
        Log.d(TAG, "Reading from file")
        return when {
            isDialogueState(classOfT) -> {
                dialogueStateFile?.let {
                    val jsonData = it.readText()
                    gson.fromJson(jsonData, classOfT)
                }
            }
            isSpeakersInfo(classOfT) -> {
                speakersInfoFile?.let {
                    val jsonData = it.readText()
                    gson.fromJson(jsonData, classOfT)
                }
            }
            isDialogueStatistics(classOfT) -> {
                dialogueStatisticsFile?.let {
                    val jsonData = it.readText()
                    gson.fromJson(jsonData, classOfT)
                }
            }
            else -> {
                Log.d(TAG, "Class type not recognized")
                null
            }
        }
    }

    // Check if files exist
    fun filesExist(): Boolean {
        return (dialogueStateFile?.exists() == true) &&
                (speakersInfoFile?.exists() == true) &&
                (dialogueStatisticsFile?.exists() == true)
    }
}

// Helper functions for type checking
inline fun <reified T> isDialogueStatistics(data: T): Boolean {
    return data is DialogueStatistics
}

inline fun <reified T> isSpeakersInfo(data: T): Boolean {
    return data is SpeakersInfo
}

inline fun <reified T> isDialogueState(data: T): Boolean {
    return data is DialogueState
}

// Type checking helper function for DialogueState
fun <T> isDialogueState(classOfT: Class<T>): Boolean {
    return classOfT == DialogueState::class.java
}

// Similar helper functions for other classes
fun <T> isSpeakersInfo(classOfT: Class<T>): Boolean {
    return classOfT == SpeakersInfo::class.java
}

fun <T> isDialogueStatistics(classOfT: Class<T>): Boolean {
    return classOfT == DialogueStatistics::class.java
}