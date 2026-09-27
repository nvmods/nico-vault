package fr.nvmods.cozmo.personality

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PersonalityEngineTest {
    @Test
    fun faceDetectionUpdatesStateAndProducesGreeting() = runBlocking {
        val robot = MockRobotActions()
        val engine = PersonalityEngine(
            robot = robot,
            clockMs = { 10_000L },
            random = Random(1)
        )

        engine.start()
        engine.handle(PersonalityEvent.FaceDetected("Nico"))

        assertTrue(engine.state.value.knownFaceVisible)
        assertTrue(engine.state.value.happiness > 0.65f)
        assertEquals("Saluer la personne", engine.state.value.lastDecision)
        assertTrue(
            robot.history().any {
                it == RobotAction.PlayAnimation("greeting")
            }
        )
    }

    @Test
    fun disabledEngineIgnoresNormalEvents() = runBlocking {
        val robot = MockRobotActions()
        val engine = PersonalityEngine(robot)

        engine.handle(PersonalityEvent.Touched)

        assertEquals(0L, engine.state.value.interactions)
        assertTrue(robot.history().isEmpty())
    }

    @Test
    fun pickedUpBlocksIdleAutonomy() = runBlocking {
        var now = 50_000L
        val robot = MockRobotActions()
        val engine = PersonalityEngine(
            robot = robot,
            clockMs = { now },
            random = Random(0)
        )

        engine.start()
        engine.handle(PersonalityEvent.PickedUp)
        robot.clear()

        repeat(30) {
            now += 20_000L
            engine.handle(PersonalityEvent.IdleTick)
        }

        assertTrue(engine.state.value.pickedUp)
        assertFalse(
            robot.history().any {
                it is RobotAction.Drive || it is RobotAction.MoveHead
            }
        )
    }

    @Test
    fun batteryLowReducesEnergyAndStopsRobot() = runBlocking {
        val robot = MockRobotActions()
        val engine = PersonalityEngine(robot)

        engine.start()
        val before = engine.state.value.energy
        engine.handle(PersonalityEvent.BatteryLow)

        assertTrue(engine.state.value.energy < before)
        assertTrue(robot.history().contains(RobotAction.Stop))
    }
}
