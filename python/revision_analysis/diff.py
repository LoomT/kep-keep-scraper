"""Diff two consecutive revisions into add/delete/modify units.

Block-level diff classifies the *part* (code / prose / metadata_header) and the
operation (added / deleted / modified). Within modified or added/deleted prose
blocks, a sentence-level sub-diff feeds ``classify`` for the
rephrase/typo/formatting breakdown.
"""

from __future__ import annotations

from dataclasses import dataclass
from difflib import SequenceMatcher
from typing import List

from .classify import classify_prose_modification, part_category, same_words
from .segment import Block, segment_content, split_sentences

# Above this block size we skip the per-sentence sub-diff: a rewrite of a block
# this large is "substantive" regardless, and the O(n*m) sentence SequenceMatcher
# is the dominant cost on plain-text projects (e.g. C++) where the whole document
# is a single block.
LARGE_PROSE_CHARS = 20000


@dataclass
class ContentChange:
    op: str  # "added" | "deleted" | "modified"
    block_kind: str  # "code" | "prose" | "metadata_header"
    category: str  # final label, e.g. "prose_rephrase"
    prose_subtype: str | None = None  # typo/formatting/rephrase/substantive
    n_sentences: int = 0  # prose sentences touched
    old: str = ""
    new: str = ""


def _sentence_units(old_block: str, new_block: str) -> tuple[int, list[str]]:
    """Subclassify a modified prose block at sentence level.

    Returns (#sentences touched, [subtypes]) where each touched sentence gets a
    subtype. An overall block subtype is derived by the caller.
    """
    old_s, new_s = split_sentences(old_block), split_sentences(new_block)
    sm = SequenceMatcher(a=old_s, b=new_s, autojunk=False)
    subtypes: list[str] = []
    touched = 0
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        if tag == "replace":
            # pair sentences positionally; classify each pair
            for k in range(max(i2 - i1, j2 - j1)):
                o = old_s[i1 + k] if i1 + k < i2 else ""
                n = new_s[j1 + k] if j1 + k < j2 else ""
                touched += 1
                if not o:
                    subtypes.append("substantive")
                elif not n:
                    subtypes.append("substantive")
                else:
                    subtypes.append(classify_prose_modification(o, n))
        elif tag == "insert":
            touched += j2 - j1
            subtypes += ["substantive"] * (j2 - j1)
        elif tag == "delete":
            touched += i2 - i1
            subtypes += ["substantive"] * (i2 - i1)
    return touched, subtypes


def _summarise_subtype(subtypes: list[str]) -> str:
    """Pick a representative subtype for a modified prose block."""
    if not subtypes:
        return "formatting"
    # priority: if everything is formatting/typo it's a light edit; otherwise
    # the "heaviest" change dominates the block label.
    order = ["substantive", "rephrase", "typo", "formatting"]
    for level in order:
        if level in subtypes:
            return level
    return subtypes[0]


def diff_revisions(
        prev_content: str | None,
        curr_content: str | None,
        fmt: str | None = None,
) -> List[ContentChange]:
    """Diff two snapshots and return classified content changes.

    ``fmt`` forces the content format (single-format projects); None detects it
    per revision (multi-format projects).
    """
    prev_blocks = segment_content(prev_content, fmt)
    curr_blocks = segment_content(curr_content, fmt)
    return diff_blocks(prev_blocks, curr_blocks)


def diff_blocks(prev_blocks: List[Block], curr_blocks: List[Block]) -> List[ContentChange]:
    """Diff two already-segmented snapshots."""
    changes: List[ContentChange] = []
    sm = SequenceMatcher(
        a=[b.text for b in prev_blocks], b=[b.text for b in curr_blocks], autojunk=False
    )
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        if tag == "replace":
            changes.extend(_diff_block_span(prev_blocks[i1:i2], curr_blocks[j1:j2]))
        elif tag == "insert":
            for b in curr_blocks[j1:j2]:
                changes.append(_pure(b, "added"))
        elif tag == "delete":
            for b in prev_blocks[i1:i2]:
                changes.append(_pure(b, "deleted"))
    return changes


def _pure(block: Block, op: str) -> ContentChange:
    """An added or deleted whole block."""
    subtype = None
    n_sent = 0
    if block.kind == "prose":
        n_sent = len(split_sentences(block.text))
        subtype = "substantive"
    return ContentChange(
        op=op,
        block_kind=block.kind,
        category=part_category(block.kind, op, subtype),
        prose_subtype=subtype,
        n_sentences=n_sent,
        old=block.raw if op == "deleted" else "",
        new=block.raw if op == "added" else "",
    )


def _diff_block_span(old_blocks: list[Block], new_blocks: list[Block]) -> List[ContentChange]:
    """Handle a ``replace`` opcode: pair blocks positionally, extras add/delete."""
    out: List[ContentChange] = []
    n = max(len(old_blocks), len(new_blocks))
    for k in range(n):
        ob = old_blocks[k] if k < len(old_blocks) else None
        nb = new_blocks[k] if k < len(new_blocks) else None
        if ob is None:
            out.append(_pure(nb, "added"))
        elif nb is None:
            out.append(_pure(ob, "deleted"))
        else:
            out.append(_modify(ob, nb))
    return out


def _modify(ob: Block, nb: Block) -> ContentChange:
    # cross-kind change (e.g. prose -> code): treat as delete+add collapsed to modify
    kind = nb.kind
    subtype = None
    n_sent = 0
    if ob.kind == "prose" and nb.kind == "prose":
        if same_words(ob.raw, nb.raw):
            # only whitespace/punctuation/markup/case changed (e.g. list marker
            # "2)" -> "2."); skip the sentence diff, which such marker changes
            # would mis-split into spurious substantive edits.
            subtype = "formatting"
        elif max(len(ob.raw), len(nb.raw)) > LARGE_PROSE_CHARS:
            # coarse classification for huge blocks (skip the O(n*m) sentence diff)
            subtype = "formatting" if ob.text == nb.text else "substantive"
        else:
            n_sent, subtypes = _sentence_units(ob.raw, nb.raw)
            subtype = _summarise_subtype(subtypes)
    return ContentChange(
        op="modified",
        block_kind=kind,
        category=part_category(kind, "modified", subtype),
        prose_subtype=subtype,
        n_sentences=n_sent,
        old=ob.raw,
        new=nb.raw,
    )
