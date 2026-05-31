"""Orchestrates segment -> diff -> classify pipeline.

Produces tidy DataFrames suitable for the notebook aggregations:

    content_changes : one row per classified content change

Results can be cached to parquet so re-running stats does not re-diff the
~77k revisions.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pandas as pd

from . import db, metadata_changes
from .diff import diff_blocks
from .segment import format_for_project, segment_content


def build_content_changes(
        con: sqlite3.Connection,
        project_id: int | None = None,
        progress_every: int = 1000,
) -> pd.DataFrame:
    """Diff every consecutive revision pair and classify the content changes."""
    rows: list[dict] = []
    n_props = 0
    for revs in db.iter_proposal_revisions(con, project_id=project_id, min_revisions=2):
        n_props += 1
        if progress_every and n_props % progress_every == 0:
            print(f"  ... {n_props} proposals processed", flush=True)
        fmt = format_for_project(revs[0].project_id)
        # segment each revision once and reuse across the two pairs it joins;
        # skip pairs whose content is byte-identical (e.g., metadata-only edits).
        seg_cache: list = [None] * len(revs)
        seg_cache[0] = segment_content(revs[0].content, fmt)
        for k in range(len(revs) - 1):
            prev, curr = revs[k], revs[k + 1]
            if prev.content == curr.content:
                seg_cache[k + 1] = seg_cache[k]
                continue
            if seg_cache[k + 1] is None:
                seg_cache[k + 1] = segment_content(curr.content, fmt)
            for ch in diff_blocks(seg_cache[k], seg_cache[k + 1]):
                rows.append(
                    {
                        "project_id": prev.project_id,
                        "proposal_id": prev.proposal_id,
                        "from_created": prev.created_at,
                        "to_created": curr.created_at,
                        "op": ch.op,
                        "block_kind": ch.block_kind,
                        "prose_subtype": ch.prose_subtype,
                        "category": ch.category,
                        "n_sentences": ch.n_sentences,
                    }
                )
    cols = [
        "project_id", "proposal_id", "from_created", "to_created",
        "op", "block_kind", "prose_subtype", "category", "n_sentences",
    ]
    return pd.DataFrame(rows, columns=cols)


def build_revision_pairs(con: sqlite3.Connection, project_id: int | None = None) -> pd.DataFrame:
    """Per-proposal revision counts + lifespan, joined to latest status.

    The revision count is the number of content revisions (``ProposalRevision``
    rows) plus the number of distinct status-revision timestamps
    (``ProposalStatus``) that do NOT coincide with a content revision: a status
    entry sharing a content revision's ``created_at`` is the *same* revision (no
    new revision), while a status entry at any other time adds one. The lifespan
    spans the union of content and status timestamps.

    The latest ``normalised_status`` is bucketed into terminal vs in-progress
    so revision frequency can be compared between the two groups.
    """
    where_r = "" if project_id is None else "WHERE project_id = :pid"
    and_s = "" if project_id is None else "AND s.project_id = :pid"
    sql = f"""
        WITH content AS (
            SELECT project_id, proposal_id,
                   COUNT(*) AS content_revisions,
                   MIN(datetime(created_at)) AS first_c,
                   MAX(datetime(created_at)) AS last_c
            FROM ProposalRevision {where_r}
            GROUP BY project_id, proposal_id
        ),
        status_agg AS (
            SELECT s.project_id, s.proposal_id,
                   -- distinct status timestamps with no matching content revision
                   COUNT(DISTINCT CASE WHEN NOT EXISTS (
                         SELECT 1 FROM ProposalRevision r
                         WHERE r.project_id = s.project_id
                           AND r.proposal_id = s.proposal_id
                           AND datetime(r.created_at) = datetime(s.created_at)
                       ) THEN datetime(s.created_at) END) AS extra_status,
                   MIN(datetime(s.created_at)) AS first_s,
                   MAX(datetime(s.created_at)) AS last_s
            FROM ProposalStatus s
            WHERE 1 = 1 {and_s}
            GROUP BY s.project_id, s.proposal_id
        ),
        latest_status AS (
            SELECT s.project_id, s.proposal_id, s.normalised_status
            FROM ProposalStatus s
            JOIN (
                SELECT project_id, proposal_id, MAX(status_index) AS mi
                FROM ProposalStatus GROUP BY project_id, proposal_id
            ) m ON m.project_id = s.project_id
               AND m.proposal_id = s.proposal_id
               AND m.mi = s.status_index
        )
        SELECT c.project_id, c.proposal_id,
               c.content_revisions + IFNULL(sa.extra_status, 0) AS n_revisions,
               MIN(c.first_c, IFNULL(sa.first_s, c.first_c)) AS first_rev,
               MAX(c.last_c, IFNULL(sa.last_s, c.last_c)) AS last_rev,
               ls.normalised_status AS latest_status
        FROM content c
        LEFT JOIN status_agg sa
          ON sa.project_id = c.project_id AND sa.proposal_id = c.proposal_id
        LEFT JOIN latest_status ls
          ON ls.project_id = c.project_id AND ls.proposal_id = c.proposal_id
    """
    df = pd.read_sql_query(sql, con, params={"pid": project_id} if project_id is not None else {})
    if df.empty:
        return df
    df["first_rev"] = pd.to_datetime(df["first_rev"], errors="coerce")
    df["last_rev"] = pd.to_datetime(df["last_rev"], errors="coerce")
    df["lifespan_days"] = (df["last_rev"] - df["first_rev"]).dt.total_seconds() / 86400.0
    terminal = {"accepted", "rejected", "withdrawn", "superseded"}
    df["status_group"] = df["latest_status"].map(
        lambda s: "terminal" if s in terminal else "in_progress"
    )
    # revisions per month of life (avoid div by zero for same-day proposals)
    df["revisions_per_month"] = df["n_revisions"] / (
            df["lifespan_days"].clip(lower=1) / 30.44
    )
    return df


def run(
        db_path: str | None = None,
        project_id: int | None = None,
        cache_dir: str | None = None,
) -> dict[str, pd.DataFrame]:
    """Run the full pipeline; optionally cache results to parquet."""
    con = db.connect(db_path)
    try:
        print("Building content changes (segment -> diff -> classify)...", flush=True)
        content = build_content_changes(con, project_id)
        print(f"  {len(content):,} content changes", flush=True)
        print("Building metadata changes...", flush=True)
        meta = metadata_changes.all_metadata_changes(con, project_id)
        print(f"  {len(meta):,} metadata changes", flush=True)
        print("Building revision pairs (RQ-B)...", flush=True)
        pairs = build_revision_pairs(con, project_id)
        print(f"  {len(pairs):,} proposals", flush=True)
    finally:
        con.close()

    result = {"content_changes": content, "metadata_changes": meta, "revision_pairs": pairs}
    if cache_dir:
        out = Path(cache_dir)
        out.mkdir(parents=True, exist_ok=True)
        for name, frame in result.items():
            frame.to_parquet(out / f"{name}.parquet", index=False)
        print(f"Cached to {out}", flush=True)
    return result
