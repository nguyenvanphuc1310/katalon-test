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
| [flows/](flows/) | Runbooks for proven workflows — written after the workflow ran green |
| [plans/](plans/) | Dated design plans (historical archive) |

## Start here

1. [overview/project-tracking.md](overview/project-tracking.md) — current status, what to do next.
2. [architecture/how-the-check-works.md](architecture/how-the-check-works.md) — crawl → extract → compare, end to end.
3. [reference/verdicts-and-score.md](reference/verdicts-and-score.md) — what each verdict means, and how the score is computed.
4. [reference/report-contract.md](reference/report-contract.md) — **read before writing a check**: what must land on disk for the report to render it.
5. [guides/running-tests.md](guides/running-tests.md) — the four modes and which suite to run.

## Quick run matrix

| Goal | Run | Needs |
|---|---|---|
| Capture the live side, everything | `collections/TSC_PreT0_Baseline_All` | nothing (the live site is public) |
| Compare everything against it | `collections/TSC_PostT0_Compare_All` | VPN |
| One page type, end to end | `collections/TSC_GCDP_Content_Full` | VPN |
| One page type, compare only | `<group>/by-page/TS_<Type>_Compare` | VPN |
| Re-judge without re-crawling | `normal-pages/by-page/TS_GeneralContentDetailPage_Recompare` | nothing |
| Everything, outside Katalon | `tools/offline-checks/run.sh replay` | nothing |
