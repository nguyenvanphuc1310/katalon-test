# Verdicts and the score

Two results per page. They answer different questions and are deliberately not merged.

> **The score is deferred.** `ReportBuilder` renders the **verdict only** — see
> [report-contract.md](report-contract.md). The verdict table below is
> live and is the source of the 10 finding values; [the score section](#score--the-ranking) is
> kept as the specification to restore, not as something the report reads. The two must still
> never be derived from one another when the score returns.

## Verdict — the gate

Written by `ContentTextCheck` to `Reports/parity-results/<slug>/content.txt`.

| Verdict | Meaning | Level |
|---|---|---|
| `MISSING_ON_AEM` | Live-page text found nowhere on the new page | 🔴 fails the page |
| `NUMBER_CHANGED` | Same sentence, different figures — a sum, premium, age, percentage or policy term | 🔴 fails the page |
| `LINK_CHANGED` | Same button wording, different destination | 🔴 fails the page |
| `WRONG_TAB` | The text exists but under a different tab | 🔴 fails the page |
| `SCOPE_ASYMMETRY` | The two sides did not read comparable content, so this page's findings rest on a weaker measurement | 🟡 warning |
| `COUNT_MISMATCH` | On the new page, but fewer times than on the live page | 🔴 fails the page |
| `TEXT_CHANGED` | Same slot, reworded (token overlap ≥ 0.9). Reported once, not as a missing + extra pair | 🔴 fails the page |
| `STATE_ONLY_ON_LIVE` | A tab/section of the live page with no counterpart | 🟡 warning |
| `STATE_ONLY_ON_NEW` | A tab/section only on the new page | 🟡 warning |
| `ONLY_ON_AEM` | Extra text on the new page — subset rule | ⚪ info |

`NOT_RUN` means the check does not apply (the URL serves a PDF). It is not the same as a
missing file, which means the page has not been checked yet.

### How the three levels render

`ReportBuilder.level()` maps every verdict to one of three levels, and each finding block on a
page carries it **three ways at once** — a coloured left rule, a tinted header band, and a
spelled-out label:

| Level | Label printed | Colour | Verdicts |
|---|---|---|---|
| error | `FAILS THE PAGE` | red `--fail` `#B3261E` on `--fail-soft` | the six in `ContentCompare.ERRORS` |
| warning | `WARNING` | amber `--warn` `#8A5A00` on `--warn-soft` | the four warnings above |
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

### `COUNT_MISMATCH` fails the page — read `SCOPE_ASYMMETRY` first

A text the live page states twice and the new page states once has lost an appearance, and
that is content the migration did not carry, so it fails (changed 2026-08-20; it warned
before).

The caveat that made it a warning has not gone away: the two sides do not yet read comparable
content — on the lifestage pages the live side discards 83–160 hidden elements where the new
side discards exactly 8, which is what `SCOPE_ASYMMETRY` says out loud. A page can therefore
fail on a count difference the extraction produced rather than the migration. When a page
carries both verdicts, read the `SCOPE_ASYMMETRY` block before acting on its counts.

Only text of at least 40 characters is counted at all: counting substrings, `Protection`
occurred 24 times on the live page and 23 on the new one — true, unactionable, and it would
have failed the page.

## Score — the ranking

> **Deferred by decision — not implemented, and not rendered by the report.** The current
> report scope is verdict-only, so nothing consumes a score today. `ContentCompare` also has no
> `WEIGHTS` and no `score()`, `write()` emits no `weight` column and no `score.csv`, and
> `ReportBuilder` renders no score. The generated files under `Reports/` were produced by an
> earlier build that had it. (The offline harness that asserted the score was removed with
> `tools/` on 2026-08-20.) Everything
> below is the specification to restore it to; see
> [open point 6](../overview/project-tracking.md#open-points).

```
score = 100 × (1 − Σ weight(finding) / items compared)
```

`items compared` is the number of live-page content items. Dividing by it is what makes two
pages comparable — and it is also what makes the score unable to replace the verdict.

| Verdict | Weight | Why |
|---|---:|---|
| `MISSING_ON_AEM` | 1.0 | exactly one item of the live page is gone |
| `NUMBER_CHANGED` | 1.0 | |
| `LINK_CHANGED` | 1.0 | |
| `WRONG_TAB` | 0.5 | reachable, just not where the visitor looked |
| `STATE_ONLY_ON_LIVE` | 0.5 | its contents are already counted item by item; 1.0 would charge the same loss twice |
| `COUNT_MISMATCH` | 0.3 | the measurement is weaker than the count suggests |
| `TEXT_CHANGED` | 0.1 | mostly punctuation; a genuine reword is a copy decision |
| `ONLY_ON_AEM`, `STATE_ONLY_ON_NEW`, `SCOPE_ASYMMETRY` | 0.0 | subset rule / says something about the measurement, not the page |

Weights live in `ContentCompare.WEIGHTS` and are written into `findings.csv` as a `weight`
column, so anyone can add the column up in a spreadsheet and reproduce the score exactly.

### Grades and the hard gate

`PASS ≥ 98 · WARN 95–98 · FAIL < 95`, **except** that any `NUMBER_CHANGED` or
`LINK_CHANGED` grades FAIL whatever the score. A wrong sum assured is not the kind of defect
that gets averaged away.

### Confidence

A score is marked **low confidence** when `SCOPE_ASYMMETRY` fired, or when fewer than 40
items were compared (one finding then moves the score by more than 2.5 points). The number
is still shown, with the reason beside it. It is never silently adjusted — adjusting it
would hide the reason it is weak. Low-confidence scores are excluded from the site average,
and when *every* score is low confidence the average is withheld rather than printed as a
figure that would have to be un-learned later.
