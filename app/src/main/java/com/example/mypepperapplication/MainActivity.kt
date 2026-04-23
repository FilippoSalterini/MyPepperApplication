package com.example.mypepperapplication

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.example.mypepperapplication.controllers.PepperMovementController
import com.example.mypepperapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), RobotLifecycleCallbacks {

    private lateinit var binding: ActivityMainBinding
    private val movementController = PepperMovementController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        QiSDK.register(this, this)
        setupButtons()
    }

    private fun setupButtons() {
        binding.btnForward.setOnClickListener  { movementController.moveForward() }
        binding.btnBackward.setOnClickListener { movementController.moveBackward() }
        binding.btnLeft.setOnClickListener     { movementController.rotateLeft() }
        binding.btnRight.setOnClickListener    { movementController.rotateRight() }
        binding.btnStop.setOnClickListener     { movementController.stopMovement() }
    }

    override fun onRobotFocusGained(qiContext: QiContext?) {
        qiContext ?: return
        movementController.onRobotReady(qiContext)
        runOnUiThread { binding.tvStatus.text = "✓ Robot connesso" }
    }

    override fun onRobotFocusLost() {
        movementController.onRobotLost()
        runOnUiThread { binding.tvStatus.text = "Connessione persa" }
    }

    override fun onRobotFocusRefused(reason: String?) {
        runOnUiThread { binding.tvStatus.text = "Rifiutato: $reason" }
    }

    override fun onDestroy() {
        QiSDK.unregister(this, this)
        super.onDestroy()
    }
}