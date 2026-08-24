# Project layout

## Keywords

| File | Lines | Role |
|---|---:|---|
| `migration/ContentScope.groovy` | 340 | The JavaScript that reads a page: resolve the content root, enumerate tabs/accordions, emit content items. All per-host selectors come from `site-profiles.json`. |
| `migration/ContentSnapshot.groovy` | 100 | Navigate, read through `ContentScope`, write `<slug>.<side>.json` + `.html`. |
| `migration/StateMatch.groovy` | 151 | Pair the tab panels and accordion sections of the two sides by score, not by label. |
| `migration/checks/ContentCompare.groovy` | 434 | The diff and the score. **No browser, no network** — reads two JSON files. |
| `migration/checks/ContentTextCheck.groovy` | 92 | Orchestrates the four modes and records the verdict. |
| `migration/WebActions.groovy` | 217 | Open a page, pin the viewport, dismiss cookies, scroll, expand hidden content. |
| `migration/AuditUtils.groovy` | 90 | The on-disk contract: `slugOf`, `baselinePath`, `reportDir`, `recordResult`, `csvq`. |
| `migration/ReportBuilder.groovy` | 989 | **The only renderer.** The HTML report and nothing else, under `Reports/parity-report/`: index + one file per template + one file per page + one file per test case of a page. |

The report has four levels, each listing only what the next one opens:

| File | Lists |
|---|---|
| `index.html` | the run, then one row per template |
| `templates/<group>-<pagetype>.html` | the pages of that template, with how many test cases each one passed and failed |
| `pages/<slug>.html` | the test cases run against that URL |
| `pages/<slug>__<check>.html` | one test case on one URL — its counts and its findings |

A **test case is a check id** — one entry of `CHECK_ORDER`. Only `content` has a producer
today; GA4, images and metadata are registry entries away. The contract for adding one is in
[report-contract.md](../reference/report-contract.md).

`AuditUtils` is the shared floor, not a helper for `ReportBuilder`: it defines the slug, the
baseline paths, the evidence paths and the verdict file format. `ReportBuilder` can read what
the check wrote only because both go through it.

## Data

- `Data Files/aem-url-mapping.csv` — **the** source of URLs.
  Columns: `sitecoreurl,aemurl,aemtemplate,pagegroup,pagetype`. Its URLs are **absolute**:
  `ReportBuilder` renders them as the report's clickable links.
- `Data Files/url-mapping/<group>/<slug>.csv` + `.dat` — generated slices, one per page type,
  bound to that page type's test case by the suites. **15 exist** (10 normal, 5 custom), covering
  all 1,823 master rows; the CSV is named by the pagetype slug, the `.dat` by its PascalCase form,
  and that `.dat` name must equal the `testDataId` the group suites reference.
  **Every** slice carries **paths** (`/en/lifestage`) instead of absolute URLs, with the scheme +
  host supplied per environment by the suite (since 2026-08-24).
  Master and slice still have to agree, now **by path** — `AuditUtils.normalizeKey()` and
  `slugOf()` both strip the host, so the two forms index and name files identically.
- `Data Files/site-profiles.json` — per-host extraction selectors. Adding a CMS is a JSON
  block, not a code change. The `id` carries a version (`@5`) so a snapshot taken before a
  shape change is identifiable.
- `Data Files/baselines/<group>/<pagetype>/snapshot/<slug>.<side>.{json,html}` — the crawl.

## Test cases

Five on disk, all under `Test Cases/migration-aem/`:

- `checks/TC_Check_Content_Text` — the check itself (`sitecoreurl`, `pageurl`, `mode`).
- `checks/TC_Build_Parity_Report` — a three-line wrapper over `ReportBuilder.build()`.
- `templates/normal-pages/TC_GeneralContentDetailPage`, `templates/normal-pages/TC_LbuHomepage`,
  `templates/custom-pages/TC_IlpFund` — one per page type. Each guards on `pagetype`, joins
  `sitecorehost`/`aemhost` onto the row's paths via `AuditUtils.absolute()`, and calls the
  content check. All three are identical in shape.

The target is one template per page type (26 of them). The remaining 23 are wired into the
group suites but have not been written — though since 2026-08-24 **10 of them already have their
`.csv` + `.dat`**, so what is missing is the test case, not the data.

`TC_Build_Baseline_Summary` was **deleted on 2026-08-20** together with `Exports.groovy`, and
the step was removed from the three baseline suites that ran it. `Reports/baseline-summary.html`
is no longer produced by anything. Verifying a capture is now a manual read of the snapshot
JSON — see [../guides/running-tests.md](../guides/running-tests.md#after-a-capture-verify-the-snapshot).

## Test suites

Ten on disk, all under `Test Suites/migration-aem/`:

- `<group>/by-page/TS_<Type>_Compare` — one page type: `TS_GeneralContentDetailPage_Compare`
  and `TS_IlpFund_Compare`. The proven type (`GeneralContentDetailPage`) also has
  `_Baseline`, `_Capture` and `_Recompare`. **These five are the runnable ones.**
- `<group>/TS_<Group>_PreT0_Baseline` / `TS_<Group>_PostT0_Compare` — whole group, four of
  them. They cannot start until the missing page-type test cases exist.
- `<group>/by-page/TSC_<Type>_Crawl` — one **test suite collection**, the tenth file:
  `normal-pages/by-page/TSC_GeneralContentDetailPage_Crawl`. It runs `_Baseline` and
  `_Capture` at the same time in two browsers, since the two sides write different files and
  share no state. See [../guides/running-tests.md](../guides/running-tests.md#crawling-both-sides-at-once).

**A collection is a `.ts` file, not a `.tsc` file.** `TestSuiteCollectionEntity.getFileExtension()`
returns `.ts`, the same extension a suite uses; Katalon tells the two apart by the root element
(`<TestSuiteCollectionEntity>` vs `<TestSuiteEntity>`) and by nothing else. Naming a collection
`.tsc` puts a file on disk that Studio never lists, with no error to say why — the `TSC_` name
prefix is what separates them for a reader. The collection's own fields are `executionMode`
(`SEQUENTIAL` | `PARALLEL`), `maxConcurrentInstances`, `delayBetweenInstances`, and a
`<testSuiteRunConfigurations>` wrapper whose entries name a suite by the same display-id path
form a `testCaseId` uses.

**Suites bind the data file and set `mode` themselves.** Editing a test case's variables by
hand is for investigating one page, not for running a suite.

## Tools

None. `tools/` (the offline runner and the report demo) was removed on 2026-08-20 — everything
runs through Katalon Studio.
