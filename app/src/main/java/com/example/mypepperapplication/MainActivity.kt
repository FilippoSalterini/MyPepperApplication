package com.example.mypepperapplication

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.example.mypepperapplication.vision.BoundingBox
import com.example.mypepperapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), RobotLifecycleCallbacks {

    companion object {
        private const val TAG = "MainActivity"

        // ── Detection server ───────────────────────────────────────────────
        // Sostituisci con l'IP del PC che ospita il server YOLO
        private const val DETECTION_SERVER_URL = "http://10.186.13.27:8000"
        private const val USE_MOCK_FALLBACK    = true

        // ── Label oggetto da cercare (temporanea — sarà da UI/config) ──────
        // TODO: esporre via Dialog o Spinner nella UI
        private const val DEFAULT_SEARCH_LABEL = "laptop"
    }

    private lateinit var binding: ActivityMainBinding

    // ── RobotManager ──────────────────────────────────────────────────────────
    private val robotManager = RobotManager(
        listener = object : RobotManager.RobotManagerListener {

            override fun onModeChanged(mode: RobotMode) =
                runOnUiThread { updateUiForMode(mode) }

            override fun onFollowingHuman() =
                showToast("Seguendo l'umano…")

            override fun onCloseEnoughToHuman() =
                showToast("Sono vicino! Mi fermo.")

            override fun onCantReachHuman() =
                showToast("Non riesco a raggiungere l'umano!")

            override fun onDistanceChanged(meters: Double) =
                runOnUiThread {
                    binding.tvStatus.text = "🚶 Follow Human — distanza: ${"%.2f".format(meters)} m"
                }

            override fun onServoingStarted(label: String) =
                showToast("Visual Servoing avviato: '$label'")

            override fun onServoingStopped() =
                showToast("Visual Servoing fermato")
        }
    )

    // ── Android lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        QiSDK.register(this, this)
        setupButtons()
    }

    override fun onDestroy() {
        robotManager.stopAll()
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    // ── QiSDK lifecycle ───────────────────────────────────────────────────────

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
        showToast("Robot focus refused: $reason")
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupButtons() {

        // Snapshot: scatta un frame, mostralo e lancia la detection
        binding.btnSnapshot.setOnClickListener {
            showToast("Scattando frame…")
            robotManager.cameraController.takeSinglePicture { bitmap, _ ->
                showBitmapOnUi(bitmap)
                runDetection(bitmap)
            }
        }

        // Follow Human: toggle on/off
        binding.btnFollowHuman.setOnClickListener {
            if (robotManager.mode == RobotMode.FOLLOW_HUMAN) {
                robotManager.stopFollowHuman()
            } else {
                showToast("Cercando umano nelle vicinanze…")
                robotManager.startFollowHumanAutoDetect(
                    onNoHumanFound = { showToast("Nessun umano rilevato — avvicinati a Pepper") }
                )
            }
        }

        // Track Object: toggle on/off
        // TODO: sostituire DEFAULT_SEARCH_LABEL con selezione dinamica da UI
        binding.btnTrack.setOnClickListener {
            if (robotManager.mode == RobotMode.VISUAL_SERVOING) {
                robotManager.stopVisualServoing()
            } else {
                robotManager.startVisualServoing(label = DEFAULT_SEARCH_LABEL)
            }
        }
    }

    // ── UI update ─────────────────────────────────────────────────────────────

    private fun updateUiForMode(mode: RobotMode) {
        when (mode) {
            RobotMode.IDLE -> {
                binding.btnFollowHuman.text = "🚶 Follow Human"
                binding.btnTrack.text       = "🔍 Track Object"
                binding.tvStatus.text       = "● Idle"
            }
            RobotMode.FOLLOW_HUMAN -> {
                binding.btnFollowHuman.text = "■ Stop Following"
                binding.btnTrack.text       = "🔍 Track Object"
                binding.tvStatus.text       = "● Follow Human (SDK)"
            }
            RobotMode.VISUAL_SERVOING -> {
                binding.btnFollowHuman.text = "🚶 Follow Human"
                binding.btnTrack.text       = "■ Stop Tracking"
                binding.tvStatus.text       = "● Visual Servoing — '$DEFAULT_SEARCH_LABEL'"
            }
        }
    }

    // ── Detection helpers ─────────────────────────────────────────────────────

    private fun runDetection(bitmap: Bitmap) {
        robotManager.detectionController.detect(bitmap) { boxes, imgW, imgH ->
            Log.d(TAG, "Detection: ${boxes.size} oggetti trovati")
            boxes.forEach { box ->
                Log.d(TAG, "  [${box.source}] ${box.label} ${"%.0f".format(box.score * 100)}%")
            }
            updateOverlay(boxes, imgW, imgH)
        }
    }

    private fun showBitmapOnUi(bitmap: Bitmap) = runOnUiThread {
        binding.ivCameraPreview.setImageBitmap(bitmap)
        binding.ivCameraPreview.visibility = View.VISIBLE
    }

    private fun updateOverlay(boxes: List<BoundingBox>, imgW: Int, imgH: Int) =
        runOnUiThread { binding.overlayView.update(boxes, imgW, imgH) }

    private fun showToast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}