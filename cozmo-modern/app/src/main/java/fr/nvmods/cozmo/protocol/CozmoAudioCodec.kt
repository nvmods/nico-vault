package fr.nvmods.cozmo.protocol

/**
 * Codec audio attendu par le firmware Cozmo.
 *
 * Le nom historique est "u-law", mais le mapping d'octet observé/validé par
 * PyCozmo n'est pas le G.711 téléphonie classique. En particulier, encoder
 * un zéro PCM donne 0x01 et non 0xff.
 */
internal object CozmoAudioCodec {
    const val SAMPLE_RATE = 22_050
    const val SAMPLES_PER_PACKET = 744

    fun encodePacket(
        samples: ShortArray,
        offset: Int
    ): ByteArray {
        val out = ByteArray(SAMPLES_PER_PACKET)
        val count = minOf(
            SAMPLES_PER_PACKET,
            samples.size - offset
        )

        for (i in 0 until count) {
            out[i] = encodeSample(samples[offset + i])
        }

        return out
    }

    fun encodeSample(input: Short): Byte {
        var sample = input.toInt()
        var sign = 0

        if (sample < 0) {
            sample = -sample
            sign = 0x80
        }

        sample = (sample + 132).coerceAtMost(0x7fff)

        var mask = 0x4000
        var position = 14

        while (
            (sample and mask) != mask &&
            position >= 7
        ) {
            mask = mask ushr 1
            position--
        }

        val lsb =
            (sample shr (position - 4)) and 0x0f

        val value =
            sign or
                ((position - 7) shl 4) or
                lsb

        // PyCozmo : -(~value), mathématiquement value + 1.
        return ((value + 1) and 0xff).toByte()
    }

    fun packetDurationNanos(): Long =
        SAMPLES_PER_PACKET.toLong() *
            1_000_000_000L /
            SAMPLE_RATE.toLong()
}
