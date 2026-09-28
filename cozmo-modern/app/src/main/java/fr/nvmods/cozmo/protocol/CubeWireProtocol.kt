package fr.nvmods.cozmo.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Codec LightCube reconstruit à partir des structures CLAD présentes dans
 * libcozmoEngine.so 3.4.3, puis recoupé avec PyCozmo.
 *
 * Cette classe ne contient aucune logique de reconnexion ni d'UI : uniquement
 * les identifiants de messages et leur représentation binaire vérifiée.
 */
internal object CubeWireProtocol {
    const val CMD_CUBE_LIGHTS = 0x04
    const val CMD_SET_PROP_SLOT = 0x05
    const val CMD_STREAM_OBJECT_ACCEL = 0x08
    const val CMD_SET_ACCESSORY_DISCOVERY = 0x0a
    const val CMD_CUBE_ID = 0x10

    const val EVT_OBJECT_MOVED = 0xb4
    const val EVT_OBJECT_STOPPED = 0xb5
    const val EVT_OBJECT_TAPPED = 0xb6
    const val EVT_OBJECT_TAP_FILTERED = 0xb9
    const val EVT_OBJECT_POWER_LEVEL = 0xce
    const val EVT_OBJECT_CONNECTION_STATE = 0xd0
    const val EVT_OBJECT_UP_AXIS_CHANGED = 0xd7
    const val EVT_OBJECT_AVAILABLE = 0xf3
    const val EVT_OBJECT_ACCEL = 0xf5

    data class ObjectAvailable(
        val factoryId: Long,
        val objectType: Int,
        val rssi: Int
    )

    data class ObjectConnectionState(
        val objectId: Long,
        val factoryId: Long,
        val objectType: Int,
        val connected: Boolean
    )

    data class ObjectPowerLevel(
        val objectId: Long,
        val missedPackets: Long,
        val batteryLevel: Int
    )

    data class ObjectAccel(
        val timestamp: Long,
        val objectId: Long,
        val x: Float,
        val y: Float,
        val z: Float
    )

    data class ObjectMoved(
        val timestamp: Long,
        val objectId: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val upAxis: Int
    )

    data class ObjectStopped(
        val timestamp: Long,
        val objectId: Long
    )

    data class ObjectTapped(
        val timestamp: Long,
        val objectId: Long,
        val numTaps: Int,
        val tapTime: Int,
        val tapNeg: Int,
        val tapPos: Int
    )

    data class ObjectTapFiltered(
        val timestamp: Long,
        val objectId: Long,
        val tapType: Int,
        val intensity: Int
    )

    data class ObjectUpAxisChanged(
        val timestamp: Long,
        val objectId: Long,
        val axis: Int
    )

    fun decodeObjectAvailable(payload: ByteArray): ObjectAvailable? {
        if (payload.size < 9) return null
        val b = payload.le()
        return ObjectAvailable(
            factoryId = b.uint32(),
            objectType = b.int,
            rssi = b.get().toInt()
        )
    }

    fun decodeObjectConnectionState(payload: ByteArray): ObjectConnectionState? {
        if (payload.size < 13) return null
        val b = payload.le()
        return ObjectConnectionState(
            objectId = b.uint32(),
            factoryId = b.uint32(),
            objectType = b.int,
            connected = b.get().toInt() != 0
        )
    }

    fun decodeObjectPowerLevel(payload: ByteArray): ObjectPowerLevel? {
        if (payload.size < 9) return null
        val b = payload.le()
        return ObjectPowerLevel(
            objectId = b.uint32(),
            missedPackets = b.uint32(),
            batteryLevel = b.get().toInt() and 0xff
        )
    }

    fun decodeObjectAccel(payload: ByteArray): ObjectAccel? {
        if (payload.size < 20) return null
        val b = payload.le()
        return ObjectAccel(
            timestamp = b.uint32(),
            objectId = b.uint32(),
            x = b.float,
            y = b.float,
            z = b.float
        )
    }

    fun decodeObjectMoved(payload: ByteArray): ObjectMoved? {
        if (payload.size < 21) return null
        val b = payload.le()
        return ObjectMoved(
            timestamp = b.uint32(),
            objectId = b.uint32(),
            x = b.float,
            y = b.float,
            z = b.float,
            upAxis = b.get().toInt() and 0xff
        )
    }

    fun decodeObjectStopped(payload: ByteArray): ObjectStopped? {
        if (payload.size < 8) return null
        val b = payload.le()
        return ObjectStopped(
            timestamp = b.uint32(),
            objectId = b.uint32()
        )
    }

    fun decodeObjectTapped(payload: ByteArray): ObjectTapped? {
        if (payload.size < 12) return null
        val b = payload.le()
        return ObjectTapped(
            timestamp = b.uint32(),
            objectId = b.uint32(),
            numTaps = b.get().toInt() and 0xff,
            tapTime = b.get().toInt() and 0xff,
            tapNeg = b.get().toInt(),
            tapPos = b.get().toInt()
        )
    }

    fun decodeObjectTapFiltered(payload: ByteArray): ObjectTapFiltered? {
        if (payload.size < 10) return null
        val b = payload.le()
        return ObjectTapFiltered(
            timestamp = b.uint32(),
            objectId = b.uint32(),
            tapType = b.get().toInt() and 0xff,
            intensity = b.get().toInt() and 0xff
        )
    }

    fun decodeObjectUpAxisChanged(payload: ByteArray): ObjectUpAxisChanged? {
        if (payload.size < 9) return null
        val b = payload.le()
        return ObjectUpAxisChanged(
            timestamp = b.uint32(),
            objectId = b.uint32(),
            axis = b.get().toInt() and 0xff
        )
    }

    /**
     * Message 0x05 vérifié dans libcozmoEngine.so 3.4.3 :
     * SetPropSlot(factory_id:uint32, slot:uint8).
     *
     * Le 5e octet n'est PAS un booléen "connect". L'engine officiel gère
     * cinq slots (0..4) et utilise factory_id=0 pour vider un slot.
     */
    fun setPropSlot(factoryId: Long, slot: Int): ByteArray {
        require(slot in 0..4) { "Prop slot hors plage: $slot" }

        return ByteBuffer.allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(factoryId.toInt())
            .put(slot.toByte())
            .array()
    }

    fun clearPropSlot(slot: Int): ByteArray =
        setPropSlot(factoryId = 0L, slot = slot)

    fun streamObjectAccel(objectId: Long, enabled: Boolean): ByteArray =
        ByteBuffer.allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(objectId.toInt())
            .put((if (enabled) 1 else 0).toByte())
            .array()

    fun cubeId(objectId: Long, rotationPeriodFrames: Int = 0): ByteArray =
        ByteBuffer.allocate(5)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(objectId.toInt())
            .put(rotationPeriodFrames.coerceIn(0, 255).toByte())
            .array()

    fun cubeLights(states: List<ByteArray>): ByteArray {
        require(states.size == 4)
        require(states.all { it.size == 10 })

        return ByteBuffer.allocate(40)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { states.forEach { put(it) } }
            .array()
    }

    fun solidLightState(color: BackpackColor): ByteArray {
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

    fun animatedLightState(color: BackpackColor): ByteArray {
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
                // Valeur historiquement utilisée par Cozmo pour un blanc
                // moins agressif que le 0x7fff du backpack.
                val r = 8
                val g = 8
                val b = 16
                (r shl 10) or (g shl 5) or b
            }
            else -> color.encoded
        }

    private fun ByteArray.le(): ByteBuffer =
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.uint32(): Long =
        int.toLong() and 0xffffffffL
}
