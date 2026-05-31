"""Database access helpers.

Revisions are ordered by ``revision_index`` and
 direction is set according to ``created_at``
 because some projects store a reversed revision index
 for some proposals.
"""

from __future__ import annotations

import os
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import pandas as pd

# rq3-kep-keep/data/shared/all_proposals.db relative to this file
_DEFAULT_DB = (
        Path(__file__).resolve().parents[2] / "data" / "shared" / "all_proposals.db"
)


def default_db_path() -> Path:
    """Resolve the shared DB path (override with $ALL_PROPOSALS_DB)."""
    env = os.environ.get("ALL_PROPOSALS_DB")
    return Path(env) if env else _DEFAULT_DB


def connect(db_path: str | os.PathLike | None = None) -> sqlite3.Connection:
    path = Path(db_path) if db_path else default_db_path()
    if not path.exists():
        raise FileNotFoundError(f"Database not found: {path}")
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    return con


def query(con: sqlite3.Connection, sql: str, params: tuple = ()) -> pd.DataFrame:
    return pd.read_sql_query(sql, con, params=params)


def project_names(con: sqlite3.Connection) -> dict[int, str]:
    return {
        int(r["project_id"]): r["project_name"]
        for r in con.execute("SELECT project_id, project_name FROM Project")
    }


@dataclass(frozen=True)
class Revision:
    project_id: int
    proposal_id: str
    revision_index: int
    title: str
    created_at: str
    content: str | None


def direction_cte(where_pid: str = "") -> str:
    """SQL for two CTEs -- ``_adj`` and ``rev_dir(project_id, proposal_id, sgn)``.

    Within a proposal ``revision_index`` is a clean total order (no ties), but
    its *direction* relative to ``created_at`` is not guaranteed: e.g. some Rust or Swift
    proposals store revision_index reversed (index 0 = newest). And ``created_at`` is
    sometimes date-only, so it cannot break ties on its own. ``sgn`` is -1 when
    ordering by revision_index ascending makes ``created_at`` mostly *decrease*
    (so revisions should be read in descending index order), else +1. Callers
    then ``ORDER BY revision_index * sgn`` to get chronological, tie-free order.
    """
    return f"""_adj AS (
            SELECT project_id, proposal_id, revision_index,
                   datetime(created_at) AS _t,
                   LAG(datetime(created_at)) OVER (
                       PARTITION BY project_id, proposal_id ORDER BY revision_index
                   ) AS _pt
            FROM ProposalRevision {where_pid}
        ),
        rev_dir AS (
            SELECT project_id, proposal_id,
                   CASE WHEN SUM(CASE WHEN _pt IS NOT NULL AND _t < _pt THEN 1 ELSE 0 END)
                           > SUM(CASE WHEN _pt IS NOT NULL AND _t > _pt THEN 1 ELSE 0 END)
                        THEN -1 ELSE 1 END AS sgn
            FROM _adj GROUP BY project_id, proposal_id
        )"""


def ordered_revisions(
        con: sqlite3.Connection, project_id: int, proposal_id: str
) -> list[Revision]:
    """A single proposal's revisions in chronological order (same ordering as
    :func:`iter_proposal_revisions`). Handy for inspecting one proposal."""
    where = "WHERE project_id = :pid AND proposal_id = :prop"
    sql = f"""
        WITH {direction_cte(where)}
        SELECT r.project_id, r.proposal_id, r.revision_index, r.title,
               r.created_at, r.content
        FROM ProposalRevision r
        JOIN rev_dir d USING (project_id, proposal_id)
        WHERE r.project_id = :pid AND r.proposal_id = :prop
        ORDER BY r.revision_index * d.sgn
    """
    rows = con.execute(sql, {"pid": project_id, "prop": proposal_id}).fetchall()
    return [
        Revision(
            project_id=int(r["project_id"]), proposal_id=str(r["proposal_id"]),
            revision_index=int(r["revision_index"]), title=r["title"],
            created_at=r["created_at"], content=r["content"],
        )
        for r in rows
    ]


def iter_proposal_revisions(
        con: sqlite3.Connection,
        project_id: int | None = None,
        min_revisions: int = 1,
) -> Iterator[list[Revision]]:
    """Yield each proposal's revisions as a list in chronological order.

    Revisions are ordered by ``revision_index`` with a per-proposal direction
    (see :func:`direction_cte`) so the sequence is monotonically increasing in
    ``created_at`` even when timestamps are date-only (tie-prone) or the index
    runs backwards (Rust). Streams one proposal at a time to conserve memory.
    ``project_id=None`` covers all 10 projects.
    """
    where = "" if project_id is None else "WHERE project_id = :pid"
    params = {} if project_id is None else {"pid": project_id}
    sql = f"""
        WITH {direction_cte(where)}
        SELECT r.project_id, r.proposal_id, r.revision_index, r.title,
               r.created_at, r.content
        FROM ProposalRevision r
        JOIN rev_dir d USING (project_id, proposal_id)
        ORDER BY r.project_id, r.proposal_id, r.revision_index * d.sgn
    """
    cur = con.execute(sql, params)
    current_key: tuple | None = None
    bucket: list[Revision] = []
    for row in cur:
        key = (row["project_id"], row["proposal_id"])
        rev = Revision(
            project_id=int(row["project_id"]),
            proposal_id=str(row["proposal_id"]),
            revision_index=int(row["revision_index"]),
            title=row["title"],
            created_at=row["created_at"],
            content=row["content"],
        )
        if key != current_key:
            if bucket and len(bucket) >= min_revisions:
                yield bucket
            bucket = []
            current_key = key
        bucket.append(rev)
    if bucket and len(bucket) >= min_revisions:
        yield bucket
