package fr.nvmods.cozmo.personality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
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

    fun reportRecoveredFault(message: String) {
        _state.value = _state.value.copy(
            recoveredFaults = _state.value.recoveredFaults + 1,
            lastFault = message.take(180),
            lastDecision = "Erreur interceptée, moteur conservé actif"
        )
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

        val executed = mutableListOf<RobotAction>()

        for (action in actions) {
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
                recoverFromActionFault(
                    action = action,
                    result = result,
                    event = event,
                    now = now,
                    executed = executed
                )
                return
            }
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

    private suspend fun recoverFromActionFault(
        action: RobotAction,
        result: ActionResult,
        event: PersonalityEvent,
        now: Long,
        executed: List<RobotAction>
    ) {
        val detail =
            (result.message ?: result.status.name)
                .take(160)

        _state.value = _state.value.copy(
            recoveredFaults = _state.value.recoveredFaults + 1,
            lastFault =
                (action::class.simpleName ?: "Action") +
                    " : " +
                    detail,
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
                RobotAction.Express(
                    "greeting",
                    PersonalitySoundCue.GREETING
                ),
                RobotAction.Backpack(PersonalityLight.BLUE)
            )

            PersonalityEvent.FaceLost -> emptyList()

            is PersonalityEvent.CubeDetected -> listOf(
                RobotAction.Express(
                    "cube_interest",
                    PersonalitySoundCue.CURIOUS
                )
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
                RobotAction.Express(
                    "picked_up",
                    PersonalitySoundCue.PICKED_UP
                )
            )

            PersonalityEvent.PutDown -> listOf(
                RobotAction.Express(
                    "put_down",
                    PersonalitySoundCue.PUT_DOWN
                )
            )

            PersonalityEvent.OnBack -> listOf(
                RobotAction.Stop,
                RobotAction.Express(
                    "on_back_notice",
                    PersonalitySoundCue.SURPRISED
                )
            )

            PersonalityEvent.OnFace -> listOf(
                RobotAction.Stop,
                RobotAction.Express(
                    "on_face_notice",
                    PersonalitySoundCue.SURPRISED
                )
            )

            PersonalityEvent.OnSide -> listOf(
                RobotAction.Stop,
                RobotAction.Express(
                    "on_side_notice",
                    PersonalitySoundCue.CURIOUS
                )
            )

            PersonalityEvent.Wheelie -> listOf(
                RobotAction.Stop,
                RobotAction.Express(
                    "wheelie_notice",
                    PersonalitySoundCue.EFFORT
                )
            )

            PersonalityEvent.Falling -> listOf(
                RobotAction.Stop,
                RobotAction.Express(
                    "fall_notice",
                    PersonalitySoundCue.SURPRISED
                )
            )

            PersonalityEvent.CliffDetected -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.CLIFF),
                RobotAction.Backpack(PersonalityLight.RED),
                RobotAction.PlayAnimation("cliff_notice")
            )

            PersonalityEvent.Touched -> listOf(
                RobotAction.Express(
                    "happy_small",
                    PersonalitySoundCue.HAPPY_SHORT
                )
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
                RobotAction.Express(
                    "low_energy",
                    PersonalitySoundCue.LOW_ENERGY
                )
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

        // Le Cozmo original enchaîne beaucoup de micro-réactions. Les gestes
        // restent courts pour préserver le transport, mais le rythme est
        // volontairement nettement plus élevé qu'en 0.14.
        val cooldownMs = when (state.mode) {
            PersonalityMode.CALME -> 2_400L
            PersonalityMode.NORMAL -> 900L
            PersonalityMode.JOUEUR -> 520L
        }

        if (now - lastAutonomousDecisionMs < cooldownMs) return emptyList()

        val chance = when (state.mode) {
            PersonalityMode.CALME -> 0.62f
            PersonalityMode.NORMAL -> 0.93f
            PersonalityMode.JOUEUR -> 0.985f
        }

        if (state.curiosity < 0.18f || random.nextFloat() > chance) {
            return emptyList()
        }

        lastAutonomousDecisionMs = now

        if (state.cubeVisible) {
            return when (random.nextInt(6)) {
                0 -> listOf(RobotAction.Express("cube_interest"))
                1 -> listOf(
                    RobotAction.Express(
                        "cube_peek",
                        PersonalitySoundCue.CURIOUS
                    )
                )
                2 -> listOf(RobotAction.Express("micro_scan"))
                3 -> listOf(RobotAction.Express("quick_bob"))
                4 -> listOf(
                    RobotAction.Express(
                        "small_bounce",
                        PersonalitySoundCue.HAPPY_SHORT
                    )
                )
                else -> listOf(RobotAction.Express("dash_peek"))
            }
        }

        return when (state.mode) {
            PersonalityMode.CALME ->
                when (random.nextInt(5)) {
                    0 -> listOf(RobotAction.PlayAnimation("idle_blink"))
                    1 -> listOf(RobotAction.PlayAnimation("curious_nod"))
                    2 -> listOf(RobotAction.Express("micro_scan"))
                    3 -> listOf(RobotAction.Express("head_peek"))
                    else -> listOf(
                        RobotAction.Express(
                            "small_bounce",
                            PersonalitySoundCue.CURIOUS
                        )
                    )
                }

            PersonalityMode.NORMAL ->
                when (random.nextInt(12)) {
                    0 -> listOf(RobotAction.PlayAnimation("idle_blink"))
                    1 -> listOf(RobotAction.Express("micro_scan"))
                    2 -> listOf(RobotAction.Express("quick_bob"))
                    3 -> listOf(RobotAction.Express("head_peek"))
                    4 -> listOf(RobotAction.Express("tiny_wiggle"))
                    5 -> listOf(RobotAction.Express("dash_peek"))
                    6 -> listOf(RobotAction.Express("curious_nod"))
                    7 -> listOf(RobotAction.Express("wander_short"))
                    8 -> listOf(
                        RobotAction.Express(
                            "body_bob",
                            PersonalitySoundCue.HAPPY_SHORT
                        )
                    )
                    9 -> listOf(
                        RobotAction.Express(
                            "look_around",
                            PersonalitySoundCue.CURIOUS
                        )
                    )
                    10 -> listOf(RobotAction.PlayAnimation("double_blink"))
                    else -> listOf(
                        RobotAction.Express(
                            "happy_small",
                            PersonalitySoundCue.PLAYFUL
                        )
                    )
                }

            PersonalityMode.JOUEUR ->
                when (random.nextInt(14)) {
                    0 -> listOf(RobotAction.Express("micro_scan"))
                    1 -> listOf(RobotAction.Express("quick_bob"))
                    2 -> listOf(RobotAction.Express("dash_peek"))
                    3 -> listOf(RobotAction.Express("tiny_wiggle"))
                    4 -> listOf(RobotAction.Express("wander_short"))
                    5 -> listOf(RobotAction.Express("curious_nod"))
                    6 -> listOf(
                        RobotAction.Express(
                            "excited_shuffle",
                            PersonalitySoundCue.PLAYFUL
                        )
                    )
                    7 -> listOf(
                        RobotAction.Express(
                            "small_bounce",
                            PersonalitySoundCue.HAPPY_SHORT
                        )
                    )
                    8 -> listOf(
                        RobotAction.Express(
                            "playful_invite",
                            PersonalitySoundCue.PLAYFUL
                        )
                    )
                    9 -> listOf(RobotAction.Express("head_peek"))
                    10 -> listOf(RobotAction.PlayAnimation("double_blink"))
                    11 -> listOf(
                        RobotAction.Express(
                            "body_bob",
                            PersonalitySoundCue.CURIOUS
                        )
                    )
                    12 -> listOf(
                        RobotAction.Express(
                            "happy_small",
                            PersonalitySoundCue.HAPPY_LONG
                        )
                    )
                    else -> listOf(
                        RobotAction.Express(
                            "look_around",
                            PersonalitySoundCue.CURIOUS
                        )
                    )
                }
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
        private const val ACTION_TIMEOUT_MS = 4_000L
    }
}
