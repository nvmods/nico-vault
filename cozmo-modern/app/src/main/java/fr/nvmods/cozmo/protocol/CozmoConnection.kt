package fr.nvmods.cozmo.protocol

import android.util.Log
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

data class CozmoState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val batteryVoltage: Float? = null,
    val headAngleRad: Float? = null,
    val liftHeightMm: Float? = null,
    val firmwareSeen: Boolean = false,
    val bodySeen: Boolean = false,
    val lastError: String? = null,
    val packetsReceived: Long = 0,
    val packetsSent: Long = 0
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    READY
}

class CozmoConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CozmoState())
    val state: StateFlow<CozmoState> = _state.asStateFlow()

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
                receiveBufferSize = 64 * 1024
                sendBufferSize = 64 * 1024
            }

            receiveJob = scope.launch { receiveLoop() }
            sendRaw(CozmoProtocol.resetFrame())

            pingJob = scope.launch {
                while (isActive) {
                    delay(500)
                    if (_state.value.connection >= ConnectionState.CONNECTED) {
                        sendRaw(CozmoProtocol.pingFrame(lastRobotSeq, pingCounter++))
                    }
                }
            }
        } catch (t: Throwable) {
            fail("Connexion impossible: ${t.message}", t)
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

    fun moveHead(speedRadPerSec: Float) =
        sendCommand(0x35, CozmoProtocol.leFloats(speedRadPerSec))

    fun moveLift(speedRadPerSec: Float) =
        sendCommand(0x34, CozmoProtocol.leFloats(speedRadPerSec))

    fun setHeadLight(enabled: Boolean) =
        sendCommand(0x0b, byteArrayOf((if (enabled) 1 else 0).toByte()))

    fun enableCamera(enabled: Boolean) {
        val mode = if (enabled) 1 else 0
        sendCommand(0x4c, byteArrayOf(mode.toByte(), 4.toByte()))
        sendCommand(0x66, byteArrayOf(0.toByte()))
    }

    private fun initializeAfterFirmwareSignature() {
        // PyCozmo envoie Enable deux fois: la répétition déclenche BodyInfo sur
        // le firmware 2381 utilisé par les Cozmo de production.
        sendCommand(0x25)
        sendCommand(0x25)
    }

    private fun initializeAfterBodyInfo() {
        sendCommand(0x45, CozmoProtocol.setOriginPayload())
        sendCommand(0x4b, CozmoProtocol.syncTimePayload())
        _state.value = _state.value.copy(connection = ConnectionState.READY)
    }

    private fun sendCommand(id: Int, payload: ByteArray = byteArrayOf()) {
        if (socket == null) return
        val current = txSeq
        txSeq = (txSeq + 1) % CozmoProtocol.MAX_SEQ
        sendRaw(CozmoProtocol.commandFrame(current, lastRobotSeq, id, payload))
    }

    private fun sendRaw(bytes: ByteArray) {
        val sock = socket ?: return
        val address = robotAddress ?: return

        scope.launch {
            try {
                sock.send(DatagramPacket(bytes, bytes.size, address))
                _state.value = _state.value.copy(packetsSent = _state.value.packetsSent + 1)
            } catch (t: Throwable) {
                if (!sock.isClosed) fail("Erreur UDP TX: ${t.message}", t)
            }
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(4096)

        while (scope.isActive && socket?.isClosed == false) {
            val sock = socket ?: break
            val datagram = DatagramPacket(buffer, buffer.size)

            try {
                sock.receive(datagram)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (!sock.isClosed) fail("Erreur UDP RX: ${t.message}", t)
                break
            }

            val frame = CozmoProtocol.decodeFrame(datagram.data, datagram.length) ?: continue
            if (frame.seq != CozmoProtocol.OOB_SEQ) lastRobotSeq = frame.seq

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

    private fun fail(message: String, t: Throwable? = null) {
        if (t != null) Log.e("CozmoModern", message, t) else Log.e("CozmoModern", message)
        _state.value = _state.value.copy(lastError = message)
    }

    companion object {
        const val HEAD_SPEED = 1.5f
        const val LIFT_SPEED = 1.5f
        const val DRIVE_SPEED = 70f
        const val TURN_SPEED = 55f
        const val MAX_HEAD_DEG = 44.5f
        const val MIN_HEAD_DEG = -25f

        fun radToDeg(rad: Float): Float = (rad * 180f / PI.toFloat())
    }
}
