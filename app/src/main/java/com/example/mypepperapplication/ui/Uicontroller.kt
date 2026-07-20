package com.example.mypepperapplication.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.mypepperapplication.core.RobotMode
import com.example.mypepperapplication.databinding.ActivityMainBinding
private const val TAG = "UiController"

class UiController(
    private val binding: ActivityMainBinding,
    private val context: Context
) {
    private val searchableLabels = listOf("bottle", "cup", "glasses", "remote", "keys")

    var onFollowHuman:      (() -> Unit)? = null
    var onStopFollowHuman:  (() -> Unit)? = null
    var onApproachHuman:    (() -> Unit)? = null
    var onStopApproachHuman:(() -> Unit)? = null
    var onFindHuman:        (() -> Unit)? = null
    var onStopFindHuman:    (() -> Unit)? = null
    var onTrackObject:      ((String) -> Unit)? = null
    var onStopTracking:     (() -> Unit)? = null
    val selectedLabel: String
        get() = binding.spinnerLabel.selectedItem as? String ?: searchableLabels.first()

    init {
        setupSpinner()
        setupButtons()
    }

    // ── Spinner ───────────────────────────────────────────────────────────

    private fun setupSpinner() {
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            searchableLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerLabel.adapter = adapter
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnFollowHuman.setOnClickListener {
            if (binding.btnFollowHuman.tag == RobotMode.FOLLOW_HUMAN)
                onStopFollowHuman?.invoke()
            else
                onFollowHuman?.invoke()
        }
        binding.btnApproachHuman.setOnClickListener {
            if (binding.btnApproachHuman.tag == RobotMode.APPROACH_HUMAN)
                onStopApproachHuman?.invoke()
            else
                onApproachHuman?.invoke()
        }
        binding.btnFindHuman.setOnClickListener {
            if (binding.btnFindHuman.tag == RobotMode.FIND_PERSON)
                onStopFindHuman?.invoke()
            else
                onFindHuman?.invoke()
        }
        binding.btnTrack.setOnClickListener {
            if (binding.btnTrack.tag == RobotMode.VISUAL_SERVOING)
                onStopTracking?.invoke()
            else
                onTrackObject?.invoke(selectedLabel)
        }
    }

    // ── Mode ──────────────────────────────────────────────────────────────

    fun updateForMode(mode: RobotMode) {
        Log.d(TAG, "updateForMode: $mode")
        // Reset tutto a IDLE come baseline
        binding.btnFollowHuman.text   = "Follow Human";   binding.btnFollowHuman.tag   = RobotMode.IDLE
        binding.btnApproachHuman.text = "Approach Human"; binding.btnApproachHuman.tag = RobotMode.IDLE
        binding.btnFindHuman.text     = "Find Human";     binding.btnFindHuman.tag     = RobotMode.IDLE
        binding.btnTrack.text         = "Track Object";   binding.btnTrack.tag         = RobotMode.IDLE
        binding.spinnerLabel.isEnabled = true

        when (mode) {
            RobotMode.IDLE -> {
                binding.tvStatus.text = "Idle"
            }
            RobotMode.FOLLOW_HUMAN -> {
                binding.btnFollowHuman.text = "Stop Following"
                binding.btnFollowHuman.tag  = RobotMode.FOLLOW_HUMAN
                binding.tvStatus.text       = "Following Human…"
                binding.spinnerLabel.isEnabled = false
            }
            RobotMode.APPROACH_HUMAN -> {
                binding.btnApproachHuman.text = "Stop Approach"
                binding.btnApproachHuman.tag  = RobotMode.APPROACH_HUMAN
                binding.tvStatus.text         = "Approaching Human…"
                binding.spinnerLabel.isEnabled = false
            }
            RobotMode.FIND_PERSON -> {
                binding.btnFindHuman.text = "Stop Finding"
                binding.btnFindHuman.tag  = RobotMode.FIND_PERSON
                binding.tvStatus.text     = "Finding Human…"
                binding.spinnerLabel.isEnabled = false
            }
            RobotMode.VISUAL_SERVOING -> {
                binding.btnTrack.text = "Stop Tracking"
                binding.btnTrack.tag  = RobotMode.VISUAL_SERVOING
                binding.tvStatus.text = "Tracking — $selectedLabel"
                binding.spinnerLabel.isEnabled = false
            }
        }
    }

    fun updateDistance(meters: Double) {
        binding.tvStatus.text = "Following — ${"%.2f".format(meters)} m"
    }

    // ── Conversation bubbles ──────────────────────────────────────────────

    fun addUserMessage(text: String)  = addBubble(text, isUser = true)
    fun addRobotMessage(text: String) = addBubble(text, isUser = false)

    private fun addBubble(text: String, isUser: Boolean) {
        val bubble = TextView(context).apply {
            this.text = text
            textSize  = 13f
            setTextColor(
                if (isUser) 0xFF0D0F14.toInt()
                else        0xFFE8EAF0.toInt()
            )
            setBackgroundColor(
                if (isUser) 0xFF00E5A0.toInt()
                else        0xFF252830.toInt()
            )
            setPadding(28, 16, 28, 16)
        }

        val screenWidth = binding.root.width
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin    = 6
            bottomMargin = 6
            if (isUser) {
                marginStart = screenWidth / 4
                gravity     = Gravity.END
            } else {
                marginEnd = screenWidth / 4
                gravity   = Gravity.START
            }
        }

        (context as? Activity)?.runOnUiThread {
            binding.llConversation.addView(bubble, params)
            binding.scrollConversation.post {
                binding.scrollConversation.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // ── Toast ─────────────────────────────────────────────────────────────

    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}