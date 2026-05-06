# CSE3000_RQ3-KEEP-KEP

## Modules

| Module     | Purpose                                                                                         |
|------------|-------------------------------------------------------------------------------------------------|
| `:scraper` | Shared library + a generic scraper CLI that works against any GitHub repo.                      |
| `:keep`    | KEEP-specific scraper (`Kotlin/KEEP`). Includes the proposal-text revision walk.                |
| `:kep`     | KEP-specific scraper (`kubernetes/enhancements`). Includes the kep.yaml + README revision walk. |
| `:rq3`     | Analysis module (TBD).                                                                          |

## Prerequisites

1. JDK 25 (or use `./gradlew` with a foojay-resolved toolchain — done automatically).
2. A GitHub Personal Access Token in the `GITHUB_TOKEN` environment variable for scraping GitHub.
    - `repo:public_repo` scope is sufficient (both KEEP and KEP repos are public).
    - Authenticated requests get the 5000 req/hr REST quota and 5000 points/hr GraphQL quota.

```powershell
# Windows PowerShell
$env:GITHUB_TOKEN = "ghp_..."
```

```sh
# Unix
export GITHUB_TOKEN=ghp_...
```

## Running the scrapers

All commands assume you're at the repo root.

### KEEP and KEP (the named scrapers)

```sh
./gradlew :keep:run --args="--mode=full"   # initial backfill of Kotlin/KEEP
./gradlew :keep:run --args="--mode=update" # incremental refresh
./gradlew :keep:run                        # same as above (mode defaults to update)
./gradlew :keep:run --args="--mode=full --limit=5 --include=issues,prs" # smoke test: at most 5 issues and 5 pull requests

 # for kubernetes/enhancements, replace keep with kep, e.g.:
./gradlew :kep:run
```

### Generic scraper (`:scraper`)

For any other GitHub repository — does **not** include the proposals/revision-walk
phase since it's only meaningful for KEEP/KEP layouts.

```sh
./gradlew :scraper:run --args="--repo=spring-projects/spring-boot --mode=full"
./gradlew :scraper:run --args="--repo=JetBrains/kotlin --mode=update --include=issues,prs --limit=20"
```

Arguments:

| Flag                  | Default                | Notes                                                                                           |
|-----------------------|------------------------|-------------------------------------------------------------------------------------------------|
| `--repo=owner/name`   | (required)             | Target repository.                                                                              |
| `--mode=full\|update` | `update`               | `full` ignores cursors; `update` resumes from last sync.                                        |
| `--limit=N`           | unlimited              | Process at most N top-level items per phase. Useful for smoke tests.                            |
| `--include=p1,p2,...` | all minus proposals    | Phases: `repo-info`, `issues`, `prs`, `discussions`, `commits`. Passing `proposals` errors out. |
| `--dataDir=PATH`      | `data/<owner>/<name>/` | Where to write cache, normalized JSONL, manifests, and the local git mirror.                    |

### Phase filtering on the named scrapers

`:keep:run` and `:kep:run` accept the same `--include=...` flag. Phases:

- `repo-info` — one-shot fetch of `/repos/{slug}` metadata.
- `issues` — issues + comments + timeline events.
- `prs` — PRs + reviews + review-comments + commits-of-the-PR + files + timeline.
- `discussions` — GitHub Discussions tab (KEEP only — no-op on KEP).
- `proposals` — full revision history of proposal files (KEEP markdown / KEP yaml + README) via a local bare git mirror.
- `commits` — repo-wide commit history via the GitHub REST API. **Crucially carries
  `author.login` / `committer.login`** (the GitHub user mapped from the git author
  email) — that's the bit the local-git `proposals` walker can't see.
  - For the named scrapers (`:keep:run`, `:kep:run`), this phase is automatically
    narrowed to commits that touched proposal files (one `?path=` query per file,
    paths discovered from the local mirror — so this implies cloning the mirror).
  - For the generic scraper (`:scraper:run`), no path filter; the entire repo's
    history is fetched. Skip this phase on huge repos unless you need it.

All three CLIs (`:scraper:run`, `:keep:run`, `:kep:run`) share the same flag
syntax — `--mode=`, `--limit=`, `--include=`, `--dataDir=` — none of them are
positional. The generic scraper additionally requires `--repo=`.

```sh
./gradlew :keep:run --args="--mode=full --include=proposals"
./gradlew :kep:run  --args="--mode=update --include=issues,prs"
./gradlew :keep:run --args="--mode=update --dataDir=/tmp/keep-scrape"
```

### Scraping user metadata for a manually-supplied login list

Once you have a list of GitHub logins (e.g., emitted by `:loader` from
`PersonUsername` rows, or assembled by hand), you can fetch full account
metadata — `name`, `company`, `location`, `bio`, `public_repos`, `followers`,
account creation timestamp, etc. — via `GET /users/{login}`:

```sh
./gradlew :scraper:runUsers -Pinput=path/to/logins.txt
```

Input file: one GitHub login per line, blank lines and lines starting with
`#` are ignored. Optional Gradle properties: `-PdataDir=...` (default
`data/users/`, isolated from the per-repo scrape caches), `-Plimit=N` for
smoke tests, `-Pmode=full|update` (default `update`).

Output: `<dataDir>/normalized/users.jsonl` — one row per user (the raw
`/users/{login}` response, plus `_login` and `_scraped_at` meta). 404
(deleted/renamed accounts) are logged at WARN and skipped — not fatal.

### Where the data goes

The scrapers default to `data/` at their module root (gitignored). Layout per scraper:

```
data/
  cache/                          # ETag store, sync cursors, raw API response bodies
    raw/<sha1-shard>/<sha1>.json
    raw/<sha1-shard>/<sha1>.json.link    # rel="next"/"last" sidecar (for paginated 304s)
    etags.json
    last-sync.json                # endpoint -> max(updated_at)
    git-heads.json                # repo slug -> last-walked HEAD SHA
  repos/                          # bare git mirrors used by the proposals phase
    Kotlin_KEEP.git/
    kubernetes_enhancements.git/
  normalized/                     # one JSONL per entity stream — your downstream input
    keep-issues.jsonl
    keep-pulls.jsonl
    keep-pull-detail.jsonl
    keep-pr-files.jsonl
    keep-pr-reviews.jsonl
    keep-pr-review-comments.jsonl
    keep-pr-commits.jsonl
    keep-pr-issuecomments.jsonl
    keep-issue-comments.jsonl
    keep-issue-timeline.jsonl
    keep-pr-timeline.jsonl
    keep-discussions.jsonl
    keep-discussion-comments.jsonl
    keep-discussion-comment-replies.jsonl
    keep-repo-info.jsonl
    keep-proposal-revisions.jsonl
    kep-*.jsonl                   # mirror of the above, plus:
    kep-yaml-revisions.jsonl
    kep-readme-revisions.jsonl
  manifests/                      # per-run summary: counts, request totals, cancelled flag
    Kotlin_KEEP-full-2026-04-27T....json
  logs/scraper.log                # rolling log file (also goes to stdout)
```

The **`:scraper:run` CLI** writes to `data/<owner>/<name>/...` by default, e.g.
`data/spring-projects/spring-boot/normalized/spring-projects_spring-boot-issues.jsonl`.
Override with `--dataDir=...`.

Every JSONL row has these scraper-added meta fields:

- `_scraped_at` — ISO timestamp when the row was emitted.
- `_repo` — `<owner>/<name>` slug.
- Phase-specific extras (`_pr`, `_issue`, `_discussion`, `_path`, etc.) where useful.

The downstream MySQL loader can dedupe by entity-id + `_scraped_at`.

### Configuration via environment

| Variable              | Default                 | Effect                                                             |
|-----------------------|-------------------------|--------------------------------------------------------------------|
| `GITHUB_TOKEN`        | (required)              | PAT used for all API calls.                                        |
| `SCRAPER_DATA_DIR`    | `data/`                 | Override the default data directory for the named scrapers.        |
| `SCRAPER_CONCURRENCY` | `16`                    | Max in-flight HTTP requests. Lower if you hit GitHub abuse limits. |
| `SCRAPER_LOG_LEVEL`   | `INFO`                  | `DEBUG` for verbose, `WARN` to silence progress logs.              |
| `SCRAPER_LOG_FILE`    | `data/logs/scraper.log` | Where the rolling logfile goes.                                    |

### Graceful cancellation

A scrape can run for hours. To stop it without losing buffered JSONL or
ETag/cursor state, use one of the mechanisms below — they all converge on
the same shutdown path: ETag store + cursors flushed, JSONL writers closed,
manifest written with `"cancelled": true`, then exit.

A bounded 30-second timeout protects against a stuck network call. A second
hard kill (e.g. second Ctrl-C, or `kill -9`) bypasses everything and exits
the JVM immediately.

#### Stop file (works everywhere — recommended)

Create the file `<dataDir>/STOP`. The scraper polls every 2 seconds, deletes
the file once seen, and triggers graceful cancellation. Works regardless of
OS, Gradle daemon mode, or whether you launched from a terminal or IntelliJ.

```sh
# Unix
touch data/STOP                                          # named scrapers
touch data/spring-projects/spring-boot/STOP              # generic scraper
```

```powershell
# Windows
New-Item data/STOP -ItemType File -Force
New-Item data/spring-projects/spring-boot/STOP -ItemType File -Force
```

#### Ctrl-C in the terminal

- **Unix (Linux/macOS)**: works out of the box if you launch with
  `./gradlew --no-daemon :keep:run --args="full"`. SIGINT propagates from your
  shell through the gradle process down to the forked JVM, the JVM shutdown
  hook fires, and cleanup runs.
- **Windows**: Ctrl-C against `gradlew.bat` is unreliable — the daemon swallows
  the signal. Use the stop file instead, or run the installed distribution
  directly (next section).

#### Bypassing Gradle (Ctrl-C works reliably on all platforms)

```sh
./gradlew :scraper:installDist
./scraper/build/install/scraper/bin/scraper --repo=owner/name --mode=full
# Ctrl-C in this terminal goes straight to the JVM. Hook fires, cleanup runs.
```

Equivalents exist for `:keep` (`./keep/build/install/keep/bin/keep full`) and
`:kep` (`./kep/build/install/kep/bin/kep full`).

#### IntelliJ "stop" button

The square-stop button on a Gradle run kills the process via `TerminateProcess`
on Windows, which **bypasses JVM shutdown hooks**. Don't rely on it — use the
stop file instead. (On Linux/macOS IntelliJ sends SIGTERM, which does fire
the hook, but the stop file is consistent across both.)

### Resuming after cancel

Just re-run with `update`. The cursor (`data/cache/last-sync.json`) advances
per-item as each issue / PR / discussion finishes its sub-fetches, so the
next run picks up at the last fully-processed item. ETag-cached URLs return
304 immediately for everything unchanged since the last successful fetch —
the manifest's `requests_304` count tells you how many.

## Sharing data with collaborators

This scraper feeds into a **shared SQLite database**.

### One-shot push

The `:loader` module produces `data.sql` from the local `data/normalized/*.jsonl`
streams and emits `apply.sh` / `apply.bat` helpers. The push is **one-shot**:
re-applying the same SQL to a database that already contains your project_ids'
rows will fail on PRIMARY KEY violations.

```sh
# 1. Scrape (run scrapers to completion)
./gradlew :keep:run
./gradlew :kep:run

# 2. Generate SQL (replace 3 / 4 with your assigned project ids)
./gradlew :loader:run -PkeepProjectId=3 -PkepProjectId=4

# 3. Apply to the sibling repo's committed proposals.db
cd ~/projects/proposals-db && git pull
bash ~/projects/this-repo/loader/build/export/keep-kep/apply.sh ./proposals.db

# 4. Commit + push the modified .db
git add proposals.db
git commit -m "[keep-kep] one-shot import"
git push
```

`:loader:run` writes:

```
loader/build/export/keep-kep/
  schema.txt        # snapshot of db-schema.sql (sanity check vs the live .db)
  data.sql          # all INSERTs in FK-dependency order, single transaction
  apply.sh          # bash apply.sh path/to/proposals.db
  apply.bat         # Windows equivalent
```

`person_id` / `organisation_id` / `comment_id` values are allocated locally in
the half-open range `[smallestProjectId × 1_000_000, +1_000_000)` so they can't
collide with other contributors' allocations. Person dedup across projects is
deferred to `:rq3` post-processing, e.g., the same GitHub user appearing in two
contributors' data will be two separate `Person` rows after both pushes.

### Pull and analyse in `:rq3`

```sh
# 1. Pull the latest sibling repo
cd ~/projects/proposals-db && git pull

# 2. Sync the shared db into this repo's :rq3 module
cd ~/projects/this-repo
./gradlew :rq3:syncSharedDb -PsharedDbPath=../../proposals-db/proposals.db

# 3. Run analyses (initially: print sanity row counts per table)
./gradlew :rq3:run
```

`syncSharedDb` copies the .db into `rq3/data/shared/proposals.db` (gitignored).
`:rq3:run` opens it via `sqlite-jdbc`. You can also point `:rq3` at the
sibling-repo checkout directly without copying via
`-PsharedDbPath=path/to/proposals.db` on `:rq3:run`.

### Schema is `db-schema.sql`

The collaborative schema lives in `db-schema.sql` at the repo root. The loader's
`SchemaModel.kt` data classes mirror it; if the schema changes, update both
together. The loader's per-stream → SQL mapping (`KeepMapper.kt` / `KepMapper.kt`)
is TBD

## Build

```sh
./gradlew build         # compile all modules
./gradlew clean         # wipe build outputs
```

Per-module tasks (e.g. `:scraper:tasks --group scraping`) list the available
runnable scrape entry points.

## Notes

- Multi-module Gradle setup; shared build logic is in `buildSrc/`.
- Versions are pinned in `gradle/libs.versions.toml`.
- Build cache and configuration cache are enabled — see `gradle.properties`.
