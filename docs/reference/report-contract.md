# The report contract

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

`summaryOf()` matches the first detail line with a literal pattern:

```
<N> live items compared: <N> missing, <N> in the wrong tab, <N> reworded, <N> only on the new page
```

and a second one for `... with changed figures, ... appearing fewer times`. **Change the
wording, change the regex.** New counters must be *appended* after both clauses — which is
why the score is appended last, and why `tools/offline-checks/report-contract.groovy`
asserts that ordering.

## Evidence files

| File | Format | Notes |
|---|---|---|
| `ContentAudit/<slug>/findings.csv` | `verdict,kind,"path","text","note",weight` | strict regex; the `weight` column is optional so pre-scoring files still render |
| `ContentAudit/<slug>/score.csv` | `field,value` quoted pairs | `score`, `grade`, `items`, `lost`, `hardFailures`, `confidence`, `confidenceWhy` |
| `ContentAudit/<slug>/state_pairs.csv` | `pair,sc_id,sc_label,aem_id,aem_label,by,score` | rendered as the tab-pairing table |

The parsers are strict and **drop rows they cannot match without saying so**. That is the
worst failure mode a report can have — it just goes quiet — which is why the contract has
its own assertion harness.

## Checklist for a new check

1. Pick an id and add it to `CHECK_ORDER` / `ALL_CHECKS`, plus `CHECK_TITLE` and `CHECK_DESC`.
2. Call `AuditUtils.recordResult(aemUrl, id, verdict, summary)` — one of the four verdicts.
3. Put the headline on the **first** detail line; add a `summaryOf()` branch if it needs
   turning into plain English.
4. Write evidence under `Reports/<Kind>/<slug>/` and render it from `renderCheckCard`.
5. Add assertions to `tools/offline-checks/report-contract.groovy` for every regex and every
   positional reader you introduced.
6. Update this page and `docs/overview/project-tracking.md` in the same session.
