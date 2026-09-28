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
import kotlin.math.abs
import kotlin.math.sqrt

enum class ChassisOrientation {
    ON_THREADS,
    ON_BACK,
    ON_FACE,
    ON_LEFT_SIDE,
    ON_RIGHT_SIDE,
    WHEELIE
}

data class CozmoState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val batteryVoltage: Float? = null,
    val headAngleRad: Float? = null,
    val liftHeightMm: Float? = null,
    val poseAngleRad: Float? = null,
    val posePitchRad: Float? = null,
    val leftWheelSpeedMmps: Float? = null,
    val rightWheelSpeedMmps: Float? = null,
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null,
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    val robotStatus: Long = 0L,
    val pickedUp: Boolean = false,
    val falling: Boolean = false,
    val onCharger: Boolean = false,
    val charging: Boolean = false,
    val cliffDetected: Boolean = false,
    val wheelsMoving: Boolean = false,
    val cliffRaw: List<Int> = List(4) { 0 },
    val backpackTouchRaw: Int? = null,
    val chassisOrientation: ChassisOrientation = ChassisOrientation.ON_THREADS,
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
    val audioStreaming: Boolean = false,
    val faceExpression: CozmoFaceExpression = CozmoFaceExpression.NEUTRAL
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
    private val audioMutex = Mutex()
    private val cameraAssembler = CozmoCameraAssembler()
    private val receiveWindow = ReceiveSequenceWindow<CozmoProtocol.Packet>(
        size = 62,
        maxSeq = CozmoProtocol.MAX_SEQ
    )

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var pingJob: Job? = null
    private var robotAddress: InetSocketAddress? = null
    private var reliableTransport: ReliableCommandTransport? = null

    private var backpackJob: Job? = null
    private var faceRefreshJob: Job? = null

    private val cubeManager by lazy {
        CubeManager(
            scope = scope,
            sendCommand = { id, payload ->
                sendCommand(id, payload)
            },
            sendBatch = { commands ->
                sendBatch(commands)
            },
            onChanged = { cubes ->
                _state.value = _state.value.copy(cubes = cubes)
            }
        )
    }

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
                val handshakeStartedMs = System.currentTimeMillis()
                var handshakeWarningSent = false

                while (isActive) {
                    delay(500)

                    when (_state.value.connection) {
                        ConnectionState.CONNECTING -> {
                            // Le RESET initial peut être perdu ou arriver alors
                            // que le robot n'est pas encore prêt. On le renvoie
                            // tant que Cozmo n'a pas répondu au handshake.
                            sendFrameNow(CozmoProtocol.resetFrame())

                            if (
                                !handshakeWarningSent &&
                                System.currentTimeMillis() - handshakeStartedMs >=
                                    HANDSHAKE_WARNING_MS
                            ) {
                                handshakeWarningSent = true
                                fail(
                                    "Toujours aucune réponse de Cozmo après " +
                                        (HANDSHAKE_WARNING_MS / 1000L) +
                                        " s — RESET UDP retransmis automatiquement"
                                )
                            }
                        }

                        ConnectionState.CONNECTED,
                        ConnectionState.READY -> {
                            sendFrameNow(
                                CozmoProtocol.pingFrame(
                                    ack = lastRobotSeq,
                                    counter = pingCounter++
                                )
                            )
                        }

                        ConnectionState.DISCONNECTED -> Unit
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

        faceRefreshJob?.cancel()
        faceRefreshJob = null

        cubeManager.reset()

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
        receiveWindow.reset()

        _state.value = CozmoState(connection = ConnectionState.DISCONNECTED)
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    fun drive(
        leftMmps: Float,
        rightMmps: Float,
        accelMmps2: Float = 0f
    ) {
        sendCommand(
            0x32,
            CozmoProtocol.leFloats(
                leftMmps,
                rightMmps,
                accelMmps2,
                accelMmps2
            )
        )
    }

    fun stopAllMotors() = sendCommand(0x3b)

    fun moveHead(speedRadPerSec: Float) {
        sendCommand(0x35, CozmoProtocol.leFloats(speedRadPerSec))
    }

    fun moveLift(speedRadPerSec: Float) {
        sendCommand(0x34, CozmoProtocol.leFloats(speedRadPerSec))
    }

    /**
     * Position absolue de la tête, protocole natif Cozmo SetHeadAngle (0x37).
     * Contrairement à MoveHead (0x35), la commande s'arrête d'elle-même à la
     * cible et ne dépend donc pas d'un STOP temporisé.
     */
    fun setHeadAngle(
        angleRad: Float,
        maxSpeedRadPerSec: Float = 2.2f,
        accelRadPerSec2: Float = 8f,
        durationSec: Float = 0f
    ) {
        val payload = ByteBuffer.allocate(17)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(angleRad.coerceIn(MIN_HEAD_ANGLE_RAD, MAX_HEAD_ANGLE_RAD))
            .putFloat(maxSpeedRadPerSec.coerceIn(0.2f, 15f))
            .putFloat(accelRadPerSec2.coerceIn(0.5f, 30f))
            .putFloat(durationSec.coerceAtLeast(0f))
            .put(0)
            .array()
        sendCommand(0x37, payload)
    }

    /**
     * Position absolue du lift, protocole natif Cozmo SetLiftHeight (0x36).
     */
    fun setLiftHeight(
        heightMm: Float,
        maxSpeedRadPerSec: Float = 2.0f,
        accelRadPerSec2: Float = 8f,
        durationSec: Float = 0f
    ) {
        val payload = ByteBuffer.allocate(17)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(heightMm.coerceIn(MIN_LIFT_HEIGHT_MM, MAX_LIFT_HEIGHT_MM))
            .putFloat(maxSpeedRadPerSec.coerceIn(0.2f, 3f))
            .putFloat(accelRadPerSec2.coerceIn(0.5f, 30f))
            .putFloat(durationSec.coerceAtLeast(0f))
            .put(0)
            .array()
        sendCommand(0x36, payload)
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
                byteArrayOf((if (enabled) 1 else 0).toByte())
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
        // Ne jamais envoyer de commande LightCube pendant le handshake robot.
        // Les cubes ne sont activables qu'une fois Cozmo complètement READY.
        if (enabled && _state.value.connection != ConnectionState.READY) {
            _state.value = _state.value.copy(
                cubeDiscovery = false,
                lastError = "Cubes indisponibles tant que Cozmo n'est pas READY"
            )
            return
        }

        _state.value = _state.value.copy(
            cubeDiscovery = enabled,
            lastError = null
        )
        cubeManager.setDiscovery(enabled)
    }

    fun setFaceExpression(expression: CozmoFaceExpression) {
        _state.value = _state.value.copy(faceExpression = expression)

        if (_state.value.connection != ConnectionState.READY) {
            return
        }

        val payload = CozmoFaceDisplay.payload(expression)
        scope.launch {
            reliableTransport?.sendImmediate(
                OutboundCommand(
                    CozmoFaceDisplay.COMMAND_DISPLAY_IMAGE,
                    payload
                )
            )
        }

        ensureFaceRefresh()
    }

    suspend fun playFaceAnimation(
        animation: CozmoFaceAnimation,
        returnTo: CozmoFaceExpression = CozmoFaceExpression.NEUTRAL
    ) {
        if (_state.value.connection != ConnectionState.READY) return

        CozmoFaceDisplay.frames(animation).forEach { frame ->
            reliableTransport?.sendImmediate(
                OutboundCommand(
                    CozmoFaceDisplay.COMMAND_DISPLAY_IMAGE,
                    frame.payload
                )
            )
            delay(frame.durationMs)
        }

        setFaceExpression(returnTo)
    }

    private fun ensureFaceRefresh() {
        if (faceRefreshJob?.isActive == true) return

        faceRefreshJob = scope.launch {
            while (isActive) {
                delay(FACE_REFRESH_MS)

                if (_state.value.connection != ConnectionState.READY) {
                    continue
                }

                reliableTransport?.sendImmediate(
                    OutboundCommand(
                        CozmoFaceDisplay.COMMAND_DISPLAY_IMAGE,
                        CozmoFaceDisplay.payload(
                            _state.value.faceExpression
                        )
                    )
                )
            }
        }
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

    fun setCubeColor(
        factoryId: Long,
        color: BackpackColor
    ) {
        cubeManager.setSolidColor(factoryId, color)
    }

    fun setAllCubeColor(color: BackpackColor) {
        cubeManager.setAllSolidColor(color)
    }

    fun setCubePairPattern(
        factoryId: Long,
        first: BackpackColor,
        second: BackpackColor
    ) {
        cubeManager.setPairPattern(
            factoryId = factoryId,
            first = first,
            second = second
        )
    }

    fun setAllCubePairPattern(
        first: BackpackColor,
        second: BackpackColor
    ) {
        cubeManager.setAllPairPattern(first, second)
    }

    fun setCubeCornerColor(
        factoryId: Long,
        corner: Int,
        color: BackpackColor
    ) {
        cubeManager.setCornerColor(
            factoryId = factoryId,
            corner = corner,
            color = color
        )
    }

    fun setCubeAccelStreaming(
        factoryId: Long,
        enabled: Boolean
    ) {
        cubeManager.setAccelStreaming(factoryId, enabled)
    }

    fun setAllCubeAccelStreaming(enabled: Boolean) {
        cubeManager.setAllAccelStreaming(enabled)
    }

    fun startCubeChaser(
        factoryId: Long,
        color: BackpackColor
    ) {
        cubeManager.startChaser(factoryId, color)
    }

    fun stopCubeChaser(factoryId: Long) {
        cubeManager.stopChaser(factoryId)
    }

    suspend fun playPcm22050(samples: ShortArray) {
        if (samples.isEmpty()) return

        audioMutex.withLock {
            _state.value = _state.value.copy(audioStreaming = true)

            try {
                var offset = 0
                val frameDurationNanos =
                    1_000_000_000L / AUDIO_FRAME_RATE

                val streamStart = System.nanoTime()
                var frameIndex = 0L

                while (offset < samples.size) {
                    val payload =
                        CozmoAudioCodec.encodePacket(
                            samples = samples,
                            offset = offset
                        )

                    val count = minOf(
                        CozmoAudioCodec.SAMPLES_PER_PACKET,
                        samples.size - offset
                    )

                    reliableTransport?.sendImmediate(
                        OutboundCommand(0x8e, payload)
                    )
                    offset += count
                    frameIndex++

                    // Le contrôleur d'animation Cozmo consomme une trame audio
                    // à 30 Hz. Se caler sur cette horloge évite les petits
                    // trous audibles entre paquets.
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
            } finally {
                // Ne jamais suspendre dans le nettoyage d'une coroutine annulée :
                // l'ordre silence est simplement remis dans la file fiable.
                sendCommand(0x8f)
                _state.value = _state.value.copy(audioStreaming = false)
            }
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
                OutboundCommand(0x4b, CozmoProtocol.syncTimePayload()),
                OutboundCommand(0x60, byteArrayOf(1)),
                // Active une seule fois les états animation/audio, comme
                // l'AnimationController PyCozmo. Ne pas le renvoyer à chaque son.
                OutboundCommand(0x9f)
            )
        )

        _state.value = _state.value.copy(connection = ConnectionState.READY)

        // Position de repos stable à l'initialisation : tête au milieu de sa
        // course, lift en bas. On utilise les vraies consignes de position,
        // jamais une impulsion de vitesse qui pourrait finir en butée.
        setHeadAngle(HEAD_NEUTRAL_RAD, maxSpeedRadPerSec = 1.8f)
        setLiftHeight(MIN_LIFT_HEIGHT_MM, maxSpeedRadPerSec = 1.4f)
        setFaceExpression(CozmoFaceExpression.NEUTRAL)
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

            // PyCozmo : le seq de trame sert d'ACK vers le robot, mais les
            // EVENT sont hors fenêtre (OOB) et ne consomment PAS de numéro de
            // séquence. Il ne faut donc jamais dédupliquer une trame entière
            // simplement parce que frame.seq est identique à la précédente.
            if (frame.seq != CozmoProtocol.OOB_SEQ) {
                lastRobotSeq = frame.seq
            }

            _state.value = _state.value.copy(
                packetsReceived =
                    _state.value.packetsReceived + frame.packets.size,
                lastError = null
            )

            if (
                frame.type == CozmoProtocol.FrameType.ROBOT ||
                frame.type == CozmoProtocol.FrameType.ENGINE
            ) {
                var packetSeq = frame.firstSeq

                frame.packets.forEach { packet ->
                    if (packet.type.id >= CozmoProtocol.PacketType.EVENT.id) {
                        // EVENT / KEYFRAME / PING : livraison immédiate,
                        // exactement comme Packet.is_oob() dans PyCozmo.
                        handlePacket(packet)
                    } else {
                        receiveWindow.put(packetSeq, packet)
                        packetSeq =
                            (packetSeq + 1) % CozmoProtocol.MAX_SEQ
                    }
                }

                while (true) {
                    val packet = receiveWindow.get() ?: break
                    handlePacket(packet)
                }
            } else {
                frame.packets.forEach(::handlePacket)
            }
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

            // Cubes / objets BLE.
            0xf3 -> cubeManager.onObjectAvailable(payload)
            0xd0 -> cubeManager.onObjectConnectionState(payload)
            0xce -> cubeManager.onObjectPowerLevel(payload)
            0xf5 -> cubeManager.onObjectAccel(payload)
            0xb4 -> cubeManager.onObjectMoved(payload)
            0xb5 -> cubeManager.onObjectStoppedMoving(payload)
            0xb6 -> cubeManager.onObjectTapped(payload)
            0xb9 -> cubeManager.onObjectTapFiltered(payload)
            0xd7 -> cubeManager.onObjectUpAxisChanged(payload)
        }
    }

    private fun parseRobotState(payload: ByteArray) {
        if (payload.size < 80) return

        val b = ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN)

        val poseAngle = b.getFloat(24)
        val posePitch = b.getFloat(28)
        val leftWheel = b.getFloat(32)
        val rightWheel = b.getFloat(36)
        val headAngle = b.getFloat(40)
        val liftHeight = b.getFloat(44)
        val accelX = b.getFloat(48)
        val accelY = b.getFloat(52)
        val accelZ = b.getFloat(56)
        val gyroX = b.getFloat(60)
        val gyroY = b.getFloat(64)
        val gyroZ = b.getFloat(68)
        val battery = b.getFloat(72)
        val status = b.getInt(76).toLong() and 0xffffffffL

        val cliffRaw =
            if (payload.size >= 88) {
                List(4) { index ->
                    b.getShort(80 + index * 2).toInt() and 0xffff
                }
            } else {
                _state.value.cliffRaw
            }

        val backpackTouch =
            if (payload.size >= 90) {
                b.getShort(88).toInt() and 0xffff
            } else {
                _state.value.backpackTouchRaw
            }

        // poseAngle est le cap/yaw dans le plan : il ne dit absolument pas
        // si Cozmo est couché sur un côté. Pour le roulis on utilise la
        // composante latérale de la gravité mesurée par l'accéléromètre.
        val orientation = classifyChassisOrientation(
            posePitchRad = posePitch,
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ
        )

        _state.value = _state.value.copy(
            connection = ConnectionState.READY,
            headAngleRad = headAngle,
            liftHeightMm = liftHeight,
            batteryVoltage = battery,
            poseAngleRad = poseAngle,
            posePitchRad = posePitch,
            leftWheelSpeedMmps = leftWheel,
            rightWheelSpeedMmps = rightWheel,
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            robotStatus = status,
            pickedUp = (status and STATUS_IS_PICKED_UP) != 0L,
            falling = (status and STATUS_IS_FALLING) != 0L,
            onCharger = (status and STATUS_IS_ON_CHARGER) != 0L,
            charging = (status and STATUS_IS_CHARGING) != 0L,
            cliffDetected = (status and STATUS_CLIFF_DETECTED) != 0L,
            wheelsMoving = (status and STATUS_WHEELS_MOVING) != 0L,
            cliffRaw = cliffRaw,
            backpackTouchRaw = backpackTouch,
            chassisOrientation = orientation
        )
    }

    private fun parseImageChunk(payload: ByteArray) {
        val bitmap = cameraAssembler.accept(payload) ?: return

        _state.value = _state.value.copy(
            cameraBitmap = bitmap,
            cameraFrames = _state.value.cameraFrames + 1
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
        private const val FACE_REFRESH_MS = 4_000L
        private const val HANDSHAKE_WARNING_MS = 6_000L
        private const val AUDIO_FRAME_RATE = 30L

        const val MIN_HEAD_ANGLE_RAD = -0.43633232f   // -25°
        const val MAX_HEAD_ANGLE_RAD = 0.7766715f    // +44.5°
        const val HEAD_NEUTRAL_RAD = 0.1701696f      // +9.75°
        const val MIN_LIFT_HEIGHT_MM = 32f
        const val MAX_LIFT_HEIGHT_MM = 92f
        private const val GRAVITY_AXIS_RATIO = 0.70f

        /**
         * Déduit la position physique de Cozmo avec le vecteur de gravité.
         *
         * Le pitch fourni par le firmware devient imprécis aux positions
         * intermédiaires : un côté gauche pouvait être pris pour la face et
         * un wheelie pour le dos. L'axe dominant de l'accéléromètre permet de
         * distinguer directement les six appuis du châssis, quelle que soit
         * l'unité utilisée par le firmware.
         *
         * Repère Cozmo observé :
         * +Z = chenilles, -Z = dos
         * -Y = côté gauche, +Y = côté droit
         * -X = face, +X = arrière / wheelie
         *
         * Le pitch n'est conservé qu'en secours si la norme de l'accéléro est
         * inexploitable.
         */
        internal fun classifyChassisOrientation(
            posePitchRad: Float,
            accelX: Float,
            accelY: Float,
            accelZ: Float
        ): ChassisOrientation {
            val magnitude = sqrt(
                (
                    accelX * accelX +
                        accelY * accelY +
                        accelZ * accelZ
                    ).toDouble()
            ).toFloat()

            if (magnitude >= 0.001f) {
                val xRatio = abs(accelX) / magnitude
                val yRatio = abs(accelY) / magnitude
                val zRatio = abs(accelZ) / magnitude
                val dominant = maxOf(xRatio, yRatio, zRatio)

                if (dominant >= GRAVITY_AXIS_RATIO) {
                    return when (dominant) {
                        xRatio ->
                            if (accelX < 0f) ChassisOrientation.ON_FACE
                            else ChassisOrientation.WHEELIE

                        yRatio ->
                            if (accelY < 0f) ChassisOrientation.ON_LEFT_SIDE
                            else ChassisOrientation.ON_RIGHT_SIDE

                        else ->
                            if (accelZ < 0f) ChassisOrientation.ON_BACK
                            else ChassisOrientation.ON_THREADS
                    }
                }
            }

            return when {
                posePitchRad < -1.0f -> ChassisOrientation.ON_FACE
                posePitchRad > 1.0f -> ChassisOrientation.ON_BACK
                else -> ChassisOrientation.ON_THREADS
            }
        }
        private const val STATUS_IS_PICKED_UP = 0x0008L
        private const val STATUS_IS_FALLING = 0x0020L
        private const val STATUS_IS_ON_CHARGER = 0x1000L
        private const val STATUS_IS_CHARGING = 0x2000L
        private const val STATUS_CLIFF_DETECTED = 0x4000L
        private const val STATUS_WHEELS_MOVING = 0x8000L
        const val HEAD_SPEED = 1.5f
        const val LIFT_SPEED = 1.5f
        const val DRIVE_SPEED = 70f
        const val TURN_SPEED = 55f

        fun radToDeg(rad: Float): Float =
            rad * 180f / PI.toFloat()
    }
}
