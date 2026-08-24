# Verdicts and the score

Two results per page. They answer different questions and are deliberately not merged.

> **Both are live.** `ContentCompare.grade()` computes the score, `write()` records it in
> `score.csv`, and `ReportBuilder` renders it — see [report-contract.md](report-contract.md).
> The verdict is now **banded on the score**, which is the one place the two touch: the score
> decides which band the page lands in, and nothing else about the score is derived from the
> verdict or the verdict from anything but the band and the hard gate.

## Verdict — the gate

Written by `ContentTextCheck` to `Reports/parity-results/<slug>/content.txt`, as one of
`PASS`, `WARN`, `FAIL` or `NOT_RUN`. Which one is decided by the [score band and the hard
gate](#grades-and-the-hard-gate), not by whether any single finding exists.

Two lists, and they are not the same list. `ERRORS` says which findings are **content the
migration did not carry** — they are what the report prints in red and counts as "texts
failed". `WEIGHTS` says **how much of the page** each one costs, and that is what the band is
computed from. A text that is simply gone is a whole item lost; the same text reworded is still
readable and costs a tenth of one. Both are errors; only one of them should be able to fail a
large page on its own.

| Verdict | Meaning | Level | Weight |
|---|---|---|---:|
| `MISSING_ON_AEM` | Live-page text found nowhere on the new page | 🔴 counts against the page | 1.0 |
| `NUMBER_CHANGED` | Same sentence, different figures — a sum, premium, age, percentage or policy term | 🔴 **fails at any score** | 1.0 |
| `SCOPE_ASYMMETRY` | The two sides did not read comparable content, so this page cannot be judged | 🔴 **fails at any score** — but writes no `findings.csv` row and draws no block; see below | 0.0 |
| `COUNT_MISMATCH` | On the new page, but fewer times than on the live page | 🔴 counts against the page | 0.3 |
| `TEXT_CHANGED` | Same slot, reworded (token overlap ≥ 0.9). Reported once, not as a missing + extra pair | 🔴 counts against the page | 0.1 |
| `WRONG_TAB` | The text exists, under a different tab | 🟡 warning | 0.0 |
| `STATE_ONLY_ON_LIVE` | A tab/section of the live page with no counterpart | 🟡 warning | 0.5 |
| `STATE_ONLY_ON_NEW` | A tab/section only on the new page | 🟡 warning | 0.0 |
| `ONLY_ON_AEM` | Extra text on the new page — subset rule | ⚪ info | 0.0 |

`NOT_RUN` means the check does not apply (the URL serves a PDF). It is not the same as a
missing file, which means the page has not been checked yet. `WARN` means the page cleared the
gate and still wants a reader — it does **not** mark the Katalon test case failed.

### How the three levels render

`ReportBuilder.level()` maps every verdict to one of three levels, and each finding block on a
page carries it **three ways at once** — a coloured left rule, a tinted header band, and a
spelled-out label:

| Level | Label printed | Colour | Verdicts |
|---|---|---|---|
| error | `FAILS THE PAGE` | red `--fail` `#B3261E` on `--fail-soft` | the six in `ContentCompare.ERRORS` |

The error label still reads `FAILS THE PAGE` because that is what these findings are — content
that did not survive. Whether the **page** ends up FAIL is the band's decision, and a page can
carry a handful of red findings and still pass. The block label describes the finding; the badge
at the top of the file describes the page.
| warning | `WARNING` | amber `--warn` `#8A5A00` on `--warn-soft` | the two warnings above |
| info | `FOR INFORMATION` | neutral `--muted` on `--sunk` | `ONLY_ON_AEM` |

The label is not decoration: this report is printed, forwarded and read on strange screens, and
roughly one man in twelve cannot separate the red rule from the amber one. Colour is the fast
signal; the label is the statement. Adding a verdict means adding it to `ERRORS`, `WARNINGS` or
`INFOS` in `ReportBuilder` — an unlisted verdict silently renders as a warning.

### Why figures are compared separately

Token overlap cannot see them. A paragraph in which `S$100,000` becomes `S$200,000` scores
~0.98 overlap, so before figures were extracted separately it read as ordinary rewording. Both
now fail the page, but they must stay apart: `NUMBER_CHANGED` names a wrong sum, `TEXT_CHANGED`
names wording that drifted, and on insurance content the first is the most expensive thing the
check can get wrong. Figures are extracted and compared on their own and outrank the overlap
score.

A digit glued to the end of a word or a bracket is a **footnote marker**, not a figure, and
is ignored: Sitecore writes the marker as a plain digit in a child `<sup>` while AEM writes
a superscript that NFKC folds back to a digit, and the two sites number their footnotes
differently.

### Why short text is treated differently

Below 30 characters a text is a **label**, and a substring hit on the page blob is not
evidence it survived — a page carrying `Compare Plans` once satisfies every `Compare Plans`
the live page has. A label must be answered by a label: an item that *is* the text, or the
text with something glued to one end (AEM appends accessibility copy with no separator, so
the live page's `English` is `Englishopens in a new tab` there), or an item short enough
that the label is still its subject.

The glued-on form is bounded by `AFFIX_EXTRA_MAX = 40` **characters**, not by a ratio. The
ratio used for the containment case cannot express "plus a short fixed suffix": `English` is 7
characters and AEM's addition is 18, so a 3× bound rejects it and reports `English` as missing
content on every product-deck page. What the bound has to exclude is a paragraph of arbitrary
length answering a short CTA because it happens to begin with the same words, and a character
count does that.

A label is also accepted when the new page has **glued a 3-4 character prefix to its front**
(`GLUE_PREFIX_MIN` / `GLUE_PREFIX_MAX`). This is one defect and not a class of them: Sitecore
writes a product name as `PRU` plus an inline element carrying the rest —
`<h1>PRU<span>Shield</span> / PRU<span>Extra</span> Premium Rate Tables</h1>` — so the collector
emits `Shield` and `Extra` as items in their own right, while AEM writes `PRUShield` as one text
node and wraps `PRU` instead, which `MIN_LEN` drops for being 3 characters. The live fragment
then has no fragment to answer it, and the word-boundary test rejects the only place it does
occur, because the character to its left is the `u` of `PRU`.

The **lower** bound is the load-bearing one. Without it `here` is answered by `there are many
ways to make or receive payment`, which is a coincidence and not a counterpart. Measured over
the 192 captured page pairs, the rule fires on 9 findings across 4 pages and the glued prefix
is `pru` every time; without the lower bound it also silently lifted
`en_claims_and_support_payments` by two findings. (2026-08-24)

### `COUNT_MISMATCH` fails the page — read `SCOPE_ASYMMETRY` first

A text the live page states twice and the new page states once has lost an appearance, and
that is content the migration did not carry, so it fails (changed 2026-08-20; it warned
before).

The caveat that made it a warning has not gone away, but **the evidence for it turned out to be
partly the measurement's own fault.** The reading everyone quoted — "the live side discards
83–160 hidden elements where the new side discards exactly 8" — was `SCOPE_ASYMMETRY` comparing
two counters that do not mean the same thing on the two CMSes: Sitecore marks nothing
`aria-hidden` so its invisible content sat in `skipped.hidden`, AEM marks every inactive panel
so its invisible content sat in `skipped.noise`. Since 2026-08-21 both sides are measured as
`hidden + ariaHidden` (`ContentCompare.hiddenCount()`), and **how much asymmetry is left is not
yet known** — the snapshots on disk predate the split. Until they are re-captured at `@7`, a
page can still fail on a count difference the extraction produced rather than the migration.
When a page carries both verdicts, read the `SCOPE_ASYMMETRY` block before acting on its counts.

### `SCOPE_ASYMMETRY` fails the page, and it measures three things

It is an error (changed 2026-08-21; it warned before), and it is a different **kind** of error
from the six above it. Those say the migration lost content. This one says the check could not
tell — so every other verdict on the page is measuring the extraction rather than the page. It
fails because the alternative is worse: as a warning it let `en_lifestage` read `PASS` while its
new side had collected **one element out of the whole document**. A page that cannot be judged
must not report that it passed.

The old rule read one counter, `skipped.hidden`. That is what missed `en_lifestage`: its hidden
counts were 12 against 0, well inside the threshold, while its item counts were 30 against 1.
Three measures are compared now, and any one of them firing is enough:

| Measure | Catches |
|---|---|
| `hidden + ariaHidden` | one side dropping invisible content the other kept |
| items collected | an item walk that died early, or a content root that resolved to the wrong element |
| `skipped.scanned` | a content root far smaller on one side than the other (compared only when both snapshots carry the counter) |

Each uses the same bar — `hi >= SCOPE_ASYMMETRY_MIN (20)` **and** `hi >= lo * 3 + 10` — so a 3×
gap between 2 and 8 says nothing and does not fire.

Only text of at least 40 characters is counted at all: counting substrings, `Protection`
occurred 24 times on the live page and 23 on the new one — true, unactionable, and it would
have failed the page.

#### It fails the page, but it is not written to `findings.csv` and draws no block (2026-08-24)

`ContentCompare.NO_CSV` keeps the finding out of `findings.csv`, and `ReportBuilder.NOT_RENDERED`
keeps it out of the report for the files already on disk. Both lists exist because
`findings.csv` is the per-**text** evidence — one row is one live text and what became of it —
and the report tabulates it as *"N of M live texts did not survive"*. The asymmetry finding is
not a live text; it is a statement about the crawl. Written there it drew a red one-row
**Fails the page** panel on 27 pages, **19 of them URLs that 404 on both sides**, where all the
panel reported was that the two error pages have different markup.

Nothing about the gate changed. The finding is still produced, still in `HARD_FAIL`, and the
verdict still comes from `content.txt`, which the report reads — not from either list. What the
reader sees instead is one sentence: the **low-confidence callout**, whose text is `score.csv`'s
`confidenceWhy`, and which now carries the measurements themselves —

> Low confidence — the two sides did not read comparable content, so the denominator is not the
> page — 10 element(s) scanned on the live page against 42 on the new page. Re-capture this page
> before reading anything else it reports.

Keep the two lists in step. `SCOPE_ASYMMETRY` deliberately stays in `ERRORS`, so `readFindings`'
unknown-verdict warning does not fire on the stale rows; it is excluded from the failed-text
counts by hand in `checkStatsOf`.

## Score — the ranking

> **Implemented 2026-08-21.** `ContentCompare.WEIGHTS`, `HARD_FAIL`, `PASS_SCORE`,
> `WARN_SCORE` and `grade()` are in the committed code; `write()` emits the `weight` column and
> `score.csv`; `ReportBuilder` renders the score on the test-case file, sorts a template's pages
> by it, and averages it per template. It ranks pages by how much work each needs — it is not a
> second verdict, and the hard gate below overrides it.

```
score = 100 × (1 − Σ weight(finding) / items compared)
```

`items compared` is the number of live-page content items. Dividing by it is what makes two
pages comparable — and it is also what makes the score unable to replace the verdict.

| Verdict | Weight | Why |
|---|---:|---|
| `MISSING_ON_AEM` | 1.0 | exactly one item of the live page is gone |
| `NUMBER_CHANGED` | 1.0 | |
| `STATE_ONLY_ON_LIVE` | 0.5 | its contents are already counted item by item; 1.0 would charge the same loss twice |
| `COUNT_MISMATCH` | 0.3 | the measurement is weaker than the count suggests |
| `TEXT_CHANGED` | 0.1 | mostly punctuation; a genuine reword is a copy decision |
| `WRONG_TAB` | 0.0 | the text survived; which tab it sits under is layout, not content |
| `ONLY_ON_AEM`, `STATE_ONLY_ON_NEW`, `SCOPE_ASYMMETRY` | 0.0 | subset rule / says something about the measurement, not the page |

Weights live in `ContentCompare.WEIGHTS` and are written into `findings.csv` as a `weight`
column, so anyone can add the column up in a spreadsheet and reproduce the score exactly.

### Grades and the hard gate

`PASS ≥ 95 · WARN 90–95 · FAIL < 90`, **except** that any `NUMBER_CHANGED` or
`SCOPE_ASYMMETRY` grades FAIL whatever the score, and so does a page where **0 items were
compared**. A wrong sum assured is not the kind of defect that gets averaged away; a scope
asymmetry weighs 0.0, so without the hard gate a page whose only finding is "the two sides did
not read comparable content" would score a clean 100 and pass; and `1 − Σw/0` is a division by
zero that would otherwise read as a perfect page.

The band was chosen at 90 because two pages are almost never byte-identical after a migration —
a curly quote, a non-breaking space, a rephrased CTA — and a gate that failed every one of them
was reporting only that fact. `WARN` is **through the gate**: `ContentTextCheck` does not mark
the Katalon test case failed, and `ReportBuilder` counts it in the numerator of the pass rate
while still colouring it amber.

The thresholds are constants in `ContentCompare`, not configuration. `ReportBuilder` repeats
them (for colouring **averages** only) and is kept in step by comment, the same way `ERRORS` is.

### Confidence

A score is marked **low confidence** when `SCOPE_ASYMMETRY` fired, or when fewer than 40
items were compared (one finding then moves the score by more than 2.5 points). The number
is still shown, with the reason beside it. For `SCOPE_ASYMMETRY` that reason is also the only
place the finding appears in the report at all, so `confidenceWhy` carries its **measurements**
and not just its conclusion — see above. It is never silently adjusted — adjusting it
would hide the reason it is weak. Low-confidence scores are excluded from the site average,
and when *every* score is low confidence the average is withheld rather than printed as a
figure that would have to be un-learned later.
