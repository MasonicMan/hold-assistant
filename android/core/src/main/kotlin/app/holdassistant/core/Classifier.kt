package app.holdassistant.core

/** Call state for the remote side of the line. [wire] matches the harness labels. */
enum class LineState(val wire: String) {
    HOLD("hold"), IVR_MENU("ivr_menu"), HUMAN("human"), UNSURE("unsure");

    /** States that interrupt the user's media. UNSURE alerts on purpose: a false
     *  alarm costs a second, a missed human costs the user's place in line. */
    val alerts: Boolean get() = this != HOLD

    companion object {
        fun fromWire(s: String): LineState? = entries.firstOrNull { it.wire == s }
    }
}

data class Segment(val t: Double, val text: String)

data class Decision(
    val state: LineState,
    val tier: String, // silence | repeat | phrase | llm | fingerprint | fallback
    val confidence: Double,
    val reason: String,
    val flags: List<String> = emptyList(),
)

data class LlmVerdict(val state: LineState, val confidence: Double, val reason: String)

/** Tier 3. Given the recent window of (t, text) and the latest text, or null on failure. */
fun interface LlmTier {
    fun classify(window: List<Pair<Double, String>>, current: String): LlmVerdict?
}

/** Hold and menu texts heard on earlier calls to the same number. */
data class Fingerprint(val hold: List<String> = emptyList(), val ivrMenu: List<String> = emptyList()) {
    val isEmpty: Boolean get() = hold.isEmpty() && ivrMenu.isEmpty()
}

/**
 * Tiered classifier: silence -> repeat match -> phrase rules -> fingerprint
 * novelty -> LLM -> unsure. Feed segments in order; keeps per-call history.
 */
class Classifier(
    private val llm: LlmTier? = null,
    known: Fingerprint = Fingerprint(),
    private val repeatThreshold: Double = 90.0,
    private val minRepeatWords: Int = 6,
    private val llmWindowS: Double = 30.0,
    private val llmMinConfidence: Double = 0.6,
    private val historyS: Double = 900.0,
) {
    private class Entry(val segment: Segment, val norm: String, val decision: Decision)

    private val known: List<Pair<String, LineState>> =
        known.hold.map { normalize(it) to LineState.HOLD } + known.ivrMenu.map { normalize(it) to LineState.IVR_MENU }
    private val history = ArrayDeque<Entry>()
    var llmCalls = 0
        private set

    fun classify(segment: Segment): Decision {
        val norm = normalize(segment.text)
        val decision = decide(segment, norm)
        history.addLast(Entry(segment, norm, decision))
        while (history.isNotEmpty() && segment.t - history.first().segment.t > historyS) history.removeFirst()
        return decision
    }

    /** Hold and menu texts from this call, to store for the dialed number. */
    fun fingerprint(): Fingerprint {
        fun texts(state: LineState) = history
            .filter { it.norm.isNotEmpty() && it.decision.state == state }
            .map { it.segment.text }.distinct()
        return Fingerprint(texts(LineState.HOLD), texts(LineState.IVR_MENU))
    }

    private fun decide(segment: Segment, norm: String): Decision {
        if (norm.isEmpty()) return Decision(LineState.HOLD, "silence", 0.6, "no speech (music or silence)")

        val hits = Phrases.match(norm)
        val flags = hits.special

        // Tier 1: repeat match. Skipped when a human cue is present, since a
        // person may reuse a phrase the recording also says.
        if (hits.human.isEmpty()) {
            repeatMatch(norm)?.let { (state, score, source) ->
                return Decision(state, "repeat", 0.95, "repeats $source (${score.toInt()})", flags)
            }
        }

        // Tier 2: phrase rules.
        phraseDecision(hits)?.let { return it.copy(flags = flags) }

        // Known number, text matches nothing in its fingerprint: almost
        // certainly a person, no LLM needed.
        if (known.isNotEmpty()) {
            return Decision(LineState.HUMAN, "fingerprint", 0.7, "novel text vs. known hold loop", flags)
        }

        // Tier 3: LLM on the recent window.
        if (llm != null) {
            llmCalls++
            val window = history
                .filter { segment.t - it.segment.t <= llmWindowS }
                .map { it.segment.t to it.segment.text } + (segment.t to segment.text)
            val v = llm.classify(window, segment.text)
            if (v != null) {
                return if (v.confidence >= llmMinConfidence) {
                    Decision(v.state, "llm", v.confidence, v.reason, flags)
                } else {
                    Decision(LineState.UNSURE, "llm", v.confidence, "low confidence ${v.state.wire}: ${v.reason}", flags)
                }
            }
        }

        return Decision(LineState.UNSURE, "fallback", 0.0, "no tier could place it", flags)
    }

    private fun repeatMatch(norm: String): Triple<LineState, Double, String>? {
        if (wordCount(norm) < minRepeatWords) return null
        val candidates = known.map { (text, state) -> Triple(text, state, "fingerprint") } +
            history.filter { it.norm.isNotEmpty() && it.decision.state != LineState.HUMAN }
                .map { Triple(it.norm, it.decision.state, "segment at ${fmt(it.segment.t)}s") }
        var best: Triple<LineState, Double, String>? = null
        for ((text, state, source) in candidates) {
            if (wordCount(text) < minRepeatWords) continue
            val score = repeatScore(norm, text)
            if (score >= repeatThreshold && (best == null || score > best.second)) {
                // A repeated menu is still a menu; any other repeat is a recording.
                best = Triple(if (state == LineState.IVR_MENU) LineState.IVR_MENU else LineState.HOLD, score, source)
            }
        }
        return best
    }

    private fun phraseDecision(h: Phrases.Hits): Decision? = when {
        // Menus need input, so they win even when a menu says "how can I help".
        h.ivrMenu.isNotEmpty() -> Decision(
            LineState.IVR_MENU, "phrase", if (h.human.isNotEmpty()) 0.75 else 0.9,
            "menu cues: " + h.ivrMenu.joinToString(),
        )
        // Human cues override hold cues: people also say "thank you for holding".
        h.human.isNotEmpty() -> Decision(
            LineState.HUMAN, "phrase", if (h.hold.isNotEmpty()) 0.75 else 0.9,
            "human cues: " + h.human.joinToString(),
        )
        h.hold.isNotEmpty() -> Decision(LineState.HOLD, "phrase", 0.9, "hold cues: " + h.hold.joinToString())
        "end_of_menu" in h.special ->
            Decision(LineState.IVR_MENU, "phrase", 0.8, "end of menu (often disconnects next)")
        h.special.isNotEmpty() -> Decision(LineState.HOLD, "phrase", 0.7, "announcement: " + h.special.joinToString())
        else -> null
    }

    private fun fmt(t: Double) = if (t == t.toLong().toDouble()) t.toLong().toString() else t.toString()
}

/** Decides when to interrupt: on each move from hold into an alert state. */
class AlertTracker {
    var state = LineState.HOLD
        private set

    fun update(next: LineState): Boolean {
        val fire = state == LineState.HOLD && next.alerts
        state = next
        return fire
    }
}
