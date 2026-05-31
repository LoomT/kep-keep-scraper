"""Revision-evolution analysis for the proposal corpus.

Replicates the API-documentation evolution methodology (identify revisions ->
classify by heuristics -> refine) on the proposal snapshots stored in
``all_proposals.db``.

Pipeline:
    db          -- connection + streaming queries (ordered by index/created_at)
    segment     -- per-revision format detection + block/sentence segmentation
    diff        -- diff two consecutive revisions into add/delete/modify units
    classify    -- heuristic rules -> part categories (code/prose/metadata)
    metadata_changes -- title/author/status changes from structured tables
    pipeline    -- orchestrates the above
"""

from . import db, metadata_changes, pipeline
from .classify import classify_prose_modification
from .diff import diff_revisions, ContentChange
from .segment import detect_format, segment_content, Block

__all__ = [
    "detect_format",
    "segment_content",
    "Block",
    "diff_revisions",
    "ContentChange",
    "classify_prose_modification",
    "db",
    "metadata_changes",
    "pipeline",
]
