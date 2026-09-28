package fr.nvmods.cozmo.personality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityManagersTest {

    private val tuning = PersonalityTuning.DEFAULT
    private val eps = 1e-4f

    @Test
    fun curveInterpolatesAndClamps() {
        val c = Curve.of(0 to 1, 10 to 1, 30 to 0.9, 75 to 0.6, 150 to 0)
        assertEquals(1f, c.eval(-5f), eps)
        assertEquals(1f, c.eval(10f), eps)
        assertEquals(0.95f, c.eval(20f), eps)
        assertEquals(0.3f, c.eval(112.5f), eps)
        assertEquals(0f, c.eval(500f), eps)
    }

    @Test
    fun curveHandlesDuplicateX() {
        val step = Curve.of(8 to 0, 8 to 1)
        assertEquals(0f, step.eval(2f), eps)
        assertEquals(1f, step.eval(9f), eps)
    }

    @Test
    fun emotionDecaysBackToZeroWithOriginalCurve() {
        val mood = MoodManager(tuning.mood)
        mood.add(EmotionType.Happy, 0.5f, 0L)
        assertEquals(0.5f, mood.value(EmotionType.Happy, 10_000L), eps)
        assertEquals(0.3f, mood.value(EmotionType.Happy, 75_000L), eps)
        assertEquals(0f, mood.value(EmotionType.Happy, 150_000L), eps)
    }

    @Test
    fun wantToPlayDoesNotDecay() {
        val mood = MoodManager(tuning.mood)
        mood.add(EmotionType.WantToPlay, 0.7f, 0L)
        assertEquals(0.7f, mood.value(EmotionType.WantToPlay, 3_600_000L), eps)
    }

    @Test
    fun repeatedEventIsPenalised() {
        val mood = MoodManager(tuning.mood)
        assertEquals(1f, mood.trigger("FoundObservedObject", 0L), eps)
        assertEquals(0.08f, mood.value(EmotionType.Excited, 0L), eps)

        // Répété immédiatement : aucun effet.
        assertEquals(0f, mood.trigger("FoundObservedObject", 0L), eps)
        assertEquals(0.08f, mood.value(EmotionType.Excited, 0L), eps)

        // Répété 15 s plus tard : demi-effet.
        assertEquals(0.5f, mood.trigger("FoundObservedObject", 15_000L), eps)
    }

    @Test
    fun unknownEventIsIgnored() {
        val mood = MoodManager(tuning.mood)
        assertEquals(0f, mood.trigger("DoesNotExist", 0L), eps)
    }

    @Test
    fun needsRespectFullnessCooldownThenDecayAtOriginalRate() {
        val needs = NeedsManager(tuning.needs, 0L)

        needs.update(20 * MINUTE)
        assertEquals(1f, needs.level(NeedId.Energy), eps)

        // 60 min après la fin du cooldown : Energy -0,005/min au-dessus de 0,6.
        needs.update(80 * MINUTE)
        assertEquals(0.7f, needs.level(NeedId.Energy), 1e-3f)
        assertEquals(NeedBracket.Normal, needs.bracket(NeedId.Energy))
    }

    @Test
    fun energyNeedsHoursToBecomeCritical() {
        val needs = NeedsManager(tuning.needs, 0L)
        needs.update(2 * 60 * MINUTE)
        assertFalse(needs.bracket(NeedId.Energy) == NeedBracket.Critical)
    }

    @Test
    fun feedRestoresEnergyAndCooldownIsHonoured() {
        val needs = NeedsManager(tuning.needs, 0L)
        repeat(3) { needs.apply("BatteryLowSimulated", 0L) }
        val low = needs.level(NeedId.Energy)
        assertTrue(needs.apply("Feed", 1L))
        assertEquals(low + 0.33f, needs.level(NeedId.Energy), 1e-3f)

        assertTrue(needs.apply("SeeFace", 0L))
        assertFalse(needs.apply("SeeFace", 30_000L))
        assertTrue(needs.apply("SeeFace", 61_000L))
    }

    @Test
    fun needsNeverGoBelowMinimum() {
        val needs = NeedsManager(tuning.needs, 0L)
        repeat(10) { needs.apply("BatteryLowSimulated", 0L) }
        assertEquals(tuning.needs.minLevel, needs.level(NeedId.Energy), eps)
        assertEquals(NeedBracket.Critical, needs.bracket(NeedId.Energy))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
