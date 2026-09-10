package com.example.mypepperapplication.planning

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import com.example.mypepperapplication.core.AppConfig
private const val TAG = "PlannerClient"

/**
 * Esito di una richiesta di pianificazione: tre casi che il PlanExecutor
 * deve trattare in modo diverso.
 */
sealed class PlanResult {
    /** Piano trovato. actions vuota = goal già soddisfatto nello stato iniziale. */
    data class Plan(
        val actions: List<String>,
        val metrics: Map<String, String> = emptyMap()
    ) : PlanResult() {
        val isGoalAlreadySatisfied: Boolean get() = actions.isEmpty()
    }
    /** Il planner ha girato ma non esiste un piano per questo problema. */
    object Unsolvable : PlanResult()
    /** Non siamo riusciti a parlare col planner (rete, HTTP, risposta malformata). */
    data class Error(val reason: String) : PlanResult()
}

/**
 * Client HTTP verso l'endpoint /plan del server. Il server esegue Fast Downward e
 * restituisce il piano GIÀ PARSATO: qui dentro non esiste nessuna conoscenza di
 * sas_plan, regex o formati di output del planner.
 *
 * Contratto:
 *   POST /plan   { "problem": "<testo problem.pddl>" }
 *   200          { "plan": ["go_to pepper posizione_uno posizione_due", ...] }
 *                { "plan": null }   -> problema irresolvibile
 */
class PlannerClient(
    private val serverIp: String,
    private val serverPort: Int = AppConfig.SERVER_PORT
) {
    companion object {
        private const val CONNECT_TIMEOUT_S = 5L
        private const val READ_TIMEOUT_S    = 60L   // stima da calibrare col problema reale
        private const val CALL_TIMEOUT_S    = 65L
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    suspend fun requestPlan(problemPddl: String): PlanResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("problem", problemPddl).toString()
        val request = Request.Builder()
            .url("http://$serverIp:$serverPort/plan")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    Log.e(TAG, "Plan request failed: ${e.message}")
                    cont.resume(PlanResult.Error("Server non raggiungibile: ${e.message}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            Log.e(TAG, "Plan request HTTP ${res.code}")
                            cont.resume(PlanResult.Error("HTTP ${res.code} ${res.message}"))
                            return
                        }
                        val body = res.body?.string()
                        if (body == null) {
                            cont.resume(PlanResult.Error("Risposta vuota dal server"))
                            return
                        }
                        cont.resume(parseResponse(body))
                    }
                }
            })
        }
    }

    private fun parseResponse(body: String): PlanResult = try {
        val root = JSONObject(body)
        if (root.isNull("plan")) {
            Log.w(TAG, "Planner: nessun piano trovato (problema irresolvibile)")
            PlanResult.Unsolvable
        } else {
            val arr = root.getJSONArray("plan")
            val actions = (0 until arr.length()).map { arr.getString(it).trim() }
            val metrics = root.optJSONObject("metrics")?.let { m ->
                m.keys().asSequence().associateWith { k -> m.opt(k)?.toString() ?: "" }
            } ?: emptyMap()
            Log.i(TAG, "Planner: piano di ${actions.size} azioni")
            PlanResult.Plan(actions, metrics)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Parse error: ${e.message}")
        PlanResult.Error("Risposta malformata: ${e.message}")
    }
}