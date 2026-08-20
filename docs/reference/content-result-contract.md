# The content-result contract — what `TC_Check_Content_Text` hands to the report

> **Not implemented. Kept as a design record only.**
> Its consumer, `migration/ContentReport.groovy`, was deleted on 2026-08-20 when the project
> settled on a single renderer, and no producer ever wrote a `Reports/content/<slug>.json`
> file — the sample data that existed came from `tools/report-demo/`, itself already removed.
> **The live contract is [report-contract.md](report-contract.md).** Read this page only if you
> are reviving the JSON hand-over; nothing below describes code in the repository today.

**Audience:** whoever writes the content check (the *producer*) and whoever writes the HTML
report (the *consumer*). It is the only agreement between them. Neither side reads the other's
code; both sides read this page.

**Scope of the current report:** content text only. No screenshots, no image comparison, **no
score** — the report renders the verdict and the finding counts and nothing else. The 0-100
score stays specified in [verdicts-and-score.md](verdicts-and-score.md) for later; nothing on
this page produces or consumes it.

Older checks handed their result over by writing a prose line into
`Reports/parity-results/<slug>/content.txt` and three CSV files, which the report re-parsed
with literal English regexes — see [report-contract.md](report-contract.md). That mechanism
drops rows it cannot match **without saying so**. This contract replaces it with one JSON file
per page.

---

## 1. The one file the check must write

```
Reports/content/<slug>.json
```

- `<slug>` is `AuditUtils.slugOf(aemUrl)` — the **AEM** URL, column 2 of
  `Data Files/aem-url-mapping.csv`. `https://aem-uat.prudential.com.sg/en/lifestage` →
  `en_lifestage`. A page absent from that CSV is never rendered, so its file is never written.
- One file per page, rewritten in full on every run. Never appended to.
- Written in the modes that produce a comparison: **`compare`** and **`recompare`**.
  `baseline` and `capture` only crawl, and write nothing here.
- UTF-8, no BOM. Pretty-printed or minified — the report does not care.

Two states that look alike and must not be confused:

| On disk | Means | Report shows |
|---|---|---|
| file **absent** | the page has not been checked yet | an empty row, "not checked" |
| file present, `"verdict": "NOT_RUN"` | the check does not apply (the URL serves a PDF) | a greyed row with `notRunReason` |

### Optional: the run header

```
Reports/content/run.json     { "runId", "startedAt", "finishedAt", "mode", "suite" }
```

Written once per suite run. The report prints it as a header line when it exists and omits the
line when it does not. Nothing else depends on it.

---

## 2. Page-level fields

Every field below is **required** unless the Required column says otherwise. Required means the
key is present with the right type — `""`, `0` and `[]` are legal values, a missing key is not.

| Field | Type | Required | Legal values / format | What breaks if it is wrong |
|---|---|:---:|---|---|
| `schema` | string | ✅ | exactly `"content-result@1"` | the report refuses a version it does not know rather than mis-reading it |
| `slug` | string | ✅ | must equal `slugOf(aemUrl)` | the detail page is written to the wrong filename and the index links 404 |
| `sitecoreUrl` | string | ✅ | absolute URL, live site | rendered as the "live page" link |
| `aemUrl` | string | ✅ | absolute URL, AEM UAT | rendered as the "new page" link; also the row identity |
| `pagegroup` | string | ✅ | `normal` \| `custom` | grouping in the index |
| `pagetype` | string | ✅ | slug from the mapping CSV, e.g. `general-content-detail-page` | the pagetype filter chip |
| `mode` | string | ✅ | `compare` \| `recompare` | shown in the detail header; tells a reader whether the AEM side was re-crawled |
| `checkedAt` | string | ✅ | ISO-8601 with offset, `2026-08-20T14:03:11+08:00` | the report sorts and dates by it; a bare local time cannot be compared across runs |
| `verdict` | string | ✅ | `PASS` \| `WARN` \| `FAIL` \| `NOT_RUN` — **closed list** | the string becomes a CSS class: an invented or misspelled verdict renders unstyled and drops out of every failure count |
| `notRunReason` | string | ✅ | non-empty when `verdict` is `NOT_RUN`, `""` otherwise | a `NOT_RUN` row with no reason is indistinguishable from a bug |
| `itemsCompared` | int | ✅ | ≥ 0 — live-page items that entered the comparison | the "Items" column; also how a reader judges whether 3 findings is a lot |
| `aemItems` | int | ✅ | ≥ 0 — items read on the new page | shown beside `itemsCompared` in the detail header |
| `counts` | object | ✅ | **all 11 verdict keys always present**, value ≥ 0 | see below |
| `findings` | array | ✅ | section 3; `[]` means a clean page | the detail table |
| `statePairs` | array | ✅ | section 4; `[]` is legal | the tab-pairing table |
| `snapshots` | object | ✅ | section 5 | lets a reader see a stale crawl instead of blaming the page |

### `counts`

All eleven keys, every time, `0` when the verdict did not fire. **An absent key must never be
used to mean zero** — the report would then be unable to tell "no missing text" from "the check
forgot to count missing text".

```json
"counts": {
  "MISSING_ON_AEM": 12, "WRONG_TAB": 0, "NUMBER_CHANGED": 1, "LINK_CHANGED": 0,
  "SCOPE_ASYMMETRY": 1, "COUNT_MISMATCH": 0, "OPTION_MISSING": 3, "TEXT_CHANGED": 4,
  "STATE_ONLY_ON_LIVE": 0, "STATE_ONLY_ON_NEW": 0, "ONLY_ON_AEM": 7
}
```

Each value must equal the number of entries in `findings[]` carrying that verdict. The report
uses `counts` for the index table and `findings` for the detail table; if they disagree, the
index lies.

### How the page verdict is decided

Not the report's decision — the check's. Stated here so it cannot be re-invented:

```
FAIL   if any finding's verdict is in ERRORS = [MISSING_ON_AEM, WRONG_TAB, NUMBER_CHANGED, LINK_CHANGED]
WARN   else if findings is non-empty
PASS   else
NOT_RUN if the URL is not an HTML page   (findings must then be [])
```

This is `ContentCompare.ERRORS` in
[`Keywords/migration/checks/ContentCompare.groovy`](../../Keywords/migration/checks/ContentCompare.groovy).
Any page that lost one item out of 127 is a **FAIL**. Size never softens it.

---

## 3. `findings[]`

One entry per defect. This is today's in-memory finding shape
(`verdict, kind, path, text, note`) plus the four fields the report needs and the flat CSV
could not carry (`id`, `tab`, `aemText`, `liveHref`/`aemHref`).

| Field | Type | Required | Legal values / format | Notes |
|---|---|:---:|---|---|
| `id` | string | ✅ | unique within the file, `f001`, `f002`, … | the report anchors to it (`#f012`), so it must be stable for a given run |
| `verdict` | string | ✅ | one of the **11** in [verdicts-and-score.md](verdicts-and-score.md) | drives the severity grouping and the row colour |
| `kind` | string | ✅ | `heading` \| `cta` \| `bullet` \| `para` \| `option` \| `tab` \| `accordion` \| `scope` | shown as a small badge. The first four are the item kinds from the snapshot; `option` is a dropdown choice, `tab`/`accordion` come from `STATE_ONLY_ON_LIVE` / `STATE_ONLY_ON_NEW`, `scope` is `SCOPE_ASYMMETRY` |
| `path` | string | ✅ | breadcrumb as captured: `"Our plans > PRUShield > tab:Medical & Accident"`; `""` when there is none | the "Where" column |
| `tab` | string | ✅ | the tab/section label alone, e.g. `Medical & Accident`; `""` when the item is not inside one | rendered as a badge so a reader sees the tab without reading the whole breadcrumb |
| `text` | string | ✅ | the **live page** text — the thing that is missing or changed | the "On the live page" column |
| `aemText` | string | ✅ | the counterpart found on the new page; `""` when there is none | the "On the new page" column. For `ONLY_ON_AEM` the extra text goes in `text` and `aemText` is `""` — see below |
| `liveHref` | string | ✅ | required for `LINK_CHANGED`, `""` elsewhere | the destination on the live page |
| `aemHref` | string | ✅ | required for `LINK_CHANGED`, `""` elsewhere | the destination on the new page |
| `note` | string | ✅ | the existing human sentence, or `""` | printed under the new-page cell |

### Which side `text` holds

`text` is always **the side that has the content**, so the report never renders an empty
headline cell:

| Verdict | `text` | `aemText` |
|---|---|---|
| `MISSING_ON_AEM`, `WRONG_TAB`, `COUNT_MISMATCH`, `OPTION_MISSING`, `STATE_ONLY_ON_LIVE` | live text | `""` |
| `TEXT_CHANGED`, `NUMBER_CHANGED`, `LINK_CHANGED` | live text | new-page text |
| `ONLY_ON_AEM`, `STATE_ONLY_ON_NEW` | the extra new-page text | `""` |
| `SCOPE_ASYMMETRY` | the measurement sentence | `""` |

For `LINK_CHANGED` the wording is identical on both sides by definition (that is how the two
links were matched), so `text` and `aemText` carry the same button label and the difference the
report shows is `liveHref` vs `aemHref`.

### Two rules the producer must not break

1. **No HTML, no markup, no entity encoding in any string.** Ship the raw text exactly as
   captured. Escaping is the report's job; a check that pre-escapes produces `&amp;amp;` on the
   page and cannot be un-done.
2. **No truncation, no ellipsis.** Ship the full text however long. Clamping with a "show more"
   is the report's job; a truncated string cannot be compared, searched or copied into a ticket.

Newlines inside `text` are fine — JSON carries them and the report renders them.

---

## 4. `statePairs[]`

Which tab/section on the live page was matched to which on the new page. Same data as today's
`state_pairs.csv`.

| Field | Type | Format |
|---|---|---|
| `scId` | string | live-side state id, `tab:0:0`, `acc:0` |
| `scLabel` | string | live-side label, `Medical & Accident` |
| `aemId` | string | new-side state id |
| `aemLabel` | string | new-side label |
| `by` | string | `label` \| `content` \| `position` — how they were matched |
| `score` | number | 0.00-1.00, match strength |

`[]` is legal and means the page has no tabs or accordions. It is **not** an error.

---

## 5. `snapshots`

```json
"snapshots": {
  "sitecore": { "capturedAt": "2026-08-18T09:41:02+08:00", "contentRoot": "main[role=main]", "profile": "prudential-sitecore@5" },
  "aem":      { "capturedAt": "2026-08-20T14:02:55+08:00", "contentRoot": "main",            "profile": "prudential-aem@5" }
}
```

Printed verbatim in the detail header. Its whole purpose is that a reader looking at a page
full of findings can see the live snapshot is two months old, or that the two sides used
different profile versions, **before** filing 40 content tickets.

---

## 6. The HTML report — where every field lands

Two pages. The producer does not have to build them; this section exists so both sides can see
that every field above has a consumer, and that the report asks for nothing that is not above.

### Index — `Reports/parity-report/index.html`

Header band: pages checked · FAIL / WARN / PASS / NOT_RUN counts · total findings by verdict ·
run timestamp from `run.json`. Below it, filter chips (verdict, `pagegroup`, `pagetype`) and a
free-text box filtering the table.

One row per page:

| # | Page | Live page | New page | Verdict | Missing | Wrong tab | Figures | Links | Reworded | Extra | Items | |
|--:|---|---|---|---|--:|--:|--:|--:|--:|--:|--:|---|
| 1 | `en_lifestage`<br/>`general-content-detail-page` | ↗ | ↗ | **FAIL** | 12 | 0 | 1 | 0 | 4 | 7 | 187 | Details → |

Column → field:

| Column | Field |
|---|---|
| Page | `slug` + `pagetype` badge (`pagegroup` as a second badge) |
| Live page / New page | `sitecoreUrl` / `aemUrl` |
| Verdict | `verdict` (as a coloured chip; `NOT_RUN` shows `notRunReason` on hover) |
| Missing | `counts.MISSING_ON_AEM` |
| Wrong tab | `counts.WRONG_TAB` |
| Figures | `counts.NUMBER_CHANGED` |
| Links | `counts.LINK_CHANGED` |
| Reworded | `counts.TEXT_CHANGED` |
| Extra | `counts.ONLY_ON_AEM` |
| Items | `itemsCompared` |
| Details | link to `pages/<slug>.html` |

The four red columns (Missing, Wrong tab, Figures, Links) are the ones that fail a page and are
grouped together, left of the warnings. Default sort: FAIL first, then `counts.MISSING_ON_AEM`
descending — the page needing the most work is the first row. `NOT_RUN` rows are greyed and
excluded from the failure totals in the header.

`counts.SCOPE_ASYMMETRY`, `COUNT_MISMATCH`, `OPTION_MISSING`, `STATE_ONLY_ON_LIVE` and
`STATE_ONLY_ON_NEW` have no column of their own — they appear in the header totals and in full
on the detail page. A page whose only finding is one of these still shows **WARN**, so it is
never invisible.

### Detail — `Reports/parity-report/pages/<slug>.html`

Header: `slug`, both URLs as links, the verdict chip, `checkedAt`, `mode`, `itemsCompared` vs
`aemItems`, and the two `snapshots` lines.

If the page carries a `SCOPE_ASYMMETRY` finding, its `text` and `note` are promoted to a banner
above everything else: it says every other number on the page rests on a weaker measurement,
and it must not be buried in row 30 of a table.

Findings table, grouped **errors → warnings → info**, each group collapsible with its count in
the heading:

| Verdict | Kind | Where | On the live page | On the new page |
|---|---|---|---|---|
| `verdict` chip | `kind` badge | `path`, with `tab` as a badge | `text` (+ `liveHref` when set) | `aemText` (+ `aemHref` when set), then `note` in small type |

Row anchor is `id`, so a finding can be linked to directly from a ticket.

Then the tab-pairing table from `statePairs[]`: live label/id · new label/id · matched `by` ·
`score`.

---

## 7. Worked example

A complete, valid file. The producer can diff their output against this shape field by field.

```json
{
  "schema": "content-result@1",
  "slug": "en_lifestage",
  "sitecoreUrl": "https://www.prudential.com.sg/lifestage",
  "aemUrl": "https://aem-uat.prudential.com.sg/en/lifestage",
  "pagegroup": "normal",
  "pagetype": "general-content-detail-page",
  "mode": "recompare",
  "checkedAt": "2026-08-20T14:03:11+08:00",
  "verdict": "FAIL",
  "notRunReason": "",
  "itemsCompared": 187,
  "aemItems": 201,
  "counts": {
    "MISSING_ON_AEM": 1,
    "WRONG_TAB": 0,
    "NUMBER_CHANGED": 1,
    "LINK_CHANGED": 0,
    "SCOPE_ASYMMETRY": 1,
    "COUNT_MISMATCH": 0,
    "OPTION_MISSING": 1,
    "TEXT_CHANGED": 1,
    "STATE_ONLY_ON_LIVE": 0,
    "STATE_ONLY_ON_NEW": 0,
    "ONLY_ON_AEM": 1
  },
  "findings": [
    {
      "id": "f001",
      "verdict": "MISSING_ON_AEM",
      "kind": "cta",
      "path": "Life's Journeys",
      "tab": "",
      "text": "Life's Journeys",
      "aemText": "",
      "liveHref": "",
      "aemHref": "",
      "note": ""
    },
    {
      "id": "f002",
      "verdict": "NUMBER_CHANGED",
      "kind": "para",
      "path": "Protect what matters > tab:Medical & Accident",
      "tab": "Medical & Accident",
      "text": "Get covered for up to S$100,000 with no medical underwriting.",
      "aemText": "Get covered for up to S$200,000 with no medical underwriting.",
      "liveHref": "",
      "aemHref": "",
      "note": "live page states 100,000; new page states 200,000"
    },
    {
      "id": "f003",
      "verdict": "TEXT_CHANGED",
      "kind": "cta",
      "path": "Our plans",
      "tab": "",
      "text": "A customisable critical illness plan",
      "aemText": "A customisable critical illness insurance plan",
      "liveHref": "",
      "aemHref": "",
      "note": "new page says: A customisable critical illness insurance plan"
    },
    {
      "id": "f004",
      "verdict": "OPTION_MISSING",
      "kind": "option",
      "path": "",
      "tab": "",
      "text": "warm",
      "aemText": "",
      "liveHref": "",
      "aemHref": "",
      "note": "a dropdown choice on the live page that the new page does not offer"
    },
    {
      "id": "f005",
      "verdict": "SCOPE_ASYMMETRY",
      "kind": "scope",
      "path": "",
      "tab": "",
      "text": "128 hidden element(s) skipped on the live page against 8 on the new page",
      "aemText": "",
      "liveHref": "",
      "aemHref": "",
      "note": "the two sides did not read comparable content, so presence and occurrence counts on this page are weaker than they look"
    },
    {
      "id": "f006",
      "verdict": "ONLY_ON_AEM",
      "kind": "cta",
      "path": "",
      "tab": "",
      "text": "Home",
      "aemText": "",
      "liveHref": "",
      "aemHref": "",
      "note": ""
    }
  ],
  "statePairs": [
    { "scId": "acc:0", "scLabel": "Footnotes", "aemId": "acc:0", "aemLabel": "Footnotes", "by": "label", "score": 1.0 },
    { "scId": "tab:0:0", "scLabel": "Medical & Accident", "aemId": "tab:1:0", "aemLabel": "Medical & accident", "by": "label", "score": 0.99 }
  ],
  "snapshots": {
    "sitecore": { "capturedAt": "2026-08-18T09:41:02+08:00", "contentRoot": "main[role=main]", "profile": "prudential-sitecore@5" },
    "aem": { "capturedAt": "2026-08-20T14:02:55+08:00", "contentRoot": "main", "profile": "prudential-aem@5" }
  }
}
```

A `NOT_RUN` page is the short form:

```json
{
  "schema": "content-result@1",
  "slug": "en_docs_brochure",
  "sitecoreUrl": "https://www.prudential.com.sg/docs/brochure",
  "aemUrl": "https://aem-uat.prudential.com.sg/en/docs/brochure",
  "pagegroup": "normal",
  "pagetype": "general-content-detail-page",
  "mode": "compare",
  "checkedAt": "2026-08-20T14:03:11+08:00",
  "verdict": "NOT_RUN",
  "notRunReason": "Not an HTML page — the URL serves a document, so the content check does not apply",
  "itemsCompared": 0,
  "aemItems": 0,
  "counts": { "MISSING_ON_AEM": 0, "WRONG_TAB": 0, "NUMBER_CHANGED": 0, "LINK_CHANGED": 0, "SCOPE_ASYMMETRY": 0, "COUNT_MISMATCH": 0, "OPTION_MISSING": 0, "TEXT_CHANGED": 0, "STATE_ONLY_ON_LIVE": 0, "STATE_ONLY_ON_NEW": 0, "ONLY_ON_AEM": 0 },
  "findings": [],
  "statePairs": [],
  "snapshots": { "sitecore": {}, "aem": {} }
}
```

---

## 8. Checklist before handing over

The producer ticks these; the consumer can assert every one of them.

- [ ] `schema` is `content-result@1`
- [ ] one file per mapped page, named `slugOf(aemUrl).json`, under `Reports/content/`
- [ ] `verdict` is one of the four; `NOT_RUN` carries a `notRunReason` and empty `findings`
- [ ] the verdict follows the ERRORS rule in section 2 — no page with a `MISSING_ON_AEM`,
      `WRONG_TAB`, `NUMBER_CHANGED` or `LINK_CHANGED` finding is anything but `FAIL`
- [ ] all **11** `counts` keys present, each equal to the number of matching entries in `findings`
- [ ] every finding has all 10 fields, `id` unique within the file
- [ ] every `verdict` inside `findings` is one of the 11; every `kind` one of the 8
- [ ] no HTML, no entity encoding, no truncation, no ellipsis in any string
- [ ] `LINK_CHANGED` findings carry both `liveHref` and `aemHref`
- [ ] the file parses as JSON and is UTF-8 without BOM

## What is deliberately not in this contract

- **The score.** Out of scope for this report — [verdicts-and-score.md](verdicts-and-score.md)
  keeps the specification for when it comes back. No `weight`, no `score`, no `grade`, no
  `confidence` field here.
- **Screenshots and image evidence.** Out of scope for the project — see
  [../architecture/scope.md](../architecture/scope.md).
- **Anything from the Katalon log.** The report is built only from files on disk. If the
  report should show it, this contract must carry it.
