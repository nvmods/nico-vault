package fr.nvmods.cozmo.personality

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Vocalises robotiques procédurales.
 *
 * Ce n'est volontairement plus une suite de sinus "bip bip" : chaque
 * syllabe possède un glissé de fréquence, un vibrato rapide, plusieurs
 * harmoniques et une enveloppe courte. Le résultat sert de voix principale
 * tant que les VO Wwise 3.6.6 ne sont pas décodées directement dans l'app.
 */
internal object PersonalityToneSynth {
    private const val SAMPLE_RATE = 22_050
    private const val GAP_MS = 14

    private data class Syllable(
        val startHz: Double,
        val endHz: Double,
        val durationMs: Int,
        val amplitude: Double = 0.23,
        val vibratoHz: Double = 17.0,
        val vibratoDepth: Double = 0.055,
        val mouthHz: Double = 9.0
    )

    fun synthesize(
        cue: PersonalitySoundCue,
        random: Random = Random.Default
    ): ShortArray {
        val syllables = when (cue) {
            PersonalitySoundCue.GREETING -> listOf(
                Syllable(560.0, 820.0, 105),
                Syllable(760.0, 1120.0, 130),
                Syllable(930.0, 760.0, 95)
            )

            PersonalitySoundCue.HAPPY_SHORT -> listOf(
                Syllable(720.0, 1120.0, 90),
                Syllable(980.0, 1380.0, 105)
            )

            PersonalitySoundCue.HAPPY_LONG -> listOf(
                Syllable(620.0, 920.0, 105),
                Syllable(850.0, 1320.0, 130),
                Syllable(1180.0, 900.0, 105),
                Syllable(920.0, 1260.0, 125)
            )

            PersonalitySoundCue.CURIOUS -> listOf(
                Syllable(430.0, 650.0, 125, mouthHz = 7.0),
                Syllable(610.0, 980.0, 155, vibratoDepth = 0.075)
            )

            PersonalitySoundCue.BORED -> listOf(
                Syllable(430.0, 330.0, 185, 0.17, 11.0, 0.025, 5.0),
                Syllable(350.0, 280.0, 220, 0.15, 9.0, 0.020, 4.0)
            )

            PersonalitySoundCue.ANGRY -> listOf(
                Syllable(330.0, 245.0, 105, 0.27, 23.0, 0.08, 13.0),
                Syllable(290.0, 380.0, 115, 0.27, 25.0, 0.09, 15.0),
                Syllable(350.0, 240.0, 100, 0.26, 22.0, 0.08, 13.0)
            )

            PersonalitySoundCue.SAD -> listOf(
                Syllable(520.0, 390.0, 160, 0.17, 10.0, 0.025, 5.0),
                Syllable(410.0, 285.0, 210, 0.15, 8.0, 0.020, 4.0)
            )

            PersonalitySoundCue.SURPRISED -> listOf(
                Syllable(560.0, 1260.0, 80, 0.25, 22.0, 0.07, 12.0),
                Syllable(1180.0, 1540.0, 105, 0.23, 24.0, 0.08, 14.0)
            )

            PersonalitySoundCue.PICKED_UP -> listOf(
                Syllable(470.0, 760.0, 95),
                Syllable(680.0, 1080.0, 110),
                Syllable(980.0, 1240.0, 105)
            )

            PersonalitySoundCue.PUT_DOWN -> listOf(
                Syllable(900.0, 680.0, 95),
                Syllable(720.0, 520.0, 110)
            )

            PersonalitySoundCue.EFFORT -> listOf(
                Syllable(300.0, 410.0, 135, 0.25, 19.0, 0.07, 12.0),
                Syllable(360.0, 520.0, 145, 0.25, 21.0, 0.08, 13.0)
            )

            PersonalitySoundCue.SELF_RIGHT -> listOf(
                Syllable(340.0, 520.0, 100, 0.24, 19.0, 0.07, 11.0),
                Syllable(480.0, 780.0, 105, 0.24),
                Syllable(720.0, 1120.0, 135, 0.25)
            )

            PersonalitySoundCue.CLIFF -> listOf(
                Syllable(1380.0, 1080.0, 70, 0.26, 28.0, 0.07, 17.0),
                Syllable(1040.0, 1420.0, 70, 0.26, 28.0, 0.07, 17.0),
                Syllable(1380.0, 1120.0, 80, 0.26, 28.0, 0.07, 17.0)
            )

            PersonalitySoundCue.SLEEPY -> listOf(
                Syllable(390.0, 310.0, 210, 0.14, 7.0, 0.018, 3.5),
                Syllable(320.0, 250.0, 245, 0.12, 6.0, 0.015, 3.0)
            )

            PersonalitySoundCue.WAKE_UP -> listOf(
                Syllable(430.0, 650.0, 90),
                Syllable(620.0, 950.0, 100),
                Syllable(900.0, 1280.0, 120)
            )

            PersonalitySoundCue.PLAYFUL -> listOf(
                Syllable(650.0, 1040.0, 75, vibratoHz = 24.0),
                Syllable(1080.0, 790.0, 80, vibratoHz = 25.0),
                Syllable(760.0, 1260.0, 95, vibratoHz = 26.0),
                Syllable(1120.0, 1420.0, 105, vibratoHz = 27.0)
            )

            PersonalitySoundCue.WIN -> listOf(
                Syllable(600.0, 780.0, 80),
                Syllable(760.0, 980.0, 85),
                Syllable(960.0, 1210.0, 90),
                Syllable(1160.0, 1480.0, 135)
            )

            PersonalitySoundCue.LOSE -> listOf(
                Syllable(560.0, 470.0, 115, 0.18, 10.0, 0.03, 5.0),
                Syllable(470.0, 370.0, 135, 0.17, 9.0, 0.025, 4.5),
                Syllable(380.0, 285.0, 175, 0.15, 8.0, 0.02, 4.0)
            )

            PersonalitySoundCue.LOW_ENERGY -> listOf(
                Syllable(370.0, 310.0, 180, 0.13, 7.0, 0.018, 3.5),
                Syllable(310.0, 255.0, 220, 0.11, 6.0, 0.015, 3.0)
            )
        }

        // Petite dérive aléatoire de timbre et de tempo : deux CURIOUS
        // successifs ne doivent plus être strictement identiques.
        val pitchScale = 0.92 + random.nextDouble() * 0.16
        val durationScale = 0.90 + random.nextDouble() * 0.20
        val vibratoScale = 0.92 + random.nextDouble() * 0.18
        val mouthOffset = -1.2 + random.nextDouble() * 2.4

        val variant = syllables.map { syllable ->
            syllable.copy(
                startHz = syllable.startHz * pitchScale,
                endHz = syllable.endHz * pitchScale,
                durationMs =
                    (syllable.durationMs * durationScale)
                        .toInt()
                        .coerceAtLeast(45),
                vibratoHz = syllable.vibratoHz * vibratoScale,
                mouthHz = (syllable.mouthHz + mouthOffset).coerceAtLeast(2.5)
            )
        }

        return render(variant)
    }

    private fun render(syllables: List<Syllable>): ShortArray {
        if (syllables.isEmpty()) return ShortArray(0)

        val gapSamples = SAMPLE_RATE * GAP_MS / 1000
        val totalSamples =
            syllables.sumOf { SAMPLE_RATE * it.durationMs / 1000 } +
                gapSamples * (syllables.size - 1)

        val out = ShortArray(totalSamples)
        var offset = 0
        var phase = 0.0

        syllables.forEachIndexed { index, syllable ->
            val count = SAMPLE_RATE * syllable.durationMs / 1000
            val edge = (SAMPLE_RATE * 0.012).toInt().coerceAtLeast(1)

            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val progress =
                    if (count <= 1) 0.0
                    else i.toDouble() / (count - 1).toDouble()

                val baseFrequency =
                    syllable.startHz +
                        (syllable.endHz - syllable.startHz) * progress

                val vibrato =
                    1.0 +
                        syllable.vibratoDepth *
                        sin(2.0 * PI * syllable.vibratoHz * t)

                val frequency = baseFrequency * vibrato
                phase += 2.0 * PI * frequency / SAMPLE_RATE

                val fadeIn = (i.toDouble() / edge).coerceIn(0.0, 1.0)
                val fadeOut =
                    ((count - 1 - i).toDouble() / edge)
                        .coerceIn(0.0, 1.0)
                val envelope =
                    minOf(fadeIn, fadeOut)

                // Plusieurs harmoniques donnent un timbre de "voix robot"
                // plutôt qu'un simple générateur de tonalités.
                val carrier =
                    sin(phase) +
                        0.38 * sin(phase * 2.0 + 0.55) +
                        0.16 * sin(phase * 3.0 + 1.10)

                // Petite modulation d'amplitude façon bouche/syllabe.
                val mouth =
                    0.82 +
                        0.18 *
                        sin(2.0 * PI * syllable.mouthHz * t)

                val value =
                    carrier / 1.54 *
                        mouth *
                        envelope *
                        syllable.amplitude *
                        Short.MAX_VALUE

                out[offset + i] =
                    value
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
            }

            offset += count
            if (index != syllables.lastIndex) {
                offset += gapSamples
            }
        }

        return out
    }
}
