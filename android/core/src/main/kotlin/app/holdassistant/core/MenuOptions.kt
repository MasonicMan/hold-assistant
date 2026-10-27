package app.holdassistant.core

/** One keypad choice an IVR menu offered, e.g. digit "2" for "billing questions". */
data class MenuOption(val digit: String, val label: String)

/** Pulls "for X, press N" / "press N for X" choices out of menu text for one-tap DTMF buttons. */
object MenuOptions {
    private val DIGITS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "star" to "*", "pound" to "#",
    )
    private const val KEY = "(\\d|zero|one|two|three|four|five|six|seven|eight|nine|star|pound)"
    private val LABEL_FIRST =
        Regex("^\\s*(?:for|to|if)\\s+(.+?),?\\s+(?:please\\s+)?(?:press|dial|say or press|say or enter)\\s+$KEY\\b")
    private val KEY_FIRST = Regex("(?:press|dial)\\s+$KEY\\s+(?:for|to)\\s+(.+)")
    private val SENTENCE = Regex("[.?!;\\n]+")

    fun parse(text: String, maxLabel: Int = 40): List<MenuOption> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lowercase().split(SENTENCE)) {
            val s = raw.trim()
            val (label, key) = LABEL_FIRST.find(s)?.let { it.groupValues[1] to it.groupValues[2] }
                ?: KEY_FIRST.find(s)?.let { it.groupValues[2] to it.groupValues[1] }
                ?: continue
            val digit = DIGITS[key] ?: key
            out.putIfAbsent(digit, tidy(label, maxLabel))
        }
        return out.map { (d, l) -> MenuOption(d, l) }
    }

    private fun tidy(label: String, max: Int): String {
        val l = label.trim().trimEnd(',').removePrefix("a ").removePrefix("the ")
        return if (l.length <= max) l else l.take(max - 1).trimEnd() + "…"
    }
}
