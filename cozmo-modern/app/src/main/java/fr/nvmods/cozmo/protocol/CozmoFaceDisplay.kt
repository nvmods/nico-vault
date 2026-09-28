package fr.nvmods.cozmo.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Visage monochrome natif de Cozmo.
 *
 * Le robot attend une image logique 128 x 32 compressée avec son RLE
 * colonne-par-colonne, précédée d'une longueur uint16 little-endian
 * dans la commande DisplayImage (0x97).
 */
enum class CozmoFaceExpression {
    NEUTRAL,
    HAPPY,
    CURIOUS,
    SURPRISED,
    SLEEPY,
    SAD,
    ANGRY,
    BLINK
}

internal object CozmoFaceDisplay {
    const val COMMAND_DISPLAY_IMAGE = 0x97

    private const val WIDTH = 128
    private const val HEIGHT = 32

    fun payload(expression: CozmoFaceExpression): ByteArray {
        val pixels = render(expression)
        val encoded = encode(pixels)

        return ByteBuffer.allocate(2 + encoded.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(encoded.size.toShort())
            .put(encoded)
            .array()
    }

    private fun render(expression: CozmoFaceExpression): BooleanArray {
        val p = BooleanArray(WIDTH * HEIGHT)

        when (expression) {
            CozmoFaceExpression.NEUTRAL -> {
                squareEye(p, 41, 16, 20, 22)
                squareEye(p, 87, 16, 20, 22)
            }

            CozmoFaceExpression.HAPPY -> {
                happyEye(p, 41, 17)
                happyEye(p, 87, 17)
            }

            CozmoFaceExpression.CURIOUS -> {
                squareEye(p, 41, 16, 22, 24)
                squareEye(p, 87, 17, 16, 17)
            }

            CozmoFaceExpression.SURPRISED -> {
                squareEye(p, 41, 16, 23, 26)
                squareEye(p, 87, 16, 23, 26)
            }

            CozmoFaceExpression.SLEEPY -> {
                roundedRect(p, 31, 14, 51, 20, 3)
                roundedRect(p, 77, 14, 97, 20, 3)
            }

            CozmoFaceExpression.SAD -> {
                squareEye(p, 41, 18, 20, 18)
                squareEye(p, 87, 18, 20, 18)
                cutDiagonalTop(p, 30, 52, rising = false)
                cutDiagonalTop(p, 76, 98, rising = true)
            }

            CozmoFaceExpression.ANGRY -> {
                squareEye(p, 41, 17, 21, 20)
                squareEye(p, 87, 17, 21, 20)
                cutDiagonalTop(p, 30, 52, rising = true)
                cutDiagonalTop(p, 76, 98, rising = false)
            }

            CozmoFaceExpression.BLINK -> {
                roundedRect(p, 31, 16, 51, 18, 1)
                roundedRect(p, 77, 16, 97, 18, 1)
            }
        }

        return p
    }

    private fun squareEye(
        p: BooleanArray,
        cx: Int,
        cy: Int,
        width: Int,
        height: Int
    ) {
        val left = cx - width / 2
        val right = left + width
        val top = cy - height / 2
        val bottom = top + height

        roundedRect(
            p = p,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 4
        )
    }

    private fun eye(
        p: BooleanArray,
        cx: Int,
        cy: Int,
        width: Int,
        height: Int
    ) {
        val rx = width / 2.0
        val ry = height / 2.0

        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry

                if (nx.pow(2) + ny.pow(2) <= 1.0) {
                    set(p, x, y, true)
                }
            }
        }
    }

    private fun happyEye(
        p: BooleanArray,
        cx: Int,
        cy: Int
    ) {
        // Deux yeux en croissant orientés vers le haut.
        eye(p, cx, cy + 2, 21, 17)

        for (y in 0 until HEIGHT) {
            for (x in (cx - 12)..(cx + 12)) {
                val dx = x - cx
                val curve = cy + 1 + (dx * dx) / 32

                if (y < curve) {
                    set(p, x, y, false)
                }
            }
        }
    }

    private fun roundedRect(
        p: BooleanArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Int
    ) {
        for (y in top..bottom) {
            for (x in left..right) {
                val insideCore =
                    x in (left + radius)..(right - radius) ||
                        y in (top + radius)..(bottom - radius)

                val cornerX = when {
                    x < left + radius -> left + radius
                    x > right - radius -> right - radius
                    else -> x
                }

                val cornerY = when {
                    y < top + radius -> top + radius
                    y > bottom - radius -> bottom - radius
                    else -> y
                }

                val dx = x - cornerX
                val dy = y - cornerY

                if (insideCore || dx * dx + dy * dy <= radius * radius) {
                    set(p, x, y, true)
                }
            }
        }
    }

    private fun cutDiagonalTop(
        p: BooleanArray,
        left: Int,
        right: Int,
        rising: Boolean
    ) {
        val width = (right - left).coerceAtLeast(1)

        for (x in left..right) {
            val ratio = (x - left).toFloat() / width
            val top = if (rising) {
                8 + (ratio * 8).toInt()
            } else {
                16 - (ratio * 8).toInt()
            }

            for (y in 0..top.coerceIn(0, HEIGHT - 1)) {
                set(p, x, y, false)
            }
        }
    }

    private fun set(
        p: BooleanArray,
        x: Int,
        y: Int,
        value: Boolean
    ) {
        if (x in 0 until WIDTH && y in 0 until HEIGHT) {
            p[y * WIDTH + x] = value
        }
    }

    /**
     * Port volontairement fidèle du ImageEncoder PyCozmo.
     * Il encode verticalement chaque colonne et compresse aussi les
     * colonnes vides / répétées.
     */
    private fun encode(pixels: BooleanArray): ByteArray {
        val out = ByteArrayOutputStream()
        var lastColumn = byteArrayOf()
        var currentColumn = ByteArrayOutputStream()
        var skipColumns = 0
        var repeatColumns = 0
        var x = 0
        var y = 0

        fun flushRepeatColumns() {
            if (repeatColumns <= 0) return

            while (repeatColumns >= 64) {
                out.write(0x7f)
                repeatColumns -= 64
            }

            if (repeatColumns > 0) {
                out.write(0x40 + repeatColumns - 1)
                repeatColumns = 0
            }
        }

        fun flushSkipColumns() {
            if (skipColumns <= 0) return

            repeatColumns = 0
            lastColumn = byteArrayOf()

            while (skipColumns >= 64) {
                out.write(0x3f)
                skipColumns -= 64
            }

            if (skipColumns > 0) {
                out.write(skipColumns - 1)
                skipColumns = 0
            }
        }

        fun pixel(px: Int, py: Int): Boolean =
            pixels[py * WIDTH + px]

        while (x < WIDTH && y < HEIGHT) {
            val color = pixel(x, y)
            y++

            var sameAfterFirst = 0

            while (x < WIDTH && y < HEIGHT && pixel(x, y) == color) {
                sameAfterFirst++
                y++

                if (y > HEIGHT - 1) {
                    x++
                    y = 0
                    break
                }
            }

            var command: Int? = null

            if (color) {
                command =
                    if (sameAfterFirst <= 15) {
                        0x80 + (sameAfterFirst shl 2) + 0x01
                    } else {
                        0xc0 + ((sameAfterFirst - 16) shl 2) + 0x01
                    }
            } else {
                command = when {
                    sameAfterFirst <= 15 ->
                        0x80 + (sameAfterFirst shl 2)

                    sameAfterFirst < 31 ->
                        0xc0 + ((sameAfterFirst - 16) shl 2)

                    else -> {
                        skipColumns++
                        null
                    }
                }
            }

            if (command != null) {
                if (y == 0) {
                    flushSkipColumns()

                    if (
                        (command and 0xc3) == 0x81 ||
                        (command and 0xc3) == 0xc1
                    ) {
                        command += 1
                    }
                }

                currentColumn.write(command)
            }

            if (y == 0) {
                val current = currentColumn.toByteArray()

                if (skipColumns == 0) {
                    if (current.contentEquals(lastColumn)) {
                        repeatColumns++
                    } else {
                        flushRepeatColumns()
                        out.write(current)
                        lastColumn = current
                    }
                } else {
                    flushRepeatColumns()
                }

                currentColumn = ByteArrayOutputStream()
            }
        }

        if (y == 0) {
            flushSkipColumns()
            flushRepeatColumns()
        }

        return out.toByteArray()
    }
}
