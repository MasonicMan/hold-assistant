package app.holdassistant.core

object PhoneNumbers {
    /**
     * E.164 form Twilio can dial ("+18005551212"), assuming North America for
     * numbers without a country code. Null for short codes and other numbers
     * that can't be dialed through Twilio.
     */
    fun toE164(raw: String): String? {
        val plus = raw.trim().startsWith("+")
        val digits = raw.filter(Char::isDigit)
        return when {
            plus && digits.length in 8..15 -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length in 12..15 && digits.startsWith("011") -> "+" + digits.drop(3)
            else -> null
        }
    }

    /** "(800) 555-1212" style for display; falls back to the input. */
    fun pretty(e164: String): String {
        val d = e164.filter(Char::isDigit)
        return if (d.length == 11 && d.startsWith("1")) "(${d.substring(1, 4)}) ${d.substring(4, 7)}-${d.substring(7)}" else e164
    }
}
