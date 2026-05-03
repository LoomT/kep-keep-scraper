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
./gradlew :scraper:run --args="--repo=JetBrains/kotlin --mode=update --limit=20"
./gradlew :scraper:runFull --args="--repo=denoland/deno"
./gradlew :scraper:runUpdate --args="--repo=denoland/deno --include=issues,prs"
```

Arguments:

| Flag                  | Default                | Notes                                                                                |
|-----------------------|------------------------|--------------------------------------------------------------------------------------|
| `--repo=owner/name`   | (required)             | Target repository.                                                                   |
| `--mode=full\|update` | `update`               | `full` ignores cursors; `update` resumes from last sync.                             |
| `--limit=N`           | unlimited              | Process at most N top-level items per phase. Useful for smoke tests.                 |
| `--include=p1,p2,...` | all minus proposals    | Phases: `repo-info`, `issues`, `prs`, `discussions`. Passing `proposals` errors out. |
| `--dataDir=PATH`      | `data/<owner>/<name>/` | Where to write cache, normalized JSONL, manifests, and the local git mirror.         |

### Phase filtering on the named scrapers

`:keep:run` and `:kep:run` accept the same `--include=...` flag. Phases:

- `repo-info` — one-shot fetch of `/repos/{slug}` metadata.
- `issues` — issues + comments + timeline events.
- `prs` — PRs + reviews + review-comments + commits + files + timeline.
- `discussions` — GitHub Discussions tab (KEEP only — no-op on KEP).
- `proposals` — full revision history of proposal files (KEEP markdown / KEP yaml + README) via a local bare git mirror.

All three CLIs (`:scraper:run`, `:keep:run`, `:kep:run`) share the same flag
syntax — `--mode=`, `--limit=`, `--include=`, `--dataDir=` — none of them are
positional. The generic scraper additionally requires `--repo=`.

```sh
./gradlew :keep:run --args="--mode=full --include=proposals"
./gradlew :kep:run  --args="--mode=update --include=issues,prs"
./gradlew :keep:run --args="--mode=update --dataDir=/tmp/keep-scrape"
```

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
