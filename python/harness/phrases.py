"""Curated phrase rules per state. Patterns run against normalize()d text.

Starter lists: check and extend them against real call transcripts. Keep the
regex syntax Java-compatible so the lists can move to the Kotlin app as-is.
"""

import re

_NUM = r"(\d|one|two|three|four|five|six|seven|eight|nine|zero|star|pound)"

_RAW = {
    "human": [
        ("my_name_is", r"\bmy name is\b"),
        ("this_is_name", r"\bthank you for calling\b.{0,40}\bthis is \w+"),
        ("name_speaking", r"\bthis is \w+( speaking| how| can| what)\b"),
        ("youve_got_name", r"\byou'?ve got \w+\b"),
        ("how_can_i_help", r"\bhow (can|may) i (help|assist)( you)?\b"),
        ("what_can_i_do", r"\bwhat can i do for you\b"),
        ("may_i_have", r"\b(may|can|could) i (have|get) (your|the)\b"),
        ("who_am_i_speaking", r"\bwho am i speaking (with|to)\b"),
        ("are_you_there", r"\bare you (still )?there\b"),
        ("bare_hello", r"^(hello|hi|hey)( there)?$"),
        ("hello_intro", r"^(hello|hi|hey)( there)? (this is|my name is|you'?ve got) \w+"),
        ("how_are_you", r"\bhow are you( doing)?( today)?\b"),
        ("give_me_a_sec", r"\bgive me (a|one) (sec|second|moment|minute)\b"),
        ("bear_with_me", r"\bbear with me\b"),
        ("let_me_check", r"\blet me (pull|look|check|see)\b"),
        ("verify_request", r"\bcould you (please )?(verify|confirm|spell)\b"),
    ],
    "ivr_menu": [
        ("press_n", rf"\bpress {_NUM}\b"),
        ("say_or_enter", r"\bsay or (press|enter)\b"),
        ("enter_your", r"\b(please )?enter (your|the)\b"),
        ("for_topic", r"\bfor (billing|sales|support|technical|account|english|new orders|order status)\b"),
        ("calling_about", r"\btell me (what|why) you'?re calling\b"),
        ("you_can_say", r"\byou can say\b"),
        ("in_a_few_words", r"\bin a few words\b"),
        ("didnt_get_that", r"\bi didn'?t (get|catch|understand) that\b"),
        ("please_say", r"\bplease (say|repeat)\b"),
        ("repeat_options", r"\bto (repeat|hear) (these|this|the) (options|menu)\b"),
        ("main_menu", r"\bmain menu\b"),
        ("para_espanol", r"\bpara espa[nñ]ol\b"),
        ("pound_key", r"\bfollowed by the pound\b"),
        ("extension", r"\bif you know your party'?s extension\b"),
        ("confirm_echo", r"\bis that (right|correct)\b"),
    ],
    "hold": [
        ("call_important", r"\bcall is (very )?important\b"),
        ("thanks_holding", r"\bthank you for (holding|your patience|waiting)\b"),
        ("please_hold", r"\bplease (continue to )?hold\b"),
        ("stay_on_line", r"\b(please )?(stay|remain) on the line\b"),
        ("wait_time", r"\bestimated (wait|hold) time\b"),
        ("all_agents_busy", r"\ball (of )?(our )?\w+ are (currently )?(busy|helping|assisting)\b"),
        ("in_order_received", r"\b(answered|received) in the order\b"),
        ("next_available", r"\bnext available\b"),
        ("your_call_will_be", r"\byour call will be\b"),
        ("while_you_wait", r"\bwhile you (wait|hold)\b"),
        ("did_you_know", r"\bdid you know\b"),
        ("visit_online", r"\b(visit|go to) (us )?(online|our website)\b"),
        ("url", r"\b(www|com)\b"),
        ("download_app", r"\bdownload (it|our|the)\b"),
        ("transferring", r"\btransferr?(ing)? your call\b"),
        ("one_moment_while", r"\bone moment while\b"),
        ("please_wait_while", r"\bplease wait while\b"),
    ],
    # Informational; see classifier for how each affects the state.
    "special": [
        ("recorded", r"\b(may|might|will) be (monitored|recorded)\b"),
        ("end_of_menu", r"\b(end of (the|this|our) menu|no (more|further) options)\b"),
        ("high_volume", r"\b(high|higher than (normal|usual)|unusually high) (call )?volume\b"),
        ("callback_offer", r"\b(call you back|callback|call back)\b"),
    ],
}

PATTERNS = {
    state: [(name, re.compile(rx)) for name, rx in rules] for state, rules in _RAW.items()
}


def match(text: str) -> dict[str, list[str]]:
    """Rule names that fire for each state on already-normalized text."""
    return {
        state: [name for name, rx in rules if rx.search(text)]
        for state, rules in PATTERNS.items()
    }
