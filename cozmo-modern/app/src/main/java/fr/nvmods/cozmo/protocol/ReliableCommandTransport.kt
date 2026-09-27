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

/**
 * Transport fiable pour les commandes ENGINE Cozmo.
 *
 * Les points importants par rapport à la V0.5/V0.6 initiale :
 * - file unique d'émission ;
 * - fenêtre de séquences acquittées ;
 * - retransmission des trames complètes ;
 * - un batch logique est encodé dans UNE trame ENGINE quand il tient dedans.
 *
 * Ce dernier point est particulièrement important pour CubeId + CubeLights :
 * retransmettre séparément ces deux commandes peut faire viser le mauvais cube.
 */
internal class ReliableCommandTransport(
    private val scope: CoroutineScope,
    private val ackProvider: () -> Int,
    private val sendRaw: suspend (ByteArray) -> Unit,
    private val onRetryCount: (Long) -> Unit = {}
) {
    private data class PendingFrame(
        val firstSeq: Int,
        val lastSeq: Int,
        val packetCount: Int,
        val bytes: ByteArray,
        var lastSentNanos: Long,
        var attempts: Int
    )

    private val queue = Channel<List<OutboundCommand>>(Channel.UNLIMITED)
    private val pendingMutex = Mutex()
    private val pending = LinkedHashMap<Int, PendingFrame>()
    private val windowChanged = Channel<Unit>(Channel.CONFLATED)

    private var nextSeq = 0
    private var sendJob: Job? = null
    private var retryJob: Job? = null
    private var retries = 0L

    fun start() {
        if (sendJob != null) return

        sendJob = scope.launch {
            for (batch in queue) {
                val groups = splitForFrame(batch)

                for (commands in groups) {
                    waitForWindowSlots(commands.size)

                    val firstSeq = pendingMutex.withLock {
                        val value = nextSeq
                        nextSeq = (nextSeq + commands.size) % CozmoProtocol.MAX_SEQ
                        value
                    }

                    val lastSeq =
                        (firstSeq + commands.size - 1) % CozmoProtocol.MAX_SEQ

                    val frame = CozmoProtocol.commandFrame(
                        firstSeq = firstSeq,
                        ack = ackProvider(),
                        commands = commands.map { it.id to it.payload }
                    )

                    val now = System.nanoTime()

                    pendingMutex.withLock {
                        pending[lastSeq] = PendingFrame(
                            firstSeq = firstSeq,
                            lastSeq = lastSeq,
                            packetCount = commands.size,
                            bytes = frame,
                            lastSentNanos = now,
                            attempts = 1
                        )
                    }

                    sendRaw(frame)
                    delay(MIN_SEND_GAP_MS)
                }
            }
        }

        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_SCAN_MS)
                resendTimedOutFrames()
            }
        }
    }

    fun enqueue(command: OutboundCommand) {
        queue.trySend(listOf(command))
    }

    fun enqueueBatch(commands: List<OutboundCommand>) {
        if (commands.isNotEmpty()) {
            queue.trySend(commands)
        }
    }

    fun acknowledge(ack: Int) {
        if (ack == CozmoProtocol.OOB_SEQ) return

        scope.launch {
            var changed = false

            pendingMutex.withLock {
                // L'ACK Cozmo est cumulatif. Nos trames sont stockées dans
                // l'ordre d'émission et indexées par leur DERNIÈRE séquence.
                // Lorsqu'un ACK correspond à une fin de trame, tout ce qui
                // précède peut être libéré.
                if (pending.containsKey(ack)) {
                    val iterator = pending.entries.iterator()

                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        iterator.remove()
                        changed = true

                        if (entry.key == ack) break
                    }
                }
            }

            if (changed) {
                windowChanged.trySend(Unit)
            }
        }
    }

    fun stop() {
        sendJob?.cancel()
        retryJob?.cancel()
        sendJob = null
        retryJob = null
        queue.close()
        windowChanged.close()
        pending.clear()
    }

    private suspend fun waitForWindowSlots(required: Int) {
        while (true) {
            val canSend = pendingMutex.withLock {
                val pendingPackets =
                    pending.values.sumOf { it.packetCount }

                pendingPackets + required <= WINDOW_SIZE
            }

            if (canSend) return

            windowChanged.receive()
        }
    }

    private suspend fun resendTimedOutFrames() {
        val now = System.nanoTime()
        val retry = mutableListOf<PendingFrame>()

        pendingMutex.withLock {
            for (frame in pending.values) {
                val ageMs =
                    (now - frame.lastSentNanos) / 1_000_000L

                if (ageMs >= ACK_TIMEOUT_MS) {
                    frame.lastSentNanos = now
                    frame.attempts++
                    retry += frame

                    if (retry.size >= MAX_RETRY_BURST) break
                }
            }
        }

        if (retry.isEmpty()) return

        for (frame in retry) {
            sendRaw(frame.bytes)
            retries++
        }

        onRetryCount(retries)
    }

    private fun splitForFrame(
        commands: List<OutboundCommand>
    ): List<List<OutboundCommand>> {
        if (commands.isEmpty()) return emptyList()

        val result = mutableListOf<List<OutboundCommand>>()
        var current = mutableListOf<OutboundCommand>()
        var payloadSize = 0

        for (command in commands) {
            // Packet COMMAND = type(1) + len(2) + id(1) + payload.
            val commandSize = 4 + command.payload.size

            if (
                current.isNotEmpty() &&
                payloadSize + commandSize > MAX_ENGINE_PAYLOAD
            ) {
                result += current
                current = mutableListOf()
                payloadSize = 0
            }

            current += command
            payloadSize += commandSize
        }

        if (current.isNotEmpty()) {
            result += current
        }

        return result
    }

    companion object {
        // PyCozmo utilise 62 entrées. On garde un peu de marge.
        private const val WINDOW_SIZE = 48

        // 1051 octets de trame - 14 octets d'en-tête.
        private const val MAX_ENGINE_PAYLOAD = 1037

        // PyCozmo : 3 * 1/30 s ~= 100 ms.
        private const val ACK_TIMEOUT_MS = 100L
        private const val RETRY_SCAN_MS = 25L
        private const val MIN_SEND_GAP_MS = 2L
        private const val MAX_RETRY_BURST = 8
    }
}
