package com.example.mypepperapplication.navigation

import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
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
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import com.aldebaran.qi.sdk.`object`.actuation.Localize.OnStatusChangedListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.math.atan2
import java.io.File

/**
 * Basata sull'architettura della repository https://github.com/softbankrobotics-labs/maplocalizeandmove.git
 * Il processo si fonda su un approccio di **Visual SLAM**, che combina le fotocamere stereo,
 * l'odometria delle ruote e i laser di sicurezza:
 *
 * 1. **Estrazione Feature**: Per ogni frame stereo, l'algoritmo individua i punti d'interesse (corner ad alto
 *    contrasto tra zone chiare e scure).
 *
 * 2. **Matching tra Frame**: Confronta le feature attuali con quelle dei frame precedenti per stimare lo
 *    spostamento del robot (visual odometry), integrando l'odometria dei motori.
 *
 * 3. **Triangolazione Stereo**: Calcola la posizione 3D delle feature nello spazio rispetto al robot sfruttando
 *    la vista dalle due fotocamere.
 *
 * 4. **Costruzione della Mappa**: Le feature confermate diventano *anchor point*, salvati sotto forma di
 *    descrittori matematici insieme alla loro posizione 3D rispetto al `MapFrame`.
 *
 * 5. **Loop Closure**: Riconosce i punti già mappati (es. durante il giro a 360° finale) per "chiudere l'anello"
 *    e correggere la deriva cumulativa.
 *
 * Responsabilità specifiche della classe:
 * - **Mappatura & Traiettoria**: Esegue `LocalizeAndMap` producendo un'oggetto [ExplorationMap] ed effettua
 *   il campionamento (sampling) periodico delle coordinate 2D del robot per tracciarne la traiettoria.
 * - **Gestione Punti di Interesse (PoI)**: Calcola, memorizza e ripristina i frame di riferimento (coordinate 2D)
 *   relativi al `MapFrame`, salvandoli su file JSON.
 * - **Rilocalizzazione & Movimento**: Rilocalizza il robot all'interno di una mappa salvata ed esegue i comandi
 *   di navigazione (`moveTo`), integrando logiche di retry e gestione degli ostacoli.
 * - **Persistenza & Esportazione**: Serializza e carica la mappa su file di testo ed estrae la rappresentazione
 *   grafica 2D sotto forma di [Bitmap].
 */
class NavigationController(private val qiContext: QiContext) {
    enum class GoToStatus { FAILED, CANCELLED, FINISHED }
    companion object { private const val TAG = "NavigationController" }
    private var explorationMap: ExplorationMap? = null
    private var currentLocalizeFuture: Future<Void>? = null
    // Traccia il GoTo lanciato da goToWithRetry (moveTo), separato da currentLocalizeFuture
    // che copre solo Localize/LocalizeAndMap. Serve per poterlo cancellare da emergencyStop().
    @Volatile private var currentGoToFuture: Future<Void>? = null
    private val poiFrames = mutableMapOf<String, AttachedFrame>()
    private val poiStore = PoiStore(File(qiContext.filesDir, "pois.json"))
    private val trajectoryPoints = mutableListOf<Triple<Double, Double, Double>>() // x, y, theta
    private var samplingJob: Job? = null
    private val trajectoryStore = File(qiContext.filesDir, "trajectory.json")

    // --- SETUP: mappatura guidata, una tantum ---

    suspend fun localizeAndMap(withExistingMap: Boolean, samplingScope: CoroutineScope): Boolean = withContext(Dispatchers.IO) {
    // Nota da documentazione ufficiale (LocalizeAndMap):
    // https://qisdk.softbankrobotics.com/sdk/doc/pepper-sdk/ch4_api/movement/reference/localize_and_map.html
    // La guida raccomanda di verificare che non ci siano persone o ostacoli in movimento attorno a Pepper
    // e di attendere fino a 15 secondi prima di avviare l'azione.
    //
    // NOTA: Dalle prove empiriche effettuate, un'attesa di 5 secondi risulta sufficiente.
        delay(5000L)
        val action = LocalizeAndMapBuilder.with(qiContext)
            .apply { if (withExistingMap && explorationMap != null) withMap(explorationMap) }
            .build()
        val future = action.async().run()
        currentLocalizeFuture = future
        startTrajectorySampling(samplingScope)
        try { future.get() } catch (_: Exception) { }
        stopTrajectorySampling()

        if (future.hasError()) {
            Log.w(TAG, "LocalizeAndMap error: ${future.error}")
            return@withContext false
        }
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
    fun getPoiNames(): List<String> = poiFrames.keys.toList()
    // --- RUNTIME: rilocalizzazione + navigazione verso i PoI salvati ---

    // suspend fun localize(): Boolean = withContext(Dispatchers.IO) {
    //     val map = explorationMap ?: return@withContext false
    //     val localize = LocalizeBuilder.with(qiContext).withMap(map).build()
    //     val future = localize.async().run()
    //     currentLocalizeFuture = future
    //
    //     val localized = withTimeoutOrNull(90_000L) {
    //         suspendCancellableCoroutine<Boolean> { cont ->
    //             val onStatusChangedListener = OnStatusChangedListener { status ->
    //                 Log.i(TAG, "LOCALIZE: status changed -> $status")
    //                 if (status == LocalizationStatus.LOCALIZED && cont.isActive) {
    //                     cont.resume(true)
    //                 }
    //             }
    //             localize.addOnStatusChangedListener(onStatusChangedListener)
    //             cont.invokeOnCancellation {
    //                 localize.removeOnStatusChangedListener(onStatusChangedListener)
    //                 future.requestCancellation()
    //             }
    //         }
    //     } ?: false
    //
    //     future.requestCancellation()
    //     Log.i(TAG, "LOCALIZE: result=$localized")
    //     localized
    // }

    suspend fun localize(): Boolean = withContext(Dispatchers.IO) {
        val map = explorationMap ?: return@withContext false
        val localize = LocalizeBuilder.with(qiContext).withMap(map).build()
        val future = localize.async().run()
        currentLocalizeFuture = future

        val localized = withTimeoutOrNull(90_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val listener = OnStatusChangedListener { status ->
                    Log.i(TAG, "LOCALIZE status: $status (t=${System.currentTimeMillis()})")
                    if (status == LocalizationStatus.LOCALIZED && cont.isActive) {
                        cont.resume(true)
                    }
                }
                localize.addOnStatusChangedListener(listener)
                cont.invokeOnCancellation {
                    localize.removeOnStatusChangedListener(listener)
                    future.requestCancellation()
                }
            }
        } ?: run {
            future.requestCancellation()
            false
        }
        Log.i(TAG, "LOCALIZE: result=$localized (background localization ${if (localized) "still running" else "stopped"})")
        localized
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
        Log.i(TAG, "PROVA LOCALIZE ATTIVA: isDone=${currentLocalizeFuture?.isDone}, isCancelled=${currentLocalizeFuture?.isCancelled}")
        return goToWithRetry(attached.frame(), straight, maxSpeed)
    }

    fun stopCurrentAction() {
        currentLocalizeFuture?.requestCancellation()
    }

    fun stopMovement() {
        try {
            currentGoToFuture?.requestCancellation()
        } catch (e: Exception) {
            Log.w(TAG, "stopMovement: error cancelling GoTo: ${e.message}")
        }
        try {
            currentLocalizeFuture?.requestCancellation()
        } catch (e: Exception) {
            Log.w(TAG, "stopMovement: error cancelling Localize: ${e.message}")
        }
    }
    // --- Persistenza mappa su file ---

    fun saveMapToFile(file: File) {
        val map = explorationMap ?: return
        val mapData: String = map.serialize()
        file.writeText(mapData)
    }

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
            currentGoToFuture = future
            try { future.get() } catch (_: Exception) { }
            if (currentGoToFuture == future) currentGoToFuture = null
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
    // --- Trajectory sampling (per VISUALIZZAZZIONE) ---

    private fun startTrajectorySampling(scope: CoroutineScope) {
        trajectoryPoints.clear()
        samplingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val mapFrame = qiContext.mapping.mapFrame()
                    val robotFrame = qiContext.actuation.robotFrame()
                    val t = robotFrame.computeTransform(mapFrame).transform
                    trajectoryPoints.add(Triple(t.translation.x, t.translation.y, yawFromQuaternion(t.rotation)))
                } catch (_: Exception) {}
                delay(700L)
            }
        }
    }

    private fun stopTrajectorySampling() {
        samplingJob?.cancel()
        samplingJob = null
    }

    fun saveTrajectoryToFile() {
        val gson = Gson()
        val json = gson.toJson(trajectoryPoints.map { mapOf("x" to it.first, "y" to it.second, "theta" to it.third) })
        trajectoryStore.writeText(json)
        Log.i(TAG, "Trajectory saved: ${trajectoryPoints.size} points -> $trajectoryStore")
    }

    fun getMapBitmap(): Bitmap? {
        val map = explorationMap ?: return null
        return try {
            val byteBuffer = map.topGraphicalRepresentation.image.data.apply { rewind() }
            val size = byteBuffer.remaining()
            val byteArray = ByteArray(size).also { byteBuffer.get(it) }
            BitmapFactory.decodeByteArray(byteArray, 0, size)
        } catch (e: Exception) {
            Log.e(TAG, "getMapBitmap error: ${e.message}")
            null
        }
    }
}