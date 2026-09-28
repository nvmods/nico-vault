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
    private var lastCubeMotionReactionMs = 0L

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

        if (actions.isEmpty()) {
            // Un stimulus ignoré (anti-spam cube, tick sans décision...) ne doit
            // pas remplacer la dernière vraie action par "Observer".
            _state.value = next.copy(lastDecision = before.lastDecision)
            return
        }

        val decision = describeDecision(event, actions)
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

            PersonalityEvent.FaceLost -> current.copy(
                knownFaceVisible = false,
                lastStimulus = "Visage perdu"
            )

            is PersonalityEvent.CubeDetected -> current.copy(
                curiosity = (current.curiosity + 0.14f).unit(),
                cubeVisible = true,
                lastStimulus = event.cubeId?.let { "Cube $it détecté" } ?: "Cube détecté"
            )

            is PersonalityEvent.CubeTapped -> current.copy(
                happiness = (current.happiness + 0.08f).unit(),
                curiosity = (current.curiosity + 0.05f).unit(),
                interactions = current.interactions + 1,
                lastStimulus = "Cube ${event.cubeId} tapé"
            )

            is PersonalityEvent.CubeMoved -> current.copy(
                curiosity = (current.curiosity + 0.06f).unit(),
                lastStimulus = "Cube ${event.cubeId} déplacé"
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

            PersonalityEvent.OnBack -> current.copy(
                frustration = (current.frustration + 0.10f).unit(),
                curiosity = (current.curiosity + 0.03f).unit(),
                lastStimulus = "Sur le dos"
            )

            PersonalityEvent.OnFace -> current.copy(
                frustration = (current.frustration + 0.12f).unit(),
                lastStimulus = "Sur la face"
            )

            PersonalityEvent.OnSide -> current.copy(
                frustration = (current.frustration + 0.06f).unit(),
                lastStimulus = "Sur le côté"
            )

            PersonalityEvent.Wheelie -> current.copy(
                curiosity = (current.curiosity + 0.08f).unit(),
                happiness = (current.happiness + 0.03f).unit(),
                lastStimulus = "En wheelie"
            )

            PersonalityEvent.Falling -> current.copy(
                confidence = (current.confidence - 0.08f).unit(),
                frustration = (current.frustration + 0.10f).unit(),
                lastStimulus = "Chute détectée"
            )

            PersonalityEvent.CliffDetected -> current.copy(
                confidence = (current.confidence - 0.06f).unit(),
                frustration = (current.frustration + 0.08f).unit(),
                lastStimulus = "Bord détecté"
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

            PersonalityEvent.FaceLost -> emptyList()

            is PersonalityEvent.CubeDetected -> listOf(
                RobotAction.MoveHead(-0.6f, 180),
                RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                RobotAction.PlayAnimation("cube_interest")
            )

            is PersonalityEvent.CubeTapped -> when (random.nextInt(4)) {
                0 -> listOf(
                    RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
                    RobotAction.CubeLight(event.cubeId, PersonalityLight.GREEN),
                    RobotAction.MoveHead(-0.35f, 120),
                    RobotAction.Wait(100),
                    RobotAction.MoveHead(0.35f, 120),
                    RobotAction.CubeLight(event.cubeId, PersonalityLight.BLUE)
                )

                1 -> listOf(
                    RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
                    RobotAction.CubeLight(event.cubeId, PersonalityLight.GREEN),
                    RobotAction.PlayAnimation("small_bounce"),
                    RobotAction.CubeLight(event.cubeId, PersonalityLight.BLUE)
                )

                2 -> listOf(
                    RobotAction.PlaySound(PersonalitySoundCue.PLAYFUL),
                    RobotAction.PlayAnimation("tiny_wiggle"),
                    RobotAction.CubeLight(event.cubeId, PersonalityLight.GREEN)
                )

                else -> listOf(
                    RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                    RobotAction.PlayAnimation("cube_peek"),
                    RobotAction.CubeLight(event.cubeId, PersonalityLight.BLUE)
                )
            }

            is PersonalityEvent.CubeMoved -> {
                if (now - lastCubeMotionReactionMs < 1_800L) {
                    emptyList()
                } else {
                    lastCubeMotionReactionMs = now
                    when (random.nextInt(3)) {
                        0 -> listOf(
                            RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                            RobotAction.PlayAnimation("cube_peek")
                        )

                        1 -> listOf(
                            RobotAction.PlayAnimation("curious_nod")
                        )

                        else -> listOf(
                            RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
                            RobotAction.PlayAnimation("small_bounce")
                        )
                    }
                }
            }

            PersonalityEvent.PickedUp -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.PICKED_UP),
                RobotAction.PlayAnimation("picked_up")
            )

            PersonalityEvent.PutDown -> listOf(
                RobotAction.PlaySound(PersonalitySoundCue.PUT_DOWN),
                RobotAction.PlayAnimation("put_down")
            )

            PersonalityEvent.OnBack -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.SURPRISED),
                RobotAction.PlayAnimation("on_back_notice")
            )

            PersonalityEvent.OnFace -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.SURPRISED),
                RobotAction.PlayAnimation("on_face_notice")
            )

            PersonalityEvent.OnSide -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                RobotAction.PlayAnimation("on_side_notice")
            )

            PersonalityEvent.Wheelie -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.EFFORT),
                RobotAction.PlayAnimation("wheelie_notice")
            )

            PersonalityEvent.Falling -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.SURPRISED),
                RobotAction.PlayAnimation("fall_notice")
            )

            PersonalityEvent.CliffDetected -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.CLIFF),
                RobotAction.Backpack(PersonalityLight.RED),
                RobotAction.PlayAnimation("cliff_notice")
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

        // Cozmo doit donner l'impression de "vivre" même sans stimulus.
        // Les séquences restent courtes ; le mode calme conserve des pauses.
        val cooldownMs = when (state.mode) {
            PersonalityMode.CALME -> 5_500L
            PersonalityMode.NORMAL -> 2_600L
            PersonalityMode.JOUEUR -> 1_700L
        }

        if (now - lastAutonomousDecisionMs < cooldownMs) return emptyList()

        val chance = when (state.mode) {
            PersonalityMode.CALME -> 0.48f
            PersonalityMode.NORMAL -> 0.82f
            PersonalityMode.JOUEUR -> 0.95f
        }

        if (state.curiosity < 0.24f || random.nextFloat() > chance) {
            return emptyList()
        }

        lastAutonomousDecisionMs = now

        if (state.cubeVisible) {
            return when (random.nextInt(4)) {
                0 -> listOf(
                    RobotAction.PlayAnimation("cube_interest")
                )

                1 -> listOf(
                    RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                    RobotAction.PlayAnimation("cube_peek")
                )

                2 -> listOf(
                    RobotAction.PlayAnimation("idle_blink"),
                    RobotAction.Wait(120),
                    RobotAction.PlayAnimation("curious_nod")
                )

                else -> listOf(
                    RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
                    RobotAction.PlayAnimation("small_bounce")
                )
            }
        }

        return when (random.nextInt(
            when (state.mode) {
                PersonalityMode.CALME -> 5
                PersonalityMode.NORMAL -> 8
                PersonalityMode.JOUEUR -> 10
            }
        )) {
            0 -> listOf(
                RobotAction.PlayAnimation("idle_blink")
            )

            1 -> listOf(
                RobotAction.PlayAnimation("curious_nod")
            )

            2 -> listOf(
                RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                RobotAction.PlayAnimation("head_peek")
            )

            3 -> listOf(
                RobotAction.PlayAnimation("small_bounce")
            )

            4 -> listOf(
                RobotAction.PlaySound(PersonalitySoundCue.BORED),
                RobotAction.PlayAnimation("look_around")
            )

            5 -> listOf(
                RobotAction.PlaySound(PersonalitySoundCue.PLAYFUL),
                RobotAction.PlayAnimation("tiny_wiggle")
            )

            6 -> listOf(
                RobotAction.PlayAnimation("double_blink"),
                RobotAction.PlayAnimation("head_peek")
            )

            7 -> listOf(
                RobotAction.PlayAnimation("wander_short")
            )

            8 -> listOf(
                RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
                RobotAction.PlayAnimation("body_bob")
            )

            else -> listOf(
                RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
                RobotAction.PlayAnimation("playful_invite")
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
        PersonalityEvent.FaceLost -> "Continuer son activité"
        is PersonalityEvent.CubeDetected -> "S'intéresser au cube"
        is PersonalityEvent.CubeTapped -> "Réagir au tap du cube"
        is PersonalityEvent.CubeMoved -> "Suivre le mouvement du cube"
        PersonalityEvent.PickedUp -> "Réagir au soulèvement"
        PersonalityEvent.PutDown -> "Réagir au retour au sol"
        PersonalityEvent.OnBack -> "Constater qu'il est sur le dos"
        PersonalityEvent.OnFace -> "Constater qu'il est sur la face"
        PersonalityEvent.OnSide -> "Constater qu'il est sur le côté"
        PersonalityEvent.Wheelie -> "Réagir au wheelie"
        PersonalityEvent.Falling -> "Réagir à la chute"
        PersonalityEvent.CliffDetected -> "Sécuriser le bord"
        PersonalityEvent.Touched -> "Réagir au contact"
        PersonalityEvent.UserInteraction -> "Répondre à l'utilisateur"
        PersonalityEvent.BatteryLow -> "Passer en énergie basse"
        PersonalityEvent.CubeLost -> "Chercher le cube"
        PersonalityEvent.IdleTick ->
            if (actions.isEmpty()) "Repos tranquille" else "Faire sa vie"
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
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 80
    }
}
