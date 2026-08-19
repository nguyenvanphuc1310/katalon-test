# Project layout

## Keywords

| File | Lines | Role |
|---|---:|---|
| `migration/ContentScope.groovy` | 328 | The JavaScript that reads a page: resolve the content root, enumerate tabs/accordions, emit content items. All per-host selectors come from `site-profiles.json`. |
| `migration/ContentSnapshot.groovy` | 100 | Navigate, read through `ContentScope`, write `<slug>.<side>.json` + `.html`. |
| `migration/StateMatch.groovy` | 167 | Pair the tab panels and accordion sections of the two sides by score, not by label. |
| `migration/checks/ContentCompare.groovy` | ~530 | The diff and the score. **No browser, no network** — reads two JSON files. |
| `migration/checks/ContentTextCheck.groovy` | 86 | Orchestrates the four modes and records the verdict. |
| `migration/WebActions.groovy` | 217 | Open a page, pin the viewport, dismiss cookies, scroll, expand hidden content. |
| `migration/AuditUtils.groovy` | 90 | The on-disk contract: `slugOf`, `baselinePath`, `reportDir`, `recordResult`, `csvq`. |
| `migration/ReportBuilder.groovy` | ~1,370 | The HTML report, the publish bundle, `report.xlsx`, the baseline summary. |

`AuditUtils` is the shared floor, not a helper for `ReportBuilder`: it defines the slug, the
baseline paths, the evidence paths and the verdict file format. `ReportBuilder` can read what
the check wrote only because both go through it.

## Data

- `Data Files/aem-url-mapping.csv` — **the** source of URLs.
  Columns: `sitecoreurl,aemurl,aemtemplate,pagegroup,pagetype`.
- `Data Files/url-mapping/<group>/<slug>.csv` + `.dat` — generated slices, one per page type,
  bound to that page type's test case by the suites.
- `Data Files/site-profiles.json` — per-host extraction selectors. Adding a CMS is a JSON
  block, not a code change. The `id` carries a version (`@5`) so a snapshot taken before a
  shape change is identifiable.
- `Data Files/baselines/<group>/<pagetype>/snapshot/<slug>.<side>.{json,html}` — the crawl.

## Test cases

- `checks/TC_Check_Content_Text` — the check itself (`sitecoreurl`, `pageurl`, `mode`).
- `checks/TC_Build_Parity_Report`, `TC_Build_Mastersheet_Column`, `TC_Build_Baseline_Summary`
  — thin wrappers, 3–8 lines each, over `ReportBuilder`.
- `templates/<group>/TC_<PageType>` — 26 of them, one per page type. Each guards on
  `pagetype` and calls the content check.

## Test suites

- `<group>/TS_<Group>_PreT0_Baseline` / `TS_<Group>_PostT0_Compare` — whole group.
- `<group>/by-page/TS_<Type>_Compare` — one page type. The proven type
  (`GeneralContentDetailPage`) also has `_Baseline`, `_Capture` and `_Recompare`.
- `collections/TSC_PreT0_Baseline_All`, `TSC_PostT0_Compare_All`, `TSC_GCDP_Content_Full`.

**Suites bind the data file and set `mode` themselves.** Editing a test case's variables by
hand is for investigating one page, not for running a suite.

## Tools

`tools/offline-checks/run.sh` — compile, rule assertions, contract assertions, and with
`replay`, the whole compare half over the snapshots on disk plus a report rebuild. Runs on
Katalon's own Groovy and JRE.
