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
4. **Check the result against the snapshot already on disk, and refuse to overwrite a good one
   with a worse one.** `degradedAgainstPrevious()` stops the run when the new crawl holds fewer
   than `MIN_ITEMS = 10` items, carries no `rootText`, or has lost more than half its items,
   `rootText` or `scanned` count against the previous capture (`DEGRADED_RATIO = 0.5`). The
   rejected capture is written as `<slug>.<side>.rejected.json`; the previous file is untouched.
   The gate is relative as well as absolute because a healthy item count differs per pagetype —
   the previous capture of *this* page is the only honest reference for it.
5. **Check that the page which answered is the page that was asked for.** `wrongPageReason()`
   refuses the capture when the browser ended up on a different path (a redirect, a soft-404,
   or a wrong row in the mapping) or when the page's own `<link rel=canonical>` is an
   unpublished `/content/…` author path. Paths only — the domain differs between the two
   systems, AEM adds an `/en` prefix and a trailing slash, and a page with no canonical at all
   is normal and must pass. Selenium cannot see an HTTP status, so this is the only way a
   redirect or a soft-404 is detectable.
6. Write `<slug>.<side>.json` + `.html` under
   `Data Files/baselines/<group>/<pagetype>/snapshot/`.

A capture used to overwrite unconditionally, and the only guard was a warning logged *after* the
file was on disk. That is not a hypothetical: it is how `en_lifestage.aem.json` came to hold one
item, and comparing against it reported 17 texts as missing that are in the page's own HTML.

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

`tabs` is a **list** of widget configs, because a site can author more than one. Prudential has
two: the product widget (`ul.tabs-primary`, `data-tab` → `data-content`) and an outer
`Protection | Wealth | For your dependants` widget whose tab is `<a href="#wealth">` and whose
panel is `<div data-lifestage-tab="wealth">` — no element carries `id="wealth"`, so `mode:
'fragment'` resolves the panel by the value of a named attribute instead. Describing only the
first widget left the second's inactive panels unreachable: ~186 text-bearing elements a page,
dropped as hidden, which is what `SCOPE_ASYMMETRY` was reporting until 2026-08-21.

A reachable region has to be registered with the **right kind**. `StateMatch` refuses to pair a
tab with an accordion, so registering these as `accordionTrigger` entries would have left AEM's
three matching tabs with no counterpart even once their content was reachable.

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

**First, whether a comparison is possible at all.** `ContentTextCheck.incompatibility()` runs
before `diff()` and refuses the pair rather than producing numbers about it, recording `FAIL`
with `0 live items compared: …`. Four ways a pair is refused:

| Condition | Why it is not a weak result but a meaningless one |
|---|---|
| different `@N` collector shapes | fields added between versions are absent on one side, so checks that read them cannot fire — a `@4` AEM snapshot carries no `href`, which silently disabled `LINK_CHANGED` on every page |
| either side has no `rootText` | there is nothing to match against |
| captured more than `MAX_CAPTURE_SKEW_DAYS = 7` apart | either side may have changed in between |
| fewer than `MIN_JUDGEABLE_ITEMS = 10` items against ≥ `THIN_EXTRACTION_ROOTTEXT = 1000` characters | the item walk died early; the page's own content becomes findings against it |

That last row needs both bounds. `en_piliproductdeck` extracts 9 items from 101 characters and is
a real, tiny page; `en_lifestage.aem.json` extracts 1 from 5,769 and is a broken crawl. Neither
bound alone separates them.

The failure mode all four prevent is **PASS**: a comparison run over an incompatible pair does not
look broken, it looks clean.

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
states → `STATE_ONLY_ON_LIVE` / `STATE_ONLY_ON_NEW`.

**Link destinations are not compared** (removed 2026-08-21). `LINK_CHANGED` worked and found 37 real
destination changes, but all of them were one intentional site reorganisation, and the text, the tab
and the card were identical on both sides. This check asks whether the TEXT survived. `href` is still
captured on every item, so restoring the rule would not need a re-crawl — it would need a way to
ignore a known path migration first.

**Dropdown contents are not compared.** `<option>` text never renders, so the collector takes
it without asking whether the enclosing `<select>` is visible — which swept up Sitecore's
hidden CRM fields, and every lifestage page reported the same three `Cold` / `Hot` / `Warm`
choices of the invisible `.leadData` LeadRating select. The `OPTION_MISSING` verdict was
removed on 2026-08-20; `formOptions` is still captured, and nothing reads it.

**Finally the check checks itself.** Every finding above assumes the two sides read comparable
content; `SCOPE_ASYMMETRY` is what tests that assumption, and it **fails the page** when the
assumption is false — because when it is, the numbers are measuring the extraction rather than
the page, and a page that cannot be judged must not report that it passed.

It compares three measures, and any one of them is enough: hidden elements discarded (see
below), **items collected**, and **`skipped.scanned`** (only when both snapshots carry that
counter). Reading hidden elements alone is what let `en_lifestage` through — 30 live items
against 1 on the new side, with hidden counts of 12 against 0. All three use the same bar,
`hi >= SCOPE_ASYMMETRY_MIN (20)` and `hi >= lo * 3 + 10`, held in one place by `gapBetween()`.

*"Hidden elements" means every invisible drop, whichever mechanism hid them* —
`ContentCompare.hiddenCount()` adds `skipped.hidden` (no client rects) to `skipped.ariaHidden`
(inside `[aria-hidden=true]`) on both sides. They are two counters and one number for a reason
the check itself once fell for: **the two CMSes hide content differently.** Sitecore marks
nothing `aria-hidden`, so all of its invisible content is in `hidden`; AEM marks every inactive
panel, so its invisible content was in the *other* counter. Reading `hidden` alone therefore
compared 83–160 on the live side against a flat 8 on the new side on all five lifestage pages
and fired on every one of them — while `young_family.aem.html` carries 45 modals and 33
`aria-hidden="true"` nodes inside `<main>`. That was the counters talking, not the pages. Split
and summed on 2026-08-21, which is what bumped the profiles to `@6`: a snapshot below `@6` has
no `ariaHidden`, reads it as 0, and must not be compared against one at `@6`. The profiles moved
again to `@7` the same day — see the changelog — so the current floor is `@7`.

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
