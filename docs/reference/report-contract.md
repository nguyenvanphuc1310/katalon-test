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

### The slug names every file, and file names are not case-sensitive here

`AuditUtils.slugOf` strips the domain and collapses every run of non-alphanumerics to `_`, so
`/a/b-c` and `/a-b/c` are one slug, one snapshot, one `findings.csv`, one verdict and one page in
the report — the second page checked silently overwrites the first. The scheme is deliberately
**not** changed: it names every file already on disk, so changing it is a migration.

`ReportBuilder.warnOnSlugCollisions` makes that loud instead, and it buckets slugs
**case-insensitively**, because what actually collides is a *file name* and macOS ships APFS
case-insensitive. Comparing slugs exactly — as it did until 2026-08-22 — misses the case:
`…/sustainability/Responsible Investment` and `…/sustainability/responsible-investment` are two
distinct slugs and one directory. That pair is real, it is in the mapping today, and it is why
the 2026-08-22 report counts **270** pages compared but writes **269** page files.


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
| `ContentAudit/<slug>/findings.csv` | `verdict,kind,"path","text","note",weight` | one row per live **text**. `weight` is **written** since 2026-08-21 (`ContentCompare.WEIGHTS`); it must nonetheless stay **optional** in the reader's regex — see below. Verdicts in `ContentCompare.NO_CSV` are **not** written here — see below |
| `ContentAudit/<slug>/score.csv` | `field,value` | `score`, `grade`, `items`, `lost`, `hardFailures`, `confidence`, `confidenceWhy`. Written by `ContentCompare.write()` and read by `ReportBuilder.readScore()` since 2026-08-21 |
| `ContentAudit/<slug>/state_pairs.csv` | `pair,sc_id,sc_label,aem_id,aem_label,by,score` | rendered as the tab-pairing table |

### What `text` and `note` hold

The two columns carry different things under different verdicts, which is why the report's column
headers are **per verdict** (`ReportBuilder.FINDING_COLUMNS`, defaulting to
`FINDING_COLUMNS_DEFAULT`) rather than one row of headers for all nine blocks.

| Verdict | `text` | `note` |
|---|---|---|
| `TEXT_CHANGED`, `NUMBER_CHANGED` | the **Sitecore** wording | the **AEM** wording, bare |
| `MISSING_ON_AEM` | the Sitecore wording | empty — there is no AEM counterpart |
| `ONLY_ON_AEM`, `STATE_ONLY_ON_NEW` | the **AEM** text (`path` is an AEM location too) | a sentence, or empty |
| `WRONG_TAB`, `COUNT_MISMATCH`, `STATE_ONLY_ON_LIVE` | the Sitecore wording, or a gap description | a sentence of **explanation**, not AEM text |
| `SCOPE_ASYMMETRY` | *(no longer written — see below; older files hold a gap description)* | *(a sentence of explanation)* |

Only `TEXT_CHANGED` therefore gets the headers `Where on the live page / Sitecore / AEM`; every
other block keeps `… / Text / Note`, because naming the sites there would label five blocks out of
nine wrongly.

**`note` no longer carries a label in front of the AEM wording** (2026-08-24). It used to read
`new page says: <AEM text>`, and `NUMBER_CHANGED` prefixed that with
`figures changed: [30000] -> [10000]; `. The column header names the side now, so the label only
repeated it. `ContentCompare` stopped writing both, **and** `ReportBuilder.stripNoteLabel()` removes
them on read — so a `findings.csv` captured before the change renders identically to a fresh one,
with no re-crawl. Do not delete that strip until every snapshot on disk has been re-compared.

### Findings that are never written, and findings that are never drawn (2026-08-24)

Two lists, one on each side of the contract, and they must stay in step:

| List | Where | Effect |
|---|---|---|
| `ContentCompare.NO_CSV` | the producer | the finding is kept in `result.findings` but **not written** to `findings.csv` |
| `ReportBuilder.NOT_RENDERED` | the renderer | a row with that verdict is read and classified, but **draws no block** |

Today both hold exactly `SCOPE_ASYMMETRY`. The producer's list stops new runs writing the row;
the renderer's list is what keeps the report honest about the `findings.csv` files **already on
disk**, which still carry it. Drop the renderer's list before every page has been re-compared and
27 red panels come back.

Why it is not a row: `findings.csv` is the per-**text** evidence, and the report tabulates it as
*"N of M live texts did not survive"*. `SCOPE_ASYMMETRY` is not a live text — it is a statement
about the crawl — so it was being counted as a failed text on pages where every text was found.

**None of this touches the gate.** The finding is still produced and still in
`ContentCompare.HARD_FAIL`, and the page verdict the report renders comes from `content.txt`, not
from `findings.csv`. What the reader sees instead is `score.csv`'s `confidenceWhy`, rendered as
the low-confidence callout, which now carries the measurements themselves.

Three consequences for the renderer, all handled in `renderCheckFile`:

- a page can be **FAIL with zero failed texts**, so the status line must not read *"All N live
  text(s) were found on the new page"* beside a red badge;
- the above-the-pass-mark callout must only promise *"The finding is below"* when a block is
  actually drawn;
- the low-confidence callout must not say *"read the findings"* when there are none.

`SCOPE_ASYMMETRY` deliberately stays in `ERRORS` so the unknown-verdict warning below does not
fire on the stale rows, and is excluded from the failed-text counts by hand in `checkStatsOf`.

The score is **read from `score.csv`, never recomputed** by the report. Recomputing it would be
a second implementation of the formula, free to disagree with the one that actually set the
verdict, and the reader would see a score of 96 beside a FAIL with no way to tell which half was
wrong. `ReportBuilder` re-declares only `PASS_SCORE`/`WARN_SCORE`, and only to colour
**averages**; a single test case is coloured by the grade the check recorded.

The parsers are strict and **drop rows they cannot match**. That is the worst failure mode a
report can have — it just goes quiet — which is why the contract has its own assertion harness.

Since 2026-08-21 `readFindings` at least **says so**, on the three ways a row can vanish:

- a line that does not match the pattern — a contract break;
- a line that matches but carries a verdict listed in neither `ERRORS`, `WARNINGS` nor `INFOS`.
  `findingBlocks` walks `FINDING_ORDER` and `checkStatsOf` tallies the three lists, so such a row
  renders nowhere **and** counts nowhere. This is not hypothetical either: 18 `OPTION_MISSING`
  rows, left by the build that predated the verdict's removal, were invisible in the
  2026-08-20 report.
- a line that exhausts the stack while being matched (added 2026-08-22, see below).

All three log a `KeywordUtil.markWarning` naming the file. A warning in the run log is not a
substitute for the removed harness — it only fires when someone runs the build and reads the log.

### The findings pattern

```
^(\w+),(\w*),"([^"]*(?:""[^"]*)*)","([^"]*(?:""[^"]*)*)","([^"]*(?:""[^"]*)*)"(?:,[-0-9.]+)?$
```

Two rules, each of which this project has already paid for once.

**The trailing weight stays optional.** `ReportBuilder`'s findings reader once required the five
quoted columns and nothing after them, while every `findings.csv` on disk ends with `,1.0`. The
`$` anchor therefore rejected **every row of every page**, and each page rendered "no findings"
under a `FAIL` verdict — with no error anywhere. Fixed 2026-08-20 with `(?:,[-0-9.]+)?$`.

**A quoted field is `[^"]*(?:""[^"]*)*` and never `(?:[^"]|"")*`.** The two match the same
language, but `(?:A|B)*` compiles to a **recursive** `Loop`/`Branch` pair in `java.util.regex` —
one set of stack frames per matched character — while a single-char class under `*` compiles to a
`Curly` that iterates. The alternation form survived a 9-page corpus and then killed the build the
first time the mapping widened to 270 pages: one 1760-char row (the SCB PDPA consent clause, a
~700-char `text` beside an ~800-char `note`) exhausted the stack and `TC_Build_Parity_Report`
died with a bare `java.lang.StackOverflowError` carrying **no frames at all**, because the JVM's
fast-throw optimisation strips the trace off a hot implicit throw. Measured afterwards on
Katalon's own JRE 21: even on a **fresh** 1 MB stack that row matches with zero frames to spare,
and `render()` runs hundreds of frames deep inside Katalon's runner. Fixed 2026-08-22; recursion
depth is now the number of `""` escape pairs, not the length of the field.

If you touch that pattern, re-render and check a rendered page actually lists its findings. An
empty page and a correct one look identical from the outside, and nothing asserts this any more —
the harness that did was removed with `tools/` on 2026-08-20. The cheap version of that check is
to compare totals: `grep -o '<tr><td>' Reports/parity-report/pages/*__content.html | wc -l`
against the row count of every `findings.csv`. They must be equal *once the `NOT_RENDERED`
verdicts are discounted* — until every page has been re-compared, the CSVs on disk still carry
`SCOPE_ASYMMETRY` rows that the report deliberately drops, so the identity is
`rendered = rows - (SCOPE_ASYMMETRY rows)`. It was 2203 = 2203 on 2026-08-22, and
**2192 - 27 = 2165** on 2026-08-24.

Count the rows with `awk`, not `tail | grep`: `ContentCompare.write()` joins with `'\n'` and
emits **no trailing newline**, so `tail -q -n +2 …/*.csv | grep -c .` glues the last row of each
file to the first row of the next and undercounts by one per page (2192 read as 2048). Use
`awk 'FNR>1 && NF{n++} END{print n}' Reports/ContentAudit/*/findings.csv`.

## Writing the output directory

`render()` builds the whole report into `Reports/.parity-report.tmp` and renames it over
`Reports/parity-report/` only once every file is written. It used to delete the output directory
as its first act and render into the hole, so any failure after that line destroyed a good report
to produce nothing — which is what the 2026-08-21 crash did, leaving two asset files, an empty
`templates/` and an empty `pages/`. A report is read far more often than it is built, and the
last good one is worth more than a fast swap.

This is safe only because **every link the report emits is relative and none of them names the
output directory**: `templateHref` prefixes `templates/`, `checkFile` returns a bare filename,
`head()` points at `assets/_site/`. Keep it that way. A `.parity-report.tmp` left on disk is the
wreckage of a failed build, not work in progress; the next run deletes it.

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
