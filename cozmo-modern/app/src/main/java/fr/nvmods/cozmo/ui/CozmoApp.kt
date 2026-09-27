package fr.nvmods.cozmo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.nvmods.cozmo.protocol.ConnectionState
import fr.nvmods.cozmo.protocol.CozmoConnection

@Composable
fun CozmoApp(vm: CozmoViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var headLight by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Cozmo Modern",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "V0 — protocole natif moderne, sans libcozmoEngine ni Acapela",
                    style = MaterialTheme.typography.bodyMedium
                )

                StatusCard(
                    connection = state.connection,
                    battery = state.batteryVoltage,
                    head = state.headAngleRad,
                    lift = state.liftHeightMm,
                    rx = state.packetsReceived,
                    tx = state.packetsSent,
                    error = state.lastError
                )

                if (state.connection == ConnectionState.DISCONNECTED) {
                    Button(onClick = vm::connect, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Text("  Connecter Cozmo")
                    }
                    Text(
                        "Connecte d’abord le téléphone au Wi‑Fi COZMO_xxxxxx, puis touche Connecter.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    FilledTonalButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) {
                        Text("Déconnecter")
                    }
                }

                ControlCard("Chenilles") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        ControlButton(Icons.Default.ArrowUpward, "Avancer", vm::forward)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ControlButton(Icons.Default.KeyboardArrowLeft, "Gauche", vm::left)
                        ControlButton(Icons.Default.Stop, "STOP", vm::stop)
                        ControlButton(Icons.Default.KeyboardArrowRight, "Droite", vm::right)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        ControlButton(Icons.Default.ArrowDownward, "Reculer", vm::backward)
                    }
                }

                ControlCard("Tête et lift") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ControlButton(Icons.Default.ArrowUpward, "Tête +", vm::headUp)
                        ControlButton(Icons.Default.ArrowDownward, "Tête −", vm::headDown)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ControlButton(Icons.Default.ArrowUpward, "Lift +", vm::liftUp)
                        ControlButton(Icons.Default.ArrowDownward, "Lift −", vm::liftDown)
                    }
                    Button(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) {
                        Text("Arrêter les moteurs")
                    }
                }

                ControlCard("Fonctions") {
                    ToggleRow("Phare tête", headLight) {
                        headLight = it
                        vm.headLight(it)
                    }
                    ToggleRow("Flux caméra (réception à venir)", camera) {
                        camera = it
                        vm.camera(it)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Étape suivante : décodage caméra, cubes, animations puis TTS PCM vers le robot.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    connection: ConnectionState,
    battery: Float?,
    head: Float?,
    lift: Float?,
    rx: Long,
    tx: Long,
    error: String?
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("État : $connection", fontWeight = FontWeight.SemiBold)
            Text("Batterie : ${battery?.let { "%.2f V".format(it) } ?: "—"}")
            Text("Tête : ${head?.let { "%.1f°".format(CozmoConnection.radToDeg(it)) } ?: "—"}")
            Text("Lift : ${lift?.let { "%.1f mm".format(it) } ?: "—"}")
            Text("Paquets RX/TX : $rx / $tx")
            if (error != null) Text("Erreur : $error", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    action: () -> Unit
) {
    FilledTonalButton(onClick = action) {
        Icon(icon, contentDescription = null)
        Text("  $label")
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
