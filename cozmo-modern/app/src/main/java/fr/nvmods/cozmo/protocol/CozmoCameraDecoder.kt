package fr.nvmods.cozmo.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CozmoCameraAssembler {
    private var imageId: Long? = null
    private var encoding: Int = 0
    private var resolution: Int = 4
    private var expectedChunk = 0
    private val data = ByteArrayOutputStream()

    fun accept(payload: ByteArray): Bitmap? {
        if (payload.size < 20) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        val newImageId = b.int.toLong() and 0xffffffffL
        b.int
        val newEncoding = b.get().toInt() and 0xff
        val newResolution = b.get().toInt() and 0xff
        val chunkCount = b.get().toInt() and 0xff
        val chunkId = b.get().toInt() and 0xff
        b.short
        val declaredLength = b.short.toInt() and 0xffff
        val actualLength = minOf(declaredLength, b.remaining())
        val chunkData = ByteArray(actualLength)
        b.get(chunkData)

        if (imageId != newImageId || chunkId == 0) {
            reset(newImageId, newEncoding, newResolution)
        }

        if (chunkId != expectedChunk) {
            reset(null, 0, 4)
            return null
        }

        data.write(chunkData)
        expectedChunk++

        if (chunkId != chunkCount - 1) return null

        val raw = data.toByteArray()
        val currentEncoding = encoding
        val currentResolution = resolution
        reset(null, 0, 4)

        return decode(raw, currentEncoding, currentResolution)
    }

    private fun reset(newId: Long?, newEncoding: Int, newResolution: Int) {
        imageId = newId
        encoding = newEncoding
        resolution = newResolution
        expectedChunk = 0
        data.reset()
    }

    private fun decode(raw: ByteArray, encoding: Int, resolution: Int): Bitmap? {
        if (raw.isEmpty()) return null

        return when (encoding) {
            8 -> decodeMiniJpeg(raw, resolution)

            5, 6, 7 -> {
                val start = findJpegStart(raw)
                if (start < 0) {
                    null
                } else {
                    BitmapFactory.decodeByteArray(raw, start, raw.size - start)
                }
            }

            else -> null
        }
    }

    private fun decodeMiniJpeg(raw: ByteArray, resolution: Int): Bitmap? {
        val (fullWidth, height) = resolutionSize(resolution)

        // Format Cozmo JPEGMinimizedGray :
        // le premier octet indique si le flux est couleur.
        // En couleur, la trame JPEG est encodée à demi-largeur.
        val isColor = (raw[0].toInt() and 0xff) != 0

        if (!isColor) {
            val jpeg = MiniJpeg.toGrayJpeg(raw, fullWidth, height)
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        }

        val encodedWidth = fullWidth / 2
        if (encodedWidth <= 0) return null

        val jpeg = MiniJpeg.toColorJpeg(raw, encodedWidth, height)
        val halfWidthBitmap =
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null

        // Le SDK Cozmo encode la chrominance couleur en demi-largeur.
        // Restaurer la géométrie nominale (ex. 320 x 240).
        if (halfWidthBitmap.width == fullWidth) return halfWidthBitmap

        return Bitmap.createScaledBitmap(
            halfWidthBitmap,
            fullWidth,
            height,
            true
        ).also {
            if (it !== halfWidthBitmap) {
                halfWidthBitmap.recycle()
            }
        }
    }

    private fun findJpegStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (
                (data[i].toInt() and 0xff) == 0xff &&
                (data[i + 1].toInt() and 0xff) == 0xd8
            ) {
                return i
            }
        }
        return -1
    }

    private fun resolutionSize(resolution: Int): Pair<Int, Int> = when (resolution) {
        0 -> 16 to 16
        1 -> 40 to 30
        2 -> 80 to 60
        3 -> 160 to 120
        4 -> 320 to 240
        5 -> 400 to 296
        6 -> 640 to 480
        7 -> 800 to 600
        8 -> 1024 to 768
        9 -> 1280 to 960
        10 -> 1600 to 1200
        11 -> 2048 to 1536
        12 -> 3200 to 2400
        else -> 320 to 240
    }
}

private object MiniJpeg {
    private val grayHeader = hex(
        "ffd8ffe000104a46494600010100000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffc0000b080128019001011100ffc400d20000010501010101010100000000000000000102030405060708090a0b100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f00"
    )

    private val colorHeader = hex(
        "ffd8ffe000104a46494600010100000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffc000110800f0014003012100021100031100ffc400d20000010501010101010100000000000000000102030405060708090a0b100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda000c03010002000300003f00"
    )

    fun toGrayJpeg(mini: ByteArray, width: Int, height: Int): ByteArray =
        toJpeg(mini, width, height, grayHeader)

    fun toColorJpeg(mini: ByteArray, width: Int, height: Int): ByteArray =
        toJpeg(mini, width, height, colorHeader)

    private fun toJpeg(
        mini: ByteArray,
        width: Int,
        height: Int,
        template: ByteArray
    ): ByteArray {
        if (mini.isEmpty()) return ByteArray(0)

        val h = template.copyOf()
        h[0x5e] = (height ushr 8).toByte()
        h[0x5f] = height.toByte()
        h[0x60] = (width ushr 8).toByte()
        h[0x61] = width.toByte()

        var end = mini.size
        while (
            end > 1 &&
            (mini[end - 1].toInt() and 0xff) == 0xff
        ) {
            end--
        }

        val out = ByteArrayOutputStream(h.size + end * 2 + 2)
        out.write(h)

        // Le premier octet du mini-JPEG est le flag couleur, pas du JPEG.
        for (i in 1 until end) {
            val value = mini[i].toInt() and 0xff
            out.write(value)

            // Byte stuffing JPEG.
            if (value == 0xff) out.write(0)
        }

        out.write(0xff)
        out.write(0xd9)
        return out.toByteArray()
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
