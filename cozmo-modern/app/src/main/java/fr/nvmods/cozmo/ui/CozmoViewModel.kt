package fr.nvmods.cozmo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.nvmods.cozmo.audio.AndroidSpeechBridge
import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoState
import fr.nvmods.cozmo.protocol.CubeInfo
import fr.nvmods.cozmo.personality.CozmoRobotActions
import fr.nvmods.cozmo.personality.PersonalityEngine
import fr.nvmods.cozmo.personality.PersonalityEvent
import fr.nvmods.cozmo.personality.PersonalityMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class CozmoViewModel(application: Application) : AndroidViewModel(application) {
    private val connection = CozmoConnection()
    private val speech = AndroidSpeechBridge(application)

    private val personalityRobot = CozmoRobotActions(connection)
    private val personality = PersonalityEngine(personalityRobot)
    private var personalityTickerJob: Job? = null
    private var personalityActionJob: Job? = null
    private var manualMotionActive = false
    private var previousCubes: Map<Long, CubeInfo> = emptyMap()

    val state: StateFlow<CozmoState> = connection.state
    val personalityState = personality.state
    val personalityLog = personality.log

    private val _speechStatus = MutableStateFlow("Prêt")
    val speechStatus: StateFlow<String> = _speechStatus.asStateFlow()

    init {
        viewModelScope.launch {
            state.collect { robotState ->
                observeRobotState(robotState)
            }
        }
    }

    fun connect() {
        viewModelScope.launch {
            connection.connect()
        }
    }

    fun disconnect() = connection.disconnect()

    fun forward() = manualMotion {
        connection.drive(CozmoConnection.DRIVE_SPEED, CozmoConnection.DRIVE_SPEED)
    }

    fun backward() = manualMotion {
        connection.drive(-CozmoConnection.DRIVE_SPEED, -CozmoConnection.DRIVE_SPEED)
    }

    fun left() = manualMotion {
        connection.drive(-CozmoConnection.TURN_SPEED, CozmoConnection.TURN_SPEED)
    }

    fun right() = manualMotion {
        connection.drive(CozmoConnection.TURN_SPEED, -CozmoConnection.TURN_SPEED)
    }

    fun stop() {
        personalityActionJob?.cancel()
        personalityActionJob = null
        connection.stopAllMotors()
        manualMotionActive = false
    }

    fun headUp() = manualMotion {
        connection.moveHead(CozmoConnection.HEAD_SPEED)
    }

    fun headDown() = manualMotion {
        connection.moveHead(-CozmoConnection.HEAD_SPEED)
    }

    fun liftUp() = manualMotion {
        connection.moveLift(CozmoConnection.LIFT_SPEED)
    }

    fun liftDown() = manualMotion {
        connection.moveLift(-CozmoConnection.LIFT_SPEED)
    }

    fun headLight(enabled: Boolean) = connection.setHeadLight(enabled)

    fun camera(enabled: Boolean) = connection.enableCamera(enabled)

    fun discoverCubes(enabled: Boolean) = connection.setAccessoryDiscovery(enabled)

    fun backpack(color: BackpackColor) = connection.setBackpackColor(color)

    fun cubeColor(
        factoryId: Long,
        color: BackpackColor
    ) = connection.setCubeColor(factoryId, color)

    fun allCubeColor(color: BackpackColor) =
        connection.setAllCubeColor(color)

    fun cubePairPattern(
        factoryId: Long,
        first: BackpackColor,
        second: BackpackColor
    ) = connection.setCubePairPattern(
        factoryId,
        first,
        second
    )

    fun allCubePairPattern(
        first: BackpackColor,
        second: BackpackColor
    ) = connection.setAllCubePairPattern(first, second)

    fun cubeCornerColor(
        factoryId: Long,
        corner: Int,
        color: BackpackColor
    ) = connection.setCubeCornerColor(
        factoryId,
        corner,
        color
    )

    fun cubeAccel(
        factoryId: Long,
        enabled: Boolean
    ) = connection.setCubeAccelStreaming(
        factoryId,
        enabled
    )

    fun allCubeAccel(enabled: Boolean) =
        connection.setAllCubeAccelStreaming(enabled)

    fun cubeChaser(
        factoryId: Long,
        color: BackpackColor
    ) = connection.startCubeChaser(factoryId, color)

    fun stopCubeChaser(factoryId: Long) =
        connection.stopCubeChaser(factoryId)

    fun volume(percent: Float) = connection.setRobotVolume(percent)

    fun personalityEnabled(enabled: Boolean) {
        personalityActionJob?.cancel()
        personalityActionJob = null

        if (!enabled) {
            personalityTickerJob?.cancel()
            personalityTickerJob = null
            val hadAutonomousMotion = !manualMotionActive
            personality.stop()
            if (hadAutonomousMotion) {
                connection.stopAllMotors()
            }
            return
        }

        personality.start()

        personalityTickerJob?.cancel()
        personalityTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_500)

                if (
                    personality.state.value.enabled &&
                    !manualMotionActive &&
                    personalityActionJob?.isActive != true
                ) {
                    dispatchPersonality(
                        event = PersonalityEvent.IdleTick,
                        interruptCurrent = false
                    )
                }
            }
        }
    }

    fun personalityMode(mode: PersonalityMode) {
        personality.setMode(mode)
    }

    fun personalityFace(name: String? = null) =
        dispatchPersonality(PersonalityEvent.FaceDetected(name))

    fun personalityCube(cubeId: Long? = null) =
        dispatchPersonality(PersonalityEvent.CubeDetected(cubeId))

    fun personalityCubeLost() =
        dispatchPersonality(PersonalityEvent.CubeLost)

    fun personalityPickedUp() =
        dispatchPersonality(PersonalityEvent.PickedUp)

    fun personalityPutDown() =
        dispatchPersonality(PersonalityEvent.PutDown)

    fun personalityTouched() =
        dispatchPersonality(PersonalityEvent.Touched)

    fun personalityInteract() =
        dispatchPersonality(PersonalityEvent.UserInteraction)

    fun personalityBatteryLow() =
        dispatchPersonality(PersonalityEvent.BatteryLow)

    private fun manualMotion(action: () -> Unit) {
        personalityActionJob?.cancel()
        personalityActionJob = null
        manualMotionActive = true
        action()
    }

    private fun dispatchPersonality(
        event: PersonalityEvent,
        interruptCurrent: Boolean = true
    ) {
        if (!personality.state.value.enabled || manualMotionActive) return

        if (interruptCurrent) {
            personalityActionJob?.cancel()
        } else if (personalityActionJob?.isActive == true) {
            return
        }

        personalityActionJob = viewModelScope.launch {
            personality.handle(event)
        }
    }

    private fun observeRobotState(robotState: CozmoState) {
        val current = robotState.cubes.associateBy { it.factoryId }

        if (personality.state.value.enabled && !manualMotionActive) {
            val hadConnectedCube = previousCubes.values.any { it.connected }
            val hasConnectedCube = current.values.any { it.connected }

            current.values.forEach { cube ->
                val previous = previousCubes[cube.factoryId]

                if (cube.connected && previous?.connected != true) {
                    dispatchPersonality(
                        PersonalityEvent.CubeDetected(cube.factoryId)
                    )
                }

                if (cube.tapCount > (previous?.tapCount ?: 0)) {
                    dispatchPersonality(
                        PersonalityEvent.CubeTapped(
                            cubeId = cube.factoryId,
                            intensity = cube.tapIntensity
                        )
                    )
                } else if (cube.moving && previous?.moving != true) {
                    dispatchPersonality(
                        PersonalityEvent.CubeMoved(cube.factoryId),
                        interruptCurrent = false
                    )
                }
            }

            if (hadConnectedCube && !hasConnectedCube) {
                dispatchPersonality(PersonalityEvent.CubeLost)
            }
        }

        previousCubes = current
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
        personalityTickerJob?.cancel()
        personalityActionJob?.cancel()
        personality.stop()
        speech.shutdown()
        connection.close()
        super.onCleared()
    }
}
