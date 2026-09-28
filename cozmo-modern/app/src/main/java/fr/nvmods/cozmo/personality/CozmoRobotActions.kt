package fr.nvmods.cozmo.personality

import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.ConnectionState
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoFaceAnimation
import fr.nvmods.cozmo.protocol.CozmoFaceExpression
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adaptateur entre les intentions de personnalité et le vrai robot.
 *
 * Les mouvements autonomes sont volontairement courts et bornés.
 * L'annulation de la coroutine interrompt la séquence sans envoyer
 * d'ordre tardif susceptible d'écraser une commande manuelle.
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
                connection.drive(action.leftMmps, action.rightMmps)
                delay(action.durationMs.coerceIn(20L, 1_500L))
                connection.drive(0f, 0f)
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.MoveHead -> {
                connection.moveHead(action.speedRadPerSec.coerceIn(-1.5f, 1.5f))
                delay(action.durationMs.coerceIn(20L, 800L))
                connection.moveHead(0f)
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.MoveLift -> {
                connection.moveLift(action.speedRadPerSec.coerceIn(-1.5f, 1.5f))
                delay(action.durationMs.coerceIn(20L, 800L))
                connection.moveLift(0f)
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
                val samples = PersonalityAudioCatalog.samples(action.cue)
                connection.playPcm22050(samples)
                ActionResult(ActionStatus.SUCCESS)
            }

            is RobotAction.PlayAnimation -> {
                playMotionMacro(action.name)
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
            val faceJob = launch {
                when (name) {
                    "double_blink" -> {
                        connection.playFaceAnimation(CozmoFaceAnimation.BLINK)
                        delay(80)
                        connection.playFaceAnimation(CozmoFaceAnimation.BLINK)
                    }

                    else -> faceAnimationFor(name)?.let {
                        connection.playFaceAnimation(it)
                    }
                }
            }

            val known = when (name) {
                "greeting" -> {
                    headPulse(0.75f, 130)
                    headPulse(-0.45f, 90)
                    true
                }

                "cube_interest" -> {
                    headPulse(-0.55f, 130)
                    liftPulse(0.55f, 90)
                    liftPulse(-0.45f, 80)
                    true
                }

                "picked_up" -> {
                    headPulse(0.65f, 100)
                    liftPulse(0.60f, 100)
                    true
                }

                "put_down" -> {
                    liftPulse(-0.45f, 80)
                    headPulse(0.35f, 80)
                    true
                }

                "happy_small" -> {
                    headPulse(0.65f, 90)
                    headPulse(-0.60f, 90)
                    headPulse(0.45f, 75)
                    true
                }

                "playful_invite" -> {
                    drivePulse(-45f, 45f, 150)
                    drivePulse(45f, -45f, 280)
                    drivePulse(-45f, 45f, 140)
                    true
                }

                "acknowledge" -> {
                    headPulse(-0.42f, 90)
                    headPulse(0.42f, 90)
                    true
                }

                "low_energy" -> {
                    headPulse(-0.40f, 180)
                    liftPulse(-0.35f, 160)
                    true
                }

                "look_around" -> {
                    drivePulse(-32f, 32f, 150)
                    drivePulse(32f, -32f, 300)
                    drivePulse(-32f, 32f, 150)
                    true
                }

                "idle_blink" -> {
                    delay(130)
                    true
                }

                "double_blink" -> {
                    delay(180)
                    true
                }

                "curious_nod" -> {
                    headPulse(-0.38f, 95)
                    headPulse(0.42f, 110)
                    true
                }

                "head_peek" -> {
                    headPulse(0.45f, 120)
                    delay(90)
                    headPulse(-0.28f, 85)
                    true
                }

                "small_bounce" -> {
                    liftPulse(0.45f, 75)
                    headPulse(0.38f, 75)
                    liftPulse(-0.35f, 65)
                    true
                }

                "tiny_wiggle" -> {
                    drivePulse(-24f, 24f, 105)
                    drivePulse(24f, -24f, 210)
                    drivePulse(-24f, 24f, 105)
                    true
                }

                "cube_peek" -> {
                    headPulse(-0.48f, 115)
                    liftPulse(0.38f, 80)
                    delay(100)
                    liftPulse(-0.30f, 70)
                    true
                }

                "on_back_notice" -> {
                    headPulse(0.32f, 90)
                    true
                }

                "on_face_notice" -> {
                    liftPulse(-0.28f, 85)
                    true
                }

                "on_side_notice" -> {
                    headPulse(0.30f, 75)
                    true
                }

                "fall_notice" -> {
                    connection.stopAllMotors()
                    delay(120)
                    true
                }

                "cliff_notice" -> {
                    connection.stopAllMotors()
                    headPulse(-0.42f, 95)
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
            connection.setFaceExpression(CozmoFaceExpression.NEUTRAL)
            ActionResult(ActionStatus.SUCCESS)
        }

    private fun faceAnimationFor(name: String): CozmoFaceAnimation? =
        when (name) {
            "greeting",
            "happy_small",
            "playful_invite",
            "small_bounce",
            "tiny_wiggle" -> CozmoFaceAnimation.HAPPY

            "cube_interest",
            "cube_peek",
            "curious_nod",
            "head_peek",
            "acknowledge",
            "on_side_notice" -> CozmoFaceAnimation.CURIOUS

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
        connection.moveHead(speed)
        delay(durationMs)
        connection.moveHead(0f)
        delay(35)
    }

    private suspend fun liftPulse(speed: Float, durationMs: Long) {
        connection.moveLift(speed)
        delay(durationMs)
        connection.moveLift(0f)
        delay(35)
    }

    private suspend fun drivePulse(
        left: Float,
        right: Float,
        durationMs: Long
    ) {
        connection.drive(left, right)
        delay(durationMs)
        connection.drive(0f, 0f)
        delay(45)
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
