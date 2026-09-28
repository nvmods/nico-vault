package fr.nvmods.cozmo.personality

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalBehaviorSchedulerTest {

    @Test
    fun hikingIntroOnlyRunsWhenEnteringActivity() {
        val profile = OriginalBehaviorProfile(
            source = "test",
            rootOrder = listOf("Hiking"),
            reactions = mapOf("CliffDetected" to listOf("ReactToCliff")),
            activities = mapOf(
                "Hiking" to OriginalActivityRule(
                    id = "Hiking",
                    priority = 16,
                    shouldEndSeconds = 60.0,
                    behaviors = listOf(
                        OriginalBehaviorRule("Hiking_FirstLookIntro", 100.0),
                        OriginalBehaviorRule("Hiking_VisitInterestingEdge", 1.0)
                    )
                )
            )
        )

        val scheduler = OriginalBehaviorScheduler(profile, Random(1234))
        val state = PersonalityState(
            mode = PersonalityMode.CALME,
            curiosity = 1f,
            energy = 1f
        )

        val first = scheduler.idle(state, 10_000L)
        val second = scheduler.idle(state, 13_000L)

        assertEquals("Hiking_FirstLookIntro", first?.behaviorId)
        assertEquals("Hiking_VisitInterestingEdge", second?.behaviorId)
        assertEquals("Hiking", second?.activityId)
    }

    @Test
    fun cliffReactionContainsOriginalBackupMacro() {
        val profile = OriginalBehaviorProfile(
            source = "test",
            rootOrder = emptyList(),
            reactions = mapOf("CliffDetected" to listOf("ReactToCliff")),
            activities = emptyMap()
        )

        val scheduler = OriginalBehaviorScheduler(profile, Random(1))
        val decision = scheduler.reaction(
            "CliffDetected",
            PersonalityState(),
            1_000L
        )

        assertTrue(
            decision?.actions?.any {
                it == RobotAction.PlayAnimation("cliff_react_original")
            } == true
        )
    }
}
