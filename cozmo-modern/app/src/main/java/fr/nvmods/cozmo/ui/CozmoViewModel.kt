package fr.nvmods.cozmo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.nvmods.cozmo.protocol.CozmoConnection
import fr.nvmods.cozmo.protocol.CozmoState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CozmoViewModel : ViewModel() {
    private val connection = CozmoConnection()
    val state: StateFlow<CozmoState> = connection.state

    fun connect() {
        viewModelScope.launch { connection.connect() }
    }

    fun disconnect() = connection.disconnect()
    fun forward() = connection.drive(CozmoConnection.DRIVE_SPEED, CozmoConnection.DRIVE_SPEED)
    fun backward() = connection.drive(-CozmoConnection.DRIVE_SPEED, -CozmoConnection.DRIVE_SPEED)
    fun left() = connection.drive(-CozmoConnection.TURN_SPEED, CozmoConnection.TURN_SPEED)
    fun right() = connection.drive(CozmoConnection.TURN_SPEED, -CozmoConnection.TURN_SPEED)
    fun stop() = connection.stopAllMotors()
    fun headUp() = connection.moveHead(CozmoConnection.HEAD_SPEED)
    fun headDown() = connection.moveHead(-CozmoConnection.HEAD_SPEED)
    fun liftUp() = connection.moveLift(CozmoConnection.LIFT_SPEED)
    fun liftDown() = connection.moveLift(-CozmoConnection.LIFT_SPEED)
    fun headLight(enabled: Boolean) = connection.setHeadLight(enabled)
    fun camera(enabled: Boolean) = connection.enableCamera(enabled)

    override fun onCleared() {
        connection.close()
        super.onCleared()
    }
}
