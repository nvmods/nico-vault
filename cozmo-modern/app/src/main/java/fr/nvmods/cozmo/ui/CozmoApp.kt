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
import androidx.compose.material3.LinearProgressIndicator
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
import fr.nvmods.cozmo.protocol.CubeInfo
import fr.nvmods.cozmo.personality.PersonalityLogEntry
import fr.nvmods.cozmo.personality.PersonalityMode
import fr.nvmods.cozmo.personality.PersonalityState

@Composable
fun CozmoApp(vm: CozmoViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val speechStatus by vm.speechStatus.collectAsState()
    val personalityState by vm.personalityState.collectAsState()
    val personalityLog by vm.personalityLog.collectAsState()
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
                        "Cozmo Modern 0.13.0",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Liaison stable + caméra + cubes + personnalité autonome",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TabRow(selectedTabIndex = tab) {
                    listOf("Pilotage", "Caméra", "Voix & cubes", "Personnalité").forEachIndexed { index, label ->
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
                    2 -> VoiceCubeTab(state, speechStatus, vm)
                    else -> PersonalityTab(
                        robotState = state,
                        state = personalityState,
                        log = personalityLog,
                        vm = vm
                    )
                }
            }
        }
    }
}

@Composable
private fun PilotageTab(state: CozmoState, vm: CozmoViewModel) {
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
                ControlButton(
                    Icons.Default.ArrowUpward,
                    "Avancer",
                    vm::forward,
                    Modifier.weight(1f)
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton(
                    Icons.Default.KeyboardArrowLeft,
                    "Gauche",
                    vm::left,
                    Modifier.weight(1f)
                )
                ControlButton(
                    Icons.Default.Stop,
                    "STOP",
                    vm::stop,
                    Modifier.weight(1f)
                )
                ControlButton(
                    Icons.Default.KeyboardArrowRight,
                    "Droite",
                    vm::right,
                    Modifier.weight(1f)
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                ControlButton(
                    Icons.Default.ArrowDownward,
                    "Reculer",
                    vm::backward,
                    Modifier.weight(1f)
                )
            }
        }

        ControlCard("Tête et lift") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton(
                    Icons.Default.ArrowUpward,
                    "Tête +",
                    vm::headUp,
                    Modifier.weight(1f)
                )
                ControlButton(
                    Icons.Default.ArrowDownward,
                    "Tête −",
                    vm::headDown,
                    Modifier.weight(1f)
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton(
                    Icons.Default.ArrowUpward,
                    "Lift +",
                    vm::liftUp,
                    Modifier.weight(1f)
                )
                ControlButton(
                    Icons.Default.ArrowDownward,
                    "Lift −",
                    vm::liftDown,
                    Modifier.weight(1f)
                )
            }

            Button(
                onClick = vm::stop,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Arrêter les moteurs")
            }
        }

        ControlCard("Éclairage") {
            ToggleRow(
                "LED infrarouge caméra",
                state.headLightEnabled,
                vm::headLight
            )

            Text(
                "État IR conservé entre les onglets et réappliqué après activation caméra.",
                style = MaterialTheme.typography.bodySmall
            )

            Text("Backpack", fontWeight = FontWeight.Medium)
            ColorButtons(
                current = state.backpackColor,
                onColor = vm::backpack
            )

            Text(
                "Les changements rapides sont coalescés avant envoi pour éviter de saturer la liaison.",
                style = MaterialTheme.typography.bodySmall
            )
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

            ToggleRow(
                "LED IR",
                state.headLightEnabled,
                vm::headLight
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
                "L'état IR est commun aux écrans : plus de reset visuel lors d'un changement d'onglet.",
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
    var pitch by remember { mutableFloatStateOf(1.25f) }
    var rate by remember { mutableFloatStateOf(0.90f) }

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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { french = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (french) "✓ Français" else "Français")
                }

                FilledTonalButton(
                    onClick = { french = false },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (!french) "✓ Anglais" else "Anglais")
                }
            }

            Text("Pitch : " + "%.2f".format(pitch))
            Slider(
                value = pitch,
                onValueChange = { pitch = it },
                valueRange = 0.70f..1.80f
            )

            Text("Vitesse : " + "%.2f".format(rate))
            Slider(
                value = rate,
                onValueChange = { rate = it },
                valueRange = 0.55f..1.50f
            )

            Button(
                onClick = {
                    vm.speak(
                        text = text,
                        french = french,
                        pitch = pitch,
                        rate = rate
                    )
                },
                enabled = state.connection == ConnectionState.READY &&
                    !state.audioStreaming,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.audioStreaming) {
                        "Lecture…"
                    } else {
                        "Faire parler Cozmo"
                    }
                )
            }

            Text("État : " + speechStatus)

            Text(
                "Le TTS est envoyé sans traitement audio artificiel ; le codec et le timing sont adaptés au format natif de Cozmo. Pitch/vitesse restent réglables.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        CubeManagerPanel(
            state = state,
            vm = vm
        )

        Text(
            "Les prochaines briques lourdes seront visages/animations et comportements. La couche réseau est maintenant séparée et acquittée.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PersonalityTab(
    robotState: CozmoState,
    state: PersonalityState,
    log: List<PersonalityLogEntry>,
    vm: CozmoViewModel
) {
    ScrollColumn {
        ControlCard("Moteur de personnalité — réel") {
            ToggleRow(
                "Activer l'autonomie",
                state.enabled,
                vm::personalityEnabled
            )

            Text(
                "Les réactions utilisent le vrai robot. Le pilotage manuel a toujours priorité et suspend l'autonomie jusqu'au bouton STOP.",
                style = MaterialTheme.typography.bodySmall
            )

            Text("Mode", fontWeight = FontWeight.Medium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PersonalityMode.entries.forEach { mode ->
                    FilledTonalButton(
                        onClick = { vm.personalityMode(mode) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (state.mode == mode) "✓ " + mode.name
                            else mode.name
                        )
                    }
                }
            }
        }

        ControlCard("État interne") {
            PersonalityMeter("Bonheur", state.happiness)
            PersonalityMeter("Curiosité", state.curiosity)
            PersonalityMeter("Énergie", state.energy)
            PersonalityMeter("Frustration", state.frustration)
            PersonalityMeter("Confiance", state.confidence)

            Text("Dernier stimulus : " + state.lastStimulus)
            Text("Dernière décision : " + state.lastDecision)
            Text(
                "Interactions : " + state.interactions +
                    "  •  ticks : " + state.idleTicks,
                style = MaterialTheme.typography.bodySmall
            )
        }

        ControlCard("Capteurs du châssis — diagnostic") {
            Text(
                "Orientation : " + robotState.chassisOrientation.name,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                "Soulevé=" + yesNo(robotState.pickedUp) +
                    " • chute=" + yesNo(robotState.falling) +
                    " • bord=" + yesNo(robotState.cliffDetected)
            )

            Text(
                "Chargeur=" + yesNo(robotState.onCharger) +
                    " • charge=" + yesNo(robotState.charging) +
                    " • roues=" + yesNo(robotState.wheelsMoving)
            )

            Text(
                "Cap (yaw)=" + format2(robotState.poseAngleRad) +
                    " rad • pitch=" + format2(robotState.posePitchRad) + " rad"
            )

            Text(
                "Roues : G " + format1(robotState.leftWheelSpeedMmps) +
                    " / D " + format1(robotState.rightWheelSpeedMmps) +
                    " mm/s"
            )

            Text(
                "Accéléro : X " + format2(robotState.accelX) +
                    " • Y " + format2(robotState.accelY) +
                    " • Z " + format2(robotState.accelZ)
            )

            Text(
                "Gyro : X " + format2(robotState.gyroX) +
                    " • Y " + format2(robotState.gyroY) +
                    " • Z " + format2(robotState.gyroZ)
            )

            Text(
                "Cliff raw : " +
                    robotState.cliffRaw.joinToString(" / ")
            )

            Text(
                "Touch backpack raw : " +
                    (robotState.backpackTouchRaw?.toString() ?: "—")
            )

            Text(
                "Orientation : dos/face via pitch, côtés via l'accélération Y. Le cap yaw n'intervient plus dans l'orientation.",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "Le tactile backpack reste diagnostic seulement : il n'est pas utilisé par la personnalité tant qu'il n'est pas validé sur ce robot.",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "Test utile : soulève Cozmo, pose-le sur le dos/côté puis approche doucement un bord sans le laisser tomber. Les valeurs doivent changer immédiatement.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        ControlCard("Tests de perception") {
            Text(
                "Les cubes alimentent déjà automatiquement le moteur. Ces boutons restent utiles pour tester les autres réactions avant leurs capteurs réels.",
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = { vm.personalityFace("Nico") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Visage")
                }
                FilledTonalButton(
                    onClick = vm::personalityPickedUp,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Soulevé")
                }
                FilledTonalButton(
                    onClick = vm::personalityPutDown,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reposé")
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = vm::personalityTouched,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Touché")
                }
                FilledTonalButton(
                    onClick = vm::personalityInteract,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Interaction")
                }
                FilledTonalButton(
                    onClick = vm::personalityBatteryLow,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Batterie basse")
                }
            }
        }

        ControlCard("Journal des décisions") {
            if (log.isEmpty()) {
                Text("Aucune décision pour le moment.")
            } else {
                log.take(12).forEach { entry ->
                    Text(
                        entry.event + " → " + entry.decision,
                        fontWeight = FontWeight.Medium
                    )

                    if (entry.actions.isNotEmpty()) {
                        Text(
                            entry.actions.joinToString(" • ") { action ->
                                action::class.simpleName ?: action.toString()
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalityMeter(
    label: String,
    value: Float
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label + " : " + (value * 100).toInt() + " %")
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ColorButtons(
    current: BackpackColor?,
    onColor: (BackpackColor) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ColorButton("Rouge", BackpackColor.RED, current, onColor, Modifier.weight(1f))
        ColorButton("Vert", BackpackColor.GREEN, current, onColor, Modifier.weight(1f))
        ColorButton("Bleu", BackpackColor.BLUE, current, onColor, Modifier.weight(1f))
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ColorButton("Blanc", BackpackColor.WHITE, current, onColor, Modifier.weight(1f))
        ColorButton("Off", BackpackColor.OFF, current, onColor, Modifier.weight(1f))
    }
}

@Composable
private fun ColorButton(
    label: String,
    color: BackpackColor,
    current: BackpackColor?,
    onColor: (BackpackColor) -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = { onColor(color) },
        modifier = modifier
    ) {
        Text(
            if (current == color) {
                "✓ " + label
            } else {
                label
            }
        )
    }
}

private fun yesNo(value: Boolean): String =
    if (value) "oui" else "non"

private fun format1(value: Float?): String =
    value?.let { "%.1f".format(it) } ?: "—"

private fun format2(value: Float?): String =
    value?.let { "%.2f".format(it) } ?: "—"

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
            Text(
                "État : " + state.connection,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                "Batterie : " +
                    (state.batteryVoltage?.let {
                        "%.2f V".format(it)
                    } ?: "—")
            )

            Text(
                "Tête : " +
                    (state.headAngleRad?.let {
                        "%.1f°".format(
                            CozmoConnection.radToDeg(it)
                        )
                    } ?: "—")
            )

            Text(
                "Lift : " +
                    (state.liftHeightMm?.let {
                        "%.1f mm".format(it)
                    } ?: "—")
            )

            Text(
                "Paquets RX/TX : " +
                    state.packetsReceived +
                    " / " +
                    state.packetsSent
            )

            Text(
                "Retransmissions TX : " + state.txRetries,
                style = MaterialTheme.typography.bodySmall
            )

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
            Text(
                title,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    action: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = action,
        modifier = modifier
    ) {
        Icon(
            icon,
            contentDescription = null
        )
        Text(" " + label)
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
