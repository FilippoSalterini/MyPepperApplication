package com.example.mypepperapplication.planning

private const val DOMAIN_NAME = "pepper_domain"


// PDDL non ammette spazi nei nomi

private fun String.pddl(): String =
    trim()
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^A-Za-z0-9_-]"), "")
        .lowercase()

/** Problema PDDL generato + tabella per ritradurre i nomi del piano in nomi reali. */
data class GeneratedProblem(
    val text: String,
    val nameMap: Map<String, String>   // "posizione_uno" -> "posizione uno"
)

class ProblemGenerator(private val state: WorldState) {

    fun computeGoal(human: String = WorldStateManager.DEFAULT_HUMAN): String {
        val target = (state.bound ?: "target").pddl()
        return "(informed ${human.pddl()} $target)"
    }

    fun generateProblem(goal: String): GeneratedProblem {
        val taskName = state.bound ?: "target"
        val others = state.knownObjects.filter { it != state.bound }.sorted()
        val humans = state.humans.sorted()
        val nameMap = buildNameMap(state.rooms + others + humans + listOf(taskName))

        val init = mutableListOf<String>()
        if (state.localized) init.add("(localized pepper)")
        init.add("(robot_at pepper ${state.robotAt.pddl()})")
        for ((a, b) in state.edges) {
            init.add("(connected ${a.pddl()} ${b.pddl()})")
            init.add("(connected ${b.pddl()} ${a.pddl()})")
        }
        for (h in humans) {
            val room = state.humanAt[h]
            if (room != null) init.add("(human_at ${h.pddl()} ${room.pddl()})")
            else               init.add("(human_unknown ${h.pddl()})")
            if (h in state.nearHuman) init.add("(near_human pepper ${h.pddl()})")
        }
        if (state.hasTask) init.add("(has_task pepper)")
        for (rm in state.searchedRooms.sorted()) init.add("(searched_human ${rm.pddl()})")
        for (o in others) writeObjectFacts(init, o, o)
        writeObjectFacts(init, taskName, state.bound)
        for ((h, o) in state.informed.sortedWith(compareBy({ it.first }, { it.second }))) {
            init.add("(informed ${h.pddl()} ${o.pddl()})")
        }

        val objectsLine = buildString {
            append("pepper - robot")
            if (humans.isNotEmpty()) append(" ${humans.joinToString(" ") { it.pddl() }} - human")
            append(" ${state.rooms.joinToString(" ") { it.pddl() }} - room")
            append(" ${(others + taskName).joinToString(" ") { it.pddl() }} - object")
        }

        val text = buildString {
            append("(define (problem generated)\n")
            append("  (:domain $DOMAIN_NAME)\n")
            append("  (:objects $objectsLine)\n")
            append("  (:init\n    ${init.joinToString("\n    ")})\n")
            append("  (:goal $goal))\n")
        }

        return GeneratedProblem(text, nameMap)
    }

    /**
     * Tabella nome PDDL -> nome reale, necessaria perché il piano torna dal
     * planner con i nomi codificati ma NavigationController.moveTo() fa un
     * lookup esatto sul nome vero. Fallisce rumorosamente se due nomi reali
     * diversi collidono sullo stesso identificatore: meglio accorgersene qui
     * che avere il robot che va nel posto sbagliato.
     */
    private fun buildNameMap(realNames: List<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (real in realNames) {
            val encoded = real.pddl()
            val previous = map.put(encoded, real)
            require(previous == null || previous == real) {
                "Collisione di nomi PDDL: '$previous' e '$real' danno entrambi '$encoded'"
            }
        }
        return map
    }

    private fun writeObjectFacts(init: MutableList<String>, name: String, real: String?) {
        val encoded = name.pddl()
        val rooms = if (real != null) state.objectAt[real].orEmpty() else emptySet()
        for (rm in rooms.sorted()) init.add("(object_at $encoded ${rm.pddl()})")
        if (rooms.isEmpty()) init.add("(object_unknown $encoded)")
        if (real != null && real in state.objectFound) init.add("(object_found $encoded)")
        val srooms = state.searched.filter { it.second == real }.map { it.first }.toSet()
        for (rm in srooms.sorted()) init.add("(searched ${rm.pddl()} $encoded)")
        if (srooms.containsAll(state.rooms) && (real == null || real !in state.objectFound)) {
            init.add("(all_searched $encoded)")
        }
    }
}