package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import org.openqa.selenium.JavascriptExecutor

/**
 * Captures one side of a page to disk: the structured content items plus the
 * rendered HTML.
 *
 * Splitting capture from compare is what makes the matching rules tunable: the
 * crawl is the expensive half (1,683 pages), the comparison is the half that gets
 * re-run every time a rule changes. After a capture, ContentCompare works purely
 * from these files — no browser, and no VPN.
 *
 * The same open page also writes <slug>.<side>.tags.json (semantic tags for
 * UniversalTagComparer). StructureCheck reads those files so it does not
 * reopen Live/AEM. ContentCompare stays text-only and is not merged with Universal.
 *
 * The HTML must come from the live browser: aem-uat answers 403 to
 * HttpURLConnection, and part of the content on both sites is injected
 * client-side, so a server-side fetch would not see it anyway.
 */
public class ContentSnapshot {

	/** Data Files/baselines/<group>/<pagetype>/snapshot/<slug>.<side>.json */
	@Keyword
	static String pathFor(String pageurl, String side, String ext) {
		return AuditUtils.baselinePath('snapshot', pageurl, '.' + side + ext)
	}

	/**
	 * Navigate to targetUrl, read its content through ContentScope and write
	 * <slug>.<side>.json + <slug>.<side>.html. Returns the snapshot map.
	 */
	@Keyword
	static Map capture(String targetUrl, String pageurl, String side) {
		Map probe = AuditUtils.probeDocument(targetUrl)
		if (probe.kind == 'pdf') {
			saveDocumentMeta(pageurl, side, probe)
			KeywordUtil.logInfo("${targetUrl} is a PDF, not a web page — detected by ${probe.detectedBy}" +
				(probe.finalUrl && probe.finalUrl != targetUrl ? ", landed ${probe.finalUrl}" : '') +
				(probe.contentType ? " (${probe.contentType})" : '') +
				' — skipping HTML snapshot')
			return null
		}

		// Reload only when we are on another URL or a earlier check expanded the DOM.
		// forceReload=true used to navigate every time and opened AEM twice in one iteration.
		WebActions.ensureOnPage(targetUrl, true)
		String landed = ''
		try { landed = DriverFactory.getWebDriver().getCurrentUrl() } catch (Exception ignore) { }
		if (AuditUtils.isPdfUrl(landed)) {
			saveDocumentMeta(pageurl, side, [
				kind: 'pdf', url: targetUrl, finalUrl: landed,
				contentType: 'application/pdf', detectedBy: 'browser-redirect'
			])
			KeywordUtil.logInfo("${targetUrl} redirected in the browser to PDF ${landed} — skipping HTML snapshot")
			return null
		}

		KeywordUtil.logInfo("Capturing [${side}] — not clicking tabs or accordions. Reading the open DOM.")
		// Content text is already in main. Full-page scroll is for lazy images (structure)
		// and on Product Deck it restarts Evergage/GTM so Chrome freezes again.
		KeywordUtil.logInfo("Reading page text on ${targetUrl}")
		Map scope = ContentScope.readPageOrFail(targetUrl)
		if (scope.notHtml) {
			String ct = (scope.notHtml ?: '').toString()
			String kind = AuditUtils.isPdfContentType(ct) || ct.toLowerCase().contains('pdf') ? 'pdf' : 'document'
			saveDocumentMeta(pageurl, side, [
				kind: kind, url: targetUrl, finalUrl: landed, contentType: ct, detectedBy: 'contentType'
			])
			KeywordUtil.logInfo("${targetUrl} is not a web page (${ct}) — nothing to snapshot")
			return null
		}

		Map snap = [
			url: targetUrl,
			side: side,
			capturedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss"),
			profile: ContentScope.profileFor(targetUrl).id,
			contentRoot: scope.root,
			tabGroups: scope.tabGroups,
			skipped: scope.skipped,
			// matching text — omitting these silently degraded ContentCompare to item-join
			// matching, which is exactly the splitting problem the whole-element text fixes
			rootText: scope.rootText,
			// One entry per interactive state (tab panel, accordion section) with its own text:
			// StateMatch pairs the two sides on these, and the comparison is then scoped to the
			// matched pair instead of to a label that only one of the two sites ever uses.
			states: scope.states,
			// Dropdown contents, which the item loop cannot see because <option> never renders
			formOptions: scope.formOptions,
			items: scope.items
		]

		String jsonPath = pathFor(pageurl, side, '.json')
		new File(jsonPath).setText(JsonOutput.prettyPrint(JsonOutput.toJson(snap)), 'UTF-8')

		try {
			JavascriptExecutor js = (JavascriptExecutor) DriverFactory.getWebDriver()
			String html = (String) js.executeScript('return document.documentElement.outerHTML')
			new File(pathFor(pageurl, side, '.html')).setText(html, 'UTF-8')
		} catch (Exception e) {
			KeywordUtil.markWarning('Could not store the rendered HTML: ' + e.getMessage())
		}

		// Same open page: tag list for UniversalTagComparer so compare does not navigate again.
		try {
			List tags = new UniversalTagComparer().extractOnCurrentPage()
			saveTags(pageurl, side, tags)
			KeywordUtil.logInfo("Snapshot [${side}] ${tags ? tags.size() : 0} semantic tag(s) -> " +
				pathFor(pageurl, side, '.tags.json'))
		} catch (Exception e) {
			KeywordUtil.markWarning('Could not store semantic tags: ' + e.getMessage())
		}

		int tabs = (snap.tabGroups as List).sum { ((Map) it).tabs.size() } ?: 0
		KeywordUtil.logInfo("Snapshot [${side}] ${scope.items.size()} items, ${snap.tabGroups.size()} tab group(s)/${tabs} tab(s), " +
			"root=${scope.root}, dropped ${scope.skipped} -> ${jsonPath}")
		if (!snap.rootText) {
			KeywordUtil.markWarning('Snapshot has no rootText — matching would fall back to joined items')
		}
		if ((scope.items as List).size() < 10) {
			KeywordUtil.markWarning("Only ${scope.items.size()} content items on ${targetUrl} — verify the page rendered")
		}
		return snap
	}

	/** Load a snapshot written by capture(); null when it does not exist */
	@Keyword
	static Map load(String pageurl, String side) {
		File f = new File(pathFor(pageurl, side, '.json'))
		return f.exists() ? (Map) new JsonSlurper().parseText(f.getText('UTF-8')) : null
	}

	/** Semantic tags written beside the snapshot; null when this capture predates tag extract */
	@Keyword
	static List loadTags(String pageurl, String side) {
		File f = new File(pathFor(pageurl, side, '.tags.json'))
		if (!f.exists()) return null
		return (List) new JsonSlurper().parseText(f.getText('UTF-8'))
	}

	@Keyword
	static void saveTags(String pageurl, String side, List tags) {
		new File(pathFor(pageurl, side, '.tags.json')).setText(
			JsonOutput.prettyPrint(JsonOutput.toJson(tags ?: [])), 'UTF-8')
	}

	/** Written when the mapped URL is a PDF or other non-HTML document */
	@Keyword
	static Map loadDocumentMeta(String pageurl, String side) {
		File f = new File(pathFor(pageurl, side, '.doc.json'))
		if (!f.exists()) return null
		return (Map) new JsonSlurper().parseText(f.getText('UTF-8'))
	}

	@Keyword
	static void saveDocumentMeta(String pageurl, String side, Map meta) {
		new File(pathFor(pageurl, side, '.doc.json')).setText(
			JsonOutput.prettyPrint(JsonOutput.toJson(meta ?: [:])), 'UTF-8')
	}

	@Keyword
	static boolean isPdfDocument(String pageurl) {
		if (AuditUtils.isPdfUrl(pageurl)) return true
		Map aem = loadDocumentMeta(pageurl, 'aem')
		Map live = loadDocumentMeta(pageurl, 'sitecore')
		return aem?.kind == 'pdf' || live?.kind == 'pdf'
	}
}
