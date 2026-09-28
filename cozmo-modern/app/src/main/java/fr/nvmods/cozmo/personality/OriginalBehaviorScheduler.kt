package fr.nvmods.cozmo.personality

import kotlin.math.max
import kotlin.random.Random

data class OriginalBehaviorDecision(
    val activityId: String,
    val behaviorId: String,
    val actions: List<RobotAction>
)

/**
 * Adaptateur entre les tables de comportement Anki 3.6.6 et notre couche
 * moteur moderne.
 *
 * Les scores et pénalités viennent de l'OBB original. La "runnability"
 * (un visage est visible, un cube est disponible, etc.) est reconstruite
 * avec les perceptions que Cozmo Modern sait actuellement fournir.
 *
 * Les comportements demandant une localisation/docking de cube très précise
 * restent volontairement non exécutables pour le moment : ils sont conservés
 * dans le profil, mais filtrés ici.
 */
class OriginalBehaviorScheduler(
    private val profile: OriginalBehaviorProfile,
    private val random: Random = Random.Default
) {
    private val lastBehaviorMs = mutableMapOf<String, Long>()

    fun reaction(
        trigger: String,
        state: PersonalityState,
        now: Long
    ): OriginalBehaviorDecision? {
        val behaviorId = profile.reaction(trigger) ?: return null
        val actions = actionsFor(behaviorId, state) ?: return null
        lastBehaviorMs[behaviorId] = now
        return OriginalBehaviorDecision(
            activityId = "Reaction",
            behaviorId = behaviorId,
            actions = actions
        )
    }

    /**
     * Choisit un comportement d'origine dans l'activité imposée par
     * PersonalityBrain (qui gère besoins, humeur et durées d'activité).
     * Repli sur NothingToDo si aucun comportement n'est exécutable.
     */
    fun pick(
        activityId: String,
        state: PersonalityState,
        now: Long
    ): OriginalBehaviorDecision? = chooseFromActivity(activityId, state, now)

    private fun chooseFromActivity(
        activityId: String,
        state: PersonalityState,
        now: Long
    ): OriginalBehaviorDecision? {
        val activity = profile.activities[activityId] ?: return null

        val candidates = activity.behaviors.mapNotNull { rule ->
            val actions = actionsFor(rule.id, state) ?: return@mapNotNull null
            val multiplier = repetitionMultiplier(rule, now)
            val base = if (rule.score > 0.0) rule.score else directObjectiveWeight(rule.id)
            val effective = max(0.0, base * multiplier)
            if (effective <= 0.0) return@mapNotNull null
            Candidate(rule.id, effective, actions)
        }

        if (candidates.isEmpty()) {
            return if (activityId != "NothingToDo") {
                chooseFromActivity("NothingToDo", state, now)
            } else {
                null
            }
        }

        val chosen = weightedChoice(candidates)
        lastBehaviorMs[chosen.behaviorId] = now

        return OriginalBehaviorDecision(
            activityId = activityId,
            behaviorId = chosen.behaviorId,
            actions = chosen.actions
        )
    }

    private fun repetitionMultiplier(
        rule: OriginalBehaviorRule,
        now: Long
    ): Double {
        val points = rule.repetitionPenalty
        if (points.isEmpty()) return 1.0

        val previous = lastBehaviorMs[rule.id] ?: return 1.0
        val elapsedSeconds = (now - previous).coerceAtLeast(0L) / 1000.0

        if (elapsedSeconds <= points.first().seconds) {
            return points.first().multiplier
        }

        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            if (elapsedSeconds <= b.seconds) {
                val width = b.seconds - a.seconds
                if (width <= 0.0) return b.multiplier
                val t = (elapsedSeconds - a.seconds) / width
                return a.multiplier + (b.multiplier - a.multiplier) * t
            }
        }

        return points.last().multiplier
    }

    private fun weightedChoice(candidates: List<Candidate>): Candidate {
        val total = candidates.sumOf { it.weight }
        if (total <= 0.0) return candidates.random(random)

        var cursor = random.nextDouble(total)
        candidates.forEach { candidate ->
            cursor -= candidate.weight
            if (cursor <= 0.0) return candidate
        }
        return candidates.last()
    }

    private fun directObjectiveWeight(behaviorId: String): Double =
        when (behaviorId) {
            "FPPeekABoo" -> 1.0
            "PounceOnMotion_Socialize" -> 1.0
            else -> 0.0
        }

    private fun actionsFor(
        behaviorId: String,
        state: PersonalityState
    ): List<RobotAction>? =
        when {
            behaviorId == "AcknowledgeFace" -> listOf(
                RobotAction.Stop,
                RobotAction.Express("greeting", PersonalitySoundCue.GREETING),
                RobotAction.Backpack(PersonalityLight.BLUE)
            )

            behaviorId == "AcknowledgeObject" -> listOf(
                RobotAction.Express("cube_interest", PersonalitySoundCue.CURIOUS)
            )

            behaviorId == "ReactToCubeMoved" -> listOf(
                RobotAction.Express("cube_peek", PersonalitySoundCue.CURIOUS)
            )

            behaviorId == "ReactToPickup" -> listOf(
                RobotAction.Stop,
                RobotAction.Express("picked_up", PersonalitySoundCue.PICKED_UP)
            )

            behaviorId == "ReactToReturnedToTreads" -> listOf(
                RobotAction.Express("put_down", PersonalitySoundCue.PUT_DOWN)
            )

            behaviorId == "ReactToRobotOnBack" -> listOf(
                RobotAction.Stop,
                RobotAction.Express("on_back_notice", PersonalitySoundCue.SURPRISED)
            )

            behaviorId == "ReactToRobotOnFace" -> listOf(
                RobotAction.Stop,
                RobotAction.Express("on_face_notice", PersonalitySoundCue.SURPRISED)
            )

            behaviorId == "ReactToRobotOnSide" -> listOf(
                RobotAction.Stop,
                RobotAction.Express("on_side_notice", PersonalitySoundCue.CURIOUS)
            )

            behaviorId == "ReactToImpact" -> listOf(
                RobotAction.Stop,
                RobotAction.Express("fall_notice", PersonalitySoundCue.SURPRISED)
            )

            behaviorId == "ReactToCliff" -> listOf(
                RobotAction.Stop,
                RobotAction.PlaySound(PersonalitySoundCue.CLIFF),
                RobotAction.Backpack(PersonalityLight.RED),
                RobotAction.PlayAnimation("cliff_notice")
            )

            behaviorId == "ReactToRobotShaken" -> listOf(
                RobotAction.Stop,
                RobotAction.Express("excited_shuffle", PersonalitySoundCue.SURPRISED)
            )

            behaviorId == "ReactToOnCharger" -> listOf(
                RobotAction.Express("low_energy", PersonalitySoundCue.SLEEPY)
            )

            behaviorId == "NothingToDo_Idle" ->
                when (random.nextInt(5)) {
                    0 -> listOf(RobotAction.PlayAnimation("idle_blink"))
                    1 -> listOf(RobotAction.PlayAnimation("double_blink"))
                    2 -> listOf(RobotAction.Express("micro_scan"))
                    3 -> listOf(RobotAction.Express("head_peek"))
                    else -> listOf(RobotAction.Express("curious_nod"))
                }

            behaviorId == "NothingToDo_BoredAnim" ->
                if (state.energy < 0.35f) {
                    listOf(
                        RobotAction.Express(
                            "low_energy",
                            PersonalitySoundCue.BORED
                        )
                    )
                } else {
                    listOf(
                        RobotAction.Express(
                            "body_bob",
                            PersonalitySoundCue.CURIOUS
                        )
                    )
                }

            behaviorId == "Hiking_FirstLookIntro" ||
                behaviorId == "Hiking_FirstLookWakeUp" ->
                listOf(
                    RobotAction.Express("look_around", PersonalitySoundCue.CURIOUS)
                )

            behaviorId == "Hiking_ThinkAboutBeacons" ->
                listOf(RobotAction.Express("micro_scan"))

            behaviorId == "Hiking_PounceOnMotion" ->
                listOf(
                    RobotAction.Express("dash_peek", PersonalitySoundCue.PLAYFUL)
                )

            behaviorId == "Hiking_VisitInterestingEdge" ->
                listOf(RobotAction.Express("wander_short"))

            behaviorId == "Hiking_LookInPlaceForUnknown" ->
                listOf(
                    RobotAction.Express("head_peek", PersonalitySoundCue.CURIOUS)
                )

            behaviorId == "GuardDog" ->
                listOf(
                    RobotAction.Express("micro_scan", PersonalitySoundCue.CURIOUS),
                    RobotAction.Express("tiny_wiggle")
                )

            behaviorId == "CubeLiftWorkout" ->
                listOf(
                    RobotAction.Express("body_bob", PersonalitySoundCue.EFFORT),
                    RobotAction.Express("quick_bob")
                )

            behaviorId == "SparksFireTruckAlarm" ->
                listOf(
                    RobotAction.Express("excited_shuffle", PersonalitySoundCue.PLAYFUL)
                )

            behaviorId == "Bouncer" ->
                listOf(
                    RobotAction.Express("small_bounce", PersonalitySoundCue.HAPPY_SHORT)
                )

            behaviorId.startsWith("Request") ->
                listOf(
                    RobotAction.Express("playful_invite", PersonalitySoundCue.PLAYFUL)
                )

            behaviorId == "FPPeekABoo" ->
                listOf(
                    RobotAction.Express("head_peek", PersonalitySoundCue.PLAYFUL)
                )

            behaviorId == "PounceOnMotion_Socialize" ->
                listOf(
                    RobotAction.Express("dash_peek", PersonalitySoundCue.PLAYFUL)
                )

            behaviorId.startsWith("Singing_") ->
                listOf(
                    RobotAction.Express(
                        "body_bob",
                        PersonalitySoundCue.HAPPY_LONG
                    ),
                    RobotAction.Express("quick_bob")
                )

            // Conservés dans le profil mais non activés tant que notre
            // localisation/docking des cubes n'est pas suffisamment sûre.
            behaviorId == "RollBlockOnSide" ||
                behaviorId == "RollBlockOnSideLowScore" ||
                behaviorId == "StackBlocks" ||
                behaviorId == "PopAWheelie" ||
                behaviorId == "KnockOverCubes" ||
                behaviorId == "Hiking_RollCube" ||
                behaviorId == "Hiking_BringCubeToBeacon" ||
                behaviorId == "PutDownBlock" ||
                behaviorId == "PutDownBlockNothingToDo" ||
                behaviorId == "DriveOffCharger" ||
                behaviorId == "Hiking_DriveOffCharger" ||
                behaviorId == "ReactToObstacle" -> null

            else -> null
        }

    private data class Candidate(
        val behaviorId: String,
        val weight: Double,
        val actions: List<RobotAction>
    )
}

object OriginalBehaviorLabels {
    fun activity(id: String): String =
        when (id) {
            "Reaction" -> "Réaction"
            "Socialize" -> "Avec toi"
            "Singing" -> "Chant"
            "PlayWithHumans" -> "Jeu ensemble"
            "BuildPyramid" -> "Construction"
            "PlayAlone" -> "Jeu autonome"
            "Hiking" -> "Exploration"
            "NothingToDo" -> "Temps libre"
            "NeedsSevereLowEnergy" -> "Épuisé"
            "NeedsSevereLowRepair" -> "Besoin de réparation"
            "NeedsSevereLowPlay" -> "Besoin de jouer"
            else -> id
        }

    fun behavior(id: String): String =
        when {
            id == "AcknowledgeFace" -> "Il t'a remarqué"
            id == "AcknowledgeObject" -> "Il observe un cube"
            id == "ReactToCubeMoved" -> "Le cube a bougé"
            id == "ReactToPickup" -> "Il réagit au soulèvement"
            id == "ReactToReturnedToTreads" -> "De retour sur ses chenilles"
            id == "ReactToRobotOnBack" -> "Il est sur le dos"
            id == "ReactToRobotOnFace" -> "Il est sur la face"
            id == "ReactToRobotOnSide" -> "Il est sur le côté"
            id == "ReactToImpact" -> "Il a senti la chute"
            id == "ReactToCliff" -> "Attention au bord"
            id == "NothingToDo_Idle" -> "Il regarde autour de lui"
            id == "NothingToDo_BoredAnim" -> "Il commence à s'ennuyer"
            id.startsWith("Hiking_") -> "Il explore"
            id == "GuardDog" -> "Il surveille son cube"
            id == "CubeLiftWorkout" -> "Petit entraînement"
            id == "Bouncer" -> "Il veut jouer"
            id.startsWith("Request") -> "Il propose une activité"
            id == "FPPeekABoo" -> "Coucou !"
            id == "PounceOnMotion_Socialize" -> "Il a vu quelque chose bouger"
            id.startsWith("Singing_") -> "Il fredonne"
            id == "ReactToFrustrationMinor" -> "Il s'agace"
            id == "ReactToFrustrationMajor" -> "Grosse frustration !"
            else -> id.replace('_', ' ')
        }
}
