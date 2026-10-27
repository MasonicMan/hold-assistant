"""Tiered call-state classifier: repeat match -> phrase rules -> LLM.

Feed it transcript segments in order with classify(). It keeps per-call
history for repeat matching and the LLM window.
"""

from dataclasses import dataclass, field
from typing import Callable, Optional

from . import phrases
from .text import normalize, repeat_score, word_count

STATES = ("hold", "ivr_menu", "human", "unsure")
# States that interrupt the user's media. `unsure` alerts on purpose: a false
# alarm costs a second, a missed human costs the user's place in line.
ALERT_STATES = frozenset({"ivr_menu", "human", "unsure"})

# (window of (t, text) pairs, current text) -> (state, confidence, reason) or None
LLMTier = Callable[[list[tuple[float, str]], str], Optional[tuple[str, float, str]]]


@dataclass
class Segment:
    t: float  # start time in seconds from call start
    text: str


@dataclass
class Decision:
    state: str
    tier: str  # silence | repeat | phrase | llm | fingerprint | fallback
    confidence: float
    reason: str
    flags: list[str] = field(default_factory=list)

    @property
    def alerts(self) -> bool:
        return self.state in ALERT_STATES


@dataclass
class _Entry:
    segment: Segment
    norm: str
    decision: Decision


class Classifier:
    def __init__(
        self,
        llm: Optional[LLMTier] = None,
        known_hold: tuple[str, ...] = (),
        known_menu: tuple[str, ...] = (),
        repeat_threshold: float = 90,
        min_repeat_words: int = 6,
        llm_window_s: float = 30,
        llm_min_confidence: float = 0.6,
    ):
        self.llm = llm
        # Fingerprints from earlier calls to the same number.
        self.known = [(normalize(t), "hold") for t in known_hold] + [
            (normalize(t), "ivr_menu") for t in known_menu
        ]
        self.repeat_threshold = repeat_threshold
        self.min_repeat_words = min_repeat_words
        self.llm_window_s = llm_window_s
        self.llm_min_confidence = llm_min_confidence
        self.history: list[_Entry] = []
        self.llm_calls = 0

    def classify(self, segment: Segment) -> Decision:
        norm = normalize(segment.text)
        decision = self._decide(segment, norm)
        self.history.append(_Entry(segment, norm, decision))
        return decision

    def fingerprint(self) -> dict[str, list[str]]:
        """Hold and menu texts from this call, to store for the dialed number."""
        out: dict[str, list[str]] = {"hold": [], "ivr_menu": []}
        for e in self.history:
            if e.norm and e.decision.state in out and e.segment.text not in out[e.decision.state]:
                out[e.decision.state].append(e.segment.text)
        return out

    def _decide(self, segment: Segment, norm: str) -> Decision:
        if not norm:
            return Decision("hold", "silence", 0.6, "no speech (music or silence)")

        hits = phrases.match(norm)
        flags = hits["special"]

        # Tier 1: repeat match. Skipped when a human cue is present, since a
        # person may reuse a phrase the recording also says.
        if not hits["human"]:
            rep = self._repeat_match(norm)
            if rep:
                state, score, source = rep
                return Decision(state, "repeat", 0.95, f"repeats {source} ({score:.0f})", flags)

        # Tier 2: phrase rules.
        phrase = self._phrase_decision(hits)
        if phrase:
            phrase.flags = flags
            return phrase

        # Known number, text matches nothing in its fingerprint: almost
        # certainly a person, no LLM needed.
        if self.known:
            return Decision("human", "fingerprint", 0.7, "novel text vs. known hold loop", flags)

        # Tier 3: LLM on the recent window.
        if self.llm:
            self.llm_calls += 1
            window = [
                (e.segment.t, e.segment.text)
                for e in self.history
                if segment.t - e.segment.t <= self.llm_window_s
            ] + [(segment.t, segment.text)]
            verdict = self.llm(window, segment.text)
            if verdict:
                state, conf, reason = verdict
                if state in STATES and conf >= self.llm_min_confidence:
                    return Decision(state, "llm", conf, reason, flags)
                return Decision("unsure", "llm", conf, f"low confidence {state}: {reason}", flags)

        return Decision("unsure", "fallback", 0.0, "no tier could place it", flags)

    def _repeat_match(self, norm: str) -> Optional[tuple[str, float, str]]:
        if word_count(norm) < self.min_repeat_words:
            return None
        best: Optional[tuple[str, float, str]] = None
        candidates = [(n, s, "fingerprint") for n, s in self.known] + [
            (e.norm, e.decision.state, f"segment at {e.segment.t:g}s")
            for e in self.history
            if e.norm and e.decision.state != "human"
        ]
        for text, state, source in candidates:
            if word_count(text) < self.min_repeat_words:
                continue
            score = repeat_score(norm, text)
            if score >= self.repeat_threshold and (not best or score > best[1]):
                # A repeated menu is still a menu; any other repeat is a recording.
                best = ("ivr_menu" if state == "ivr_menu" else "hold", score, source)
        return best

    @staticmethod
    def _phrase_decision(hits: dict[str, list[str]]) -> Optional[Decision]:
        ivr, human, hold, special = hits["ivr_menu"], hits["human"], hits["hold"], hits["special"]
        # Menus need input, so they win even when a menu says "how can I help".
        if ivr:
            conf = 0.75 if human else 0.9
            return Decision("ivr_menu", "phrase", conf, "menu cues: " + ", ".join(ivr))
        # Human cues override hold cues: people also say "thank you for holding".
        if human:
            conf = 0.75 if hold else 0.9
            return Decision("human", "phrase", conf, "human cues: " + ", ".join(human))
        if hold:
            return Decision("hold", "phrase", 0.9, "hold cues: " + ", ".join(hold))
        if "end_of_menu" in special:
            return Decision("ivr_menu", "phrase", 0.8, "end of menu (often disconnects next)")
        if special:
            return Decision("hold", "phrase", 0.7, "announcement: " + ", ".join(special))
        return None


class AlertTracker:
    """Decides when to interrupt: on each move from hold into an alert state."""

    def __init__(self) -> None:
        self.state = "hold"

    def update(self, state: str) -> bool:
        fire = self.state == "hold" and state in ALERT_STATES
        self.state = state
        return fire
