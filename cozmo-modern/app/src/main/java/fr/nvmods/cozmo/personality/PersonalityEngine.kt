package fr.nvmods.cozmo.personality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Moteur de personnalité indépendant du protocole Cozmo.
 *
 * La décision est déléguée à PersonalityBrain (logique pure, testable) ;
 * ce moteur exécute les séquences sur le robot, gère timeouts / pannes et
 * publie l'état pour l'UI.
 */
class PersonalityEngine(
    private val robot: RobotActions,
    private val clockMs: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
    private val originalProfile: OriginalBehaviorProfile = OriginalBehaviorProfile.fallback(),
    tuning: PersonalityTuning = PersonalityTuning.DEFAULT
) {
    private val brain = PersonalityBrain(tuning, clockMs(), random, originalProfile)

    private val _state = MutableStateFlow(
        PersonalityState(
            tuningSource = tuning.source,
            behaviorSource = originalProfile.source
        )
    )
    val state: StateFlow<PersonalityState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<PersonalityLogEntry>>(emptyList())
    val log: StateFlow<List<PersonalityLogEntry>> = _log.asStateFlow()

    fun start() {
        publish(
            enabled = true,
            lastStimulus = "Moteur démarré",
            lastDecision = "Observation"
        )
    }

    fun stop() {
        publish(
            enabled = false,
            lastStimulus = "Moteur arrêté",
            lastDecision = "Aucune action"
        )
    }

    fun setMode(mode: PersonalityMode) {
        brain.mode = mode
        publish()
    }

    fun reportRecoveredFault(message: String) {
        publish(
            recoveredFaults = _state.value.recoveredFaults + 1,
            lastFault = message.take(180),
            lastDecision = "Erreur interceptée, moteur conservé actif"
        )
    }

    suspend fun handle(event: PersonalityEvent) {
        if (!_state.value.enabled && event != PersonalityEvent.BatteryLow) return

        val now = clockMs()
        val decision = brain.onEvent(event, now)

        val isInteraction = when (event) {
            is PersonalityEvent.FaceDetected,
            is PersonalityEvent.CubeTapped,
            PersonalityEvent.Touched,
            PersonalityEvent.UserInteraction -> true
            else -> false
        }
        val previous = _state.value
        publish(
            lastStimulus = describeEvent(event),
            lastDecision = decision?.label ?: previous.lastDecision,
            originalActivity = decision?.activityId ?: previous.originalActivity,
            originalBehavior = decision?.behaviorId ?: previous.originalBehavior,
            interactions = previous.interactions + if (isInteraction) 1 else 0,
            idleTicks = previous.idleTicks + if (event == PersonalityEvent.IdleTick) 1 else 0
        )

        // Un stimulus ignoré (anti-spam, tick sans décision...) ne remplace
        // pas la dernière vraie décision.
        if (decision == null || decision.actions.isEmpty()) return

        val executed = mutableListOf<RobotAction>()

        for (action in decision.actions) {
            val result =
                try {
                    withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                        robot.execute(action)
                    } ?: ActionResult(
                        ActionStatus.TIMEOUT,
                        "Action trop longue: ${action::class.simpleName}"
                    )
                } catch (cancelled: CancellationException) {
                    // Une nouvelle perception peut interrompre proprement une
                    // séquence. Ce n'est pas une panne.
                    throw cancelled
                } catch (t: Exception) {
                    ActionResult(
                        ActionStatus.FAILED,
                        t.message ?: t::class.java.simpleName
                    )
                }

            executed += action

            if (
                result.status == ActionStatus.FAILED ||
                result.status == ActionStatus.TIMEOUT
            ) {
                recoverFromActionFault(action, result, event, now, executed)
                return
            }
        }

        brain.onDecisionCompleted(decision, clockMs())
        publish()

        appendLog(
            PersonalityLogEntry(
                timestampMs = now,
                event = describeEvent(event),
                decision = decision.label,
                actions = decision.actions
            )
        )
    }

    private suspend fun recoverFromActionFault(
        action: RobotAction,
        result: ActionResult,
        event: PersonalityEvent,
        now: Long,
        executed: List<RobotAction>
    ) {
        val detail = (result.message ?: result.status.name).take(160)

        publish(
            recoveredFaults = _state.value.recoveredFaults + 1,
            lastFault = (action::class.simpleName ?: "Action") + " : " + detail,
            lastDecision = "Séquence sécurisée après erreur"
        )

        try {
            withTimeoutOrNull(600L) {
                robot.execute(RobotAction.Stop)
            }
        } catch (_: Exception) {
            // Une erreur de récupération ne doit jamais faire tomber l'appli.
        }

        appendLog(
            PersonalityLogEntry(
                timestampMs = now,
                event = describeEvent(event),
                decision = "Erreur récupérée : $detail",
                actions = executed
            )
        )
    }

    /** Recalcule les jauges depuis le cerveau et fusionne les champs donnés. */
    private fun publish(
        enabled: Boolean = _state.value.enabled,
        lastStimulus: String = _state.value.lastStimulus,
        lastDecision: String = _state.value.lastDecision,
        interactions: Long = _state.value.interactions,
        idleTicks: Long = _state.value.idleTicks,
        recoveredFaults: Long = _state.value.recoveredFaults,
        lastFault: String? = _state.value.lastFault,
        originalActivity: String = _state.value.originalActivity,
        originalBehavior: String = _state.value.originalBehavior
    ) {
        val now = clockMs()
        brain.needs.update(now)
        val emotions = brain.mood.snapshot(now)

        fun unit(e: EmotionType) = ((emotions.getValue(e) + 1f) / 2f).coerceIn(0f, 1f)

        _state.value = _state.value.copy(
            enabled = enabled,
            mode = brain.mode,
            happiness = unit(EmotionType.Happy),
            curiosity = unit(EmotionType.Excited),
            energy = brain.needs.level(NeedId.Energy),
            frustration = (-emotions.getValue(EmotionType.Confident)).coerceIn(0f, 1f),
            confidence = unit(EmotionType.Confident),
            play = brain.needs.level(NeedId.Play),
            repair = brain.needs.level(NeedId.Repair),
            emotions = emotions,
            activity = brain.activity.label,
            pickedUp = brain.pickedUp,
            knownFaceVisible = brain.faceVisible,
            cubeVisible = brain.cubeVisible,
            interactions = interactions,
            idleTicks = idleTicks,
            lastStimulus = lastStimulus,
            lastDecision = lastDecision,
            recoveredFaults = recoveredFaults,
            lastFault = lastFault,
            originalActivity = originalActivity,
            originalBehavior = originalBehavior,
            behaviorSource = originalProfile.source
        )
    }

    private fun appendLog(entry: PersonalityLogEntry) {
        _log.value = (listOf(entry) + _log.value).take(MAX_LOG_ENTRIES)
    }

    private fun describeEvent(event: PersonalityEvent): String = when (event) {
        is PersonalityEvent.FaceDetected ->
            event.name?.let { "Visage détecté : $it" } ?: "Visage détecté"

        PersonalityEvent.FaceLost -> "Visage perdu"

        is PersonalityEvent.CubeDetected ->
            event.cubeId?.let { "Cube détecté : $it" } ?: "Cube détecté"

        is PersonalityEvent.CubeTapped ->
            "Cube tapé : ${event.cubeId}" +
                (event.intensity?.let { " (intensité $it)" } ?: "")

        is PersonalityEvent.CubeMoved -> "Cube déplacé : ${event.cubeId}"

        PersonalityEvent.CubeLost -> "Cube perdu"
        PersonalityEvent.PickedUp -> "Soulevé"
        PersonalityEvent.PutDown -> "Reposé"
        PersonalityEvent.OnBack -> "Sur le dos"
        PersonalityEvent.OnFace -> "Sur la face"
        PersonalityEvent.OnSide -> "Sur le côté"
        PersonalityEvent.Wheelie -> "Wheelie"
        PersonalityEvent.Falling -> "Chute"
        PersonalityEvent.CliffDetected -> "Bord détecté"
        PersonalityEvent.Touched -> "Touché"
        PersonalityEvent.UserInteraction -> "Interaction"
        PersonalityEvent.BatteryLow -> "Batterie faible"
        PersonalityEvent.IdleTick -> "Tick d'autonomie"
        is PersonalityEvent.NeedsAction -> "Besoin : ${event.label}"
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 80
        private const val ACTION_TIMEOUT_MS = 4_000L
    }
}
