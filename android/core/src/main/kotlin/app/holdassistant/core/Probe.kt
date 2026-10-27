package app.holdassistant.core

import kotlin.random.Random

/**
 * Verification probe: when UNSURE persists, ask the line to say a keyword.
 * Pure state machine over transcript events; the app turns SPEAK events into
 * TTS audio injected into the call with the user's mic muted.
 */
class ProbeController(
    var enabled: Boolean = false, // opt-in per call: probing can reset or misroute an IVR
    keyword: String? = null,
    random: Random = Random.Default,
    private val unsureAfterS: Double = 3.0,
    private val responseWindowS: Double = 8.0,
    private val menuQuietS: Double = 10.0,
    private val matchThreshold: Double = 80.0,
    /** Optional extra check (e.g. an LLM) that a keyword hit isn't an echo. */
    private val contextCheck: ((text: String, keyword: String) -> Boolean)? = null,
) {
    enum class Kind { SPEAK, CONFIRMED_HUMAN, NO_RESPONSE, REJECTED_ECHO, IVR_REACTED }

    data class Event(val t: Double, val kind: Kind, val detail: String)

    val keyword: String = keyword ?: VETTED_KEYWORDS.random(random)

    init {
        require(this.keyword !in IVR_TRIGGER_WORDS)
    }

    var waiting = false // prompt spoken, listening for the keyword
        private set
    private var deadline = 0.0
    private var reprompted = false
    private var unsureSince: Double? = null
    private var probedThisEvent = false // cooldown: one probe per unsure stretch
    private var lastMenuT: Double? = null
    val events = mutableListOf<Event>()

    val micMuted: Boolean get() = waiting

    fun observe(segment: Segment, decision: Decision): List<Event> {
        val out = mutableListOf<Event>()
        if (decision.state == LineState.IVR_MENU) lastMenuT = segment.t

        when {
            waiting -> out += checkResponse(segment, decision)
            decision.state == LineState.UNSURE -> if (unsureSince == null) unsureSince = segment.t
            else -> {
                unsureSince = null
                probedThisEvent = false
            }
        }
        out += maybeFire(segment.t)
        events += out
        return out
    }

    fun tick(t: Double): List<Event> {
        val out = mutableListOf<Event>()
        if (waiting && t >= deadline) {
            waiting = false
            out += Event(t, Kind.NO_RESPONSE, "keyword not heard; back to hold")
        }
        out += maybeFire(t)
        events += out
        return out
    }

    private fun maybeFire(t: Double): List<Event> {
        val since = unsureSince
        val menu = lastMenuT
        if (!enabled || waiting || since == null || probedThisEvent || t - since < unsureAfterS ||
            (menu != null && t - menu < menuQuietS)
        ) return emptyList()
        probedThisEvent = true
        reprompted = false
        return listOf(speak(t, PROMPT))
    }

    private fun speak(t: Double, template: String): Event {
        waiting = true
        deadline = t + responseWindowS
        return Event(t, Kind.SPEAK, template.replace("{kw}", keyword))
    }

    private fun checkResponse(segment: Segment, decision: Decision): List<Event> {
        val norm = normalize(segment.text)
        val t = segment.t
        if (containsWord(norm, keyword, matchThreshold)) {
            val echo = ECHO.containsMatchIn(norm) || (contextCheck != null && !contextCheck.invoke(segment.text, keyword))
            if (!echo) {
                waiting = false
                return listOf(Event(t, Kind.CONFIRMED_HUMAN, "heard '$keyword'"))
            }
            if (!reprompted) {
                // Ask a second time; a person answers plainly, an IVR echoes again.
                reprompted = true
                return listOf(speak(t, REPROMPT))
            }
            waiting = false
            return listOf(Event(t, Kind.REJECTED_ECHO, "keyword only heard inside IVR echo"))
        }
        if (decision.state == LineState.HUMAN) {
            waiting = false
            return listOf(Event(t, Kind.CONFIRMED_HUMAN, "classifier: ${decision.reason}"))
        }
        if (decision.state == LineState.IVR_MENU) {
            waiting = false
            return listOf(Event(t, Kind.IVR_REACTED, "IVR responded to the prompt; stop probing"))
        }
        return emptyList()
    }

    companion object {
        /** Multi-syllable, common, no homophones, nothing an IVR acts on. */
        val VETTED_KEYWORDS = listOf(
            "pineapple", "umbrella", "elephant", "butterfly", "avocado", "kangaroo",
            "volcano", "telescope", "watermelon", "dinosaur", "broccoli", "pancake",
        )

        /** Words IVRs route on. Never use these as keywords. */
        val IVR_TRIGGER_WORDS = setOf(
            "agent", "representative", "operator", "yes", "no", "billing", "help",
            "menu", "repeat", "cancel", "account", "payment", "support", "sales",
        )

        private val ECHO = Regex(
            "\\b(you said|did you say|i heard|is that (right|correct)|" +
                "i didn'?t (get|catch|understand)|let'?s try (that )?again|please say)\\b",
        )

        const val PROMPT = "Hi, I'm an automated assistant holding for someone. " +
            "Could you say the word {kw} so I can connect them?"
        const val REPROMPT = "This is the automated assistant again. If you're a person, please say the word {kw}."
    }
}
