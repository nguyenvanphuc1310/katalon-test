# How the content check works — crawl, extract, compare

For *what code runs and in which mode*, see [workflow.md](workflow.md) — this page is about how a
page is judged once the crawl has happened.

Three stages. The first two need a browser, the third does not — and that split is what
makes the matching rules and the score weights tunable against real data.

## 1. Crawl

`ContentSnapshot.capture()` runs twice per URL, once per side:

1. `ensureOnPage(url, true)` — **forces a reload** rather than reusing whatever is open.
2. `scrollFullPage()` — trigger lazy-loading.
3. Inject `ContentScope`'s JavaScript and read the content out.
4. Write `<slug>.<side>.json` + `.html` under
   `Data Files/baselines/<group>/<pagetype>/snapshot/`.

The HTML must come from the live browser (`document.documentElement.outerHTML`): aem-uat
answers **403** to `HttpURLConnection`, and both sites inject content client-side, so a
server-side fetch would not see it anyway.

## 2. Extract — what a "content item" is

Selectors per host live in `Data Files/site-profiles.json`, not in code.

**Positive scoping.** Not `body` minus header/nav/footer, but a resolved **content root**:
`main[role=main]` on Sitecore, `main.main__container` on AEM. No root found → the check
**fails loudly** instead of silently falling back to `<body>`. This alone removes the whole
class of "Back to top" / "Skip to main content" findings, which live outside `<main>`.

**States.** Every tab panel and accordion section is read straight out of the DOM with its
label, **without clicking**: both CMSes keep all panels in the DOM. Sitecore's accordions
carry no `aria-controls`/`data-target`/`href`, so the profile names the panel explicitly via
`accordionPanel`.

**Items.** One per element, after dropping noise (`script`, `.sr-only`, `<option>`),
`<label>`s of hidden inputs, and elements that are hidden *and* not reachable through any
tab or accordion. Content that is hidden but a visitor can open is kept, tagged with how to
reach it.

```json
{ "text": "Plan your protection", "full": "Plan your protection", "kind": "cta",
  "path": "Protecting Your Family's Tomorrow", "stateId": "", "reach": "visible",
  "href": "/promotion/..." }
```

`path` is the nearest h1–h4 chain plus the tab/section containing the node. `full` is the
element's whole text; matching uses it, reporting uses the item — because Sitecore puts the
`PRU` of `PRUShield` in its own `<span>` while AEM writes it inline, so matching per node
never agrees.

The snapshot also carries `rootText` (whole-page text), per-state text, `formOptions`
(dropdown contents, which never render — captured but **no longer compared**, see below) and
the `skipped` counters.

## 3. Compare — browser-free

`ContentCompare.diff()` reads two JSON files. Sitecore is the **subset baseline**.

**Pair the states first** (`StateMatch`). Not keyed by label — scored: label agreement
(1.00), token overlap of the panel text (0.90), same position (0.55); assigned best-first;
nothing paired below 0.45. The two sites organise the same content differently — live splits
its plan finder by product family, new by need, and nests it two deep — so label keying
matched nothing, silently degraded to "present anywhere on the page", and `WRONG_TAB` could
never fire.

**Then, per live item, in order:**

| Question | Answer |
|---|---|
| In the **paired state**? | ✅ done |
| Not there, but elsewhere on the page? | 🔴 `WRONG_TAB` (only if that state has a counterpart) |
| Present, in the only place it can be judged? | count it → 🔴 `COUNT_MISMATCH` if fewer |
| Absent, but something scores ≥ 0.9 token overlap? | figures differ → 🔴 `NUMBER_CHANGED`, else 🔴 `TEXT_CHANGED` |
| Absent entirely | 🔴 `MISSING_ON_AEM` |

Presence is measured on a normalised blob (NFKC so `²` ≡ `2`, lowercased, unified
quotes/dashes) scoped to the paired state, with a spaceless fallback for fragments ≥ 20
characters so a sentence the two CMSes break differently still matches.

**Then the reverse and the structure:** AEM-only items → `ONLY_ON_AEM` (info); unpaired
states → `STATE_ONLY_ON_LIVE` / `STATE_ONLY_ON_NEW`; CTAs with the same wording but a
different destination → `LINK_CHANGED`.

**Dropdown contents are not compared.** `<option>` text never renders, so the collector takes
it without asking whether the enclosing `<select>` is visible — which swept up Sitecore's
hidden CRM fields, and every lifestage page reported the same three `Cold` / `Hot` / `Warm`
choices of the invisible `.leadData` LeadRating select. The `OPTION_MISSING` verdict was
removed on 2026-08-20; `formOptions` is still captured, and nothing reads it.

**Finally the check checks itself.** If one side discarded far more hidden elements than the
other, `SCOPE_ASYMMETRY` fires and says so: every finding above assumes the two sides read
comparable content, and when they did not, the numbers are measuring the extraction rather
than the page.

## 4. Result

`ContentTextCheck` records a **verdict** and `ContentCompare` computes a **score** — see
[verdicts-and-score.md](../reference/verdicts-and-score.md). Outputs:

| File | Contents |
|---|---|
| `Reports/parity-results/<slug>/content.txt` | verdict on line 1, summary from line 2 — named after the check id, which is also its test case in the report |
| `Reports/ContentAudit/<slug>/findings.csv` | one row per finding, with its score weight * |
| `Reports/ContentAudit/<slug>/score.csv` | score, grade, items, points lost, confidence * |
| `Reports/ContentAudit/<slug>/state_pairs.csv` | which tab paired with which, and how |

\* The `weight` column and `score.csv` are not written by the code currently on disk — see the
note in [verdicts-and-score.md](../reference/verdicts-and-score.md#score--the-ranking).
