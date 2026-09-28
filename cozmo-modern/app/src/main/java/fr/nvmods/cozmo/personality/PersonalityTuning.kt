package fr.nvmods.cozmo.personality

/**
 * Réglages du moteur de personnalité.
 *
 * Les valeurs par défaut reprennent le tuning du moteur Cozmo d'origine
 * (mood_config, emotionevents, needs_*_config, activités freeplay,
 * reactionTrigger_behavior_map). Un cozmo_personality.json généré depuis
 * l'APK de l'utilisateur peut les remplacer à l'exécution
 * (voir PersonalityTuningLoader).
 *
 * Les entrées marquées CUSTOM n'existent pas dans l'original : elles couvrent
 * des perceptions propres à cette appli (tap cube, interaction UI...).
 */

enum class EmotionType { Happy, Calm, Excited, Brave, Confident, Social, WantToPlay }

enum class NeedId { Repair, Energy, Play }

enum class NeedBracket { Full, Normal, Warning, Critical }

/** Courbe linéaire par morceaux, même sémantique que les "graphs" Anki. */
class Curve(points: List<Pair<Float, Float>>) {
    private val pts = points.sortedBy { it.first }

    init {
        require(pts.isNotEmpty()) { "Courbe vide" }
    }

    fun eval(x: Float): Float {
        if (x <= pts.first().first) return pts.first().second
        if (x >= pts.last().first) return pts.last().second
        for (i in 1 until pts.size) {
            val (x1, y1) = pts[i - 1]
            val (x2, y2) = pts[i]
            if (x <= x2) {
                if (x2 == x1) return y2
                return y1 + (y2 - y1) * (x - x1) / (x2 - x1)
            }
        }
        return pts.last().second
    }

    companion object {
        fun of(vararg p: Pair<Number, Number>): Curve =
            Curve(p.map { it.first.toFloat() to it.second.toFloat() })
    }
}

data class MoodTuning(
    /** x = secondes depuis le dernier changement, y = fraction conservée. */
    val defaultDecay: Curve,
    val decay: Map<EmotionType, Curve>,
    /** x = secondes depuis la dernière occurrence du même événement. */
    val repetitionPenalty: Curve,
    val events: Map<String, Map<EmotionType, Float>>
) {
    fun decayFor(e: EmotionType): Curve = decay[e] ?: defaultDecay
}

data class DecayStep(val threshold: Float, val perMinute: Float)

data class NeedsActionDelta(
    val repair: Float,
    val energy: Float,
    val play: Float,
    val cooldownSec: Float = 0f
)

data class NeedsTuning(
    val minLevel: Float,
    val maxLevel: Float,
    val initial: Map<NeedId, Float>,
    /** Borne basse de chaque tranche ; Critical = en dessous de Warning. */
    val brackets: Map<NeedId, Map<NeedBracket, Float>>,
    /** Pas de décroissance pendant ce délai après être redevenu plein. */
    val fullnessCooldownSec: Map<NeedId, Float>,
    /** Taux quand l'appli est connectée au robot, seuils décroissants. */
    val connectedDecay: Map<NeedId, List<DecayStep>>,
    val actions: Map<String, NeedsActionDelta>
)

data class FrustrationTuning(
    val minorMaxConfident: Float,
    val minorCooldownSec: Float,
    val majorMaxConfident: Float,
    /** CUSTOM : fenêtre et seuil pour TooManyResumesCliffOrMovement. */
    val disruptionWindowSec: Float,
    val disruptionCount: Int
)

enum class Activity(val label: String) {
    Socialize("Socialiser"),
    PlayWithHumans("Jeu ensemble"),
    PlayAlone("Jouer seul"),
    Hiking("Explorer"),
    NothingToDo("Rien à faire"),
    NeedsSevereLowRepair("Besoin critique : réparation"),
    NeedsSevereLowEnergy("Besoin critique : énergie"),
    NeedsSevereLowPlay("Besoin critique : jeu")
}

data class ActivityTiming(
    /** <= 0 : l'activité dure tant que sa condition tient. */
    val shouldEndSec: Float,
    val cooldownSec: Float
)

data class PersonalityTuning(
    val mood: MoodTuning,
    val needs: NeedsTuning,
    val frustration: FrustrationTuning,
    val activities: Map<Activity, ActivityTiming>,
    /** Socialize ne démarre que si Social <= cette valeur (startMoodScorer). */
    val socializeMaxSocial: Float,
    val source: String
) {
    fun timing(a: Activity): ActivityTiming =
        activities[a] ?: ActivityTiming(-1f, 0f)

    companion object {
        private fun ev(vararg a: Pair<EmotionType, Number>) =
            a.associate { it.first to it.second.toFloat() }

        private fun steps(vararg s: Pair<Number, Number>) =
            s.map { DecayStep(it.first.toFloat(), it.second.toFloat()) }

        private fun brackets(normal: Float, warning: Float) = mapOf(
            NeedBracket.Full to 0.99f,
            NeedBracket.Normal to normal,
            NeedBracket.Warning to warning,
            NeedBracket.Critical to 0f
        )

        val DEFAULT = PersonalityTuning(
            mood = MoodTuning(
                defaultDecay = Curve.of(0 to 1, 10 to 1, 30 to 0.9, 75 to 0.6, 150 to 0),
                decay = mapOf(
                    EmotionType.WantToPlay to Curve.of(0 to 1),
                    EmotionType.Social to Curve.of(0 to 1, 10 to 1, 20 to 0.6, 60 to 0.2, 100 to 0),
                    EmotionType.Confident to Curve.of(0 to 1, 30 to 1, 60 to 0.5, 70 to 0)
                ),
                repetitionPenalty = Curve.of(0 to 0, 30 to 1),
                events = mapOf(
                    "CliffDetected" to ev(
                        EmotionType.Happy to -0.12,
                        EmotionType.Calm to -0.12,
                        EmotionType.Brave to -0.12
                    ),
                    "FoundObservedObject" to ev(EmotionType.Excited to 0.08),
                    "FoundPossibleObject" to ev(EmotionType.Excited to 0.03),
                    "LookAtFaceVerified" to ev(EmotionType.Social to 0.08),
                    "InteractWithUnnamedFace" to ev(EmotionType.Social to 0.6),
                    "InteractWithNamedFace" to ev(EmotionType.Social to 0.8),
                    "MotionReact" to ev(
                        EmotionType.Happy to 0.12,
                        EmotionType.Excited to 0.12,
                        EmotionType.Calm to -0.12
                    ),
                    "ReactToUnexpectedMovement" to ev(EmotionType.Confident to -0.2),
                    "TooManyResumesCliffOrMovement" to ev(EmotionType.Confident to -1),
                    "FinishedMinorFrustration" to ev(EmotionType.Confident to 0.1),
                    "FinishedMajorFrustration" to ev(EmotionType.Confident to 1),
                    // CUSTOM
                    "CubeTapped" to ev(EmotionType.Happy to 0.08, EmotionType.Excited to 0.05),
                    "Petted" to ev(EmotionType.Happy to 0.08, EmotionType.Calm to 0.05),
                    "UserInteraction" to ev(EmotionType.Social to 0.2, EmotionType.Happy to 0.05)
                )
            ),
            needs = NeedsTuning(
                minLevel = 0.03f,
                maxLevel = 1f,
                initial = NeedId.entries.associateWith { 1f },
                brackets = mapOf(
                    NeedId.Repair to brackets(0.6f, 0.27f),
                    NeedId.Energy to brackets(0.6f, 0.21f),
                    NeedId.Play to brackets(0.5f, 0.14f)
                ),
                fullnessCooldownSec = NeedId.entries.associateWith { 1200f },
                connectedDecay = mapOf(
                    NeedId.Repair to steps(0.6 to 0.0007, 0.3 to 0.0006, 0.03 to 0.0005),
                    NeedId.Energy to steps(0.6 to 0.005, 0.3 to 0.004, 0.03 to 0.003),
                    NeedId.Play to steps(0.5 to 0.014, 0.2 to 0.013, 0.03 to 0.012)
                ),
                actions = mapOf(
                    "RepairHead" to NeedsActionDelta(0.33f, 0f, 0f),
                    "RepairLift" to NeedsActionDelta(0.33f, 0f, 0f),
                    "RepairTreads" to NeedsActionDelta(0.33f, 0f, 0f),
                    "Feed" to NeedsActionDelta(0.001f, 0.33f, 0f),
                    "Fall" to NeedsActionDelta(-0.15f, -0.001f, -0.1f),
                    "PlacedOnSide" to NeedsActionDelta(0f, -0.001f, -0.03f),
                    "SeeFace" to NeedsActionDelta(0f, -0.001f, 0.05f, cooldownSec = 60f),
                    // CUSTOM
                    "CubePlay" to NeedsActionDelta(0f, -0.001f, 0.05f, cooldownSec = 20f),
                    "PlayAloneBehavior" to NeedsActionDelta(0f, -0.001f, 0.02f, cooldownSec = 30f),
                    "BatteryLowSimulated" to NeedsActionDelta(0f, -0.25f, 0f)
                )
            ),
            frustration = FrustrationTuning(
                minorMaxConfident = -0.6f,
                minorCooldownSec = 60f,
                majorMaxConfident = -0.9f,
                disruptionWindowSec = 60f,
                disruptionCount = 3
            ),
            activities = mapOf(
                Activity.Socialize to ActivityTiming(300f, 30f),
                Activity.PlayWithHumans to ActivityTiming(-1f, 30f),
                Activity.PlayAlone to ActivityTiming(25f, 30f),
                Activity.Hiking to ActivityTiming(60f, 15f),
                Activity.NothingToDo to ActivityTiming(1f, 0f)
            ),
            socializeMaxSocial = 0.3f,
            source = "Valeurs intégrées (tuning d'origine)"
        )
    }
}
