package fr.nvmods.cozmo.personality

/**
 * Émotions façon Cozmo d'origine : chaque dimension vit dans [-1, 1] et
 * revient vers 0 selon une courbe fonction du temps écoulé depuis son
 * dernier changement. Un même événement répété trop vite est atténué
 * (repetitionPenalty).
 */
class MoodManager(private val tuning: MoodTuning) {

    private class Slot(var base: Float = 0f, var changedAtMs: Long = 0L)

    private val slots = EmotionType.entries.associateWith { Slot() }
    private val lastEventMs = mutableMapOf<String, Long>()

    fun value(e: EmotionType, nowMs: Long): Float {
        val s = slots.getValue(e)
        if (s.base == 0f) return 0f
        val elapsedSec = ((nowMs - s.changedAtMs).coerceAtLeast(0L)) / 1000f
        return (s.base * tuning.decayFor(e).eval(elapsedSec)).coerceIn(-1f, 1f)
    }

    fun add(e: EmotionType, delta: Float, nowMs: Long) {
        val s = slots.getValue(e)
        s.base = (value(e, nowMs) + delta).coerceIn(-1f, 1f)
        s.changedAtMs = nowMs
    }

    /**
     * Applique un événement nommé (table emotionevents).
     * @return le multiplicateur appliqué (0 si inconnu ou totalement pénalisé).
     */
    fun trigger(eventName: String, nowMs: Long): Float {
        val affectors = tuning.events[eventName] ?: return 0f
        val penalty = lastEventMs[eventName]
            ?.let { tuning.repetitionPenalty.eval((nowMs - it) / 1000f) }
            ?: 1f
        lastEventMs[eventName] = nowMs
        if (penalty <= 0f) return 0f
        affectors.forEach { (e, v) -> add(e, v * penalty, nowMs) }
        return penalty
    }

    fun snapshot(nowMs: Long): Map<EmotionType, Float> =
        EmotionType.entries.associateWith { value(it, nowMs) }
}
