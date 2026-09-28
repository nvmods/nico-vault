package fr.nvmods.cozmo.personality

/**
 * Charge un cozmo_personality.json (produit par extract_cozmo_personality.py
 * à partir de l'APK de l'utilisateur) par-dessus les valeurs intégrées.
 *
 * Toute clé absente ou mal formée garde la valeur de base : un fichier
 * partiel ou d'une autre version ne peut pas casser le moteur.
 */
object PersonalityTuningLoader {

    fun fromJson(
        text: String,
        base: PersonalityTuning = PersonalityTuning.DEFAULT,
        sourceLabel: String = "cozmo_personality.json"
    ): PersonalityTuning {
        val root = MiniJson.parse(text).obj() ?: error("Racine JSON invalide")
        val mood = root["mood"].obj()
        val needs = root["needs"].obj()

        return base.copy(
            mood = loadMood(mood, base.mood),
            needs = loadNeeds(needs, base.needs),
            frustration = loadFrustration(root["reactionTriggers"].arr(), base.frustration),
            activities = loadActivities(root["activities"].obj()?.get("sub").obj(), base.activities),
            source = sourceLabel
        )
    }

    private fun emotion(name: String): EmotionType? =
        EmotionType.entries.firstOrNull { it.name == name }

    private fun curve(v: Any?): Curve? {
        val pts = v.arr()?.mapNotNull { p ->
            val xy = p.arr() ?: return@mapNotNull null
            val x = xy.getOrNull(0).num() ?: return@mapNotNull null
            val y = xy.getOrNull(1).num() ?: return@mapNotNull null
            x to y
        }
        return if (pts.isNullOrEmpty()) null else Curve(pts)
    }

    private fun loadMood(m: Map<String, Any?>?, base: MoodTuning): MoodTuning {
        if (m == null) return base
        val curves = m["decayCurves"].obj().orEmpty()
        val decay = base.decay.toMutableMap()
        curves.forEach { (k, v) ->
            val e = emotion(k) ?: return@forEach
            curve(v)?.let { decay[e] = it }
        }
        val events = base.events.toMutableMap()
        m["events"].obj()?.forEach { (name, v) ->
            val aff = v.obj()?.get("affectors").obj() ?: return@forEach
            val parsed = aff.mapNotNull { (k, x) ->
                val e = emotion(k) ?: return@mapNotNull null
                val f = x.num() ?: return@mapNotNull null
                e to f
            }.toMap()
            if (parsed.isNotEmpty()) events[name] = parsed
        }
        return base.copy(
            defaultDecay = curve(curves["default"]) ?: base.defaultDecay,
            decay = decay,
            repetitionPenalty = curve(m["repetitionPenalty"]) ?: base.repetitionPenalty,
            events = events
        )
    }

    private fun loadNeeds(n: Map<String, Any?>?, base: NeedsTuning): NeedsTuning {
        if (n == null) return base
        val cfg = n["config"].obj().orEmpty()

        fun f(key: String) = cfg[key].num()

        val brackets = base.brackets.mapValues { (need, b) ->
            b.mapValues { (bracket, v) ->
                f("BracketLevel${need.name}${bracket.name}") ?: v
            }
        }
        val rates = n["decay"].obj()?.get("DecayRates").obj()
        val decay = base.connectedDecay.mapValues { (need, steps) ->
            rates?.get("ConnectedDecayRates${need.name}").arr()?.mapNotNull { s ->
                val o = s.obj() ?: return@mapNotNull null
                val t = o["Threshold"].num() ?: return@mapNotNull null
                val r = o["DecayPerMinute"].num() ?: return@mapNotNull null
                DecayStep(t, r)
            }?.takeIf { it.isNotEmpty() }?.sortedByDescending { it.threshold } ?: steps
        }
        val actions = base.actions.toMutableMap()
        n["actionDeltas"].obj()?.forEach { (id, v) ->
            val o = v.obj() ?: return@forEach
            actions[id] = NeedsActionDelta(
                repair = o["repairDelta"].num() ?: 0f,
                energy = o["energyDelta"].num() ?: 0f,
                play = o["playDelta"].num() ?: 0f,
                cooldownSec = o["cooldownSecs"].num() ?: 0f
            )
        }
        return base.copy(
            minLevel = f("MinimumNeedLevel") ?: base.minLevel,
            maxLevel = f("MaximumNeedLevel") ?: base.maxLevel,
            initial = base.initial.mapValues { (need, v) -> f("InitialNeedLevel${need.name}") ?: v },
            brackets = brackets,
            fullnessCooldownSec = base.fullnessCooldownSec.mapValues { (need, v) ->
                f("FullnessDecayCooldown${need.name}") ?: v
            },
            connectedDecay = decay,
            actions = actions
        )
    }

    private fun loadFrustration(list: List<Any?>?, base: FrustrationTuning): FrustrationTuning {
        var t = base
        list?.forEach { item ->
            val o = item.obj() ?: return@forEach
            if (o["reactionTrigger"] != "Frustration") return@forEach
            val p = o["frustrationParams"].obj() ?: return@forEach
            when (o["behaviorID"]) {
                "ReactToFrustrationMinor" -> t = t.copy(
                    minorMaxConfident = p["maxConfidence"].num() ?: t.minorMaxConfident,
                    minorCooldownSec = p["cooldownTime_s"].num() ?: t.minorCooldownSec
                )
                "ReactToFrustrationMajor" -> t = t.copy(
                    majorMaxConfident = p["maxConfidence"].num() ?: t.majorMaxConfident
                )
            }
        }
        return t
    }

    private fun loadActivities(
        sub: Map<String, Any?>?,
        base: Map<Activity, ActivityTiming>
    ): Map<Activity, ActivityTiming> {
        if (sub == null) return base
        return base.mapValues { (activity, timing) ->
            val strategy = sub[activity.name].obj()?.get("activityStrategy").obj()
                ?: return@mapValues timing
            ActivityTiming(
                shouldEndSec = strategy["activityShouldEndDurationSecs"].num() ?: timing.shouldEndSec,
                cooldownSec = strategy["cooldownBaseSecs"].num() ?: timing.cooldownSec
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
    private fun Any?.arr(): List<Any?>? = this as? List<Any?>
    private fun Any?.num(): Float? = (this as? Number)?.toFloat()
}

/** Parseur JSON minimal (objets, tableaux, nombres, chaînes, bool, null). */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "Contenu JSON inattendu à ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "Fin de JSON inattendue" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Caractère JSON invalide '$c' à $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Littéral invalide à $i" }
            i += word.length
            return v
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                require(i < s.length) { "Chaîne non terminée" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            val out = mutableListOf<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("',' ou ']' attendu à ${i - 1}")
                }
            }
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i++] == ':') { "':' attendu à ${i - 1}" }
                out[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("',' ou '}' attendu à ${i - 1}")
                }
            }
        }
    }
}
