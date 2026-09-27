package fr.nvmods.cozmo.protocol

/**
 * Fenêtres de séquences du protocole Cozmo.
 *
 * Port volontairement proche de PyCozmo (window.py) :
 * - 16 bits sur le fil ;
 * - MAX_SEQ = 0xfffe, donc séquences valides 0..0xfffd ;
 * - fenêtre de 62 paquets ;
 * - ACK cumulatif inclusif.
 *
 * Cette couche ne connaît ni les cubes ni l'UI.
 */
internal class ReceiveSequenceWindow<T>(
    private val size: Int = 62,
    private val maxSeq: Int = CozmoProtocol.MAX_SEQ
) {
    private val slots = arrayOfNulls<Any?>(size)

    var expectedSeq: Int = 0
        private set

    private var lastSeq: Int =
        (expectedSeq + size - 1) % maxSeq

    init {
        require(size > 0)
        require(maxSeq % size == 0)
    }

    fun reset() {
        expectedSeq = 0
        lastSeq = (expectedSeq + size - 1) % maxSeq
        slots.fill(null)
    }

    fun put(seq: Int, value: T) {
        if (!isValid(seq)) return
        if (isOutOfOrder(seq)) return

        val index = seq % size
        if (slots[index] != null) return

        slots[index] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun get(): T? {
        val index = expectedSeq % size
        val value = slots[index] as T? ?: return null

        slots[index] = null
        expectedSeq = (expectedSeq + 1) % maxSeq
        lastSeq = (expectedSeq + size - 1) % maxSeq
        return value
    }

    private fun isValid(seq: Int): Boolean =
        seq in 0 until maxSeq

    private fun isOutOfOrder(seq: Int): Boolean =
        if (expectedSeq > lastSeq) {
            expectedSeq > seq && seq > lastSeq
        } else {
            seq < expectedSeq || seq > lastSeq
        }
}

internal class SendSequenceWindow<T>(
    private val size: Int = 62,
    private val maxSeq: Int = CozmoProtocol.MAX_SEQ
) {
    private val slots = arrayOfNulls<Any?>(size)

    var expectedSeq: Int = 0
        private set

    var nextSeq: Int = 0
        private set

    init {
        require(size > 0)
        require(maxSeq % size == 0)
    }

    fun reset() {
        expectedSeq = 0
        nextSeq = 0
        slots.fill(null)
    }

    fun isFull(): Boolean =
        if (expectedSeq > nextSeq) {
            nextSeq + maxSeq - expectedSeq >= size
        } else {
            nextSeq - expectedSeq >= size
        }

    fun put(value: T): Int {
        check(!isFull()) { "Fenêtre d'émission Cozmo pleine" }

        val seq = nextSeq
        slots[seq % size] = value
        nextSeq = (nextSeq + 1) % maxSeq
        return seq
    }

    /**
     * ACK cumulatif inclusif, identique à PyCozmo SendWindow.acknowledge().
     */
    fun acknowledge(seq: Int) {
        if (!isValid(seq)) return
        if (isOutOfOrder(seq)) return

        val afterAck = (seq + 1) % maxSeq

        while (expectedSeq != afterAck) {
            slots[expectedSeq % size] = null
            expectedSeq = (expectedSeq + 1) % maxSeq
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun entries(): List<Pair<Int, T>> {
        val result = ArrayList<Pair<Int, T>>()
        var seq = expectedSeq

        while (seq != nextSeq) {
            val value = slots[seq % size] as T?
            if (value != null) {
                result += seq to value
            }
            seq = (seq + 1) % maxSeq
        }

        return result
    }

    private fun isValid(seq: Int): Boolean =
        seq in 0 until maxSeq

    private fun isOutOfOrder(seq: Int): Boolean =
        if (expectedSeq > nextSeq) {
            expectedSeq > seq && seq >= nextSeq
        } else {
            seq < expectedSeq || seq >= nextSeq
        }
}
