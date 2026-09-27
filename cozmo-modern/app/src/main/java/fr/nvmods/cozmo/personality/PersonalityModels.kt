package fr.nvmods.cozmo.personality

enum class PersonalityMode {
    CALME,
    NORMAL,
    JOUEUR
}

data class PersonalityState(
    val enabled: Boolean = false,
    val mode: PersonalityMode = PersonalityMode.NORMAL,
    val happiness: Float = 0.65f,
    val curiosity: Float = 0.55f,
    val energy: Float = 0.80f,
    val frustration: Float = 0.10f,
    val confidence: Float = 0.55f,
    val pickedUp: Boolean = false,
    val knownFaceVisible: Boolean = false,
    val cubeVisible: Boolean = false,
    val interactions: Long = 0,
    val idleTicks: Long = 0,
    val lastStimulus: String = "Aucun",
    val lastDecision: String = "En attente"
)

sealed interface PersonalityEvent {
    data class FaceDetected(val name: String? = null) : PersonalityEvent
    data class CubeDetected(val cubeId: Long? = null) : PersonalityEvent
    data object CubeLost : PersonalityEvent
    data object PickedUp : PersonalityEvent
    data object PutDown : PersonalityEvent
    data object Touched : PersonalityEvent
    data object UserInteraction : PersonalityEvent
    data object BatteryLow : PersonalityEvent
    data object IdleTick : PersonalityEvent
}

enum class PersonalityLight {
    OFF,
    RED,
    GREEN,
    BLUE,
    WHITE
}

sealed interface RobotAction {
    data object Stop : RobotAction
    data class Drive(
        val leftMmps: Float,
        val rightMmps: Float,
        val durationMs: Long
    ) : RobotAction

    data class MoveHead(
        val speedRadPerSec: Float,
        val durationMs: Long
    ) : RobotAction

    data class MoveLift(
        val speedRadPerSec: Float,
        val durationMs: Long
    ) : RobotAction

    data class Backpack(val light: PersonalityLight) : RobotAction
    data class PlayAnimation(val name: String) : RobotAction
    data class Speak(val text: String) : RobotAction
    data class Wait(val durationMs: Long) : RobotAction
}

data class PersonalityLogEntry(
    val timestampMs: Long,
    val event: String,
    val decision: String,
    val actions: List<RobotAction>
)

internal fun Float.unit(): Float = coerceIn(0f, 1f)
