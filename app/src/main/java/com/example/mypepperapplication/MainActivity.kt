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
import com.example.mypepperapplication.vision.ObjectDetectionController
import com.example.mypepperapplication.vision.PepperCameraController
import com.example.mypepperapplication.controllers.PepperMovementController
import com.example.mypepperapplication.vision.VisualServoingController
import com.example.mypepperapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), RobotLifecycleCallbacks {

    private var qiContext: QiContext? = null
    private lateinit var binding: ActivityMainBinding

    // ── Controllers ───────────────────────────────────────────────────────────
    private val movementController   = PepperMovementController()
    private val cameraController     = PepperCameraController()
    private val detectionController  by lazy { ObjectDetectionController(this) }
    private val servoingController   = VisualServoingController()
//    private val servoingController   = VisualServoingController(movementController)

    // ── Stato UI ──────────────────────────────────────────────────────────────
    private var isServoingActive = false

    // ── Lifecycle Android ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QiSDK.register(this, this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMovementButtons()
        setupVisionButtons()

        // Configura GPT Vision (opzionale – commenta se usi solo YOLO)
        // detectionController.openAiApiKey = BuildConfig.OPENAI_API_KEY
        // detectionController.useGptFallback = true
    }

    override fun onDestroy() {
        QiSDK.unregister(this, this)
        servoingController.stopTracking()
        cameraController.stopContinuousCapture()
        super.onDestroy()
    }

    // ── QiSDK Lifecycle ───────────────────────────────────────────────────────

    override fun onRobotFocusGained(qiContext: QiContext) {
        Log.d("MainActivity", "Robot focus gained")
        this.qiContext = qiContext
        movementController.onRobotReady(qiContext)
        cameraController.onRobotReady(qiContext)
        servoingController.onRobotReady(qiContext)
    }

    override fun onRobotFocusLost() {
        Log.d("MainActivity", "Robot focus lost")
        this.qiContext = null
        movementController.onRobotLost()
        cameraController.onRobotLost()
        servoingController.onRobotLost()
    }

    override fun onRobotFocusRefused(reason: String?) {
        Log.w("MainActivity", "Robot focus refused: $reason")
    }

    // ── Setup UI ──────────────────────────────────────────────────────────────

    private fun setupMovementButtons() {
        binding.btnForward.setOnClickListener  { movementController.moveForward() }
        binding.btnBackward.setOnClickListener { movementController.moveBackward() }
        binding.btnLeft.setOnClickListener     { movementController.rotateLeft() }
        binding.btnRight.setOnClickListener    { movementController.rotateRight() }
        binding.btnStop.setOnClickListener     { movementController.stopMovement() }
    }

    private fun setupVisionButtons() {
        // Scatto singolo → mostra nella ImageView + bounding boxes nel log
        binding.btnSnapshot.setOnClickListener {
            cameraController.takeSinglePicture { bitmap, _ ->
                showBitmapOnUi(bitmap)
                runDetection(bitmap)
            }
        }

        // Avvia / ferma visual servoing
        binding.btnTrack.setOnClickListener {
            if (isServoingActive) {
                servoingController.stopTracking()
                isServoingActive = false
                binding.btnTrack.text = "Track Person"
                showToast("Visual servoing stopped")
            } else {
                servoingController.targetLabel = "person"
                servoingController.startTracking(
                    cameraController    = cameraController,
                    detectionController = detectionController,
                    label               = "person"
                )
                isServoingActive = true
                binding.btnTrack.text = "Stop Tracking"
                showToast("Tracking 'person'…")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun runDetection(bitmap: Bitmap) {
        detectionController.detect(bitmap) { boxes, w, h ->
            Log.d("MainActivity", "Detected ${boxes.size} objects in ${w}×${h} frame:")
            boxes.forEach { box ->
                Log.d("MainActivity",
                    "  [${box.source}] ${box.label} %.0f%% @ cx=%.2f cy=%.2f"
                        .format(box.score * 100, box.cx, box.cy))
            }
            runOnUiThread { updateOverlay(boxes, bitmap.width, bitmap.height) }
        }
    }

    private fun showBitmapOnUi(bitmap: Bitmap) {
        runOnUiThread {
            binding.ivCameraPreview.setImageBitmap(bitmap)
            binding.ivCameraPreview.visibility = View.VISIBLE
        }
    }

    private fun updateOverlay(boxes: List<BoundingBox>, w: Int, h: Int) {
        // BoundingBoxOverlayView.update() – disegna i rettangoli sull'overlay
        // (vedi BoundingBoxOverlayView.kt da creare nell'iterazione successiva)
        binding.overlayView.update(boxes, w, h)
    }

    private fun showToast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}