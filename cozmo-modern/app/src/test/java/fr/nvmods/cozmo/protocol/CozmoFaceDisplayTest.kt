package fr.nvmods.cozmo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CozmoFaceDisplayTest {

    @Test
    fun everyExpressionProducesLengthPrefixedImage() {
        CozmoFaceExpression.entries.forEach { expression ->
            val payload = CozmoFaceDisplay.payload(expression)

            assertTrue(
                "Payload trop court pour $expression",
                payload.size > 2
            )

            val declaredLength =
                ByteBuffer.wrap(payload, 0, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                    .toInt() and 0xffff

            assertEquals(
                "Longueur incohérente pour $expression",
                payload.size - 2,
                declaredLength
            )

            assertTrue(
                "Image RLE vide pour $expression",
                declaredLength > 0
            )
        }
    }

    @Test
    fun everyOriginalAnimationFrameHasValidLengthPrefix() {
        CozmoFaceAnimation.entries.forEach { animation ->
            val frames = CozmoFaceDisplay.frames(animation)

            assertTrue(
                "Aucune frame pour $animation",
                frames.isNotEmpty()
            )

            frames.forEachIndexed { index, frame ->
                assertValidPayload(
                    label = "$animation frame $index",
                    payload = frame.payload
                )
                assertTrue(
                    "Durée invalide pour $animation frame $index",
                    frame.durationMs > 0
                )
            }
        }
    }

    private fun assertValidPayload(
        label: String,
        payload: ByteArray
    ) {
        assertTrue(
            "Payload trop court pour $label",
            payload.size > 2
        )

        val declaredLength =
            ByteBuffer.wrap(payload, 0, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xffff

        assertEquals(
            "Longueur incohérente pour $label",
            payload.size - 2,
            declaredLength
        )

        assertTrue(
            "Image RLE vide pour $label",
            declaredLength > 0
        )
    }
}
