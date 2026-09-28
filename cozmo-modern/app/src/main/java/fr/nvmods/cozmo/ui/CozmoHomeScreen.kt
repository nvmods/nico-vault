package fr.nvmods.cozmo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nvmods.cozmo.R
import fr.nvmods.cozmo.personality.OriginalBehaviorLabels
import fr.nvmods.cozmo.personality.PersonalityState
import fr.nvmods.cozmo.protocol.ConnectionState
import fr.nvmods.cozmo.protocol.CozmoState

enum class CozmoSection {
    HOME,
    CONTROL,
    CAMERA,
    VOICE_CUBES,
    DIAGNOSTICS
}

val CozmoYellow = Color(0xFFFFCC20)
val CozmoCyan = Color(0xFF20BED0)
val CozmoGraphite = Color(0xFF272B2E)
val CozmoCream = Color(0xFFF7F4EC)

@Composable
fun CozmoHomeScreen(
    robotState: CozmoState,
    personalityState: PersonalityState,
    vm: CozmoViewModel,
    onOpen: (CozmoSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CozmoCream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HomeHeader(
            robotState = robotState,
            onDiagnostic = { onOpen(CozmoSection.DIAGNOSTICS) }
        )

        PersonalityHero(
            robotState = robotState,
            personalityState = personalityState,
            vm = vm
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeTile(
                title = "Piloter",
                subtitle = "Chenilles, tête et lift",
                icon = Icons.Default.Gamepad,
                modifier = Modifier.weight(1f),
                onClick = { onOpen(CozmoSection.CONTROL) }
            )
            HomeTile(
                title = "Caméra",
                subtitle = "Voir ce qu'il voit",
                icon = Icons.Default.CameraAlt,
                modifier = Modifier.weight(1f),
                onClick = { onOpen(CozmoSection.CAMERA) }
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeTile(
                title = "Voix & cubes",
                subtitle = "Parler et interagir",
                icon = Icons.Default.GraphicEq,
                modifier = Modifier.weight(1f),
                onClick = { onOpen(CozmoSection.VOICE_CUBES) }
            )
            HomeTile(
                title = "Diagnostic",
                subtitle = "Capteurs et détails",
                icon = Icons.Default.DeveloperMode,
                modifier = Modifier.weight(1f),
                onClick = { onOpen(CozmoSection.DIAGNOSTICS) }
            )
        }

        Text(
            "Personnalité basée sur les données Cozmo 3.6.6 • " +
                personalityState.behaviorSource,
            style = MaterialTheme.typography.labelSmall,
            color = CozmoGraphite.copy(alpha = 0.58f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HomeHeader(
    robotState: CozmoState,
    onDiagnostic: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "COZMO",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = CozmoGraphite,
            letterSpacing = 2.sp
        )

        ConnectionBadge(robotState)

        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(42.dp)
                .clickable(onClick = onDiagnostic),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Diagnostic",
                tint = CozmoGraphite
            )
        }
    }
}

@Composable
private fun ConnectionBadge(state: CozmoState) {
    val ready = state.connection == ConnectionState.READY
    Row(
        modifier = Modifier
            .background(
                if (ready) CozmoCyan.copy(alpha = 0.14f)
                else CozmoGraphite.copy(alpha = 0.08f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            if (ready) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (ready) CozmoCyan else CozmoGraphite.copy(alpha = 0.65f)
        )

        val batteryIcon = when {
            state.batteryVoltage == null -> Icons.Default.BatteryStd
            state.batteryVoltage < 3.55f -> Icons.Default.BatteryAlert
            else -> Icons.Default.BatteryFull
        }
        Icon(
            batteryIcon,
            contentDescription = "Batterie",
            modifier = Modifier.size(18.dp),
            tint = if (state.batteryVoltage != null && state.batteryVoltage < 3.55f) {
                MaterialTheme.colorScheme.error
            } else {
                CozmoGraphite.copy(alpha = 0.72f)
            }
        )
    }
}

@Composable
private fun PersonalityHero(
    robotState: CozmoState,
    personalityState: PersonalityState,
    vm: CozmoViewModel
) {
    val drawable = when {
        personalityState.cubeVisible -> R.drawable.mood_cube
        personalityState.happiness >= 0.72f -> R.drawable.mood_happy
        else -> R.drawable.mood_explore
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(drawable),
                contentDescription = null,
                modifier = Modifier.size(154.dp)
            )

            Text(
                OriginalBehaviorLabels.activity(personalityState.originalActivity),
                style = MaterialTheme.typography.labelLarge,
                color = CozmoCyan,
                fontWeight = FontWeight.Bold
            )

            Text(
                OriginalBehaviorLabels.behavior(personalityState.originalBehavior),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CozmoGraphite,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp)
            )

            Text(
                personalityState.lastStimulus,
                style = MaterialTheme.typography.bodyMedium,
                color = CozmoGraphite.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 5.dp)
            )

            Spacer(Modifier.height(14.dp))

            if (robotState.connection == ConnectionState.DISCONNECTED) {
                Button(
                    onClick = vm::connect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                    Text("  Connecter Cozmo")
                }
                Text(
                    "Connecte d'abord le téléphone au Wi-Fi de Cozmo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CozmoGraphite.copy(alpha = 0.58f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            CozmoYellow.copy(alpha = 0.18f),
                            RoundedCornerShape(18.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Mode autonome",
                            fontWeight = FontWeight.Bold,
                            color = CozmoGraphite
                        )
                        Text(
                            if (personalityState.enabled) {
                                "Cozmo fait sa vie"
                            } else {
                                "Autonomie en pause"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CozmoGraphite.copy(alpha = 0.62f)
                        )
                    }
                    Switch(
                        checked = personalityState.enabled,
                        onCheckedChange = vm::personalityEnabled
                    )
                }

                FilledTonalButton(
                    onClick = vm::personalityInteract,
                    enabled = personalityState.enabled &&
                        robotState.connection == ConnectionState.READY,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Text("Hé Cozmo !")
                }
            }
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(CozmoYellow.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = CozmoGraphite
                )
            }
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = CozmoGraphite
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CozmoGraphite.copy(alpha = 0.60f)
            )
        }
    }
}
