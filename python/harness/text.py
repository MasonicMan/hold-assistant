"""Text normalization and fuzzy matching shared by the classifier and probe."""

import re

from rapidfuzz import fuzz

_PUNCT = re.compile(r"[^\w\s']")
_SPACE = re.compile(r"\s+")


def normalize(text: str) -> str:
    """Lowercase, drop punctuation (keep apostrophes), collapse whitespace."""
    text = text.lower().replace("’", "'")
    text = _PUNCT.sub(" ", text)
    return _SPACE.sub(" ", text).strip()


def word_count(text: str) -> int:
    return len(text.split())


def repeat_score(segment: str, earlier: str) -> float:
    """0-100 similarity of a new segment to an earlier one.

    Uses partial matching so a segment that STT cut differently (a prefix or
    suffix of an earlier one) still matches.
    """
    if not segment or not earlier:
        return 0.0
    return max(fuzz.ratio(segment, earlier), fuzz.partial_ratio(segment, earlier))


def contains_word(text: str, word: str, threshold: float = 80) -> bool:
    """Fuzzy whole-word search, also trying adjacent word pairs ("pine apple")."""
    tokens = text.split()
    candidates = tokens + [a + b for a, b in zip(tokens, tokens[1:])]
    return any(fuzz.ratio(c, word) >= threshold for c in candidates)
