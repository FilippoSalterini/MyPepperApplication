package com.example.mypepperapplication

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mypepperapplication.ui.theme.MyPepperApplicationTheme

// Enum rappresenta le 5 possibili direzioni di pepper (in caso implementa anche movimento diagonale)
enum class Direction {
    FORWARD, BACKWARD, LEFT, RIGHT, STOP
}
// qui non avviene il movimento, viene passato solo un evento
// cioè "è stato premuto un bottone - dimmmi quale
@Composable
fun DirectionControls(
    onDirectionPressed: (Direction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // Riga 1 — Avanti
        Row(horizontalArrangement = Arrangement.Center) {
            DirectionButton(label = "▲", description = "Forward") {
                onDirectionPressed(Direction.FORWARD)
            }
        }

        // Riga 2 — Sinistra, Stop, Destra
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DirectionButton(label = "◀", description = "Left") {
                onDirectionPressed(Direction.LEFT)
            }
            DirectionButton(label = "⏹", description = "Stop", isStop = true) {
                onDirectionPressed(Direction.STOP)
            }
            DirectionButton(label = "▶", description = "Right") {
                onDirectionPressed(Direction.RIGHT)
            }
        }

        // Riga 3 — Indietro
        Row(horizontalArrangement = Arrangement.Center) {
            DirectionButton(label = "▼", description = "Backward") {
                onDirectionPressed(Direction.BACKWARD)
            }
        }
    }
}

@Composable
fun DirectionButton(
    label: String,
    description: String,
    isStop: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(80.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isStop)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, fontSize = 20.sp)
            Text(text = description, fontSize = 9.sp)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DirectionControlsPreview() {
    MyPepperApplicationTheme {
        DirectionControls(onDirectionPressed = {})
    }
}