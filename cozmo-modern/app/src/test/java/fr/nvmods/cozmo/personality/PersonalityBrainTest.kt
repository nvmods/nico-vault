package fr.nvmods.cozmo.personality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PersonalityBrainTest {

    private fun brain(seed: Int = 1) =
        PersonalityBrain(PersonalityTuning.DEFAULT, 0L, Random(seed))

    /** Régression v0.15 : l'autonomie ne doit pas s'éteindre après quelques minutes. */
    @Test
    fun autonomyKeepsRunningForThirtyMinutes() {
        val b = brain()
        var now = 0L
        var lastQuarterDecisions = 0
        val end = 30 * 60_000L

        while (now < end) {
            now += 450L
            val d = b.onEvent(PersonalityEvent.IdleTick, now)
            if (d != null && now > end * 3 / 4) lastQuarterDecisions++
        }

        assertTrue("décisions dans le dernier quart : $lastQuarterDecisions", lastQuarterDecisions > 100)
        assertTrue(b.needs.bracket(NeedId.Energy) != NeedBracket.Critical)
    }

    @Test
    fun repeatedPickupsTriggerMajorFrustrationThenRecovery() {
        val b = brain()
        var last: BrainDecision? = null

        for (i in 0 until 3) {
            val t = i * 2_000L
            last = b.onEvent(PersonalityEvent.PickedUp, t)
            b.onEvent(PersonalityEvent.PutDown, t + 1_000L)
        }

        val decision = requireNotNull(last)
        assertTrue(decision.label, decision.label.contains("frustration majeure", ignoreCase = true))
        assertEquals("FinishedMajorFrustration", decision.onCompleteEmotion)
        assertTrue(b.mood.value(EmotionType.Confident, 4_000L) <= -0.9f)

        b.onDecisionCompleted(decision, 6_000L)
        assertTrue(b.mood.value(EmotionType.Confident, 6_000L) > -0.2f)
    }

    @Test
    fun confidenceAloneDecaysBackWithoutFrustrationLoop() {
        val b = brain()
        b.mood.add(EmotionType.Confident, -0.7f, 0L)

        val first = b.onEvent(PersonalityEvent.IdleTick, 100L)
        assertEquals("Frustration mineure", first?.label)

        // Cooldown de 60 s : pas de seconde réaction mineure juste après.
        val again = b.onEvent(PersonalityEvent.IdleTick, 1_000L)
        assertTrue(again?.label != "Frustration mineure")

        // Confident revient à 0 en 70 s.
        assertEquals(0f, b.mood.value(EmotionType.Confident, 71_000L), 1e-4f)
    }

    @Test
    fun activityFollowsWhatCozmoPerceives() {
        val b = brain()
        var now = 1_000L

        b.onEvent(PersonalityEvent.IdleTick, now)
        assertEquals(Activity.Hiking, b.activity)

        b.onEvent(PersonalityEvent.CubeDetected(1L), now)
        now += 6_000L
        b.onEvent(PersonalityEvent.IdleTick, now)
        assertEquals(Activity.PlayAlone, b.activity)

        b.onEvent(PersonalityEvent.FaceDetected(), now)
        now += 6_000L
        b.onEvent(PersonalityEvent.IdleTick, now)
        assertEquals(Activity.Socialize, b.activity)

        b.onEvent(PersonalityEvent.FaceLost, now)
        now += 1_000L
        b.onEvent(PersonalityEvent.IdleTick, now)
        assertEquals(Activity.PlayAlone, b.activity)
    }

    @Test
    fun criticalEnergySwitchesToSevereActivityInsteadOfFreezing() {
        val b = brain()
        repeat(4) { b.onEvent(PersonalityEvent.BatteryLow, 0L) }
        assertEquals(NeedBracket.Critical, b.needs.bracket(NeedId.Energy))

        var decisions = 0
        var now = 0L
        repeat(200) {
            now += 450L
            if (b.onEvent(PersonalityEvent.IdleTick, now) != null) decisions++
        }
        assertEquals(Activity.NeedsSevereLowEnergy, b.activity)
        assertTrue("rythme ralenti mais vivant : $decisions", decisions in 5..60)

        b.onEvent(PersonalityEvent.NeedsAction("Feed"), now)
        b.onEvent(PersonalityEvent.NeedsAction("Feed"), now + 1)
        b.onEvent(PersonalityEvent.IdleTick, now + 10_000L)
        assertTrue(b.activity != Activity.NeedsSevereLowEnergy)
    }

    @Test
    fun cubeMotionIsRateLimited() {
        val b = brain()
        assertNotNull(b.onEvent(PersonalityEvent.CubeMoved(1L), 0L))
        assertEquals(null, b.onEvent(PersonalityEvent.CubeMoved(1L), 1_000L))
        assertNotNull(b.onEvent(PersonalityEvent.CubeMoved(1L), 2_000L))
    }

    @Test
    fun idleUsesOriginalBehaviorIdsFromProfile() {
        val b = brain()
        var now = 0L
        val seen = mutableSetOf<String>()
        repeat(400) {
            now += 450L
            b.onEvent(PersonalityEvent.IdleTick, now)?.let { d ->
                d.behaviorId?.let { id -> seen += id }
                assertTrue(d.activityId != null)
            }
        }
        assertTrue("comportements vus : $seen", seen.any { it.startsWith("Hiking_") })
    }

    @Test
    fun reactionsCarryOriginalBehaviorIds() {
        val b = brain()
        val d = requireNotNull(b.onEvent(PersonalityEvent.CliffDetected, 0L))
        assertEquals("ReactToCliff", d.behaviorId)
        assertEquals("Reaction", d.activityId)
        assertEquals("Attention au bord", d.label)
    }
}
