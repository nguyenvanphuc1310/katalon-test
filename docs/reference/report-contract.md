# The report contract

> **This is the live contract.** `ReportBuilder` is the only renderer, and the `content.txt` +
> CSV scraping described here is how it gets its data. The JSON replacement once planned in
> [content-result-contract.md](content-result-contract.md) was deleted on 2026-08-20 along with
> its renderer; that page is now a design record, not a description of the code.
>
> `AuditUtils.recordResult` writes `content.txt`, so it is both the Katalon pass/fail marker and
> half of this contract.

`ReportBuilder` is built entirely from **files on disk**. It never reads the Katalon log.
Anything a check wants rendered, it must write. Read this before adding or changing a check.

## The one mandatory call

```groovy
AuditUtils.recordResult(pageurl, 'content', 'FAIL', summary)
// -> Reports/parity-results/<slug>/content.txt
//    line 1  = verdict
//    line 2+ = detail
```

Call it with the **AEM** URL: the slug comes from column 2 of `aem-url-mapping.csv`, and a
page absent from that CSV is never rendered at all.

## Closed lists

- **Check ids.** `CHECK_ORDER` and `ALL_CHECKS` are `['content']`. A file written under any
  other id is written and then ignored — silently.
- **Verdicts.** `PASS`, `WARN`, `FAIL`, `NOT_RUN`. The verdict string becomes a **CSS class**,
  so an invented or misspelled verdict renders unstyled and drops out of every failure count.
- `NOT_RUN` means "does not apply". A **missing file** means "not checked yet". They render
  differently and must not be confused.

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

| File | Format | Notes |
|---|---|---|
| `ContentAudit/<slug>/findings.csv` | `verdict,kind,"path","text","note",weight` | strict `$`-anchored regex; the `weight` column **must** stay optional in it — see below |
| `ContentAudit/<slug>/score.csv` | `field,value` quoted pairs | `score`, `grade`, `items`, `lost`, `hardFailures`, `confidence`, `confidenceWhy` |
| `ContentAudit/<slug>/state_pairs.csv` | `pair,sc_id,sc_label,aem_id,aem_label,by,score` | rendered as the tab-pairing table |

The parsers are strict and **drop rows they cannot match without saying so**. That is the
worst failure mode a report can have — it just goes quiet — which is why the contract has
its own assertion harness.

It has happened. `ReportBuilder`'s findings reader required the five quoted columns and
nothing after them, while every `findings.csv` on disk ends with `,1.0`. The `$` anchor
therefore rejected **every row of every page**, and each page rendered "no findings" under a
`FAIL` verdict — with no error anywhere. Fixed 2026-08-20 by making the trailing weight
optional (`(?:,[-0-9.]+)?$`). If you touch that pattern, run
check a rendered page actually lists its findings. An empty page and a correct one look
identical from the outside, and nothing asserts this any more — the harness that did was
removed with `tools/` on 2026-08-20.

## Checklist for a new check

1. Pick an id and add it to `CHECK_ORDER` / `ALL_CHECKS`, plus `CHECK_TITLE` and `CHECK_DESC`.
2. Call `AuditUtils.recordResult(aemUrl, id, verdict, summary)` — one of the four verdicts.
3. Put the headline on the **first** detail line; add a `summaryOf()` branch if it needs
   turning into plain English.
4. Write evidence under `Reports/<Kind>/<slug>/` and render it from `renderCheckCard`.
5. Re-render the report and open a page that should show the new evidence. Every parser here
   is strict and silent, so "it compiled" is not the test — "the page shows the row" is.
6. Update this page and `docs/overview/project-tracking.md` in the same session.
