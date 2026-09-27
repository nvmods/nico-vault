package fr.nvmods.cozmo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.ConnectionState
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoState

@Composable
fun CozmoApp(vm: CozmoViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val speechStatus by vm.speechStatus.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

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
                    .fillMaxSize()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        "Cozmo Modern 0.5",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Android moderne — sans libcozmoEngine / Acapela",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TabRow(selectedTabIndex = tab) {
                    listOf("Pilotage", "Caméra", "Voix & cubes").forEachIndexed { index, label ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(label) }
                        )
                    }
                }

                when (tab) {
                    0 -> PilotageTab(state, vm)
                    1 -> CameraTab(state, vm)
                    else -> VoiceCubeTab(state, speechStatus, vm)
                }
            }
        }
    }
}

@Composable
private fun PilotageTab(state: CozmoState, vm: CozmoViewModel) {
    var ir by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0.65f) }

    ScrollColumn {
        StatusCard(state)

        if (state.connection == ConnectionState.DISCONNECTED) {
            Button(
                onClick = vm::connect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                Text("  Connecter Cozmo")
            }
            Text(
                "Le téléphone doit être connecté au Wi-Fi COZMO_xxxxxx.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            FilledTonalButton(
                onClick = vm::disconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Déconnecter")
            }
        }

        ControlCard("Chenilles") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
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

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
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

            Button(
                onClick = vm::stop,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Arrêter les moteurs")
            }
        }

        ControlCard("Éclairage") {
            ToggleRow("LED infrarouge caméra", ir) {
                ir = it
                vm.headLight(it)
            }

            Text("Backpack", fontWeight = FontWeight.Medium)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilledTonalButton(onClick = { vm.backpack(BackpackColor.RED) }) {
                    Text("Rouge")
                }
                FilledTonalButton(onClick = { vm.backpack(BackpackColor.GREEN) }) {
                    Text("Vert")
                }
                FilledTonalButton(onClick = { vm.backpack(BackpackColor.BLUE) }) {
                    Text("Bleu")
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilledTonalButton(onClick = { vm.backpack(BackpackColor.WHITE) }) {
                    Text("Blanc")
                }
                FilledTonalButton(onClick = { vm.backpack(BackpackColor.OFF) }) {
                    Text("Off")
                }
            }
        }

        ControlCard("Volume robot") {
            Text(((volume * 100).toInt()).toString() + " %")

            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { vm.volume(volume) }
            )
        }
    }
}

@Composable
private fun CameraTab(state: CozmoState, vm: CozmoViewModel) {
    ScrollColumn {
        ControlCard("Caméra de Cozmo") {
            ToggleRow(
                "Flux 320 × 240",
                state.cameraEnabled,
                vm::camera
            )

            val bitmap = state.cameraBitmap

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Caméra Cozmo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
                    contentScale = ContentScale.Fit
                )
                Text(state.cameraFrames.toString() + " image(s) décodée(s)")
            } else {
                Text("Active le flux : la première image doit apparaître ici.")
                Spacer(Modifier.height(120.dp))
            }

            Text(
                "La LED de tête est infrarouge : dans une pièce sombre, compare IR OFF/ON directement avec ce flux.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun VoiceCubeTab(
    state: CozmoState,
    speechStatus: String,
    vm: CozmoViewModel
) {
    var text by remember { mutableStateOf("Bonjour, je suis Cozmo !") }
    var french by remember { mutableStateOf(true) }

    ScrollColumn {
        ControlCard("Voix — expérimental") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Texte à faire dire") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilledTonalButton(onClick = { french = true }) {
                    Text(if (french) "✓ Français" else "Français")
                }

                FilledTonalButton(onClick = { french = false }) {
                    Text(if (!french) "✓ Anglais" else "Anglais")
                }
            }

            Button(
                onClick = { vm.speak(text, french) },
                enabled = state.connection == ConnectionState.READY && !state.audioStreaming,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.audioStreaming) "Lecture…" else "Faire parler Cozmo")
            }

            Text("État : " + speechStatus)

            Text(
                "Android synthétise le PCM puis l'envoie au haut-parleur de Cozmo en 22,05 kHz.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        ControlCard("Cubes") {
            ToggleRow(
                "Recherche / connexion automatique",
                state.cubeDiscovery,
                vm::discoverCubes
            )

            if (state.cubes.isEmpty()) {
                Text("Aucun cube détecté pour le moment.")
            } else {
                state.cubes.forEachIndexed { index, cube ->
                    Text(
                        "Cube " + (index + 1) + " — 0x" + cube.factoryId.toString(16),
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        "type=" + cube.objectType +
                            "  RSSI=" + (cube.rssi?.toString() ?: "—") +
                            "  connecté=" + (if (cube.connected) "oui" else "non") +
                            "  batterie=" + (cube.batteryLevel?.toString() ?: "—")
                    )
                }
            }
        }

        Text(
            "Les animations/visages seront ajoutés après validation caméra + audio : ils utilisent le même flux temps réel 30 FPS.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ScrollColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(state: CozmoState) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("État : " + state.connection, fontWeight = FontWeight.SemiBold)
            Text("Batterie : " + (state.batteryVoltage?.let { "%.2f V".format(it) } ?: "—"))
            Text(
                "Tête : " +
                    (state.headAngleRad?.let { "%.1f°".format(CozmoConnection.radToDeg(it)) } ?: "—")
            )
            Text("Lift : " + (state.liftHeightMm?.let { "%.1f mm".format(it) } ?: "—"))
            Text("Paquets RX/TX : " + state.packetsReceived + " / " + state.packetsSent)

            if (state.lastError != null) {
                Text(
                    "Erreur : " + state.lastError,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ControlCard(
    title: String,
    content: @Composable () -> Unit
) {
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
    icon: ImageVector,
    label: String,
    action: () -> Unit
) {
    FilledTonalButton(onClick = action) {
        Icon(icon, contentDescription = null)
        Text("  " + label)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
