package app.holdassistant.core

/** One row of the phone's call log. [name] is the contact name Android cached, if the number is saved. */
data class CallRecord(val number: String, val name: String?, val type: Type, val dateMs: Long, val durationS: Long) {
    enum class Type { INCOMING, OUTGOING, MISSED, OTHER }
}

/** Everything the call log says about one number. */
data class NumberHistory(
    val number: String, // E.164
    val name: String?,
    val outgoing: Int,
    val incoming: Int,
    val longestOutgoingS: Long,
    val lastCallMs: Long,
)

data class HistoryVerdict(val history: NumberHistory, val kind: ContactKind, val score: Int, val reasons: List<String>)

/**
 * Finds the company lines in the call log. Most aren't saved as contacts, so
 * besides the number and any saved name, the strongest signal is how you
 * used it: long outgoing calls are what time on hold looks like.
 */
object CallHistoryClassifier {
    private const val LONG_CALL_S = 10 * 60L
    private const val MEDIUM_CALL_S = 5 * 60L

    /** Group by dialable number; only numbers you've called yourself, most recent first. */
    fun summarize(records: List<CallRecord>): List<NumberHistory> = records
        .mapNotNull { r -> PhoneNumbers.toE164(r.number)?.let { it to r } }
        .groupBy({ it.first }, { it.second })
        .map { (number, calls) ->
            val out = calls.filter { it.type == CallRecord.Type.OUTGOING }
            NumberHistory(
                number = number,
                name = calls.sortedByDescending { it.dateMs }.firstNotNullOfOrNull { it.name?.trim()?.takeIf(String::isNotEmpty) },
                outgoing = out.size,
                incoming = calls.count { it.type == CallRecord.Type.INCOMING },
                longestOutgoingS = out.maxOfOrNull { it.durationS } ?: 0,
                lastCallMs = calls.maxOf { it.dateMs },
            )
        }
        .filter { it.outgoing > 0 }
        .sortedByDescending { it.lastCallMs }

    fun classify(h: NumberHistory): HistoryVerdict {
        var score = 0
        val reasons = mutableListOf<String>()
        fun add(points: Int, why: String) {
            score += points
            reasons += (if (points > 0) "+$points " else "$points ") + why
        }

        if (InstitutionClassifier.isTollFree(h.number.filter(Char::isDigit))) add(3, "toll-free number")

        val name = h.name
        if (name != null) {
            // Reuse the contact rules on the saved name only (brands, "bank", "Mom", ...).
            val v = InstitutionClassifier.classify(ContactInfo("", name))
            v.reasons.forEach { r -> add(r.substringBefore(' ').toInt(), r.substringAfter(' ')) }
            if (v.score == 0) add(-2, "saved under a name with no company signals")
        } else {
            add(1, "not in your contacts")
        }

        when {
            h.longestOutgoingS >= LONG_CALL_S -> add(2, "${h.longestOutgoingS / 60}-minute call (hold?)")
            h.longestOutgoingS >= MEDIUM_CALL_S -> add(1, "${h.longestOutgoingS / 60}-minute call")
        }
        if (name == null && h.incoming >= 3 && h.outgoing >= 3) add(-1, "lots of calls both ways")

        val kind = when {
            score >= InstitutionClassifier.INSTITUTION_AT -> ContactKind.INSTITUTION
            score <= InstitutionClassifier.PERSONAL_AT -> ContactKind.PERSONAL
            else -> ContactKind.UNCLEAR
        }
        return HistoryVerdict(h, kind, score, reasons)
    }

    fun analyze(records: List<CallRecord>): List<HistoryVerdict> = summarize(records).map(::classify)
}
