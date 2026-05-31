"""Per-revision format detection and block/sentence segmentation.

A proposal snapshot is split into typed *blocks*:

    - ``code``            : MD/RST/HTML code blocks
    - ``metadata_header`` : YAML/RST front-matter and ``Key: value`` headers
    - ``prose``           : everything else (paragraphs, lists, headings)

Format is detected **per revision** (not per project): the JavaScript project
mixes HTML and markdown, and some proposals transition md -> html across
revisions, so a fixed per-project format would mis-segment them.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List

import pysbd

Format = str  # "markdown" | "rst" | "html" | "plain"
BlockKind = str  # "code" | "metadata_header" | "prose"


@dataclass(frozen=True)
class Block:
    kind: BlockKind
    text: str  # normalized text used for diffing/equality
    raw: str  # original text (with markup), for inspection


# Format detection

_HTML_TAG = re.compile(r"</?(p|div|h[1-6]|pre|code|ul|ol|li|table|span|a)\b", re.I)
_RST_UNDERLINE = re.compile(r"^[=\-~`:'\"^*+#]{3,}\s*$", re.M)
_RST_DIRECTIVE = re.compile(r"^\.\.\s+\w[\w-]*::", re.M)
_MD_FENCE = re.compile(r"^\s*(```|~~~)", re.M)
_MD_HEADING = re.compile(r"^#{1,6}\s+\S", re.M)

# Projects that author proposals in a single, fixed format.
# Projects NOT listed here are multi-format and fall back to per-revision detection:
#   3 JavaScript (HTML + Markdown),
#   4 C++ (plain text + HTML),
#   6 OpenJDK (Markdown + reStructuredText).
PROJECT_FORMATS: dict[int, Format] = {
    0: "rst",  # Python PEPs
    1: "markdown",  # Pandas PDEPs
    2: "rst",  # NumPy NEPs
    5: "markdown",  # Rust RFCs
    7: "markdown",  # Swift evolution
    8: "markdown",  # Kubernetes KEPs
    9: "markdown",  # Kotlin KEEPs
}


def format_for_project(project_id: int | None) -> Format | None:
    """Fixed format for a single-format project, else None (detect per revision)."""
    return PROJECT_FORMATS.get(project_id) if project_id is not None else None


def detect_format(content: str) -> Format:
    if not content or not content.strip():
        return "plain"
    sample = content[:4000]
    html_hits = len(_HTML_TAG.findall(sample))
    if html_hits >= 3:
        return "html"
    if _MD_FENCE.search(sample) or _MD_HEADING.search(sample):
        return "markdown"
    # RST: underlined section titles or directives, but not the markdown `---`
    rst_hits = len(_RST_UNDERLINE.findall(sample)) + len(_RST_DIRECTIVE.findall(sample))
    if rst_hits >= 2:
        return "rst"
    if html_hits >= 1:
        return "html"
    return "plain"


def segment_content(content: str | None, fmt: Format | None = None) -> List[Block]:
    """Split a snapshot into typed blocks using a format-appropriate splitter.

    Pass ``fmt`` to force a format (single-format projects); leave it None to
    detect per revision (multi-format projects).
    """
    if not content:
        return []
    content = content.replace("\r\n", "\n").replace("\r", "\n")
    if fmt is None:
        fmt = detect_format(content)
    if fmt == "html":
        return _segment_html(content)
    if fmt == "rst":
        return _segment_rst(content)
    # markdown and plain share the fence/indent-aware paragraph splitter
    return _segment_markdownish(content, allow_fences=(fmt == "markdown"))


# Helpers

_META_LINE = re.compile(r"^[A-Z][A-Za-z _-]{1,30}:\s+\S")  # "Author: ...", "Status: ..."
_RST_FIELD = re.compile(r"^:[A-Za-z][\w -]*:\s+\S")  # ":Author: ..."


def _normalize(text: str) -> str:
    """Collapse whitespace so formatting-only diffs are detectable separately."""
    return re.sub(r"\s+", " ", text).strip()


def _is_metadata_paragraph(para: str, fmt_fields: bool = False) -> bool:
    lines = [ln for ln in para.splitlines() if ln.strip()]
    if not lines:
        return False
    pat = _RST_FIELD if fmt_fields else _META_LINE
    hits = sum(1 for ln in lines if pat.match(ln))
    return hits >= max(1, len(lines) // 2) and hits >= 1


_ATX_HEADING = re.compile(r"^\s{0,3}#{1,6}\s+\S")
_UNDERLINE_LINE = re.compile(r"^[=\-~`:'\"^*+#]{3,}\s*$")


def _paragraph_units(text: str) -> List[str]:
    """Split a prose region into units on blank lines, ATX headings and RST
    underlines, so documents that omit blank lines (e.g. NumPy NEPs) still
    segment into sections instead of one giant block."""
    lines = text.split("\n")
    units: List[str] = []
    cur: List[str] = []

    def flush() -> None:
        if any(ln.strip() for ln in cur):
            units.append("\n".join(cur))
        cur.clear()

    i = 0
    while i < len(lines):
        ln = lines[i]
        if not ln.strip():
            flush()
            i += 1
            continue
        if _ATX_HEADING.match(ln):
            flush()
            units.append(ln)
            i += 1
            continue
        # title line followed by an RST underline row -> a section heading unit
        if i + 1 < len(lines) and _UNDERLINE_LINE.match(lines[i + 1]):
            flush()
            units.append(ln + "\n" + lines[i + 1])
            i += 2
            continue
        cur.append(ln)
        i += 1
    flush()
    return units


def _split_frontmatter(content: str) -> tuple[str | None, str]:
    """Peel a leading ``---``-delimited YAML front-matter block, if present."""
    m = re.match(r"^---\s*\n(.*?\n)---\s*\n", content, re.S)
    if m:
        return content[: m.end()], content[m.end():]
    return None, content


# Markdown / plain

_FENCE_BLOCK = re.compile(r"(?ms)^[ \t]*(```|~~~).*?^[ \t]*\1[ \t]*$")
_BULLET = re.compile(r"^\s*[*+-]\s+\S")


def _looks_like_bullet_metadata(para: str) -> bool:
    """A KEEP/Swift-style metadata bullet list: a contiguous block of bullets,
    right after a title heading most carrying a ``Key: value``
    (e.g. ``* **Status**: Stable`` for KEEP or ``* Status: Awaiting Review`` for Swift).
    """
    lines = [ln for ln in para.splitlines() if ln.strip()]
    bullets = [ln for ln in lines if _BULLET.match(ln)]
    if len(bullets) < 2:
        return False
    # most lines must be bullets (allow a few wrapped continuation lines)
    if len(bullets) < max(2, (len(lines) + 1) // 2):
        return False
    with_colon = sum(1 for b in bullets if ":" in b)
    return with_colon >= max(2, (len(bullets) + 1) // 2)


def _is_heading_unit(para: str) -> bool:
    return bool(_ATX_HEADING.match(para)) or bool(
        len(para.splitlines()) == 2 and _UNDERLINE_LINE.match(para.splitlines()[-1])
    )


def _segment_markdownish(content: str, allow_fences: bool) -> List[Block]:
    blocks: List[Block] = []

    front, body = _split_frontmatter(content)
    if front is not None:
        blocks.append(Block("metadata_header", _normalize(front), front))

    # First, isolate fenced code blocks so blank lines inside them don't split.
    spans: list[tuple[int, int, bool]] = []  # (start, end, is_code)
    if allow_fences:
        idx = 0
        for m in _FENCE_BLOCK.finditer(body):
            if m.start() > idx:
                spans.append((idx, m.start(), False))
            spans.append((m.start(), m.end(), True))
            idx = m.end()
        if idx < len(body):
            spans.append((idx, len(body), False))
    else:
        spans.append((0, len(body), False))

    first_prose_seen = False
    seen_body = False  # any non-heading body block yet?
    headings_seen = 0  # metadata may only precede the 2nd heading (after the title)
    for start, end, is_code in spans:
        chunk = body[start:end]
        if is_code:
            seen_body = True
            blocks.append(Block("code", _normalize(chunk), chunk))
            continue
        for para in _paragraph_units(chunk):
            if not para.strip():
                continue
            is_heading = _is_heading_unit(para)
            if is_heading:
                headings_seen += 1
            # KEEP/Swift metadata bullet list: the leading bullet block after the
            # H1 title and before any further heading (mirrors KeepMapper).
            elif (
                    not seen_body
                    and headings_seen <= 1
                    and _looks_like_bullet_metadata(para)
            ):
                blocks.append(Block("metadata_header", _normalize(para), para))
                seen_body = True
                continue
            # leading metadata header block (e.g., inline RST-ish "Author:" lines)
            elif not first_prose_seen and _is_metadata_paragraph(para):
                blocks.append(Block("metadata_header", _normalize(para), para))
                continue
            # indented (4-space / tab) code paragraph
            elif _is_indented_code(para):
                seen_body = True
                blocks.append(Block("code", _normalize(para), para))
                continue
            if not is_heading:
                first_prose_seen = True
                seen_body = True
            blocks.append(Block("prose", _normalize(para), para))
    return blocks


def _is_indented_code(para: str) -> bool:
    lines = [ln for ln in para.splitlines() if ln.strip()]
    if len(lines) < 2:
        return False
    indented = sum(1 for ln in lines if ln.startswith("    ") or ln.startswith("\t"))
    # avoid treating markdown bullet/numbered lists as code
    listish = sum(1 for ln in lines if re.match(r"^\s*([*+-]|\d+\.)\s", ln))
    return indented == len(lines) and listish < len(lines)


# reStructuredText

def _segment_rst(content: str) -> List[Block]:
    blocks: List[Block] = []
    paras = _paragraph_units(content)
    first_prose_seen = False
    expect_code = False
    for para in paras:
        if not para.strip():
            continue
        if expect_code or _RST_DIRECTIVE.match(para.strip()):
            blocks.append(Block("code", _normalize(para), para))
            expect_code = False
            continue
        if not first_prose_seen and (
                _is_metadata_paragraph(para) or _is_metadata_paragraph(para, fmt_fields=True)
        ):
            blocks.append(Block("metadata_header", _normalize(para), para))
            continue
        # In RST a literal block is only introduced by "::" or a code directive
        # (handled via expect_code above); bare indentation is just wrapped prose,
        # so we deliberately do NOT treat indented paragraphs as code here.
        first_prose_seen = True
        blocks.append(Block("prose", _normalize(para), para))
        # a paragraph ending in "::" introduces a literal (code) block
        expect_code = para.rstrip().endswith("::")
    return blocks



# HTML

_PRE_CODE = re.compile(r"(?is)<(pre|code)\b.*?>.*?</\1>")
_TAG = re.compile(r"(?s)<[^>]+>")
# Metadata block, e.g. JavaScript (TC39) specs. The class
# attribute may be double-quoted, single-quoted, or unquoted:
#   <pre class="metadata">..., <pre class=metadata>title: ...\nstage: ...</pre>
_META_PRE_OPEN = re.compile(
    r'(?is)^<pre\b[^>]*\bclass\s*=\s*'
    r'(?:"[^"]*\bmetadata\b[^"]*"|\'[^\']*\bmetadata\b[^\']*\'|[^\s>]*\bmetadata\b[^\s>]*)'
)


def _segment_html(content: str) -> List[Block]:
    blocks: List[Block] = []
    idx = 0
    pieces: list[tuple[str, bool]] = []
    for m in _PRE_CODE.finditer(content):
        if m.start() > idx:
            pieces.append((content[idx:m.start()], False))
        pieces.append((m.group(0), True))
        idx = m.end()
    if idx < len(content):
        pieces.append((content[idx:], False))

    for text, is_code in pieces:
        if is_code:
            inner = _TAG.sub("", text)
            kind = "metadata_header" if _META_PRE_OPEN.match(text) else "code"
            blocks.append(Block(kind, _normalize(inner), text))
            continue
        # split prose on block-level tags, then strip remaining inline tags
        for chunk in re.split(r"(?i)</?(?:p|div|h[1-6]|li|tr|br\s*/?)\s*>", text):
            stripped = _TAG.sub("", chunk)
            if stripped.strip():
                blocks.append(Block("prose", _normalize(stripped), chunk))
    return blocks


# Sentence segmentation (lazy pysbd, regex fallback)

_SENT_FALLBACK = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9])")

# Sentence boundaries only feed the coarse prose subclassification
# (typo/formatting/rephrase/substantive), not the part classification.
# pysbd is ~6-22x slower so keep it False by default
USE_PYSBD = False


def split_sentences(text: str) -> List[str]:
    text = text.strip()
    if not text:
        return []
    if USE_PYSBD:
        seg = pysbd.Segmenter(language="en", clean=False)
        if seg:
            try:
                return [s.strip() for s in seg.segment(text) if s.strip()]
            except Exception:
                pass
    return [s.strip() for s in _SENT_FALLBACK.split(text) if s.strip()]
