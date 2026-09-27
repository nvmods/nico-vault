package fr.nvmods.cozmo.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class OutboundCommand(
    val id: Int,
    val payload: ByteArray = byteArrayOf()
)

private data class OutboundBatch(
    val commands: List<OutboundCommand>
)

/**
 * Transport ENGINE reconstruit sur le modèle PyCozmo.
 *
 * Différences volontaires par rapport au transport 0.7.x :
 * - fenêtre de 62 PAQUETS (pas une table de trames) ;
 * - ACK cumulatif inclusif par numéro de paquet ;
 * - collecte des commandes pendant ~1/90 s avant encodage ;
 * - retransmission de tous les paquets encore non acquittés après ~100 ms ;
 * - les commandes voisines (ex. CubeId + CubeLights) restent voisines et
 *   peuvent donc être encodées dans la même trame ENGINE.
 *
 * Le transport ne contient aucune logique spécifique aux cubes.
 */
internal class ReliableCommandTransport(
    private val scope: CoroutineScope,
    private val ackProvider: () -> Int,
    private val sendRaw: suspend (ByteArray) -> Unit,
    private val onRetryCount: (Long) -> Unit = {}
) {
    private val queue = Channel<OutboundBatch>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val window = SendSequenceWindow<OutboundCommand>(
        size = WINDOW_SIZE,
        maxSeq = CozmoProtocol.MAX_SEQ
    )
    private val carry = ArrayDeque<OutboundCommand>()

    private var senderJob: Job? = null
    private var lastAckTimeNanos = 0L
    private var retries = 0L

    fun start() {
        if (senderJob?.isActive == true) return

        senderJob = scope.launch {
            while (isActive) {
                delay(COLLECT_INTERVAL_MS)

                var retryCountChanged = false

                val packetsToSend = mutex.withLock {
                    drainQueueIntoCarry()

                    val newPackets = mutableListOf<Pair<Int, OutboundCommand>>()

                    while (carry.isNotEmpty() && !window.isFull()) {
                        val command = carry.removeFirst()
                        val seq = window.put(command)
                        newPackets += seq to command
                    }

                    val now = System.nanoTime()
                    val unacked = window.entries()

                    val resend =
                        if (
                            unacked.isNotEmpty() &&
                            lastAckTimeNanos != 0L &&
                            now - lastAckTimeNanos >= ACK_TIMEOUT_NS
                        ) {
                            lastAckTimeNanos = now
                            retries += unacked.size
                            retryCountChanged = true
                            unacked
                        } else {
                            emptyList()
                        }

                    resend + newPackets
                }

                if (packetsToSend.isNotEmpty()) {
                    sendSequencedPackets(packetsToSend)
                }

                if (retryCountChanged) {
                    onRetryCount(retries)
                }
            }
        }
    }

    fun enqueue(command: OutboundCommand) {
        queue.trySend(
            OutboundBatch(
                commands = listOf(command)
            )
        )
    }

    fun enqueueBatch(commands: List<OutboundCommand>) {
        if (commands.isNotEmpty()) {
            queue.trySend(
                OutboundBatch(
                    commands = commands.toList()
                )
            )
        }
    }

    /**
     * Conservé pour compatibilité avec les appels existants.
     * La reconstruction 0.8 utilise la même file que PyCozmo : pas de barrière
     * ACK artificielle entre deux commandes adjacentes.
     */
    fun enqueueSequential(
        commands: List<OutboundCommand>,
        waitUntilAcknowledged: Boolean = false
    ) {
        @Suppress("UNUSED_VARIABLE")
        val ignoredBarrier = waitUntilAcknowledged
        enqueueBatch(commands)
    }

    fun acknowledge(ack: Int) {
        if (ack == CozmoProtocol.OOB_SEQ) return

        scope.launch {
            mutex.withLock {
                window.acknowledge(ack)
                lastAckTimeNanos = System.nanoTime()
            }
        }
    }

    fun stop() {
        senderJob?.cancel()
        senderJob = null
        queue.close()

        scope.launch {
            mutex.withLock {
                carry.clear()
                window.reset()
                lastAckTimeNanos = 0L
            }
        }
    }

    private fun drainQueueIntoCarry() {
        while (true) {
            val batch = queue.tryReceive().getOrNull() ?: break
            carry.addAll(batch.commands)
        }
    }

    private suspend fun sendSequencedPackets(
        packets: List<Pair<Int, OutboundCommand>>
    ) {
        if (packets.isEmpty()) return

        val ack = ackProvider()
        var current = mutableListOf<Pair<Int, OutboundCommand>>()
        var currentPayloadSize = 0

        suspend fun flush() {
            if (current.isEmpty()) return

            val firstSeq = current.first().first
            val commands = current.map { it.second }

            sendRaw(
                CozmoProtocol.commandFrame(
                    firstSeq = firstSeq,
                    ack = ack,
                    commands = commands.map { it.id to it.payload }
                )
            )

            current = mutableListOf()
            currentPayloadSize = 0
        }

        for ((seq, command) in packets) {
            val commandSize = 4 + command.payload.size
            val previousSeq = current.lastOrNull()?.first
            val contiguous =
                previousSeq == null ||
                    seq == (previousSeq + 1) % CozmoProtocol.MAX_SEQ

            if (
                current.isNotEmpty() &&
                (
                    !contiguous ||
                    currentPayloadSize + commandSize > MAX_ENGINE_PAYLOAD
                )
            ) {
                flush()
            }

            current += seq to command
            currentPayloadSize += commandSize
        }

        flush()
    }

    companion object {
        // Valeurs de référence PyCozmo.
        private const val WINDOW_SIZE = 62
        private const val COLLECT_INTERVAL_MS = 12L
        private const val ACK_TIMEOUT_NS = 100_000_000L

        // 1051 octets de trame - 14 octets d'en-tête.
        private const val MAX_ENGINE_PAYLOAD = 1037
    }
}
