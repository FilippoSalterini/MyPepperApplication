package com.example.mypepperapplication.navigation

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder
import com.aldebaran.qi.sdk.builder.LocalizeBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.builder.ExplorationMapBuilder
import com.aldebaran.qi.sdk.`object`.actuation.AttachedFrame
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import com.aldebaran.qi.sdk.`object`.actuation.PathPlanningPolicy
import com.aldebaran.qi.sdk.`object`.geometry.Quaternion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2

class NavigationController(private val qiContext: QiContext) {

    private var explorationMap: ExplorationMap? = null
    private var currentLocalizeFuture: Future<Void>? = null

    private val poiFrames = mutableMapOf<String, AttachedFrame>()
    private val poiStore = PoiStore(File(qiContext.filesDir, "pois.json"))
    // --- SETUP: mappatura guidata, una tantum ---

    suspend fun localizeAndMap(withExistingMap: Boolean): Boolean = withContext(Dispatchers.IO) {
        val action = LocalizeAndMapBuilder.with(qiContext)
            .apply { if (withExistingMap && explorationMap != null) withMap(explorationMap) }
            .build()
        val future = action.async().run()
        currentLocalizeFuture = future
        try { future.get() } catch (_: Exception) { /* atteso -> stop manuale */ }

        if (future.hasError()) {
            Log.w(TAG, "LocalizeAndMap error: ${future.error}")
            return@withContext false
        }
        // sia su cancellazione volontaria che su fine naturale, dumpiamo la mappa
        explorationMap = action.async().dumpMap().get()
        true
    }
    suspend fun saveCurrentPositionAsPoi(name: String) = withContext(Dispatchers.IO) {
        val mapping = qiContext.mapping
        val mapFrame = mapping.mapFrame()
        val robotFrame = qiContext.actuation.robotFrame()
        val transform = robotFrame.computeTransform(mapFrame).transform
        poiFrames[name] = mapFrame.makeAttachedFrame(transform)
    }

    fun persistPois() {
        val mapFrame = qiContext.mapping.mapFrame()
        val list = poiFrames.map { (name, attached) ->
            val t = attached.frame().computeTransform(mapFrame).transform
            PointOfInterest(name, t.translation.x, t.translation.y, yawFromQuaternion(t.rotation))
        }
        poiStore.save(list)
    }

    // --- RUNTIME: rilocalizzazione + navigazione verso i PoI salvati ---
    //                      LOCALIZE 2 VERSIONS
//    suspend fun localize(): Boolean = withContext(Dispatchers.IO) {
//        val map = explorationMap
//        if (map == null) {
//            Log.e(TAG, "LOCALIZE: explorationMap is NULL")
//            return@withContext false
//        }
//        Log.i(TAG, "LOCALIZE: starting localization")
//        try {
//            val localize = LocalizeBuilder
//                .with(qiContext)
//                .withMap(map)
//                .build()
//            Log.i(TAG, "LOCALIZE: Localize action built")
//            val future = localize.async().run()
//            Log.i(TAG, "LOCALIZE: action started")
//            future.get()
//            Log.i(TAG, "LOCALIZE: future.get() completed")
//            Log.i(
//                TAG,
//                "LOCALIZE: success=${future.isSuccess}, " +
//                        "cancelled=${future.isCancelled}, " +
//                        "hasError=${future.hasError()}, " +
//                        "error=${future.error}"
//            )
//            return@withContext future.isSuccess
//        } catch (e: Exception) {
//            Log.e(TAG, "LOCALIZE: exception during localization", e)
//            return@withContext false
//        }
//    }

    suspend fun localize(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "LOCALIZE: start")
        val map = explorationMap
        if (map == null) {
            Log.e(TAG, "LOCALIZE: explorationMap is NULL")
            return@withContext false
        }
        Log.d(TAG, "LOCALIZE: building Localize object")
        val localize = LocalizeBuilder.with(qiContext).withMap(map).build()
        Log.d(TAG, "LOCALIZE: starting async run()")
        val future = localize.async().run()
        currentLocalizeFuture = future
        val completed = withTimeoutOrNull(30_000L) {
            try {
                Log.d(TAG, "LOCALIZE: waiting for future.get()")
                future.get().also {
                    Log.d(TAG, "LOCALIZE: future.get() completed successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "LOCALIZE: exception during future.get()", e)
                null
            }
        }
        if (completed == null) {
            Log.w(TAG, "LOCALIZE: TIMEOUT after 30s, requesting cancellation")
            future.requestCancellation()
            return@withContext false
        }
        val success = future.isSuccess
        Log.d(TAG, "LOCALIZE: completed = $completed, isSuccess = $success")
        success
    }
    fun loadPois() {
        val mapFrame = qiContext.mapping.mapFrame()
        poiFrames.clear()
        poiStore.load().forEach { poi ->
            val transform = TransformBuilder.create().from2DTransform(poi.x, poi.y, poi.theta)
            poiFrames[poi.name] = mapFrame.makeAttachedFrame(transform)
        }
    }

    suspend fun moveTo(name: String, straight: Boolean = false, maxSpeed: Boolean = false): GoToStatus {
        val attached = poiFrames[name] ?: return GoToStatus.FAILED
        return goToWithRetry(attached.frame(), straight, maxSpeed)
    }

    fun stopCurrentAction() {
        currentLocalizeFuture?.requestCancellation()
    }

    // --- Persistenza mappa su file ---

    fun saveMapToFile(file: File) {
        val map = explorationMap ?: return
        val mapData: String = map.serialize()
        file.writeText(mapData)
    }
// LOAD MAP FROM FILE 2 VERSIONS
//    fun loadMapFromFile(file: File): Boolean {
//        if (!file.exists()) return false
//        return try {
//            val mapData = file.readText()
//            explorationMap = ExplorationMapBuilder.with(qiContext)
//                .withMapString(mapData)
//                .build()
//            true
//        } catch (e: Exception) {
//            Log.w(TAG, "loadMapFromFile error: ${e.message}")
//            false
//        }
//    }

fun loadMapFromFile(file: File): Boolean {

    Log.i(TAG, "LOAD MAP: path=${file.absolutePath}")
    Log.i(TAG, "LOAD MAP: exists=${file.exists()}")

    if (!file.exists()) {
        Log.e(TAG, "LOAD MAP: file does not exist")
        return false
    }

    return try {

        val mapData = file.readText()

        Log.i(TAG, "LOAD MAP: file size=${mapData.length}")

        explorationMap = ExplorationMapBuilder
            .with(qiContext)
            .withMapString(mapData)
            .build()

        Log.i(TAG, "LOAD MAP: ExplorationMap successfully reconstructed")

        true

    } catch (e: Exception) {

        Log.e(TAG, "LOAD MAP: ERROR loading map", e)

        false
    }
}

    private suspend fun goToWithRetry(
        frame: Frame, straight: Boolean, maxSpeed: Boolean, maxTries: Int = 15
    ): GoToStatus = withContext(Dispatchers.IO) {
        val policy = if (straight) PathPlanningPolicy.STRAIGHT_LINES_ONLY else PathPlanningPolicy.GET_AROUND_OBSTACLES
        val speed = if (maxSpeed) 0.55f else 0.40f
        var tries = maxTries
        while (true) {
            val goTo = GoToBuilder.with(qiContext)
                .withFrame(frame)
                .withPathPlanningPolicy(policy)
                .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                .withMaxSpeed(speed)
                .build()
            val future = goTo.async().run()
            try { future.get() } catch (_: Exception) { }
            when {
                future.isSuccess -> return@withContext GoToStatus.FINISHED
                future.isCancelled -> return@withContext GoToStatus.CANCELLED
                future.hasError() && tries > 0 -> { tries--; delay(1500L) }
                else -> return@withContext GoToStatus.FAILED
            }
        }
        @Suppress("UNREACHABLE_CODE") GoToStatus.FAILED
    }

    private fun yawFromQuaternion(q: Quaternion): Double {
        val sinYaw = 2.0 * (q.w * q.z + q.x * q.y)
        val cosYaw = 1.0 - 2.0 * (q.y * q.y + q.z * q.z)
        return atan2(sinYaw, cosYaw)
    }

    enum class GoToStatus { FAILED, CANCELLED, FINISHED }

    companion object { private const val TAG = "NavigationController" }
}