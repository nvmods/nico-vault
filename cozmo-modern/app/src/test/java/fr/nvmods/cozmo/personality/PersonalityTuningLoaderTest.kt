package fr.nvmods.cozmo.personality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityTuningLoaderTest {

    private val sample = """
    {
      "mood": {
        "decayCurves": {
          "default": [[0, 1], [20, 0]],
          "Social": [[0, 1], [5, 0]],
          "Unknown": [[0, 1]]
        },
        "repetitionPenalty": [[0, 0.5], [10, 1]],
        "events": {
          "CliffDetected": {"source": "cliff_events.json", "affectors": {"Brave": -0.5}},
          "NewEvent": {"affectors": {"Happy": 0.25, "Nope": 1}}
        }
      },
      "needs": {
        "config": {"BracketLevelEnergyWarning": 0.3, "FullnessDecayCooldownPlay": 0, "MinimumNeedLevel": 0.05},
        "decay": {"DecayRates": {"ConnectedDecayRatesPlay": [
          {"Threshold": 0.03, "DecayPerMinute": 0.5},
          {"Threshold": 0.5, "DecayPerMinute": 0.1}
        ]}},
        "actionDeltas": {"Feed": {"repairDelta": 0, "energyDelta": 0.5, "playDelta": 0, "cooldownSecs": 3}}
      },
      "reactionTriggers": [
        {"reactionTrigger": "Frustration", "behaviorID": "ReactToFrustrationMinor",
         "frustrationParams": {"maxConfidence": -0.5, "cooldownTime_s": 30.0}},
        {"reactionTrigger": "Frustration", "behaviorID": "ReactToFrustrationMajor",
         "frustrationParams": {"maxConfidence": -0.8}}
      ],
      "activities": {"sub": {
        "Hiking": {"activityStrategy": {"activityShouldEndDurationSecs": 42.0, "cooldownBaseSecs": 7.0}}
      }},
      "behaviors": {"x": {"text": "é\u00e9\"q"}}
    }
    """.trimIndent()

    @Test
    fun overridesKnownKeysAndKeepsDefaultsElsewhere() {
        val t = PersonalityTuningLoader.fromJson(sample, sourceLabel = "test")
        val d = PersonalityTuning.DEFAULT

        assertEquals("test", t.source)
        assertEquals(0.5f, t.mood.defaultDecay.eval(10f), 1e-4f)
        assertEquals(0f, t.mood.decayFor(EmotionType.Social).eval(5f), 1e-4f)
        assertEquals(0.5f, t.mood.repetitionPenalty.eval(0f), 1e-4f)
        assertEquals(mapOf(EmotionType.Brave to -0.5f), t.mood.events["CliffDetected"])
        assertEquals(mapOf(EmotionType.Happy to 0.25f), t.mood.events["NewEvent"])
        assertTrue("les événements CUSTOM restent", t.mood.events.containsKey("CubeTapped"))

        assertEquals(0.3f, t.needs.brackets.getValue(NeedId.Energy).getValue(NeedBracket.Warning), 1e-4f)
        assertEquals(0f, t.needs.fullnessCooldownSec.getValue(NeedId.Play), 1e-4f)
        assertEquals(0.05f, t.needs.minLevel, 1e-4f)
        assertEquals(0.5f, t.needs.connectedDecay.getValue(NeedId.Play).last().perMinute, 1e-4f)
        assertEquals(0.5f, t.needs.connectedDecay.getValue(NeedId.Play).first().threshold, 1e-4f)
        assertEquals(d.needs.connectedDecay[NeedId.Energy], t.needs.connectedDecay[NeedId.Energy])
        assertEquals(0.5f, t.needs.actions.getValue("Feed").energy, 1e-4f)
        assertTrue(t.needs.actions.containsKey("CubePlay"))

        assertEquals(-0.5f, t.frustration.minorMaxConfident, 1e-4f)
        assertEquals(30f, t.frustration.minorCooldownSec, 1e-4f)
        assertEquals(-0.8f, t.frustration.majorMaxConfident, 1e-4f)

        assertEquals(42f, t.timing(Activity.Hiking).shouldEndSec, 1e-4f)
        assertEquals(7f, t.timing(Activity.Hiking).cooldownSec, 1e-4f)
        assertEquals(d.timing(Activity.Socialize), t.timing(Activity.Socialize))
    }

    @Test
    fun emptyObjectGivesDefaults() {
        val t = PersonalityTuningLoader.fromJson("{}")
        assertEquals(PersonalityTuning.DEFAULT.needs, t.needs)
        assertEquals(PersonalityTuning.DEFAULT.frustration, t.frustration)
    }

    @Test(expected = Exception::class)
    fun invalidJsonThrows() {
        PersonalityTuningLoader.fromJson("{\"mood\": [1, 2")
    }
}
