package migration.checks

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil

import com.kms.katalon.core.configuration.RunConfiguration
import groovy.json.JsonOutput

import migration.AuditUtils
import migration.ContentSnapshot
import migration.FileImageComparer
import migration.UniversalTagComparer
import migration.WebActions

/**
 * Second check on a compare run: tag counts, images, and PDF files
 * (mapped URL or in-page links). Text belongs to ContentCompare.
 * Does not replace ContentTextCheck. Writes the ReportBuilder "structure" slot:
 *   Reports/parity-results/<slug>/structure.txt
 *   Reports/ContentAudit/<slug>/universal.txt       (one reason per line)
 *   Reports/ContentAudit/<slug>/universal-log.txt   (tester log — not shown in the report)
 *   Reports/ContentAudit/<slug>/tags.csv            (tag | Sitecore | AEM)
 *   Reports/ContentAudit/<slug>/images.json         (every compared picture + comparer steps)
 */
public class StructureCheck {

	@Keyword
	static void run(String liveUrl, String aemUrl) {
		liveUrl = liveUrl?.trim()
		aemUrl = aemUrl?.trim()
		if (!aemUrl && liveUrl) {
			aemUrl = AuditUtils.aemUrlForLive(liveUrl)
		}
		if (!liveUrl || !aemUrl) {
			KeywordUtil.markFailed("Cannot run structure check — liveUrl='${liveUrl}' aemUrl='${aemUrl}'")
			return
		}

		Map result
		try {
			if (isPdfPair(liveUrl, aemUrl)) {
				result = compareMappedPdfs(liveUrl, aemUrl)
			} else if (isNonHtmlDocument(aemUrl)) {
				result = skipNonPage(liveUrl, aemUrl)
			} else {
				result = compareFromSnapshotsOrBrowser(liveUrl, aemUrl)
			}
		} catch (Exception e) {
			result = [
				status : 'FAIL',
				passed : false,
				reasons: ['Error comparing pages: ' + (e.getMessage() ?: 'unknown error')],
				logLines: ['UniversalTagComparer error: ' + (e.getMessage() ?: 'unknown error')],
				structuralPercent: 0, imagesChecked: 0, imagesFailed: 0,
			]
		}
		if (result == null) {
			result = [status: 'FAIL', passed: false, reasons: ['Comparer returned no result'], logLines: []]
		}

		String status = (result.status ?: ((result.passed as boolean) ? 'PASS' : 'FAIL')).toString()
		List reasons = ((result.reasons instanceof List) ? (List) result.reasons : [])
			.collect { it?.toString()?.trim() }.findAll { it }
		if (!reasons && status != 'PASS') {
			reasons = ['Structure check failed with no reason recorded']
		}

		String summary
		if (status == 'PASS' || status == 'NOT_RUN') {
			summary = reasons ? reasons[0] : (status == 'PASS' ? 'Tag counts matched perfectly.' : 'This URL is not a page.')
		} else if (result.pdfDocument) {
			summary = reasons ? reasons[0] : 'PDF files do not match.'
		} else {
			int checked = (result.imagesChecked ?: 0) as int
			int failedPics = (result.imagesFailed ?: 0) as int
			int matchedPics = Math.max(0, checked - failedPics)
			if (checked > 0) {
				summary = matchedPics + ' of ' + checked + ' pictures look the same'
				if (failedPics > 0) summary += ('. ' + failedPics + ' do not')
			} else {
				summary = 'Structure ' + fmt(result.structuralPercent) + '%'
			}
			if (((result.pdfsChecked ?: 0) as int) > 0) {
				summary += (', PDFs ' + (result.pdfsFailed ?: 0) + '/' + result.pdfsChecked + ' failed')
			}
			summary += '.'
		}

		String outDir = AuditUtils.reportDir('ContentAudit', aemUrl)
		new File(outDir + '/universal.txt').setText(reasons.join('\n'), 'UTF-8')
		List logLines = (result.logLines instanceof List) ? (List) result.logLines : []
		new File(outDir + '/universal-log.txt').setText(logLines.join('\n'), 'UTF-8')
		writeStructureArtifacts(outDir, result)

		AuditUtils.recordResult(aemUrl, 'structure', status, summary + '\n' + reasons.join('\n'))
		KeywordUtil.logInfo('Recorded structure ' + status + ' for ' + aemUrl + ' — ' + summary)

		if (status == 'PASS') {
			KeywordUtil.markPassed(summary)
		} else if (status == 'NOT_RUN') {
			KeywordUtil.logInfo(summary)
		} else {
			KeywordUtil.markFailed(summary + ' ' + reasons.join('; '))
		}
	}

	/**
	 * Mapped URL is a PDF (or last capture recorded it as one). Do not scrape
	 * the Chrome PDF viewer — download both files and MD5-compare them.
	 */
	private static boolean isPdfPair(String liveUrl, String aemUrl) {
		if (AuditUtils.isPdfUrl(liveUrl) || AuditUtils.isPdfUrl(aemUrl)) return true
		if (ContentSnapshot.isPdfDocument(aemUrl)) return true
		File f = new File(RunConfiguration.getProjectDir() + '/Reports/parity-results/' +
			AuditUtils.slugOf(aemUrl) + '/content.txt')
		if (!f.exists()) return false
		String text = f.getText('UTF-8').toLowerCase()
		return text.startsWith('not_run') && text.contains('pdf')
	}

	private static boolean isNonHtmlDocument(String aemUrl) {
		if (ContentSnapshot.loadDocumentMeta(aemUrl, 'aem') != null) return true
		if (ContentSnapshot.loadDocumentMeta(aemUrl, 'sitecore') != null) return true
		File f = new File(RunConfiguration.getProjectDir() + '/Reports/parity-results/' +
			AuditUtils.slugOf(aemUrl) + '/content.txt')
		return f.exists() && f.getText('UTF-8').toLowerCase().startsWith('not_run')
	}

	private static Map skipNonPage(String liveUrl, String aemUrl) {
		String reason = 'This URL is not a page — it is a document. Tag and image comparison does not apply.'
		KeywordUtil.logInfo(reason)
		KeywordUtil.logInfo('Live: ' + liveUrl)
		KeywordUtil.logInfo('AEM : ' + aemUrl)
		return [
			status : 'NOT_RUN',
			passed : true,
			reasons: [reason],
			logLines: [reason, 'Live: ' + liveUrl, 'AEM : ' + aemUrl],
			structuralPercent: 0.0,
			imagesChecked: 0, imagesFailed: 0,
		]
	}

	private static Map compareMappedPdfs(String liveUrl, String aemUrl) {
		Map liveDoc = ContentSnapshot.loadDocumentMeta(aemUrl, 'sitecore') ?: AuditUtils.probeDocument(liveUrl)
		Map aemDoc = ContentSnapshot.loadDocumentMeta(aemUrl, 'aem') ?: AuditUtils.probeDocument(aemUrl)
		String liveFile = (liveDoc?.finalUrl ?: liveUrl).toString()
		String aemFile = (aemDoc?.finalUrl ?: aemUrl).toString()
		List logLines = [
			'This URL is not a page — it is a PDF',
			'Mapped Live: ' + liveUrl,
			'Mapped AEM : ' + aemUrl
		]
		if (liveFile != liveUrl) logLines << ('Live file : ' + liveFile)
		if (aemFile != aemUrl) logLines << ('AEM file  : ' + aemFile)
		logLines.each { KeywordUtil.logInfo(it) }

		Map pdf = new FileImageComparer().comparePdfFiles(liveFile, aemFile)
		if (pdf.logLines instanceof List) logLines.addAll((List) pdf.logLines)

		boolean matched = pdf.matched as boolean
		String reason = matched
			? 'This URL is not a page — it is a PDF. File contents match.'
			: ('This URL is not a page — it is a PDF. Live and AEM files do not match' +
				(pdf.error ? ' (' + pdf.error + ').' : '.'))
		return [
			status : matched ? 'PASS' : 'FAIL',
			passed : matched,
			reasons: [reason],
			logLines: logLines,
			pdfDocument: true,
			structuralPercent: matched ? 100.0 : 0.0,
			imagesChecked: 0, imagesFailed: 0,
			pdfsChecked: 1, pdfsFailed: matched ? 0 : 1,
		]
	}

	/**
	 * ContentSnapshot already wrote *.tags.json while the page was open.
	 * Prefer those files so compare is one AEM load (the content snapshot),
	 * not Live + AEM again. Only open a side when its tag file is missing.
	 */
	private static Map compareFromSnapshotsOrBrowser(String liveUrl, String aemUrl) {
		List liveTags = ContentSnapshot.loadTags(aemUrl, 'sitecore')
		List aemTags = ContentSnapshot.loadTags(aemUrl, 'aem')
		UniversalTagComparer comparer = new UniversalTagComparer()

		if (liveTags != null && aemTags != null) {
			KeywordUtil.logInfo('Structure check using tag snapshots on disk — no extra page load')
			return comparer.compareExtracted(liveTags, aemTags, liveUrl, aemUrl)
		}
		if (liveTags == null && aemTags != null) {
			KeywordUtil.logInfo('Live tag snapshot missing — opening Live only (saved for the next compare)')
			liveTags = extractFromUrl(comparer, liveUrl)
			ContentSnapshot.saveTags(aemUrl, 'sitecore', liveTags)
			return comparer.compareExtracted(liveTags, aemTags, liveUrl, aemUrl)
		}
		if (liveTags != null && aemTags == null) {
			KeywordUtil.logInfo('AEM tag snapshot missing — opening AEM only')
			aemTags = extractFromUrl(comparer, aemUrl)
			ContentSnapshot.saveTags(aemUrl, 'aem', aemTags)
			return comparer.compareExtracted(liveTags, aemTags, liveUrl, aemUrl)
		}
		KeywordUtil.logInfo('Tag snapshots missing on both sides — opening Live and AEM')
		return comparer.compareLiveAndAem(liveUrl, aemUrl)
	}

	private static List extractFromUrl(UniversalTagComparer comparer, String url) {
		WebActions.ensureOnPage(url, true)
		WebActions.scrollFullPage()
		return comparer.extractOnCurrentPage() ?: []
	}

	private static void writeStructureArtifacts(String outDir, Map result) {
		Map liveCounts = (result.liveTagCounts instanceof Map) ? (Map) result.liveTagCounts : [:]
		Map aemCounts = (result.aemTagCounts instanceof Map) ? (Map) result.aemTagCounts : [:]
		if (liveCounts || aemCounts) {
			Set tags = new TreeSet(liveCounts.keySet())
			tags.addAll(aemCounts.keySet())
			List lines = ['tag,sitecore,aem']
			tags.each { t ->
				lines << (t + ',' + liveCounts.getOrDefault(t, 0) + ',' + aemCounts.getOrDefault(t, 0))
			}
			new File(outDir + '/tags.csv').setText(lines.join('\n'), 'UTF-8')
		}

		List pairs = (result.imagePairs instanceof List) ? (List) result.imagePairs : []
		if (!pairs) return
		int failed = pairs.count { !(it.matched as boolean) } as int
		Map json = [
			checked       : result.imagesChecked ?: pairs.size(),
			failed        : result.imagesFailed ?: failed,
			sitecoreCount : result.sitecoreImages ?: 0,
			aemCount      : result.aemImages ?: 0,
			pairs         : pairs.collect { Map p ->
				[
					pair    : p.pair ?: 0,
					matched : p.matched as boolean,
					how     : (p.how ?: '').toString(),
					error   : (p.error ?: '').toString(),
					liveW   : p.liveW ?: 0,
					liveH   : p.liveH ?: 0,
					aemW    : p.aemW ?: 0,
					aemH    : p.aemH ?: 0,
					hashDist: p.hashDist ?: -1,
					liveUrl : (p.liveUrl ?: '').toString(),
					aemUrl  : (p.aemUrl ?: '').toString(),
				]
			},
		]
		new File(outDir + '/images.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(json)), 'UTF-8')
	}

	private static String fmt(Object n) {
		return String.format('%.0f', (n == null ? 0.0 : n as double))
	}
}
