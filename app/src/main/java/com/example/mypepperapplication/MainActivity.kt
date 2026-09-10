package com.example.mypepperapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.example.mypepperapplication.core.AppConfig
import com.example.mypepperapplication.core.RobotManager
import com.example.mypepperapplication.core.RobotMode
import com.example.mypepperapplication.databinding.ActivityMainBinding
import com.example.mypepperapplication.ui.UiController
import com.example.mypepperapplication.vision.BoundingBox
import com.aldebaran.qi.sdk.`object`.human.Human
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
// ================================================================
// Main Activity
// ================================================================
/**
 * Entry point Android.
 * Responsabilità:
 *   1. Lifecycle Android + QiSDK
 *   2. Creazione di UiController e RobotManager
 *   3. Wiring UI → RobotManager tramite [bindUiToRobot]
 *   Resto : logica robot → RobotManager, logica UI → UiController.
 *
 *   COMANDI DI CONTROLLO
 *   adb shell am broadcast -a com.example.mypepperapplication.STOP_PLAN
 *   adb shell am broadcast -a com.example.mypepperapplication.EMERGENCY_STOP
 *   adb shell am broadcast -a com.example.mypepperapplication.RESET_ESTOP
 */
class MainActivity : AppCompatActivity(), RobotLifecycleCallbacks {
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_AUDIO = 100
        const val ACTION_EMERGENCY_STOP = "com.example.mypepperapplication.EMERGENCY_STOP"
        const val ACTION_RESET_ESTOP = "com.example.mypepperapplication.RESET_ESTOP"
        const val ACTION_START_PLAN = "com.example.mypepperapplication.START_PLAN"
        const val ACTION_STOP_PLAN  = "com.example.mypepperapplication.STOP_PLAN"
    }
    private lateinit var binding: ActivityMainBinding
    private lateinit var ui: UiController
    private lateinit var robotManager: RobotManager
    private var emergencyStopReceiver: BroadcastReceiver? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        QiSDK.register(this, this)

        ui = UiController(binding, this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO
            )
        }
        registerEmergencyStopReceiver()
    }

    override fun onDestroy() {
        emergencyStopReceiver?.let { unregisterReceiver(it) }
        emergencyStopReceiver = null
        if (::robotManager.isInitialized) robotManager.stopAll()
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    override fun onRobotFocusGained(ctx: QiContext) {
        Log.d(TAG, "onRobotFocusGained")

        robotManager = RobotManager(
            listener  = buildRobotListener(),
            context   = this,
            azureKey  = BuildConfig.AZURE_SPEECH_KEY,
            serverIp  = AppConfig.SERVER_IP
        ).apply {
            onRobotReady(ctx)
            detectionController.serverUrl = AppConfig.DETECTION_SERVER_URL
            onUserSpeechUi  = { text -> ui { ui.addUserMessage(text) } }
            onRobotSpeechUi = { text -> ui { ui.addRobotMessage(text) } }
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
            ) }
        ui.onApproachHuman = {
            ui { ui.showToast("Searching for human…") }
            robotManager.startApproachHuman() }
        ui.onStopApproachHuman = { robotManager.stopApproachHuman() }
        ui.onStopFollowHuman = { robotManager.stopFollowHuman() }
        ui.onFindHuman = {
            ui { ui.showToast("Searching for person…") }
            robotManager.startFindPerson() }
        ui.onStopFindHuman = { robotManager.stopFindPerson() }
        ui.onTrackObject = { label -> robotManager.startVisualServoing(label) }
        ui.onStopTracking = { robotManager.stopVisualServoing() }
        ui.onEmergencyStop = { robotManager.emergencyStop() }
        ui.onResetEmergencyStop = { robotManager.resetEmergencyStop() }
    }
    private fun registerEmergencyStopReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                Log.i(TAG, "Broadcast ricevuto: ${intent?.action}")
                if (!::robotManager.isInitialized) {
                    Log.w(TAG, "Broadcast ignored: robotManager not yet initialized")
                    return
                }
                when (intent?.action) {
                    ACTION_EMERGENCY_STOP -> {
                        Log.w(TAG, "EMERGENCY STOP received via adb broadcast")
                        robotManager.emergencyStop()
                    }
                    ACTION_RESET_ESTOP -> {
                        Log.i(TAG, "RESET received via adb broadcast")
                        robotManager.resetEmergencyStop()
                    }
                    ACTION_START_PLAN -> {
                        Log.i(TAG, "START PLAN received via adb broadcast")
                        robotManager.startPlan()
                    }
                    ACTION_STOP_PLAN -> {
                        Log.i(TAG, "STOP PLAN received via adb broadcast")
                        robotManager.stopPlan()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_EMERGENCY_STOP)
            addAction(ACTION_RESET_ESTOP)
            addAction(ACTION_START_PLAN)
            addAction(ACTION_STOP_PLAN)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        emergencyStopReceiver = receiver
    }
    private fun buildRobotListener() = object : RobotManager.RobotManagerListener {
        override fun onModeChanged(mode: RobotMode)                   = ui { ui.updateForMode(mode) }
        override fun onFollowingHuman()                               = ui { ui.showToast("Following the human…") }
        override fun onCloseEnoughToHuman()                           = ui { ui.showToast("I'm close! I'll stop") }
        override fun onCantReachHuman()                               = ui { ui.showToast("I cannot reach the human!") }
        override fun onDistanceChanged(meters: Double)                = ui { ui.updateDistance(meters) }
        override fun onServoingStarted(labels: List<String>)          = ui { ui.showToast("Searching: ${labels.joinToString(", ")}") }
        override fun onServoingStopped()                              = ui { ui.showToast("Visual Servoing stopped") }
        override fun onObjectCentered(label: String, box: BoundingBox) = ui { ui.showToast("Object found: $label") }
        override fun onObjectLost(labels: List<String>)               = ui { ui.showToast("Object lost: ${labels.joinToString(", ")}") }
        override fun onObjectsSpotted(spotted: List<BoundingBox>) { /*non presente in UI*/ }
        override fun onChargingFlapOpen()                             = ui { ui.showToast("Charging flap open — movement blocked") }
        override fun onPersonFound(human: Human)                      = ui { ui.showToast("Person found!") }
        override fun onPersonNotFound()                               = ui { ui.showToast("No person found") }
    }
    private fun ui(block: () -> Unit) = runOnUiThread(block)
}