package fr.nvmods.cozmo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.nvmods.cozmo.audio.AndroidSpeechBridge
import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoState
import fr.nvmods.cozmo.personality.MockRobotActions
import fr.nvmods.cozmo.personality.PersonalityEngine
import fr.nvmods.cozmo.personality.PersonalityEvent
import fr.nvmods.cozmo.personality.PersonalityMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class CozmoViewModel(application: Application) : AndroidViewModel(application) {
    private val connection = CozmoConnection()
    private val speech = AndroidSpeechBridge(application)

    // V1 personnalité : toutes les actions vont vers le mock.
    // Aucun ordre autonome n'est envoyé au vrai robot tant que la liaison
    // et les commandes ne sont pas déclarées stables.
    private val personalityRobot = MockRobotActions()
    private val personality = PersonalityEngine(personalityRobot)
    private var personalityJob: Job? = null

    val state: StateFlow<CozmoState> = connection.state
    val personalityState = personality.state
    val personalityLog = personality.log

    private val _speechStatus = MutableStateFlow("Prêt")
    val speechStatus: StateFlow<String> = _speechStatus.asStateFlow()

    fun connect() {
        viewModelScope.launch {
            connection.connect()
        }
    }

    fun disconnect() = connection.disconnect()

    fun forward() =
        connection.drive(CozmoConnection.DRIVE_SPEED, CozmoConnection.DRIVE_SPEED)

    fun backward() =
        connection.drive(-CozmoConnection.DRIVE_SPEED, -CozmoConnection.DRIVE_SPEED)

    fun left() =
        connection.drive(-CozmoConnection.TURN_SPEED, CozmoConnection.TURN_SPEED)

    fun right() =
        connection.drive(CozmoConnection.TURN_SPEED, -CozmoConnection.TURN_SPEED)

    fun stop() = connection.stopAllMotors()

    fun headUp() = connection.moveHead(CozmoConnection.HEAD_SPEED)

    fun headDown() = connection.moveHead(-CozmoConnection.HEAD_SPEED)

    fun liftUp() = connection.moveLift(CozmoConnection.LIFT_SPEED)

    fun liftDown() = connection.moveLift(-CozmoConnection.LIFT_SPEED)

    fun headLight(enabled: Boolean) = connection.setHeadLight(enabled)

    fun camera(enabled: Boolean) = connection.enableCamera(enabled)

    fun discoverCubes(enabled: Boolean) = connection.setAccessoryDiscovery(enabled)

    fun backpack(color: BackpackColor) = connection.setBackpackColor(color)

    fun cubeColor(objectId: Long, color: BackpackColor) = connection.setCubeColor(objectId, color)

    fun allCubeColor(color: BackpackColor) = connection.setAllCubeColor(color)

    fun volume(percent: Float) = connection.setRobotVolume(percent)

    fun personalityEnabled(enabled: Boolean) {
        personalityJob?.cancel()
        personalityJob = null

        if (!enabled) {
            personality.stop()
            return
        }

        personality.start()
        personalityJob = viewModelScope.launch {
            while (isActive) {
                delay(1_500)
                personality.handle(PersonalityEvent.IdleTick)
            }
        }
    }

    fun personalityMode(mode: PersonalityMode) = personality.setMode(mode)

    fun personalityFace(name: String? = null) {
        viewModelScope.launch {
            personality.handle(PersonalityEvent.FaceDetected(name))
        }
    }

    fun personalityCube(cubeId: Long? = null) {
        viewModelScope.launch {
            personality.handle(PersonalityEvent.CubeDetected(cubeId))
        }
    }

    fun personalityCubeLost() {
        viewModelScope.launch {
            personality.handle(PersonalityEvent.CubeLost)
        }
    }

    fun personalityPickedUp() {
        viewModelScope.launch {
            personality.handle(PersonalityEvent.PickedUp)
        }
    }

    fun personalityPutDown() {
        viewModelScope.launch {
            personality.handle(PersonalityEvent.PutDown)
        }
    }

    fun personalityTouched() {
        viewModelScope.launch {
            personality.handle(PersonalityEvent.Touched)
        }
    }

    fun personalityInteract() {
        viewModelScope.launch {
            personality.handle(PersonalityEvent.UserInteraction)
        }
    }

    fun personalityBatteryLow() {
        viewModelScope.launch {
            personality.handle(PersonalityEvent.BatteryLow)
        }
    }

    fun speak(text: String, french: Boolean, pitch: Float, rate: Float) {
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                _speechStatus.value = "Synthèse…"

                val locale = if (french) Locale.FRANCE else Locale.US
                val synthesized =
                    speech.synthesize(
                        text = text,
                        locale = locale,
                        pitch = pitch,
                        rate = rate
                    )

                _speechStatus.value =
                    "PCM Android " +
                        synthesized.sourceRate +
                        " Hz / " +
                        synthesized.sourceChannels +
                        " ch / enc=" +
                        synthesized.sourceEncoding +
                        " -> 22050 Hz"

                connection.playPcm22050(synthesized.samples)

                _speechStatus.value =
                    "Terminé — source " +
                        synthesized.sourceRate +
                        " Hz -> 22050 Hz"
            } catch (t: Throwable) {
                _speechStatus.value = "Erreur : " + t.message
            }
        }
    }

    override fun onCleared() {
        personalityJob?.cancel()
        personality.stop()
        speech.shutdown()
        connection.close()
        super.onCleared()
    }
}
