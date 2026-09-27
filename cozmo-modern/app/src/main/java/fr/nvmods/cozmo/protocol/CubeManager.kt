package fr.nvmods.cozmo.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

enum class CubeUpAxis(val raw: Int, val label: String) {
    X_NEGATIVE(0, "-X"),
    X_POSITIVE(1, "+X"),
    Y_NEGATIVE(2, "-Y"),
    Y_POSITIVE(3, "+Y"),
    Z_NEGATIVE(4, "-Z"),
    Z_POSITIVE(5, "+Z"),
    UNKNOWN(7, "?");

    companion object {
        fun fromRaw(value: Int): CubeUpAxis =
            entries.firstOrNull { it.raw == value } ?: UNKNOWN
    }
}

data class CubeInfo(
    val factoryId: Long,
    val objectId: Long? = null,
    val objectType: Int = -1,
    val rssi: Int? = null,
    val connected: Boolean = false,
    val batteryLevel: Int? = null,
    val missedPackets: Long? = null,
    val connectAttempts: Int = 0,
    val disconnectCount: Int = 0,
    val ledColors: List<BackpackColor> = List(4) { BackpackColor.OFF },
    val accelStreaming: Boolean = false,
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null,
    val upAxis: CubeUpAxis = CubeUpAxis.UNKNOWN,
    val moving: Boolean = false,
    val tapCount: Int = 0,
    val tapIntensity: Int? = null,
    val lastEvent: String = "—"
) {
    val logicalNumber: Int?
        get() = objectType.takeIf { it in 1..3 }

    val displayName: String
        get() = logicalNumber?.let { "Cube " + it } ?: "Objet " + objectType
}

internal class CubeManager(
    private val scope: CoroutineScope,
    private val sendCommand: (Int, ByteArray) -> Unit,
    private val sendSequential: (
        commands: List<OutboundCommand>,
        waitUntilAcknowledged: Boolean
    ) -> Unit,
    private val onChanged: (List<CubeInfo>) -> Unit
) {
    private val lock = Any()
    private val cubesByFactory = linkedMapOf<Long, CubeInfo>()
    private val lastSeenNanos = ConcurrentHashMap<Long, Long>()
    private val lastAccelPublishNanos = ConcurrentHashMap<Long, Long>()
    private val accelWantedFactories = mutableSetOf<Long>()
    private val disconnectJobs = mutableMapOf<Long, Job>()

    private var discoveryEnabled = false
    private var managerJob: Job? = null
    private var connectingFactoryId: Long? = null
    private var connectWaiter: CompletableDeferred<Boolean>? = null

    fun reset() {
        managerJob?.cancel()
        managerJob = null
        disconnectJobs.values.forEach { it.cancel() }
        disconnectJobs.clear()
        connectWaiter?.cancel()
        connectWaiter = null

        synchronized(lock) {
            cubesByFactory.clear()
            accelWantedFactories.clear()
            connectingFactoryId = null
        }

        lastSeenNanos.clear()
        lastAccelPublishNanos.clear()
        publish()
    }

    fun setDiscovery(enabled: Boolean) {
        discoveryEnabled = enabled

        if (enabled) {
            ensureManager()
        } else {
            managerJob?.cancel()
            managerJob = null

            sendCommand(
                0x0a,
                byteArrayOf(0)
            )

            synchronized(lock) {
                connectingFactoryId = null
            }

            connectWaiter?.cancel()
            connectWaiter = null
        }
    }

    fun onObjectAvailable(payload: ByteArray) {
        if (payload.size < 9) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val factoryId = b.int.toLong() and 0xffffffffL
        val objectType = b.int
        val rssi = b.get().toInt()

        if (objectType !in 1..3) return

        lastSeenNanos[factoryId] = System.nanoTime()

        synchronized(lock) {
            val previous = cubesByFactory[factoryId]

            cubesByFactory[factoryId] =
                (previous ?: CubeInfo(factoryId = factoryId)).copy(
                    objectType = objectType,
                    rssi = rssi
                )
        }

        publish()
    }

    fun onObjectConnectionState(payload: ByteArray) {
        if (payload.size < 13) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val objectId = b.int.toLong() and 0xffffffffL
        val factoryId = b.int.toLong() and 0xffffffffL
        val objectType = b.int
        val connected = b.get().toInt() != 0

        if (connectingFactoryId == factoryId) {
            connectWaiter?.complete(connected)
        }

        if (connected) {
            disconnectJobs.remove(factoryId)?.cancel()

            synchronized(lock) {
                val previous =
                    cubesByFactory[factoryId] ?: CubeInfo(factoryId = factoryId)

                cubesByFactory[factoryId] = previous.copy(
                    objectId = objectId,
                    objectType = objectType,
                    connected = true,
                    lastEvent = "Connecté"
                )
            }

            if (factoryId in accelWantedFactories) {
                streamAccelByFactory(factoryId, true)
            }

            publish()
            return
        }

        val wasConnected = synchronized(lock) {
            cubesByFactory[factoryId]?.connected == true
        }

        if (!wasConnected) {
            synchronized(lock) {
                val previous =
                    cubesByFactory[factoryId] ?: CubeInfo(factoryId = factoryId)

                cubesByFactory[factoryId] = previous.copy(
                    objectType = objectType,
                    connected = false,
                    lastEvent = "Connexion refusée / inactive"
                )
            }
            publish()
            return
        }

        // Le body peut publier de très brèves transitions false pendant le
        // scan ou un changement de liaison BLE. Ne pas faire clignoter l'UI
        // ni lancer immédiatement une tempête de reconnexions.
        disconnectJobs.remove(factoryId)?.cancel()
        disconnectJobs[factoryId] = scope.launch {
            delay(DISCONNECT_DEBOUNCE_MS)

            synchronized(lock) {
                val current = cubesByFactory[factoryId] ?: return@synchronized
                if (!current.connected) return@synchronized

                cubesByFactory[factoryId] = current.copy(
                    connected = false,
                    disconnectCount = current.disconnectCount + 1,
                    lastEvent = "Déconnecté"
                )
            }

            publish()
        }
    }

    fun onObjectPowerLevel(payload: ByteArray) {
        if (payload.size < 9) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val objectId = b.int.toLong() and 0xffffffffL
        val missedPackets = b.int.toLong() and 0xffffffffL
        val batteryLevel = b.get().toInt() and 0xff

        updateByObjectId(objectId) {
            it.copy(
                batteryLevel = batteryLevel,
                missedPackets = missedPackets
            )
        }
    }

    fun onObjectAccel(payload: ByteArray) {
        if (payload.size < 20) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        val objectId = b.int.toLong() and 0xffffffffL
        val x = b.float
        val y = b.float
        val z = b.float

        val now = System.nanoTime()
        val last = lastAccelPublishNanos[objectId] ?: 0L
        val publishNow = now - last >= ACCEL_UI_PERIOD_NS

        updateByObjectId(
            objectId = objectId,
            publishChanges = publishNow
        ) {
            it.copy(
                accelX = x,
                accelY = y,
                accelZ = z
            )
        }

        if (publishNow) {
            lastAccelPublishNanos[objectId] = now
        }
    }

    fun onObjectMoved(payload: ByteArray) {
        if (payload.size < 21) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        val objectId = b.int.toLong() and 0xffffffffL
        val x = b.float
        val y = b.float
        val z = b.float
        val axis = b.get().toInt() and 0xff

        updateByObjectId(objectId) {
            it.copy(
                accelX = x,
                accelY = y,
                accelZ = z,
                upAxis = CubeUpAxis.fromRaw(axis),
                moving = true,
                lastEvent = "Mouvement"
            )
        }
    }

    fun onObjectStoppedMoving(payload: ByteArray) {
        if (payload.size < 8) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        val objectId = b.int.toLong() and 0xffffffffL

        updateByObjectId(objectId) {
            it.copy(
                moving = false,
                lastEvent = "Arrêt"
            )
        }
    }

    fun onObjectTapped(payload: ByteArray) {
        if (payload.size < 12) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        val objectId = b.int.toLong() and 0xffffffffL
        val numTaps = b.get().toInt() and 0xff
        val tapTime = b.get().toInt() and 0xff
        val tapNeg = b.get().toInt()
        val tapPos = b.get().toInt()

        updateByObjectId(objectId) {
            it.copy(
                tapCount = it.tapCount + numTaps.coerceAtLeast(1),
                tapIntensity = maxOf(kotlin.math.abs(tapNeg), kotlin.math.abs(tapPos)),
                lastEvent = "Tap x" + numTaps + " (" + tapTime + ")"
            )
        }
    }

    fun onObjectTapFiltered(payload: ByteArray) {
        if (payload.size < 10) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        val objectId = b.int.toLong() and 0xffffffffL
        b.get()
        val intensity = b.get().toInt() and 0xff

        updateByObjectId(objectId) {
            it.copy(
                tapIntensity = intensity,
                lastEvent = "Tap filtré (" + intensity + ")"
            )
        }
    }

    fun onObjectUpAxisChanged(payload: ByteArray) {
        if (payload.size < 9) return

        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        val objectId = b.int.toLong() and 0xffffffffL
        val axis = b.get().toInt() and 0xff
        val parsedAxis = CubeUpAxis.fromRaw(axis)

        updateByObjectId(objectId) {
            it.copy(
                upAxis = parsedAxis,
                lastEvent = "Face haute " + parsedAxis.label
            )
        }
    }

    fun setAccelStreaming(objectId: Long, enabled: Boolean) {
        val factoryId = synchronized(lock) {
            cubesByFactory.entries
                .firstOrNull { it.value.objectId == objectId }
                ?.key
        } ?: return

        synchronized(lock) {
            if (enabled) {
                accelWantedFactories += factoryId
            } else {
                accelWantedFactories -= factoryId
            }
        }

        streamAccelByFactory(factoryId, enabled)
    }

    fun setAllAccelStreaming(enabled: Boolean) {
        val connected = synchronized(lock) {
            cubesByFactory.values
                .filter { it.connected && it.objectId != null }
                .mapNotNull { it.objectId }
        }

        connected.forEach { objectId ->
            setAccelStreaming(objectId, enabled)
        }
    }

    fun setSolidColor(objectId: Long, color: BackpackColor) {
        setLights(
            objectId = objectId,
            colors = List(4) { color }
        )
    }

    fun setPairPattern(
        objectId: Long,
        first: BackpackColor,
        second: BackpackColor
    ) {
        setLights(
            objectId = objectId,
            colors = listOf(first, second, first, second)
        )
    }

    fun setCornerColor(
        objectId: Long,
        corner: Int,
        color: BackpackColor
    ) {
        if (corner !in 0..3) return

        val current = synchronized(lock) {
            cubesByFactory.values
                .firstOrNull { it.objectId == objectId }
                ?.ledColors
                ?.toMutableList()
        } ?: MutableList(4) { BackpackColor.OFF }

        current[corner] = color

        setLights(
            objectId = objectId,
            colors = current
        )
    }

    fun startChaser(
        objectId: Long,
        color: BackpackColor,
        rotationPeriodFrames: Int = 18
    ) {
        val selectPayload =
            cubeIdPayload(
                objectId = objectId,
                rotationPeriodFrames =
                    rotationPeriodFrames.coerceIn(1, 255)
            )

        val states = listOf(
            animatedLightState(color),
            solidLightState(BackpackColor.OFF),
            solidLightState(BackpackColor.OFF),
            solidLightState(BackpackColor.OFF)
        )

        sendCubeTransaction(
            objectId = objectId,
            selectPayload = selectPayload,
            states = states,
            logicalColors = listOf(
                color,
                BackpackColor.OFF,
                BackpackColor.OFF,
                BackpackColor.OFF
            )
        )
    }

    fun stopChaser(objectId: Long) {
        setSolidColor(objectId, BackpackColor.OFF)
    }

    fun setAllSolidColor(color: BackpackColor) {
        connectedObjectIds()
            .forEach { setSolidColor(it, color) }
    }

    fun setAllPairPattern(
        first: BackpackColor,
        second: BackpackColor
    ) {
        connectedObjectIds()
            .forEach { setPairPattern(it, first, second) }
    }

    private fun setLights(
        objectId: Long,
        colors: List<BackpackColor>
    ) {
        if (colors.size != 4) return

        val selectPayload =
            cubeIdPayload(
                objectId = objectId,
                rotationPeriodFrames = 0
            )

        val states =
            colors.map(::solidLightState)

        sendCubeTransaction(
            objectId = objectId,
            selectPayload = selectPayload,
            states = states,
            logicalColors = colors
        )
    }

    private fun sendCubeTransaction(
        objectId: Long,
        selectPayload: ByteArray,
        states: List<ByteArray>,
        logicalColors: List<BackpackColor>
    ) {
        if (states.size != 4) return

        val lights = ByteBuffer.allocate(40)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                states.forEach { put(it) }
            }
            .array()

        sendSequential(
            listOf(
                OutboundCommand(0x10, selectPayload),
                OutboundCommand(0x04, lights)
            ),
            true
        )

        updateByObjectId(objectId) {
            it.copy(
                ledColors = logicalColors,
                lastEvent = "LEDs mises à jour"
            )
        }
    }

    private fun streamAccelByFactory(
        factoryId: Long,
        enabled: Boolean
    ) {
        val objectId = synchronized(lock) {
            cubesByFactory[factoryId]?.objectId
        } ?: return

        val payload = ByteBuffer.allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(objectId.toInt())
            .put((if (enabled) 1 else 0).toByte())
            .array()

        sendCommand(0x08, payload)

        updateByObjectId(objectId) {
            it.copy(
                accelStreaming = enabled,
                lastEvent =
                    if (enabled) "Accéléromètre ON" else "Accéléromètre OFF"
            )
        }
    }

    private fun ensureManager() {
        if (!discoveryEnabled) return
        if (managerJob?.isActive == true) return

        managerJob = scope.launch {
            while (discoveryEnabled) {
                val connectedCount = synchronized(lock) {
                    cubesByFactory.values.count { it.connected }
                }

                if (connectedCount >= 3) {
                    // Une fois les trois cubes établis, couper le scan BLE.
                    // Cela évite les micro-coupures observées quand scan et
                    // liaisons actives tournent simultanément.
                    sendCommand(0x0a, byteArrayOf(0))
                    delay(STABLE_POLL_MS)
                    continue
                }

                // Phase 1 : scan court, sans tenter de connexion en parallèle.
                sendCommand(0x0a, byteArrayOf(1))
                delay(SCAN_WINDOW_MS)
                sendCommand(0x0a, byteArrayOf(0))
                delay(AFTER_SCAN_SETTLE_MS)

                // Phase 2 : connecter les objets vus, strictement un par un.
                while (discoveryEnabled) {
                    val candidate = chooseConnectCandidate() ?: break
                    val waiter = CompletableDeferred<Boolean>()
                    connectWaiter = waiter

                    synchronized(lock) {
                        connectingFactoryId = candidate.factoryId
                        cubesByFactory[candidate.factoryId] =
                            candidate.copy(
                                connectAttempts = candidate.connectAttempts + 1,
                                lastEvent = "Connexion BLE…"
                            )
                    }
                    publish()

                    val payload = ByteBuffer.allocate(5)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(candidate.factoryId.toInt())
                        .put(1.toByte())
                        .array()

                    sendCommand(0x05, payload)

                    val result =
                        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                            waiter.await()
                        }

                    synchronized(lock) {
                        if (connectingFactoryId == candidate.factoryId) {
                            connectingFactoryId = null
                        }

                        if (result != true) {
                            val current = cubesByFactory[candidate.factoryId]
                            if (current != null && !current.connected) {
                                cubesByFactory[candidate.factoryId] =
                                    current.copy(
                                        lastEvent =
                                            if (result == false) {
                                                "Connexion refusée"
                                            } else {
                                                "Timeout connexion"
                                            }
                                    )
                            }
                        }
                    }

                    connectWaiter = null
                    publish()
                    delay(BETWEEN_CONNECTS_MS)
                }

                delay(RESCAN_DELAY_MS)
            }
        }
    }

    private fun chooseConnectCandidate(): CubeInfo? {
        val now = System.nanoTime()

        return synchronized(lock) {
            cubesByFactory.values
                .filter {
                    !it.connected &&
                        it.objectType in 1..3 &&
                        now - (lastSeenNanos[it.factoryId] ?: 0L) <
                            AVAILABLE_MAX_AGE_NS
                }
                .sortedWith(
                    compareBy<CubeInfo> { it.connectAttempts }
                        .thenBy { it.objectType }
                )
                .firstOrNull()
        }
    }

    private fun connectedObjectIds(): List<Long> =
        synchronized(lock) {
            cubesByFactory.values
                .filter { it.connected }
                .mapNotNull { it.objectId }
        }

    private fun updateByObjectId(
        objectId: Long,
        publishChanges: Boolean = true,
        transform: (CubeInfo) -> CubeInfo
    ) {
        var changed = false

        synchronized(lock) {
            val entry =
                cubesByFactory.entries
                    .firstOrNull { it.value.objectId == objectId }

            if (entry != null) {
                cubesByFactory[entry.key] =
                    transform(entry.value)
                changed = true
            }
        }

        if (changed && publishChanges) publish()
    }

    private fun cubeIdPayload(
        objectId: Long,
        rotationPeriodFrames: Int
    ): ByteArray =
        ByteBuffer.allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(objectId.toInt())
            .put(rotationPeriodFrames.toByte())
            .array()

    private fun solidLightState(
        color: BackpackColor
    ): ByteArray {
        val encoded = cubeColorValue(color)

        return ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(encoded.toShort())
            .putShort(encoded.toShort())
            .put(0)
            .put(0)
            .put(0)
            .put(0)
            .putShort(0)
            .array()
    }

    private fun animatedLightState(
        color: BackpackColor
    ): ByteArray {
        val encoded = cubeColorValue(color)

        return ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(encoded.toShort())
            .putShort(0)
            .put(5)
            .put(20)
            .put(5)
            .put(10)
            .putShort(0)
            .array()
    }

    private fun cubeColorValue(color: BackpackColor): Int =
        when (color) {
            BackpackColor.WHITE -> {
                val r = 8
                val g = 8
                val b = 16
                (r shl 10) or (g shl 5) or b
            }

            else -> color.encoded
        }

    private fun publish() {
        val snapshot = synchronized(lock) {
            cubesByFactory.values
                .sortedWith(
                    compareBy<CubeInfo> {
                        if (it.objectType in 1..3) it.objectType else 99
                    }.thenBy { it.factoryId }
                )
                .toList()
        }

        onChanged(snapshot)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_500L
        private const val BETWEEN_CONNECTS_MS = 220L
        private const val AVAILABLE_MAX_AGE_NS = 8_000_000_000L

        private const val SCAN_WINDOW_MS = 1_500L
        private const val AFTER_SCAN_SETTLE_MS = 180L
        private const val RESCAN_DELAY_MS = 800L
        private const val STABLE_POLL_MS = 1_000L
        private const val DISCONNECT_DEBOUNCE_MS = 600L

        // 10 Hz suffit largement pour l'affichage et évite de recomposer
        // toute l'UI à chaque paquet accéléromètre (~33 Hz par cube).
        private const val ACCEL_UI_PERIOD_NS = 100_000_000L
    }
}
