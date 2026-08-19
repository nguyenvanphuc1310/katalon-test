# Getting started — from a fresh clone to a green run

This page takes a new machine to a run that produces a report. It stops where
[running-tests.md](running-tests.md) starts: that page is the reference for *which mode and
which suite*, this one is the reference for *getting there at all*.

## 1. Prerequisites

| | Why |
|---|---|
| **Katalon Studio 11.4, Enterprise** | The project was created and last modified with it (`katalon-test.prj`). The offline tools also borrow Studio's bundled Groovy and JRE, so Studio must be installed even for the browser-free runs |
| macOS, Studio at `/Applications/Katalon Studio.app` | `tools/offline-checks/run.sh` looks there. Elsewhere: `export KATALON_APP=/path/to/Katalon Studio.app` |
| **Chrome** | Only the `baseline` and `capture` modes open a browser |
| **Corporate VPN** | Only for `aem-uat.prudential.com.sg`. `www.prudential.com.sg` is public, so a live baseline run needs no VPN |
| git | The snapshots under `Data Files/baselines/` come with the clone |

You do **not** need your own Java or Groovy. The system `java` on a current macOS is newer
than Groovy 3 can read while Katalon executes on 21 — the offline runner deliberately uses
Katalon's own JRE to avoid exactly that mismatch.

## 2. Get the project

```bash
git clone <repo> && cd katalon-test
```

Then open `katalon-test.prj` in Katalon Studio and wait for it to finish compiling the
keywords (the status bar shows it; the first open takes the longest).

What the clone does and does not contain:

| Tracked | Not tracked (regenerated) |
|---|---|
| `Keywords/`, `Test Cases/`, `Test Suites/`, `Data Files/`, `tools/`, `docs/` | `Reports/` — every run rewrites it |
| `Data Files/baselines/` — the crawled snapshots | `bin/`, `.cache/`, `Libs/`, `build/`, `.gradle/` |
| | `.classpath`, `.project`, `.settings/` — Studio writes them on first open |

`Data Files/baselines/` is tracked on purpose: `tools/offline-checks/run.sh replay` re-diffs
those snapshots, so they are **input**, not output.

## 3. Verify the install without a browser

```bash
tools/offline-checks/run.sh
```

Green looks like this: every keyword compiles (≈138 classes), **one** class-file major
version for the whole tree, 23/23 verdict and score rule assertions, 11/11 report-contract
assertions. It takes seconds and touches no network.

Add `replay` to also re-diff every snapshot on disk and rebuild the reports:

```bash
tools/offline-checks/run.sh replay
```

That is the same work as `mode=recompare` plus the report test cases, outside Katalon. Run
it after **every** keyword edit, and always before believing that a change to a matching
rule or a score weight did what you intended.

## 4. First run inside Studio

Start with the suite that needs neither a browser nor the VPN:

```
Test Suites/migration-aem/normal-pages/by-page/TS_GeneralContentDetailPage_Recompare
```

It re-judges the 9 General Content Detail rows against the 8 snapshot pairs in the repo and
ends by rebuilding the report. The ninth URL serves a PDF, can never have a snapshot, and is
correctly recorded `NOT_RUN` — that is not a failure.

Open `Reports/parity-report/index.html` when it finishes.

Then the full loop, which does need Chrome and (for the AEM half) the VPN:

| Step | Suite | Browser | VPN |
|---|---|---|---|
| 1. Capture the live side | `normal-pages/by-page/TS_GeneralContentDetailPage_Baseline` | yes | no |
| 2. Capture the new side | `normal-pages/by-page/TS_GeneralContentDetailPage_Capture` | yes | **yes** |
| 3. Judge | `normal-pages/by-page/TS_GeneralContentDetailPage_Recompare` | no | no |

`TS_GeneralContentDetailPage_Compare` does steps 2 and 3 in one run.

**Crawl once, re-judge as often as you like.** Crawling is the expensive half; judging is
the half that gets re-run every time a rule or a weight changes.

**The suite sets the mode and binds the data file.** Editing a test case's variables by hand
is for investigating a single page, not for running a suite.

## 5. Read the output

| File | What |
|---|---|
| `Reports/parity-report/index.html` | the report — cover, at-a-glance matrix, filter bar, one page per URL |
| `Reports/publish/` | the same site with no local paths — copy this to a report server |
| `Reports/parity-results/<slug>/content.txt` | verdict on line 1, summary from line 2 |
| `Reports/ContentAudit/<slug>/findings.csv` | one row per finding, with its score weight |
| `Reports/ContentAudit/<slug>/score.csv` | score, grade, items, points lost, confidence |
| `Reports/ContentAudit/<slug>/state_pairs.csv` | which tab paired with which, and how |
| `Reports/baseline-summary.html` | which pages have both sides captured and can be compared |

The slug comes from the AEM URL in `Data Files/aem-url-mapping.csv`; a page absent from that
CSV is never rendered at all. What each verdict means and how the score is computed:
[../reference/verdicts-and-score.md](../reference/verdicts-and-score.md).

## 6. When it breaks

**`UnsupportedClassVersionError` naming a keyword.** Usually stale compiled classes: a
keyword edited outside the IDE leaves `bin/keyword/` holding classes at a different version,
and Katalon then names the wrong file. Close Studio, delete `bin/keyword/` and
`.cache/Keywords/`, reopen. `run.sh` prints the class-file versions, so a mismatch is visible
before Studio hits it.

**...and the class file is ~800 bytes.** Then it is not a JDK problem at all: a single
backslash in an embedded JS regex inside a triple-quoted Groovy string makes Katalon's
compiler emit an error *stub* — a valid class whose constructor throws. Check the size
first, then `javap -c` it; the real compiler message is inside. `run.sh` flags anything
under 1 KB.

**`run.sh` says `.classpath` has no jar entries.** A Gradle/Buildship refresh replaced
Katalon's classpath with a container, and nothing resolves `com.kms.katalon.*` any more —
Studio blames the keywords with `unable to resolve class migration.<X>`. Close Studio,
delete `.classpath`, `.project`, `.settings/org.eclipse.buildship.core.prefs`,
`.settings/org.eclipse.jdt.core.prefs`, `.gradle` and `bin`, then reopen the project so
Studio regenerates them. `run.sh` prints this recovery itself.

**A variable is empty and nothing errored.** A suite's `variableId` must match the id in the
bound test case's `.tc`. A mismatch does not fail — the variable is simply never set. Check
the ids after hand-editing or copying a suite.

**`recompare` insists on a baseline that cannot exist.** The URL serves a PDF. The baseline
run records `NOT_RUN` and `recompare` honours it; a hard failure here means something else.

## 7. Known gaps — what you cannot run yet

The docs describe more suites than the repository contains. As of 2026-08-19, on disk:
**6 test cases, 9 suites, no test-suite collections.**

| Runnable | Not runnable |
|---|---|
| `normal-pages/by-page/TS_GeneralContentDetailPage_{Baseline,Capture,Compare,Recompare}` | `normal-pages/TS_Normal_PreT0_Baseline` / `TS_Normal_PostT0_Compare` — bind 19 page-type test cases, of which only `TC_GeneralContentDetailPage` and `TC_LbuHomepage` exist |
| `custom-pages/by-page/TS_IlpFund_Compare` (its final `TC_Build_Mastersheet_Column` step is missing, so the mastersheet column is not written) | `custom-pages/TS_Custom_PreT0_Baseline` / `TS_Custom_PostT0_Compare` — bind 7 test cases, of which only `TC_IlpFund` exists |
| `tools/offline-checks/run.sh [replay]` | any suite named `collections/TSC_…` — **no `.tsc` file exists in this repository** |

Tracked in [../overview/project-tracking.md](../overview/project-tracking.md).
