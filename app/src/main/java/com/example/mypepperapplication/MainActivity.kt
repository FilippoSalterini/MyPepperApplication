package com.example.mypepperapplication

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.example.mypepperapplication.config.AppConfig
import com.example.mypepperapplication.databinding.ActivityMainBinding
import com.example.mypepperapplication.ui.UiController
import com.example.mypepperapplication.vision.BoundingBox

// ================================================================
// Main Activity
// ================================================================
/**
 * Entry point Android.
 * Responsabilità (e SOLO queste):
 *   1. Lifecycle Android + QiSDK
 *   2. Creazione di UiController e RobotManager
 *   3. Wiring UI → RobotManager tramite [bindUiToRobot]
 *
 * Tutto il resto è delegato: logica robot → RobotManager, logica UI → UiController.
 */
class MainActivity : AppCompatActivity(), RobotLifecycleCallbacks {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var ui: UiController
    private lateinit var robotManager: RobotManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        QiSDK.register(this, this)

        ui = UiController(binding, this)
    }

    override fun onDestroy() {
        if (::robotManager.isInitialized) robotManager.stopAll()
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    override fun onRobotFocusGained(ctx: QiContext) {
        Log.d(TAG, "onRobotFocusGained")

        robotManager = RobotManager(listener = buildRobotListener()).apply {
            onRobotReady(ctx)
            detectionController.serverUrl       = AppConfig.DETECTION_SERVER_URL
            detectionController.useMockFallback = AppConfig.USE_MOCK_FALLBACK
        }

        bindUiToRobot()
    }

    override fun onRobotFocusLost() {
        Log.d(TAG, "onRobotFocusLost")
        robotManager.onRobotLost()
    }

    override fun onRobotFocusRefused(reason: String?) {
        Log.w(TAG, "onRobotFocusRefused: $reason")
        ui { ui.showToast("Robot focus refused: $reason") }
    }

    private fun bindUiToRobot() {
        ui.onFollowHuman = {
            ui { ui.showToast("Searching the human…") }
            robotManager.startFollowHumanAutoDetect(
                onNoHumanFound = { ui { ui.showToast("No human detected") } }
            )
        }
        ui.onStopFollowHuman = { robotManager.stopFollowHuman() }

        ui.onTrackObject = { label -> robotManager.startVisualServoing(label) }
        ui.onStopTracking = { robotManager.stopVisualServoing() }

        // [5] Snapshot: nessun callback annidato in Activity.
        //     Tutta la logica camera+detection è dentro RobotManager.processSnapshot().
        ui.onSnapshot = {
            robotManager.processSnapshot(
                onBitmap    = { bitmap -> ui { ui.showBitmap(bitmap) } },
                onDetection = { boxes, w, h -> ui { ui.updateOverlay(boxes, w, h) } }
            )
        }
    }
    private fun buildRobotListener() = object : RobotManager.RobotManagerListener {

        override fun onModeChanged(mode: RobotMode)         = ui { ui.updateForMode(mode) }
        override fun onFollowingHuman()                     = ui { ui.showToast("Following the human…") }
        override fun onCloseEnoughToHuman()                 = ui { ui.showToast("I'm close! I'll stop") }
        override fun onCantReachHuman()                     = ui { ui.showToast("I cannot reach the human!") }
        override fun onDistanceChanged(meters: Double)      = ui { ui.updateDistance(meters) }
        override fun onServoingStarted(labels: List<String>)= ui { ui.showToast("Searching: ${labels.joinToString(", ")}") }
        override fun onServoingStopped()                    = ui { ui.showToast("Visual Servoing stopped") }
        override fun onObjectReached(label: String, box: BoundingBox) = ui { ui.showToast("Object found: $label") }
        override fun onObjectLost(labels: List<String>)     = ui { ui.showToast("Object lost: ${labels.joinToString(", ")}") }
        override fun onChargingFlapOpen()                   = ui { ui.showToast("Charging flap open — movement blocked") }
    }
    private fun ui(block: () -> Unit) = runOnUiThread(block)
}