package fr.nvmods.cozmo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.nvmods.cozmo.audio.AndroidSpeechBridge
import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class CozmoViewModel(application: Application) : AndroidViewModel(application) {
    private val connection = CozmoConnection()
    private val speech = AndroidSpeechBridge(application)

    val state: StateFlow<CozmoState> = connection.state

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

    fun volume(percent: Float) = connection.setRobotVolume(percent)

    fun speak(text: String, french: Boolean) {
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                _speechStatus.value = "Synthèse…"

                val locale = if (french) Locale.FRANCE else Locale.US
                val pcm = speech.synthesize(text, locale)

                _speechStatus.value = "Envoi vers Cozmo…"
                connection.playPcm22050(pcm)

                _speechStatus.value = "Terminé"
            } catch (t: Throwable) {
                _speechStatus.value = "Erreur : " + t.message
            }
        }
    }

    override fun onCleared() {
        speech.shutdown()
        connection.close()
        super.onCleared()
    }
}
