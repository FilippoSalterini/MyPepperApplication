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

    private var qiContext: QiContext? = null
    private lateinit var binding: ActivityMainBinding
    private val movementController = PepperMovementController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "REGISTER BEFORE UI")

        QiSDK.register(this, this)

        Log.d("MainActivity", "QiSDK.register chiamato")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupButtons()
    }

    private fun setupButtons() {
        binding.btnForward.setOnClickListener  { movementController.moveForward() }
        binding.btnBackward.setOnClickListener { movementController.moveBackward() }
        binding.btnLeft.setOnClickListener     { movementController.rotateLeft() }
        binding.btnRight.setOnClickListener    { movementController.rotateRight() }
        binding.btnStop.setOnClickListener     { movementController.stopMovement() }
    }

    override fun onRobotFocusGained(qiContext: QiContext) {
        Log.d("MainActivity", "GAINED CALLBACK")
        this.qiContext = qiContext
        Log.d("MainActivity", "Robot focus gained")
        movementController.onRobotReady(qiContext)
    }

    override fun onRobotFocusLost() {
        this.qiContext = null
        Log.d("MainActivity", "Robot lost focus")
        movementController.onRobotLost()
    }

    override fun onRobotFocusRefused(reason: String?) {
        Log.d("MainActivity", "Robot focus refused: $reason")
    }

    override fun onDestroy() {
        QiSDK.unregister(this, this)
        Log.d("MainActivity", "Unregistered")
        super.onDestroy()
    }
}