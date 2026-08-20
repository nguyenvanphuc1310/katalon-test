# Docs — Content parity, Sitecore → AEM

Documentation for the `parity` Katalon project (`www.prudential.com.sg` →
`aem-uat.prudential.com.sg`). All docs are written in English.

This project checks **content and nothing else**. Images, GA4, metadata and HTTP are out of
scope by design — see [architecture/scope.md](architecture/scope.md) for what was left out
and why.

## Structure

| Folder | Content |
|---|---|
| [overview/](overview/) | Living status doc: scope, phases, changelog, next steps |
| [architecture/](architecture/) | How the check works and how the project is laid out |
| [reference/](reference/) | Stable reference: verdicts, the contract `ReportBuilder` reads, the deferred score and the unimplemented JSON contract |
| [guides/](guides/) | How-to: running the suites, the tuning loop, troubleshooting |
| `flows/` | Runbooks for proven workflows — written after the workflow ran green. *Not created yet* |
| `plans/` | Dated design plans (historical archive). *Not created yet* |

## Start here

0. [guides/getting-started.md](guides/getting-started.md) — fresh clone → a run that
   produces a report, plus what to do when it breaks.
1. [overview/project-tracking.md](overview/project-tracking.md) — current status, what to do next.
2. [architecture/workflow.md](architecture/workflow.md) — **the map**: diagrams of the call chain and the four modes.
3. [architecture/how-the-check-works.md](architecture/how-the-check-works.md) — crawl → extract → compare, end to end.
4. [reference/verdicts-and-score.md](reference/verdicts-and-score.md) — what each verdict means (and the deferred score spec).
5. [reference/report-contract.md](reference/report-contract.md) — **the contract `ReportBuilder` actually reads**: the files on disk it turns into the report.
6. [reference/content-result-contract.md](reference/content-result-contract.md) — a JSON contract specified but **never implemented**; its renderer was deleted on 2026-08-20. Kept as a design record, not as a description of the code.
7. [guides/running-tests.md](guides/running-tests.md) — the four modes and which suite to run.

## Quick run matrix

Suite paths are under `Test Suites/migration-aem/`.

| Goal | Run | Needs |
|---|---|---|
| Re-judge without re-crawling | `normal-pages/by-page/TS_GeneralContentDetailPage_Recompare` | nothing |
| Capture the live side, General Content Detail | `normal-pages/by-page/TS_GeneralContentDetailPage_Baseline` | Chrome (the live site is public) |
| Capture the AEM side only | `normal-pages/by-page/TS_GeneralContentDetailPage_Capture` | Chrome + VPN |
| Capture + judge in one run | `normal-pages/by-page/TS_GeneralContentDetailPage_Compare` | Chrome + VPN |
| PRULink funds, capture + judge | `custom-pages/by-page/TS_IlpFund_Compare` | Chrome + VPN |

Those are all of them. The group suites (`TS_Normal_*`, `TS_Custom_*`) bind page-type test
cases that have not been written, and there are no test-suite collections in the repository
— see [guides/getting-started.md](guides/getting-started.md#7-known-gaps--what-you-cannot-run-yet).
