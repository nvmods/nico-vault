package fr.nvmods.cozmo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CozmoProtocolTest {
    @Test
    fun resetFrameMatchesKnownProtocolHeader() {
        val expected = hex("434f5a0352450101010001000000")
        assertArrayEquals(expected, CozmoProtocol.resetFrame())
    }

    @Test
    fun commandFrameRoundTrips() {
        val encoded = CozmoProtocol.commandFrame(
            seq = 7,
            ack = 3,
            commandId = 0x3b
        )
        val decoded = CozmoProtocol.decodeFrame(encoded)
        assertNotNull(decoded)
        assertEquals(CozmoProtocol.FrameType.ENGINE, decoded!!.type)
        assertEquals(7, decoded.seq)
        assertEquals(3, decoded.ack)
        assertEquals(0x3b, decoded.packets.single().id)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
