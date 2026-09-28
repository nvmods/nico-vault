package fr.nvmods.cozmo.personality

import kotlin.math.max

/**
 * Besoins Energy / Play / Repair façon Cozmo d'origine.
 *
 * La décroissance est calculée sur le temps réel écoulé (pas par tick),
 * par pas d'au plus une minute pour respecter les changements de taux
 * aux seuils. Après être redevenu plein, un besoin ne décroît pas pendant
 * fullnessCooldownSec.
 */
class NeedsManager(
    private val tuning: NeedsTuning,
    startMs: Long
) {
    private val levels = NeedId.entries
        .associateWith { (tuning.initial[it] ?: 1f).coerceIn(tuning.minLevel, tuning.maxLevel) }
        .toMutableMap()
    private val fullSinceMs = mutableMapOf<NeedId, Long>()
    private val lastActionMs = mutableMapOf<String, Long>()
    private var lastUpdateMs = startMs

    init {
        NeedId.entries.forEach { if (bracket(it) == NeedBracket.Full) fullSinceMs[it] = startMs }
    }

    fun level(need: NeedId): Float = levels.getValue(need)

    fun bracket(need: NeedId): NeedBracket {
        val lvl = level(need)
        val b = tuning.brackets[need].orEmpty()
        return when {
            lvl >= (b[NeedBracket.Full] ?: 0.99f) -> NeedBracket.Full
            lvl >= (b[NeedBracket.Normal] ?: 0.5f) -> NeedBracket.Normal
            lvl >= (b[NeedBracket.Warning] ?: 0.2f) -> NeedBracket.Warning
            else -> NeedBracket.Critical
        }
    }

    fun update(nowMs: Long) {
        if (nowMs <= lastUpdateMs) return
        val previous = lastUpdateMs
        lastUpdateMs = nowMs

        for (need in NeedId.entries) {
            val cooldownMs = ((tuning.fullnessCooldownSec[need] ?: 0f) * 1000f).toLong()
            val fullUntil = fullSinceMs[need]?.plus(cooldownMs) ?: Long.MIN_VALUE
            val decayStart = max(previous, fullUntil)
            if (nowMs <= decayStart) continue

            var remainingMin = (nowMs - decayStart) / 60_000f
            var lvl = levels.getValue(need)
            while (remainingMin > 0f) {
                val step = minOf(1f, remainingMin)
                val rate = rateFor(need, lvl)
                if (rate <= 0f) break
                lvl = max(tuning.minLevel, lvl - rate * step)
                remainingMin -= step
                if (lvl <= tuning.minLevel) break
            }
            levels[need] = lvl
            if (bracket(need) != NeedBracket.Full) fullSinceMs.remove(need)
        }
    }

    /**
     * Applique une action de la table needs_action_config.
     * @return false si l'action est inconnue ou encore en cooldown.
     */
    fun apply(actionId: String, nowMs: Long): Boolean {
        val d = tuning.actions[actionId] ?: return false
        val last = lastActionMs[actionId]
        if (last != null && nowMs - last < (d.cooldownSec * 1000f).toLong()) return false
        lastActionMs[actionId] = nowMs
        update(nowMs)
        adjust(NeedId.Repair, d.repair, nowMs)
        adjust(NeedId.Energy, d.energy, nowMs)
        adjust(NeedId.Play, d.play, nowMs)
        return true
    }

    private fun adjust(need: NeedId, delta: Float, nowMs: Long) {
        if (delta == 0f) return
        val wasFull = bracket(need) == NeedBracket.Full
        levels[need] = (level(need) + delta).coerceIn(tuning.minLevel, tuning.maxLevel)
        val isFull = bracket(need) == NeedBracket.Full
        when {
            isFull && (delta > 0f || !wasFull) -> fullSinceMs[need] = nowMs
            !isFull -> fullSinceMs.remove(need)
        }
    }

    private fun rateFor(need: NeedId, lvl: Float): Float =
        tuning.connectedDecay[need].orEmpty()
            .sortedByDescending { it.threshold }
            .firstOrNull { lvl >= it.threshold }
            ?.perMinute ?: 0f
}
