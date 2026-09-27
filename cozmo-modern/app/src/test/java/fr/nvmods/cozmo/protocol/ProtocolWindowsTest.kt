package fr.nvmods.cozmo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolWindowsTest {

    @Test
    fun sendWindowAckIsCumulativeAndInclusive() {
        val window = SendSequenceWindow<String>(
            size = 62,
            maxSeq = CozmoProtocol.MAX_SEQ
        )

        assertEquals(0, window.put("a"))
        assertEquals(1, window.put("b"))
        assertEquals(2, window.put("c"))

        window.acknowledge(1)

        assertEquals(
            listOf(2 to "c"),
            window.entries()
        )
        assertEquals(2, window.expectedSeq)
    }

    @Test
    fun receiveWindowDeliversOnlyContiguousSequence() {
        val window = ReceiveSequenceWindow<String>(
            size = 62,
            maxSeq = CozmoProtocol.MAX_SEQ
        )

        window.put(1, "b")
        assertNull(window.get())

        window.put(0, "a")
        assertEquals("a", window.get())
        assertEquals("b", window.get())
        assertNull(window.get())
    }

    @Test
    fun sendWindowCapacityMatchesReference() {
        val window = SendSequenceWindow<Int>(
            size = 62,
            maxSeq = CozmoProtocol.MAX_SEQ
        )

        repeat(62) { index ->
            window.put(index)
        }

        assertTrue(window.isFull())

        window.acknowledge(0)
        assertFalse(window.isFull())
    }

    @Test
    fun eventPacketsAreOutOfBandByProtocolValue() {
        assertTrue(
            CozmoProtocol.PacketType.EVENT.id >=
                CozmoProtocol.PacketType.EVENT.id
        )
        assertTrue(
            CozmoProtocol.PacketType.KEYFRAME.id >=
                CozmoProtocol.PacketType.EVENT.id
        )
        assertFalse(
            CozmoProtocol.PacketType.COMMAND.id >=
                CozmoProtocol.PacketType.EVENT.id
        )
    }
}
