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
                accelY = 120f
            )
        )
    }

    @Test
    fun pitchDetectsBackAndFace() {
        assertEquals(
            ChassisOrientation.ON_BACK,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = 1.25f,
                accelY = 0f
            )
        )

        assertEquals(
            ChassisOrientation.ON_FACE,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = -1.25f,
                accelY = 0f
            )
        )
    }

    @Test
    fun lateralGravityDetectsSides() {
        assertEquals(
            ChassisOrientation.ON_LEFT_SIDE,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = 0f,
                accelY = -9_000f
            )
        )

        assertEquals(
            ChassisOrientation.ON_RIGHT_SIDE,
            CozmoConnection.classifyChassisOrientation(
                posePitchRad = 0f,
                accelY = 9_000f
            )
        )
    }
}
