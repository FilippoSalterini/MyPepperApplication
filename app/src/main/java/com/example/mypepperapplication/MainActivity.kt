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
import com.example.mypepperapplication.controllers.PepperMovementController
import com.example.mypepperapplication.vision.BoundingBox
import com.example.mypepperapplication.vision.ObjectDetectionController
import com.example.mypepperapplication.vision.PepperCameraController
import com.example.mypepperapplication.vision.VisualServoingController
import com.example.mypepperapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), RobotLifecycleCallbacks {

    private var qiContext: QiContext? = null
    private lateinit var binding: ActivityMainBinding

    // ── Controllers ───────────────────────────────────────────────────────────

    // movementController è condiviso con VisualServoingController
    private val movementController  = PepperMovementController()
    private val cameraController    = PepperCameraController()
    private val detectionController = ObjectDetectionController().apply {
        // ⚠️ IMPORTANTE: sostituisci con l'IP del tuo PC sulla LAN WiFi
        // Trovi l'IP su Windows con: ipconfig → "Indirizzo IPv4"
        serverUrl = "http://192.168.1.100:8000"

        // Fallback mock se il server non risponde (utile per test senza PC)
        useMockFallback = true
    }

    // VisualServoingController riceve movementController dall'esterno (fix architetturale)
    private val servoingController = VisualServoingController(movementController)

    // ── Stato UI ──────────────────────────────────────────────────────────────

    private var isServoingActive = false

    // ── Lifecycle Android ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QiSDK.register(this, this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupControllerToggle()
        setupMovementButtons()
        setupVisionButtons()
    }

    override fun onDestroy() {
        servoingController.stopTracking()
        cameraController.stopContinuousCapture()
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    // ── QiSDK Lifecycle ───────────────────────────────────────────────────────

    override fun onRobotFocusGained(qiContext: QiContext) {
        Log.d("MainActivity", "Robot focus gained")
        this.qiContext = qiContext

        // Propaga qiContext a tutti i controller che ne hanno bisogno
        movementController.onRobotReady(qiContext)
        cameraController.onRobotReady(qiContext)
        servoingController.onRobotReady(qiContext)  // propaga a movementController interno
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
        showToast("Robot focus refused: $reason")
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
        // ── Snapshot singolo ──────────────────────────────────────────────────
        binding.btnSnapshot.setOnClickListener {
            showToast("Capturing frame…")
            cameraController.takeSinglePicture { bitmap, _ ->
                showBitmapOnUi(bitmap)
                runDetection(bitmap)
            }
        }
        // ── Visual Servoing ON/OFF ────────────────────────────────────────────
        binding.btnTrack.setOnClickListener {
            if (isServoingActive) {
                servoingController.stopTracking()
                isServoingActive = false
                binding.btnTrack.text = "Track Person"
                showToast("Tracking stopped")
            } else {
                servoingController.startTracking(
                    cameraController    = cameraController,
                    detectionController = detectionController,
                    label               = "person"
                )
                isServoingActive = true
                binding.btnTrack.text = "Stop Tracking"
                showToast("Tracking 'person' via PC YOLOv8…")
            }
        }
    }

    private fun setupControllerToggle() {

        binding.btnToggleControls.setOnClickListener {

            if (binding.controllerContainer.visibility == View.GONE) {

                binding.controllerContainer.visibility = View.VISIBLE
                binding.btnToggleControls.text = "Hide Controller"

            } else {

                binding.controllerContainer.visibility = View.GONE
                binding.btnToggleControls.text = "Show Controller"
            }
        }
    }
    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun runDetection(bitmap: Bitmap) {
        detectionController.detect(bitmap) { boxes, imgW, imgH ->
            Log.d("MainActivity", "=== Detection results (${boxes.size} objects) ===")
            boxes.forEach { box ->
                Log.d("MainActivity",
                    "  [${box.source}] ${box.label} %.0f%% cx=%.2f cy=%.2f"
                        .format(box.score * 100, box.cx, box.cy))
            }
            // updateOverlay viene chiamato su Main thread dal detect() stesso
            updateOverlay(boxes, imgW, imgH)
        }
    }

    private fun showBitmapOnUi(bitmap: Bitmap) {
        runOnUiThread {
            binding.ivCameraPreview.setImageBitmap(bitmap)
            binding.ivCameraPreview.visibility = View.VISIBLE
        }
    }

    /**
     * Aggiorna l'overlay con le bounding boxes.
     *
     * FIX: le coordinate bbox sono già normalizzate [0,1] dal server,
     * quindi BoundingBoxOverlayView deve scalare rispetto alle sue dimensioni
     * effettive — NON rispetto a imgW/imgH di Pepper (640x480).
     *
     * BoundingBoxOverlayView.update() deve usare:
     *   left   = box.rect.left   * view.width
     *   top    = box.rect.top    * view.height
     *   right  = box.rect.right  * view.width
     *   bottom = box.rect.bottom * view.height
     */
    private fun updateOverlay(boxes: List<BoundingBox>, imgW: Int, imgH: Int) {
        runOnUiThread {
            // imgW e imgH passati per compatibilità, ma l'overlay usa coordinate normalizzate
            binding.overlayView.update(boxes, imgW, imgH)
        }
    }

    private fun showToast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}