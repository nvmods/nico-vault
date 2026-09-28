package fr.nvmods.cozmo.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CozmoOrientationTest {

    @Test
    fun flatRobotStaysOnThreads() {
        assertEquals(
            ChassisOrientation.ON_THREADS,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = 0.04f,
                accelX = 0f,
                accelY = 0.12f,
                accelZ = 9.7f
            )
        )
    }

    @Test
    fun flatRobotAlsoWorksWithMilliUnits() {
        assertEquals(
            ChassisOrientation.ON_THREADS,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = 0.02f,
                accelX = 50f,
                accelY = 100f,
                accelZ = 9_700f
            )
        )
    }

    @Test
    fun pitchDetectsBackAndFace() {
        assertEquals(
            ChassisOrientation.ON_BACK,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = 1.25f,
                accelX = 0f,
                accelY = 0f,
                accelZ = 9.8f
            )
        )

        assertEquals(
            ChassisOrientation.ON_FACE,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = -1.25f,
                accelX = 0f,
                accelY = 0f,
                accelZ = 9.8f
            )
        )
    }

    @Test
    fun lateralGravityDetectsSides() {
        assertEquals(
            ChassisOrientation.ON_LEFT_SIDE,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = 0f,
                accelX = 0f,
                accelY = -9.8f,
                accelZ = 0.2f
            )
        )

        assertEquals(
            ChassisOrientation.ON_RIGHT_SIDE,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = 0f,
                accelX = 0f,
                accelY = 9.8f,
                accelZ = 0.2f
            )
        )
    }
}
