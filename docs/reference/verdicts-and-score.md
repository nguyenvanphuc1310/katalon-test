# Verdicts and the score

Two results per page. They answer different questions and are deliberately not merged.

## Verdict — the gate

Written by `ContentTextCheck` to `Reports/parity-results/<slug>/content.txt`.

| Verdict | Meaning | Level |
|---|---|---|
| `MISSING_ON_AEM` | Live-page text found nowhere on the new page | 🔴 fails the page |
| `NUMBER_CHANGED` | Same sentence, different figures — a sum, premium, age, percentage or policy term | 🔴 fails the page |
| `LINK_CHANGED` | Same button wording, different destination | 🔴 fails the page |
| `WRONG_TAB` | The text exists but under a different tab | 🔴 fails the page |
| `SCOPE_ASYMMETRY` | The two sides did not read comparable content, so this page's findings rest on a weaker measurement | 🟡 warning |
| `COUNT_MISMATCH` | On the new page, but fewer times than on the live page | 🟡 warning |
| `OPTION_MISSING` | A dropdown choice the live page offers and the new page does not | 🟡 warning |
| `TEXT_CHANGED` | Same slot, reworded (token overlap ≥ 0.9). Reported once, not as a missing + extra pair | 🟡 warning |
| `STATE_ONLY_ON_LIVE` | A tab/section of the live page with no counterpart | 🟡 warning |
| `STATE_ONLY_ON_NEW` | A tab/section only on the new page | 🟡 warning |
| `ONLY_ON_AEM` | Extra text on the new page — subset rule | ⚪ info |

`NOT_RUN` means the check does not apply (the URL serves a PDF). It is not the same as a
missing file, which means the page has not been checked yet.

### Why figures are compared separately

Token overlap cannot see them. A paragraph in which `S$100,000` becomes `S$200,000` scores
~0.98 overlap, which lands it in `TEXT_CHANGED` — a warning that never failed a page. On
insurance content that is the most expensive thing the check can get wrong, so figures are
extracted and compared on their own and outrank the overlap score.

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

### Why `COUNT_MISMATCH` only warns

The counting is sound, but the two sides are not yet reading comparable content: on the
lifestage pages the live side discards 83–160 hidden elements where the new side discards
exactly 8. Until the scopes are symmetric, a count difference cannot be attributed to the
page rather than to the extraction — which is what `SCOPE_ASYMMETRY` says out loud. Only
text of at least 40 characters is counted at all: counting substrings, `Protection` occurred
24 times on the live page and 23 on the new one — true, unactionable, and it would have
failed the page.

## Score — the ranking

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
| `OPTION_MISSING` | 0.3 | |
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
