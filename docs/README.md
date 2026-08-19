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
| [reference/](reference/) | Stable reference: verdicts, the score, the report contract |
| [guides/](guides/) | How-to: running the suites, the tuning loop, troubleshooting |
| `flows/` | Runbooks for proven workflows — written after the workflow ran green. *Not created yet* |
| `plans/` | Dated design plans (historical archive). *Not created yet* |

## Start here

0. [guides/getting-started.md](guides/getting-started.md) — fresh clone → a run that
   produces a report, plus what to do when it breaks.
1. [overview/project-tracking.md](overview/project-tracking.md) — current status, what to do next.
2. [architecture/how-the-check-works.md](architecture/how-the-check-works.md) — crawl → extract → compare, end to end.
3. [reference/verdicts-and-score.md](reference/verdicts-and-score.md) — what each verdict means, and how the score is computed.
4. [reference/report-contract.md](reference/report-contract.md) — **read before writing a check**: what must land on disk for the report to render it.
5. [guides/running-tests.md](guides/running-tests.md) — the four modes and which suite to run.

## Quick run matrix

Suite paths are under `Test Suites/migration-aem/`.

| Goal | Run | Needs |
|---|---|---|
| Everything, outside Katalon, in seconds | `tools/offline-checks/run.sh replay` | nothing |
| Re-judge without re-crawling | `normal-pages/by-page/TS_GeneralContentDetailPage_Recompare` | nothing |
| Capture the live side, General Content Detail | `normal-pages/by-page/TS_GeneralContentDetailPage_Baseline` | Chrome (the live site is public) |
| Capture the AEM side only | `normal-pages/by-page/TS_GeneralContentDetailPage_Capture` | Chrome + VPN |
| Capture + judge in one run | `normal-pages/by-page/TS_GeneralContentDetailPage_Compare` | Chrome + VPN |
| PRULink funds, capture + judge | `custom-pages/by-page/TS_IlpFund_Compare` | Chrome + VPN |

Those are all of them. The group suites (`TS_Normal_*`, `TS_Custom_*`) bind page-type test
cases that have not been written, and there are no test-suite collections in the repository
— see [guides/getting-started.md](guides/getting-started.md#7-known-gaps--what-you-cannot-run-yet).
