# Getting started — from a fresh clone to a green run

This page takes a new machine to a run that produces a report. It stops where
[running-tests.md](running-tests.md) starts: that page is the reference for *which mode and
which suite*, this one is the reference for *getting there at all*.

## 1. Prerequisites

| | Why |
|---|---|
| **Katalon Studio 11.4, Enterprise** | The project was created and last modified with it (`katalon-test.prj`). Everything runs on Studio's bundled Groovy and JRE |
| macOS, Studio at `/Applications/Katalon Studio.app` | Where the project was built. Elsewhere, adjust the paths |
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
| `Keywords/`, `Test Cases/`, `Test Suites/`, `Data Files/`, `docs/` | `Reports/` — every run rewrites it |
| `Data Files/baselines/` — the crawled snapshots | `bin/`, `.cache/`, `Libs/`, `build/`, `.gradle/` |
| | `.classpath`, `.project`, `.settings/` — Studio writes them on first open |

`Data Files/baselines/` is tracked on purpose: `mode=recompare` re-diffs those snapshots, so
they are **input**, not output. Treat them as data you can lose — a capture overwrites its
snapshot unconditionally, so a broken crawl replaces a good baseline with no warning.

## 3. Verify the install

There is no browser-free runner in the repository any more — `tools/` was removed on
2026-08-20. Verify inside Studio instead:

```bash
rm -rf bin/keyword .cache/Keywords      # Katalon runs what is in bin/, not what you edited
```

then run `normal-pages/by-page/TS_GeneralContentDetailPage_Recompare`. It re-judges the nine
snapshots already on disk — no browser, no VPN — and `TC_Build_Parity_Report` at the end of
it rebuilds `Reports/parity-report/`. Green is every page that has a snapshot pair on disk
judged — nine today, out of the 281 pairs the mapping now names — and an index that lists
three templates.

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
| 2. **Check what step 1 wrote** — see below | — | no | no |
| 3. Capture the new side | `normal-pages/by-page/TS_GeneralContentDetailPage_Capture` | yes | **yes** |
| 4. Judge | `normal-pages/by-page/TS_GeneralContentDetailPage_Recompare` | no | no |

`TS_GeneralContentDetailPage_Compare` does steps 3 and 4 in one run.

Step 2 is not optional. A capture overwrites its snapshot unconditionally and a crawl that
read the page wrongly still writes a file and still passes, so the only thing between a
broken crawl and a report full of confident nonsense is somebody opening the file:

```
Data Files/baselines/normal-pages/general-content-detail-page/snapshot/<slug>.sitecore.json

"items": 168,                    # 150-400 here; under 10 means the crawl failed
"skipped": { "scanned": 1070 },  # 1,000-1,600; under 50 means the item walk died early
"rootText": "…"                  # 10,000+ characters
```

If they are wrong, re-run the capture. Do **not** go on to judge: comparing against an empty
snapshot flips pages to **PASS**, because nothing was left to be missing.

**Crawl once, re-judge as often as you like.** Crawling is the expensive half; judging is
the half that gets re-run every time a rule or a weight changes.

**The suite sets the mode and binds the data file.** Editing a test case's variables by hand
is for investigating a single page, not for running a suite.

## 5. Read the output

| File | What |
|---|---|
| `Reports/parity-report/index.html` | the report — run facts and one row per template |
| `Reports/parity-report/templates/<group>-<pagetype>.html` | one file per template — its pages, with texts compared and texts failed |
| `Reports/parity-report/pages/<slug>.html` | one file per compared page — the counts, then every failing text |
| `Reports/parity-results/<slug>/content.txt` | verdict on line 1, summary from line 2 |
| `Reports/ContentAudit/<slug>/findings.csv` | one row per finding, with its score weight |
| `Reports/ContentAudit/<slug>/score.csv` | score, grade, items, points lost, confidence |
| `Reports/ContentAudit/<slug>/state_pairs.csv` | which tab paired with which, and how |

The slug comes from the AEM URL in `Data Files/aem-url-mapping.csv`; a page absent from that
CSV is never rendered at all. What each verdict means and how the score is computed:
[../reference/verdicts-and-score.md](../reference/verdicts-and-score.md).

## 6. When it breaks

**`Launch terminated with non-zero exit code: 1`, and the console log says
`UnsupportedClassVersionError: CustomKeywords … class file version 70.0 … up to 65.0`.**
The project compiled against a JDK newer than the one Katalon runs on. Check
`.settings/org.eclipse.jdt.core.prefs`: `compliance`, `source` and `codegen.targetPlatform`
must be **21**, matching the `JavaSE-21` container in `.classpath` and Katalon's bundled JRE
21. A stray 26 there (the system JDK, or the `.github/modernize` tooling) produces class-file
version 70, which the runner refuses to load — it dies in `beforeStart()`, so the test case
never begins and no `Reports/<timestamp>/` folder appears. Fix the three values, delete
`bin/`, rebuild. The full log is at
`/var/folders/**/T/Katalon/<test case>/<timestamp>/console0.log`.

**If those three values already say 21, a *second* compiler is writing into `bin/`.** Katalon
is not the only tool that builds this project: Buildship (`.settings/org.eclipse.buildship.core.prefs`,
key `java.home`) and the VS Code Java language server both compile into the output folders
declared in `.classpath`, using *their own* JDK. On 2026-08-20 that was JDK 26, and it left a
version-70 `bin/lib/CustomKeywords.class` behind while the JDT prefs read 21 — the tell is that
the other classes next to it are an older version than the one in the error. Three places now
pin 21, and all three must stay pinned: `java.home` in the Buildship prefs, the `java` toolchain
block in [build.gradle](../../build.gradle), and `java.jdt.ls.java.home` /
`java.configuration.runtimes` in `.vscode/settings.json`. Verify with
`xxd -s 4 -l 4 -p bin/lib/CustomKeywords.class` — `00000041` is 21, `00000046` is 26.

**`UnsupportedClassVersionError` naming a keyword instead.** Stale compiled classes: Katalon
runs what is in `bin/keyword/`, not what you edited, so a keyword changed outside the IDE
leaves the previous build in place — the report comes out unchanged and nothing says why.
Delete `bin/keyword/` and `.cache/Keywords/`, then Project → Refresh. The tell is that the
class file lists methods the source no longer has.

**...and the class file is ~800 bytes.** Then it is not a JDK problem at all: a single
backslash in an embedded JS regex inside a triple-quoted Groovy string makes Katalon's
compiler emit an error *stub* — a valid class whose constructor throws. Check the size
first, then `javap -c` it; the real compiler message is inside. Any keyword class under
1 KB is a stub.

**Studio says `unable to resolve class migration.<X>`.** A Gradle/Buildship refresh replaced
Katalon's classpath with a container, and nothing resolves `com.kms.katalon.*` any more —
Studio blames the keywords with `unable to resolve class migration.<X>`. Close Studio,
delete `.classpath`, `.project`, `.settings/org.eclipse.buildship.core.prefs`,
`.settings/org.eclipse.jdt.core.prefs`, `.gradle` and `bin`, then reopen the project so
Studio regenerates them. Check first: `.classpath` should list ~300 `<classpathentry … .jar>`
entries; a build path reduced to a single container is the symptom.

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
| | any suite named `collections/TSC_…` — **no `.tsc` file exists in this repository** |

Tracked in [../overview/project-tracking.md](../overview/project-tracking.md).
