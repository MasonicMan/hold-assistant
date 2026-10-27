import random

from harness.classifier import Decision, Segment
from harness.probe import IVR_TRIGGER_WORDS, VETTED_KEYWORDS, ProbeController

UNSURE = Decision("unsure", "fallback", 0.0, "")
HOLD = Decision("hold", "phrase", 0.9, "")
MENU = Decision("ivr_menu", "phrase", 0.9, "")


def kinds(events):
    return [e.kind for e in events]


def probe(**kw):
    return ProbeController(enabled=True, keyword="pineapple", **kw)


def test_keywords_are_vetted():
    assert not set(VETTED_KEYWORDS) & IVR_TRIGGER_WORDS
    assert all(len(k) >= 6 for k in VETTED_KEYWORDS)


def test_keyword_random_per_call():
    picks = {ProbeController(rng=random.Random(s)).keyword for s in range(30)}
    assert len(picks) > 3


def test_disabled_by_default():
    p = ProbeController(keyword="pineapple")
    p.observe(Segment(0, "hm"), UNSURE)
    assert p.tick(10) == []


def test_fires_after_unsure_persists_then_confirms():
    p = probe()
    p.observe(Segment(0, "hm"), UNSURE)
    assert p.tick(2) == []
    assert kinds(p.tick(3)) == ["speak"] and p.mic_muted
    assert kinds(p.observe(Segment(5, "uh, pine apple?"), UNSURE)) == ["confirmed_human"]
    assert not p.mic_muted


def test_timeout_then_cooldown_until_unsure_event_ends():
    p = probe()
    p.observe(Segment(0, "hm"), UNSURE)
    p.tick(3)
    assert kinds(p.tick(11)) == ["no_response"]
    assert p.tick(30) == []  # same unsure stretch: no second probe
    p.observe(Segment(31, "please hold"), HOLD)
    p.observe(Segment(40, "hm"), UNSURE)
    assert kinds(p.tick(43)) == ["speak"]


def test_never_probes_right_after_a_menu():
    p = probe(menu_quiet_s=10)
    p.observe(Segment(0, "press 1"), MENU)
    p.observe(Segment(2, "hm"), UNSURE)
    assert p.tick(6) == []
    assert kinds(p.tick(10)) == ["speak"]


def test_echo_gets_reprompt_then_rejected():
    p = probe()
    p.observe(Segment(0, "hm"), UNSURE)
    p.tick(3)
    assert kinds(p.observe(Segment(5, "You said pineapple. Is that correct?"), MENU)) == ["speak"]
    assert kinds(p.observe(Segment(9, "I heard pineapple, is that right?"), MENU)) == ["rejected_echo"]


def test_ivr_reaction_without_keyword_stops_probe():
    p = probe()
    p.observe(Segment(0, "hm"), UNSURE)
    p.tick(3)
    assert kinds(p.observe(Segment(5, "Sorry, I didn't get that."), MENU)) == ["ivr_reacted"]


def test_context_check_hook_can_veto():
    p = probe(context_check=lambda text, kw: False)
    p.observe(Segment(0, "hm"), UNSURE)
    p.tick(3)
    assert kinds(p.observe(Segment(5, "pineapple"), UNSURE)) == ["speak"]
