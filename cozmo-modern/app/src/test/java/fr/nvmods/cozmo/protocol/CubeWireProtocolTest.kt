package fr.nvmods.cozmo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CubeWireProtocolTest {
    @Test
    fun setPropSlotEncodesFactoryIdThenSlot() {
        val payload =
            CubeWireProtocol.setPropSlot(
                factoryId = 0x12345678L,
                slot = 2
            )

        assertEquals(5, payload.size)
        assertArrayEquals(
            byteArrayOf(
                0x78,
                0x56,
                0x34,
                0x12,
                0x02
            ),
            payload
        )
    }

    @Test
    fun clearPropSlotUsesZeroFactoryIdAndRequestedSlot() {
        val payload = CubeWireProtocol.clearPropSlot(4)
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0, b.int)
        assertEquals(4, b.get().toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setPropSlotRejectsInvalidSlot() {
        CubeWireProtocol.setPropSlot(
            factoryId = 1L,
            slot = 5
        )
    }
}
