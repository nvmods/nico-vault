package fr.nvmods.cozmo.personality

import kotlin.random.Random

data class BrainDecision(
    val label: String,
    val actions: List<RobotAction>,
    /** Événement émotionnel appliqué seulement si la séquence va au bout. */
    val onCompleteEmotion: String? = null,
    /** Identifiants d'origine (Anki) pour l'UI : activité et comportement. */
    val activityId: String? = null,
    val behaviorId: String? = null
)

private data class BehaviorOption(
    val id: String,
    val weight: Float,
    val actions: List<RobotAction>,
    val emotionEvent: String? = null,
    val needsAction: String? = null
)

/**
 * Logique de décision pure (pas de coroutines, horloge injectée) :
 *  - perceptions -> événements émotionnels / actions de besoins d'origine ;
 *  - réactions immédiates aux perceptions ;
 *  - frustration dérivée de Confident (seuils ReactToFrustrationMinor/Major) ;
 *  - choix d'activité freeplay (visage -> Socialize, cube -> PlayAlone,
 *    rien -> Hiking) avec durées / cooldowns d'origine ;
 *  - comportement choisi dans l'activité par OriginalBehaviorScheduler
 *    (scores et pénalités du profil d'origine), avec repli local.
 */
class PersonalityBrain(
    val tuning: PersonalityTuning,
    startMs: Long,
    private val random: Random = Random.Default,
    private val profile: OriginalBehaviorProfile = OriginalBehaviorProfile.fallback()
) {
    private val scheduler = OriginalBehaviorScheduler(profile, random)

    val mood = MoodManager(tuning.mood)
    val needs = NeedsManager(tuning.needs, startMs)

    var mode: PersonalityMode = PersonalityMode.NORMAL
    var faceVisible = false
        private set
    var cubeVisible = false
        private set
    var pickedUp = false
        private set
    var activity: Activity = Activity.NothingToDo
        private set

    private var activityStartMs = startMs
    private val activityCooldownUntil = mutableMapOf<Activity, Long>()
    private val behaviorLastUseMs = mutableMapOf<String, Long>()
    private var lastAutonomousMs = NEVER
    private var lastCubeMotionMs = NEVER
    private var lastMinorFrustrationMs = NEVER
    private var lastMajorFrustrationMs = NEVER
    private val disruptions = ArrayDeque<Long>()

    fun onEvent(event: PersonalityEvent, nowMs: Long): BrainDecision? {
        needs.update(nowMs)

        if (event == PersonalityEvent.IdleTick) {
            return frustrationReaction(nowMs) ?: idle(nowMs)
        }

        val reaction = react(event, nowMs)?.let { withOriginalReaction(event, it) }
        val frustration = frustrationReaction(nowMs)

        return when {
            frustration == null -> reaction
            reaction == null -> frustration
            else -> BrainDecision(
                label = reaction.label + " puis " + frustration.label.lowercase(),
                actions = reaction.actions + frustration.actions,
                onCompleteEmotion = frustration.onCompleteEmotion,
                activityId = "Reaction",
                behaviorId = frustration.behaviorId
            )
        }
    }

    /** Rattache une réaction à son behaviorID d'origine (reactionTrigger_behavior_map). */
    private fun withOriginalReaction(event: PersonalityEvent, d: BrainDecision): BrainDecision {
        val trigger = when (event) {
            is PersonalityEvent.FaceDetected -> "FacePositionUpdated"
            is PersonalityEvent.CubeDetected -> "ObjectPositionUpdated"
            is PersonalityEvent.CubeMoved -> "CubeMoved"
            PersonalityEvent.PickedUp -> "RobotPickedUp"
            PersonalityEvent.PutDown -> "ReturnedToTreads"
            PersonalityEvent.OnBack -> "RobotOnBack"
            PersonalityEvent.OnFace -> "RobotOnFace"
            PersonalityEvent.OnSide -> "RobotOnSide"
            PersonalityEvent.Falling -> "RobotFalling"
            PersonalityEvent.CliffDetected -> "CliffDetected"
            else -> null
        } ?: return d
        val behaviorId = profile.reaction(trigger) ?: return d
        return d.copy(
            label = OriginalBehaviorLabels.behavior(behaviorId),
            activityId = "Reaction",
            behaviorId = behaviorId
        )
    }

    fun onDecisionCompleted(decision: BrainDecision, nowMs: Long) {
        decision.onCompleteEmotion?.let { mood.trigger(it, nowMs) }
    }

    // ------------------------------------------------------------------
    // Réactions aux perceptions
    // ------------------------------------------------------------------

    private fun react(event: PersonalityEvent, now: Long): BrainDecision? = when (event) {
        is PersonalityEvent.FaceDetected -> {
            faceVisible = true
            // Détection = AcknowledgeFace d'origine ; le gros gain Social
            // vient ensuite des interactions pendant Socialize.
            mood.trigger("LookAtFaceVerified", now)
            needs.apply("SeeFace", now)
            BrainDecision(
                "Saluer la personne",
                listOf(
                    RobotAction.Stop,
                    RobotAction.Express("greeting", PersonalitySoundCue.GREETING),
                    RobotAction.Backpack(PersonalityLight.BLUE)
                )
            )
        }

        PersonalityEvent.FaceLost -> {
            faceVisible = false
            null
        }

        is PersonalityEvent.CubeDetected -> {
            cubeVisible = true
            mood.trigger("FoundObservedObject", now)
            BrainDecision(
                "S'intéresser au cube",
                listOf(RobotAction.Express("cube_interest", PersonalitySoundCue.CURIOUS))
            )
        }

        is PersonalityEvent.CubeTapped -> {
            mood.trigger("CubeTapped", now)
            needs.apply("CubePlay", now)
            BrainDecision("Réagir au tap du cube", cubeTapActions(event.cubeId))
        }

        is PersonalityEvent.CubeMoved -> {
            if (now - lastCubeMotionMs < CUBE_MOTION_COOLDOWN_MS) {
                null
            } else {
                lastCubeMotionMs = now
                mood.trigger("MotionReact", now)
                BrainDecision("Suivre le mouvement du cube", cubeMovedActions())
            }
        }

        PersonalityEvent.CubeLost -> {
            cubeVisible = false
            null
        }

        PersonalityEvent.PickedUp -> {
            pickedUp = true
            mood.trigger("ReactToUnexpectedMovement", now)
            disruption(now)
            BrainDecision(
                "Réagir au soulèvement",
                listOf(
                    RobotAction.Stop,
                    RobotAction.Express("picked_up", PersonalitySoundCue.PICKED_UP)
                )
            )
        }

        PersonalityEvent.PutDown -> {
            pickedUp = false
            BrainDecision(
                "Réagir au retour au sol",
                listOf(RobotAction.Express("put_down", PersonalitySoundCue.PUT_DOWN))
            )
        }

        PersonalityEvent.OnBack -> {
            disruption(now)
            BrainDecision(
                "Constater qu'il est sur le dos",
                listOf(
                    RobotAction.Stop,
                    RobotAction.Express("on_back_notice", PersonalitySoundCue.SURPRISED)
                )
            )
        }

        PersonalityEvent.OnFace -> {
            disruption(now)
            BrainDecision(
                "Constater qu'il est sur la face",
                listOf(
                    RobotAction.Stop,
                    RobotAction.Express("on_face_notice", PersonalitySoundCue.SURPRISED)
                )
            )
        }

        PersonalityEvent.OnSide -> {
            disruption(now)
            needs.apply("PlacedOnSide", now)
            BrainDecision(
                "Constater qu'il est sur le côté",
                listOf(
                    RobotAction.Stop,
                    RobotAction.Express("on_side_notice", PersonalitySoundCue.CURIOUS)
                )
            )
        }

        PersonalityEvent.Wheelie -> BrainDecision(
            "Réagir au wheelie",
            listOf(
                RobotAction.Stop,
                RobotAction.Express("wheelie_notice", PersonalitySoundCue.EFFORT)
            )
        )

        PersonalityEvent.Falling -> {
            needs.apply("Fall", now)
            disruption(now)
            BrainDecision(
                "Réagir à la chute",
                listOf(
                    RobotAction.Stop,
                    RobotAction.Express("fall_notice", PersonalitySoundCue.SURPRISED)
                )
            )
        }

        PersonalityEvent.CliffDetected -> {
            mood.trigger("CliffDetected", now)
            disruption(now)
            BrainDecision(
                "Sécuriser le bord",
                listOf(
                    RobotAction.Stop,
                    RobotAction.PlaySound(PersonalitySoundCue.CLIFF),
                    RobotAction.Backpack(PersonalityLight.RED),
                    RobotAction.PlayAnimation("cliff_notice")
                )
            )
        }

        PersonalityEvent.Touched -> {
            mood.trigger("Petted", now)
            BrainDecision(
                "Réagir au contact",
                listOf(RobotAction.Express("happy_small", PersonalitySoundCue.HAPPY_SHORT))
            )
        }

        PersonalityEvent.UserInteraction -> {
            mood.trigger("UserInteraction", now)
            BrainDecision(
                "Répondre à l'utilisateur",
                if (mode == PersonalityMode.JOUEUR) {
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
            )
        }

        PersonalityEvent.BatteryLow -> {
            needs.apply("BatteryLowSimulated", now)
            BrainDecision(
                "Passer en énergie basse",
                listOf(
                    RobotAction.Stop,
                    RobotAction.Express("low_energy", PersonalitySoundCue.LOW_ENERGY)
                )
            )
        }

        is PersonalityEvent.NeedsAction -> {
            if (!needs.apply(event.actionId, now)) {
                null
            } else if (event.actionId == "Feed") {
                BrainDecision(
                    "Manger",
                    listOf(RobotAction.Express("happy_small", PersonalitySoundCue.HAPPY_LONG))
                )
            } else {
                BrainDecision(
                    event.label,
                    listOf(RobotAction.Express("tiny_wiggle", PersonalitySoundCue.HAPPY_SHORT))
                )
            }
        }

        PersonalityEvent.IdleTick -> null
    }

    private fun cubeTapActions(cubeId: Long): List<RobotAction> = when (random.nextInt(4)) {
        0 -> listOf(
            RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
            RobotAction.CubeLight(cubeId, PersonalityLight.GREEN),
            RobotAction.MoveHead(-0.35f, 120),
            RobotAction.Wait(100),
            RobotAction.MoveHead(0.35f, 120),
            RobotAction.CubeLight(cubeId, PersonalityLight.BLUE)
        )

        1 -> listOf(
            RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
            RobotAction.CubeLight(cubeId, PersonalityLight.GREEN),
            RobotAction.PlayAnimation("small_bounce"),
            RobotAction.CubeLight(cubeId, PersonalityLight.BLUE)
        )

        2 -> listOf(
            RobotAction.PlaySound(PersonalitySoundCue.PLAYFUL),
            RobotAction.PlayAnimation("tiny_wiggle"),
            RobotAction.CubeLight(cubeId, PersonalityLight.GREEN)
        )

        else -> listOf(
            RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
            RobotAction.PlayAnimation("cube_peek"),
            RobotAction.CubeLight(cubeId, PersonalityLight.BLUE)
        )
    }

    private fun cubeMovedActions(): List<RobotAction> = when (random.nextInt(3)) {
        0 -> listOf(
            RobotAction.PlaySound(PersonalitySoundCue.CURIOUS),
            RobotAction.PlayAnimation("cube_peek")
        )

        1 -> listOf(RobotAction.PlayAnimation("curious_nod"))

        else -> listOf(
            RobotAction.PlaySound(PersonalitySoundCue.HAPPY_SHORT),
            RobotAction.PlayAnimation("small_bounce")
        )
    }

    // ------------------------------------------------------------------
    // Frustration
    // ------------------------------------------------------------------

    /**
     * Équivalent de TooManyResumesCliffOrMovement : trop d'interruptions
     * physiques (falaise, chute, soulèvement, retournement) en peu de temps.
     */
    private fun disruption(now: Long) {
        val f = tuning.frustration
        val windowMs = (f.disruptionWindowSec * 1000f).toLong()
        disruptions.addLast(now)
        while (disruptions.isNotEmpty() && now - disruptions.first() > windowMs) {
            disruptions.removeFirst()
        }
        if (disruptions.size >= f.disruptionCount) {
            disruptions.clear()
            mood.trigger("TooManyResumesCliffOrMovement", now)
        }
    }

    private fun frustrationReaction(now: Long): BrainDecision? {
        val f = tuning.frustration
        val confident = mood.value(EmotionType.Confident, now)

        if (confident <= f.majorMaxConfident && now - lastMajorFrustrationMs >= MAJOR_GUARD_MS) {
            lastMajorFrustrationMs = now
            return BrainDecision(
                behaviorId = "ReactToFrustrationMajor",
                activityId = "Reaction",
                label = "Frustration majeure",
                actions = listOf(
                    RobotAction.Stop,
                    RobotAction.Backpack(PersonalityLight.RED),
                    RobotAction.Express("on_face_notice", PersonalitySoundCue.ANGRY),
                    RobotAction.Wait(400),
                    RobotAction.Express("low_energy", PersonalitySoundCue.SAD),
                    RobotAction.Backpack(PersonalityLight.OFF)
                ),
                onCompleteEmotion = "FinishedMajorFrustration"
            )
        }

        val minorCooldownMs = (f.minorCooldownSec * 1000f).toLong()
        if (
            confident <= f.minorMaxConfident &&
            confident > f.majorMaxConfident &&
            now - lastMinorFrustrationMs >= minorCooldownMs
        ) {
            lastMinorFrustrationMs = now
            return BrainDecision(
                behaviorId = "ReactToFrustrationMinor",
                activityId = "Reaction",
                label = "Frustration mineure",
                actions = listOf(RobotAction.Express("on_face_notice", PersonalitySoundCue.ANGRY)),
                onCompleteEmotion = "FinishedMinorFrustration"
            )
        }

        return null
    }

    // ------------------------------------------------------------------
    // Freeplay : activité puis comportement
    // ------------------------------------------------------------------

    private fun idle(now: Long): BrainDecision? {
        if (pickedUp) return null

        val current = chooseActivity(now)

        val baseCooldown = when (mode) {
            PersonalityMode.CALME -> 2_400L
            PersonalityMode.NORMAL -> 900L
            PersonalityMode.JOUEUR -> 520L
        }
        val cooldownMs = if (current.isSevere()) baseCooldown * 3 else baseCooldown
        if (now - lastAutonomousMs < cooldownMs) return null

        val chance = when (mode) {
            PersonalityMode.CALME -> 0.62f
            PersonalityMode.NORMAL -> 0.93f
            PersonalityMode.JOUEUR -> 0.985f
        }
        if (random.nextFloat() > chance) return null

        if (!current.isSevere()) {
            scheduler.pick(current.name, stateView(), now)?.let { original ->
                lastAutonomousMs = now
                applyBehaviorEffects(Activity.entries.firstOrNull { it.name == original.activityId } ?: current, original.behaviorId, now)
                return BrainDecision(
                    label = OriginalBehaviorLabels.behavior(original.behaviorId),
                    actions = original.actions,
                    activityId = original.activityId,
                    behaviorId = original.behaviorId
                )
            }
        }

        val option = pickBehavior(current, now) ?: return null
        lastAutonomousMs = now
        behaviorLastUseMs[option.id] = now
        option.emotionEvent?.let { mood.trigger(it, now) }
        option.needsAction?.let { needs.apply(it, now) }

        return BrainDecision(
            label = current.label + " : " + option.id,
            actions = option.actions,
            activityId = current.name,
            behaviorId = option.id
        )
    }

    /** Effets humeur / besoins d'un comportement choisi par le scheduler. */
    private fun applyBehaviorEffects(activity: Activity, behaviorId: String, now: Long) {
        when (activity) {
            Activity.Socialize ->
                mood.trigger(
                    if (behaviorId in SOCIAL_INTERACTIONS) "InteractWithUnnamedFace" else "LookAtFaceVerified",
                    now
                )
            Activity.PlayWithHumans -> {
                mood.trigger("LookAtFaceVerified", now)
                needs.apply("SeeFace", now)
            }
            Activity.PlayAlone -> needs.apply("PlayAloneBehavior", now)
            else -> Unit
        }
    }

    /** Vue minimale attendue par OriginalBehaviorScheduler. */
    private fun stateView() = PersonalityState(
        mode = mode,
        energy = needs.level(NeedId.Energy),
        pickedUp = pickedUp,
        knownFaceVisible = faceVisible,
        cubeVisible = cubeVisible
    )

    private fun chooseActivity(now: Long): Activity {
        severeActivity()?.let {
            switchTo(it, now)
            return it
        }

        val cur = activity
        val elapsedSec = (now - activityStartMs) / 1000f
        val timing = tuning.timing(cur)

        fun ready(a: Activity) = now >= (activityCooldownUntil[a] ?: Long.MIN_VALUE)

        val stillValid = when (cur) {
            Activity.Socialize, Activity.PlayWithHumans -> faceVisible
            Activity.PlayAlone -> cubeVisible
            Activity.Hiking -> true
            else -> false
        }
        val expired = timing.shouldEndSec > 0f && elapsedSec >= timing.shouldEndSec

        val candidates = listOf(
            Activity.Socialize to (
                faceVisible &&
                    mood.value(EmotionType.Social, now) <= tuning.socializeMaxSocial
                ),
            Activity.PlayWithHumans to faceVisible,
            Activity.PlayAlone to cubeVisible,
            Activity.Hiking to true
        )
        val best = candidates.firstOrNull { (a, ok) -> ok && ready(a) }?.first
            ?: Activity.NothingToDo

        if (stillValid && !expired) {
            // Une activité mieux placée peut préempter après un minimum
            // de temps (activityCanEndDurationSecs d'origine : 5 s).
            val preempt = best.rank() < cur.rank() && elapsedSec >= CAN_END_SEC
            if (!preempt) return cur
        } else if (cur.rank() in 0..3) {
            activityCooldownUntil[cur] = now + (timing.cooldownSec * 1000f).toLong()
        }

        val next = if (!stillValid || expired) {
            candidates.firstOrNull { (a, ok) -> ok && ready(a) }?.first ?: Activity.NothingToDo
        } else {
            best
        }
        switchTo(next, now)
        return next
    }

    private fun severeActivity(): Activity? = when {
        needs.bracket(NeedId.Repair) == NeedBracket.Critical -> Activity.NeedsSevereLowRepair
        needs.bracket(NeedId.Energy) == NeedBracket.Critical -> Activity.NeedsSevereLowEnergy
        needs.bracket(NeedId.Play) == NeedBracket.Critical -> Activity.NeedsSevereLowPlay
        else -> null
    }

    private fun switchTo(a: Activity, now: Long) {
        if (a != activity) {
            activity = a
            activityStartMs = now
        }
    }

    private fun Activity.isSevere() = name.startsWith("NeedsSevere")

    private fun Activity.rank(): Int = when (this) {
        Activity.Socialize -> 0
        Activity.PlayWithHumans -> 1
        Activity.PlayAlone -> 2
        Activity.Hiking -> 3
        Activity.NothingToDo -> 4
        else -> -1
    }

    private fun pickBehavior(a: Activity, now: Long): BehaviorOption? {
        val pool = pools.getValue(a)
        val weighted = pool.map { opt ->
            val last = behaviorLastUseMs[opt.id]
            val penalty = if (last == null) 1f else BEHAVIOR_REPETITION.eval((now - last) / 1000f)
            opt to opt.weight * penalty
        }.filter { it.second > 0f }
        val total = weighted.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f) return null
        var r = random.nextFloat() * total
        for ((opt, w) in weighted) {
            r -= w
            if (r <= 0f) return opt
        }
        return weighted.last().first
    }

    private val pools: Map<Activity, List<BehaviorOption>> = run {
        fun express(name: String, cue: PersonalitySoundCue? = null) =
            listOf(RobotAction.Express(name, cue))

        fun anim(name: String) = listOf(RobotAction.PlayAnimation(name))

        val social = "LookAtFaceVerified"
        val interact = "InteractWithUnnamedFace"
        val alone = "PlayAloneBehavior"

        mapOf(
            Activity.Socialize to listOf(
                BehaviorOption("regarder", 1f, express("look_around", PersonalitySoundCue.CURIOUS), social),
                BehaviorOption("acquiescer", 1f, anim("acknowledge"), social),
                BehaviorOption("hocher", 1f, anim("curious_nod"), social),
                BehaviorOption("content", 0.8f, express("happy_small", PersonalitySoundCue.HAPPY_SHORT), social),
                BehaviorOption("inviter", 0.6f, express("playful_invite", PersonalitySoundCue.PLAYFUL), interact),
                BehaviorOption("saluer", 0.3f, express("greeting", PersonalitySoundCue.GREETING), interact)
            ),
            Activity.PlayWithHumans to listOf(
                BehaviorOption("inviter à jouer", 1f, express("playful_invite", PersonalitySoundCue.PLAYFUL), social),
                BehaviorOption("rebondir", 1f, express("small_bounce", PersonalitySoundCue.HAPPY_SHORT), social)
            ),
            Activity.PlayAlone to listOf(
                BehaviorOption("fixer le cube", 1f, express("cube_interest", PersonalitySoundCue.CURIOUS), needsAction = alone),
                BehaviorOption("épier le cube", 1f, express("cube_peek"), needsAction = alone),
                BehaviorOption("sautiller", 0.8f, express("small_bounce", PersonalitySoundCue.HAPPY_SHORT), needsAction = alone),
                BehaviorOption("foncer voir", 0.8f, express("dash_peek"), needsAction = alone),
                BehaviorOption("dodeliner", 0.8f, express("quick_bob"), needsAction = alone),
                BehaviorOption("trépigner", 0.5f, express("excited_shuffle", PersonalitySoundCue.PLAYFUL), needsAction = alone)
            ),
            Activity.Hiking to listOf(
                BehaviorOption("se balader", 1.25f, express("wander_short")),
                BehaviorOption("regarder autour", 1.1f, express("look_around", PersonalitySoundCue.CURIOUS)),
                BehaviorOption("scanner", 1f, express("micro_scan")),
                BehaviorOption("lever la tête", 1f, express("head_peek")),
                BehaviorOption("foncer voir", 0.8f, express("dash_peek")),
                BehaviorOption("hocher", 0.6f, anim("curious_nod"))
            ),
            Activity.NothingToDo to listOf(
                BehaviorOption("cligner", 1f, anim("idle_blink")),
                BehaviorOption("double clignement", 1f, anim("double_blink")),
                BehaviorOption("s'ennuyer", 0.5f, express("body_bob", PersonalitySoundCue.BORED))
            ),
            Activity.NeedsSevereLowEnergy to listOf(
                BehaviorOption("fatigué", 1f, express("low_energy", PersonalitySoundCue.LOW_ENERGY)),
                BehaviorOption("somnoler", 1f, anim("idle_blink"))
            ),
            Activity.NeedsSevereLowRepair to listOf(
                BehaviorOption("grincer", 1f, express("low_energy", PersonalitySoundCue.SAD)),
                BehaviorOption("forcer", 0.5f, express("tiny_wiggle", PersonalitySoundCue.EFFORT)),
                BehaviorOption("cligner", 1f, anim("idle_blink"))
            ),
            Activity.NeedsSevereLowPlay to listOf(
                BehaviorOption("réclamer", 1f, express("playful_invite", PersonalitySoundCue.PLAYFUL)),
                BehaviorOption("bouder", 1f, express("body_bob", PersonalitySoundCue.BORED)),
                BehaviorOption("cligner", 0.5f, anim("idle_blink"))
            )
        )
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2
        private const val CUBE_MOTION_COOLDOWN_MS = 1_800L
        private const val MAJOR_GUARD_MS = 10_000L
        private const val CAN_END_SEC = 5f

        /** Pénalité de répétition par comportement (CUSTOM, courte). */
        private val BEHAVIOR_REPETITION = Curve.of(0 to 0.05, 20 to 1)

        /** Comportements Socialize qui constituent une vraie interaction. */
        private val SOCIAL_INTERACTIONS = setOf("FPPeekABoo", "PounceOnMotion_Socialize")
    }
}
