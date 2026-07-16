package com.example.mypepperapplication.conversation

import android.util.Log
import com.example.mypepperapplication.storage.FileStorageManager
import kotlin.random.Random
/*
* ConversationState — il wrapper che tiene insieme -> [speakerInfo, dialogueState e dialogueStatistics].
* I metodi importanti:
*
* - loadFromFile() / writeToFile(): persistenza su disco tra una sessione e l'altra
* - getReplySentence(personName): restituisce la frase principale che il robot deve dire
* - getLastContinuationSentence(personName): frase di continuazione (es. "Posso aiutarti con altro?")
* - replaceSpeakerTags(): sostituisce i placeholder $prevspk/$desspk con i nomi reali
**/
private const val TAG = "ConversationState"

class ConversationState(
    private var fileStorageManager: FileStorageManager,
    private var previousSentence: String
) {
    var dialogueState: DialogueState = DialogueState()
    var speakersInfo = SpeakersInfo()
    var dialogueStatistics = DialogueStatistics()
    var planSentence: String? = null
    var plan: String? = null

    // TODO: these are not reinitialized with a new hubRequest
    // check if they are not already inside dialogueState
    var prevTurnLastSpeaker: String = ""
    var prevSpeakerTopic: Int? = null

    /**
     * Loads conversation state from files.
     * Returns true if everything was loaded correctly, false otherwise.
     *
     * This method is intentionally defensive:
     * - no !! operator
     * - no crash if one file is missing/corrupted
     * - caller can decide how to recover
     */
    fun loadFromFile(): Boolean {
        return try {
            Log.i(TAG, "Loading conversation state elements")

            val loadedDialogueState = fileStorageManager.readFromFile(DialogueState::class.java)
            if (loadedDialogueState == null) {
                Log.e(TAG, "dialogueState is null while loading from file")
                return false
            }

            val loadedSpeakersInfo = fileStorageManager.readFromFile(SpeakersInfo::class.java)
            if (loadedSpeakersInfo == null) {
                Log.e(TAG, "speakersInfo is null while loading from file")
                return false
            }

            val loadedDialogueStatistics =
                fileStorageManager.readFromFile(DialogueStatistics::class.java)
            if (loadedDialogueStatistics == null) {
                Log.e(TAG, "dialogueStatistics is null while loading from file")
                return false
            }

            dialogueState = loadedDialogueState
            speakersInfo = loadedSpeakersInfo
            dialogueStatistics = loadedDialogueStatistics

            Log.i(TAG, "dialogueState = $dialogueState")
            Log.i(TAG, "speakersInfo = $speakersInfo")
            Log.i(TAG, "dialogueStatistics = $dialogueStatistics")

            dialogueState.conversationHistory.add(
                mapOf("role" to "assistant", "content" to previousSentence)
            )
            dialogueState.prevDialogueSentence = listOf(listOf("s", previousSentence))

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error while loading conversation state from file", e)
            false
        }
    }

    fun writeToFile() {
        Log.i(TAG, "Writing conversation state elements")
        fileStorageManager.writeToFile(dialogueState)
        Log.i(TAG, "dialogueState = $dialogueState")
        fileStorageManager.writeToFile(speakersInfo)
        Log.i(TAG, "speakersInfo = $speakersInfo")
        fileStorageManager.writeToFile(dialogueStatistics)
        Log.i(TAG, "dialogueStatistics = $dialogueStatistics")
    }

    fun copy(
        fileStorageManager: FileStorageManager = this.fileStorageManager,
        previousSentence: String = this.previousSentence,
        dialogueState: DialogueState = this.dialogueState.copy(),
        speakersInfo: SpeakersInfo = this.speakersInfo.copy(),
        dialogueStatistics: DialogueStatistics = this.dialogueStatistics.copy(),
        plan: String? = this.plan,
        planSentence: String? = this.planSentence
    ): ConversationState {
        val newConversationState = ConversationState(fileStorageManager, previousSentence)
        newConversationState.dialogueState = dialogueState
        newConversationState.speakersInfo = speakersInfo
        newConversationState.dialogueStatistics = dialogueStatistics
        newConversationState.plan = plan
        newConversationState.planSentence = planSentence
        return newConversationState
    }

    private fun replaceSpeakerTags(
        sentence: String,
        prevSpeakerName: String?,
        destSpeakerName: String?
    ): String {
        var result = sentence

        val patternDesspk = $$"\\s*,?\\s*\\$desspk\\s*,?\\s*".toRegex()
        val patternPrevspk = $$"\\s*,?\\s*\\$prevspk\\s*,?\\s*".toRegex()

        val usePrevSpeakerName = prevSpeakerName != null && Random.nextInt(100) < 10
        val useDestSpeakerName = destSpeakerName != null && Random.nextInt(100) < 10

        result = if (usePrevSpeakerName) {
            result.replace(patternPrevspk, " $prevSpeakerName ")
        } else {
            result.replace(patternPrevspk, " ")
        }

        result = if (useDestSpeakerName) {
            result.replace(patternDesspk, " $destSpeakerName ")
        } else {
            result.replace(patternDesspk, " ")
        }

        return result
    }

    fun getLastContinuationSentence(personName: String): String {
        var lastContinuationSentence = dialogueState.dialogueSentence.lastOrNull()?.getOrNull(1) ?: ""
        val destSpeakerName = if (
            personName.isEmpty() ||
            personName == "Utente" ||
            personName == "User"
        ) {
            null
        } else {
            personName
        }
        lastContinuationSentence =
            replaceSpeakerTags(lastContinuationSentence, null, destSpeakerName)
        return lastContinuationSentence
    }

    fun getPreviousContinuationSentence(personName: String): String {
        var lastContinuationSentence =
            dialogueState.prevDialogueSentence.lastOrNull()?.getOrNull(1) ?: ""
        val destSpeakerName = if (
            personName.isEmpty() ||
            personName == "Utente" ||
            personName == "User"
        ) {
            null
        } else {
            personName
        }
        lastContinuationSentence =
            replaceSpeakerTags(lastContinuationSentence, null, destSpeakerName)
        return lastContinuationSentence
    }

    fun getReplySentence(personName: String): String {
        val replySentence = if (!plan.isNullOrEmpty()) {
            planSentence.toString()
        } else {
            dialogueState.dialogueSentence.getOrNull(0)?.getOrNull(1).orEmpty()
        }

        Log.i(TAG, "Reply sentence: $replySentence")

        val prevSpeakerName = personName.ifEmpty { null }

        return if (replySentence.isNotEmpty()) {
            replaceSpeakerTags(replySentence, prevSpeakerName, null)
        } else {
            ""
        }
    }
}