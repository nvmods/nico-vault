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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val batteryLevel: Int? = null,
    val lightColor: BackpackColor = BackpackColor.OFF
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
    val txRetries: Long = 0,
    val cameraEnabled: Boolean = false,
    val cameraBitmap: Bitmap? = null,
    val cameraFrames: Long = 0,
    val headLightEnabled: Boolean = false,
    val backpackColor: BackpackColor = BackpackColor.OFF,
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
    private val cameraAssembler = CozmoCameraAssembler()
    private val cubes = linkedMapOf<Long, CubeInfo>()
    private val connectingCubeFactories = mutableSetOf<Long>()
    private val recentRobotSeq = LinkedHashSet<Int>()

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var pingJob: Job? = null
    private var robotAddress: InetSocketAddress? = null
    private var reliableTransport: ReliableCommandTransport? = null

    private var backpackJob: Job? = null
    private val cubeLightJobs = mutableMapOf<Long, Job>()

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
                receiveBufferSize = 256 * 1024
                sendBufferSize = 256 * 1024
            }

            reliableTransport = ReliableCommandTransport(
                scope = scope,
                ackProvider = { lastRobotSeq },
                sendRaw = ::sendFrameNow,
                onRetryCount = { retryCount ->
                    _state.value = _state.value.copy(txRetries = retryCount)
                }
            ).also { it.start() }

            receiveJob = scope.launch { receiveLoop() }
            sendFrameNow(CozmoProtocol.resetFrame())

            pingJob = scope.launch {
                while (isActive) {
                    delay(500)
                    if (_state.value.connection >= ConnectionState.CONNECTED) {
                        sendFrameNow(
                            CozmoProtocol.pingFrame(
                                ack = lastRobotSeq,
                                counter = pingCounter++
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            fail("Connexion impossible: " + (t.message ?: t::class.java.simpleName), t)
        }
    }

    fun disconnect() {
        backpackJob?.cancel()
        backpackJob = null

        cubeLightJobs.values.forEach { it.cancel() }
        cubeLightJobs.clear()

        receiveJob?.cancel()
        pingJob?.cancel()
        receiveJob = null
        pingJob = null

        reliableTransport?.stop()
        reliableTransport = null

        socket?.close()
        socket = null
        robotAddress = null

        lastRobotSeq = CozmoProtocol.OOB_SEQ
        pingCounter = 0
        recentRobotSeq.clear()
        connectingCubeFactories.clear()
        cubes.clear()

        _state.value = CozmoState(connection = ConnectionState.DISCONNECTED)
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    fun drive(leftMmps: Float, rightMmps: Float) {
        sendCommand(
            0x32,
            CozmoProtocol.leFloats(leftMmps, rightMmps, 0f, 0f)
        )
    }

    fun stopAllMotors() = sendCommand(0x3b)

    fun moveHead(speedRadPerSec: Float) {
        sendCommand(0x35, CozmoProtocol.leFloats(speedRadPerSec))
    }

    fun moveLift(speedRadPerSec: Float) {
        sendCommand(0x34, CozmoProtocol.leFloats(speedRadPerSec))
    }

    fun setHeadLight(enabled: Boolean) {
        _state.value = _state.value.copy(headLightEnabled = enabled)
        sendCommand(
            0x0b,
            byteArrayOf((if (enabled) 1 else 0).toByte())
        )
    }

    fun enableCamera(enabled: Boolean) {
        val irWasEnabled = _state.value.headLightEnabled
        _state.value = _state.value.copy(cameraEnabled = enabled)

        val mode = if (enabled) 1 else 0
        val commands = mutableListOf(
            OutboundCommand(
                0x4c,
                byteArrayOf(mode.toByte(), 4.toByte())
            ),
            OutboundCommand(
                0x66,
                byteArrayOf(0.toByte())
            )
        )

        if (enabled && irWasEnabled) {
            commands += OutboundCommand(
                0x0b,
                byteArrayOf(1.toByte())
            )
        }

        sendBatch(commands)
    }

    fun setAccessoryDiscovery(enabled: Boolean) {
        _state.value = _state.value.copy(cubeDiscovery = enabled)
        sendCommand(
            0x0a,
            byteArrayOf((if (enabled) 1 else 0).toByte())
        )
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
        _state.value = _state.value.copy(backpackColor = color)

        backpackJob?.cancel()
        backpackJob = scope.launch {
            delay(70)

            val light = lightState(color.encoded)
            val center = ByteBuffer.allocate(31)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    repeat(3) { put(light) }
                    put(0)
                }
                .array()

            val side = ByteBuffer.allocate(21)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    repeat(2) { put(light) }
                    put(0)
                }
                .array()

            sendBatch(
                listOf(
                    OutboundCommand(0x03, center),
                    OutboundCommand(0x11, side)
                )
            )
        }
    }

    fun setCubeColor(objectId: Long, color: BackpackColor) {
        cubeLightJobs[objectId]?.cancel()

        cubeLightJobs[objectId] = scope.launch {
            delay(50)

            val selectCube = ByteBuffer.allocate(5)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(objectId.toInt())
                .put(0.toByte())
                .array()

            val light = lightState(color.encoded)
            val lights = ByteBuffer.allocate(40)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    repeat(4) { put(light) }
                }
                .array()

            sendBatch(
                listOf(
                    OutboundCommand(0x10, selectCube),
                    OutboundCommand(0x04, lights)
                )
            )

            val entry = cubes.entries.firstOrNull {
                it.value.objectId == objectId
            }

            if (entry != null) {
                cubes[entry.key] = entry.value.copy(lightColor = color)
                publishCubes()
            }
        }
    }

    fun setAllCubeColor(color: BackpackColor) {
        cubes.values
            .mapNotNull { cube ->
                cube.objectId?.takeIf { cube.connected }
            }
            .forEach { objectId ->
                setCubeColor(objectId, color)
            }
    }

    suspend fun playPcm22050(samples: ShortArray) {
        if (samples.isEmpty()) return

        _state.value = _state.value.copy(audioStreaming = true)

        try {
            sendCommand(0x9f)

            var offset = 0

            val frameDurationNanos =
                CozmoAudioCodec.packetDurationNanos()

            val streamStart = System.nanoTime()
            var frameIndex = 0L

            while (offset < samples.size) {
                // PyCozmo laisse le reste du dernier paquet à 0.
                // Le codec Cozmo n'utilise PAS le mapping μ-law téléphonie
                // standard où 0xff représente le silence.
                val payload =
                    CozmoAudioCodec.encodePacket(
                        samples = samples,
                        offset = offset
                    )

                val count = minOf(
                    CozmoAudioCodec.SAMPLES_PER_PACKET,
                    samples.size - offset
                )

                sendCommand(0x8e, payload)
                offset += count
                frameIndex++

                // 744 / 22050 = 33,741... ms. On se cale sur une horloge
                // absolue pour éviter la dérive d'un simple delay(34).
                val target =
                    streamStart + frameIndex * frameDurationNanos
                val remaining =
                    target - System.nanoTime()

                if (remaining > 0) {
                    delay(
                        (remaining + 999_999L) /
                            1_000_000L
                    )
                }
            }

            sendCommand(0x8f)
        } finally {
            _state.value = _state.value.copy(audioStreaming = false)
        }
    }

    private fun lightState(color: Int): ByteArray {
        return ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(color.toShort())
            .putShort(color.toShort())
            .put(0)
            .put(0)
            .put(0)
            .put(0)
            .putShort(0)
            .array()
    }

    private fun initializeAfterFirmwareSignature() {
        sendBatch(
            listOf(
                OutboundCommand(0x25),
                OutboundCommand(0x25)
            )
        )
    }

    private fun initializeAfterBodyInfo() {
        sendBatch(
            listOf(
                OutboundCommand(0x45, CozmoProtocol.setOriginPayload()),
                OutboundCommand(0x4b, CozmoProtocol.syncTimePayload())
            )
        )

        _state.value = _state.value.copy(connection = ConnectionState.READY)
    }

    private fun sendCommand(
        id: Int,
        payload: ByteArray = byteArrayOf()
    ) {
        if (socket == null) return

        reliableTransport?.enqueue(
            OutboundCommand(
                id = id,
                payload = payload
            )
        )
    }

    private fun sendBatch(commands: List<OutboundCommand>) {
        if (socket == null) return
        reliableTransport?.enqueueBatch(commands)
    }

    private suspend fun sendFrameNow(bytes: ByteArray) {
        val sock = socket ?: return
        val address = robotAddress ?: return

        try {
            withContext(Dispatchers.IO) {
                sendMutex.withLock {
                    sock.send(
                        DatagramPacket(
                            bytes,
                            bytes.size,
                            address
                        )
                    )
                }
            }

            _state.value = _state.value.copy(
                packetsSent = _state.value.packetsSent + 1
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (!sock.isClosed) {
                val type = t::class.java.simpleName.ifBlank {
                    t::class.java.name
                }
                val detail = t.message ?: "(aucun message)"

                fail(
                    "Erreur UDP TX [" + type + "] : " + detail,
                    t
                )
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
                    val type = t::class.java.simpleName.ifBlank {
                        t::class.java.name
                    }
                    val detail = t.message ?: "(aucun message)"

                    fail(
                        "Erreur UDP RX [" + type + "] : " + detail,
                        t
                    )
                }
                break
            }

            val frame = CozmoProtocol.decodeFrame(
                datagram.data,
                datagram.length
            ) ?: continue

            reliableTransport?.acknowledge(frame.ack)

            var duplicate = false

            if (frame.seq != CozmoProtocol.OOB_SEQ) {
                lastRobotSeq = frame.seq

                synchronized(recentRobotSeq) {
                    duplicate = recentRobotSeq.contains(frame.seq)

                    if (!duplicate) {
                        recentRobotSeq += frame.seq

                        if (recentRobotSeq.size > 128) {
                            val first = recentRobotSeq.firstOrNull()
                            if (first != null) {
                                recentRobotSeq.remove(first)
                            }
                        }
                    }
                }
            }

            if (duplicate) continue

            _state.value = _state.value.copy(
                packetsReceived =
                    _state.value.packetsReceived + frame.packets.size
            )

            frame.packets.forEach(::handlePacket)
        }
    }

    private fun handlePacket(packet: CozmoProtocol.Packet) {
        when (packet.type) {
            CozmoProtocol.PacketType.CONNECT -> {
                _state.value = _state.value.copy(
                    connection = ConnectionState.CONNECTED
                )
            }

            CozmoProtocol.PacketType.DISCONNECT -> {
                _state.value = _state.value.copy(
                    connection = ConnectionState.DISCONNECTED
                )
            }

            CozmoProtocol.PacketType.COMMAND,
            CozmoProtocol.PacketType.EVENT -> {
                handleCommandOrEvent(
                    packet.id,
                    packet.payload
                )
            }

            else -> Unit
        }
    }

    private fun handleCommandOrEvent(
        id: Int?,
        payload: ByteArray
    ) {
        when (id) {
            0xee -> {
                _state.value = _state.value.copy(
                    firmwareSeen = true
                )
                initializeAfterFirmwareSignature()
            }

            0xed -> {
                _state.value = _state.value.copy(
                    bodySeen = true
                )
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

        val b = ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN)

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

        val b = ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN)

        val factoryId =
            b.int.toLong() and 0xffffffffL

        val objectType = b.int
        val rssi = b.get().toInt()

        val previous = cubes[factoryId]

        cubes[factoryId] =
            (previous ?: CubeInfo(factoryId = factoryId)).copy(
                objectType = objectType,
                rssi = rssi
            )

        publishCubes()

        if (
            _state.value.cubeDiscovery &&
            previous?.connected != true &&
            connectingCubeFactories.add(factoryId)
        ) {
            // ObjectAvailable peut être publié plusieurs fois avant que
            // ObjectConnectionState arrive. Une seule tentative à la fois :
            // sinon on inonde le lien radio des cubes avec ObjectConnect.
            val connectPayload =
                ByteBuffer.allocate(5)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(factoryId.toInt())
                    .put(1)
                    .array()

            sendCommand(
                0x05,
                connectPayload
            )
        }
    }

    private fun parseObjectConnection(payload: ByteArray) {
        if (payload.size < 13) return

        val b = ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN)

        val objectId =
            b.int.toLong() and 0xffffffffL

        val factoryId =
            b.int.toLong() and 0xffffffffL

        val objectType = b.int
        val connected = b.get().toInt() != 0

        val previous =
            cubes[factoryId] ?: CubeInfo(factoryId)

        connectingCubeFactories.remove(factoryId)

        cubes[factoryId] = previous.copy(
            objectId = objectId,
            objectType = objectType,
            connected = connected
        )

        publishCubes()
    }

    private fun parseObjectPower(payload: ByteArray) {
        if (payload.size < 9) return

        val b = ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN)

        val objectId =
            b.int.toLong() and 0xffffffffL

        b.int
        val batteryLevel = b.get().toInt() and 0xff

        val entry =
            cubes.entries.firstOrNull {
                it.value.objectId == objectId
            } ?: return

        cubes[entry.key] =
            entry.value.copy(
                batteryLevel = batteryLevel
            )

        publishCubes()
    }

    private fun publishCubes() {
        _state.value = _state.value.copy(
            cubes = cubes.values.toList()
        )
    }

    private fun fail(
        message: String,
        t: Throwable? = null
    ) {
        if (t != null) {
            Log.e("CozmoModern", message, t)
        } else {
            Log.e("CozmoModern", message)
        }

        _state.value = _state.value.copy(
            lastError = message
        )
    }

    companion object {
        const val HEAD_SPEED = 1.5f
        const val LIFT_SPEED = 1.5f
        const val DRIVE_SPEED = 70f
        const val TURN_SPEED = 55f

        fun radToDeg(rad: Float): Float =
            rad * 180f / PI.toFloat()
    }
}
