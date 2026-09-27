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
 * Le protocole du robot utilise une fenêtre glissante avec acquittements.
 * La V0.5 envoyait chaque commande immédiatement sans retenir les trames non
 * acquittées. Cela fonctionnait à faible débit, mais un burst de LEDs pouvait
 * dépasser la fenêtre du robot et casser la session.
 *
 * Cette classe :
 * - sérialise toutes les commandes ;
 * - limite le nombre de trames non acquittées ;
 * - conserve les trames tant que le robot ne les a pas ACK ;
 * - retransmet après ~100 ms comme PyCozmo ;
 * - conserve l'ordre d'un batch (ex. sélection cube + LEDs).
 */
internal class ReliableCommandTransport(
    private val scope: CoroutineScope,
    private val ackProvider: () -> Int,
    private val sendRaw: suspend (ByteArray) -> Unit,
    private val onRetryCount: (Long) -> Unit = {}
) {
    private data class PendingFrame(
        val seq: Int,
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
                for (command in batch) {
                    waitForWindowSlot()

                    val seq = pendingMutex.withLock {
                        val value = nextSeq
                        nextSeq = (nextSeq + 1) % CozmoProtocol.MAX_SEQ
                        value
                    }

                    val frame = CozmoProtocol.commandFrame(
                        seq = seq,
                        ack = ackProvider(),
                        commandId = command.id,
                        payload = command.payload
                    )

                    val now = System.nanoTime()
                    pendingMutex.withLock {
                        pending[seq] = PendingFrame(
                            seq = seq,
                            bytes = frame,
                            lastSentNanos = now,
                            attempts = 1
                        )
                    }

                    sendRaw(frame)

                    // Une petite temporisation évite les bursts agressifs de
                    // commandes UI tout en restant très en dessous de 30 FPS.
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
                // LinkedHashMap garde l'ordre d'émission. Si l'ACK existe
                // dans la fenêtre, il acquitte cette trame et toutes celles
                // qui la précèdent, y compris lors du wrap 0xfffd -> 0.
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

    private suspend fun waitForWindowSlot() {
        while (true) {
            val canSend = pendingMutex.withLock {
                pending.size < WINDOW_SIZE
            }

            if (canSend) return

            // On attend un ACK sans faire tourner le CPU.
            windowChanged.receive()
        }
    }

    private suspend fun resendTimedOutFrames() {
        val now = System.nanoTime()
        val retry = mutableListOf<PendingFrame>()

        pendingMutex.withLock {
            for (frame in pending.values) {
                val ageMs = (now - frame.lastSentNanos) / 1_000_000L
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

    companion object {
        // PyCozmo utilise une fenêtre de 62. On garde un peu de marge pour
        // Android et pour les flux audio/caméra concurrents.
        private const val WINDOW_SIZE = 48

        // PyCozmo : 3 * 1/30 s = ~100 ms.
        private const val ACK_TIMEOUT_MS = 100L
        private const val RETRY_SCAN_MS = 25L
        private const val MIN_SEND_GAP_MS = 3L
        private const val MAX_RETRY_BURST = 8
    }
}
