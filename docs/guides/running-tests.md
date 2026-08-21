# Running the checks

## The four modes

| Mode | What it does | Browser | VPN | Output |
|---|---|---|---|---|
| `baseline` | Capture the **live** (Sitecore) side | yes | no — the live site is public | `<slug>.sitecore.json` + `.html` |
| `capture` | Capture the **new** (AEM UAT) side | yes | **yes** | `<slug>.aem.json` + `.html` |
| `compare` | Capture AEM, then diff, then report | yes | **yes** | findings + score + verdict |
| `recompare` | Diff the snapshots already on disk | **no** | **no** | same, in seconds |

The intended loop is **baseline + capture once, recompare as often as you like**. Crawling
is the expensive half; judging is the half that gets re-run every time a matching rule or a
score weight changes.

## Which suite

All paths are under `Test Suites/migration-aem/`. These five are the suites that actually
execute today:

| Goal | Suite | mode | VPN |
|---|---|---|---|
| Live baseline, General Content Detail | `normal-pages/by-page/TS_GeneralContentDetailPage_Baseline` | `baseline` | no |
| Capture the AEM side only | `normal-pages/by-page/TS_GeneralContentDetailPage_Capture` | `capture` | yes |
| Capture + judge in one run | `normal-pages/by-page/TS_GeneralContentDetailPage_Compare` | `compare` | yes |
| Re-judge, no crawl | `normal-pages/by-page/TS_GeneralContentDetailPage_Recompare` | `recompare` | no |
| PRULink funds, capture + judge | `custom-pages/by-page/TS_IlpFund_Compare` | `compare` | yes |

Every compare/recompare suite ends with `TC_Build_Parity_Report`, so any run regenerates the
report. The `baseline` suites end with the last page they capture — they write snapshots and
nothing else, so verify them by reading the snapshot JSON (below).

**Not runnable yet.** The four group suites (`normal-pages/TS_Normal_PreT0_Baseline`,
`TS_Normal_PostT0_Compare`, `custom-pages/TS_Custom_PreT0_Baseline`,
`TS_Custom_PostT0_Compare`) bind 19 and 7 page-type test cases respectively, of which only
`TC_GeneralContentDetailPage`, `TC_LbuHomepage` and `TC_IlpFund` have been written. There
are **no test-suite collections** (`.tsc`) in the repository. `TS_IlpFund_Compare` and the
group compare suites also end with `TC_Build_Mastersheet_Column`, which does not exist as a
test case either — only as `ReportBuilder.mastersheetColumn`. See
[getting-started.md](getting-started.md#7-known-gaps--what-you-cannot-run-yet).

## Without Katalon Studio

Nothing in the repository runs the check outside Studio any more — `tools/` was removed on
2026-08-20. `TS_GeneralContentDetailPage_Recompare` is the equivalent inside Studio: it
re-judges the snapshots already on disk, with no browser and no VPN.

Anything run by hand must use **Katalon's own Groovy and JRE** under
`/Applications/Katalon Studio.app/Contents/Eclipse`: the system `java` is newer than Groovy 3
can read while Katalon executes on 21, and that mismatch is what produces the misleading
`UnsupportedClassVersionError` inside Studio.

`TS_GeneralContentDetailPage_Recompare` ends with `TC_Build_Parity_Report`, so one run
re-judges every snapshot and rebuilds the report. Use it to see what a rule change did to the
real numbers before running anything against the live sites.

## After a capture, verify the snapshot

A capture **overwrites** `Data Files/baselines/…/snapshot/<slug>.<side>.json` unconditionally, and
a crawl that read the page wrongly still writes a file and still passes. Open one of the files it
just wrote:

```
"profile": "prudential-aem@7",   # BOTH sides must read @7 — see below
"items": 168,                    # 150-400 for a General Content Detail page; under 10 = broken
"skipped": { "scanned": 1070 },  # 1,000-1,600; under 50 = the item walk died early
"rootText": "…"                  # 10,000+ characters
```

If those are wrong, do **not** run recompare: it will overwrite the previous results with a
comparison against nothing, and pages will flip to PASS because there was nothing left to miss.
Re-run the capture instead.

Since 2026-08-21 two gates enforce most of this for you, so the check above is a confirmation
rather than the only line of defence:

- **The capture refuses to overwrite a good snapshot with a worse one.** Fewer than 10 items, no
  `rootText`, or more than half the items / `rootText` / `scanned` lost against the file already
  on disk stops the run before the write. The previous snapshot stays; the rejected capture is
  written beside it as `<slug>.<side>.rejected.json` — open that one to see what the crawl read.
- **The comparison refuses an unjudgeable pair**, recording `FAIL` with
  `0 live items compared: the two snapshots cannot be compared — …` instead of numbers. Mixed
  `@N` shapes, a missing `rootText`, captures over 7 days apart, or an item walk that died early.

A run that stops is doing its job. The failure these replace was silent and green.

**Check the profile id on both sides, and check that they agree.** This is not bookkeeping — the
id says what shape the file has, and comparing two shapes silently produces findings that mean
nothing. It has already happened twice:

- `@4` items carry **no `href`**, so a `@4` AEM file against a `@5` Sitecore file makes
  `LINK_CHANGED` unable to fire at all — and its absence from the report then reads as "no link
  changed", which is not what was measured.
- `@9` makes `tabs` a list of widget configs and adds `mode: 'fragment', bringing Sitecore's outer
  lifestage tab widget into scope; `@8` added its popups.
- `@7` records a link destination on **any** item inside an anchor, not only those classified
  `cta` — a linked heading was classified `heading` and lost its destination, which is 326 real
  links across the 8 captured pages. It also adds `landedUrl` and `canonical`, so a capture can
  refuse a redirect, a soft-404 or an unpublished `/content/…` author page.
- `@6` splits the `aria-hidden` drop into `skipped.ariaHidden`, which `SCOPE_ASYMMETRY` adds to
  `skipped.hidden`. A pre-`@6` file reads that counter as 0, so the asymmetry figure is wrong in
  whichever direction that side happens to hide its content.

Both `skipped` maps should also carry the **same keys**. A missing `scanned` or `noText` on one
side means that file came out of an older collector, whatever its id says.

## Reading the results

| Where | What |
|---|---|
| `Reports/parity-report/index.html` | the report — run facts including the project pass rate, then one row per template with that template's pass rate; open a template to reach its pages |
| `Reports/parity-report/templates/<group>-<pagetype>.html` | one file per template — that template's own pass rate, then its pages with how many test cases each one passed and failed |
| `Reports/parity-report/pages/<slug>.html` | one file per compared page — the test cases run against that URL |
| `Reports/parity-report/pages/<slug>__<check>.html` | one file per test case of a page — the counts, then every failing text |
| `Reports/ContentAudit/<slug>/findings.csv` | every finding — `verdict,kind,"path","text","note"`. The score `weight` column is specified but not written by the current code |

The pass rate is a **page** rate: how many pages carry a PASS verdict, out of the pages that
were actually judged. Pages with no result on disk and pages recorded `NOT_RUN` are in neither
half of that fraction, so the denominator is normally smaller than "Compared in this run" — the
fraction is printed next to the percentage (`50% (4 of 8)`) for exactly that reason. It never
counts texts: a page that lost one text out of 127 is one failed page here, the same as a page
that lost all 127.

## Traps

**Stale compiled keywords.** A keyword edited outside the IDE can leave `bin/keyword/`
holding classes at a different version; Katalon then throws `UnsupportedClassVersionError`
naming the wrong file. Clear `bin/keyword/` and `.cache/Keywords/`. `run.sh` prints the
class-file versions so a mismatch shows up before Studio hits it.

**An 800-byte class file.** A single backslash in an embedded JS regex inside a
triple-quoted Groovy string makes Katalon's compiler emit an error stub instead of a class —
and the stub throws `UnsupportedClassVersionError`, which reads like a JDK problem and is
not. Check the class *size* first, then `javap -c` it: the real compiler message is inside.
`run.sh` flags anything under 1 KB.

**A test case variable that never binds.** A suite's `variableId` must match the id in the
bound test case's `.tc`. A mismatch does not error — the variable is simply never set. After
hand-editing or copying a suite, verify the ids.

**A URL that serves a PDF.** It can never have a snapshot. The baseline run records
`NOT_RUN`, and `recompare` honours that rather than demanding a baseline that cannot exist.
