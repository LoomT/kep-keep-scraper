"""Heuristic classification of prose modifications.

A modified prose unit (sentence or block) is subclassified as:

    - ``formatting`` : differs only in whitespace/markup; text is identical
    - ``typo``       : tiny edit in a single token
    - ``rephrase``   : reworded but substantially overlapping content
    - ``substantive``: large content change
"""

from __future__ import annotations

import re
from difflib import SequenceMatcher

# tuning knobs ------------------------------------------------------------- #
TYPO_MAX_CHANGED_TOKENS = 1
TYPO_MAX_CHARDIST = 3
REPHRASE_MIN_SIMILARITY = 0.55  # token-set similarity above this -> rephrase

_WORD = re.compile(r"\w+")
_MARKUP = re.compile(r"[*_`#>\[\]()~|+-]")


def _strip_markup(text: str) -> str:
    """Lower-case, drop markup chars, and collapse whitespace so that changes
    which only add markup (e.g. ``code`` spans) or reflow whitespace compare
    equal and are classified as formatting."""
    return re.sub(r"\s+", " ", _MARKUP.sub("", text)).strip().lower()


def _tokens(text: str) -> list[str]:
    return _WORD.findall(text.lower())


def same_words(a: str, b: str) -> bool:
    """True if two texts have identical word-token sequences, i.e., they differ
    only in whitespace, punctuation, markup, or case -> a formatting-only change.
    Used to short-circuit a modified prose block before the sentence-level diff,
    which list-marker changes (e.g. ``2)`` -> ``2.``) would otherwise mis-split."""
    return _tokens(a) == _tokens(b)


def _char_distance(a: str, b: str) -> int:
    """Levenshtein distance, only computed for short strings (typo check)."""
    if a == b:
        return 0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(
                min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
            )
        prev = cur
    return prev[-1]


def classify_prose_modification(old_text: str, new_text: str) -> str:
    """Return one of formatting / typo / rephrase / substantive."""
    old_n, new_n = old_text.strip(), new_text.strip()
    if old_n == new_n:
        return "formatting"

    # formatting-only: identical once markup + case are removed
    if _strip_markup(old_n) == _strip_markup(new_n):
        return "formatting"

    old_tok, new_tok = _tokens(old_n), _tokens(new_n)

    # if no word tokens changed, the diff was only whitespace/markup -> formatting
    sm = SequenceMatcher(a=old_tok, b=new_tok, autojunk=False)
    changed_old, changed_new = [], []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag != "equal":
            changed_old += old_tok[i1:i2]
            changed_new += new_tok[j1:j2]
    if not changed_old and not changed_new:
        return "formatting"

    # typo: a single token changed by a tiny edit distance
    if (
            len(changed_old) <= TYPO_MAX_CHANGED_TOKENS
            and len(changed_new) <= TYPO_MAX_CHANGED_TOKENS
            and _char_distance(" ".join(changed_old), " ".join(changed_new))
            <= TYPO_MAX_CHARDIST
    ):
        return "typo"

    # rephrase vs substantive: token-set Jaccard similarity
    sa, sb = set(old_tok), set(new_tok)
    if sa or sb:
        jaccard = len(sa & sb) / len(sa | sb)
        if jaccard >= REPHRASE_MIN_SIMILARITY:
            return "rephrase"
    return "substantive"


def part_category(block_kind: str, op: str, prose_subtype: str | None = None) -> str:
    """Compose the final category label for a content change."""
    if block_kind == "code":
        return f"code_{op}"  # code_added / code_deleted / code_modified
    if block_kind == "metadata_header":
        return f"metadata_header_{op}"
    if op == "modified" and prose_subtype:
        return f"prose_{prose_subtype}"  # prose_typo / prose_rephrase / ...
    return f"prose_{op}"
