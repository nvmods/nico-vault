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
    // Routage strict des événements ENGINE : un object_id ne peut avoir
    // qu'un seul propriétaire physique (factory_id) à un instant donné.
    private val activeFactoryByObjectId = mutableMapOf<Long, Long>()
    private val lastSeenNanos = ConcurrentHashMap<Long, Long>()
    private val lastAdvertPublishNanos = ConcurrentHashMap<Long, Long>()
    private val lastAccelPublishNanos = ConcurrentHashMap<Long, Long>()
    private val accelWantedFactories = mutableSetOf<Long>()
    private var lastPublishedSnapshot: List<CubeInfo> = emptyList()
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
            activeFactoryByObjectId.clear()
            accelWantedFactories.clear()
            connectingFactoryId = null
        }

        lastSeenNanos.clear()
        lastAdvertPublishNanos.clear()
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

        val now = System.nanoTime()
        lastSeenNanos[factoryId] = now

        var publishNow = false

        synchronized(lock) {
            val previous = cubesByFactory[factoryId]
            val next =
                (previous ?: CubeInfo(factoryId = factoryId)).copy(
                    objectType = objectType,
                    rssi = rssi
                )

            cubesByFactory[factoryId] = next

            val lastUi = lastAdvertPublishNanos[factoryId] ?: 0L
            publishNow =
                previous == null ||
                    previous.objectType != objectType ||
                    (
                        previous.rssi != rssi &&
                            now - lastUi >= RSSI_UI_PERIOD_NS
                    )
        }

        if (publishNow) {
            lastAdvertPublishNanos[factoryId] = now
            publish()
        }
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

            var displacedFactoryId: Long? = null

            synchronized(lock) {
                val previous =
                    cubesByFactory[factoryId] ?: CubeInfo(factoryId = factoryId)

                // Si Cozmo réattribue un object_id après une micro-coupure,
                // retirer immédiatement l'ancien propriétaire. Sans cela deux
                // cartes peuvent pointer vers le même cube physique.
                val oldOwner = activeFactoryByObjectId[objectId]
                if (oldOwner != null && oldOwner != factoryId) {
                    displacedFactoryId = oldOwner
                    val displaced = cubesByFactory[oldOwner]
                    if (displaced != null) {
                        cubesByFactory[oldOwner] = displaced.copy(
                            objectId = null,
                            connected = false,
                            lastEvent = "object_id réattribué — reconnexion"
                        )
                    }
                }

                // Le même factory_id peut aussi recevoir un nouvel object_id.
                val previousObjectId = previous.objectId
                if (
                    previousObjectId != null &&
                    previousObjectId != objectId &&
                    activeFactoryByObjectId[previousObjectId] == factoryId
                ) {
                    activeFactoryByObjectId.remove(previousObjectId)
                }

                activeFactoryByObjectId[objectId] = factoryId
                lastSeenNanos[factoryId] = System.nanoTime()

                cubesByFactory[factoryId] = previous.copy(
                    objectId = objectId,
                    objectType = objectType,
                    connected = true,
                    lastEvent = "Connecté"
                )
            }

            displacedFactoryId?.let {
                disconnectJobs.remove(it)?.cancel()
            }

            if (factoryId in accelWantedFactories) {
                streamAccelByFactory(factoryId, true)
            }

            publish()
            return
        }

        val wasConnected = synchronized(lock) {
            if (activeFactoryByObjectId[objectId] == factoryId) {
                activeFactoryByObjectId.remove(objectId)
            }

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

    fun setAccelStreaming(factoryId: Long, enabled: Boolean) {
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
        connectedFactoryIds()
            .forEach { factoryId ->
                setAccelStreaming(factoryId, enabled)
            }
    }

    fun setSolidColor(factoryId: Long, color: BackpackColor) {
        setLights(
            factoryId = factoryId,
            colors = List(4) { color }
        )
    }

    fun setPairPattern(
        factoryId: Long,
        first: BackpackColor,
        second: BackpackColor
    ) {
        setLights(
            factoryId = factoryId,
            colors = listOf(first, second, first, second)
        )
    }

    fun setCornerColor(
        factoryId: Long,
        corner: Int,
        color: BackpackColor
    ) {
        if (corner !in 0..3) return

        val current = synchronized(lock) {
            cubesByFactory[factoryId]
                ?.ledColors
                ?.toMutableList()
        } ?: MutableList(4) { BackpackColor.OFF }

        current[corner] = color

        setLights(
            factoryId = factoryId,
            colors = current
        )
    }

    fun startChaser(
        factoryId: Long,
        color: BackpackColor,
        rotationPeriodFrames: Int = 18
    ) {
        val objectId = activeObjectIdForFactory(factoryId) ?: return

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
            factoryId = factoryId,
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

    fun stopChaser(factoryId: Long) {
        setSolidColor(factoryId, BackpackColor.OFF)
    }

    fun setAllSolidColor(color: BackpackColor) {
        connectedFactoryIds()
            .forEach { setSolidColor(it, color) }
    }

    fun setAllPairPattern(
        first: BackpackColor,
        second: BackpackColor
    ) {
        connectedFactoryIds()
            .forEach { setPairPattern(it, first, second) }
    }

    private fun setLights(
        factoryId: Long,
        colors: List<BackpackColor>
    ) {
        if (colors.size != 4) return

        val objectId = activeObjectIdForFactory(factoryId) ?: return

        val selectPayload =
            cubeIdPayload(
                objectId = objectId,
                rotationPeriodFrames = 0
            )

        val states =
            colors.map(::solidLightState)

        sendCubeTransaction(
            factoryId = factoryId,
            objectId = objectId,
            selectPayload = selectPayload,
            states = states,
            logicalColors = colors
        )
    }

    private fun sendCubeTransaction(
        factoryId: Long,
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

        updateByFactoryId(factoryId) {
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
        val objectId = activeObjectIdForFactory(factoryId) ?: return

        val payload = ByteBuffer.allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(objectId.toInt())
            .put((if (enabled) 1 else 0).toByte())
            .array()

        sendCommand(0x08, payload)

        updateByFactoryId(factoryId) {
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
                val connectedCount = connectedFactoryIds().size

                if (connectedCount >= 3) {
                    // Une fois les trois cubes établis, couper le scan BLE.
                    sendCommand(0x0a, byteArrayOf(0))
                    delay(STABLE_POLL_MS)
                    continue
                }

                // Si un cube déjà connu vient de tomber, tenter d'abord une
                // reconnexion directe par factory_id. Cela évite de relancer
                // le scan BLE qui peut perturber les autres liens actifs.
                val reconnectCandidate = chooseConnectCandidate()
                if (reconnectCandidate != null) {
                    attemptConnect(reconnectCandidate)
                    delay(BETWEEN_CONNECTS_MS)
                    continue
                }

                // Aucun cube récemment connu à reconnecter : scan court.
                sendCommand(0x0a, byteArrayOf(1))
                delay(SCAN_WINDOW_MS)
                sendCommand(0x0a, byteArrayOf(0))
                delay(AFTER_SCAN_SETTLE_MS)

                // Puis connecter strictement un par un les objets annoncés.
                while (discoveryEnabled) {
                    val candidate = chooseConnectCandidate() ?: break
                    attemptConnect(candidate)
                    delay(BETWEEN_CONNECTS_MS)
                }

                delay(RESCAN_DELAY_MS)
            }
        }
    }

    private suspend fun attemptConnect(candidate: CubeInfo) {
        val waiter = CompletableDeferred<Boolean>()
        connectWaiter = waiter

        synchronized(lock) {
            connectingFactoryId = candidate.factoryId
            val current = cubesByFactory[candidate.factoryId] ?: candidate
            cubesByFactory[candidate.factoryId] =
                current.copy(
                    connectAttempts = current.connectAttempts + 1,
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

    private fun connectedFactoryIds(): List<Long> =
        synchronized(lock) {
            cubesByFactory.values
                .filter { cube ->
                    cube.connected &&
                        cube.objectId != null &&
                        activeFactoryByObjectId[cube.objectId] == cube.factoryId
                }
                .map { it.factoryId }
        }

    private fun activeObjectIdForFactory(factoryId: Long): Long? =
        synchronized(lock) {
            val cube = cubesByFactory[factoryId] ?: return@synchronized null
            val objectId = cube.objectId ?: return@synchronized null

            if (
                cube.connected &&
                activeFactoryByObjectId[objectId] == factoryId
            ) {
                objectId
            } else {
                null
            }
        }

    private fun updateByObjectId(
        objectId: Long,
        publishChanges: Boolean = true,
        transform: (CubeInfo) -> CubeInfo
    ) {
        val factoryId = synchronized(lock) {
            activeFactoryByObjectId[objectId]
        } ?: return

        // Toute télémétrie valide rafraîchit aussi la fraîcheur du cube.
        lastSeenNanos[factoryId] = System.nanoTime()

        updateByFactoryId(
            factoryId = factoryId,
            publishChanges = publishChanges,
            transform = transform
        )
    }

    private fun updateByFactoryId(
        factoryId: Long,
        publishChanges: Boolean = true,
        transform: (CubeInfo) -> CubeInfo
    ) {
        var changed = false

        synchronized(lock) {
            val previous = cubesByFactory[factoryId]

            if (previous != null) {
                val next = transform(previous)

                if (next != previous) {
                    cubesByFactory[factoryId] = next
                    changed = true
                }
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
            val next =
                cubesByFactory.values
                    .sortedWith(
                        compareBy<CubeInfo> {
                            if (it.objectType in 1..3) it.objectType else 99
                        }.thenBy { it.factoryId }
                    )
                    .toList()

            if (next == lastPublishedSnapshot) {
                null
            } else {
                lastPublishedSnapshot = next
                next
            }
        } ?: return

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
        // Les transitions false liées au scan BLE peuvent durer plus de 600 ms.
        // On garde donc l'état UI stable avant de déclarer une vraie coupure.
        private const val DISCONNECT_DEBOUNCE_MS = 2_000L

        // Le RSSI sert au diagnostic, pas au pilotage : 2 Hz suffit et évite
        // de republier toute la liste à chaque publicité BLE reçue.
        private const val RSSI_UI_PERIOD_NS = 500_000_000L

        // 10 Hz suffit largement pour l'affichage et évite de recomposer
        // toute l'UI à chaque paquet accéléromètre (~33 Hz par cube).
        private const val ACCEL_UI_PERIOD_NS = 100_000_000L
    }
}
