"""Tier 3: Claude classifier for text the cheaper tiers can't place."""

import logging
from typing import Literal, Optional

import anthropic
from pydantic import BaseModel

log = logging.getLogger(__name__)

SYSTEM = """You classify a live transcript of a phone call to an automated line \
(bank, utility, big company), heard from the caller's side while they wait.

Label the LATEST segment as one of:
- hold: recorded hold messages, ads and promos, transfer notices, wait-time \
announcements, music or silence.
- ivr_menu: an automated menu or prompt that wants the caller's input (press, \
say, enter, "tell me what you're calling about", echo-back confirmations).
- human: a live person speaking (addresses the caller directly, asks for \
details, refers to their specific account or problem, hesitates or uses fillers).
- unsure: you cannot tell.

Recordings are scripted and polished; people are not. Text that repeats earlier \
text nearly verbatim is a recording. If torn between human and hold, answer unsure."""


class Verdict(BaseModel):
    state: Literal["hold", "ivr_menu", "human", "unsure"]
    confidence: float
    reason: str


class ClaudeTier:
    def __init__(self, model: str = "claude-haiku-5-5", client: Optional[anthropic.Anthropic] = None):
        self.model = model
        self.client = client or anthropic.Anthropic()

    def __call__(self, window: list[tuple[float, str]], current: str) -> Optional[tuple[str, float, str]]:
        lines = "\n".join(f"[{t:6.1f}s] {text or '(no speech)'}" for t, text in window)
        try:
            response = self.client.messages.parse(
                model=self.model,
                max_tokens=1024,
                system=SYSTEM,
                output_config={"effort": "low"},
                messages=[{
                    "role": "user",
                    "content": f"Transcript, last ~30 s:\n{lines}\n\nLatest segment: {current!r}\n"
                    "Give state, confidence from 0 to 1, and a short reason.",
                }],
                output_format=Verdict,
            )
        except (anthropic.APIStatusError, anthropic.APIConnectionError) as e:
            log.warning("LLM tier failed, treating as unsure: %s", e)
            return None
        if response.stop_reason == "refusal" or response.parsed_output is None:
            return None
        v = response.parsed_output
        return v.state, v.confidence, v.reason
