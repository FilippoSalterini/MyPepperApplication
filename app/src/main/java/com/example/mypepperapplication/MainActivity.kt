package com.example.mypepperapplication

// ─────────────────────────────
// 1. Android base
// ─────────────────────────────
import android.os.Bundle

// ─────────────────────────────
// 2. Activity / lifecycle
// ─────────────────────────────
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

// ─────────────────────────────
// 3. Compose UI core
// ─────────────────────────────
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// ─────────────────────────────
// 4. Compose layout
// ─────────────────────────────
import androidx.compose.foundation.layout.*

// ─────────────────────────────
// 5. Material 3
// ─────────────────────────────
import androidx.compose.material3.*

// ─────────────────────────────
// 6. Theme progetto
// ─────────────────────────────
import com.example.mypepperapplication.ui.theme.MyPepperApplicationTheme
import com.example.mypepperapplication.Direction

// ─────────────────────────────
// 7. QiSDK (Pepper)
// ─────────────────────────────
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.aldebaran.qi.sdk.`object`.conversation.Say

class MainActivity : ComponentActivity(), RobotLifecycleCallbacks {

    private val message = mutableStateOf("Waiting for instructions...")
    private val movementController = PepperMovementController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QiSDK.register(this, this)
        enableEdgeToEdge()
        setContent {
            MyPepperApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        message = message.value,
                        modifier = Modifier.padding(innerPadding),
                        // a questo punto passo ai bottoni le relative azioni
                        onDirectionPressed = { direction ->
                            when (direction) {
                                Direction.FORWARD -> movementController.moveForward()
                                Direction.BACKWARD -> movementController.moveBackward()
                                Direction.LEFT -> movementController.moveLeft()
                                Direction.RIGHT -> movementController.moveRight()
                                Direction.STOP -> movementController.stopMovement()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    override fun onRobotFocusGained(qiContext: QiContext) {
        movementController.onRobotReady(qiContext)
        val text = "Hello Human!"

        runOnUiThread {
            message.value = text
        }
        val say: Say = SayBuilder.with(qiContext)
            .withText(text)
            .build()

        say.run()
    }

    override fun onRobotFocusLost() {
        movementController.onRobotLost() // ← NUOVO
    }

    override fun onRobotFocusRefused(reason: String?) {
    }
}

@Composable
fun Greeting(message: String, modifier: Modifier = Modifier, onDirectionPressed: (Direction) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        // rivedi il text qua
        Text(
            text = "Pepper Assistant 🤖",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = "Message",
                    style = MaterialTheme.typography.labelMedium
                )

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        AssistChip(
            onClick = {},
            label = { Text("Status: active") }
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Controllo movimento",
            style = MaterialTheme.typography.labelMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        DirectionControls(
            onDirectionPressed = onDirectionPressed,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyPepperApplicationTheme {
        Greeting("Android")
    }
}