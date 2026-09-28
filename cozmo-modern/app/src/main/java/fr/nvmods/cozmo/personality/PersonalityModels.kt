package fr.nvmods.cozmo.personality

enum class PersonalityMode {
    CALME,
    NORMAL,
    JOUEUR
}

/**
 * Vue publiée vers l'UI. Les jauges historiques (happiness, curiosity,
 * frustration, confidence) sont dérivées des émotions d'origine dans
 * [-1, 1], ramenées sur [0, 1] pour l'affichage.
 */
data class PersonalityState(
    val enabled: Boolean = false,
    val mode: PersonalityMode = PersonalityMode.NORMAL,
    /** (Happy + 1) / 2 */
    val happiness: Float = 0.5f,
    /** (Excited + 1) / 2 — l'original n'a pas de dimension "curiosité". */
    val curiosity: Float = 0.5f,
    /** Besoin Energy. */
    val energy: Float = 1f,
    /** max(0, -Confident) : 0,6 = frustration mineure, 0,9 = majeure. */
    val frustration: Float = 0f,
    /** (Confident + 1) / 2 */
    val confidence: Float = 0.5f,
    val play: Float = 1f,
    val repair: Float = 1f,
    val emotions: Map<EmotionType, Float> = emptyMap(),
    val activity: String = "—",
    val tuningSource: String = PersonalityTuning.DEFAULT.source,
    val pickedUp: Boolean = false,
    val knownFaceVisible: Boolean = false,
    val cubeVisible: Boolean = false,
    val interactions: Long = 0,
    val idleTicks: Long = 0,
    val lastStimulus: String = "Aucun",
    val lastDecision: String = "En attente",
    val originalActivity: String = "NothingToDo",
    val originalBehavior: String = "NothingToDo_Idle",
    val behaviorSource: String = "Compatibilité locale",
    val recoveredFaults: Long = 0,
    val lastFault: String? = null
)

sealed interface PersonalityEvent {
    data class FaceDetected(val name: String? = null) : PersonalityEvent
    data object FaceLost : PersonalityEvent
    data class CubeDetected(val cubeId: Long? = null) : PersonalityEvent
    data class CubeTapped(
        val cubeId: Long,
        val intensity: Int? = null
    ) : PersonalityEvent
    data class CubeMoved(val cubeId: Long) : PersonalityEvent
    data object CubeLost : PersonalityEvent
    data object PickedUp : PersonalityEvent
    data object PutDown : PersonalityEvent
    data object OnBack : PersonalityEvent
    data object OnFace : PersonalityEvent
    data object OnSide : PersonalityEvent
    data object Wheelie : PersonalityEvent
    data object Falling : PersonalityEvent
    data object CliffDetected : PersonalityEvent
    data object Touched : PersonalityEvent
    data object UserInteraction : PersonalityEvent
    data object BatteryLow : PersonalityEvent
    data object IdleTick : PersonalityEvent

    /** Action de la table needs_action_config (Feed, RepairHead...). */
    data class NeedsAction(
        val actionId: String,
        val label: String = actionId
    ) : PersonalityEvent
}

enum class PersonalityLight {
    OFF,
    RED,
    GREEN,
    BLUE,
    WHITE
}

/**
 * Intentions sonores de haut niveau.
 *
 * Elles ne dépendent pas d'un fichier audio précis : l'adaptateur audio
 * choisira une variante dans le catalogue officiel, ce qui permet d'éviter
 * que Cozmo répète toujours exactement le même bip/vocalise.
 */
enum class PersonalitySoundCue {
    GREETING,
    HAPPY_SHORT,
    HAPPY_LONG,
    CURIOUS,
    BORED,
    ANGRY,
    SAD,
    SURPRISED,
    PICKED_UP,
    PUT_DOWN,
    EFFORT,
    SELF_RIGHT,
    CLIFF,
    SLEEPY,
    WAKE_UP,
    PLAYFUL,
    WIN,
    LOSE,
    LOW_ENERGY
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
    data class CubeLight(
        val cubeId: Long,
        val light: PersonalityLight
    ) : RobotAction
    data class PlayAnimation(val name: String) : RobotAction

    /**
     * Animation + vocalise lancées ensemble, plus proche des clips Anki où
     * visage, moteurs et audio évoluent en parallèle.
     */
    data class Express(
        val name: String,
        val cue: PersonalitySoundCue? = null
    ) : RobotAction

    data class PlaySound(val cue: PersonalitySoundCue) : RobotAction
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
