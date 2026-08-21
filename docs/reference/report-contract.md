# The report contract

> **This is the live contract.** `ReportBuilder` is the only renderer, and the `<check>.txt` +
> CSV scraping described here is how it gets its data. The JSON replacement once planned in
> [content-result-contract.md](content-result-contract.md) was deleted on 2026-08-20 along with
> its renderer; that page is now a design record, not a description of the code.
>
> `AuditUtils.recordResult` writes that file, so it is both the Katalon pass/fail marker and
> half of this contract.

`ReportBuilder` is built entirely from **files on disk**. It never reads the Katalon log.
Anything a check wants rendered, it must write. Read this before adding or changing a check.

## One check id = one test case

Since 2026-08-20 the report's third and fourth levels are per test case: a **check id** is a
test case, and it gets a row on every page it applies to plus a file of its own per URL
(`pages/<slug>__<check>.html`). See [project-layout.md](../architecture/project-layout.md) for
the four output levels.

Only `content` has a producer today. GA4, images and metadata are planned test cases: each will
appear in the report as soon as it writes the two files below, with no change to the renderer
beyond its four registry entries.

## The one mandatory call

```groovy
AuditUtils.recordResult(pageurl, 'content', 'FAIL', summary)
// -> Reports/parity-results/<slug>/content.txt
//    line 1  = verdict
//    line 2+ = detail
```

Call it with the **AEM** URL: the slug comes from column 2 of `aem-url-mapping.csv`, and a
page absent from that CSV is never rendered at all. The file is named after the check id, so
a `ga4` check writes `Reports/parity-results/<slug>/ga4.txt`.

## Closed lists

- **Check ids.** `CHECK_ORDER` in `ReportBuilder` is `['content']`. A file written under any
  other id is written and then ignored — silently. An id listed there needs an entry in all
  three of `CHECK_TITLE`, `CHECK_DESC` and `CHECK_EVIDENCE`.
- **Verdicts.** `PASS`, `WARN`, `FAIL`, `NOT_RUN`. The verdict string becomes a **CSS class**,
  so an invented or misspelled verdict renders unstyled and drops out of every failure count.
- `NOT_RUN` means "does not apply". A **missing file** means "not checked yet". They render
  differently and must not be confused: `NOT_RUN` gets a test-case file saying the URL serves
  no page to compare, a missing file gets an unlinked "not run yet" row. Neither counts as a
  pass or a fail in any tally.

## The page verdict

A page's verdict is the **worst** verdict any of its test cases reached (`pageVerdict`): one
failed test case fails the page, however many others passed. It is what the index, the template
tables and every pass rate count. That rate stays a **page** rate and never becomes a test-case
rate — see [verdicts-and-score.md](verdicts-and-score.md).

`WARN` is a judged verdict, not a missing one. It is in `JUDGED` alongside `PASS` and `FAIL`,
and in `THROUGH` alongside `PASS` — so a warned page is in the pass rate's denominator **and**
its numerator, coloured amber rather than green. Leaving `WARN` out of `JUDGED` is the specific
mistake this line exists to prevent: every warned page would silently join the "not run" bucket
and be reported as work nobody has done.

## Parsed by regex

`itemsCompared()` reads **one number** out of the first detail line:

```
<N> live items compared: ...
```

That opening clause is the whole of the prose contract now. `summaryOf()` — which re-parsed
the entire line (`... missing, ... in the wrong tab, ... reworded, ... only on the new page`,
plus `... with changed figures, ... appearing fewer times`) into an English sentence — was
**removed on 2026-08-20** along with the sentence it printed: the per-page counts are taken
from `findings.csv`, where each finding is a row with its own verdict, instead of from prose.
The clause must still lead the line: new counters go **after** the existing ones, never in
front of it.

## Evidence files

Each check id names its own evidence folder through `CHECK_EVIDENCE` in `ReportBuilder`
(`content` → `ContentAudit`), so the paths below are `Reports/<that folder>/<slug>/`.

| File | Format | Notes |
|---|---|---|
| `ContentAudit/<slug>/findings.csv` | `verdict,kind,"path","text","note",weight` | `weight` is **written** since 2026-08-21 (`ContentCompare.WEIGHTS`); it must nonetheless stay **optional** in the reader's regex — see below |
| `ContentAudit/<slug>/score.csv` | `field,value` | `score`, `grade`, `items`, `lost`, `hardFailures`, `confidence`, `confidenceWhy`. Written by `ContentCompare.write()` and read by `ReportBuilder.readScore()` since 2026-08-21 |
| `ContentAudit/<slug>/state_pairs.csv` | `pair,sc_id,sc_label,aem_id,aem_label,by,score` | rendered as the tab-pairing table |

The score is **read from `score.csv`, never recomputed** by the report. Recomputing it would be
a second implementation of the formula, free to disagree with the one that actually set the
verdict, and the reader would see a score of 96 beside a FAIL with no way to tell which half was
wrong. `ReportBuilder` re-declares only `PASS_SCORE`/`WARN_SCORE`, and only to colour
**averages**; a single test case is coloured by the grade the check recorded.

The parsers are strict and **drop rows they cannot match**. That is the worst failure mode a
report can have — it just goes quiet — which is why the contract has its own assertion harness.

Since 2026-08-21 `readFindings` at least **says so**, on the two ways a row can vanish:

- a line that does not match the pattern — a contract break;
- a line that matches but carries a verdict listed in neither `ERRORS`, `WARNINGS` nor `INFOS`.
  `findingBlocks` walks `FINDING_ORDER` and `checkStatsOf` tallies the three lists, so such a row
  renders nowhere **and** counts nowhere. This is not hypothetical either: 18 `OPTION_MISSING`
  rows, left by the build that predated the verdict's removal, were invisible in the
  2026-08-20 report.

Both log a `KeywordUtil.logWarning` naming the file. A warning in the run log is not a substitute
for the removed harness — it only fires when someone runs the build and reads the log.

It has happened. `ReportBuilder`'s findings reader required the five quoted columns and
nothing after them, while every `findings.csv` on disk ends with `,1.0`. The `$` anchor
therefore rejected **every row of every page**, and each page rendered "no findings" under a
`FAIL` verdict — with no error anywhere. Fixed 2026-08-20 by making the trailing weight
optional (`(?:,[-0-9.]+)?$`). If you touch that pattern, run
check a rendered page actually lists its findings. An empty page and a correct one look
identical from the outside, and nothing asserts this any more — the harness that did was
removed with `tools/` on 2026-08-20.

## Checklist for a new check (= a new test case)

1. Pick an id and add it to `CHECK_ORDER`, plus an entry in each of `CHECK_TITLE`,
   `CHECK_DESC` and `CHECK_EVIDENCE`. That is the whole of the renderer's side.
2. Call `AuditUtils.recordResult(aemUrl, id, verdict, summary)` — one of the four verdicts.
   It writes `Reports/parity-results/<slug>/<id>.txt`.
3. Put the headline on the **first** detail line, opening with the `<N> live items compared`
   clause if the test case counts items — that clause is all `itemsCompared()` parses.
4. Write evidence to `Reports/<CHECK_EVIDENCE[id]>/<slug>/findings.csv` in the format above.
   Rows whose verdict is in `ERRORS` are counted as content that did not survive, and their
   weights decide the band. A check that wants a score writes `score.csv` too; one that does
   not simply omits it, and the report renders that test case without a score rather than
   inventing one.
5. Re-render the report and open the test case's own file on a URL that should show the new
   evidence. Every parser here is strict and silent, so "it compiled" is not the test — "the
   file lists the rows" is.
6. Update this page and `docs/overview/project-tracking.md` in the same session.
