package fr.nvmods.cozmo.personality

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ActionStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    NOT_AVAILABLE
}

data class ActionResult(
    val status: ActionStatus,
    val message: String? = null
)

interface RobotActions {
    suspend fun execute(action: RobotAction): ActionResult
}

/**
 * Robot simulé utilisé tant que la couche transport/commandes continue
 * d'évoluer. Il permet de valider le cerveau sans envoyer de trames à Cozmo.
 */
class MockRobotActions : RobotActions {
    private val mutex = Mutex()
    private val history = mutableListOf<RobotAction>()

    override suspend fun execute(action: RobotAction): ActionResult {
        mutex.withLock {
            history += action
        }
        return ActionResult(ActionStatus.SUCCESS)
    }

    suspend fun history(): List<RobotAction> =
        mutex.withLock { history.toList() }

    suspend fun clear() {
        mutex.withLock {
            history.clear()
        }
    }
}
