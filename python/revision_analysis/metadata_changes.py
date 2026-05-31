"""Metadata revisions derived from structured tables (no text diffing).

Title, author and status are first-class in the schema, so changes to them are
detected by comparing rows across the chronological timeline rather than by
diffing ``content``. Note how each links to a content revision:

    - title  : ``ProposalRevision.title`` across revisions (by created_at)
    - author : ``ProposalRevisionAuthor.revision_index`` is the *same* index as
               ``ProposalRevision.revision_index`` -> join on revision_index.
    - status : ``ProposalStatus`` lives on its own timeline (status_index is NOT
               a revision index).
"""

from __future__ import annotations

import sqlite3

import pandas as pd

from .db import direction_cte


def title_changes(con: sqlite3.Connection, project_id: int | None = None) -> pd.DataFrame:
    """One row per revision whose title differs from the previous revision.

    Ordered by revision_index with the per-proposal direction (see
    ``db.direction_cte``) so date-only ``created_at`` ties don't scramble order.
    """
    where = "" if project_id is None else "WHERE project_id = :pid"
    sql = f"""
        WITH {direction_cte(where)},
        ordered AS (
            SELECT r.project_id, r.proposal_id, r.title, r.created_at,
                   LAG(r.title) OVER (
                       PARTITION BY r.project_id, r.proposal_id
                       ORDER BY r.revision_index * d.sgn
                   ) AS prev_title
            FROM ProposalRevision r
            JOIN rev_dir d USING (project_id, proposal_id)
        )
        SELECT project_id, proposal_id, created_at, prev_title, title
        FROM ordered
        WHERE prev_title IS NOT NULL AND prev_title <> title
    """
    return pd.read_sql_query(sql, con, params={"pid": project_id} if project_id is not None else {})


def author_changes(con: sqlite3.Connection, project_id: int | None = None) -> pd.DataFrame:
    """Revisions where the author set differs from the previous revision."""
    where_pr = "" if project_id is None else "WHERE pr.project_id = :pid"
    where = "" if project_id is None else "WHERE project_id = :pid"
    sql = f"""
        WITH {direction_cte(where)},
        author_sets AS (
            SELECT pr.project_id, pr.proposal_id, pr.revision_index, pr.created_at,
                   GROUP_CONCAT(a.author_id) AS authors
            FROM ProposalRevision pr
            LEFT JOIN ProposalRevisionAuthor a
              ON a.project_id = pr.project_id
             AND a.proposal_id = pr.proposal_id
             AND a.revision_index = pr.revision_index
            {where_pr}
            GROUP BY pr.project_id, pr.proposal_id, pr.revision_index
        ),
        ordered AS (
            SELECT s.*,
                   LAG(s.authors) OVER (
                       PARTITION BY s.project_id, s.proposal_id
                       ORDER BY s.revision_index * d.sgn
                   ) AS prev_authors
            FROM author_sets s
            JOIN rev_dir d USING (project_id, proposal_id)
        )
        SELECT project_id, proposal_id, created_at, prev_authors, authors
        FROM ordered
        WHERE prev_authors IS NOT NULL
          AND IFNULL(prev_authors,'') <> IFNULL(authors,'')
    """
    # normalise author-id ordering before comparison in pandas (GROUP_CONCAT order
    # is unspecified), so a pure reordering is not counted as a change.
    df = pd.read_sql_query(sql, con, params={"pid": project_id} if project_id is not None else {})

    def _norm(s):
        if not isinstance(s, str) or not s:
            return ""
        return ",".join(sorted(s.split(",")))

    if not df.empty:
        df = df[df["prev_authors"].map(_norm) != df["authors"].map(_norm)].copy()
    return df


def status_changes(con: sqlite3.Connection, project_id: int | None = None) -> pd.DataFrame:
    """All status transitions on the ProposalStatus timeline.

    Ordered by created_at (status_index is not reliable). Every transition is a
    metadata revision.
    """
    where = "" if project_id is None else "WHERE project_id = :pid"
    sql = f"""
        WITH ordered AS (
            SELECT project_id, proposal_id, normalised_status, created_at,
                   LAG(normalised_status) OVER (
                       PARTITION BY project_id, proposal_id
                       ORDER BY datetime(created_at), status_index
                   ) AS prev_status
            FROM ProposalStatus
            {where}
        )
        SELECT project_id, proposal_id, created_at, prev_status, normalised_status AS status
        FROM ordered
        WHERE prev_status IS NOT NULL AND prev_status <> normalised_status
    """
    return pd.read_sql_query(sql, con, params={"pid": project_id} if project_id is not None else {})


def all_metadata_changes(con: sqlite3.Connection, project_id: int | None = None) -> pd.DataFrame:
    """Tidy union of title/author/status changes, one row per change."""
    frames = []
    t = title_changes(con, project_id)
    if not t.empty:
        t = t.assign(category="metadata_title", op="modified")
        frames.append(t[["project_id", "proposal_id", "created_at", "category", "op"]])
    a = author_changes(con, project_id)
    if not a.empty:
        a = a.assign(category="metadata_author", op="modified")
        frames.append(a[["project_id", "proposal_id", "created_at", "category", "op"]])
    s = status_changes(con, project_id)
    if not s.empty:
        s = s.assign(category="metadata_status", op="modified")
        frames.append(s[["project_id", "proposal_id", "created_at", "category", "op"]])
    if not frames:
        return pd.DataFrame(columns=["project_id", "proposal_id", "created_at", "category", "op"])
    return pd.concat(frames, ignore_index=True)
