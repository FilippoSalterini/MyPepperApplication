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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QiSDK.register(this, this)
        enableEdgeToEdge()
        setContent {
            MyPepperApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        message = message.value,
                        modifier = Modifier.padding(innerPadding)
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

        val text = "Ciao sono molto felice di essere al RICE lab."

        runOnUiThread {
            message.value = text
        }
        val say: Say = SayBuilder.with(qiContext)
            .withText(text)
            .build()

        say.run()
    }

    override fun onRobotFocusLost() {
    }

    override fun onRobotFocusRefused(reason: String?) {
    }
}

@Composable
fun Greeting(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {

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
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyPepperApplicationTheme {
        Greeting("Android")
    }
}