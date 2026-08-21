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

		// Is this even the page that was asked for? Nothing checked before, and Selenium cannot
		// see an HTTP status, so a redirect, a soft-404 or an unpublished author page snapshotted
		// as ordinary content. That is not hypothetical: en_lifestage.aem.json was captured from
		// /content/prudential-aem-lbu/pacs/backup/test1/lifestage — a backup branch — and shipped
		// into the report with nothing anywhere saying the page was wrong.
		String wrongPage = wrongPageReason(targetUrl, scope)
		if (wrongPage) {
			KeywordUtil.markFailedAndStop("Refusing to snapshot ${targetUrl}: ${wrongPage}. " +
				'Nothing was written — fix the URL in Data Files/aem-url-mapping.csv, or publish the page.')
		}

		Map snap = [
			url: targetUrl,
			side: side,
			capturedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss"),
			profile: ContentScope.profileFor(targetUrl).id,
			// Which page actually answered, and which page it says it is. Recorded even when they
			// agree: a snapshot that cannot say where it came from cannot be audited later.
			landedUrl: scope.landedUrl,
			canonical: scope.canonical,
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
		// Gate BEFORE the write. A capture used to overwrite its snapshot unconditionally, so a
		// crawl that broke — a blocked page, a soft-404, an extraction that died early — destroyed
		// good data and the run still went green. The only guard was an items<10 warning, logged
		// AFTER the file was already on disk. That is how en_lifestage.aem.json came to hold 1 item.
		String reject = degradedAgainstPrevious(snap, jsonPath)
		if (reject) {
			String rejPath = pathFor(pageurl, side, '.rejected.json')
			new File(rejPath).setText(JsonOutput.prettyPrint(JsonOutput.toJson(snap)), 'UTF-8')
			KeywordUtil.markFailedAndStop("Refusing to overwrite ${jsonPath}: ${reject}. " +
				"The previous snapshot is untouched; the rejected capture is at ${rejPath}. " +
				'Re-run the capture — see docs/guides/running-tests.md#after-a-capture-verify-the-snapshot.')
		}
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
		// The empty-rootText and thin-items warnings that used to sit here are gone: both conditions
		// now stop the run in degradedAgainstPrevious() BEFORE the file is written, which is the only
		// place a warning about them was ever any use.
		return snap
	}

	/**
	 * Why the page that answered is not the page that was asked for, or '' when it is.
	 *
	 * Paths only. The domain legitimately differs between the two systems, AEM carries an `/en`
	 * prefix and a trailing slash where Sitecore carries neither, so the comparison reuses
	 * `ContentCompare.linkKey()` — the rule that already exists for exactly this normalization —
	 * rather than growing a third copy of it.
	 */
	private static String wrongPageReason(String targetUrl, Map scope) {
		// Whether a landed URL was RECORDED is the question, not whether its key is non-empty.
		// Those are different, and conflating them hid the commonest redirect of all: the site
		// root normalizes to an empty key, so a page that bounced to /en/ compared '' against
		// '/lifestage/retirement', was read as "no opinion", and was captured as if it were the
		// page that had been asked for.
		String landed = (scope.landedUrl ?: '').toString()
		if (landed) {
			String want = pathKeyOf(targetUrl), got = pathKeyOf(landed)
			if (want != got) {
				return "the browser ended up on ${landed} instead — a redirect, or the wrong URL in the mapping"
			}
		}
		// An AEM author path is never a published page, whatever it renders. A page with no
		// canonical at all says nothing and must pass: both productdeck pages on the live side
		// carry none.
		String canon = (scope.canonical ?: '').toString()
		if (canon.startsWith('/content/')) {
			return "the page says it is ${canon} — an unpublished author path, not the published page"
		}
		return ''
	}

	/** Comparable path of a URL: no domain, no /en prefix, no trailing slash, lowercased */
	private static String pathKeyOf(String url) {
		String p = (url ?: '').replaceFirst('^https?://[^/]+', '').split('\\?')[0].split('#')[0]
		return migration.checks.ContentCompare.linkKey(p)
	}

	/** A capture holding less than this fraction of what the previous one held is a broken crawl, not a thinner page */
	static final double DEGRADED_RATIO = 0.5

	/**
	 * A thin item count is only evidence of a broken walk when the page HAS text the walk should
	 * have found. Both bounds, never one.
	 *
	 * This started as a bare `items < 10` floor and it rejected the live capture of
	 * `en_piibproductdeck` and `en_piliproductdeck` on the first real run — 9 items out of 77 and 80
	 * characters of page text, which is the whole of those pages. A floor alone cannot tell a tiny
	 * page from a dead crawl, and it also contradicted the sibling rule in
	 * `ContentTextCheck.thinExtraction()`, which names `en_piliproductdeck` as a legitimate 9-item
	 * page. Both now ask the same question with the same numbers.
	 */
	static final int MIN_ITEMS = 10
	static final int THIN_ROOTTEXT = 1000

	/**
	 * Why this many items is too few for that much page text, or '' when it is credible.
	 * Shared with ContentTextCheck so the capture side and the compare side cannot drift apart.
	 */
	static String thinExtractionReason(int items, int rootLen, String sideName) {
		if (items >= MIN_ITEMS || rootLen < THIN_ROOTTEXT) return ''
		return "the ${sideName} capture holds ${items} item(s) against ${rootLen} characters of page " +
			'text, so its item walk died early'
	}

	/**
	 * Why the new capture must NOT replace the one already on disk, or '' when it may.
	 *
	 * Two bars. An ABSOLUTE one that every capture has to clear, so the very first crawl of a page
	 * cannot enshrine a broken extraction as the baseline; and a RELATIVE one against the previous
	 * snapshot, because what counts as a healthy item count differs per pagetype and the previous
	 * capture of THIS page is the only honest reference for it.
	 *
	 * Relative, not absolute-only, on purpose: a small pagetype legitimately extracts 12 items, and
	 * a fixed 150-item floor would reject it forever. The absolute bar is for the same reason
	 * expressed as a pair — see thinExtractionReason().
	 */
	private static String degradedAgainstPrevious(Map snap, String jsonPath) {
		int items = ((List) (snap.items ?: [])).size()
		int rootLen = ((snap.rootText ?: '').toString()).length()

		String thin = thinExtractionReason(items, rootLen, 'new')
		if (thin) return thin
		if (rootLen == 0) return 'the capture has no rootText — there is nothing to match against'

		File prev = new File(jsonPath)
		if (!prev.exists()) return ''
		Map old = null
		try {
			old = (Map) new JsonSlurper().parseText(prev.getText('UTF-8'))
		} catch (Exception e) {
			// An unreadable previous snapshot is not a reason to block a good capture
			KeywordUtil.logInfo('Could not read the previous snapshot for comparison: ' + e.getMessage())
			return ''
		}

		int oldItems = ((List) (old.items ?: [])).size()
		int oldRootLen = ((old.rootText ?: '').toString()).length()
		if (oldItems > 0 && items < oldItems * DEGRADED_RATIO) {
			return "items fell ${oldItems} -> ${items}"
		}
		if (oldRootLen > 0 && rootLen < oldRootLen * DEGRADED_RATIO) {
			return "rootText fell ${oldRootLen} -> ${rootLen} character(s)"
		}
		// scanned only exists on snapshots taken after the counter was added; compare it when both have it
		Integer scanned = scannedOf(snap), oldScanned = scannedOf(old)
		if (scanned != null && oldScanned != null && oldScanned > 0 && scanned < oldScanned * DEGRADED_RATIO) {
			return "scanned elements fell ${oldScanned} -> ${scanned}"
		}
		return ''
	}

	/** skipped.scanned, or null on a snapshot taken before that counter existed */
	private static Integer scannedOf(Map snap) {
		Object v = ((Map) (snap.skipped ?: [:])).scanned
		return v == null ? null : (v as int)
	}

	/** Load a snapshot written by capture(); null when it does not exist */
	@Keyword
	static Map load(String pageurl, String side) {
		File f = new File(pathFor(pageurl, side, '.json'))
		return f.exists() ? (Map) new JsonSlurper().parseText(f.getText('UTF-8')) : null
	}
}
