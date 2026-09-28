package fr.nvmods.cozmo.personality

import fr.nvmods.cozmo.protocol.BackpackColor
import fr.nvmods.cozmo.protocol.ConnectionState
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoFaceExpression
import kotlinx.coroutines.delay

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
                val samples = PersonalityToneSynth.synthesize(action.cue)
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

    private suspend fun playMotionMacro(name: String): ActionResult {
        val expression = when (name) {
            "greeting" -> CozmoFaceExpression.HAPPY
            "cube_interest" -> CozmoFaceExpression.CURIOUS
            "picked_up" -> CozmoFaceExpression.SURPRISED
            "put_down" -> CozmoFaceExpression.NEUTRAL
            "happy_small" -> CozmoFaceExpression.HAPPY
            "playful_invite" -> CozmoFaceExpression.HAPPY
            "acknowledge" -> CozmoFaceExpression.CURIOUS
            "low_energy" -> CozmoFaceExpression.SLEEPY
            "look_around" -> CozmoFaceExpression.CURIOUS
            "idle_blink" -> CozmoFaceExpression.BLINK
            "double_blink" -> CozmoFaceExpression.BLINK
            "curious_nod" -> CozmoFaceExpression.CURIOUS
            "head_peek" -> CozmoFaceExpression.CURIOUS
            "small_bounce" -> CozmoFaceExpression.HAPPY
            "tiny_wiggle" -> CozmoFaceExpression.HAPPY
            "cube_peek" -> CozmoFaceExpression.CURIOUS
            "on_back_notice" -> CozmoFaceExpression.SURPRISED
            "on_face_notice" -> CozmoFaceExpression.SAD
            "on_side_notice" -> CozmoFaceExpression.CURIOUS
            "fall_notice" -> CozmoFaceExpression.SURPRISED
            "cliff_notice" -> CozmoFaceExpression.SURPRISED
            else -> null
        }

        if (expression != null) {
            connection.setFaceExpression(expression)
        }

        when (name) {
            "greeting" -> {
                headPulse(0.75f, 130)
                headPulse(-0.45f, 90)
            }

            "cube_interest" -> {
                headPulse(-0.55f, 130)
                liftPulse(0.55f, 90)
                liftPulse(-0.45f, 80)
            }

            "picked_up" -> {
                headPulse(0.65f, 100)
                liftPulse(0.60f, 100)
            }

            "put_down" -> {
                liftPulse(-0.45f, 80)
                headPulse(0.35f, 80)
            }

            "happy_small" -> {
                headPulse(0.65f, 90)
                headPulse(-0.60f, 90)
                headPulse(0.45f, 75)
            }

            "playful_invite" -> {
                drivePulse(-45f, 45f, 150)
                drivePulse(45f, -45f, 280)
                drivePulse(-45f, 45f, 140)
            }

            "acknowledge" -> {
                headPulse(-0.42f, 90)
                headPulse(0.42f, 90)
            }

            "low_energy" -> {
                headPulse(-0.40f, 180)
                liftPulse(-0.35f, 160)
            }

            "look_around" -> {
                drivePulse(-32f, 32f, 150)
                drivePulse(32f, -32f, 300)
                drivePulse(-32f, 32f, 150)
            }

            "idle_blink" -> {
                delay(130)
            }

            "double_blink" -> {
                delay(90)
                connection.setFaceExpression(CozmoFaceExpression.NEUTRAL)
                delay(90)
                connection.setFaceExpression(CozmoFaceExpression.BLINK)
                delay(90)
            }

            "curious_nod" -> {
                headPulse(-0.38f, 95)
                headPulse(0.42f, 110)
            }

            "head_peek" -> {
                headPulse(0.45f, 120)
                delay(90)
                headPulse(-0.28f, 85)
            }

            "small_bounce" -> {
                liftPulse(0.45f, 75)
                headPulse(0.38f, 75)
                liftPulse(-0.35f, 65)
            }

            "tiny_wiggle" -> {
                drivePulse(-24f, 24f, 105)
                drivePulse(24f, -24f, 210)
                drivePulse(-24f, 24f, 105)
            }

            "cube_peek" -> {
                headPulse(-0.48f, 115)
                liftPulse(0.38f, 80)
                delay(100)
                liftPulse(-0.30f, 70)
            }

            "on_back_notice" -> {
                headPulse(0.32f, 90)
            }

            "on_face_notice" -> {
                liftPulse(-0.28f, 85)
            }

            "on_side_notice" -> {
                headPulse(0.30f, 75)
            }

            "fall_notice" -> {
                connection.stopAllMotors()
                delay(120)
            }

            "cliff_notice" -> {
                connection.stopAllMotors()
                headPulse(-0.42f, 95)
            }

            else -> {
                return ActionResult(
                    ActionStatus.NOT_AVAILABLE,
                    "Macro inconnue: $name"
                )
            }
        }

        delay(220)
        connection.setFaceExpression(CozmoFaceExpression.NEUTRAL)

        return ActionResult(ActionStatus.SUCCESS)
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
