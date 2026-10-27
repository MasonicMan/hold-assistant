"""Verification probe: when `unsure` persists, ask the line to say a keyword.

Pure state machine over transcript events, so it can be tested without audio.
The app turns `speak` events into muted-mic TTS injected into the call.
"""

import random
import re
from dataclasses import dataclass
from typing import Callable, Optional

from .classifier import Decision, Segment
from .text import contains_word, normalize

# Multi-syllable, common, no homophones, nothing an IVR acts on.
VETTED_KEYWORDS = (
    "pineapple", "umbrella", "elephant", "butterfly", "avocado", "kangaroo",
    "volcano", "telescope", "watermelon", "dinosaur", "broccoli", "pancake",
)
# Words IVRs route on. Never use these as keywords.
IVR_TRIGGER_WORDS = frozenset({
    "agent", "representative", "operator", "yes", "no", "billing", "help",
    "menu", "repeat", "cancel", "account", "payment", "support", "sales",
})

# Context around a keyword hit that suggests an IVR repeating us back.
_ECHO = re.compile(
    r"\b(you said|did you say|i heard|is that (right|correct)|"
    r"i didn'?t (get|catch|understand)|let'?s try (that )?again|please say)\b"
)

PROMPT = (
    "Hi, I'm an automated assistant holding for someone. "
    "Could you say the word {kw} so I can connect them?"
)
REPROMPT = "This is the automated assistant again. If you're a person, please say the word {kw}."


@dataclass
class ProbeEvent:
    t: float
    kind: str  # speak | confirmed_human | no_response | rejected_echo | ivr_reacted
    detail: str


class ProbeController:
    def __init__(
        self,
        enabled: bool = False,  # opt-in per call: probing can reset or misroute an IVR
        keyword: Optional[str] = None,
        rng: Optional[random.Random] = None,
        unsure_after_s: float = 3.0,
        response_window_s: float = 8.0,
        menu_quiet_s: float = 10.0,
        match_threshold: float = 80,
        # Optional extra check (e.g. an LLM) that a keyword hit isn't an echo.
        context_check: Optional[Callable[[str, str], bool]] = None,
    ):
        self.enabled = enabled
        self.keyword = keyword or (rng or random.Random()).choice(VETTED_KEYWORDS)
        assert self.keyword not in IVR_TRIGGER_WORDS
        self.unsure_after_s = unsure_after_s
        self.response_window_s = response_window_s
        self.menu_quiet_s = menu_quiet_s
        self.match_threshold = match_threshold
        self.context_check = context_check

        self.waiting = False  # prompt spoken, listening for the keyword
        self.deadline = 0.0
        self.reprompted = False
        self.unsure_since: Optional[float] = None
        self.probed_this_event = False  # cooldown: one probe per unsure stretch
        self.last_menu_t: Optional[float] = None
        self.events: list[ProbeEvent] = []

    @property
    def mic_muted(self) -> bool:
        return self.waiting

    def observe(self, segment: Segment, decision: Decision) -> list[ProbeEvent]:
        out: list[ProbeEvent] = []
        if decision.state == "ivr_menu":
            self.last_menu_t = segment.t

        if self.waiting:
            out += self._check_response(segment, decision)
        elif decision.state == "unsure":
            if self.unsure_since is None:
                self.unsure_since = segment.t
        else:
            self.unsure_since = None
            self.probed_this_event = False

        out += self._maybe_fire(segment.t)
        self.events += out
        return out

    def tick(self, t: float) -> list[ProbeEvent]:
        out: list[ProbeEvent] = []
        if self.waiting and t >= self.deadline:
            self.waiting = False
            out.append(ProbeEvent(t, "no_response", "keyword not heard; back to hold"))
        out += self._maybe_fire(t)
        self.events += out
        return out

    def _maybe_fire(self, t: float) -> list[ProbeEvent]:
        if (
            not self.enabled
            or self.waiting
            or self.unsure_since is None
            or self.probed_this_event
            or t - self.unsure_since < self.unsure_after_s
            or (self.last_menu_t is not None and t - self.last_menu_t < self.menu_quiet_s)
        ):
            return []
        self.probed_this_event = True
        self.reprompted = False
        return [self._speak(t, PROMPT)]

    def _speak(self, t: float, template: str) -> ProbeEvent:
        self.waiting = True
        self.deadline = t + self.response_window_s
        return ProbeEvent(t, "speak", template.format(kw=self.keyword))

    def _check_response(self, segment: Segment, decision: Decision) -> list[ProbeEvent]:
        norm = normalize(segment.text)
        t = segment.t
        if contains_word(norm, self.keyword, self.match_threshold):
            echo = bool(_ECHO.search(norm)) or (
                self.context_check is not None and not self.context_check(segment.text, self.keyword)
            )
            if not echo:
                self.waiting = False
                return [ProbeEvent(t, "confirmed_human", f"heard {self.keyword!r}")]
            if not self.reprompted:
                # Ask a second time; a person answers plainly, an IVR echoes again.
                self.reprompted = True
                return [self._speak(t, REPROMPT)]
            self.waiting = False
            return [ProbeEvent(t, "rejected_echo", "keyword only heard inside IVR echo")]
        if decision.state == "human":
            self.waiting = False
            return [ProbeEvent(t, "confirmed_human", f"classifier: {decision.reason}")]
        if decision.state == "ivr_menu":
            self.waiting = False
            return [ProbeEvent(t, "ivr_reacted", "IVR responded to the prompt; stop probing")]
        return []
