# Scope — what this project checks, and what it deliberately does not

## In scope

The text of the main content area of the live page must be present on the new page: under
the same tab, as often, with the same figures, and behind buttons that lead to the same
place. Content hidden inside a collapsed tab or accordion counts — it is reachable by a
visitor, so it is content.

The live (Sitecore) page is a **subset baseline**: whatever it has, the new page must have.
Extra text on the new page is reported and never fails.

## Out of scope, and why

| Left out | Why |
|---|---|
| Screenshots and diff evidence | The findings are text and each one names the exact text it is about, the tab it sits in, and what the new page says instead. That is checkable by opening the two pages. Screenshots cost ~100 MB per page and a reveal loop that has to force hidden containers open, for evidence a reader still has to interpret. |
| Image comparison | A separate problem with a separate failure mode (pairing, decoding, perceptual hashing, WebP support). Mixing it in was what made the previous report need 30 MB bundles and a picture viewer. |
| GA4 / metadata / HTTP | Different checks with different owners and different fix cycles. Nothing stops them being added — `CHECK_ORDER` is still a list — but they are not this project's question. |

## What this buys

- The publish bundle is a few hundred KB instead of tens of MB, with no downscaling step.
- `ReportBuilder` is ~1,300 lines instead of 1,745, with no ImageIO, no asset copying and no
  binary references at all.
- No `Drivers/` jars: the WebP reader the image check needed is gone, so `build.gradle`
  declares no dependencies.
- The whole compare half runs outside Katalon in seconds, which is what makes the matching
  rules and the score weights tunable against real data.
