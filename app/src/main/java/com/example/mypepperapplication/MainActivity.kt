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
    }

    private lateinit var binding: ActivityMainBinding

    // ── RobotManager ─────────────────────────────────────────────────────────
    private val robotManager = RobotManager(
        listener = object : RobotManager.RobotManagerListener {

            override fun onModeChanged(mode: RobotMode) {
                runOnUiThread { updateUiForMode(mode) }
            }

            override fun onFollowingHuman() {
                showToast("Seguendo l'umano…")
            }

            override fun onCloseEnoughToHuman() {
                showToast("Sono vicino! Mi fermo.")
            }

            override fun onCantReachHuman() {
                showToast("Non riesco a raggiungere l'umano!")
            }

            override fun onDistanceChanged(meters: Double) {
                runOnUiThread {
                    binding.tvStatus.text = "Distanza: ${"%.2f".format(meters)} m"
                }
            }

            override fun onServoingStarted(label: String) {
                showToast("Visual Servoing: '$label'")
            }

            override fun onServoingStopped() {
                showToast("Visual Servoing fermato")
            }
        }
    )

    // ── Lifecycle Android ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QiSDK.register(this, this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupButtons()
    }

    override fun onDestroy() {
        robotManager.stopAll()
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    // ── QiSDK Lifecycle ───────────────────────────────────────────────────────

    override fun onRobotFocusGained(ctx: QiContext) {
        Log.d(TAG, "Robot focus gained")
        robotManager.onRobotReady(ctx)

        // Configura server YOLO — sostituisci con l'IP del tuo PC
        robotManager.detectionController.serverUrl       = "http://10.186.13.27:8000"
        robotManager.detectionController.useMockFallback = true
    }

    override fun onRobotFocusLost() {
        Log.d(TAG, "Robot focus lost")
        robotManager.onRobotLost()
    }

    override fun onRobotFocusRefused(reason: String?) {
        Log.w(TAG, "Robot focus refused: $reason")
        showToast("Robot focus refused: $reason")
    }

    // ── Setup pulsanti ────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnSnapshot.setOnClickListener {
            showToast("Scattando frame…")
            robotManager.cameraController.takeSinglePicture { bitmap, _ ->
                showBitmapOnUi(bitmap)
                runDetection(bitmap)
            }
        }
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
        binding.btnTrack.setOnClickListener {
            if (robotManager.mode == RobotMode.VISUAL_SERVOING) {
                robotManager.stopVisualServoing()
            } else {
                // TODO: QUI BISGONA VERIFICARE POI COME GESTIRE GLI OGGETTI DA CERCARE
                //label cambia con oggetto di interessa
                robotManager.startVisualServoing(label = "laptop")
            }
        }
    }

    private fun updateUiForMode(mode: RobotMode) {
        when (mode) {
            RobotMode.IDLE -> {
                binding.btnFollowHuman.text = "Follow Human"
                binding.btnTrack.text       = "Track Object"
                binding.tvStatus.text       = "Idle"
            }
            RobotMode.FOLLOW_HUMAN -> {
                binding.btnFollowHuman.text = "Stop Following"
                binding.btnTrack.text       = "Track Object"
                binding.tvStatus.text       = "Modalità: Follow Human (SDK)"
            }
            RobotMode.VISUAL_SERVOING -> {
                binding.btnFollowHuman.text = "Follow Human"
                binding.btnTrack.text       = "Stop Tracking"
                binding.tvStatus.text       = "Modalità: Visual Servoing (YOLO)"
            }
        }
    }

    private fun runDetection(bitmap: Bitmap) {
        robotManager.detectionController.detect(bitmap) { boxes, imgW, imgH ->
            Log.d(TAG, "Detection: ${boxes.size} oggetti")
            boxes.forEach { box ->
                Log.d(TAG, "  [${box.source}] ${box.label} ${"%.0f".format(box.score * 100)}%")
            }
            updateOverlay(boxes, imgW, imgH)
        }
    }

    private fun showBitmapOnUi(bitmap: Bitmap) {
        runOnUiThread {
            binding.ivCameraPreview.setImageBitmap(bitmap)
            binding.ivCameraPreview.visibility = View.VISIBLE
        }
    }

    private fun updateOverlay(boxes: List<BoundingBox>, imgW: Int, imgH: Int) {
        runOnUiThread { binding.overlayView.update(boxes, imgW, imgH) }
    }

    private fun showToast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}