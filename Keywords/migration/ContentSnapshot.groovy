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
		// Force the reload rather than reusing whatever is open. The image check runs first in every
		// template test case and now expands every tab and accordion to reach the images inside
		// them, so the page it leaves behind is not the page a visitor sees — snapshotting it would
		// quietly compare an expanded DOM on one side against a default one on the other.
		WebActions.ensureOnPage(targetUrl, true)
		WebActions.scrollFullPage()
		Map scope = ContentScope.readPageOrFail(targetUrl)
		if (scope.notHtml) {
			KeywordUtil.logInfo("${targetUrl} is not a web page (${scope.notHtml}) — nothing to snapshot")
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
}
