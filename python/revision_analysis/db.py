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


def terminal_cutoffs(
        con: sqlite3.Connection, project_id: int | None = None
) -> pd.DataFrame:
    """Per proposal, the timestamp from which it is considered *terminal*.

    A revision is "terminal" if it was made once the proposal had permanently
    reached a terminal status (accepted / rejected / withdrawn / superseded) and
    "in-progress" otherwise. A proposal can bounce review -> accepted -> review,
    so we do not trust the latest status alone: the cutoff is the start of the
    *trailing run* of statuses that are all terminal, i.e., the timestamp of the
    first status after the last non-terminal one. Concretely:

    * no non-terminal status ever  -> cutoff = first status (terminal from birth)
    * the last status is non-terminal -> NaT (never permanently terminal; every
      revision is in-progress)
    * otherwise -> the created_at of the status right after the last
      non-terminal one

    A revision at time ``r`` is then terminal iff ``r >= cutoff`` (NaT cutoff =>
    always in-progress). Proposals with no status rows are absent.
    """
    where = "" if project_id is None else "WHERE project_id = :pid"
    sql = f"""
        WITH flagged AS (
            SELECT project_id, proposal_id, created_at AS raw_t,
                   CASE WHEN normalised_status IN ('accepted', 'rejected', 'withdrawn', 'superseded')
                        THEN 1 ELSE 0 END AS is_terminal,
                   ROW_NUMBER() OVER (
                       PARTITION BY project_id, proposal_id
                       ORDER BY datetime(created_at), status_index
                   ) AS rn
            FROM ProposalStatus {where}
        ),
        last_nt AS (
            SELECT project_id, proposal_id, MAX(rn) AS rn
            FROM flagged WHERE is_terminal = 0
            GROUP BY project_id, proposal_id
        ),
        bounds AS (
            SELECT project_id, proposal_id, MAX(rn) AS max_rn
            FROM flagged GROUP BY project_id, proposal_id
        )
        SELECT b.project_id, b.proposal_id,
               CASE
                   WHEN ln.rn IS NULL THEN (
                       SELECT f.raw_t FROM flagged f
                       WHERE f.project_id = b.project_id
                         AND f.proposal_id = b.proposal_id AND f.rn = 1)
                   WHEN ln.rn = b.max_rn THEN NULL
                   ELSE (
                       SELECT f.raw_t FROM flagged f
                       WHERE f.project_id = b.project_id
                         AND f.proposal_id = b.proposal_id AND f.rn = ln.rn + 1)
               END AS terminal_cutoff
        FROM bounds b
        LEFT JOIN last_nt ln USING (project_id, proposal_id)
    """
    df = pd.read_sql_query(
        sql, con, params={"pid": project_id} if project_id is not None else {}
    )
    df["terminal_cutoff"] = pd.to_datetime(
        df["terminal_cutoff"], errors="coerce", utc=True
    )
    return df


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
