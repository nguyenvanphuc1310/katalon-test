package migration.checks

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

import migration.AuditUtils
import migration.ContentEvidence
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
 * The verdict is strict: any MISSING_ON_AEM / WRONG_TAB / NUMBER_CHANGED / LINK_CHANGED
 * fails the page, however large the page is. Losing one button is losing content.
 */
public class ContentTextCheck {

	@Keyword
	static void run(String sitecoreurl, String pageurl, String mode) {
		if (mode == 'baseline') {
			if (ContentSnapshot.capture(sitecoreurl, pageurl, 'sitecore') == null) notAPage(pageurl, 'sitecore')
			return
		}
		if (mode == 'capture') {
			if (ContentSnapshot.capture(pageurl, pageurl, 'aem') == null) notAPage(pageurl, 'aem')
			return
		}

		if (mode != 'recompare') {
			Map scEarly = ContentSnapshot.load(pageurl, 'sitecore')
			if (scEarly == null) {
				if (recordedVerdict(pageurl) == 'NOT_RUN' || ContentSnapshot.isPdfDocument(pageurl)) {
					notAPage(pageurl, 'aem')
					return
				}
				KeywordUtil.markFailedAndStop('Sitecore snapshot missing: ' +
					ContentSnapshot.pathFor(pageurl, 'sitecore', '.json') +
					' — run mode=baseline first. AEM was not opened.')
			}
			if (ContentSnapshot.capture(pageurl, pageurl, 'aem') == null) { notAPage(pageurl, 'aem'); return }
		}

		Map sc = ContentSnapshot.load(pageurl, 'sitecore')
		if (sc == null) {
			if (recordedVerdict(pageurl) == 'NOT_RUN') { notAPage(pageurl, 'aem'); return }
			KeywordUtil.markFailedAndStop('Sitecore snapshot missing: ' +
				ContentSnapshot.pathFor(pageurl, 'sitecore', '.json') + ' — run mode=baseline first')
		}
		Map aem = ContentSnapshot.load(pageurl, 'aem')
		if (aem == null) {
			KeywordUtil.markFailedAndStop('AEM snapshot missing: ' +
				ContentSnapshot.pathFor(pageurl, 'aem', '.json') + ' — run mode=capture first')
		}

		Map result = ContentCompare.diff(sc, aem)
		String outDir = AuditUtils.reportDir('ContentAudit', pageurl)
		ContentCompare.write(result, outDir)

		if (mode != 'recompare' && ((List) result.findings)) {
			try {
				ContentEvidence.capture(sitecoreurl, pageurl, (List) result.findings)
			} catch (Exception e) {
				KeywordUtil.markWarning('Evidence screenshots skipped: ' + (e.getMessage() ?: 'unknown error'))
			}
		}

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

	/** The verdict recorded by an earlier run, or '' when this page has never been checked */
	private static String recordedVerdict(String pageurl) {
		File f = new File(RunConfiguration.getProjectDir() + '/Reports/parity-results/' +
			AuditUtils.slugOf(pageurl) + '/content.txt')
		return f.exists() ? (f.getText('UTF-8').readLines()[0]?.trim() ?: '') : ''
	}

	/** A mapped URL that serves a document (PDF) rather than a page: not a failure, not a pass */
	private static void notAPage(String pageurl, String side) {
		Map doc = ContentSnapshot.loadDocumentMeta(pageurl, side)
		boolean pdf = (doc?.kind == 'pdf') || AuditUtils.isPdfUrl(pageurl) ||
			((doc?.contentType ?: '').toString().toLowerCase().contains('pdf'))
		String detail = pdf
			? 'This URL is not a page — it is a PDF. The content text check does not apply.'
			: 'Not an HTML page — the URL serves a document, so the content check does not apply'
		AuditUtils.recordResult(pageurl, 'content', 'NOT_RUN', detail)
		KeywordUtil.logInfo('Content check skipped: ' + pageurl + ' — ' + detail)
	}
}
