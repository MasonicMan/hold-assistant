package app.holdassistant.core

/**
 * Curated phrase rules per state, run against [normalize]d text.
 * Kept in sync with python/harness/phrases.py, which the harness tunes first.
 */
object Phrases {
    private const val NUM = "(\\d|one|two|three|four|five|six|seven|eight|nine|zero|star|pound)"

    val human = rules(
        "my_name_is" to "\\bmy name is\\b",
        "this_is_name" to "\\bthank you for calling\\b.{0,40}\\bthis is \\w+",
        "name_speaking" to "\\bthis is \\w+( speaking| how| can| what)\\b",
        "youve_got_name" to "\\byou'?ve got \\w+\\b",
        "how_can_i_help" to "\\bhow (can|may) i (help|assist)( you)?\\b",
        "what_can_i_do" to "\\bwhat can i do for you\\b",
        "may_i_have" to "\\b(may|can|could) i (have|get) (your|the)\\b",
        "who_am_i_speaking" to "\\bwho am i speaking (with|to)\\b",
        "are_you_there" to "\\bare you (still )?there\\b",
        "bare_hello" to "^(hello|hi|hey)( there)?$",
        "hello_intro" to "^(hello|hi|hey)( there)? (this is|my name is|you'?ve got) \\w+",
        "how_are_you" to "\\bhow are you( doing)?( today)?\\b",
        "give_me_a_sec" to "\\bgive me (a|one) (sec|second|moment|minute)\\b",
        "bear_with_me" to "\\bbear with me\\b",
        "let_me_check" to "\\blet me (pull|look|check|see)\\b",
        "verify_request" to "\\bcould you (please )?(verify|confirm|spell)\\b",
    )

    val ivrMenu = rules(
        "press_n" to "\\bpress $NUM\\b",
        "say_or_enter" to "\\bsay or (press|enter)\\b",
        "enter_your" to "\\b(please )?enter (your|the)\\b",
        "for_topic" to "\\bfor (billing|sales|support|technical|account|english|new orders|order status)\\b",
        "calling_about" to "\\btell me (what|why) you'?re calling\\b",
        "you_can_say" to "\\byou can say\\b",
        "in_a_few_words" to "\\bin a few words\\b",
        "didnt_get_that" to "\\bi didn'?t (get|catch|understand) that\\b",
        "please_say" to "\\bplease (say|repeat)\\b",
        "repeat_options" to "\\bto (repeat|hear) (these|this|the) (options|menu)\\b",
        "main_menu" to "\\bmain menu\\b",
        "para_espanol" to "\\bpara espa[nñ]ol\\b",
        "pound_key" to "\\bfollowed by the pound\\b",
        "extension" to "\\bif you know your party'?s extension\\b",
        "confirm_echo" to "\\bis that (right|correct)\\b",
    )

    val hold = rules(
        "call_important" to "\\bcall is (very )?important\\b",
        "thanks_holding" to "\\bthank you for (holding|your patience|waiting)\\b",
        "please_hold" to "\\bplease (continue to )?hold\\b",
        "stay_on_line" to "\\b(please )?(stay|remain) on the line\\b",
        "wait_time" to "\\bestimated (wait|hold) time\\b",
        "all_agents_busy" to "\\ball (of )?(our )?\\w+ are (currently )?(busy|helping|assisting)\\b",
        "in_order_received" to "\\b(answered|received) in the order\\b",
        "next_available" to "\\bnext available\\b",
        "your_call_will_be" to "\\byour call will be\\b",
        "while_you_wait" to "\\bwhile you (wait|hold)\\b",
        "did_you_know" to "\\bdid you know\\b",
        "visit_online" to "\\b(visit|go to) (us )?(online|our website)\\b",
        "url" to "\\b(www|com)\\b",
        "download_app" to "\\bdownload (it|our|the)\\b",
        "transferring" to "\\btransferr?(ing)? your call\\b",
        "one_moment_while" to "\\bone moment while\\b",
        "please_wait_while" to "\\bplease wait while\\b",
    )

    val special = rules(
        "recorded" to "\\b(may|might|will) be (monitored|recorded)\\b",
        "end_of_menu" to "\\b(end of (the|this|our) menu|no (more|further) options)\\b",
        "high_volume" to "\\b(high|higher than (normal|usual)|unusually high) (call )?volume\\b",
        "callback_offer" to "\\b(call you back|callback|call back)\\b",
    )

    data class Hits(
        val human: List<String>,
        val ivrMenu: List<String>,
        val hold: List<String>,
        val special: List<String>,
    )

    fun match(normalized: String) = Hits(
        human.hits(normalized), ivrMenu.hits(normalized), hold.hits(normalized), special.hits(normalized),
    )

    private fun rules(vararg pairs: Pair<String, String>) = pairs.map { (name, rx) -> name to Regex(rx) }

    private fun List<Pair<String, Regex>>.hits(text: String) =
        filter { (_, rx) -> rx.containsMatchIn(text) }.map { it.first }
}
