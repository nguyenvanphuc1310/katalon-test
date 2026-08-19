# Running the checks

## The four modes

| Mode | What it does | Browser | VPN | Output |
|---|---|---|---|---|
| `baseline` | Capture the **live** (Sitecore) side | yes | no — the live site is public | `<slug>.sitecore.json` + `.html` |
| `capture` | Capture the **new** (AEM UAT) side | yes | **yes** | `<slug>.aem.json` + `.html` |
| `compare` | Capture AEM, then diff, then report | yes | **yes** | findings + score + verdict |
| `recompare` | Diff the snapshots already on disk | **no** | **no** | same, in seconds |

The intended loop is **baseline + capture once, recompare as often as you like**. Crawling
is the expensive half; judging is the half that gets re-run every time a matching rule or a
score weight changes.

## Which suite

| Goal | Suite |
|---|---|
| Live baseline, everything | `collections/TSC_PreT0_Baseline_All` |
| Compare everything | `collections/TSC_PostT0_Compare_All` |
| One page type, end to end | `collections/TSC_GCDP_Content_Full` |
| One page type, compare | `<group>/by-page/TS_<Type>_Compare` |
| One page type, live baseline | `normal-pages/by-page/TS_GeneralContentDetailPage_Baseline` |
| Re-judge, no crawl | `normal-pages/by-page/TS_GeneralContentDetailPage_Recompare` |

Every compare/recompare suite ends with `TC_Build_Parity_Report` and
`TC_Build_Mastersheet_Column`, so any run regenerates the reports.

## Without Katalon Studio

```bash
tools/offline-checks/run.sh          # compile + rule assertions + report contract
tools/offline-checks/run.sh replay   # ...and re-diff every snapshot, then rebuild the reports
```

Runs on Katalon's own Groovy and JRE — deliberately, because the system `java` is newer than
Groovy 3 can read while Katalon executes on 21, and that mismatch is what produces the
misleading `UnsupportedClassVersionError` inside Studio.

`replay` is the same work as `mode=recompare` plus the two report test cases. Use it to see
what a rule or weight change did to the real numbers before running anything against the
live sites.

## Reading the results

| Where | What |
|---|---|
| `Reports/parity-report/index.html` | the report — cover, at-a-glance matrix, filter bar, one file per page |
| `Reports/publish/` | the same site with no local paths — copy this to a report server |
| `Reports/report.xlsx` | one row per URL: verdict, score, grade, confidence, summary |
| `Reports/baseline-summary.html` | which pages have both sides captured and can be compared |
| `Reports/ContentAudit/<slug>/findings.csv` | every finding, with its score weight |

## Traps

**Stale compiled keywords.** A keyword edited outside the IDE can leave `bin/keyword/`
holding classes at a different version; Katalon then throws `UnsupportedClassVersionError`
naming the wrong file. Clear `bin/keyword/` and `.cache/Keywords/`. `run.sh` prints the
class-file versions so a mismatch shows up before Studio hits it.

**An 800-byte class file.** A single backslash in an embedded JS regex inside a
triple-quoted Groovy string makes Katalon's compiler emit an error stub instead of a class —
and the stub throws `UnsupportedClassVersionError`, which reads like a JDK problem and is
not. Check the class *size* first, then `javap -c` it: the real compiler message is inside.
`run.sh` flags anything under 1 KB.

**A test case variable that never binds.** A suite's `variableId` must match the id in the
bound test case's `.tc`. A mismatch does not error — the variable is simply never set. After
hand-editing or copying a suite, verify the ids.

**A URL that serves a PDF.** It can never have a snapshot. The baseline run records
`NOT_RUN`, and `recompare` honours that rather than demanding a baseline that cannot exist.
