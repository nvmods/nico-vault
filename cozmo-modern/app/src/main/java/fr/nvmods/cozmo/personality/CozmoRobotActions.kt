package fr.nvmods.cozmo.personality

import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.ConnectionState
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoFaceAnimation
import fr.nvmods.cozmo.protocol.CozmoFaceExpression
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Adaptateur entre la personnalité et le vrai robot.
 *
 * Point important : toute impulsion moteur possède maintenant un finally.
 * Une interruption au milieu d'une animation ne peut donc plus laisser une
 * chenille, la tête ou le lift continuer faute d'avoir reçu son ordre STOP.
 */
class CozmoRobotActions(
    private val connection: CozmoConnection
) : RobotActions {

    override suspend fun execute(action: RobotAction): ActionResult {
        if (
            action !is RobotAction.Wait &&
            connection.state.value.connection != ConnectionState.READY
        ) {
            return ActionResult(
                ActionStatus.NOT_AVAILABLE,
                "Cozmo n'est pas READY"
            )
        }

        return when (action) {
            RobotAction.Stop -> {
                connection.stopAllMotors()
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.Drive -> {
                drivePulse(
                    action.leftMmps,
                    action.rightMmps,
                    action.durationMs.coerceIn(20L, 1_500L)
                )
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.MoveHead -> {
                headPulse(
                    action.speedRadPerSec.coerceIn(-1.5f, 1.5f),
                    action.durationMs.coerceIn(20L, 800L)
                )
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.MoveLift -> {
                liftPulse(
                    action.speedRadPerSec.coerceIn(-1.5f, 1.5f),
                    action.durationMs.coerceIn(20L, 800L)
                )
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.Backpack -> {
                connection.setBackpackColor(action.light.toBackpack())
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.CubeLight -> {
                connection.setCubeColor(
                    action.cubeId,
                    action.light.toBackpack()
                )
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.PlaySound -> {
                connection.playPcm22050(
                    PersonalityAudioCatalog.samples(action.cue)
                )
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.PlayAnimation ->
                playMotionMacro(action.name)

            is RobotAction.Express ->
                coroutineScope {
                    val audioJob =
                        action.cue?.let { cue ->
                            launch {
                                connection.playPcm22050(
                                    PersonalityAudioCatalog.samples(cue)
                                )
                            }
                        }

                    val result = playMotionMacro(action.name)
                    audioJob?.join()
                    result
                }

            is RobotAction.Speak -> {
                ActionResult(
                    ActionStatus.NOT_AVAILABLE,
                    "La parole passe encore par AndroidSpeechBridge"
                )
            }

            is RobotAction.Wait -> {
                delay(action.durationMs.coerceIn(0L, 2_000L))
                ActionResult(ActionStatus.SUCCESS)
            }
        }
    }

    private suspend fun playMotionMacro(name: String): ActionResult =
        coroutineScope {
            var completedNormally = false
            val faceJob = launch {
                when (name) {
                    "double_blink" -> {
                        connection.playFaceAnimation(CozmoFaceAnimation.BLINK)
                        delay(55)
                        connection.playFaceAnimation(CozmoFaceAnimation.BLINK)
                    }

                    else -> faceAnimationFor(name)?.let {
                        connection.playFaceAnimation(it)
                    }
                }
            }

            try {
                val known = when (name) {
                    "hiking_intro_original" -> {
                        playOriginalHikingIntro()
                        true
                    }

                    "hiking_visit_edge_original" -> {
                        playOriginalHikingDrive()
                        true
                    }

                    "hiking_scan_original" -> {
                        playOriginalHikingScan()
                        true
                    }

                    "cliff_react_original" -> {
                        playOriginalCliffReaction()
                        true
                    }

                    "greeting" -> {
                        parallelPulse(
                            head = 0.95f to 115L,
                            lift = 0.48f to 105L
                        )
                        parallelPulse(
                            drive = (-34f to 34f) to 95L,
                            head = -0.55f to 85L
                        )
                        true
                    }

                    "cube_interest" -> {
                        parallelPulse(
                            head = -0.72f to 105L,
                            lift = 0.62f to 90L
                        )
                        parallelPulse(
                            head = 0.48f to 75L,
                            lift = -0.46f to 70L
                        )
                        true
                    }

                    "picked_up" -> {
                        parallelPulse(
                            head = 0.90f to 95L,
                            lift = 0.78f to 100L
                        )
                        true
                    }

                    "put_down" -> {
                        parallelPulse(
                            head = 0.45f to 80L,
                            lift = -0.55f to 80L
                        )
                        true
                    }

                    "happy_small" -> {
                        parallelPulse(
                            drive = (-30f to 30f) to 90L,
                            head = 0.82f to 90L,
                            lift = 0.54f to 80L
                        )
                        parallelPulse(
                            drive = (30f to -30f) to 90L,
                            head = -0.68f to 85L,
                            lift = -0.42f to 70L
                        )
                        true
                    }

                    "playful_invite" -> {
                        drivePulse(-58f, 58f, 105)
                        drivePulse(62f, -62f, 165)
                        parallelPulse(
                            drive = (-52f to 52f) to 95L,
                            head = 0.75f to 90L,
                            lift = 0.55f to 85L
                        )
                        true
                    }

                    "acknowledge" -> {
                        headPulse(-0.62f, 70)
                        headPulse(0.62f, 75)
                        true
                    }

                    "low_energy" -> {
                        parallelPulse(
                            head = -0.42f to 170L,
                            lift = -0.38f to 160L
                        )
                        true
                    }

                    "look_around" -> {
                        parallelPulse(
                            drive = (-40f to 40f) to 125L,
                            head = 0.42f to 105L
                        )
                        parallelPulse(
                            drive = (42f to -42f) to 225L,
                            head = -0.55f to 130L
                        )
                        drivePulse(-36f, 36f, 105)
                        true
                    }

                    "idle_blink" -> {
                        delay(85)
                        true
                    }

                    "double_blink" -> {
                        delay(120)
                        true
                    }

                    "curious_nod" -> {
                        headPulse(-0.62f, 75)
                        headPulse(0.72f, 85)
                        true
                    }

                    "head_peek" -> {
                        parallelPulse(
                            head = 0.68f to 95L,
                            drive = (-24f to 24f) to 75L
                        )
                        headPulse(-0.38f, 65)
                        true
                    }

                    "small_bounce" -> {
                        parallelPulse(
                            head = 0.62f to 70L,
                            lift = 0.70f to 75L
                        )
                        parallelPulse(
                            head = -0.42f to 60L,
                            lift = -0.56f to 65L
                        )
                        true
                    }

                    "tiny_wiggle" -> {
                        drivePulse(-36f, 36f, 75)
                        drivePulse(38f, -38f, 125)
                        drivePulse(-36f, 36f, 75)
                        true
                    }

                    "cube_peek" -> {
                        parallelPulse(
                            head = -0.68f to 95L,
                            lift = 0.50f to 75L
                        )
                        delay(65)
                        liftPulse(-0.42f, 65)
                        true
                    }

                    "on_back_notice" -> {
                        headPulse(0.42f, 80)
                        true
                    }

                    "on_face_notice" -> {
                        liftPulse(-0.36f, 75)
                        true
                    }

                    "on_side_notice" -> {
                        headPulse(0.42f, 70)
                        true
                    }

                    "wheelie_notice" -> {
                        parallelPulse(
                            head = 0.42f to 75L,
                            lift = 0.46f to 80L
                        )
                        true
                    }

                    "wander_short" -> {
                        parallelPulse(
                            drive = (48f to 48f) to 180L,
                            head = 0.34f to 120L
                        )
                        drivePulse(34f, -34f, 105)
                        true
                    }

                    "body_bob" -> {
                        parallelPulse(
                            head = 0.58f to 75L,
                            lift = 0.64f to 80L
                        )
                        parallelPulse(
                            head = -0.48f to 65L,
                            lift = -0.52f to 70L
                        )
                        true
                    }

                    // Micro-animations plus proches du rythme du Cozmo original :
                    // rapides, fréquentes et avec plusieurs axes en parallèle.
                    "micro_scan" -> {
                        parallelPulse(
                            drive = (-28f to 28f) to 70L,
                            head = 0.55f to 75L
                        )
                        drivePulse(28f, -28f, 80)
                        true
                    }

                    "quick_bob" -> {
                        parallelPulse(
                            head = 0.72f to 65L,
                            lift = 0.78f to 70L
                        )
                        parallelPulse(
                            head = -0.55f to 55L,
                            lift = -0.65f to 60L
                        )
                        true
                    }

                    "dash_peek" -> {
                        parallelPulse(
                            drive = (58f to 58f) to 130L,
                            head = 0.48f to 95L
                        )
                        drivePulse(-35f, 35f, 70)
                        true
                    }

                    "excited_shuffle" -> {
                        parallelPulse(
                            drive = (-60f to 60f) to 85L,
                            head = 0.82f to 80L,
                            lift = 0.68f to 75L
                        )
                        parallelPulse(
                            drive = (62f to -62f) to 110L,
                            head = -0.65f to 85L,
                            lift = -0.50f to 70L
                        )
                        drivePulse(-55f, 55f, 75)
                        true
                    }

                    "fall_notice" -> {
                        connection.stopAllMotors()
                        delay(90)
                        true
                    }

                    "cliff_notice" -> {
                        connection.stopAllMotors()
                        headPulse(-0.55f, 80)
                        true
                    }

                    else -> false
                }

                if (!known) {
                    faceJob.cancel()
                    return@coroutineScope ActionResult(
                        ActionStatus.NOT_AVAILABLE,
                        "Macro inconnue: $name"
                    )
                }

                faceJob.join()
                completedNormally = true
                ActionResult(ActionStatus.SUCCESS)
            } finally {
                faceJob.cancel()

                // Sur une fin normale, chaque mouvement connaît désormais sa
                // cible ou arrête lui-même les roues : ne pas envoyer un STOP
                // global qui casserait les trajectoires tête/lift.
                if (!completedNormally) {
                    connection.stopAllMotors()
                }

                connection.setFaceExpression(CozmoFaceExpression.NEUTRAL)
            }
        }

    private suspend fun parallelPulse(
        drive: Pair<Pair<Float, Float>, Long>? = null,
        head: Pair<Float, Long>? = null,
        lift: Pair<Float, Long>? = null
    ) = coroutineScope {
        drive?.let { (speeds, duration) ->
            launch {
                drivePulse(
                    speeds.first,
                    speeds.second,
                    duration
                )
            }
        }

        head?.let { (speed, duration) ->
            launch { headPulse(speed, duration) }
        }

        lift?.let { (speed, duration) ->
            launch { liftPulse(speed, duration) }
        }
    }

    private fun faceAnimationFor(name: String): CozmoFaceAnimation? =
        when (name) {
            "greeting",
            "happy_small",
            "playful_invite",
            "small_bounce",
            "tiny_wiggle",
            "body_bob",
            "quick_bob",
            "excited_shuffle" -> CozmoFaceAnimation.HAPPY

            "cube_interest",
            "cube_peek",
            "curious_nod",
            "head_peek",
            "acknowledge",
            "on_side_notice",
            "wheelie_notice",
            "wander_short",
            "hiking_visit_edge_original",
            "hiking_scan_original",
            "micro_scan",
            "dash_peek" -> CozmoFaceAnimation.CURIOUS

            "picked_up",
            "on_back_notice" -> CozmoFaceAnimation.PICKUP

            "low_energy",
            "on_face_notice" -> CozmoFaceAnimation.BORED

            "look_around",
            "hiking_intro_original" -> CozmoFaceAnimation.EYES_IDLE
            "idle_blink" -> CozmoFaceAnimation.BLINK

            "fall_notice",
            "cliff_notice",
            "cliff_react_original" -> CozmoFaceAnimation.CLIFF

            "put_down" -> CozmoFaceAnimation.BLINK
            else -> null
        }

    /**
     * Les anciens macros de compatibilité expriment encore une intention sous
     * forme "vitesse + durée". On la convertit en cible AnimHead ABSOLUE et on
     * borne à la plage courante des animations Anki. Surtout, on ne cumule
     * plus une cible artificielle d'un geste à l'autre : c'est ce qui faisait
     * dériver la tête jusqu'en butée.
     */
    private suspend fun headPulse(speed: Float, durationMs: Long) {
        val sensedDeg =
            CozmoConnection.radToDeg(
                connection.state.value.headAngleRad
                    ?: CozmoConnection.HEAD_NEUTRAL_RAD
            )
        val deltaDeg =
            speed.coerceIn(-1.5f, 1.5f) *
                (durationMs.coerceIn(40L, 500L) / 1000f) *
                55f
        val targetDeg = (sensedDeg + deltaDeg)
            .coerceIn(PERSONALITY_HEAD_MIN_DEG, PERSONALITY_HEAD_MAX_DEG)
            .roundToInt()

        connection.animHead(
            angleDeg = targetDeg,
            durationMs = durationMs.coerceIn(33L, 255L).toInt()
        )
        delay(durationMs.coerceAtLeast(70L))
    }

    private suspend fun liftPulse(speed: Float, durationMs: Long) {
        val targetMm =
            if (speed >= 0f) {
                (32f + speed.coerceIn(0f, 1.5f) / 1.5f * 28f).roundToInt()
            } else {
                0
            }

        connection.animLift(
            heightMm = targetMm,
            durationMs = durationMs.coerceIn(33L, 255L).toInt()
        )
        delay(durationMs.coerceAtLeast(70L))
    }

    private suspend fun drivePulse(
        left: Float,
        right: Float,
        durationMs: Long
    ) {
        connection.drive(
            leftMmps = left,
            rightMmps = right,
            accelMmps2 = 260f
        )
        try {
            delay(durationMs.coerceAtLeast(35L))
        } finally {
            connection.drive(
                leftMmps = 0f,
                rightMmps = 0f,
                accelMmps2 = 320f
            )
        }
        delay(25)
    }

    /**
     * Variante 01 de HikingIntro, décodée directement de
     * anim_hiking_getin_01.bin de l'OBB 3.6.6.
     */
    private suspend fun playOriginalHikingIntro() {
        playOriginalClip(
            head = listOf(
                HeadFrame(0, 33, 0),
                HeadFrame(198, 132, 0),
                HeadFrame(330, 99, -7),
                HeadFrame(429, 33, -8),
                HeadFrame(462, 33, -8),
                HeadFrame(495, 33, -8),
                HeadFrame(528, 99, 5),
                HeadFrame(627, 33, 6),
                HeadFrame(660, 165, 1),
                HeadFrame(825, 66, 0),
                HeadFrame(891, 66, 0),
                HeadFrame(957, 99, 0)
            ),
            lift = listOf(
                LiftFrame(0, 33, 0),
                LiftFrame(330, 99, 38),
                LiftFrame(429, 132, 0)
            ),
            body = listOf(
                BodyFrame(363, 66, 44f),
                BodyFrame(990, 132, -3f)
            )
        )
    }

    /**
     * Fallback de navigation Hiking quand on ne dispose pas encore du memory
     * map/path planner propriétaire. La vitesse d'approche (50 mm/s) vient de
     * Hiking_VisitInterestingEdge et l'animation de tête vient du vrai
     * HikingDrivingStart. Le déplacement est CONTINU, plus une rafale de bonds.
     */
    private suspend fun playOriginalHikingDrive() = coroutineScope {
        val headJob = launch {
            playOriginalClip(
                head = listOf(
                    HeadFrame(0, 231, -12),
                    HeadFrame(924, 165, -7),
                    HeadFrame(3300, 231, -12),
                    HeadFrame(3564, 99, -7)
                )
            )
        }

        connection.drive(
            leftMmps = HIKING_APPROACH_SPEED_MMP_S,
            rightMmps = HIKING_APPROACH_SPEED_MMP_S,
            accelMmps2 = 100f
        )

        try {
            var elapsed = 0L
            while (elapsed < HIKING_DRIVE_MS) {
                if (connection.state.value.cliffDetected) break
                delay(25)
                elapsed += 25
            }
        } finally {
            connection.drive(0f, 0f, 180f)
            headJob.cancel()
        }

        connection.animHead(-8, 99)
        delay(110)
    }

    /**
     * Paramètres issus de Hiking_LookInPlaceForUnknown :
     * corps 180°/s, tête 90°/s, tête basse -15..-10° puis -5..+5°.
     */
    private suspend fun playOriginalHikingScan() {
        connection.animHead(-12, 165)
        turnInPlaceForDegrees(25f, clockwise = true)
        connection.animHead(0, 165)
        delay(260)
        turnInPlaceForDegrees(35f, clockwise = false)
        connection.animHead(-15, 165)
        delay(220)
    }

    /**
     * Réaction Cliff reconstruite depuis la 3.6.6 :
     * - anim_reacttocliff_huh_01 pour la première surprise ;
     * - BehaviorReactToCliff::TransitionToBackingUp() du binaire natif :
     *   DriveStraightAction(-60 mm, 100 mm/s, true).
     */
    private suspend fun playOriginalCliffReaction() {
        connection.stopAllMotors()

        playOriginalClip(
            head = listOf(
                HeadFrame(0, 66, -19),
                HeadFrame(66, 66, -1),
                HeadFrame(132, 66, 5),
                HeadFrame(198, 66, 0),
                HeadFrame(264, 33, -18),
                HeadFrame(297, 165, -19)
            ),
            lift = listOf(
                LiftFrame(0, 66, 48),
                LiftFrame(66, 99, 0)
            ),
            body = listOf(
                BodyFrame(0, 165, -117f),
                BodyFrame(165, 165, 7f)
            )
        )

        // Étape native TransitionToBackingUp : -60 mm à 100 mm/s.
        connection.drive(-100f, -100f, 260f)
        try {
            delay(600)
        } finally {
            connection.drive(0f, 0f, 320f)
        }

        connection.animLift(0, 99)
        connection.animHead(-8, 132)
        delay(150)
    }

    private suspend fun turnInPlaceForDegrees(
        degrees: Float,
        clockwise: Boolean
    ) {
        // TRACK_WIDTH officiel PyCozmo = 45 mm.
        // 180°/s => v = omega * demi-voie = PI * 22.5 ~= 70.7 mm/s.
        val speed = if (clockwise) HIKING_TURN_WHEEL_MMP_S else -HIKING_TURN_WHEEL_MMP_S
        val duration =
            ((degrees.coerceAtLeast(1f) / HIKING_BODY_TURN_DEG_PER_SEC) * 1000f)
                .roundToInt()
                .toLong()

        drivePulse(speed, -speed, duration)
    }

    private suspend fun playOriginalClip(
        head: List<HeadFrame> = emptyList(),
        lift: List<LiftFrame> = emptyList(),
        body: List<BodyFrame> = emptyList()
    ) = coroutineScope {
        head.forEach { frame ->
            launch {
                delay(frame.atMs.toLong())
                connection.animHead(frame.angleDeg, frame.durationMs)
            }
        }
        lift.forEach { frame ->
            launch {
                delay(frame.atMs.toLong())
                connection.animLift(frame.heightMm, frame.durationMs)
            }
        }
        body.forEach { frame ->
            launch {
                delay(frame.atMs.toLong())
                connection.drive(frame.speedMmps, frame.speedMmps, 300f)
                try {
                    delay(frame.durationMs.toLong())
                } finally {
                    connection.drive(0f, 0f, 300f)
                }
            }
        }
    }

    private data class HeadFrame(
        val atMs: Int,
        val durationMs: Int,
        val angleDeg: Int
    )

    private data class LiftFrame(
        val atMs: Int,
        val durationMs: Int,
        val heightMm: Int
    )

    private data class BodyFrame(
        val atMs: Int,
        val durationMs: Int,
        val speedMmps: Float
    )

    companion object {
        private const val PERSONALITY_HEAD_MIN_DEG = -20f
        private const val PERSONALITY_HEAD_MAX_DEG = 30f
        private const val HIKING_APPROACH_SPEED_MMP_S = 50f
        private const val HIKING_DRIVE_MS = 3_650L
        private const val HIKING_BODY_TURN_DEG_PER_SEC = 180f
        private const val HIKING_TURN_WHEEL_MMP_S = 70.7f
    }

    private fun PersonalityLight.toBackpack(): BackpackColor =
        when (this) {
            PersonalityLight.OFF -> BackpackColor.OFF
            PersonalityLight.RED -> BackpackColor.RED
            PersonalityLight.GREEN -> BackpackColor.GREEN
            PersonalityLight.BLUE -> BackpackColor.BLUE
            PersonalityLight.WHITE -> BackpackColor.WHITE
        }
}
