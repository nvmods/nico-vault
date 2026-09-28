package fr.nvmods.cozmo.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    val propSlot: Int? = null,
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
    private val sendBatch: (commands: List<OutboundCommand>) -> Unit,
    private val onChanged: (List<CubeInfo>) -> Unit
) {
    private val lock = Any()
    private val cubesByFactory = linkedMapOf<Long, CubeInfo>()
    // Routage strict des événements ENGINE : un object_id ne peut avoir
    // qu'un seul propriétaire physique (factory_id) à un instant donné.
    private val activeFactoryByObjectId = mutableMapOf<Long, Long>()
    private val lastSeenNanos = ConcurrentHashMap<Long, Long>()
    private val nextConnectAllowedNanos = ConcurrentHashMap<Long, Long>()
    private val lastAdvertPublishNanos = ConcurrentHashMap<Long, Long>()
    private val lastAccelPublishNanos = ConcurrentHashMap<Long, Long>()
    private val accelWantedFactories = mutableSetOf<Long>()
    private var lastPublishedSnapshot: List<CubeInfo> = emptyList()

    private var discoveryEnabled = false
    private var managerJob: Job? = null
    private var connectingFactoryId: Long? = null
    private var connectWaiter: CompletableDeferred<Boolean>? = null

    fun reset() {
        discoveryEnabled = false
        managerJob?.cancel()
        managerJob = null
        connectWaiter?.cancel()
        connectWaiter = null

        synchronized(lock) {
            cubesByFactory.clear()
            activeFactoryByObjectId.clear()
            accelWantedFactories.clear()
            connectingFactoryId = null
        }

        lastSeenNanos.clear()
        nextConnectAllowedNanos.clear()
        lastAdvertPublishNanos.clear()
        lastAccelPublishNanos.clear()
        publish()
    }

    fun setDiscovery(enabled: Boolean) {
        val wasEnabled = discoveryEnabled
        discoveryEnabled = enabled

        if (enabled) {
            if (!wasEnabled) {
                // L'engine officiel maintient une table de 5 prop slots.
                // Nettoyer les cinq entrées une seule fois au démarrage évite
                // de conserver l'état invalide des anciennes versions où tous
                // les cubes étaient écrits dans le slot 1.
                clearAllPropSlots()
            }
            ensureManager()
        } else {
            managerJob?.cancel()
            managerJob = null

            // Ne pas piloter SetAccessoryDiscovery (0x0a) ici.
            // Les implémentations de référence reçoivent ObjectAvailable
            // spontanément et ne basculent pas la radio BLE entre les connexions.
            synchronized(lock) {
                connectingFactoryId = null
            }

            connectWaiter?.cancel()
            connectWaiter = null
        }
    }

    fun onObjectAvailable(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectAvailable(payload) ?: return
        if (event.objectType !in 1..3) return

        val now = System.nanoTime()
        lastSeenNanos[event.factoryId] = now

        var publishNow = false

        synchronized(lock) {
            val previous = cubesByFactory[event.factoryId]
            val next =
                (previous ?: CubeInfo(factoryId = event.factoryId)).copy(
                    propSlot = slotForObjectType(event.objectType),
                    objectType = event.objectType,
                    rssi = event.rssi
                )

            cubesByFactory[event.factoryId] = next

            val lastUi = lastAdvertPublishNanos[event.factoryId] ?: 0L
            publishNow =
                previous == null ||
                    previous.objectType != event.objectType ||
                    (
                        previous.rssi != event.rssi &&
                            now - lastUi >= RSSI_UI_PERIOD_NS
                    )
        }

        if (publishNow) {
            lastAdvertPublishNanos[event.factoryId] = now
            publish()
        }
    }
    fun onObjectConnectionState(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectConnectionState(payload) ?: return
        val objectId = event.objectId
        val factoryId = event.factoryId
        val objectType = event.objectType
        val connected = event.connected

        if (connected) {
            if (connectingFactoryId == factoryId) {
                connectWaiter?.complete(true)
            }
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
                nextConnectAllowedNanos.remove(factoryId)

                val expectedSlot =
                    previous.propSlot ?: slotForObjectType(objectType)

                cubesByFactory[factoryId] = previous.copy(
                    objectId = objectId,
                    propSlot = expectedSlot,
                    objectType = objectType,
                    connected = true,
                    lastEvent =
                        if (objectId.toInt() == expectedSlot) {
                            "Connecté — slot $expectedSlot"
                        } else {
                            "Connecté — slot reçu $objectId / attendu $expectedSlot"
                        }
                )
            }

            if (factoryId in accelWantedFactories) {
                streamAccelByFactory(factoryId, true)
            }

            publish()
            return
        }

        var changed = false
        var staleDisconnect = false

        synchronized(lock) {
            val current = cubesByFactory[factoryId]

            // Les EVENT sont OOB : un ancien "false" peut arriver après une
            // reconnexion ayant reçu un nouvel object_id. Ne jamais laisser
            // cet événement périmé casser la liaison courante.
            if (
                current?.connected == true &&
                current.objectId != null &&
                current.objectId != objectId
            ) {
                staleDisconnect = true
                return@synchronized
            }

            if (activeFactoryByObjectId[objectId] == factoryId) {
                activeFactoryByObjectId.remove(objectId)
            }

            val previous =
                current ?: CubeInfo(factoryId = factoryId)

            val next = previous.copy(
                objectType = objectType,
                connected = false,
                disconnectCount =
                    previous.disconnectCount +
                        if (previous.connected) 1 else 0,
                lastEvent =
                    if (previous.connected) {
                        "Déconnecté"
                    } else {
                        "Connexion refusée / inactive"
                    }
            )

            if (next != previous) {
                cubesByFactory[factoryId] = next
                changed = true
            }
        }

        if (staleDisconnect) return

        nextConnectAllowedNanos[factoryId] =
            System.nanoTime() + RECONNECT_BACKOFF_NS

        if (connectingFactoryId == factoryId) {
            connectWaiter?.complete(false)
        }

        if (changed) publish()
    }

    fun onObjectPowerLevel(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectPowerLevel(payload) ?: return

        updateByObjectId(event.objectId) {
            it.copy(
                batteryLevel = event.batteryLevel,
                missedPackets = event.missedPackets
            )
        }
    }
    fun onObjectAccel(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectAccel(payload) ?: return

        val now = System.nanoTime()
        val last = lastAccelPublishNanos[event.objectId] ?: 0L
        val publishNow = now - last >= ACCEL_UI_PERIOD_NS

        updateByObjectId(
            objectId = event.objectId,
            publishChanges = publishNow
        ) {
            it.copy(
                accelX = event.x,
                accelY = event.y,
                accelZ = event.z
            )
        }

        if (publishNow) {
            lastAccelPublishNanos[event.objectId] = now
        }
    }
    fun onObjectMoved(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectMoved(payload) ?: return

        updateByObjectId(event.objectId) {
            it.copy(
                accelX = event.x,
                accelY = event.y,
                accelZ = event.z,
                upAxis = CubeUpAxis.fromRaw(event.upAxis),
                moving = true,
                lastEvent = "Mouvement"
            )
        }
    }
    fun onObjectStoppedMoving(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectStopped(payload) ?: return

        updateByObjectId(event.objectId) {
            it.copy(
                moving = false,
                lastEvent = "Arrêt"
            )
        }
    }
    fun onObjectTapped(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectTapped(payload) ?: return

        updateByObjectId(event.objectId) {
            it.copy(
                tapCount = it.tapCount + event.numTaps.coerceAtLeast(1),
                tapIntensity = maxOf(
                    kotlin.math.abs(event.tapNeg),
                    kotlin.math.abs(event.tapPos)
                ),
                lastEvent = "Tap x" + event.numTaps + " (" + event.tapTime + ")"
            )
        }
    }
    fun onObjectTapFiltered(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectTapFiltered(payload) ?: return

        updateByObjectId(event.objectId) {
            it.copy(
                tapIntensity = event.intensity,
                lastEvent = "Tap filtré (" + event.intensity + ")"
            )
        }
    }
    fun onObjectUpAxisChanged(payload: ByteArray) {
        val event = CubeWireProtocol.decodeObjectUpAxisChanged(payload) ?: return
        val parsedAxis = CubeUpAxis.fromRaw(event.axis)

        updateByObjectId(event.objectId) {
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
            CubeWireProtocol.cubeId(
                objectId = objectId,
                rotationPeriodFrames = rotationPeriodFrames
            )

        val states = listOf(
            CubeWireProtocol.animatedLightState(color),
            CubeWireProtocol.solidLightState(BackpackColor.OFF),
            CubeWireProtocol.solidLightState(BackpackColor.OFF),
            CubeWireProtocol.solidLightState(BackpackColor.OFF)
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
            CubeWireProtocol.cubeId(objectId)

        val states =
            colors.map(CubeWireProtocol::solidLightState)

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

        val lights = CubeWireProtocol.cubeLights(states)

        // PyCozmo place CubeId puis CubeLights dans la même file d'émission.
        // Le collecteur transport les encode donc ensemble quand ils tiennent
        // dans la même trame ENGINE. Ne pas insérer de barrière ACK entre eux.
        sendBatch(
            listOf(
                OutboundCommand(CubeWireProtocol.CMD_CUBE_ID, selectPayload),
                OutboundCommand(CubeWireProtocol.CMD_CUBE_LIGHTS, lights)
            )
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

        val payload =
            CubeWireProtocol.streamObjectAccel(objectId, enabled)

        sendCommand(CubeWireProtocol.CMD_STREAM_OBJECT_ACCEL, payload)

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
                // Le body annonce périodiquement les accessoires par
                // ObjectAvailable. On ne force plus aucun cycle de scan radio.
                // On se contente de connecter, un par un, les cubes fraîchement
                // annoncés et non déjà liés.
                val candidate = chooseConnectCandidate()

                if (candidate != null) {
                    attemptConnect(candidate)
                    delay(BETWEEN_CONNECTS_MS)
                } else {
                    delay(IDLE_CONNECT_POLL_MS)
                }
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

        val slot =
            candidate.propSlot ?: slotForObjectType(candidate.objectType)

        if (slot !in 0..4) {
            synchronized(lock) {
                connectingFactoryId = null
            }
            connectWaiter = null
            return
        }

        val payload =
            CubeWireProtocol.setPropSlot(
                factoryId = candidate.factoryId,
                slot = slot
            )

        sendCommand(CubeWireProtocol.CMD_SET_PROP_SLOT, payload)

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
                    nextConnectAllowedNanos[candidate.factoryId] =
                        System.nanoTime() + RECONNECT_BACKOFF_NS
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

    private fun clearAllPropSlots() {
        for (slot in 0..4) {
            sendCommand(
                CubeWireProtocol.CMD_SET_PROP_SLOT,
                CubeWireProtocol.clearPropSlot(slot)
            )
        }
    }

    private fun slotForObjectType(objectType: Int): Int =
        when (objectType) {
            1 -> 0
            2 -> 1
            3 -> 2
            else -> -1
        }

    private fun chooseConnectCandidate(): CubeInfo? {
        val now = System.nanoTime()

        return synchronized(lock) {
            cubesByFactory.values
                .filter {
                    !it.connected &&
                        it.objectType in 1..3 &&
                        (it.propSlot ?: slotForObjectType(it.objectType)) in 0..4 &&
                        now >= (nextConnectAllowedNanos[it.factoryId] ?: 0L) &&
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

    private fun publish() {
        val snapshot = synchronized(lock) {
            val next =
                cubesByFactory.values
                    .sortedWith(
                        compareBy<CubeInfo> {
                            it.propSlot ?: 99
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
        private const val RECONNECT_BACKOFF_NS = 3_000_000_000L

        // Le manager ne pilote plus la découverte radio : il attend les
        // ObjectAvailable spontanés du body et ne gère que les connexions.
        private const val IDLE_CONNECT_POLL_MS = 250L
        // Le RSSI sert au diagnostic, pas au pilotage : 2 Hz suffit et évite
        // de republier toute la liste à chaque publicité BLE reçue.
        private const val RSSI_UI_PERIOD_NS = 500_000_000L

        // 10 Hz suffit largement pour l'affichage et évite de recomposer
        // toute l'UI à chaque paquet accéléromètre (~33 Hz par cube).
        private const val ACCEL_UI_PERIOD_NS = 100_000_000L
    }
}
