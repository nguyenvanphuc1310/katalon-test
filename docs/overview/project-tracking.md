# Project Tracking — parity (content only)

> Living document — update on every scope/status change (see the docs rule in
> [CLAUDE.md](../../CLAUDE.md)).

**Last updated:** 2026-08-19

## Goal

Verify that the text of every live Prudential SG page survived the Sitecore → AEM migration
(`www.prudential.com.sg` → `aem-uat.prudential.com.sg`), and score each page 0-100 so the
worst pages can be fixed first.

Sitecore is the **subset baseline**: whatever it has, AEM must have; extra content on AEM is
not an error.

- Current scope: 21 URL pairs in `Data Files/aem-url-mapping.csv`
  (1 LBU Homepage, 9 General Content Detail, 11 Custom Page / PRULink funds)
- Long-term target: ~2,000 pages

## Where this project came from

Split out of `test-1`, which checks content **and** images and produces screenshot evidence.
This project keeps the content half and the report shell, and drops everything binary — see
[architecture/scope.md](../architecture/scope.md). Carried over unchanged: `ContentScope`,
`ContentSnapshot`, `ContentCompare`, `StateMatch`, `site-profiles.json`, the URL mapping and
the 8 GCDP snapshots already captured. Added: the score.

| Component | test-1 | parity |
|---|---:|---:|
| `ReportBuilder` | 1,745 lines | ~1,370 |
| `WebActions` | 700 lines | 217 |
| `AuditUtils` | 259 lines | 90 |
| Image/evidence keywords | 4 files, ~65 KB | none |
| `Drivers/` jars | 6 (WebP) | none |
| Publish bundle | 30 MB | < 1 MB |

## Status

| Piece | State |
|---|---|
| Keywords compile (138 classes, one class-file version) | 🟢 |
| Rule + score assertions (`verdict-rules.groovy`) | 🟢 23/23 |
| Report contract assertions (`report-contract.groovy`) | 🟢 11/11 |
| Offline replay over the 8 carried snapshots | 🟢 scores reproduce the design table exactly |
| 30 test cases, 36 suites, 3 collections — no duplicate GUIDs, no mismatched `variableId` | 🟢 |
| `TS_GeneralContentDetailPage_Recompare` inside Studio | 🟢 9/9 pages judged |
| Live baseline + compare inside Studio | 🟡 in progress |

## Open points

1. **Every score on the carried data is low confidence.** Six pages fire `SCOPE_ASYMMETRY`
   (the live side discards 83–160 hidden elements against 8 on the new side) and three have
   fewer than 40 items. The report withholds the site average and says why rather than
   printing a figure that would have to be un-learned. Fixing this is a capture-side job.
2. **The carried snapshots are profile `@4`; `site-profiles.json` is `@5`.** They were taken
   before the capture-side fixes (CSS background heroes, inline `<svg>`, opening hidden
   content before collection, `scrollFullPage` measuring `documentElement`). A fresh
   baseline + capture at `@5` is what makes the numbers final.
3. **11 Custom Pages are not published on AEM UAT** (301 → 404). No baseline for them yet.
4. **`REWORD_OVERLAP = 0.9` is a tuning candidate.** One real reword on `young_family` sits
   at 0.75 and is therefore reported as an error-level `MISSING_ON_AEM` rather than a
   warning-level `TEXT_CHANGED`.

## Changelog

| Date | Change |
|---|---|
| 2026-08-19 | **Version control added.** The project is now a git repository on `main`, tracking 112 files, with `origin` set to `github.com/nguyenvanphuc1310/katalon-test`. The pre-existing Katalon `.gitignore` was kept as-is, so `Reports/` (21 MB of generated output), `bin/`, `Libs/`, `.gradle` and `.cache` stay untracked; `Data Files/baselines/` (6.9 MB of HTML snapshots) **is** tracked, because `tools/offline-checks/run.sh replay` re-diffs those snapshots — they are input, not output. The Katalon project file was renamed `parity.prj` -> `katalon-test.prj` (and its `<name>` element with it) to match the repo. The word "parity" everywhere else — docs, keyword names, `TC_Build_Parity_Report` — is domain vocabulary for the content-parity check and was deliberately left alone. |
| 2026-08-19 | **Project created.** Content-only fork of `test-1` with a 0-100 score per page. Copied `ContentScope` / `ContentSnapshot` / `ContentCompare` / `StateMatch` / `site-profiles.json` / URL mapping / 8 GCDP snapshots unchanged; trimmed `AuditUtils` (259→90 lines, the download/hash/HTTP half belonged to the image check), `WebActions` (700→217, every screenshot and diff-highlight member removed) and `ReportBuilder` (1,745→~1,370: image evidence, screenshot layers, the lightbox, asset copying and JPEG downscaling all gone, `CHECK_ORDER` down to one check). Dropped `ImageAssets`, `ImageHashCheck`, `DiffEvidence`, `PageCollectors`, `Ga4Check`, `MetadataCheck`, `HttpRedirectCheck`, the WebP jars and the `build.gradle` dependency on them. `ContentTextCheck` lost its `evidence` mode and is now 86 lines. **Added scoring**: `ContentCompare.WEIGHTS` + `score()`, a `weight` column in `findings.csv` and a new `score.csv`, a score chip in every table, a score panel showing the arithmetic on each page, a score-band filter replacing the now-meaningless "failing check" filter, and `score`/`grade`/`confidence` columns in `report.xlsx` — the score written as a real number, because a text column sorts 9 above 85. Two design decisions worth keeping: the score never overrides the verdict (one lost CTA on a 127-item page is 99.2 **and** a FAIL), and a weak score is shown with its reason rather than adjusted or hidden — on the carried data *every* score is weak, so the site average is withheld and the report says so at the top. Verified by 34 offline assertions, a full replay reproducing the design table exactly, and `TS_GeneralContentDetailPage_Recompare` inside Studio. **Two defects found by running it rather than assuming**: (1) the generated test-suite *collections* used a `<testSuiteRunConfiguration>` shape Katalon does not read — the run sat at PENDING and never started, fixed to the `<testSuiteRunConfigurations>` / `<TestSuiteRunConfiguration>` / `<configuration>` nesting Studio actually writes; (2) `recompare` hard-failed on a URL that serves a PDF, demanding "run mode=baseline first" — a baseline that can never produce a snapshot. It now honours a recorded `NOT_RUN`, which is what the baseline run correctly concluded. Also caught before it shipped: adding the `weight` column would have made `contentFindings`' `$`-anchored regex drop **every** row in silence — exactly the failure the report contract exists to prevent — so the column is optional in the pattern and asserted both ways. |
