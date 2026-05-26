package com.example.mypepperapplication.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import com.example.mypepperapplication.RobotMode
import com.example.mypepperapplication.databinding.ActivityMainBinding
import com.example.mypepperapplication.vision.BoundingBox
// ===========================================================================
// UI CONTROLLER
// ===========================================================================

/*
Gestisce tutta la logica UI: spinner, pulsanti, overlay, toast, status bar.
 */
private const val TAG = "UiController"
class UiController(
    private val binding: ActivityMainBinding,
    private val context: Context
) {
    //TODO : vedi oggetti da cercare e logica di default
    private val searchableLabels = listOf(
        "bottle"
    )

    var onFollowHuman: (() -> Unit)? = null
    var onStopFollowHuman: (() -> Unit)? = null
    var onTrackObject: ((label: String) -> Unit)? = null
    var onStopTracking: (() -> Unit)? = null
    var onSnapshot: (() -> Unit)? = null

    val selectedLabel: String
        get() = binding.spinnerLabel.selectedItem as? String ?: searchableLabels.first()

    init {
        setupSpinner()
        setupButtons()
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            searchableLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerLabel.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnSnapshot.setOnClickListener { onSnapshot?.invoke() }

        binding.btnFollowHuman.setOnClickListener {
            //testo pulsante -> tasto corrente
            if (binding.btnFollowHuman.tag == RobotMode.FOLLOW_HUMAN) {
                onStopFollowHuman?.invoke()
            } else {
                onFollowHuman?.invoke()
            }
        }

        binding.btnTrack.setOnClickListener {
            if (binding.btnTrack.tag == RobotMode.VISUAL_SERVOING) {
                onStopTracking?.invoke()
            } else {
                onTrackObject?.invoke(selectedLabel)
            }
        }
    }

    fun updateForMode(mode: RobotMode) {
        Log.d(TAG, "updateForMode: $mode")
        when (mode) {
            RobotMode.IDLE -> {
                binding.btnFollowHuman.text = "Follow Human"
                binding.btnFollowHuman.tag  = RobotMode.IDLE
                binding.btnTrack.text       = "Track Object"
                binding.btnTrack.tag        = RobotMode.IDLE
                binding.tvStatus.text       = "Idle"
                binding.spinnerLabel.isEnabled = true
            }
            RobotMode.FOLLOW_HUMAN -> {
                binding.btnFollowHuman.text = "Stop Following"
                binding.btnFollowHuman.tag  = RobotMode.FOLLOW_HUMAN
                binding.btnTrack.text       = "Track Object"
                binding.btnTrack.tag        = RobotMode.IDLE
                binding.tvStatus.text       = "Follow Human (SDK)"
                binding.spinnerLabel.isEnabled = false
            }
            RobotMode.VISUAL_SERVOING -> {
                binding.btnFollowHuman.text = "Follow Human"
                binding.btnFollowHuman.tag  = RobotMode.IDLE
                binding.btnTrack.text       = "Stop Tracking"
                binding.btnTrack.tag        = RobotMode.VISUAL_SERVOING
                binding.tvStatus.text       = "Visual Servoing — $selectedLabel"
                binding.spinnerLabel.isEnabled = false
            }
        }
    }

    fun updateDistance(meters: Double) {
        binding.tvStatus.text = "Follow Human — ${"%.2f".format(meters)} m"
    }

    fun showBitmap(bitmap: Bitmap) {
        binding.ivCameraPreview.setImageBitmap(bitmap)
        binding.ivCameraPreview.visibility = View.VISIBLE
    }

    fun updateOverlay(boxes: List<BoundingBox>, imgW: Int, imgH: Int) {
        binding.overlayView.update(boxes, imgW, imgH)
    }

    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}