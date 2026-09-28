package fr.nvmods.cozmo.personality

import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.ConnectionState
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoFaceAnimation
import fr.nvmods.cozmo.protocol.CozmoFaceExpression
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

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
    private var headTargetRad: Float? = null
    private var liftTargetMm: Float? = null

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
            "micro_scan",
            "dash_peek" -> CozmoFaceAnimation.CURIOUS

            "picked_up",
            "on_back_notice" -> CozmoFaceAnimation.PICKUP

            "low_energy",
            "on_face_notice" -> CozmoFaceAnimation.BORED

            "look_around" -> CozmoFaceAnimation.EYES_IDLE
            "idle_blink" -> CozmoFaceAnimation.BLINK

            "fall_notice",
            "cliff_notice" -> CozmoFaceAnimation.CLIFF

            "put_down" -> CozmoFaceAnimation.BLINK
            else -> null
        }

    private suspend fun headPulse(speed: Float, durationMs: Long) {
        val sensed =
            connection.state.value.headAngleRad ?: CozmoConnection.HEAD_NEUTRAL_RAD
        val base = headTargetRad ?: sensed
        val delta =
            speed.coerceIn(-1.5f, 1.5f) *
                (durationMs.coerceIn(40L, 500L) / 1000f) *
                1.15f
        val target = (base + delta).coerceIn(
            CozmoConnection.MIN_HEAD_ANGLE_RAD,
            CozmoConnection.MAX_HEAD_ANGLE_RAD
        )

        headTargetRad = target
        connection.setHeadAngle(
            angleRad = target,
            maxSpeedRadPerSec = max(0.9f, abs(speed) * 1.8f),
            accelRadPerSec2 = 8f
        )
        delay(durationMs.coerceAtLeast(85L))
    }

    private suspend fun liftPulse(speed: Float, durationMs: Long) {
        val sensed =
            connection.state.value.liftHeightMm ?: CozmoConnection.MIN_LIFT_HEIGHT_MM
        val base = liftTargetMm ?: sensed
        val deltaMm =
            speed.coerceIn(-1.5f, 1.5f) *
                (durationMs.coerceIn(40L, 500L) / 1000f) *
                85f
        val target = (base + deltaMm).coerceIn(
            CozmoConnection.MIN_LIFT_HEIGHT_MM,
            CozmoConnection.MAX_LIFT_HEIGHT_MM
        )

        liftTargetMm = target
        connection.setLiftHeight(
            heightMm = target,
            maxSpeedRadPerSec = max(0.8f, abs(speed) * 1.5f),
            accelRadPerSec2 = 8f
        )
        delay(durationMs.coerceAtLeast(85L))
    }

    private suspend fun drivePulse(
        left: Float,
        right: Float,
        durationMs: Long
    ) {
        val sameDirection =
            left != 0f &&
                right != 0f &&
                (left > 0f) == (right > 0f)
        val effectiveDuration =
            if (sameDirection) {
                durationMs.coerceAtLeast(260L)
            } else {
                durationMs.coerceAtLeast(105L)
            }

        connection.drive(
            leftMmps = left,
            rightMmps = right,
            accelMmps2 = 260f
        )
        try {
            delay(effectiveDuration)
        } finally {
            connection.drive(
                leftMmps = 0f,
                rightMmps = 0f,
                accelMmps2 = 320f
            )
        }
        delay(30)
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
