package fr.nvmods.cozmo.personality

import org.json.JSONObject

data class OriginalPenaltyPoint(
    val seconds: Double,
    val multiplier: Double
)

data class OriginalBehaviorRule(
    val id: String,
    val score: Double,
    val repetitionPenalty: List<OriginalPenaltyPoint> = emptyList()
)

data class OriginalActivityRule(
    val id: String,
    val priority: Int,
    val type: String = "Scoring",
    val cooldownSeconds: Double = 0.0,
    val shouldEndSeconds: Double? = null,
    val behaviors: List<OriginalBehaviorRule> = emptyList()
)

class OriginalBehaviorProfile(
    val source: String,
    val rootOrder: List<String>,
    val reactions: Map<String, List<String>>,
    val activities: Map<String, OriginalActivityRule>
) {
    fun reaction(trigger: String): String? =
        reactions[trigger]?.firstOrNull()

    companion object {
        fun fromJson(json: String): OriginalBehaviorProfile {
            val root = JSONObject(json)
            val source = root.optString("source", "Cozmo original")
            val priorities = linkedMapOf<String, Int>()
            val order = mutableListOf<String>()

            root.optJSONArray("root")?.let { array ->
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    val id = entry.getString("activityID")
                    priorities[id] = entry.optInt("activityPriority", i)
                    order += id
                }
            }

            val reactions = linkedMapOf<String, MutableList<String>>()
            root.optJSONArray("reactions")?.let { array ->
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    reactions.getOrPut(entry.getString("reactionTrigger")) {
                        mutableListOf()
                    } += entry.getString("behaviorID")
                }
            }

            val activities = linkedMapOf<String, OriginalActivityRule>()
            root.optJSONObject("activities")?.let { activityObject ->
                val keys = activityObject.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val item = activityObject.getJSONObject(id)
                    val behaviors = mutableListOf<OriginalBehaviorRule>()

                    item.optJSONArray("behaviors")?.let { array ->
                        for (i in 0 until array.length()) {
                            val behavior = array.getJSONObject(i)
                            val repeat = mutableListOf<OriginalPenaltyPoint>()
                            behavior.optJSONArray("repeat")?.let { repeatArray ->
                                for (j in 0 until repeatArray.length()) {
                                    val point = repeatArray.getJSONObject(j)
                                    repeat += OriginalPenaltyPoint(
                                        seconds = point.optDouble("x", 0.0),
                                        multiplier = point.optDouble("y", 1.0)
                                    )
                                }
                            }

                            behaviors += OriginalBehaviorRule(
                                id = behavior.getString("id"),
                                score = behavior.optDouble("score", 1.0),
                                repetitionPenalty = repeat.sortedBy { it.seconds }
                            )
                        }
                    }

                    activities[id] = OriginalActivityRule(
                        id = id,
                        priority = priorities[id] ?: Int.MAX_VALUE,
                        type = item.optString("type", "Scoring"),
                        cooldownSeconds = item.optDouble("cooldown", 0.0),
                        shouldEndSeconds =
                            if (item.has("shouldEnd") && !item.isNull("shouldEnd")) {
                                item.optDouble("shouldEnd")
                            } else {
                                null
                            },
                        behaviors = behaviors
                    )
                }
            }

            return OriginalBehaviorProfile(
                source = source,
                rootOrder = order.sortedBy { priorities[it] ?: Int.MAX_VALUE },
                reactions = reactions.mapValues { it.value.toList() },
                activities = activities
            )
        }

        /**
         * Profil de secours très réduit. Il permet à l'appli de rester
         * fonctionnelle même si l'asset extrait de l'OBB n'est pas lisible.
         */
        fun fallback(): OriginalBehaviorProfile {
            val reactions = mapOf(
                "FacePositionUpdated" to listOf("AcknowledgeFace"),
                "ObjectPositionUpdated" to listOf("AcknowledgeObject"),
                "CubeMoved" to listOf("ReactToCubeMoved"),
                "RobotPickedUp" to listOf("ReactToPickup"),
                "ReturnedToTreads" to listOf("ReactToReturnedToTreads"),
                "RobotOnBack" to listOf("ReactToRobotOnBack"),
                "RobotOnFace" to listOf("ReactToRobotOnFace"),
                "RobotOnSide" to listOf("ReactToRobotOnSide"),
                "RobotFalling" to listOf("ReactToImpact"),
                "CliffDetected" to listOf("ReactToCliff")
            )

            val activities = listOf(
                OriginalActivityRule(
                    id = "Socialize",
                    priority = 11,
                    cooldownSeconds = 30.0,
                    behaviors = listOf(
                        OriginalBehaviorRule("FPPeekABoo", 0.0),
                        OriginalBehaviorRule("PounceOnMotion_Socialize", 0.0)
                    )
                ),
                OriginalActivityRule(
                    id = "PlayWithHumans",
                    priority = 13,
                    cooldownSeconds = 30.0,
                    behaviors = listOf(
                        OriginalBehaviorRule("Bouncer", 1.0),
                        OriginalBehaviorRule("RequestCozmoPerforms", 1.0)
                    )
                ),
                OriginalActivityRule(
                    id = "PlayAlone",
                    priority = 15,
                    cooldownSeconds = 30.0,
                    behaviors = listOf(
                        OriginalBehaviorRule("GuardDog", 1.0),
                        OriginalBehaviorRule("CubeLiftWorkout", 0.8),
                        OriginalBehaviorRule("PopAWheelie", 0.8)
                    )
                ),
                OriginalActivityRule(
                    id = "Hiking",
                    priority = 16,
                    cooldownSeconds = 15.0,
                    behaviors = listOf(
                        OriginalBehaviorRule("Hiking_FirstLookIntro", 9.0),
                        OriginalBehaviorRule("Hiking_ThinkAboutBeacons", 8.0),
                        OriginalBehaviorRule(
                            "Hiking_PounceOnMotion",
                            3.1,
                            listOf(
                                OriginalPenaltyPoint(0.0, 0.0),
                                OriginalPenaltyPoint(10.0, 0.0),
                                OriginalPenaltyPoint(60.0, 1.0)
                            )
                        ),
                        OriginalBehaviorRule("Hiking_LookInPlaceForUnknown", 1.1)
                    )
                ),
                OriginalActivityRule(
                    id = "NothingToDo",
                    priority = 17,
                    behaviors = listOf(
                        OriginalBehaviorRule("NothingToDo_Idle", 1.0),
                        OriginalBehaviorRule(
                            "NothingToDo_BoredAnim",
                            1.0,
                            listOf(
                                OriginalPenaltyPoint(0.0, 0.5),
                                OriginalPenaltyPoint(9.0, 1.0)
                            )
                        )
                    )
                )
            ).associateBy { it.id }

            return OriginalBehaviorProfile(
                source = "Fallback local",
                rootOrder = listOf(
                    "Socialize",
                    "Singing",
                    "PlayWithHumans",
                    "BuildPyramid",
                    "PlayAlone",
                    "Hiking",
                    "NothingToDo"
                ),
                reactions = reactions,
                activities = activities
            )
        }
    }
}
