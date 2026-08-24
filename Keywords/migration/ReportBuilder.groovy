package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

/**
 * The client-facing HTML report for the content-parity check.
 *
 * Four levels, one file each, and every level lists only what the next one opens:
 *
 *   index.html                              the run, then one row per template
 *   templates/<group>-<pagetype>.html       the pages of that template
 *   pages/<slug>.html                       the test cases run against that page
 *   pages/<slug>__<check>.html              one test case on one page — its findings
 *
 * Nothing is listed twice and no single file grows with the whole site — the target is
 * ~2,000 URLs, where one long document is not a report, it is a download.
 *
 * A "test case" is a check id: one entry of CHECK_ORDER, run against one URL. Only `content`
 * has a producer today; the others (GA4, images, metadata) get their row as soon as they write
 * their result file, without touching the report. What the check writes but a reader cannot act
 * on — the raw summary line — is deliberately not rendered.
 *
 * Two numbers are reported side by side and answer different questions. The **pass rate** counts
 * whole pages that cleared the gate: how many pages still need work. The **score** (read from
 * score.csv, never recomputed here) is how much of one page survived, and is what the pages of a
 * template are sorted by — it is a work queue, not a second verdict. Neither is derived from the
 * other, and a page can score 99 and still be FAIL, because a changed figure or an unusable
 * comparison fails at any score.
 *
 * A page's result is the worst its test cases reached: FAIL, then WARN (through the gate, wants
 * reading), then PASS. The live page is the reference and a subset baseline, so extra text on the
 * new page is reported and never fails.
 *
 * Input is files on disk, never the Katalon log:
 *
 *   Data Files/aem-url-mapping.csv                  which pages exist, and their template
 *   Reports/parity-results/<slug>/<check>.txt       verdict + that check's detail lines
 *   Reports/<Kind>/<slug>/findings.csv              one row per finding — the evidence
 *                                                   (<Kind> per check: see CHECK_EVIDENCE)
 *
 * Output is self-contained apart from one stylesheet and one script under assets/_site/, and
 * every navigation step is a plain link, so it works from file:// with JavaScript off.
 *
 *   build() -> Reports/parity-report/
 */
public class ReportBuilder {

	// ---------------------------------------------------------------- vocabulary

	static final List GROUP_ORDER = ['custom', 'normal']
	static final Map GROUP_TITLE = [custom: 'Custom pages', normal: 'Normal pages']

	static final List ACRONYMS = ['ilp', 'ga4', 'ppc', 'pru', 'lbu', 'pva', 'http', 'gtm']

	/**
	 * The test cases, in the order they are listed against every URL.
	 *
	 * One id here is one test case: it reads `Reports/parity-results/<slug>/<id>.txt` for its
	 * verdict and `Reports/<CHECK_EVIDENCE[id]>/<slug>/findings.csv` for its evidence, and it
	 * gets a row on every page of the report plus a file of its own per URL.
	 *
	 * This is a CLOSED list. A result file written under an id that is not here is written and
	 * then ignored — silently. Adding GA4, images or metadata is: one id here, one title, one
	 * description, one evidence folder, and a producer that writes the two files. Nothing else in
	 * this class changes.
	 */
	static final List CHECK_ORDER = ['content']

	static final Map CHECK_TITLE = [content: 'Content parity']

	/** One sentence: what this test case asserts, printed on its own page. */
	static final Map CHECK_DESC = [
		content: 'Every text of the live page is still on the new page.']

	/** `Reports/<folder>/<slug>/findings.csv` — where this test case leaves its evidence. */
	static final Map CHECK_EVIDENCE = [content: 'ContentAudit']

	/** Most severe first: the reading order of the blocks on a page */
	static final List FINDING_ORDER = ['MISSING_ON_AEM', 'NUMBER_CHANGED',
		'COUNT_MISMATCH', 'TEXT_CHANGED', 'SCOPE_ASYMMETRY',
		'WRONG_TAB', 'STATE_ONLY_ON_LIVE', 'STATE_ONLY_ON_NEW', 'ONLY_ON_AEM']

	static final Map FINDING_TITLE = [
		MISSING_ON_AEM: 'Missing on the new page',
		NUMBER_CHANGED: 'Figures changed',
		WRONG_TAB: 'Present, but under a different tab',
		SCOPE_ASYMMETRY: 'The two pages were not read comparably',
		COUNT_MISMATCH: 'Appears fewer times than on the live page',
		TEXT_CHANGED: 'Wording changed',
		STATE_ONLY_ON_LIVE: 'Tab/section with no counterpart on the new page',
		STATE_ONLY_ON_NEW: 'Tab/section only on the new page',
		ONLY_ON_AEM: 'Only on the new page']

	/** One sentence each. The long explanations belong in the docs, not on all 20 pages. */
	static final Map FINDING_HINT = [
		MISSING_ON_AEM: 'Not found anywhere on the new page.',
		NUMBER_CHANGED: 'Same sentence, different figures — a sum, an age, a percentage, a policy term.',
		WRONG_TAB: 'On the new page, under a different tab. The text survived, so this does not fail the page.',
		SCOPE_ASYMMETRY: 'The two pages were not read on equal terms, so these findings rest on a weaker measurement.',
		COUNT_MISMATCH: 'On the new page, but fewer times than on the live page — the missing appearances are missing content.',
		TEXT_CHANGED: 'Same place, reworded — the live wording is not on the new page.',
		STATE_ONLY_ON_LIVE: 'A tab or section of the live page with no counterpart found on the new page.',
		STATE_ONLY_ON_NEW: 'A tab or section that exists only on the new page.',
		ONLY_ON_AEM: 'Extra text on the new page. The live page is a subset baseline, so this never fails.']

	/**
	 * The columns of a findings table, per verdict, as `[header, row key]` pairs.
	 * `FINDING_COLUMNS_DEFAULT` for any verdict not named here.
	 *
	 * The header is per verdict because the same three fields carry different things under
	 * different verdicts, and a header that is right for one block is a lie on another:
	 *
	 * - `TEXT_CHANGED` and `NUMBER_CHANGED` name the two sites. Only in those two do the cells
	 *   line up with them — the live wording, and the AEM wording that replaced it — and they are
	 *   the only two whose rows are read CHARACTER BY CHARACTER (`S$30,000` against `$10,000`,
	 *   `10%4` against `10%3`), where "Text" beside "Note" answers none of the question the row
	 *   is asking.
	 * - `MISSING_ON_AEM` and `ONLY_ON_AEM` have NO note column, because they have no note:
	 *   `ContentCompare` writes `''` for both by construction, so the column was 1,828 blank cells
	 *   whose header nonetheless promised a reader something was there. A missing text has no
	 *   counterpart to describe — that IS the finding.
	 * - everything else keeps `Text`/`Note`: `STATE_ONLY_ON_NEW` puts the **AEM** text in `text`,
	 *   and `WRONG_TAB`, `COUNT_MISMATCH`, `SCOPE_ASYMMETRY` and `STATE_ONLY_ON_LIVE` put a
	 *   sentence of explanation in `note`, not the AEM wording.
	 *
	 * The row key must name a field `readFindings` actually sets, or the cell renders empty in
	 * silence. `path` is the only one with a fallback — an empty one renders as an em dash.
	 */
	static final List FINDING_COLUMNS_DEFAULT = [
		['Where on the live page', 'path'], ['Text', 'text'], ['Note', 'note']]

	/** The live wording against the AEM wording that replaced it. */
	static final List FINDING_COLUMNS_SIDES = [
		['Where on the live page', 'path'], ['Sitecore', 'text'], ['AEM', 'note']]

	/** No note column: these verdicts never write one. */
	static final List FINDING_COLUMNS_NO_NOTE = [
		['Where on the live page', 'path'], ['Text', 'text']]

	static final Map FINDING_COLUMNS = [
		TEXT_CHANGED  : FINDING_COLUMNS_SIDES,
		NUMBER_CHANGED: FINDING_COLUMNS_SIDES,
		MISSING_ON_AEM: FINDING_COLUMNS_NO_NOTE,
		ONLY_ON_AEM   : FINDING_COLUMNS_NO_NOTE]

	/** The five that fail a page. Kept identical to ContentCompare.ERRORS. */
	static final List ERRORS = ['MISSING_ON_AEM', 'NUMBER_CHANGED',
		'COUNT_MISMATCH', 'TEXT_CHANGED', 'SCOPE_ASYMMETRY']
	/** Worth reading, but they do not fail the page */
	static final List WARNINGS = ['WRONG_TAB', 'STATE_ONLY_ON_LIVE', 'STATE_ONLY_ON_NEW']
	/** Never a problem: the live page is a subset baseline */
	static final List INFOS = ['ONLY_ON_AEM']

	/**
	 * Verdicts that are read and classified, but never drawn as a findings block.
	 *
	 * SCOPE_ASYMMETRY is a statement about the CRAWL, not about a live text. Drawn as a block it
	 * put a red "Fails the page" panel of one row on 27 pages, 19 of them URLs that 404 on both
	 * sides — where all it reported was that the two error pages have different markup. The page
	 * still fails: the verdict comes from content.txt, not from this list, and score.csv carries
	 * the measurements in `confidenceWhy`, which renders as the low-confidence callout above the
	 * blocks. One sentence in place of a table of one row.
	 *
	 * `ContentCompare.NO_CSV` stops new runs writing the row at all; this list is what keeps the
	 * report honest about the findings.csv files already on disk. They must stay in step.
	 *
	 * These verdicts stay in ERRORS so readFindings' unknown-verdict warning does not fire on them,
	 * and they are excluded from the failed-text counts by hand — see checkStatsOf.
	 */
	static final List NOT_RENDERED = ['SCOPE_ASYMMETRY']

	/**
	 * Severity is spelled out as well as coloured. This report is printed, forwarded and read on
	 * whatever screen is to hand, and roughly one man in twelve cannot tell the red rule from the
	 * amber one — the colour is the fast signal, the label is the statement.
	 */
	static final Map LEVEL_LABEL = [err: 'Fails the page', warn: 'Warning', info: 'For information']

	/** A verdict in none of the three lists renders as a warning rather than disappearing. */
	static String level(String verdict) {
		return ERRORS.contains(verdict) ? 'err' : INFOS.contains(verdict) ? 'info' : 'warn'
	}

	// ---------------------------------------------------------------- entry points

	/**
	 * Report that reads its evidence in place from Reports/ (for the test team)
	 *
	 * Everything is logged before it is rethrown. The 2026-08-21 build failed with nothing in the
	 * log but the string `java.lang.StackOverflowError` — no frames, no file, no page — because
	 * the JVM's fast-throw optimisation strips the trace off an implicit error once its throw site
	 * is hot, which a recursive regex loop becomes immediately. That cost a reproduction to
	 * diagnose what a stack trace would have named. Printing it here does not defeat fast-throw,
	 * but it does name the failing keyword, and it states the one fact the reader most needs:
	 * render() swaps a finished directory into place, so the previous report is still readable.
	 */
	@Keyword
	static String build() {
		try {
			return render()
		} catch (Throwable t) {
			StringWriter sw = new StringWriter()
			t.printStackTrace(new PrintWriter(sw))
			KeywordUtil.logInfo("Parity report build FAILED: ${t}\n${sw}\n" +
				'The previous report under Reports/parity-report/ is untouched — this build wrote ' +
				'to a temporary directory and never reached the swap.')
			throw t
		}
	}


	// ---------------------------------------------------------------- input

	/**
	 * One entry per row of the master mapping, carrying the result found on disk.
	 *
	 * The page list comes from the CSV and not from the results, so a page nobody checked is a
	 * visible gap rather than an absence no one notices. Rows without pagegroup/pagetype (legacy
	 * 3-column rows) default to "normal".
	 */
	private static List collectPages(String proj) {
		List pages = []
		File csv = new File(proj + '/Data Files/aem-url-mapping.csv')
		if (!csv.exists()) return pages
		csv.readLines('UTF-8').drop(1).each { String line ->
			if (!line?.trim()) return
			def c = line.split(',')
			if (c.length < 3) return
			String aem = c[1].trim()
			String slug = AuditUtils.slugOf(aem)
			Map results = CHECK_ORDER.collectEntries { String id -> [id, readResult(proj, slug, id)] }
			pages << [
				sc      : c[0].trim(),
				aem     : aem,
				tpl     : c.length > 2 ? c[2].trim() : '',
				group   : c.length > 3 && c[3].trim() ? c[3].trim() : 'normal',
				pagetype: c.length > 4 ? c[4].trim() : '',
				slug    : slug,
				// one entry per test case; null = that check never ran on this page
				results : results,
				// the content check still decides whether the page gets a file of its own
				result  : results['content'],
			]
		}
		warnOnSlugCollisions(pages)
		return pages
	}

	/**
	 * Two URLs that produce one slug produce one set of files, and the second silently overwrites
	 * the first — the same snapshot, the same findings.csv, the same content.txt.
	 *
	 * `AuditUtils.slugOf` collapses every run of non-alphanumerics to `_`, so `/a/b-c` and `/a-b/c`
	 * are the same slug. The scheme is deliberately NOT changed here: it names every file already
	 * on disk, so changing it is a migration. What was missing is any way to find out — this makes
	 * the collision loud while it is still cheap to fix. The risk scales with the mapping: 281 rows
	 * today against a target near 1,700.
	 *
	 * Slugs are bucketed CASE-INSENSITIVELY, because the collision that matters is a collision of
	 * FILE NAMES and macOS ships APFS case-insensitive. Comparing the slugs exactly, as this did
	 * until 2026-08-21, misses that entirely: `…/sustainability/Responsible Investment` and
	 * `…/sustainability/responsible-investment` produce two different slugs and one directory, so
	 * the second page overwrote the first's snapshot, findings.csv and verdict, the report counted
	 * two pages compared, rendered one file, and pointed both links at it — in silence, which is
	 * exactly what this check exists to prevent. A case-only collision is still reported when the
	 * project is opened on a case-sensitive volume: it is a latent bug there, not a safe one.
	 */
	private static void warnOnSlugCollisions(List pages) {
		Map bySlug = [:]
		pages.each { Object p ->
			Map page = (Map) p
			bySlug.get(((String) page.slug).toLowerCase(), []) << page
		}
		bySlug.each { Object key, Object entries ->
			List all = ((List) entries)*.aem.unique()
			if (all.size() > 1) {
				List slugs = ((List) entries)*.slug.unique()
				String how = slugs.size() > 1
						? "produce slugs differing only in case (${slugs.join(', ')}), which are one file " +
						'name on a case-insensitive filesystem'
						: "produce the same slug '${slugs[0]}'"
				KeywordUtil.markWarning("Slug collision: ${all.size()} URLs ${how}, " +
					"and therefore share one snapshot, one findings.csv and one verdict — ${all.join(' | ')}. " +
					'Only the last one checked is represented in the report.')
			}
		}
	}

	/**
	 * `<checkId>.txt`: line 1 is the verdict, the rest is that check's detail.
	 *
	 * null (no file) means "not checked yet"; a file reading NOT_RUN means "does not apply here".
	 * They render differently and must not be collapsed into one.
	 */
	private static Map readResult(String proj, String slug, String checkId) {
		File f = new File(proj + '/Reports/parity-results/' + slug + '/' + checkId + '.txt')
		if (!f.exists()) return null
		List lines = f.readLines('UTF-8')
		return [verdict: lines ? lines[0].trim() : 'NA',
			detail: lines.size() > 1 ? lines.drop(1).join('\n').trim() : '']
	}

	private static final java.util.regex.Pattern LEGACY_NOTE_LABEL =
		~/^(?:figures changed:[^;]*; )?new page says: /

	/**
	 * Drops the label that `note` used to carry in front of the AEM wording.
	 *
	 * `TEXT_CHANGED` wrote `new page says: <the AEM text>`, and `NUMBER_CHANGED` put a
	 * `figures changed: [30000] -> [10000]; ` preamble in front of that. The column header names
	 * the side now, so the label inside the cell only repeated it. `ContentCompare` stopped writing
	 * both; this strips them on the way in as well, because otherwise every findings.csv already on
	 * disk would keep rendering the old way until all 1,823 pages had been crawled again — a full
	 * crawl spent on a caption.
	 *
	 * Anchored at `^`, and the preamble is `[^;]*` rather than `.*?` so it cannot walk past the
	 * first `; ` into the AEM text. The figure sets render as `[30000]` / `[30000, 5]` — commas,
	 * never a semicolon.
	 */
	private static String stripNoteLabel(String note) {
		return note.replaceFirst(LEGACY_NOTE_LABEL, '')
	}

	/**
	 * findings.csv parsed into rows.
	 *
	 * The trailing `weight` column is OPTIONAL in this pattern and must stay that way. The files
	 * on disk carry it (`…,"note",1.0`) and the pattern is `$`-anchored: demanding the five
	 * columns exactly drops **every** row of **every** page, in silence, and the report then
	 * reads "no findings" on a page that failed. That is not hypothetical — it shipped once; see
	 * docs/reference/report-contract.md.
	 *
	 * A quoted field is `[^"]*(?:""[^"]*)*` and must NEVER be written as the more obvious
	 * `(?:[^"]|"")*`. They match the same language, but `(?:A|B)*` compiles to a RECURSIVE
	 * Loop/Branch pair in java.util.regex — one set of stack frames per matched character — while
	 * a single-char class under `*` compiles to a Curly that iterates. The alternation form ran
	 * for a year on a 9-page corpus and then killed the build the first time the mapping widened
	 * to 270 pages: one 1760-char row (the SCB PDPA consent clause, ~700-char text beside an
	 * ~800-char note) exhausted the stack, and `build()` died with a bare StackOverflowError that
	 * carried no frames at all. Measured afterwards: even on a FRESH 1 MB stack that row matches
	 * with zero frames to spare, and render() runs hundreds of frames deep inside Katalon's
	 * runner. Recursion depth here is now the number of `""` escape pairs, not the field length.
	 *
	 * All three ways a row can vanish are now logged, because silence is the whole failure mode here.
	 * A row that does not match the pattern is a contract break. A row that matches but carries a
	 * verdict listed in neither ERRORS, WARNINGS nor INFOS renders nowhere (findingBlocks walks
	 * FINDING_ORDER) and is counted nowhere (checkStatsOf tallies the three lists), so the page
	 * silently loses it: that is what happened to the 18 OPTION_MISSING rows left on disk by the
	 * build that predated the verdict's removal.
	 */
	private static List readFindings(String proj, String slug, String checkId) {
		String kind = CHECK_EVIDENCE[checkId]
		if (!kind) return []
		File f = new File(proj + '/Reports/' + kind + '/' + slug + '/findings.csv')
		if (!f.exists()) return []
		List rows = []
		int unparsed = 0
		int overflowed = 0
		f.readLines('UTF-8').drop(1).each { String line ->
			if (!line.trim()) return
			try {
				def m = (line =~ /^(\w+),(\w*),"([^"]*(?:""[^"]*)*)","([^"]*(?:""[^"]*)*)","([^"]*(?:""[^"]*)*)"(?:,[-0-9.]+)?$/)
				if (m.find()) rows << [verdict: m.group(1), kind: m.group(2),
					path: m.group(3).replace('""', '"'), text: m.group(4).replace('""', '"'),
					note: stripNoteLabel(m.group(5).replace('""', '"'))]
				else unparsed++
			} catch (StackOverflowError soe) {
				// The pattern above no longer recurses per character, so this should never fire.
				// It exists because the alternative, once, was the whole build dying with no
				// frames and no message: losing one named row beats losing the entire report.
				// Safe to catch — the regex engine's frames are gone by the time we get here.
				overflowed++
			}
		}
		if (unparsed > 0) {
			KeywordUtil.markWarning("${f.getPath()}: ${unparsed} row(s) did not match the findings contract " +
				'and are missing from the report — see docs/reference/report-contract.md')
		}
		if (overflowed > 0) {
			KeywordUtil.markWarning("${f.getPath()}: ${overflowed} row(s) exhausted the stack while being parsed " +
				'and are missing from the report. The findings pattern has started recursing again — ' +
				'see the comment on readFindings and docs/reference/report-contract.md')
		}
		Set unknown = rows.collect { (String) it.verdict }.findAll {
			!ERRORS.contains(it) && !WARNINGS.contains(it) && !INFOS.contains(it)
		} as Set
		if (unknown) {
			KeywordUtil.markWarning("${f.getPath()}: verdict(s) ${unknown.join(', ')} are in neither ERRORS, " +
				'WARNINGS nor INFOS, so they are rendered nowhere and counted nowhere. Either add them to ' +
				'ReportBuilder or re-run the check that wrote this file.')
		}
		return rows
	}

	/**
	 * How many live-page texts entered the comparison, read from the first detail line. The only
	 * part of that prose line this report parses — everything else is counted from findings.csv,
	 * where each finding is a row carrying its own verdict.
	 */
	private static int itemsCompared(String detail) {
		if (!detail) return 0
		def m = (detail.readLines()[0] =~ /(\d+) live items compared/)
		return m.find() ? (m.group(1) as int) : 0
	}

	/**
	 * score.csv as a `field -> value` map, or [:] when the check that ran wrote none.
	 *
	 * READ, never recomputed. The score is what the verdict was banded from, and a report that
	 * derived it a second time from findings.csv would be a second implementation of the formula,
	 * free to disagree with the one that actually set the verdict — the reader would then see a
	 * score of 96 beside a FAIL and have no way to tell which half was wrong. The same duplication
	 * already exists for ERRORS (see the list above, kept in step with ContentCompare by comment
	 * only); it is not worth repeating for arithmetic.
	 */
	private static Map readScore(String proj, String slug, String checkId) {
		String kind = CHECK_EVIDENCE[checkId]
		if (!kind) return [:]
		File f = new File(proj + '/Reports/' + kind + '/' + slug + '/score.csv')
		if (!f.exists()) return [:]
		Map out = [:]
		f.readLines('UTF-8').drop(1).each { String line ->
			int c = line.indexOf(',')
			if (c > 0) out[line.substring(0, c)] = line.substring(c + 1).replaceAll('^"|"$', '').replace('""', '"')
		}
		return out
	}

	/** Everything one test case on one page contributes to the report, read once. */
	private static Map checkStatsOf(String proj, Map p, String checkId) {
		Map res = (Map) ((Map) p.results)[checkId]
		List rows = res == null ? [] : readFindings(proj, (String) p.slug, checkId)
		Map sc = res == null ? [:] : readScore(proj, (String) p.slug, checkId)
		return [check  : checkId,
			verdict: res ? (String) res.verdict : 'NA',
			items  : itemsCompared(res == null ? null : (String) res.detail),
			rows   : rows,
			// NOT_RENDERED verdicts are excluded: this number is printed as "N of M live text(s) did
			// not survive", and the asymmetry finding is not a live text. Counting it there put a
			// failed text on pages where every text was found.
			failed : rows.count { ERRORS.contains(it.verdict) && !NOT_RENDERED.contains(it.verdict) },
			warned : rows.count { WARNINGS.contains(it.verdict) },
			extra  : rows.count { INFOS.contains(it.verdict) },
			// null, not 0, when no score was written: "not scored" and "scored zero" are opposite
			// statements, and a 0 default would drag every average down towards a claim nobody made.
			score  : sc.score == null ? null : (sc.score as double),
			lowConf: sc.confidence == 'low',
			confWhy: (String) (sc.confidenceWhy ?: '')]
	}

	/** The content test case — what the index and the template tables still count in texts. */
	private static Map statsOf(String proj, Map p) {
		return checkStatsOf(proj, p, 'content')
	}

	/** Every test case of one page, in CHECK_ORDER — the rows of that page's own file. */
	private static List casesOf(String proj, Map p) {
		return CHECK_ORDER.collect { String id -> checkStatsOf(proj, p, id) }
	}

	/**
	 * How many test cases of this page passed and failed.
	 *
	 * A test case with no result file, or one reading NOT_RUN, is in neither half — exactly the
	 * rule `rateOf` applies to pages. Counting it as a failure would report a verdict nobody
	 * reached; counting it as a pass would hide the work that has not been done.
	 */
	/**
	 * The verdict of a whole page: the worst verdict any of its test cases reached.
	 *
	 * One failed test case fails the page, however many others passed — the same gate the content
	 * check already applies to a single missing text. Today this is exactly the content verdict,
	 * because content is the only test case; the day GA4 lands it stops being.
	 */
	private static String pageVerdict(Map p) {
		Collection res = ((Map) p.results).values().findAll { it != null }
		if (res.any { it.verdict == 'FAIL' }) return 'FAIL'
		if (res.any { it.verdict == 'WARN' }) return 'WARN'
		if (res.any { it.verdict == 'PASS' }) return 'PASS'
		if (res.any { it.verdict == 'NOT_RUN' }) return 'NOT_RUN'
		return 'NA'
	}

	private static Map caseTallyOf(List cases) {
		return [pass  : cases.count { it.verdict == 'PASS' },
			warn  : cases.count { it.verdict == 'WARN' },
			fail  : cases.count { it.verdict == 'FAIL' },
			notRun: cases.count { !JUDGED.contains(it.verdict) }]
	}

	/** Pages of one group bucketed by template (the `pagetype` slug of the mapping) */
	private static List templatesOf(List gp) {
		Map buckets = [:]
		gp.each { Map p ->
			String key = p.pagetype ?: 'unclassified'
			if (buckets[key] == null) buckets[key] = [key: key, title: humanize((String) key), tpl: p.tpl, pages: []]
			buckets[key].pages << p
		}
		buckets.values().each { Map b -> b.pages = b.pages.sort { it.aem } }
		return buckets.values().sort { it.title }
	}

	/** "ilp-fund" -> "ILP Fund", "general-content-detail-page" -> "General Content Detail Page" */
	private static String humanize(String slug) {
		if (!slug) return 'Unclassified'
		return slug.split('[-_]').collect { String w ->
			ACRONYMS.contains(w.toLowerCase()) ? w.toUpperCase() : (w ? w.substring(0, 1).toUpperCase() + w.substring(1) : w)
		}.join(' ')
	}

	/**
	 * The verdicts a pass rate can be counted from.
	 *
	 * WARN belongs here. It is a page the check reached a conclusion about — through the gate, with
	 * reservations — and leaving it out would put every warned page in the same bucket as one that
	 * was never run: out of the denominator, out of the tally, and reported as work not yet done.
	 * That is exactly what happened the first time a producer started emitting WARN.
	 */
	static final List JUDGED = ['PASS', 'WARN', 'FAIL']
	/** Judged verdicts that cleared the gate. WARN is a pass that wants reading, not a failure. */
	static final List THROUGH = ['PASS', 'WARN']

	/**
	 * The pass rate of a set of pages, as `[html, cls]`: "90% (18 of 20)" plus good/bad.
	 *
	 * Two rules this rate must keep.
	 *
	 * It is a **page**-level rate and never a text-level one. It counts whole pages that cleared
	 * the verdict gate; a page that lost 30% of its text is one failed page here, exactly as it is
	 * everywhere else in the report. The two numbers answer different questions — "how many pages
	 * still need work" and "how much of this page survived" — and must not be blended into a "% of
	 * texts that survived". The second question now has its own answer, the score, and it is
	 * reported separately for that reason.
	 *
	 * Its denominator is the pages actually judged — PASS, WARN or FAIL. A page with no result on
	 * disk, or one whose result reads NOT_RUN, is in neither half: in the denominator it would
	 * read as a failure the check never made, in the numerator it would hide. Nothing judged
	 * prints no rate at all rather than 0% — an empty batch is not a total failure.
	 *
	 * WARN counts as passed, because it is: it cleared the gate. It still colours the rate amber
	 * rather than green, so a template carried entirely by warnings cannot read as clean.
	 */
	private static Map rateOf(List verdicts) {
		int total = verdicts.count { JUDGED.contains(it) }
		int passed = verdicts.count { THROUGH.contains(it) }
		int warned = verdicts.count { it == 'WARN' }
		if (total <= 0) return [html: '&mdash;', cls: '']
		int p = (int) Math.round(passed * 100.0d / total)
		return [html: p + '% <span class="of">(' + passed + ' of ' + total +
				(warned > 0 ? ', ' + warned + ' with reservations' : '') + ')</span>',
			cls : passed < total ? 'bad' : warned > 0 ? 'warn' : 'good']
	}

	/**
	 * The mean score of a set of test cases, as `[html, cls]`, or `[html: '—']` when it cannot be
	 * stated.
	 *
	 * Low-confidence scores are left out: a 92 measured over 11 items is not the same claim as a 92
	 * over 300, and averaging them together publishes the weaker one under the authority of the
	 * stronger. When EVERY score in the set is low confidence the average is withheld entirely
	 * rather than computed from nothing but weak evidence — an average of guesses is a guess.
	 */
	private static Map scoreAvgOf(List stats) {
		List usable = stats.findAll { it.score != null && !it.lowConf }
		if (!usable) return [html: '&mdash;', cls: '']
		double avg = (usable.sum { it.score as double } as double) / usable.size()
		int dropped = stats.count { it.score != null && it.lowConf }
		return [html: String.format('%.1f', avg) +
				(dropped > 0 ? " <span class=\"of\">(${dropped} low&#8209;confidence excluded)</span>" : ''),
			cls : scoreCls(avg)]
	}

	/**
	 * The band a score falls in, as a CSS class.
	 *
	 * Kept identical to ContentCompare.PASS_SCORE / WARN_SCORE, the same way ERRORS above is kept
	 * identical to ContentCompare.ERRORS — by comment, because this renderer reads files and does
	 * not import the checks that write them. Only AVERAGES are banded here; a single test case is
	 * coloured by the grade the check itself recorded, never by re-deciding it from the number.
	 */
	static final double PASS_SCORE = 95.0d
	static final double WARN_SCORE = 90.0d

	private static String scoreCls(double score) {
		return score >= PASS_SCORE ? 'good' : score >= WARN_SCORE ? 'warn' : 'bad'
	}

	// ---------------------------------------------------------------- rendering

	/**
	 * Renders into a temporary directory and swaps it into place only once every file is written.
	 *
	 * This used to delete Reports/parity-report/ as its first act and render into the hole. Any
	 * failure after that line therefore destroyed a good report to produce nothing — which is
	 * exactly what happened on 2026-08-21: the build died partway and left two asset files, an
	 * empty templates/ and an empty pages/, with the previous report already gone. A report is
	 * read far more often than it is built, and the last good one is worth more than a fast swap.
	 *
	 * Safe because every link the report emits is relative and none of them names this directory:
	 * templateHref prefixes `templates/`, checkFile is a bare filename, head() points at
	 * `assets/_site/`. Renaming the directory cannot break navigation.
	 */
	private static String render() {
		String proj = RunConfiguration.getProjectDir()
		List pages = collectPages(proj)

		File dest = new File(proj + "/Reports/parity-report")
		File root = new File(proj + "/Reports/.parity-report.tmp")
		// A tmp left behind is the wreckage of an earlier failed build, not work in progress.
		if (root.exists()) root.deleteDir()
		root.mkdirs()
		writeStaticAssets(root)
		File templatesDir = new File(root, 'templates'); templatesDir.mkdirs()
		File pagesDir = new File(root, 'pages'); pagesDir.mkdirs()

		Map byGroup = [:]
		GROUP_ORDER.each { String g -> byGroup[g] = pages.findAll { it.group == g } }
		List extraGroups = pages*.group.unique().findAll { !GROUP_ORDER.contains(it) }.sort()
		extraGroups.each { String g -> byGroup[g] = pages.findAll { it.group == g } }
		List groups = GROUP_ORDER + extraGroups

		File index = new File(root, 'index.html')
		index.setText(renderIndex(proj, pages, groups, byGroup), 'UTF-8')

		// Two ordered walks in the reading order of the index: one over the templates, one over
		// the compared pages. They are what previous/next and the jump dropdown follow, so a
		// reviewer can walk the whole report without coming back here between URLs.
		List tplNav = []
		groups.each { String g -> templatesOf((List) (byGroup[g] ?: [])).each { Map t -> tplNav << [g: g, t: t] } }
		tplNav.eachWithIndex { Map m, int i ->
			new File(templatesDir, templateFile((String) m.g, (Map) m.t))
					.setText(renderTemplateFile(proj, (String) m.g, (Map) m.t, tplNav, i), 'UTF-8')
		}

		List nav = []
		tplNav.each { Map m ->
			((Map) m.t).pages.findAll { it.result != null }.each { Map p -> nav << [g: m.g, t: m.t, p: p] }
		}
		int caseFiles = 0
		nav.eachWithIndex { Map n, int i ->
			List cases = casesOf(proj, (Map) n.p)
			new File(pagesDir, n.p.slug + '.html').setText(renderPageFile(proj, n, nav, i, cases), 'UTF-8')
			// One file per test case that actually ran: a test case with no result on disk is a
			// row on the page above, not an empty file of its own.
			cases.eachWithIndex { Map c, int j ->
				if (c.verdict == 'NA') return
				new File(pagesDir, checkFile((Map) n.p, (String) c.check))
						.setText(renderCheckFile(n, cases, j), 'UTF-8')
				caseFiles++
			}
		}

		// Everything is written: the report is now complete, and only now does the old one go.
		if (dest.exists() && !dest.deleteDir()) {
			throw new IOException("Could not remove the previous report at ${dest.getPath()} — " +
				"the new one is complete and waiting at ${root.getPath()}")
		}
		if (!root.renameTo(dest)) {
			throw new IOException("Could not move the finished report from ${root.getPath()} to " +
				"${dest.getPath()} — the report is complete, only the swap failed")
		}

		File published = new File(dest, 'index.html')
		KeywordUtil.logInfo("Parity report -> " + published.getAbsolutePath() +
			" (${tplNav.size()} template file(s), ${nav.size()} page file(s), ${caseFiles} test-case file(s))")
		return published.getAbsolutePath()
	}

	/** index.html: the run, then one row per template. No individual page is listed here. */
	private static String renderIndex(String proj, List pages, List groups, Map byGroup) {
		int compared = pages.count { it.result != null }
		int withFindings = pages.count { pageVerdict(it) == 'FAIL' }
		int withReservations = pages.count { pageVerdict(it) == 'WARN' }
		Map overall = rateOf(pages.collect { Map p -> pageVerdict(p) })

		StringBuilder h = new StringBuilder()
		h << head('', '')
		h << '<header class="cover">'
		h << '<p class="eyebrow">Website migration &middot; quality assurance</p>'
		h << '<h1>Migration parity report</h1>'
		h << '<p class="lede">Every page of the new site is compared against the live site, which is the reference. '
		h << 'Open a template to see its pages, a page to see the test cases run against it, '
		h << 'and a test case to see the texts that did not survive.</p>'
		h << '<dl class="facts">'
		h << "<div><dt>Generated</dt><dd>${new Date().format('yyyy-MM-dd HH:mm')}</dd></div>"
		h << "<div><dt>Pages in scope</dt><dd>${pages.size()}</dd></div>"
		h << "<div><dt>Compared in this run</dt><dd>${compared}</dd></div>"
		h << "<div><dt>Pages with findings</dt><dd class=\"${withFindings > 0 ? 'bad' : 'good'}\">${withFindings}</dd></div>"
		if (withReservations > 0) {
			h << "<div><dt>Passed with reservations</dt><dd class=\"warn\">${withReservations}</dd></div>"
		}
		h << "<div><dt>Pass rate</dt><dd class=\"${overall.cls}\">${overall.html}</dd></div>"
		h << '</dl></header>'

		h << '<section class="overview"><h2 class="minor">Templates</h2>'
		h << '<div class="scroll"><table class="matrix"><thead><tr><th>Template</th><th>Pages</th><th>Compared</th>'
		h << '<th>Texts compared</th><th>Texts failed</th><th>Avg score</th><th>Pass rate</th>'
		h << '<th>Result</th><th></th></tr></thead><tbody>'
		groups.each { String g ->
			List gp = (List) byGroup[g]
			if (!gp) return
			h << '<tr class="grouprow"><th colspan="9" scope="colgroup">'
			h << esc(GROUP_TITLE[g] ?: (g.capitalize() + ' pages')) + '</th></tr>'
			templatesOf(gp).each { Map t ->
				List done = t.pages.findAll { it.result != null }
				List stats = done.collect { Map p -> statsOf(proj, p) }
				List verdicts = done.collect { Map p -> pageVerdict(p) }
				int failedPages = verdicts.count { it == 'FAIL' }
				int items = (stats.sum { it.items } ?: 0) as int
				int failedTexts = (stats.sum { it.failed } ?: 0) as int
				h << "<tr><th scope=\"row\"><a href=\"${templateHref(g, t, '')}\">${esc(t.title)}</a></th>"
				h << "<td class=\"num\">${t.pages.size()}</td><td class=\"num\">${done.size()}</td>"
				h << "<td class=\"num\">${items ?: '&mdash;'}</td>"
				h << '<td class="num">' + (failedTexts > 0 ? "<b class=\"bad\">${failedTexts}</b>" : '&mdash;') + '</td>'
				Map avg = scoreAvgOf(stats)
				h << "<td class=\"num rate ${avg.cls}\">${avg.html}</td>"
				Map r = rateOf(verdicts)
				h << "<td class=\"num rate ${r.cls}\">${r.html}</td>"
				h << '<td>' + (done.isEmpty() ? '<span class="chip NA">not compared</span>'
						: failedPages > 0 ? "<span class=\"chip FAIL\">${failedPages} of ${done.size()} with findings</span>"
						: "<span class=\"chip PASS\">${done.size()} passed</span>") + '</td>'
				h << "<td><a href=\"${templateHref(g, t, '')}\">open &rsaquo;</a></td></tr>"
			}
		}
		h << '</tbody></table></div></section>'
		h << footer()
		h << '</main></body></html>'
		return h.toString()
	}

	/** One template = one file: the pages that report it, and nothing else. */
	private static String renderTemplateFile(String proj, String group, Map t, List tplNav, int i) {
		String gTitle = GROUP_TITLE[group] ?: (group.capitalize() + ' pages')
		List done = t.pages.findAll { it.result != null }
		List verdicts = done.collect { Map p -> pageVerdict(p) }
		int failedPages = verdicts.count { it == 'FAIL' }
		Map prev = i > 0 ? (Map) tplNav[i - 1] : null
		Map next = i < tplNav.size() - 1 ? (Map) tplNav[i + 1] : null

		StringBuilder h = new StringBuilder()
		h << head((String) t.title, '../')
		h << '<div class="topbar">'
		h << '<nav class="crumbs"><a href="../index.html">Migration parity report</a>'
		h << "<span>${esc(gTitle)}</span><span>${esc(t.title)}</span></nav>"
		h << '<div class="pagenav">'
		h << (prev ? "<a class=\"navbtn\" rel=\"prev\" href=\"${escAttr(templateFile((String) prev.g, (Map) prev.t))}\">&lsaquo; ${esc(prev.t.title)}</a>"
				: '<span class="navbtn off">&lsaquo; Previous</span>')
		h << "<span class=\"navcount\">Template ${i + 1} of ${tplNav.size()}</span>"
		h << (next ? "<a class=\"navbtn\" rel=\"next\" href=\"${escAttr(templateFile((String) next.g, (Map) next.t))}\">${esc(next.t.title)} &rsaquo;</a>"
				: '<span class="navbtn off">Next &rsaquo;</span>')
		h << '</div></div>'

		h << "<article class=\"page ${failedPages > 0 ? 'has-findings' : ''}\">"
		h << "<div class=\"page-head\"><h1>${esc(t.title)}</h1><p class=\"sub\">${esc(gTitle)}"
		if (t.tpl) h << " &middot; AEM template &ldquo;${esc(t.tpl)}&rdquo;"
		h << '</p></div>'
		h << "<p class=\"page-status\">${t.pages.size()} page(s) report this template &middot; "
		h << "${done.size()} compared in this run &middot; "
		h << (failedPages > 0 ? "${failedPages} with findings" : 'none with findings') + '</p>'
		Map r = rateOf(verdicts)
		h << "<p class=\"page-rate\">Pass rate for this template: <b class=\"${r.cls}\">${r.html}</b></p>"
		h << templateBody(proj, t)
		h << '<p class="sub pagefoot"><a href="../index.html">&lsaquo; Back to all templates</a>'
		if (next) h << "<a href=\"${escAttr(templateFile((String) next.g, (Map) next.t))}\">Next template &rsaquo;</a>"
		h << '</p></article>'
		h << footer()
		h << '</main></body></html>'
		return h.toString()
	}

	/**
	 * The pages of one template, each with how many of its test cases passed and how many failed.
	 *
	 * Deliberately not the text counts any more: a page now runs several test cases, and "127
	 * texts compared" is a fact about one of them. The per-text numbers live on that test case's
	 * own file, one click further in, where they belong to something.
	 *
	 * Pages nobody compared get no file of their own, only a folded note — one empty card each
	 * would bury the pages that were compared.
	 */
	private static String templateBody(String proj, Map t) {
		List done = t.pages.findAll { it.result != null }
		List pending = t.pages.findAll { it.result == null }
		StringBuilder h = new StringBuilder()
		if (done) {
			h << '<div class="scroll"><table class="matrix"><thead><tr><th>Page</th><th>Result</th>'
			h << '<th>Score</th><th>Test cases</th><th></th></tr></thead><tbody>'
			// Worst score first. This table is the work queue for a template, and the page that lost
			// the most content is the one to open first — alphabetical order buries it.
			done.sort { Map p ->
				Double sc = (Double) statsOf(proj, p).score
				sc == null ? 101.0d : sc
			}.each { Map p ->
				String v = pageVerdict(p)
				Map st = statsOf(proj, p)
				Map tally = caseTallyOf(casesOf(proj, p))
				h << "<tr><th scope=\"row\"><a class=\"mono\" href=\"${pageHref(p, '../')}\">${esc(shortPath((String) p.aem))}</a></th>"
				h << "<td><span class=\"chip ${v}\">${v == 'NA' ? 'not run' : v.toLowerCase()}</span></td>"
				h << '<td class="num">' + (st.score == null ? '&mdash;'
						: "<b class=\"${v == 'FAIL' ? 'bad' : v == 'WARN' ? 'warn' : 'good'}\">" +
						String.format('%.1f', st.score as double) + '</b>' +
						(st.lowConf ? '<span class="of"> low conf.</span>' : '')) + '</td>'
				h << '<td class="casetally">' + tallyHtml(tally) + '</td>'
				h << "<td><a href=\"${pageHref(p, '../')}\">open &rsaquo;</a></td></tr>"
			}
			h << '</tbody></table></div>'
		}
		if (pending) {
			h << "<details class=\"pending\"><summary>${pending.size()} page(s) not compared in this run "
			h << '&mdash; out of the current batch or not published yet</summary><ul class="plain">'
			pending.each { Map p -> h << "<li><a class=\"mono\" href=\"${esc(p.aem)}\">${esc(shortPath((String) p.aem))}</a></li>" }
			h << '</ul></details>'
		}
		return h.toString()
	}

	/**
	 * One page = one file: which test cases were run against this URL, and how each one came out.
	 *
	 * The findings themselves are one click further in, on the test case that produced them. This
	 * file is the page's contents list, and it lists every id of CHECK_ORDER — including the ones
	 * with no result on disk. A test case nobody has written yet is a visible gap here, not an
	 * absence: that is how a reader sees that this URL has never been checked for GA4.
	 */
	private static String renderPageFile(String proj, Map n, List nav, int i, List cases) {
		String group = (String) n.g
		Map t = (Map) n.t
		Map p = (Map) n.p
		Map tally = caseTallyOf(cases)
		int failedCases = tally.fail as int
		Map next = i < nav.size() - 1 ? (Map) nav[i + 1] : null
		String gTitle = GROUP_TITLE[group] ?: (group.capitalize() + ' pages')

		StringBuilder h = new StringBuilder()
		h << head(shortPath((String) p.aem), '../')
		h << '<div class="topbar">'
		h << '<nav class="crumbs"><a href="../index.html">Migration parity report</a>'
		h << "<span>${esc(gTitle)}</span><a href=\"${templateHref(group, t, '../')}\">${esc(t.title)}</a></nav>"
		h << renderPageNav(nav, i)
		h << '</div>'

		h << "<article class=\"page ${failedCases > 0 ? 'has-findings' : ''}\">"
		h << "<div class=\"page-head\"><h1 class=\"mono pagetitle\">${esc(shortPath((String) p.aem))}</h1>"
		h << "<p class=\"links\"><a href=\"${esc(p.sc)}\">live page</a><a href=\"${esc(p.aem)}\">new page</a></p></div>"

		h << '<p class="page-status">Test cases run against this URL: ' + tallyHtml(tally) + '</p>'

		h << '<div class="scroll"><table class="matrix"><thead><tr><th>Test case</th><th>Result</th>'
		h << '<th>Score</th><th>Findings</th><th></th></tr></thead><tbody>'
		cases.each { Map c ->
			String id = (String) c.check
			String v = (String) c.verdict
			int failed = c.failed as int
			String title = CHECK_TITLE[id] ?: humanize(id)
			boolean hasFile = v != 'NA'
			h << '<tr><th scope="row">'
			h << (hasFile ? "<a href=\"${escAttr(checkFile(p, id))}\">${esc(title)}</a>" : esc(title))
			h << "<span class=\"sub casedesc\">${esc(CHECK_DESC[id] ?: '')}</span></th>"
			h << "<td><span class=\"chip ${v}\">${v == 'NA' ? 'not run yet' : v == 'NOT_RUN' ? 'not run' : v.toLowerCase()}</span></td>"
			h << '<td class="num">' + (c.score == null ? '&mdash;'
					: "<b class=\"${v == 'FAIL' ? 'bad' : v == 'WARN' ? 'warn' : 'good'}\">" +
					String.format('%.1f', c.score as double) + '</b>') + '</td>'
			h << '<td class="num">' + (failed > 0 ? "<b class=\"bad\">${failed}</b>" : '&mdash;') + '</td>'
			h << '<td>' + (hasFile ? "<a href=\"${escAttr(checkFile(p, id))}\">open &rsaquo;</a>" : '') + '</td></tr>'
		}
		h << '</tbody></table></div>'

		h << '<p class="sub pagefoot"><a href="../index.html">&lsaquo; Back to all templates</a>'
		h << "<a href=\"${templateHref(group, t, '../')}\">&lsaquo; ${esc(t.title)}</a>"
		if (next) h << "<a href=\"${escAttr(next.p.slug)}.html\">Next page &rsaquo;</a>"
		h << '</p></article>'
		h << footer()
		h << '</main></body></html>'
		return h.toString()
	}

	/**
	 * One test case on one URL = one file: the counts, then the texts that failed it.
	 *
	 * This is where the per-text numbers live now. Previous/next moves between the test cases of
	 * *this* URL, not between URLs — a reader who opens this file is working on one page.
	 *
	 * `cases` is that page's full CHECK_ORDER list and `j` the index of the one being rendered,
	 * so the nav can be built without re-reading anything.
	 */
	private static String renderCheckFile(Map n, List cases, int j) {
		String group = (String) n.g
		Map t = (Map) n.t
		Map p = (Map) n.p
		Map s = (Map) cases[j]
		String id = (String) s.check
		String title = CHECK_TITLE[id] ?: humanize(id)
		String verdict = (String) s.verdict
		int failed = s.failed as int
		String gTitle = GROUP_TITLE[group] ?: (group.capitalize() + ' pages')
		String pageName = shortPath((String) p.aem)

		// Only the test cases that have a file of their own are reachable from the nav.
		List sib = cases.findAll { it.verdict != 'NA' }
		int k = sib.findIndexOf { it.check == id }
		Map prev = k > 0 ? (Map) sib[k - 1] : null
		Map next = k >= 0 && k < sib.size() - 1 ? (Map) sib[k + 1] : null

		StringBuilder h = new StringBuilder()
		h << head(title + ' — ' + pageName, '../')
		h << '<div class="topbar">'
		h << '<nav class="crumbs"><a href="../index.html">Migration parity report</a>'
		h << "<span>${esc(gTitle)}</span><a href=\"${templateHref(group, t, '../')}\">${esc(t.title)}</a>"
		h << "<a class=\"mono\" href=\"${escAttr(p.slug)}.html\">${esc(pageName)}</a></nav>"
		h << '<div class="pagenav">'
		h << (prev ? "<a class=\"navbtn\" id=\"nav-prev\" rel=\"prev\" href=\"${escAttr(checkFile(p, (String) prev.check))}\">&lsaquo; ${esc(CHECK_TITLE[prev.check] ?: prev.check)}</a>"
				: '<span class="navbtn off">&lsaquo; Previous</span>')
		h << "<span class=\"navcount\">Test case ${k + 1} of ${sib.size()}</span>"
		h << (next ? "<a class=\"navbtn\" id=\"nav-next\" rel=\"next\" href=\"${escAttr(checkFile(p, (String) next.check))}\">${esc(CHECK_TITLE[next.check] ?: next.check)} &rsaquo;</a>"
				: '<span class="navbtn off">Next &rsaquo;</span>')
		h << '</div></div>'

		h << "<article class=\"page ${failed > 0 || verdict == 'FAIL' ? 'has-findings' : ''}\">"
		h << "<div class=\"page-head\"><h1>${esc(title)}</h1>"
		h << "<p class=\"sub\">on <a class=\"mono\" href=\"${escAttr(p.slug)}.html\">${esc(pageName)}</a>"
		h << " &middot; ${esc(CHECK_DESC[id] ?: '')}</p>"
		h << "<p class=\"links\"><a href=\"${esc(p.sc)}\">live page</a><a href=\"${esc(p.aem)}\">new page</a></p></div>"

		if (verdict == 'NOT_RUN') {
			h << '<p class="page-status"><span class="badge NOT_RUN">not run</span> '
			h << 'This URL does not serve a page to compare.</p>'
		} else {
			h << "<p class=\"page-status\"><span class=\"badge ${verdict}\">${verdict}</span> "
			// A page can FAIL with no failed text: the verdict comes from content.txt, and the reason
			// may be one that findings.csv does not carry a row for (see NOT_RENDERED). Saying "all
			// were found" beside a red FAIL badge would be the report arguing with itself.
			h << (failed > 0
					? "<b class=\"bad\">${failed}</b> of ${s.items} live text(s) did not survive the migration."
					: verdict == 'FAIL' && s.items == 0
					? "Nothing on this page was compared &mdash; see below."
					: verdict == 'FAIL'
					? "No live text is missing. This page fails for a reason no single text records &mdash; see below."
					: "All ${s.items} live text(s) were found on the new page.")
			h << '</p>'
			h << '<dl class="facts">'
			h << "<div><dt>Live texts compared</dt><dd>${s.items}</dd></div>"
			h << "<div><dt>Failed</dt><dd class=\"${failed > 0 ? 'bad' : 'good'}\">${failed}</dd></div>"
			h << "<div><dt>Warnings</dt><dd>${s.warned}</dd></div>"
			h << "<div><dt>Only on the new page</dt><dd>${s.extra}</dd></div>"
			if (s.score != null) {
				// Coloured by the recorded verdict, not by re-banding the number: when a hard-fail
				// verdict overrode a high score, a green 99 beside a red FAIL is the report arguing
				// with itself. The score says how much survived; the badge above says what to do.
				h << "<div><dt>Score</dt><dd class=\"${verdict == 'FAIL' ? 'bad' : verdict == 'WARN' ? 'warn' : 'good'}\">"
				h << "${String.format('%.1f', s.score as double)}<span class=\"of\"> / 100</span></dd></div>"
			}
			h << '</dl>'
			if (s.score != null && verdict == 'FAIL' && (s.score as double) >= WARN_SCORE) {
				h << '<p class="callout">This page scores above the pass mark and still fails: it carries a '
				h << 'finding that no score can offset &mdash; a changed figure, or a comparison whose two '
				h << 'sides did not read the same amount of content.'
				// Only when there IS a block to point at. An unread comparison is reported by the
				// low-confidence callout below instead, and draws no block of its own.
				h << (failed > 0 ? ' The finding is below.' : '') + '</p>'
			}
			if (s.lowConf && s.confWhy) {
				h << "<p class=\"callout warn\">Low confidence &mdash; ${esc(s.confWhy)}. "
				h << (failed > 0 ? 'Read the findings rather than the score.'
						: 'Re-capture this page before reading anything else it reports.') + '</p>'
			}
			// The failing texts open, everything else folded: this file is read to fix failures.
			h << findingBlocks((List) s.rows, ERRORS, true)
			h << findingBlocks((List) s.rows, WARNINGS, false)
			h << findingBlocks((List) s.rows, INFOS, false)
			if (!s.rows) h << '<p class="sub">No findings were written for this test case.</p>'
		}

		h << "<p class=\"sub pagefoot\"><a href=\"${escAttr(p.slug)}.html\">&lsaquo; All test cases for this page</a>"
		h << "<a href=\"${templateHref(group, t, '../')}\">&lsaquo; ${esc(t.title)}</a>"
		h << '<a href="../index.html">&lsaquo; Back to all templates</a>'
		h << '</p></article>'
		h << footer()
		h << '</main></body></html>'
		return h.toString()
	}

	/** "1 pass / 1 warn / 2 fail · 1 not run" — the same phrasing wherever test cases are counted. */
	private static String tallyHtml(Map tally) {
		int pass = tally.pass as int
		int warn = (tally.warn ?: 0) as int
		int fail = tally.fail as int
		int notRun = tally.notRun as int
		StringBuilder h = new StringBuilder()
		h << (pass > 0 ? "<b class=\"good\">${pass}</b>" : '0') + ' pass'
		// Only shown when it happened. A permanent "0 warn" on every page of a site that has none
		// is a column of noise between the two numbers anyone is actually reading.
		if (warn > 0) h << " / <b class=\"warn\">${warn}</b> with reservations"
		h << ' / ' + (fail > 0 ? "<b class=\"bad\">${fail}</b>" : '0') + ' fail'
		if (notRun > 0) h << " <span class=\"sub\">&middot; ${notRun} not run</span>"
		return h.toString()
	}

	/**
	 * The findings of one severity, one block per verdict, worst first.
	 *
	 * Severity is carried three ways at once — a coloured left rule, a tinted header band and a
	 * spelled-out label — so it survives a greyscale printout and a colour-blind reader.
	 */
	private static String findingBlocks(List rows, List verdicts, boolean open) {
		StringBuilder h = new StringBuilder()
		FINDING_ORDER.findAll { verdicts.contains(it) && !NOT_RENDERED.contains(it) }.each { String v ->
			List got = rows.findAll { it.verdict == v }
			if (!got) return
			String lvl = level(v)
			h << "<details class=\"pairblock ${lvl}\"${open ? ' open' : ''}>"
			h << "<summary><span class=\"sev\">${LEVEL_LABEL[lvl]}</span>"
			h << "<b>${esc(FINDING_TITLE[v])}</b>"
			h << "<span class=\"tally\">${got.size()} text${got.size() == 1 ? '' : 's'}</span></summary>"
			h << "<p class=\"sub\">${esc(FINDING_HINT[v])}</p>"
			List cols = (List) (FINDING_COLUMNS[v] ?: FINDING_COLUMNS_DEFAULT)
			h << '<table class="difftable"><tr>' + cols.collect { "<th>${esc((String) ((List) it)[0])}</th>" }.join('') + '</tr>'
			got.each { Map r ->
				h << '<tr>' + cols.collect { List c ->
					String val = (String) (r[(String) c[1]] ?: '')
					// Only `path` gets a placeholder: a blank text or note is a blank cell, not an em dash.
					if (c[1] == 'path' && !val) val = '—'
					return "<td>${esc(val)}</td>"
				}.join('') + '</tr>'
			}
			h << '</table></details>'
		}
		return h.toString()
	}

	/**
	 * Previous / next / "page n of m" / jump dropdown. The two anchors are the no-JavaScript path
	 * and what the keyboard shortcuts drive; the dropdown needs report.js.
	 */
	private static String renderPageNav(List nav, int i) {
		Map prev = i > 0 ? (Map) nav[i - 1] : null
		Map next = i < nav.size() - 1 ? (Map) nav[i + 1] : null
		StringBuilder h = new StringBuilder()
		h << '<div class="pagenav">'
		h << (prev ? "<a class=\"navbtn\" id=\"nav-prev\" rel=\"prev\" href=\"${escAttr(prev.p.slug)}.html\">&lsaquo; Previous</a>"
				: '<span class="navbtn off">&lsaquo; Previous</span>')
		h << "<span class=\"navcount\">Page ${i + 1} of ${nav.size()}</span>"
		h << (next ? "<a class=\"navbtn\" id=\"nav-next\" rel=\"next\" href=\"${escAttr(next.p.slug)}.html\">Next &rsaquo;</a>"
				: '<span class="navbtn off">Next &rsaquo;</span>')
		h << '<label class="jump js-only" hidden>Jump to<select class="pagejump" id="pagejump">'
		String lastKey = null
		nav.eachWithIndex { Map m, int j ->
			String key = m.g + '/' + m.t.key
			if (key != lastKey) {
				if (lastKey != null) h << '</optgroup>'
				h << "<optgroup label=\"${escAttr((GROUP_TITLE[m.g] ?: m.g) + ' — ' + m.t.title)}\">"
				lastKey = key
			}
			h << "<option value=\"${escAttr(m.p.slug)}.html\"${j == i ? ' selected' : ''}>${esc(shortPath((String) m.p.aem))}</option>"
		}
		if (lastKey != null) h << '</optgroup>'
		h << '</select></label></div>'
		return h.toString()
	}

	// ---------------------------------------------------------------- links and escaping

	/** `prefix` reaches the output root from the linking file: '' on the index, '../' below it */
	private static String pageHref(Map p, String prefix = '') {
		return prefix + 'pages/' + p.slug + '.html'
	}

	/**
	 * One test case of one page. Flat inside pages/ rather than in a pages/<slug>/ folder, so
	 * every file below the root sits at the same depth and reaches assets/_site/ with '../'.
	 */
	private static String checkFile(Map p, String checkId) {
		return p.slug + '__' + checkId + '.html'
	}

	/** The page group is part of the file name: two groups may share one pagetype */
	private static String templateFile(String group, Map t) {
		return group + '-' + t.key + '.html'
	}

	private static String templateHref(String group, Map t, String prefix) {
		return prefix + 'templates/' + templateFile(group, t)
	}

	private static String shortPath(String url) {
		return url.replaceAll('^https?://[^/]+', '') ?: '/'
	}

	static String esc(Object s) {
		return (s == null ? '' : s.toString()).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
	}

	/** Attribute values need the quotes escaped too — esc() alone is only safe in a text node */
	private static String escAttr(Object s) {
		return esc(s).replace('"', '&quot;').replace((String) "'", '&#39;')
	}

	// ---------------------------------------------------------------- page furniture

	/**
	 * `prefix` reaches assets/_site/ from where this file sits: '' on the index, '../' from
	 * templates/ and pages/.
	 *
	 * Deliberately a single light theme: this is a document that gets read, shared and printed,
	 * so every colour is painted explicitly rather than inherited from whatever renders it.
	 */
	private static String head(String subtitle, String prefix) {
		StringBuilder h = new StringBuilder()
		h << '<!doctype html><html lang="en"><head><meta charset="utf-8">'
		h << '<meta name="viewport" content="width=device-width, initial-scale=1">'
		h << "<title>${subtitle ? esc(subtitle) + ' — ' : ''}Migration Parity Report</title>"
		h << "<link rel=\"stylesheet\" href=\"${prefix}assets/_site/report.css\">"
		h << "<script defer src=\"${prefix}assets/_site/report.js\"></script>"
		h << "</head><body class=\"${subtitle ? 'pageview' : 'indexview'}\"><main>"
		return h.toString()
	}

	private static String footer() {
		return '<footer class="foot"><p class="sub">Generated ' + new Date().format('yyyy-MM-dd HH:mm') +
			' &middot; the live site is the reference; extra content on the new site is never a failure.</p>' +
			'<p class="sub kbd js-only" hidden>Keyboard: <kbd>&larr;</kbd> / <kbd>&rarr;</kbd> previous and next page</p>' +
			'</footer>'
	}

	/**
	 * The stylesheet and the script are written once per output root, under assets/_site/ so they
	 * can never collide with anything else. Inlining them would duplicate ~8 KB into every file.
	 */
	private static void writeStaticAssets(File root) {
		File dir = new File(root, 'assets/_site')
		dir.mkdirs()
		new File(dir, 'report.css').setText(stylesheet(), 'UTF-8')
		new File(dir, 'report.js').setText(SCRIPT, 'UTF-8')
	}

	/**
	 * The only behaviour the report has, emitted to assets/_site/report.js.
	 *
	 * A classic script with no dependency and no fetch: this is opened from file:// as often as
	 * from a server. Everything here is additive — navigation is plain links, so with JavaScript
	 * off the report still works; the jump dropdown and the keyboard hint simply stay hidden.
	 *
	 * NOTE: this is a triple-quoted Groovy string. Do not put a backslash in it — Katalon's
	 * Groovy-Eclipse compiler rejects unknown escapes and emits an ~800-byte error stub for the
	 * whole keyword instead of a class (see the 2026-08-18 entry in project-tracking).
	 */
	private static final String SCRIPT = '''(function () {
  'use strict';
  var all = function (sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); };
  var byId = function (id) { return document.getElementById(id); };

  // a link inside a summary should navigate, not fold the block it labels
  all('summary a').forEach(function (a) {
    a.addEventListener('click', function (e) { e.stopPropagation(); });
  });

  var jump = byId('pagejump');
  if (jump) {
    if (jump.parentNode) jump.parentNode.hidden = false;
    jump.addEventListener('change', function () {
      if (jump.value) window.location.href = jump.value;
    });
  }
  all('.kbd').forEach(function (k) { k.hidden = false; });

  document.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    var node = document.activeElement ? document.activeElement.nodeName : '';
    if (node === 'INPUT' || node === 'SELECT' || node === 'TEXTAREA') return;
    var id = (e.key === 'ArrowLeft' || e.key === 'k') ? 'nav-prev'
      : (e.key === 'ArrowRight' || e.key === 'j') ? 'nav-next' : '';
    if (!id) return;
    var a = byId(id);
    if (a) { e.preventDefault(); window.location.href = a.getAttribute('href'); }
  });

  // paper gets the evidence too: a collapsed <details> prints empty otherwise
  window.addEventListener('beforeprint', function () {
    all('details').forEach(function (d) {
      if (!d.open) { d.setAttribute('data-was-closed', '1'); d.open = true; }
    });
  });
  window.addEventListener('afterprint', function () {
    all('details[data-was-closed]').forEach(function (d) {
      d.open = false;
      d.removeAttribute('data-was-closed');
    });
  });
}());'''

	/** The stylesheet, emitted to assets/_site/report.css */
	private static String stylesheet() {
		StringBuilder h = new StringBuilder()

		h << ':root{--ground:#FBFBFC;--surface:#FFFFFF;--sunk:#F4F6F9;--ink:#14181F;--muted:#5B6572;'
		h << '--line:#E2E6EB;--line-strong:#C9D0D9;--accent:#2C4A7C;--accent-soft:#EEF2F8;'
		h << '--fail:#B3261E;--fail-soft:#FBEEEC;--warn:#8A5A00;--warn-soft:#FCF3E1;--pass:#146C43;--pass-soft:#EBF5EF;'
		h << "--serif:'Iowan Old Style','Palatino Linotype',Palatino,Georgia,'Times New Roman',serif;"
		h << "--sans:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,Roboto,sans-serif;"
		h << '--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}'
		h << '*{box-sizing:border-box}[hidden]{display:none!important}'
		h << 'body{margin:0;padding:36px 16px 80px;background:var(--ground);color:var(--ink);'
		h << 'font-family:var(--sans);font-size:15px;line-height:1.55;-webkit-font-smoothing:antialiased}'
		h << 'main{max-width:1440px;margin:0 auto;display:flex;flex-direction:column;gap:36px}'
		h << 'body.pageview main{max-width:1680px}'
		h << 'a{color:var(--accent)}a:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:2px;border-radius:3px}'
		h << '.mono{font-family:var(--mono)}.num{font-variant-numeric:tabular-nums;text-align:right}'
		h << '.sub{color:var(--muted);font-size:13px;margin:0}'

		// cover and run facts
		h << '.cover{border-bottom:3px double var(--line-strong);padding-bottom:28px;display:flex;flex-direction:column;gap:14px}'
		h << '.eyebrow{margin:0;font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:var(--accent);font-weight:600}'
		h << 'h1{font-family:var(--serif);font-size:38px;line-height:1.15;font-weight:600;margin:0;text-wrap:balance;letter-spacing:-.01em}'
		h << '.lede{margin:0;max-width:64ch;color:var(--muted);font-size:16px}'
		h << 'h2.minor{font-family:var(--sans);font-size:12px;font-weight:600;letter-spacing:.11em;'
		h << 'text-transform:uppercase;color:var(--muted);margin:0 0 12px}'
		h << '.facts{display:flex;flex-wrap:wrap;gap:0;margin:8px 0 0;border-top:1px solid var(--line)}'
		h << '.facts>div{flex:1 1 160px;padding:12px 20px 2px 0;border-right:1px solid var(--line);margin-right:20px}'
		h << '.facts>div:last-child{border-right:0}'
		h << '.facts dt{font-size:11px;letter-spacing:.09em;text-transform:uppercase;color:var(--muted)}'
		h << '.facts dd{margin:2px 0 0;font-family:var(--serif);font-size:24px;font-variant-numeric:tabular-nums}'
		h << '.facts dd.bad{color:var(--fail)}.facts dd.good{color:var(--pass)}.facts dd.warn{color:var(--warn)}'
		// A verdict that has no rule renders as unstyled body text and stops being a signal at all —
		// which is the quiet way a whole band of results goes unread. Every class the score can
		// produce (good/warn/bad) is defined everywhere it can appear.
		h << 'td.rate.warn{color:var(--warn)}b.warn,td b.warn,td.casetally b.warn,.page-status b.warn{color:var(--warn)}'
		h << '.callout{margin:16px 0;padding:12px 14px;border-left:3px solid var(--fail);'
		h << 'background:var(--fail-soft);color:var(--fail);border-radius:0 6px 6px 0;font-size:14px}'
		h << '.callout.warn{border-left-color:var(--warn);background:var(--warn-soft);color:var(--warn)}'
		// "(18 of 20)" rides along with the percentage everywhere: a rate with no denominator
		// hides that 100% can mean two pages.
		h << '.facts dd .of{font-family:var(--sans);font-size:12px;color:var(--muted)}'

		// the template list and the page list are the same table
		h << '.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:8px;background:var(--surface)}'
		h << 'table.matrix{border-collapse:collapse;width:100%;font-size:13px}'
		h << '.matrix th,.matrix td{padding:9px 14px;text-align:left;border-bottom:1px solid var(--line);white-space:nowrap}'
		h << '.matrix thead th{background:var(--sunk);font-size:11px;letter-spacing:.07em;text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.matrix tbody tr:last-child td,.matrix tbody tr:last-child th{border-bottom:0}'
		h << '.matrix .grouprow th{background:var(--accent-soft);font-family:var(--serif);font-size:15px;font-weight:600}'
		h << '.matrix tbody th[scope=row]{font-weight:500;padding-left:26px}'
		h << '.num b.bad,td b.bad{color:var(--fail)}'
		h << 'td.rate{white-space:nowrap}td.rate.bad{color:var(--fail)}td.rate.good{color:var(--pass)}'
		h << 'td.rate .of{font-size:11px;color:var(--muted)}'

		// verdict chips and badges
		h << '.chip,.badge{display:inline-block;font-size:11px;font-weight:600;line-height:1.7;'
		h << 'border-radius:999px;padding:0 10px;white-space:nowrap;text-decoration:none}'
		h << '.badge{border-radius:5px;letter-spacing:.06em;text-transform:uppercase}'
		h << '.chip.FAIL,.badge.FAIL{background:var(--fail-soft);color:var(--fail)}'
		h << '.chip.WARN,.badge.WARN{background:var(--warn-soft);color:var(--warn)}'
		h << '.chip.PASS,.badge.PASS{background:var(--pass-soft);color:var(--pass)}'
		h << '.chip.NA,.badge.NA,.chip.NOT_RUN,.badge.NOT_RUN{background:var(--sunk);color:var(--muted)}'

		// the test-case tally, on the template table and at the head of a page
		h << 'td.casetally{font-variant-numeric:tabular-nums;color:var(--muted)}'
		h << 'td.casetally b.good,.page-status b.good{color:var(--pass)}'
		h << 'td.casetally b.bad{color:var(--fail)}'
		h << 'td.casetally .sub,.page-status .sub{font-size:12px;color:var(--muted)}'
		// the one-line description under a test case name: it wraps, the cell around it does not
		h << 'th .casedesc{display:block;white-space:normal;font-weight:400;font-size:11.5px;'
		h << 'max-width:46ch;margin-top:2px}'

		// the sheet a template or a page is printed on
		h << 'article.page{border:1px solid var(--line);border-radius:8px;background:var(--surface);'
		h << 'padding:16px 18px;margin:12px 0;display:flex;flex-direction:column;gap:12px}'
		// The severity lives on each finding block; a second red rail down the whole article
		// competed with them and made a page with one warning look as alarming as one with twenty.
		h << 'article.page.has-findings{border-top:3px solid var(--fail)}'
		h << '.page-head{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 18px;'
		h << 'padding-bottom:10px;border-bottom:1px solid var(--line)}'
		h << 'h1.pagetitle{font-family:var(--mono);font-size:20px;font-weight:600;word-break:break-all}'
		h << '.links{margin:0;display:flex;gap:16px}'
		h << '.links a{font-size:13px;text-decoration:none}.links a:hover{text-decoration:underline}'
		h << '.links a:after{content:" \\2197";font-size:11px}'
		h << '.page-status{margin:0;font-size:13px;color:var(--muted)}'
		h << '.page-status b.bad{color:var(--fail)}.page-status .badge{margin-right:6px}'
		h << '.page-rate{margin:6px 0 0;font-size:13px;color:var(--muted)}'
		h << '.page-rate b{font-size:16px;font-variant-numeric:tabular-nums}'
		h << '.page-rate b.bad{color:var(--fail)}.page-rate b.good{color:var(--pass)}'
		h << '.page-rate .of{font-size:12px;color:var(--muted);font-weight:400}'
		h << 'details.pending{border:1px dashed var(--line-strong);border-radius:8px;padding:12px 16px;background:var(--surface)}'
		h << 'ul.plain{list-style:none;margin:10px 0 0;padding:0;display:flex;flex-direction:column;gap:4px;font-size:13px}'
		h << 'summary{cursor:pointer;font-size:12.5px;color:var(--muted);font-weight:500}'
		h << 'summary:hover{color:var(--accent)}'

		// One finding block = one verdict. The severity is carried three ways — a coloured left
		// rule, a tinted header band and a spelled-out label — so it survives a greyscale print
		// and a colour-blind reader, which a tint alone does not.
		h << '.pairblock{border:1px solid var(--line);border-left:3px solid var(--line-strong);'
		h << 'border-radius:6px;background:var(--surface);overflow:hidden}'
		h << '.pairblock+.pairblock{margin-top:12px}'
		h << '.pairblock>summary{display:flex;align-items:center;gap:10px;flex-wrap:wrap;'
		h << 'padding:10px 12px;background:var(--sunk);font-size:13.5px;font-weight:600;'
		h << 'color:var(--ink);list-style:none}'
		h << '.pairblock>summary::-webkit-details-marker{display:none}'
		h << '.pairblock>summary::marker{content:""}'
		h << '.pairblock[open]>summary{border-bottom:1px solid var(--line)}'
		h << '.pairblock>summary:hover{filter:brightness(.985)}'
		h << '.pairblock .sev{font-size:10px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;'
		h << 'padding:3px 7px;border-radius:4px;background:var(--surface);color:var(--muted);'
		h << 'border:1px solid var(--line-strong);white-space:nowrap}'
		h << '.pairblock .tally{margin-left:auto;font-size:11px;font-weight:600;padding:2px 9px;'
		h << 'border-radius:999px;background:var(--surface);color:var(--muted);'
		h << 'border:1px solid var(--line);white-space:nowrap;font-variant-numeric:tabular-nums}'
		h << '.pairblock>.sub,.pairblock>table{margin:10px 12px}'
		h << '.pairblock.err{border-left-color:var(--fail)}'
		h << '.pairblock.err>summary{background:var(--fail-soft);color:var(--fail)}'
		h << '.pairblock.err .sev{background:var(--fail);color:#fff;border-color:var(--fail)}'
		h << '.pairblock.err .tally{color:var(--fail);border-color:var(--fail)}'
		h << '.pairblock.warn{border-left-color:var(--warn)}'
		h << '.pairblock.warn>summary{background:var(--warn-soft);color:var(--warn)}'
		h << '.pairblock.warn .sev{background:var(--warn);color:#fff;border-color:var(--warn)}'
		h << '.pairblock.warn .tally{color:var(--warn);border-color:var(--warn)}'
		h << '.pairblock.info{border-left-color:var(--line-strong)}'
		h << '.pairblock.info>summary{background:var(--sunk);color:var(--muted)}'

		h << 'table.difftable{border-collapse:collapse;width:100%;font-size:12.5px;margin:0 0 12px}'
		h << '.difftable th,.difftable td{border-bottom:1px solid var(--line);padding:6px 10px;text-align:left;vertical-align:top}'
		h << '.difftable th{background:var(--sunk);font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.difftable td:first-child{font-variant-numeric:tabular-nums;color:var(--muted)}'

		// breadcrumb, previous/next, jump
		h << '.topbar{position:sticky;top:0;z-index:30;background:var(--ground);border-bottom:1px solid var(--line);'
		h << 'padding:10px 0;margin-bottom:-16px;display:flex;flex-wrap:wrap;gap:8px 18px;align-items:center;justify-content:space-between}'
		h << 'nav.crumbs{display:flex;flex-wrap:wrap;align-items:center;gap:8px;font-size:12px;color:var(--muted)}'
		h << 'nav.crumbs a{color:var(--accent);text-decoration:none}nav.crumbs a:hover{text-decoration:underline}'
		h << 'nav.crumbs>*+*:before{content:"\\203A";margin-right:8px;color:var(--line-strong)}'
		h << '.pagenav{display:flex;flex-wrap:wrap;align-items:center;gap:10px}'
		h << '.navbtn{font-size:13px;text-decoration:none;border:1px solid var(--line-strong);border-radius:6px;'
		h << 'padding:5px 11px;background:var(--surface);white-space:nowrap}'
		h << 'a.navbtn:hover{background:var(--accent-soft)}'
		h << '.navbtn.off{color:var(--muted);opacity:.45}'
		h << '.navcount{font-size:12px;color:var(--muted);font-variant-numeric:tabular-nums;white-space:nowrap}'
		h << '.jump{display:flex;align-items:center;gap:6px;font-size:11px;letter-spacing:.08em;'
		h << 'text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.pagejump{font:inherit;font-size:13px;text-transform:none;letter-spacing:0;color:var(--ink);'
		h << 'background:var(--surface);border:1px solid var(--line-strong);border-radius:6px;padding:5px 8px;max-width:340px}'
		h << '.pagefoot{display:flex;flex-wrap:wrap;gap:18px}'
		h << 'footer.foot{border-top:1px solid var(--line);padding-top:14px;display:flex;flex-direction:column;gap:4px}'
		h << 'kbd{font-family:var(--mono);font-size:11px;border:1px solid var(--line-strong);border-radius:4px;'
		h << 'padding:0 5px;background:var(--sunk)}'

		h << '@media (max-width:720px){body{padding:24px 10px 56px}h1{font-size:30px}'
		h << '.topbar{position:static}.pagejump{max-width:220px}}'
		// report.js opens every <details> before printing, so the evidence survives on paper
		h << '@media print{body{background:#fff;padding:0}main{gap:24px;max-width:none}'
		h << 'article.page,.scroll,.pairblock{break-inside:avoid}.topbar{display:none!important}}'
		h << '@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}'
		return h.toString()
	}
}
