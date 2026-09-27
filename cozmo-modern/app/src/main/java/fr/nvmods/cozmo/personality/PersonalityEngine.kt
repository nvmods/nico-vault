package fr.nvmods.cozmo.personality

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.random.Random

/**
 * Moteur de personnalité indépendant du protocole Cozmo.
 *
 * Il reçoit des perceptions, met à jour l'état interne puis produit
 * des intentions sous forme de RobotAction. Le transport réel sera
 * branché plus tard derrière RobotActions.
 */
class PersonalityEngine(
    private val robot: RobotActions,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default
) {
    private val _state = MutableStateFlow(PersonalityState())
    val state: StateFlow<PersonalityState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<PersonalityLogEntry>>(emptyList())
    val log: StateFlow<List<PersonalityLogEntry>> = _log.asStateFlow()

    private var lastAutonomousDecisionMs = 0L

    fun start() {
        _state.value = _state.value.copy(
            enabled = true,
            lastStimulus = "Moteur démarré",
            lastDecision = "Observation"
        )
    }

    fun stop() {
        _state.value = _state.value.copy(
            enabled = false,
            lastStimulus = "Moteur arrêté",
            lastDecision = "Aucune action"
        )
    }

    fun setMode(mode: PersonalityMode) {
        _state.value = _state.value.copy(mode = mode)
    }

    suspend fun handle(event: PersonalityEvent) {
        if (!_state.value.enabled && event != PersonalityEvent.BatteryLow) return

        val now = clockMs()
        val before = _state.value
        var next = evolve(before, event)
        val actions = decide(next, event, now)

        val decision =
            if (actions.isEmpty()) "Observer"
            else describeDecision(event, actions)

        next = next.copy(lastDecision = decision)
        _state.value = next

        actions.forEach { action ->
            robot.execute(action)
        }

        appendLog(
            PersonalityLogEntry(
                timestampMs = now,
                event = describeEvent(event),
                decision = decision,
                actions = actions
            )
        )
    }

    private fun evolve(
        current: PersonalityState,
        event: PersonalityEvent
    ): PersonalityState {
        return when (event) {
            is PersonalityEvent.FaceDetected -> current.copy(
                happiness = (current.happiness + 0.10f).unit(),
                curiosity = (current.curiosity + 0.08f).unit(),
                confidence = (current.confidence + 0.04f).unit(),
                knownFaceVisible = true,
                interactions = current.interactions + 1,
                lastStimulus = event.name?.let { "Visage : $it" } ?: "Visage détecté"
            )

            is PersonalityEvent.CubeDetected -> current.copy(
                curiosity = (current.curiosity + 0.14f).unit(),
                cubeVisible = true,
                lastStimulus = event.cubeId?.let { "Cube $it détecté" } ?: "Cube détecté"
            )

            PersonalityEvent.CubeLost -> current.copy(
                cubeVisible = false,
                curiosity = (current.curiosity + 0.05f).unit(),
                lastStimulus = "Cube perdu"
            )

            PersonalityEvent.PickedUp -> current.copy(
                pickedUp = true,
                energy = (current.energy + 0.05f).unit(),
                frustration = (current.frustration + 0.08f).unit(),
                lastStimulus = "Cozmo soulevé"
            )

            PersonalityEvent.PutDown -> current.copy(
                pickedUp = false,
                frustration = (current.frustration - 0.04f).unit(),
                lastStimulus = "Cozmo reposé"
            )

            PersonalityEvent.Touched -> current.copy(
                happiness = (current.happiness + 0.08f).unit(),
                confidence = (current.confidence + 0.03f).unit(),
                interactions = current.interactions + 1,
                lastStimulus = "Contact"
            )

            PersonalityEvent.UserInteraction -> current.copy(
                happiness = (current.happiness + 0.06f).unit(),
                curiosity = (current.curiosity + 0.04f).unit(),
                interactions = current.interactions + 1,
                lastStimulus = "Interaction utilisateur"
            )

            PersonalityEvent.BatteryLow -> current.copy(
                energy = max(0.08f, current.energy - 0.25f),
                lastStimulus = "Batterie faible"
            )

            PersonalityEvent.IdleTick -> {
                val modeCuriosity = when (current.mode) {
                    PersonalityMode.CALME -> 0.004f
                    PersonalityMode.NORMAL -> 0.010f
                    PersonalityMode.JOUEUR -> 0.018f
                }

                current.copy(
                    curiosity = (current.curiosity + modeCuriosity).unit(),
                    happiness = (current.happiness - 0.002f).unit(),
                    energy = (current.energy - 0.0015f).unit(),
                    idleTicks = current.idleTicks + 1,
                    lastStimulus = "Temps libre"
                )
            }
        }
    }

    private fun decide(
        state: PersonalityState,
        event: PersonalityEvent,
        now: Long
    ): List<RobotAction> {
        return when (event) {
            is PersonalityEvent.FaceDetected -> listOf(
                RobotAction.Stop,
                RobotAction.MoveHead(1.0f, 220),
                RobotAction.PlaySound(PersonalitySoundCue.GREETING),
                RobotAction.PlayAnimation("greeting"),
                RobotAction.Backpack(PersonalityLight.BLUE)
            )

            is PersonalityEvent.CubeDetected -> listOf(
                RobotAction.MoveHead(-0.6f, 180),
                RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                RobotAction.PlayAnimation("cube_interest")
            )

            PersonalityEvent.PickedUp -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.PICKED_UP),
                RobotAction.PlayAnimation("picked_up")
            )

            PersonalityEvent.PutDown -> listOf(
                RobotAction.PlaySound(PersonalitySoundCue.PUT_DOWN),
                RobotAction.PlayAnimation("put_down")
            )

            PersonalityEvent.Touched -> listOf(
                RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
                RobotAction.PlayAnimation("happy_small")
            )

            PersonalityEvent.UserInteraction -> {
                if (state.mode == PersonalityMode.JOUEUR) {
                    listOf(
                        RobotAction.PlaySound(PersonalitySoundCue.PLAYFUL),
                        RobotAction.PlayAnimation("playful_invite"),
                        RobotAction.Backpack(PersonalityLight.GREEN)
                    )
                } else {
                    listOf(
                        RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                        RobotAction.PlayAnimation("acknowledge")
                    )
                }
            }

            PersonalityEvent.BatteryLow -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.LOW_ENERGY),
                RobotAction.PlayAnimation("low_energy")
            )

            PersonalityEvent.CubeLost -> emptyList()

            PersonalityEvent.IdleTick -> idleDecision(state, now)
        }
    }

    private fun idleDecision(
        state: PersonalityState,
        now: Long
    ): List<RobotAction> {
        if (state.pickedUp || state.energy < 0.18f) return emptyList()

        val cooldownMs = when (state.mode) {
            PersonalityMode.CALME -> 18_000L
            PersonalityMode.NORMAL -> 10_000L
            PersonalityMode.JOUEUR -> 6_000L
        }

        if (now - lastAutonomousDecisionMs < cooldownMs) return emptyList()

        val chance = when (state.mode) {
            PersonalityMode.CALME -> 0.10f
            PersonalityMode.NORMAL -> 0.24f
            PersonalityMode.JOUEUR -> 0.40f
        }

        if (state.curiosity < 0.62f || random.nextFloat() > chance) return emptyList()

        lastAutonomousDecisionMs = now

        return if (state.cubeVisible) {
            listOf(
                RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                RobotAction.PlayAnimation("cube_interest"),
                RobotAction.MoveHead(-0.45f, 180)
            )
        } else {
            listOf(
                RobotAction.PlaySound(PersonalitySoundCue.BORED),
                RobotAction.MoveHead(0.55f, 150),
                RobotAction.Wait(120),
                RobotAction.MoveHead(-0.55f, 300),
                RobotAction.Wait(120),
                RobotAction.MoveHead(0.55f, 150),
                RobotAction.PlayAnimation("look_around")
            )
        }
    }

    private fun appendLog(entry: PersonalityLogEntry) {
        _log.value = (listOf(entry) + _log.value).take(MAX_LOG_ENTRIES)
    }

    private fun describeDecision(
        event: PersonalityEvent,
        actions: List<RobotAction>
    ): String = when (event) {
        is PersonalityEvent.FaceDetected -> "Saluer la personne"
        is PersonalityEvent.CubeDetected -> "Observer le cube"
        PersonalityEvent.PickedUp -> "Réagir au soulèvement"
        PersonalityEvent.PutDown -> "Réagir au retour au sol"
        PersonalityEvent.Touched -> "Réagir au contact"
        PersonalityEvent.UserInteraction -> "Répondre à l'utilisateur"
        PersonalityEvent.BatteryLow -> "Passer en énergie basse"
        PersonalityEvent.CubeLost -> "Chercher le cube"
        PersonalityEvent.IdleTick ->
            if (actions.isEmpty()) "Observer" else "Explorer du regard"
    }

    private fun describeEvent(event: PersonalityEvent): String = when (event) {
        is PersonalityEvent.FaceDetected ->
            event.name?.let { "Visage détecté : $it" } ?: "Visage détecté"

        is PersonalityEvent.CubeDetected ->
            event.cubeId?.let { "Cube détecté : $it" } ?: "Cube détecté"

        PersonalityEvent.CubeLost -> "Cube perdu"
        PersonalityEvent.PickedUp -> "Soulevé"
        PersonalityEvent.PutDown -> "Reposé"
        PersonalityEvent.Touched -> "Touché"
        PersonalityEvent.UserInteraction -> "Interaction"
        PersonalityEvent.BatteryLow -> "Batterie faible"
        PersonalityEvent.IdleTick -> "Tick d'autonomie"
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 80
    }
}
