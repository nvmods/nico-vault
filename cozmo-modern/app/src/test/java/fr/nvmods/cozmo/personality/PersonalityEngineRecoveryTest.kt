package fr.nvmods.cozmo.personality

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityEngineRecoveryTest {

    @Test
    fun actionExceptionIsRecoveredWithoutStoppingEngine() = runBlocking {
        val robot = ThrowingRobotActions()
        val engine = PersonalityEngine(robot)

        engine.start()
        engine.handle(PersonalityEvent.FaceDetected("Test"))

        val state = engine.state.value

        assertTrue(state.enabled)
        assertEquals(1L, state.recoveredFaults)
        assertNotNull(state.lastFault)
        assertTrue(robot.stopCalls >= 2)
    }

    private class ThrowingRobotActions : RobotActions {
        var stopCalls = 0

        override suspend fun execute(action: RobotAction): ActionResult {
            if (action == RobotAction.Stop) {
                stopCalls++
                return ActionResult(ActionStatus.SUCCESS)
            }

            if (action is RobotAction.Express) {
                error("simulated expression failure")
            }

            return ActionResult(ActionStatus.SUCCESS)
        }
    }
}
