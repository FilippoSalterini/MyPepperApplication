package com.example.mypepperapplication

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.example.mypepperapplication.databinding.ActivityMainBinding
import com.example.mypepperapplication.ui.UiController
import com.example.mypepperapplication.vision.BoundingBox
// ================================================================
// Main Activity
// ================================================================

/* Entry point Android, gestisce i lifecycle callbacks quindi vita
android e QISDK - Delega tutto a RobotManager e UiController
 */
class MainActivity : AppCompatActivity(), RobotLifecycleCallbacks {
    // DETECTION_SERVER_URL: indirizzo IP del PC che esegue il server YOLOv8.
    // USE_MOCK_FALLBACK: se true, in caso di errore ritorna lista vuota anziché crashare.
    // Modifica DETECTION_SERVER_URL con l'IP della tua macchina sulla rete locale.
    companion object {
        private const val TAG = "MainActivity"
        private const val DETECTION_SERVER_URL = "http://10.186.13.27:8000"
        private const val USE_MOCK_FALLBACK    = true
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var ui: UiController

    private val robotManager = RobotManager(
        listener = object : RobotManager.RobotManagerListener {

            override fun onModeChanged(mode: RobotMode) =
                runOnUiThread { ui.updateForMode(mode) }

            override fun onFollowingHuman() =
                runOnUiThread { ui.showToast("Following the human…") }

            override fun onCloseEnoughToHuman() =
                runOnUiThread { ui.showToast("I'm close! I'll stop") }

            override fun onCantReachHuman() =
                runOnUiThread { ui.showToast("I cannot reach the human!") }

            override fun onDistanceChanged(meters: Double) =
                runOnUiThread { ui.updateDistance(meters) }

            override fun onServoingStarted(labels: List<String>) =
                runOnUiThread { ui.showToast("Searching: ${labels.joinToString(", ")}") }

            override fun onServoingStopped() =
                runOnUiThread { ui.showToast("Visual Servoing stopped") }

            override fun onObjectReached(label: String, box: BoundingBox) =
                runOnUiThread { ui.showToast("Object found: $label") }

            override fun onObjectLost(labels: List<String>) =
                runOnUiThread { ui.showToast("Object lost: ${labels.joinToString(", ")}") }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        QiSDK.register(this, this)

        ui = UiController(binding, this).apply {
            onFollowHuman = {
                ui.showToast("Searching the human…")
                robotManager.startFollowHumanAutoDetect(
                    onNoHumanFound = { runOnUiThread { ui.showToast("No human detected") } }
                )
            }
            onStopFollowHuman = { robotManager.stopFollowHuman() }

            onTrackObject = { label -> robotManager.startVisualServoing(label) }
            onStopTracking = { robotManager.stopVisualServoing() }

            onSnapshot = {
                robotManager.cameraController.takeSinglePicture { bitmap, _ ->
                    runOnUiThread { ui.showBitmap(bitmap) }
                    robotManager.detectionController.detect(bitmap) { boxes, w, h ->
                        runOnUiThread { ui.updateOverlay(boxes, w, h) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        robotManager.stopAll()
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    override fun onRobotFocusGained(ctx: QiContext) {
        Log.d(TAG, "onRobotFocusGained")
        robotManager.onRobotReady(ctx)
        robotManager.detectionController.serverUrl       = DETECTION_SERVER_URL
        robotManager.detectionController.useMockFallback = USE_MOCK_FALLBACK
    }

    override fun onRobotFocusLost() {
        Log.d(TAG, "onRobotFocusLost")
        robotManager.onRobotLost()
    }

    override fun onRobotFocusRefused(reason: String?) {
        Log.w(TAG, "onRobotFocusRefused: $reason")
        runOnUiThread { ui.showToast("Robot focus refused: $reason") }
    }
}