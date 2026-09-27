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


    @Test
    fun multiCommandFrameRoundTripsAtomically() {
        val encoded = CozmoProtocol.commandFrame(
            firstSeq = 10,
            ack = 4,
            commands = listOf(
                0x10 to byteArrayOf(1, 2, 3, 4, 0),
                0x04 to ByteArray(40)
            )
        )

        val decoded = CozmoProtocol.decodeFrame(encoded)
        assertNotNull(decoded)
        assertEquals(10, decoded!!.firstSeq)
        assertEquals(11, decoded.seq)
        assertEquals(4, decoded.ack)
        assertEquals(2, decoded.packets.size)
        assertEquals(0x10, decoded.packets[0].id)
        assertEquals(0x04, decoded.packets[1].id)
    }

    @Test
    fun cozmoAudioCodecMatchesKnownPyCozmoValues() {
        assertEquals(0x01, CozmoAudioCodec.encodeSample(0).toInt() and 0xff)
        assertEquals(0x0e, CozmoAudioCodec.encodeSample(100).toInt() and 0xff)
        assertEquals(0x32, CozmoAudioCodec.encodeSample(1000).toInt() and 0xff)
        assertEquals(0x64, CozmoAudioCodec.encodeSample(10000).toInt() and 0xff)
        assertEquals(0xb2, CozmoAudioCodec.encodeSample((-1000).toShort()).toInt() and 0xff)
    }

    @Test
    fun cozmoAudioPacketHas744BytesAndZeroPadding() {
        val source = shortArrayOf(0, 1000, -1000)
        val packet = CozmoAudioCodec.encodePacket(source, 0)

        assertEquals(744, packet.size)
        assertEquals(0x01, packet[0].toInt() and 0xff)
        assertEquals(0x32, packet[1].toInt() and 0xff)
        assertEquals(0xb2, packet[2].toInt() and 0xff)
        assertEquals(0x00, packet[743].toInt() and 0xff)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
