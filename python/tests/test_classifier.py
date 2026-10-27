from harness.classifier import AlertTracker, Classifier, Segment
from harness.phrases import PATTERNS

HOLD = "Your call is important to us. Please stay on the line and your call will be answered in the order it was received."


def run(clf, *texts):
    return [clf.classify(Segment(i * 10.0, t)) for i, t in enumerate(texts)]


def test_silence_is_hold():
    assert run(Classifier(), "")[0].state == "hold"


def test_repeat_match_catches_uncued_recording():
    ad = "Example Wireless now offers unlimited plans starting at thirty five dollars a month."
    first, second = run(Classifier(), ad, ad)
    assert first.state == "unsure"
    assert (second.state, second.tier) == ("hold", "repeat")


def test_repeat_match_tolerates_stt_cuts():
    d = run(Classifier(), HOLD, "stay on the line and your call will be answered in the order")[1]
    assert (d.state, d.tier) == ("hold", "repeat")


def test_repeated_menu_stays_menu():
    menu = "For account balances press 1. For billing questions press 2. For anything else press 0."
    assert run(Classifier(), menu, menu)[1].state == "ivr_menu"


def test_human_cue_overrides_hold_phrase_and_repeat():
    d = run(Classifier(), "Thank you for holding.", "Thank you for holding, my name is Ana.")[1]
    assert d.state == "human"


def test_menu_wins_over_human_cue():
    d = run(Classifier(), "How can I help you today? You can say things like pay my bill.")[0]
    assert d.state == "ivr_menu"


def test_ivr_greeting_with_hello_is_not_human():
    d = run(Classifier(), "Hello, and thank you for calling Example Bank.")[0]
    assert d.state != "human"


def test_end_of_menu_alerts():
    assert run(Classifier(), "You have reached the end of this menu.")[0].alerts


def test_special_flags_reported():
    d = run(Classifier(), "This call may be recorded for quality purposes.")[0]
    assert d.state == "hold" and "recorded" in d.flags


def test_llm_only_for_unplaced_text_and_low_confidence_is_unsure():
    calls = []

    def llm(window, current):
        calls.append(window)
        return ("human", 0.4, "maybe")

    clf = Classifier(llm=llm)
    decisions = run(clf, HOLD, "it shows it shipped on the fourth")
    assert decisions[0].tier == "phrase"
    assert decisions[1].state == "unsure" and decisions[1].tier == "llm"
    assert len(calls) == 1 and calls[0][-1][1] == "it shows it shipped on the fourth"


def test_llm_failure_falls_back_to_unsure():
    d = run(Classifier(llm=lambda w, c: None), "it shows it shipped on the fourth")[0]
    assert d.state == "unsure"


def test_fingerprint_skips_llm():
    def llm(window, current):
        raise AssertionError("LLM should not be called")

    clf = Classifier(llm=llm, known_hold=(HOLD,))
    d1, d2 = run(clf, HOLD, "okay I've got your account up now")
    assert d1.state == "hold" and d2.state == "human" and d2.tier == "fingerprint"


def test_fingerprint_export():
    clf = Classifier()
    run(clf, HOLD, "For billing press 1.", "", "Hi, my name is Sam.")
    assert clf.fingerprint() == {"hold": [HOLD], "ivr_menu": ["For billing press 1."]}


def test_alert_tracker_fires_on_leaving_hold_only():
    t = AlertTracker()
    assert [t.update(s) for s in ("hold", "ivr_menu", "unsure", "hold", "human")] == [False, True, False, False, True]


def test_patterns_avoid_lookbehind_for_kotlin_port():
    for rules in PATTERNS.values():
        for name, rx in rules:
            assert "(?<" not in rx.pattern, name
