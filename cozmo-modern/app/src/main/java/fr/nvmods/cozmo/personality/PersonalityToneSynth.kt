package fr.nvmods.cozmo.personality

import kotlin.math.PI
import kotlin.math.sin

/**
 * Petites vocalises synthétiques originales pour la personnalité.
 *
 * Elles utilisent le pipeline audio natif 22,05 kHz déjà validé.
 * Aucun asset sonore de l'application Anki n'est redistribué.
 */
internal object PersonalityToneSynth {
    private const val SAMPLE_RATE = 22_050
    private const val GAP_MS = 22

    private data class Tone(
        val frequencyHz: Double,
        val durationMs: Int,
        val amplitude: Double = 0.24
    )

    fun synthesize(cue: PersonalitySoundCue): ShortArray {
        val tones = when (cue) {
            PersonalitySoundCue.GREETING -> listOf(
                Tone(660.0, 70),
                Tone(880.0, 80),
                Tone(1100.0, 95)
            )
            PersonalitySoundCue.HAPPY_SHORT -> listOf(
                Tone(900.0, 55),
                Tone(1250.0, 70)
            )
            PersonalitySoundCue.HAPPY_LONG -> listOf(
                Tone(760.0, 70),
                Tone(980.0, 80),
                Tone(1220.0, 100),
                Tone(980.0, 70)
            )
            PersonalitySoundCue.CURIOUS -> listOf(
                Tone(520.0, 85),
                Tone(760.0, 95)
            )
            PersonalitySoundCue.BORED -> listOf(
                Tone(430.0, 120, 0.18),
                Tone(360.0, 150, 0.16)
            )
            PersonalitySoundCue.ANGRY -> listOf(
                Tone(260.0, 70, 0.26),
                Tone(220.0, 70, 0.26),
                Tone(260.0, 70, 0.26)
            )
            PersonalitySoundCue.SAD -> listOf(
                Tone(480.0, 110, 0.16),
                Tone(360.0, 160, 0.15)
            )
            PersonalitySoundCue.SURPRISED -> listOf(
                Tone(700.0, 45),
                Tone(1450.0, 95)
            )
            PersonalitySoundCue.PICKED_UP -> listOf(
                Tone(540.0, 60),
                Tone(740.0, 60),
                Tone(980.0, 70)
            )
            PersonalitySoundCue.PUT_DOWN -> listOf(
                Tone(840.0, 55),
                Tone(620.0, 75)
            )
            PersonalitySoundCue.EFFORT -> listOf(
                Tone(300.0, 90, 0.24),
                Tone(380.0, 90, 0.24)
            )
            PersonalitySoundCue.SELF_RIGHT -> listOf(
                Tone(420.0, 60),
                Tone(640.0, 60),
                Tone(900.0, 100)
            )
            PersonalitySoundCue.CLIFF -> listOf(
                Tone(1450.0, 45, 0.28),
                Tone(1100.0, 45, 0.28),
                Tone(1450.0, 45, 0.28)
            )
            PersonalitySoundCue.SLEEPY -> listOf(
                Tone(380.0, 150, 0.14),
                Tone(300.0, 180, 0.12)
            )
            PersonalitySoundCue.WAKE_UP -> listOf(
                Tone(500.0, 60),
                Tone(760.0, 70),
                Tone(1050.0, 90)
            )
            PersonalitySoundCue.PLAYFUL -> listOf(
                Tone(760.0, 45),
                Tone(1120.0, 55),
                Tone(880.0, 50),
                Tone(1320.0, 70)
            )
            PersonalitySoundCue.WIN -> listOf(
                Tone(660.0, 60),
                Tone(880.0, 60),
                Tone(1100.0, 60),
                Tone(1320.0, 100)
            )
            PersonalitySoundCue.LOSE -> listOf(
                Tone(520.0, 90),
                Tone(430.0, 100),
                Tone(330.0, 140)
            )
            PersonalitySoundCue.LOW_ENERGY -> listOf(
                Tone(360.0, 130, 0.14),
                Tone(280.0, 170, 0.12)
            )
        }

        return render(tones)
    }

    private fun render(tones: List<Tone>): ShortArray {
        if (tones.isEmpty()) return ShortArray(0)

        val gapSamples = SAMPLE_RATE * GAP_MS / 1000
        val totalSamples =
            tones.sumOf { SAMPLE_RATE * it.durationMs / 1000 } +
                gapSamples * (tones.size - 1)

        val out = ShortArray(totalSamples)
        var offset = 0

        tones.forEachIndexed { index, tone ->
            val count = SAMPLE_RATE * tone.durationMs / 1000
            val edge = (SAMPLE_RATE * 0.006).toInt().coerceAtLeast(1)

            for (i in 0 until count) {
                val fadeIn = (i.toDouble() / edge).coerceIn(0.0, 1.0)
                val fadeOut = ((count - 1 - i).toDouble() / edge).coerceIn(0.0, 1.0)
                val envelope = minOf(fadeIn, fadeOut)
                val phase = 2.0 * PI * tone.frequencyHz * i / SAMPLE_RATE
                val value =
                    sin(phase) *
                        envelope *
                        tone.amplitude *
                        Short.MAX_VALUE

                out[offset + i] = value.toInt().toShort()
            }

            offset += count
            if (index != tones.lastIndex) {
                offset += gapSamples
            }
        }

        return out
    }
}
