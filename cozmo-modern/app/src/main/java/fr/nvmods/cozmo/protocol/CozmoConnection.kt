package fr.nvmods.cozmo.protocol

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

data class CubeInfo(
    val factoryId: Long,
    val objectId: Long? = null,
    val objectType: Int = -1,
    val rssi: Int? = null,
    val connected: Boolean = false,
    val batteryLevel: Int? = null
)

data class CozmoState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val batteryVoltage: Float? = null,
    val headAngleRad: Float? = null,
    val liftHeightMm: Float? = null,
    val firmwareSeen: Boolean = false,
    val bodySeen: Boolean = false,
    val lastError: String? = null,
    val packetsReceived: Long = 0,
    val packetsSent: Long = 0,
    val cameraEnabled: Boolean = false,
    val cameraBitmap: Bitmap? = null,
    val cameraFrames: Long = 0,
    val cubeDiscovery: Boolean = false,
    val cubes: List<CubeInfo> = emptyList(),
    val audioStreaming: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    READY
}

enum class BackpackColor(val encoded: Int) {
    OFF(0x0000),
    RED(0x7c00),
    GREEN(0x03e0),
    BLUE(0x001f),
    WHITE(0x7fff)
}

class CozmoConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CozmoState())
    val state: StateFlow<CozmoState> = _state.asStateFlow()

    private val sendMutex = Mutex()
    private val seqLock = Any()
    private val cameraAssembler = CozmoCameraAssembler()
    private val cubes = linkedMapOf<Long, CubeInfo>()

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var pingJob: Job? = null
    private var robotAddress: InetSocketAddress? = null

    private var txSeq = 0
    private var lastRobotSeq = CozmoProtocol.OOB_SEQ
    private var pingCounter = 0

    suspend fun connect() {
        disconnect()
        _state.value = CozmoState(connection = ConnectionState.CONNECTING)

        try {
            robotAddress = InetSocketAddress(
                InetAddress.getByName(CozmoProtocol.ROBOT_HOST),
                CozmoProtocol.ROBOT_PORT
            )
            socket = DatagramSocket().apply {
                soTimeout = 700
                receiveBufferSize = 128 * 1024
                sendBufferSize = 128 * 1024
            }

            receiveJob = scope.launch { receiveLoop() }
            sendFrameNow(CozmoProtocol.resetFrame())

            pingJob = scope.launch {
                while (isActive) {
                    delay(500)
                    if (_state.value.connection >= ConnectionState.CONNECTED) {
                        sendFrameNow(CozmoProtocol.pingFrame(lastRobotSeq, pingCounter++))
                    }
                }
            }
        } catch (t: Throwable) {
            fail("Connexion impossible: " + t.message, t)
        }
    }

    fun disconnect() {
        receiveJob?.cancel()
        pingJob?.cancel()
        receiveJob = null
        pingJob = null
        socket?.close()
        socket = null
        robotAddress = null
        txSeq = 0
        lastRobotSeq = CozmoProtocol.OOB_SEQ
        pingCounter = 0
        cubes.clear()
        _state.value = CozmoState(connection = ConnectionState.DISCONNECTED)
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    fun drive(leftMmps: Float, rightMmps: Float) {
        sendCommand(0x32, CozmoProtocol.leFloats(leftMmps, rightMmps, 0f, 0f))
    }

    fun stopAllMotors() = sendCommand(0x3b)

    fun moveHead(speedRadPerSec: Float) {
        sendCommand(0x35, CozmoProtocol.leFloats(speedRadPerSec))
    }

    fun moveLift(speedRadPerSec: Float) {
        sendCommand(0x34, CozmoProtocol.leFloats(speedRadPerSec))
    }

    fun setHeadLight(enabled: Boolean) {
        sendCommand(0x0b, byteArrayOf((if (enabled) 1 else 0).toByte()))
    }

    fun enableCamera(enabled: Boolean) {
        _state.value = _state.value.copy(cameraEnabled = enabled)
        val mode = if (enabled) 1 else 0
        sendCommand(0x4c, byteArrayOf(mode.toByte(), 4.toByte()))
        sendCommand(0x66, byteArrayOf(0.toByte()))
    }

    fun setAccessoryDiscovery(enabled: Boolean) {
        _state.value = _state.value.copy(cubeDiscovery = enabled)
        sendCommand(0x0a, byteArrayOf((if (enabled) 1 else 0).toByte()))
    }

    fun setRobotVolume(percent: Float) {
        val value = (percent.coerceIn(0f, 1f) * 65535f).toInt()
        val payload = ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort())
            .array()
        sendCommand(0x64, payload)
    }

    fun setBackpackColor(color: BackpackColor) {
        val state = lightState(color.encoded)
        val center = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(3) { put(state) }
            put(0)
        }.array()
        val side = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(2) { put(state) }
            put(0)
        }.array()
        sendCommand(0x03, center)
        sendCommand(0x11, side)
    }

    suspend fun playPcm22050(samples: ShortArray) {
        if (samples.isEmpty()) return
        _state.value = _state.value.copy(audioStreaming = true)

        try {
            sendCommandImmediate(0x9f)

            var offset = 0
            while (offset < samples.size) {
                val payload = ByteArray(744) { 0xff.toByte() }
                val n = minOf(744, samples.size - offset)

                for (i in 0 until n) {
                    payload[i] = muLaw(samples[offset + i])
                }

                sendCommandImmediate(0x8e, payload)
                offset += n
                delay(34)
            }

            sendCommandImmediate(0x8f)
        } finally {
            _state.value = _state.value.copy(audioStreaming = false)
        }
    }

    private fun lightState(color: Int): ByteArray {
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(color.toShort())
            .putShort(color.toShort())
            .put(0)
            .put(0)
            .put(0)
            .put(0)
            .putShort(0)
            .array()
    }

    private fun muLaw(input: Short): Byte {
        var sample = input.toInt()
        var sign = 0

        if (sample < 0) {
            sample = -sample
            sign = 0x80
        }

        sample = (sample + 132).coerceAtMost(0x7fff)

        var mask = 0x4000
        var position = 14

        while ((sample and mask) != mask && position >= 7) {
            mask = mask ushr 1
            position--
        }

        val lsb = (sample shr (position - 4)) and 0x0f
        val value = sign or ((position - 7) shl 4) or lsb

        return (value.inv() and 0xff).toByte()
    }

    private fun initializeAfterFirmwareSignature() {
        sendCommand(0x25)
        sendCommand(0x25)
    }

    private fun initializeAfterBodyInfo() {
        sendCommand(0x45, CozmoProtocol.setOriginPayload())
        sendCommand(0x4b, CozmoProtocol.syncTimePayload())
        _state.value = _state.value.copy(connection = ConnectionState.READY)
    }

    private fun sendCommand(id: Int, payload: ByteArray = byteArrayOf()) {
        scope.launch {
            sendCommandImmediate(id, payload)
        }
    }

    private suspend fun sendCommandImmediate(id: Int, payload: ByteArray = byteArrayOf()) {
        if (socket == null) return

        val current = synchronized(seqLock) {
            val value = txSeq
            txSeq = (txSeq + 1) % CozmoProtocol.MAX_SEQ
            value
        }

        sendFrameNow(CozmoProtocol.commandFrame(current, lastRobotSeq, id, payload))
    }

    private suspend fun sendFrameNow(bytes: ByteArray) {
        val sock = socket ?: return
        val address = robotAddress ?: return

        try {
            // connect() est appelé depuis viewModelScope (Main). Le RESET initial
            // passe donc aussi par cette fonction : forcer TOUT envoi UDP sur IO
            // évite NetworkOnMainThreadException sur Android récent.
            withContext(Dispatchers.IO) {
                sendMutex.withLock {
                    sock.send(DatagramPacket(bytes, bytes.size, address))
                }
            }

            _state.value = _state.value.copy(
                packetsSent = _state.value.packetsSent + 1
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (!sock.isClosed) {
                val type = t::class.java.simpleName.ifBlank { t::class.java.name }
                val detail = t.message ?: "(aucun message)"
                fail("Erreur UDP TX [" + type + "] : " + detail, t)
            }
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(8192)

        while (scope.isActive && socket?.isClosed == false) {
            val sock = socket ?: break
            val datagram = DatagramPacket(buffer, buffer.size)

            try {
                sock.receive(datagram)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!sock.isClosed) {
                    val type = t::class.java.simpleName.ifBlank { t::class.java.name }
                    val detail = t.message ?: "(aucun message)"
                    fail("Erreur UDP RX [" + type + "] : " + detail, t)
                }
                break
            }

            val frame = CozmoProtocol.decodeFrame(datagram.data, datagram.length) ?: continue

            if (frame.seq != CozmoProtocol.OOB_SEQ) {
                lastRobotSeq = frame.seq
            }

            _state.value = _state.value.copy(
                packetsReceived = _state.value.packetsReceived + frame.packets.size
            )

            frame.packets.forEach(::handlePacket)
        }
    }

    private fun handlePacket(packet: CozmoProtocol.Packet) {
        when (packet.type) {
            CozmoProtocol.PacketType.CONNECT -> {
                _state.value = _state.value.copy(connection = ConnectionState.CONNECTED)
            }

            CozmoProtocol.PacketType.DISCONNECT -> {
                _state.value = _state.value.copy(connection = ConnectionState.DISCONNECTED)
            }

            CozmoProtocol.PacketType.COMMAND,
            CozmoProtocol.PacketType.EVENT -> handleCommandOrEvent(packet.id, packet.payload)

            else -> Unit
        }
    }

    private fun handleCommandOrEvent(id: Int?, payload: ByteArray) {
        when (id) {
            0xee -> {
                _state.value = _state.value.copy(firmwareSeen = true)
                initializeAfterFirmwareSignature()
            }

            0xed -> {
                _state.value = _state.value.copy(bodySeen = true)
                initializeAfterBodyInfo()
            }

            0xf0 -> parseRobotState(payload)
            0xf2 -> parseImageChunk(payload)
            0xf3 -> parseObjectAvailable(payload)
            0xd0 -> parseObjectConnection(payload)
            0xce -> parseObjectPower(payload)
        }
    }

    private fun parseRobotState(payload: ByteArray) {
        if (payload.size < 80) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val headAngle = b.getFloat(40)
        val liftHeight = b.getFloat(44)
        val battery = b.getFloat(72)

        _state.value = _state.value.copy(
            connection = ConnectionState.READY,
            headAngleRad = headAngle,
            liftHeightMm = liftHeight,
            batteryVoltage = battery
        )
    }

    private fun parseImageChunk(payload: ByteArray) {
        val bitmap = cameraAssembler.accept(payload) ?: return

        _state.value = _state.value.copy(
            cameraBitmap = bitmap,
            cameraFrames = _state.value.cameraFrames + 1
        )
    }

    private fun parseObjectAvailable(payload: ByteArray) {
        if (payload.size < 9) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val factoryId = b.int.toLong() and 0xffffffffL
        val objectType = b.int
        val rssi = b.get().toInt()

        val previous = cubes[factoryId]
        cubes[factoryId] = (previous ?: CubeInfo(factoryId = factoryId)).copy(
            objectType = objectType,
            rssi = rssi
        )
        publishCubes()

        if (_state.value.cubeDiscovery && previous?.connected != true) {
            val connectPayload = ByteBuffer.allocate(5)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(factoryId.toInt())
                .put(1)
                .array()

            sendCommand(0x05, connectPayload)
        }
    }

    private fun parseObjectConnection(payload: ByteArray) {
        if (payload.size < 13) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val objectId = b.int.toLong() and 0xffffffffL
        val factoryId = b.int.toLong() and 0xffffffffL
        val objectType = b.int
        val connected = b.get().toInt() != 0

        val previous = cubes[factoryId] ?: CubeInfo(factoryId)

        cubes[factoryId] = previous.copy(
            objectId = objectId,
            objectType = objectType,
            connected = connected
        )

        publishCubes()
    }

    private fun parseObjectPower(payload: ByteArray) {
        if (payload.size < 9) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val objectId = b.int.toLong() and 0xffffffffL
        b.int
        val batteryLevel = b.get().toInt() and 0xff

        val entry = cubes.entries.firstOrNull { it.value.objectId == objectId } ?: return
        cubes[entry.key] = entry.value.copy(batteryLevel = batteryLevel)

        publishCubes()
    }

    private fun publishCubes() {
        _state.value = _state.value.copy(
            cubes = cubes.values.toList()
        )
    }

    private fun fail(message: String, t: Throwable? = null) {
        if (t != null) {
            Log.e("CozmoModern", message, t)
        } else {
            Log.e("CozmoModern", message)
        }

        _state.value = _state.value.copy(lastError = message)
    }

    companion object {
        const val HEAD_SPEED = 1.5f
        const val LIFT_SPEED = 1.5f
        const val DRIVE_SPEED = 70f
        const val TURN_SPEED = 55f

        fun radToDeg(rad: Float): Float = rad * 180f / PI.toFloat()
    }
}
