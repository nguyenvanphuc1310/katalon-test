package migration.checks

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

import migration.AuditUtils
import migration.ContentSnapshot

/**
 * Content check with the SITECORE page as the source of truth, run in four modes:
 *
 *   baseline   capture the Sitecore side   -> <slug>.sitecore.json + .html
 *   capture    capture the AEM side        -> <slug>.aem.json + .html
 *   compare    capture AEM, then diff      -> findings.csv + verdict
 *   recompare  diff the snapshots on disk  -> no browser, no VPN
 *
 * Why the split: capturing is the expensive half (a full crawl), comparing is the
 * half that gets re-run whenever a matching rule changes.
 * `recompare` re-runs the whole comparison over an existing crawl in seconds.
 *
 * What counts as content is decided by ContentScope from Data Files/site-profiles.json
 * (positive scoping to a content root), and each item carries the tab it lives in.
 *
 * The verdict is strict: any MISSING_ON_AEM / WRONG_TAB / NUMBER_CHANGED / COUNT_MISMATCH /
 * TEXT_CHANGED / SCOPE_ASYMMETRY fails the page, however large the page is. Losing one line of
 * copy is losing content. Link destinations are NOT compared — this check is about text.
 */
public class ContentTextCheck {

	@Keyword
	static void run(String sitecoreurl, String pageurl, String mode) {
		if (mode == 'baseline') {
			if (ContentSnapshot.capture(sitecoreurl, pageurl, 'sitecore') == null) notAPage(pageurl)
			return
		}
		if (mode == 'capture') {
			if (ContentSnapshot.capture(pageurl, pageurl, 'aem') == null) notAPage(pageurl)
			return
		}

		if (mode != 'recompare') {
			if (ContentSnapshot.capture(pageurl, pageurl, 'aem') == null) { notAPage(pageurl); return }
		}

		Map sc = ContentSnapshot.load(pageurl, 'sitecore')
		if (sc == null) {
			// A URL that serves a document has no snapshot and never will: the baseline run
			// already established that and recorded NOT_RUN. Demanding a baseline here fails a
			// page the report correctly calls "does not apply", and sends whoever reads the
			// message off to re-run a capture that will reach the same conclusion again.
			if (recordedVerdict(pageurl) == 'NOT_RUN') { notAPage(pageurl); return }
			cannotJudge(pageurl, 'the live snapshot is missing (' +
				ContentSnapshot.pathFor(pageurl, 'sitecore', '.json') + ') — run mode=baseline first')
			return
		}
		Map aem = ContentSnapshot.load(pageurl, 'aem')
		if (aem == null) {
			cannotJudge(pageurl, 'the new snapshot is missing (' +
				ContentSnapshot.pathFor(pageurl, 'aem', '.json') + ') — run mode=capture first. ' +
				'If the capture refused to write it, the reason it gave is the finding')
			return
		}

		// Refuse to compare two snapshots that were not produced by comparable crawls. Every field
		// this reads has been written since the snapshots existed and nothing has ever read them,
		// so a @5 baseline was being compared against a @4 capture across a documented shape change
		// — and fields present on one side and absent on the other cannot be compared at all.
		String incompatible = incompatibility(sc, aem)
		if (incompatible) {
			// "0 live items compared" keeps ReportBuilder.itemsCompared()'s literal regex matching;
			// a line that does not lead with that clause loses the number in silence.
			String why = "0 live items compared: the two snapshots cannot be compared — ${incompatible}"
			AuditUtils.recordResult(pageurl, 'content', 'FAIL', why)
			// Clear the evidence as well as writing the verdict. A refusal produces no findings, but
			// the findings.csv from whatever ran last stays on disk and the report renders it under
			// the new verdict — which is how `en_lifestage` came to show OPTION_MISSING rows, from a
			// verdict retired in 2026-08-20, beneath a 2026-08-21 refusal. Stale evidence under a
			// fresh verdict is worse than no evidence.
			ContentCompare.write([findings: [], statePairs: []], AuditUtils.reportDir('ContentAudit', pageurl))
			KeywordUtil.markFailed(why + ' — re-capture both sides, then run mode=recompare')
			return
		}

		Map result = ContentCompare.diff(sc, aem)
		String outDir = AuditUtils.reportDir('ContentAudit', pageurl)
		ContentCompare.write(result, outDir)

		List errors = ((List) result.findings).findAll { ContentCompare.ERRORS.contains(it.verdict) }
		String summary = ContentCompare.summary(result)

		KeywordUtil.logInfo("${summary} — ${outDir}")
		KeywordUtil.logInfo("Snapshots: live ${sc.capturedAt} (${sc.contentRoot}) | new ${aem.capturedAt} (${aem.contentRoot})")

		if (errors.isEmpty()) {
			AuditUtils.recordResult(pageurl, 'content', 'PASS', summary)
			return
		}
		AuditUtils.recordResult(pageurl, 'content', 'FAIL', summary)
		KeywordUtil.markFailed("Content does not match the live page: ${summary} — see ${outDir}/findings.csv")
	}

	/**
	 * Record FAIL with a reason, and clear any stale evidence, when the page cannot be judged at all.
	 *
	 * This used to be `markFailedAndStop`, which stops the test case BEFORE anything is written — so a
	 * page with a missing snapshot ended up with no `content.txt` at all and the report rendered it as
	 * "not checked yet" rather than as a failure. That is the quietest possible way to lose a page:
	 * `en_lifestage` disappeared from the verdict list entirely once its unusable AEM snapshot was
	 * removed from disk. A page that cannot be judged must say so, not go missing.
	 */
	private static void cannotJudge(String pageurl, String why) {
		// The leading clause keeps ReportBuilder.itemsCompared()'s literal regex matching.
		String detail = "0 live items compared: this page could not be judged — ${why}"
		AuditUtils.recordResult(pageurl, 'content', 'FAIL', detail)
		ContentCompare.write([findings: [], statePairs: []], AuditUtils.reportDir('ContentAudit', pageurl))
		KeywordUtil.markFailed(detail)
	}

	/** Snapshots captured further apart than this are comparing two different points in time */
	static final int MAX_CAPTURE_SKEW_DAYS = 7

	/**
	 * The thin-extraction thresholds live on `ContentSnapshot` — `MIN_ITEMS` and `THIN_ROOTTEXT` —
	 * because the capture side has to apply the same rule before it writes a snapshot that this side
	 * would then refuse to read. They were duplicated here at first and drifted within a day.
	 *
	 * The pair of bounds is the whole point: `en_piliproductdeck` extracts 9 items from 80
	 * characters and is a real, tiny page; `en_lifestage.aem.json` extracts **1** item from 5,769
	 * characters and is a broken crawl, and comparing against it reported 17 texts as missing that
	 * are in the page's own HTML. Neither bound alone separates the two.
	 */

	/**
	 * Why these two snapshots cannot be compared, or '' when they can.
	 *
	 * Not a content judgement — a judgement about whether a content judgement is possible at all.
	 * A comparison run over an incompatible pair is not a weak result, it is a meaningless one, and
	 * the failure mode it produces is PASS.
	 */
	private static String incompatibility(Map sc, Map aem) {
		String scShape = shapeOf(sc), aemShape = shapeOf(aem)
		if (scShape && aemShape && scShape != aemShape) {
			return "they were taken with different collector shapes (live ${sc.profile}, new ${aem.profile}); " +
				'fields added between the two versions are absent on one side, so the checks that read them cannot fire'
		}
		if (!(sc.rootText ?: '')) return 'the live snapshot has no rootText, so there is nothing to match against'
		if (!(aem.rootText ?: '')) return 'the new snapshot has no rootText, so there is nothing to match against'

		// Same shape, both sides readable, and one of them still unusable: an item walk that died
		// early leaves a file that looks complete — it has its rootText, its profile and its
		// timestamp, just almost no items. On the NEW side that turns the page's own content into
		// findings against it; on the LIVE side it turns the page green for want of anything to ask.
		String thinSc = thinExtraction(sc, 'live'), thinAem = thinExtraction(aem, 'new')
		if (thinSc) return thinSc
		if (thinAem) return thinAem

		Integer skew = captureSkewDays(sc, aem)
		if (skew != null && skew > MAX_CAPTURE_SKEW_DAYS) {
			return "they were captured ${skew} days apart (live ${sc.capturedAt}, new ${aem.capturedAt}), " +
				"more than the ${MAX_CAPTURE_SKEW_DAYS} allowed — either side may have changed in between"
		}
		return ''
	}

	/**
	 * Why this snapshot holds too few items for the text it carries, or '' when it is judgeable.
	 *
	 * Delegates to `ContentSnapshot.thinExtractionReason` so the capture side and the compare side
	 * ask the same question with the same numbers. They were written separately and immediately
	 * disagreed: the capture gate used a bare `items < 10` floor and rejected the live capture of
	 * both product-deck pages — 9 items out of 77 and 80 characters, which is all those pages have —
	 * while this rule was already citing `en_piliproductdeck` as the example of a legitimate 9-item
	 * page. One rule, one pair of thresholds.
	 */
	private static String thinExtraction(Map snap, String sideName) {
		String why = ContentSnapshot.thinExtractionReason(
			((List) (snap.items ?: [])).size(),
			((snap.rootText ?: '').toString()).length(), sideName)
		return why ? why + ' — every text it does not carry would be reported as a loss the page has not suffered' : ''
	}

	/** The `@N` shape version off a profile id (`prudential-aem@5` -> `5`), or '' when it carries none */
	private static String shapeOf(Map snap) {
		def m = ((snap.profile ?: '').toString() =~ /@(\d+)$/)
		return m.find() ? m.group(1) : ''
	}

	/** Whole days between the two capturedAt stamps, or null when either cannot be parsed */
	private static Integer captureSkewDays(Map sc, Map aem) {
		try {
			String fmt = "yyyy-MM-dd'T'HH:mm:ss"
			Date a = Date.parse(fmt, (sc.capturedAt ?: '').toString())
			Date b = Date.parse(fmt, (aem.capturedAt ?: '').toString())
			return (int) (Math.abs(a.getTime() - b.getTime()) / (1000L * 60 * 60 * 24))
		} catch (Exception ignore) {
			return null
		}
	}

	/** The verdict recorded by an earlier run, or '' when this page has never been checked */
	private static String recordedVerdict(String pageurl) {
		File f = new File(RunConfiguration.getProjectDir() + '/Reports/parity-results/' +
			AuditUtils.slugOf(pageurl) + '/content.txt')
		return f.exists() ? (f.getText('UTF-8').readLines()[0]?.trim() ?: '') : ''
	}

	/** A mapped URL that serves a document (PDF) rather than a page: not a failure, not a pass */
	private static void notAPage(String pageurl) {
		AuditUtils.recordResult(pageurl, 'content', 'NOT_RUN',
			'Not an HTML page — the URL serves a document, so the content check does not apply')
		KeywordUtil.logInfo('Content check skipped: ' + pageurl + ' is not an HTML page')
	}
}
