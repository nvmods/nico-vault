package fr.nvmods.cozmo.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object CozmoProtocol {
    val FRAME_ID = byteArrayOf(
        0x43, 0x4f, 0x5a, 0x03, 0x52, 0x45, 0x01
    )
    const val ROBOT_HOST = "172.31.1.1"
    const val ROBOT_PORT = 5551
    const val OOB_SEQ = 0xffff
    const val MAX_SEQ = 0xfffe

    enum class FrameType(val id: Int) {
        RESET(1),
        RESET_ACK(2),
        FIN(3),
        ENGINE_ACT(4),
        ENGINE(7),
        ROBOT(9),
        PING(0x0b);

        companion object {
            fun fromId(id: Int): FrameType? = entries.firstOrNull { it.id == id }
        }
    }

    enum class PacketType(val id: Int) {
        CONNECT(2),
        DISCONNECT(3),
        COMMAND(4),
        EVENT(5),
        KEYFRAME(0x0a),
        PING(0x0b);

        companion object {
            fun fromId(id: Int): PacketType? = entries.firstOrNull { it.id == id }
        }
    }

    data class Packet(
        val type: PacketType,
        val id: Int? = null,
        val payload: ByteArray = byteArrayOf()
    )

    data class Frame(
        val type: FrameType,
        val firstSeq: Int,
        val seq: Int,
        val ack: Int,
        val packets: List<Packet>
    )

    fun resetFrame(): ByteArray =
        encodeFrame(FrameType.RESET, firstSeq = 0, seq = 0, ack = OOB_SEQ, packets = emptyList())

    fun pingFrame(ack: Int, counter: Int): ByteArray {
        val payload = ByteBuffer.allocate(17)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(System.nanoTime() / 1_000_000_000.0)
            .putInt(counter)
            .putInt(0)
            .put(0)
            .array()
        return encodeFrame(
            FrameType.PING,
            firstSeq = OOB_SEQ,
            seq = OOB_SEQ,
            ack = ack,
            packets = listOf(Packet(PacketType.PING, payload = payload))
        )
    }

    fun commandFrame(
        seq: Int,
        ack: Int,
        commandId: Int,
        payload: ByteArray = byteArrayOf()
    ): ByteArray =
        commandFrame(
            firstSeq = seq,
            ack = ack,
            commands = listOf(commandId to payload)
        )

    /**
     * Encode plusieurs commandes dans UNE trame ENGINE.
     *
     * C'est important pour les opérations qui ont une sémantique atomique côté
     * robot, notamment CubeId + CubeLights : une retransmission doit conserver
     * la sélection du cube et sa commande d'éclairage dans la même trame.
     */
    fun commandFrame(
        firstSeq: Int,
        ack: Int,
        commands: List<Pair<Int, ByteArray>>
    ): ByteArray {
        require(commands.isNotEmpty()) { "Au moins une commande est requise" }

        val lastSeq = (firstSeq + commands.size - 1) % MAX_SEQ

        return encodeFrame(
            type = FrameType.ENGINE,
            firstSeq = firstSeq,
            seq = lastSeq,
            ack = ack,
            packets = commands.map { (id, payload) ->
                Packet(
                    type = PacketType.COMMAND,
                    id = id,
                    payload = payload
                )
            }
        )
    }

    fun encodeFrame(
        type: FrameType,
        firstSeq: Int,
        seq: Int,
        ack: Int,
        packets: List<Packet>
    ): ByteArray {
        val payloadSize = when (type) {
            FrameType.ENGINE, FrameType.ROBOT -> packets.sumOf { p ->
                1 + 2 + (if (p.type == PacketType.COMMAND || p.type == PacketType.EVENT) 1 else 0) + p.payload.size
            }
            FrameType.PING -> packets.firstOrNull()?.payload?.size ?: 0
            else -> 0
        }

        val out = ByteBuffer.allocate(14 + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put(FRAME_ID)
        out.put(type.id.toByte())
        out.putShort(((firstSeq + 1) and 0xffff).toShort())
        out.putShort(((seq + 1) and 0xffff).toShort())
        out.putShort(((ack + 1) and 0xffff).toShort())

        when (type) {
            FrameType.ENGINE, FrameType.ROBOT -> packets.forEach { packet ->
                out.put(packet.type.id.toByte())
                if (packet.type == PacketType.COMMAND || packet.type == PacketType.EVENT) {
                    out.putShort((packet.payload.size + 1).toShort())
                    out.put((packet.id ?: error("Packet id requis")).toByte())
                } else {
                    out.putShort(packet.payload.size.toShort())
                }
                out.put(packet.payload)
            }

            FrameType.PING -> packets.firstOrNull()?.let { out.put(it.payload) }
            else -> Unit
        }

        return out.array()
    }

    fun decodeFrame(data: ByteArray, length: Int = data.size): Frame? {
        if (length < 14) return null
        for (i in FRAME_ID.indices) {
            if (data[i] != FRAME_ID[i]) return null
        }

        val input = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        input.position(7)
        val frameType = FrameType.fromId(input.get().toInt() and 0xff) ?: return null
        val firstSeq = ((input.short.toInt() and 0xffff) - 1) and 0xffff
        val seq = ((input.short.toInt() and 0xffff) - 1) and 0xffff
        val ack = ((input.short.toInt() and 0xffff) - 1) and 0xffff
        val packets = mutableListOf<Packet>()

        if (frameType == FrameType.ENGINE || frameType == FrameType.ROBOT) {
            while (input.remaining() >= 3) {
                val packetType = PacketType.fromId(input.get().toInt() and 0xff) ?: break
                val packetLength = input.short.toInt() and 0xffff
                if (packetLength > input.remaining()) break

                if (packetType == PacketType.COMMAND || packetType == PacketType.EVENT) {
                    if (packetLength < 1) break
                    val id = input.get().toInt() and 0xff
                    val payload = ByteArray(packetLength - 1)
                    input.get(payload)
                    packets += Packet(packetType, id, payload)
                } else {
                    val payload = ByteArray(packetLength)
                    input.get(payload)
                    packets += Packet(packetType, payload = payload)
                }
            }
        } else if (frameType == FrameType.PING && input.hasRemaining()) {
            val payload = ByteArray(input.remaining())
            input.get(payload)
            packets += Packet(PacketType.PING, payload = payload)
        }

        return Frame(frameType, firstSeq, seq, ack, packets)
    }

    fun leFloats(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putFloat(it) } }
            .array()

    fun setOriginPayload(): ByteArray =
        ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)
            .putInt(0)
            .putInt(1)
            .putFloat(0f)
            .putFloat(0f)
            .putInt(Int.MIN_VALUE)
            .array()

    fun syncTimePayload(): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)
            .putInt(0)
            .array()
}
