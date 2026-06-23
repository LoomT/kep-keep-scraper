# CSE3000_RQ3-KEEP-KEP

This project includes a GitHub Miner for [KEEP](https://github.com/Kotlin/KEEP)s
and [KEP](https://github.com/kubernetes/enhancements)s, and an analyzer of Software Enhancement Proposal evolution.

## Modules

| Module             | Purpose                                                                                                                                             |
|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `:scraper`         | Shared scraping utils + a generic scraper CLI that works against any GitHub repo.                                                                   |
| `:keep`            | KEEP-specific scraper (`Kotlin/KEEP`). Includes the proposal-text revision walk and corresponding commits from GH scraping.                         |
| `:kep`             | KEP-specific scraper (`kubernetes/enhancements`). Includes the proposal kep.yaml + README revision walk and corresponding commits from GH scraping. |
| `:loader`          | Maps the KEEPs and KEPs to the common SQL schema and exports `data.sql/data.db`.                                                                    |
| `:code-complexity` | Clones the codebases behind each proposal set and measures code complexity (scc + lizard) over time into `complexity.db`.                           |
| `:utils`           | Combines the per-contributor proposal databases into a single `all_proposals.db` (`combineProposals` task).                                         |
| `:python`          | Python subproject holding the analysis library (`revision_analysis`) and the Jupyter notebooks that produce the research-paper figures.             |

## Prerequisites

1. JDK 25 or newer (or use `./gradlew` with a foojay-resolved toolchain — done automatically).
2. A GitHub Personal Access Token in the `GITHUB_TOKEN` environment variable for scraping GitHub.
    - `repo:public_repo` scope is sufficient (both KEEP and KEP repos are public).
    - Authenticated requests get the 5000 req/hr REST quota and 5000 points/hr GraphQL quota.
3. Python 3.14 for analysis notebooks in the `:python` subproject.

```powershell
# Windows PowerShell
$env:GITHUB_TOKEN = "ghp_..."
```

```sh
# Unix
export GITHUB_TOKEN=ghp_...
```

## Monorepo layout (git subtrees)

The monorepo root holds the shared
`schema.sql` (and optionally `proposals.db`) plus one folder per contributor — including this repo as `rq3-kep-keep/`.
With subtrees, every contributor's files live directly in the monorepo's own commit history (no nested `.git`, no
`.gitmodules`), so a plain `git clone <monorepo-url>`
fetches everything in one shot. The Gradle convention plugin sets
`-DmonorepoRoot=<parent dir>` for every JavaExec task, so the loader and
`:utils:combineProposals` discover `../schema.sql` automatically.

If this project is cloned outside its monorepo, explicitly override `-DmonorepoRoot` to this folder!

### Open this project from a monorepo clone

```sh
# 1. Clone the monorepo.
git clone <monorepo-url> proposals-monorepo
cd proposals-monorepo/rq3-kep-keep

# 2. Open this folder in IntelliJ (NOT the monorepo root). IntelliJ detects
#    settings.gradle.kts here and treats this as the project root, so sibling
#    contributors' folders aren't indexed. Git VCS resolves to the monorepo's
#    .git/ at the parent level — that's expected; commits made via the IDE go
#    to the monorepo's history.
idea .   # or open the folder via File ▸ Open in IntelliJ

# 3. Run any Gradle command exactly as in standalone mode. The loader picks
#    ../schema.sql automatically.
./gradlew :loader:run -PkepProjectId=8 -PkeepProjectId=9
```

Override knobs (any `:run` task):

- `-PschemaPath=path/to/schema.sql` — explicit schema location for the loader and `:utils:combineProposals`.
- `-PsharedDir=path/to/shared` — explicit `data/shared/` location for `:utils:combineProposals`.
- `-PmonorepoRoot=path/to/monorepo` — override the auto-detected monorepo root.

## Running the scrapers

All commands run from inside `rq3-kep-keep/`.

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

### Available arguments

| Flag                  | Default                | Notes                                                                                                                                                            |
|-----------------------|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--repo=owner/name`   | (required)             | Target repository (only applicable to the generic scraper).                                                                                                      |
| `--mode=full\|update` | `update`               | `full` ignores cursors; `update` resumes from last sync.                                                                                                         |
| `--limit=N`           | unlimited              | Process at most N top-level items per phase. Useful for smoke tests.                                                                                             |
| `--include=p1,p2,...` | all                    | Phases: `repo-info`, `proposals`, `issues`, `prs`, `discussions`, `commits`, `users`, `orgs`. Generic scraper skips proposals and KEP scraper skips discussions. |
| `--dataDir=PATH`      | `data/<owner>/<name>/` | Where to write cache, normalized JSONL, manifests, and the local git mirror.                                                                                     |

### Phase filtering on the scrapers

`:keep:run` and `:kep:run` accept the same `--include=...` flag. Phases:

- `repo-info` — one-shot fetch of `/repos/{slug}` metadata.
- `issues` — issues + comments + timeline events.
- `prs` — PRs + reviews + review-comments + commits-of-the-PR + files + timeline.
- `discussions` — GitHub Discussions tab (KEEP only — no-op on KEP).
- `proposals` — full revision history of proposal files (KEEP markdown / KEP yaml + markdown) via a local bare git
  mirror. Generic scraper skips this phase.
- `commits` — repo-wide commit history via the GitHub REST API. **Crucially carries
  `author.login` / `committer.login`** (the GitHub user mapped from the git author
  email) — that's the bit the local-git `proposals` walker can't see.
  - For the named scrapers (`:keep:run`, `:kep:run`), this phase is automatically
    narrowed to commits that touched proposal files (one `?path=` query per file,
    paths discovered from the local mirror — so this implies cloning the mirror).
  - For the generic scraper (`:scraper:run`), no path filter; the entire repo's
    history is fetched. Skip this phase on huge repos unless you need it.
- `users` — fetches `GET /users/{login}` for every distinct GitHub login that appears anywhere in the other
  `<tag>-*.jsonl` streams already on disk. Runs sequentially **after** every other selected phase, so it sees
  freshly-emitted data; running it alone (`--include=users`) reuses the JSONLs from your last scrapes. Output goes to
  `<tag>-users.jsonl` (e.g. `keep-users.jsonl`, `kep-users.jsonl`). ETag-cached on re-run, 404s skipped.
- `orgs` — runs **after** `users`. For every login in `<tag>-users.jsonl`, fetches
  `GET /users/{login}/orgs` (the user's `organizations_url`) and emits the orgs array to `<tag>-user-orgs.jsonl` keyed
  by `_login` — that's the user→orgs membership. Then fetches `GET /orgs/{org}` for every distinct org login discovered
  and emits full org details (name, company, blog, location, email, description, etc.) to
  `<tag>-orgs.jsonl`.

All three CLIs (`:scraper:run`, `:keep:run`, `:kep:run`) share the same flag
syntax — `--mode=`, `--limit=`, `--include=`, `--dataDir=` — none of them are
positional. The generic scraper additionally requires `--repo=`.

```sh
./gradlew :keep:run --args="--mode=full --include=proposals"
./gradlew :kep:run  --args="--mode=update --include=issues,prs"
./gradlew :keep:run --args="--limit=10 --dataDir=/tmp/keep-scrape"
```

### Where the data goes

All Gradle `:run` tasks are pinned to **`<repo-root>` as their JVM working directory**
via the convention plugin. That means every relative `data/` reference resolves
to a single shared `<repo-root>/data/` (gitignored), so:

- `:keep:run` writes to `<root>/data/normalized/keep-*.jsonl`.
- `:kep:run` writes to `<root>/data/normalized/kep-*.jsonl`.
- `:scraper:run --repo=foo/bar` still gets its own subdir at
  `<root>/data/foo/bar/...` (per-repo isolation, derived from `--repo`).
- `:loader:run` reads `<root>/data/normalized/*.jsonl` — no copy step needed.

The cache (`data/cache/`) is keyed by URL (raw cache + ETags) and by qualified
keys like `keep.issues.updated_at` / `kep.issues.updated_at` (sync cursor) /
`Kotlin/KEEP` (git cursor), so KEEP and KEP coexist in the same cache without
collisions.

The manifests (`data/manifests/`) are made after every scrape run, and contain the summary of the run, e.g. the scraped
item and request counts, timestamps, errors, etc.

The logs (`data/logs/`) stores the rolling log file of any JVM task (scraping, mapping, etc.).

Layout under the unified `data/`:

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
    keep-users.jsonl
    keep-user-orgs.jsonl
    keep-orgs.jsonl
    keep-repo-info.jsonl
    keep-proposal-revisions.jsonl
    kep-*.jsonl                   # mirror of the above
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
- **Windows**: Ctrl-C against `gradlew.bat` is unreliable — the daemon swallows the signal. Use the stop file instead.

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

## Processing and sharing data with collaborators

This scraper feeds into a **shared SQLite database**.

### One-shot application

The `:loader` module produces `data.db` / `data.sql` from the local `data/normalized/*.jsonl`
streams and emits `apply.sh` / `apply.bat` helpers for applying `data.sql`. The application is **one-shot**:
re-applying the same SQL to a database that already contains your project_ids'
rows will fail on PRIMARY KEY violations.

```sh
# 1. Scrape (run scrapers to completion)
./gradlew :keep:run
./gradlew :kep:run

# 2a. Export SQLite .db file
./gradlew :loader:run -PkepProjectId=8 -PkeepProjectId=9

# 2b. Export .sql file with insert statements
./gradlew :loader:run -PexportTypes="sql,db" -PkepProjectId=8 -PkeepProjectId=9

# 3b. Apply exported .sql to the shared .db file
bash build/export/keep-kep/apply.sh ../proposals.db
```

`:loader:run` writes:

```
<repo-root>/build/export/keep-kep/
  schema-copy.sql   # snapshot of schema.sql (sanity check vs the live .db)
  data.sql          # all INSERTs in FK-dependency order, single transaction
  apply.sh          # bash apply.sh path/to/proposals.db
  apply.bat         # Windows equivalent
  data.db           # SQLite .db file according to the schema.sql
```

`person_id` / `organisation_id` / `comment_id` values are allocated locally in the half-open range
`[project_id × 1_000_000, +1_000_000)` so they can't collide with other contributors' allocations.

### Schema is `schema.sql`

The collaborative database schema lives in `schema.sql` at the monorepo root. The loader's
`SchemaModel.kt` data classes mirror it; if the schema changes, update both
together. The loader's per-stream → SQL mapping (`KeepMapper.kt` / `KepMapper.kt`)
contains the logic for mapping raw data from `data/normalized/*.jsonl` to the common SQL schema.

## Analysis

This sections documents how the proposal sets were combined, complexities of codebases analyzed and analysis for the
research paper done.

`utils` subproject contains the utility for combining proposal data from different proposal sets.

Subproject `code-complexity` contains the scrapers and analyzers for the codebases corresponding to the proposal sets.

Analysis itself is done using Python notebooks in the `:python` module. It is recommended to create a `.venv` there with
at least Python 3.14:

```sh
cd python
python -m venv .venv
.venv/Scripts/activate   # Windows; on Unix: source .venv/bin/activate
pip install -r requirements.txt
```

The notebooks live in `python/notebooks/` and import the `revision_analysis` package (segment → diff → classify pipeline
over the proposal revisions). Launch Jupyter from the `python/` directory (or open the notebooks in IntelliJ / VS Code
with the `.venv` selected as the kernel).

### Combining proposal data

After exporting the `data.db` to `<repo-root>/build/export/keep-kep/` as detailed in the previous section, move this
database file to `<repo-root>/data/shared/data.db`. Place the other database files in
`<repo-root>/data/shared/other_proposals/` (can be `.db`, `.sqlite` or `.sqlite3`).

Then, run the following command to combine all proposals and produce a single database file in
`<repo-root>/data/shared/all_proposals.db`:

```sh
./gradlew :utils:combineProposals
```

This task will filter out data with timestamps older than 2026-01-01. To change that, modify the `CUTOFF` variable in
`<repo-root>/utils/src/main/kotlin/dev/cse3000/utils/CombineProposals.kt`.

### Codebase Complexities

The `:code-complexity` module clones the codebase behind each proposal set and measures how its complexity evolves over
time. Each project is listed in `code-complexity/src/main/resources/repos-config.csv`
(`owner/repo,project_id,monthsAgo[,subfolder]`); for every project it takes semi-annual snapshots back to the
proposal-start date, then runs **scc** and **lizard** (see [External Tool Versions Used](#external-tool-versions-used) —
both must be on `PATH`) on each snapshot. Monorepos are sparse-checked out to the configured `subfolder` only.

```sh
./gradlew :code-complexity:run
```

Results are written to `<repo-root>/data/complexity/complexity.db` (bare git mirrors and worktrees go under
`<repo-root>/data/complexity/`). Rerunning skips snapshots already present in the DB, so it resumes cheaply.

Git should be installed on `PATH` since git worktrees are used and JGit does not support them
(see [External Tool Versions Used](#external-tool-versions-used)).

### Analysis

Assumes that a single database file with all proposals is in `<repo-root>/data/shared/all_proposals.db` (from
`:utils:combineProposals`) and the codebase complexity database file in `<repo-root>/data/complexity/complexity.db`
(from `:code-complexity:run`). With the Python `.venv` set up as described above, run the notebooks in
`python/notebooks/`:

- `revision-classification.ipynb` — classifies proposal revisions (code / prose / metadata changes) and compares
  terminal vs. in-progress revisions.
- `complexity.ipynb` — correlates proposal evolution with the codebase complexity snapshots.

## Build

```sh
./gradlew build # compile all modules
./gradlew test  # run all tests
./gradlew clean # wipe build outputs
```

## Continuous Integration

`.gitlab-ci.yml` defines a two-stage GitLab pipeline (`build` → `test`) scoped to this project (`PROJECT_DIR =
rq3-kep-keep`). It runs on the `eclipse-temurin:25-jdk` image and is triggered as a child of the monorepo's parent
pipeline, on merge requests, on the default branch, and on manual (`web`) runs.

- **build** — `./gradlew --no-daemon assemble` (compiles every module's jars without running tests).
- **test** — `./gradlew --no-daemon test`, publishing per-subproject JUnit reports and HTML test reports as artifacts.

The Gradle user home and build outputs are cached per branch (keyed on `gradle-wrapper.properties` +
`settings.gradle.kts`) to speed up subsequent runs.

## Notes

- Multi-module Gradle setup; shared build logic is in `buildSrc/`.
- Versions are pinned in `gradle/libs.versions.toml`.

## External Tool Versions Used

### Code Complexity Analysis

- scc (https://github.com/boyter/scc) – 3.7.0
- lizard (https://github.com/terryyin/lizard) – 1.23.0
- git (https://git-scm.com/) – 2.53.0.windows.1
