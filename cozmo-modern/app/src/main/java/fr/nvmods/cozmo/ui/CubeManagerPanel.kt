package fr.nvmods.cozmo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.CozmoState
import fr.nvmods.cozmo.protocol.CubeInfo

@Composable
internal fun CubeManagerPanel(
    state: CozmoState,
    vm: CozmoViewModel
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Cubes BLE",
                fontWeight = FontWeight.SemiBold
            )

            ToggleLine(
                label = "Connexion automatique des cubes",
                checked = state.cubeDiscovery,
                onChanged = vm::discoverCubes
            )

            val known = state.cubes
            val connected = known.filter {
                it.connected && it.objectId != null
            }

            // Une fois un cube connu, conserver ce bloc à l'écran même si
            // le BLE signale une micro-coupure : aucun saut vertical de l'UI.
            if (known.isNotEmpty()) {
                val allAccel =
                    connected.isNotEmpty() &&
                        connected.all { it.accelStreaming }

                ToggleLine(
                    label = "Accéléromètres bruts (30 ms) — tous",
                    checked = allAccel,
                    enabled = connected.isNotEmpty(),
                    onChanged = vm::allCubeAccel
                )

                Text(
                    "Commandes globales",
                    fontWeight = FontWeight.Medium
                )

                CubeColorRow(
                    enabled = connected.isNotEmpty(),
                    onColor = vm::allCubeColor
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            vm.allCubePairPattern(
                                BackpackColor.RED,
                                BackpackColor.BLUE
                            )
                        },
                        enabled = connected.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1/3 R • 2/4 B")
                    }

                    FilledTonalButton(
                        onClick = {
                            vm.allCubePairPattern(
                                BackpackColor.GREEN,
                                BackpackColor.OFF
                            )
                        },
                        enabled = connected.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1/3 V • 2/4 off")
                    }
                }
            }

            if (state.cubes.isEmpty()) {
                Text(
                    "Aucun LightCube annoncé pour le moment.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                state.cubes.forEach { cube ->
                    key(cube.factoryId) {
                        CubeDetailCard(
                            cube = cube,
                            vm = vm
                        )
                    }
                }
            }

            Text(
                "Le gestionnaire connecte désormais les cubes 1 → 2 → 3 séquentiellement. " +
                    "factory_id = identité permanente ; object_id = identifiant temporaire de la connexion BLE.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CubeDetailCard(
    cube: CubeInfo,
    vm: CozmoViewModel
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                cube.displayName +
                    " — factory 0x" +
                    cube.factoryId.toString(16),
                fontWeight = FontWeight.SemiBold
            )

            Text(
                "type=" + cube.objectType +
                    " • object_id=" + (cube.objectId?.toString() ?: "—") +
                    " • RSSI=" + (cube.rssi?.toString() ?: "—")
            )

            Text(
                "BLE=" + (if (cube.connected) "connecté" else "déconnecté") +
                    " • essais=" + cube.connectAttempts +
                    " • coupures=" + cube.disconnectCount +
                    " • batterie=" + (cube.batteryLevel?.toString() ?: "—") +
                    " • paquets manqués=" + (cube.missedPackets?.toString() ?: "—"),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "Dernier événement : " + cube.lastEvent,
                style = MaterialTheme.typography.bodySmall
            )

            val objectId = cube.objectId
            val commandable = cube.connected && objectId != null

            Text(
                if (commandable) {
                    "Liaison cube prête"
                } else {
                    "Liaison cube inactive — commandes verrouillées"
                },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (commandable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
            )

            Text(
                "4 LEDs indépendantes",
                fontWeight = FontWeight.Medium
            )

            CubeColorRow(
                enabled = commandable,
                onColor = { color ->
                    vm.cubeColor(cube.factoryId, color)
                }
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(4) { index ->
                    val current =
                        cube.ledColors.getOrElse(index) {
                            BackpackColor.OFF
                        }

                    FilledTonalButton(
                        onClick = {
                            vm.cubeCornerColor(
                                factoryId = cube.factoryId,
                                corner = index,
                                color = nextColor(current)
                            )
                        },
                        enabled = commandable,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "L" + (index + 1) +
                                " " + shortColor(current)
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        vm.cubePairPattern(
                            cube.factoryId,
                            BackpackColor.RED,
                            BackpackColor.BLUE
                        )
                    },
                    enabled = commandable,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("R/B alterné")
                }

                FilledTonalButton(
                    onClick = {
                        vm.cubePairPattern(
                            cube.factoryId,
                            BackpackColor.GREEN,
                            BackpackColor.OFF
                        )
                    },
                    enabled = commandable,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("1/3 vert")
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        vm.cubeChaser(
                            cube.factoryId,
                            BackpackColor.BLUE
                        )
                    },
                    enabled = commandable,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Chaser bleu")
                }

                FilledTonalButton(
                    onClick = {
                        vm.stopCubeChaser(cube.factoryId)
                    },
                    enabled = commandable,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("LEDs off")
                }
            }

            ToggleLine(
                label = "Flux accéléromètre",
                checked = cube.accelStreaming,
                enabled = commandable,
                onChanged = { enabled ->
                    vm.cubeAccel(cube.factoryId, enabled)
                }
            )

            Text(
                "Accel X/Y/Z : " +
                    fmt(cube.accelX) + " / " +
                    fmt(cube.accelY) + " / " +
                    fmt(cube.accelZ)
            )

            Text(
                "Face haute : " + cube.upAxis.label +
                    " • mouvement : " +
                    (if (cube.moving) "oui" else "non") +
                    " • taps : " + cube.tapCount +
                    " • intensité : " +
                    (cube.tapIntensity?.toString() ?: "—"),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CubeColorRow(
    onColor: (BackpackColor) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ColorButton(
            "R",
            BackpackColor.RED,
            onColor,
            enabled,
            Modifier.weight(1f)
        )
        ColorButton(
            "V",
            BackpackColor.GREEN,
            onColor,
            enabled,
            Modifier.weight(1f)
        )
        ColorButton(
            "B",
            BackpackColor.BLUE,
            onColor,
            enabled,
            Modifier.weight(1f)
        )
        ColorButton(
            "W",
            BackpackColor.WHITE,
            onColor,
            enabled,
            Modifier.weight(1f)
        )
        ColorButton(
            "Off",
            BackpackColor.OFF,
            onColor,
            enabled,
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun ColorButton(
    label: String,
    color: BackpackColor,
    onColor: (BackpackColor) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = { onColor(color) },
        enabled = enabled,
        modifier = modifier
    ) {
        Text(label)
    }
}

@Composable
private fun ToggleLine(
    label: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            enabled = enabled
        )
    }
}

private fun nextColor(
    current: BackpackColor
): BackpackColor =
    when (current) {
        BackpackColor.OFF -> BackpackColor.RED
        BackpackColor.RED -> BackpackColor.GREEN
        BackpackColor.GREEN -> BackpackColor.BLUE
        BackpackColor.BLUE -> BackpackColor.WHITE
        BackpackColor.WHITE -> BackpackColor.OFF
    }

private fun shortColor(
    color: BackpackColor
): String =
    when (color) {
        BackpackColor.OFF -> "off"
        BackpackColor.RED -> "R"
        BackpackColor.GREEN -> "V"
        BackpackColor.BLUE -> "B"
        BackpackColor.WHITE -> "W"
    }

private fun fmt(value: Float?): String =
    value?.let { "%.2f".format(it) } ?: "—"
